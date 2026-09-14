package be.ehealth.hub.simulator.web;

import be.ehealth.hub.simulator.service.DocumentRepository;
import be.ehealth.hub.simulator.util.Outcomes;
import ca.uhn.fhir.context.FhirContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Front door of the simulator. It runs before HAPI and does three things:
 *
 * <ol>
 *   <li><b>Confines the server to the two Interhub transactions.</b> Only {@code /metadata},
 *       {@code /DocumentReference/_search} (plus the GET search form kept for generic MHD
 *       conformance testing) and {@code /DocumentReference/$retrieve-document} are routed;
 *       anything else answers 404 with an OperationOutcome naming the two transactions.</li>
 *   <li><b>Accepts un-prefixed paths</b> — {@code /DocumentReference/...} is served as
 *       {@code /fhir/DocumentReference/...}.</li>
 *   <li><b>Streams the hub-rendered PDF</b> for {@code Accept: application/pdf} on
 *       {@code $retrieve-document}, which FHIR requires to be returned as raw binary content
 *       rather than as a serialised resource (transactions.md §3.4).</li>
 * </ol>
 */
public class InterhubGatewayFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InterhubGatewayFilter.class);

    private static final String FHIR_PREFIX = "/fhir";
    private static final String METADATA = "/metadata";
    private static final String DOCUMENT_REFERENCE = "/DocumentReference";
    private static final String SEARCH = DOCUMENT_REFERENCE + "/_search";
    private static final String RETRIEVE = DOCUMENT_REFERENCE + "/$retrieve-document";
    private static final String OBSERVATION = "/Observation";
    private static final String OBSERVATION_SEARCH = OBSERVATION + "/_search";

    private static final String UNSUPPORTED_DIAGNOSTICS =
            "This Belgian Interhub responder serves exactly three transactions: getTransactionList — "
                    + "POST [base]/DocumentReference/_search (MHD ITI-67), getTransaction — "
                    + "POST [base]/DocumentReference/$retrieve-document, and laboratory observation search — "
                    + "POST [base]/Observation/_search. GET [base]/metadata returns the "
                    + "CapabilityStatement. No other path, resource type or interaction is available.";

    private final DocumentRepository repository;
    private final FhirContext fhirContext;

    public InterhubGatewayFilter(DocumentRepository repository, FhirContext fhirContext) {
        this.repository = repository;
        this.fhirContext = fhirContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean prefixed = path.equals(FHIR_PREFIX) || path.startsWith(FHIR_PREFIX + "/");
        String fhirPath = normalise(prefixed ? path.substring(FHIR_PREFIX.length()) : path);
        String method = request.getMethod();

        switch (fhirPath) {
            case METADATA -> {
                if (!"GET".equals(method) && !"OPTIONS".equals(method)) {
                    methodNotAllowed(response, "GET");
                    return;
                }
            }
            case SEARCH -> {
                if (!"POST".equals(method)) {
                    methodNotAllowed(response, "POST");
                    return;
                }
                if (wantsPdf(request)) {
                    // The discovery response is always a FHIR searchset; PDF only exists on retrieval.
                    notAcceptable(response, "getTransactionList answers a FHIR searchset Bundle. "
                            + "application/pdf is only available on $retrieve-document.");
                    return;
                }
            }
            case RETRIEVE -> {
                if (!"POST".equals(method)) {
                    methodNotAllowed(response, "POST");
                    return;
                }
                if (wantsPdf(request)) {
                    streamRenderedPdf(request, response);
                    return;
                }
            }
            case DOCUMENT_REFERENCE -> {
                // GET search is tolerated for generic MHD conformance testing; POST here would be a create.
                if (!"GET".equals(method)) {
                    methodNotAllowed(response, "GET");
                    return;
                }
            }
            case OBSERVATION_SEARCH -> {
                if (!"POST".equals(method)) {
                    methodNotAllowed(response, "POST");
                    return;
                }
                if (wantsPdf(request)) {
                    notAcceptable(response, "Laboratory observation search answers a FHIR searchset Bundle. "
                            + "application/pdf is only available on $retrieve-document.");
                    return;
                }
            }
            case OBSERVATION -> {
                methodNotAllowed(response, "POST");
                return;
            }
            default -> {
                unsupported(response, method, fhirPath);
                return;
            }
        }

        if (prefixed) {
            chain.doFilter(request, response);
        } else {
            request.getRequestDispatcher(FHIR_PREFIX + fhirPath).forward(request, response);
        }
    }

    private static String normalise(String path) {
        if (path.isEmpty()) {
            return "/";
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static boolean wantsPdf(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase().contains("application/pdf");
    }

    /**
     * Answers {@code $retrieve-document} with the hub's own PDF rendering as a raw stream.
     * Withdrawn (410) and unknown (404) documents keep the same status codes they have on the
     * structured payload, so a consumer never sees a different error just because it asked for
     * a different representation.
     */
    private void streamRenderedPdf(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String reference = referenceFromBody(request).orElseGet(() -> request.getParameter("documentReference"));

        if (reference == null || reference.isBlank()) {
            writeOutcome(response, HttpServletResponse.SC_BAD_REQUEST, Outcomes.error(
                    OperationOutcome.IssueType.REQUIRED,
                    "Mandatory parameter 'documentReference' is missing. Supply a Parameters resource with "
                            + "parameter[name=documentReference].valueReference."));
            return;
        }

        if (repository.isWithdrawn(reference)) {
            writeOutcome(response, HttpServletResponse.SC_GONE, Outcomes.error(
                    OperationOutcome.IssueType.NOTFOUND,
                    "The hub knew this document but its source system has withdrawn it. Clear any stale "
                            + "bookmark rather than retrying."));
            return;
        }

        byte[] pdfBytes = repository.findPdfForReference(reference).orElse(null);
        if (pdfBytes == null) {
            if (repository.findDocumentReference(reference).isEmpty()) {
                writeOutcome(response, HttpServletResponse.SC_NOT_FOUND, Outcomes.error(
                        OperationOutcome.IssueType.NOTFOUND,
                        "The requested document uniqueId does not exist, or is no longer served by this hub."));
                return;
            }
            notAcceptable(response, "This hub publishes no PDF rendering for document reference '" + reference
                    + "'. Retrieve the structured document Bundle instead.");
            return;
        }

        log.info("getTransaction ($retrieve-document): streaming hub-rendered PDF for '{}' ({} bytes)",
                reference, pdfBytes.length);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/pdf");
        response.setContentLength(pdfBytes.length);
        response.setHeader("Content-Disposition", "inline; filename=\"document.pdf\"");
        response.getOutputStream().write(pdfBytes);
        response.getOutputStream().flush();
    }

    private Optional<String> referenceFromBody(HttpServletRequest request) throws IOException {
        byte[] body = IOUtils.toByteArray(request.getInputStream());
        if (body.length == 0) {
            return Optional.empty();
        }
        try {
            Parameters parameters = fhirContext.newJsonParser()
                    .parseResource(Parameters.class, new String(body, StandardCharsets.UTF_8));
            for (Parameters.ParametersParameterComponent parameter : parameters.getParameter()) {
                if (!"documentReference".equals(parameter.getName())) {
                    continue;
                }
                if (parameter.getValue() instanceof Reference reference) {
                    if (reference.hasReference()) {
                        return Optional.of(reference.getReference());
                    }
                    if (reference.hasIdentifier() && reference.getIdentifier().hasValue()) {
                        return Optional.of(reference.getIdentifier().getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse the $retrieve-document body as Parameters: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private void unsupported(HttpServletResponse response, String method, String path) throws IOException {
        log.warn("Rejected unsupported interaction: {} {}", method, path);
        writeOutcome(response, HttpServletResponse.SC_NOT_FOUND,
                Outcomes.error(OperationOutcome.IssueType.NOTSUPPORTED, UNSUPPORTED_DIAGNOSTICS,
                        "Interaction not supported by this Interhub responder"));
    }

    private void methodNotAllowed(HttpServletResponse response, String allowed) throws IOException {
        response.setHeader("Allow", allowed);
        writeOutcome(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                Outcomes.error(OperationOutcome.IssueType.NOTSUPPORTED, UNSUPPORTED_DIAGNOSTICS,
                        "HTTP method not allowed on this endpoint; use " + allowed));
    }

    private void notAcceptable(HttpServletResponse response, String diagnostics) throws IOException {
        writeOutcome(response, HttpServletResponse.SC_NOT_ACCEPTABLE,
                Outcomes.error(OperationOutcome.IssueType.NOTSUPPORTED, diagnostics));
    }

    private void writeOutcome(HttpServletResponse response, int status, OperationOutcome outcome) throws IOException {
        response.setStatus(status);
        response.setContentType("application/fhir+json;charset=UTF-8");
        fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToWriter(outcome, response.getWriter());
    }
}
