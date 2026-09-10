package be.ehealth.hub.simulator.config;

import be.ehealth.hub.simulator.service.DocumentRepository;
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
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class HapiFhirServletConfig {

    private static final Logger log = LoggerFactory.getLogger(HapiFhirServletConfig.class);

    @Bean
    public ServletRegistrationBean<HubRestfulServer> fhirServletRegistration(HubRestfulServer server) {
        ServletRegistrationBean<HubRestfulServer> registration = new ServletRegistrationBean<>(server, "/fhir/*");
        registration.setName("HubFhirServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * Filter that:
     * 1. Handles Content Negotiation for raw PDF streams on $retrieve-document when Accept: application/pdf
     * 2. Transparently forwards requests without the '/fhir' prefix (e.g. POST /DocumentReference/_search) to /fhir/...
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> fhirPathForwardingFilter(DocumentRepository repository) {
        FhirContext ctx = FhirContext.forR4();

        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                String uri = request.getRequestURI();
                String contextPath = request.getContextPath();
                String path = uri.substring(contextPath.length());
                String accept = request.getHeader("Accept");

                // Handle PDF content negotiation on $retrieve-document directly
                if ((path.endsWith("/DocumentReference/$retrieve-document") || path.endsWith("/$retrieve-document"))
                        && accept != null && accept.toLowerCase().contains("application/pdf")) {

                    log.info("Handling PDF binary content negotiation for $retrieve-document");
                    byte[] bodyBytes = IOUtils.toByteArray(request.getInputStream());
                    String refValue = null;

                    if (bodyBytes.length > 0) {
                        try {
                            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
                            Parameters params = ctx.newJsonParser().parseResource(Parameters.class, bodyStr);
                            for (Parameters.ParametersParameterComponent p : params.getParameter()) {
                                if ("documentReference".equals(p.getName())) {
                                    if (p.getValue() instanceof Reference r && r.hasReference()) {
                                        refValue = r.getReference();
                                    } else if (p.getValue() != null) {
                                        refValue = p.getValue().toString();
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Could not parse body as Parameters: {}", e.getMessage());
                        }
                    }

                    if (refValue == null) {
                        refValue = request.getParameter("documentReference");
                    }

                    if (repository.isWithdrawn(refValue)) {
                        OperationOutcome outcome = new OperationOutcome();
                        outcome.addIssue()
                                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                                .setCode(OperationOutcome.IssueType.NOTFOUND)
                                .setDiagnostics("The hub knew this document but its source system has withdrawn it.");
                        response.setStatus(HttpServletResponse.SC_GONE);
                        response.setContentType("application/fhir+json;charset=UTF-8");
                        ctx.newJsonParser().encodeResourceToWriter(outcome, response.getWriter());
                        return;
                    }

                    byte[] pdfBytes = repository.findPdfForReference(refValue).orElse(null);
                    if (pdfBytes != null) {
                        response.setContentType("application/pdf");
                        response.setContentLength(pdfBytes.length);
                        response.setHeader("Content-Disposition", "inline; filename=\"document.pdf\"");
                        response.setStatus(HttpServletResponse.SC_OK);
                        response.getOutputStream().write(pdfBytes);
                        response.getOutputStream().flush();
                        return;
                    } else {
                        OperationOutcome outcome = new OperationOutcome();
                        outcome.addIssue()
                                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                                .setCode(OperationOutcome.IssueType.NOTSUPPORTED)
                                .setDiagnostics("PDF rendering is not available for document reference '" + refValue + "'.");
                        response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
                        response.setContentType("application/fhir+json;charset=UTF-8");
                        ctx.newJsonParser().encodeResourceToWriter(outcome, response.getWriter());
                        return;
                    }
                }

                // Transparent forwarding for non-prefixed paths (e.g. /DocumentReference/_search -> /fhir/DocumentReference/_search)
                if (path.startsWith("/DocumentReference") || path.startsWith("/Bundle")
                        || path.startsWith("/Binary") || path.equals("/metadata") || path.startsWith("/metadata/")) {
                    request.getRequestDispatcher("/fhir" + path).forward(request, response);
                    return;
                }

                filterChain.doFilter(request, response);
            }
        };

        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
