package be.ehealth.hub.simulator.provider;

import be.ehealth.hub.simulator.config.HubSimulatorProperties;
import be.ehealth.hub.simulator.service.DocumentRepository;
import be.ehealth.hub.simulator.service.DocumentRepository.SearchFilter;
import be.ehealth.hub.simulator.service.DocumentRepository.SearchResult;
import be.ehealth.hub.simulator.service.DocumentRepository.SearchScope;
import be.ehealth.hub.simulator.service.SearchContinuationStore;
import be.ehealth.hub.simulator.util.Outcomes;
import be.ehealth.hub.simulator.util.SsinValidator;
import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.Offset;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Sort;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * The two Interhub transactions, and nothing else.
 *
 * <ul>
 *   <li>{@code getTransactionList} — MHD ITI-67, {@code POST [base]/DocumentReference/_search}</li>
 *   <li>{@code getTransaction} — {@code POST [base]/DocumentReference/$retrieve-document}</li>
 * </ul>
 *
 * <p>No read, create, update, delete or history interaction is declared: the Implementation Guide
 * is read-only and deliberately exposes discovery and retrieval only.
 */
@Component
public class HubDocumentReferenceResourceProvider implements IResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(HubDocumentReferenceResourceProvider.class);

    /** The two national SSIN identifier systems a consumer may use (conventions in the IG). */
    private static final Set<String> SSIN_SYSTEMS = Set.of(
            "https://www.ehealth.fgov.be/standards/fhir/core/NamingSystem/ssin",
            "urn:oid:1.3.6.1.4.1.21297.100.1.1");

    private static final Set<String> SUPPORTED_SORTS = Set.of("date", "-date");

    private static final String SEARCH_PATH = "/DocumentReference/_search";

    private final DocumentRepository repository;
    private final SearchContinuationStore continuationStore;
    private final HubSimulatorProperties properties;

    public HubDocumentReferenceResourceProvider(DocumentRepository repository,
                                                SearchContinuationStore continuationStore,
                                                HubSimulatorProperties properties) {
        this.repository = repository;
        this.continuationStore = continuationStore;
        this.properties = properties;
    }

    @Override
    public Class<DocumentReference> getResourceType() {
        return DocumentReference.class;
    }

    // -------------------------------------------------------------------------------------------
    // Transaction 1 — getTransactionList (MHD ITI-67 Find DocumentReferences)
    // -------------------------------------------------------------------------------------------

    @Search
    public Bundle search(
            @OptionalParam(name = "patient.identifier") TokenParam patientIdentifier,
            @OptionalParam(name = DocumentReference.SP_CATEGORY) TokenParam category,
            @OptionalParam(name = DocumentReference.SP_TYPE) TokenParam type,
            @OptionalParam(name = DocumentReference.SP_DATE) DateRangeParam dateRange,
            @OptionalParam(name = "author.identifier") TokenParam authorIdentifier,
            @OptionalParam(name = DocumentReference.SP_STATUS) TokenParam status,
            @OptionalParam(name = DocumentReference.SP_RES_ID) TokenParam id,
            @OptionalParam(name = DocumentReference.SP_IDENTIFIER) TokenParam identifier,
            @OptionalParam(name = "searchtype") TokenParam searchType,
            @OptionalParam(name = "_continuation") TokenParam continuation,
            @OptionalParam(name = "_simulatePartialFailure") TokenParam simulatePartialFailure,
            @Count Integer count,
            @Offset Integer offset,
            @Sort SortSpec sort,
            ServletRequestDetails requestDetails
    ) {
        boolean partialFailureRequested = isPartialFailureRequested(simulatePartialFailure, requestDetails);

        SearchFilter filter;
        if (continuation != null && continuation.getValue() != null && !continuation.getValue().isBlank()) {
            // An opaque token replays the stored query verbatim; every other parameter is ignored.
            filter = continuedFilter(continuation.getValue());
        } else {
            filter = newFilter(patientIdentifier, category, type, dateRange, authorIdentifier, status, id, identifier,
                    searchType, count, offset, sortOrder(sort));
        }
        filter.setSimulatePartialFailure(partialFailureRequested && filter.getSearchScope() != SearchScope.LOCAL);

        SearchResult result = repository.searchDocumentReferences(filter);
        log.info("getTransactionList (ITI-67): {} match(es) of {} total for patient SSIN {} [scope={}]",
                result.page().size(), result.total(), filter.getPatientSsin(), filter.getSearchScope().toCode());

        return assembleSearchset(filter, result);
    }

    /** Replays the query stored behind an opaque continuation token. */
    private SearchFilter continuedFilter(String token) {
        return continuationStore.resolve(token).orElseThrow(() -> {
            OperationOutcome outcome = Outcomes.error(OperationOutcome.IssueType.VALUE,
                    "The supplied _continuation token is unknown or expired. Re-run the original "
                            + "POST /DocumentReference/_search query to obtain a fresh result set.");
            log.warn("getTransactionList rejected: unknown or expired continuation token");
            return new InvalidRequestException("Unknown or expired continuation token", outcome);
        });
    }

    private SearchFilter newFilter(TokenParam patientIdentifier, TokenParam category, TokenParam type,
                                   DateRangeParam dateRange, TokenParam authorIdentifier, TokenParam status,
                                   TokenParam id, TokenParam identifier, TokenParam searchType,
                                   Integer count, Integer offset, String sort) {
        SearchFilter filter = new SearchFilter();
        applyPatientIdentifier(filter, patientIdentifier);

        filter.setCategory(token(category));
        filter.setType(token(type));
        filter.setAuthorIdentifier(token(authorIdentifier));
        filter.setIdentifier(token(identifier));
        if (id != null) {
            filter.setId(id.getValue());
        }

        // transactions.md §2.2: status defaults to current when the consumer does not filter on it.
        if (status != null && status.getValue() != null && !status.getValue().isBlank()) {
            filter.setStatus(status.getValue());
        }

        filter.setSearchScope(parseSearchScope(searchType));
        filter.setSort(sort);
        filter.setCount(clampCount(count));
        filter.setOffset(offset != null && offset > 0 ? offset : 0);

        if (dateRange != null) {
            filter.setDateFrom(dateRange.getLowerBoundAsInstant());
            filter.setDateTo(dateRange.getUpperBoundAsInstant());
        }
        return filter;
    }

    /** Rebuilds the {@code system|code} form so the repository can apply FHIR token matching. */
    private static String token(TokenParam param) {
        if (param == null || param.getValue() == null || param.getValue().isBlank()) {
            return null;
        }
        String system = param.getSystem();
        return system == null || system.isBlank() ? param.getValue() : system + "|" + param.getValue();
    }

    private void applyPatientIdentifier(SearchFilter filter, TokenParam patientIdentifier) {
        if (patientIdentifier == null || patientIdentifier.getValue() == null || patientIdentifier.getValue().isBlank()) {
            OperationOutcome outcome = Outcomes.error(OperationOutcome.IssueType.REQUIRED,
                    "Belgian Interhub ITI-67 mandates 'patient.identifier' specifying the national SSIN / INSS.",
                    "Mandatory search parameter 'patient.identifier' is missing.");
            log.warn("getTransactionList rejected: mandatory patient.identifier missing");
            throw new InvalidRequestException("Mandatory search parameter 'patient.identifier' is missing.", outcome);
        }

        String system = patientIdentifier.getSystem();
        if (system != null && !system.isBlank() && !SSIN_SYSTEMS.contains(system)) {
            String message = "Unsupported patient identifier system '" + system + "'. Use "
                    + String.join(" or ", SSIN_SYSTEMS) + ".";
            log.warn("getTransactionList rejected: {}", message);
            throw new InvalidRequestException(message, Outcomes.error(OperationOutcome.IssueType.VALUE, message));
        }

        SsinValidator.ValidationResult validation =
                SsinValidator.validate(patientIdentifier.getValue(), properties.isStrictSsinChecksum());
        if (!validation.isValid()) {
            log.warn("getTransactionList rejected: invalid patient.identifier - {}", validation.getErrorMessage());
            throw new InvalidRequestException(validation.getErrorMessage(),
                    Outcomes.error(OperationOutcome.IssueType.VALUE, validation.getErrorMessage()));
        }

        filter.setPatientSsin(validation.getNormalizedSsin());
        filter.setPatientIdentifierSystem(system);
    }

    private SearchScope parseSearchScope(TokenParam searchType) {
        String value = searchType != null ? searchType.getValue() : null;
        return SearchScope.parse(value).orElseThrow(() -> {
            String message = "Unsupported searchtype '" + value + "'. Use 'local' or 'federated'.";
            log.warn("getTransactionList rejected: {}", message);
            return new InvalidRequestException(message, Outcomes.error(OperationOutcome.IssueType.VALUE, message));
        });
    }

    private int clampCount(Integer count) {
        if (count == null || count <= 0) {
            return properties.getDefaultPageSize();
        }
        return Math.min(count, properties.getMaxPageSize());
    }

    /**
     * Only the two orderings the IG defines are honoured; anything else is a 400 rather than a
     * silently ignored parameter that would hand the consumer a differently ordered list.
     */
    private String sortOrder(SortSpec sort) {
        if (sort == null || sort.getParamName() == null || sort.getParamName().isBlank()) {
            return "-date";
        }
        String requested = (sort.getOrder() == SortOrderEnum.DESC ? "-" : "") + sort.getParamName().trim();
        if (!SUPPORTED_SORTS.contains(requested)) {
            String message = "Unsupported _sort '" + requested + "'. Interhub defines -date (default) and date.";
            log.warn("getTransactionList rejected: {}", message);
            throw new InvalidRequestException(message, Outcomes.error(OperationOutcome.IssueType.VALUE, message));
        }
        return requested;
    }

    private boolean isPartialFailureRequested(TokenParam simulatePartialFailure, ServletRequestDetails requestDetails) {
        if (properties.isSimulatePartialFailure()) {
            return true;
        }
        if (simulatePartialFailure != null && "true".equalsIgnoreCase(simulatePartialFailure.getValue())) {
            return true;
        }
        return requestDetails != null && requestDetails.getServletRequest() != null
                && "true".equalsIgnoreCase(requestDetails.getServletRequest().getHeader("X-Simulate-Partial-Failure"));
    }

    /**
     * Builds the searchset described in transactions.md §2.3 and §2.4: matches at
     * {@code search.mode = match}, downstream failures at {@code search.mode = outcome}, and
     * {@code Bundle.total} reporting the whole result set rather than the returned page.
     */
    private Bundle assembleSearchset(SearchFilter filter, SearchResult result) {
        Bundle searchset = new Bundle();
        searchset.setId(UUID.randomUUID().toString());
        searchset.setType(Bundle.BundleType.SEARCHSET);
        searchset.setTimestamp(new Date());
        searchset.setTotal(result.total());
        searchset.addLink().setRelation("self").setUrl(properties.getServerBaseUrl() + SEARCH_PATH);

        for (DocumentReference docRef : result.page()) {
            searchset.addEntry()
                    .setFullUrl(properties.getServerBaseUrl() + "/DocumentReference/" + docRef.getIdPart())
                    .setResource(docRef)
                    .getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }

        if (result.hasMore()) {
            String token = continuationStore.issue(filter.atOffset(result.offset() + result.page().size()));
            searchset.addLink()
                    .setRelation("next")
                    .setUrl(properties.getServerBaseUrl() + SEARCH_PATH + "?_continuation=" + token);
        }

        if (filter.isSimulatePartialFailure()) {
            repository.getPartialFailureOutcome().ifPresent(outcome -> {
                searchset.addEntry()
                        .setFullUrl("urn:uuid:" + UUID.randomUUID())
                        .setResource(outcome)
                        .getSearch().setMode(Bundle.SearchEntryMode.OUTCOME);
                log.info("Appended partial-failure OperationOutcome to the searchset (search.mode=outcome)");
            });
        }

        return searchset;
    }

    // -------------------------------------------------------------------------------------------
    // Transaction 2 — getTransaction ($retrieve-document, gatewaying MHD ITI-68)
    // -------------------------------------------------------------------------------------------

    @Operation(name = "$retrieve-document", idempotent = true, type = DocumentReference.class)
    public IBaseResource retrieveDocument(
            @OperationParam(name = "documentReference", min = 1, max = 1) Reference documentReference,
            HttpServletRequest request
    ) {
        String referenceValue = extractReferenceValue(documentReference);
        if (referenceValue == null || referenceValue.isBlank()) {
            OperationOutcome outcome = Outcomes.error(OperationOutcome.IssueType.REQUIRED,
                    "Mandatory parameter 'documentReference' is missing. Supply a Parameters resource with "
                            + "parameter[name=documentReference].valueReference.");
            throw new InvalidRequestException("Missing documentReference parameter", outcome);
        }

        log.info("getTransaction ($retrieve-document): resolving '{}'", referenceValue);

        if (repository.isWithdrawn(referenceValue)) {
            OperationOutcome outcome = Outcomes.error(OperationOutcome.IssueType.NOTFOUND,
                    "The hub knew this document but its source system has withdrawn it. Clear any stale "
                            + "bookmark rather than retrying.");
            log.warn("Document withdrawn: 410 Gone for '{}'", referenceValue);
            throw new ResourceGoneException("Document is withdrawn", outcome);
        }

        if (wantsPdf(request)) {
            return renderedPdf(referenceValue);
        }

        Bundle documentBundle = repository.findDocumentBundleForReference(referenceValue)
                .orElseThrow(() -> notFound(referenceValue));

        log.info("Returning document Bundle id='{}' (type={})", documentBundle.getIdPart(), documentBundle.getType());
        return documentBundle;
    }

    /**
     * Hub-rendered PDF (transactions.md §3.4). The raw {@code application/pdf} stream is normally
     * written by {@code InterhubGatewayFilter} before HAPI serialises anything; this path returns
     * an equivalent {@code Binary} for callers that reach the provider directly.
     */
    private Binary renderedPdf(String referenceValue) {
        byte[] pdfBytes = repository.findPdfForReference(referenceValue).orElseThrow(() -> {
            if (repository.findDocumentReference(referenceValue).isEmpty()) {
                return notFound(referenceValue);
            }
            String message = "This hub publishes no PDF rendering for document reference '" + referenceValue
                    + "'. Retrieve the structured document Bundle instead.";
            log.warn("PDF rendering unavailable: 406 Not Acceptable for '{}'", referenceValue);
            // HAPI's escape hatch for a status code it has no dedicated exception for.
            return new UnclassifiedServerFailureException(406, message,
                    Outcomes.error(OperationOutcome.IssueType.NOTSUPPORTED, message));
        });

        Binary binary = new Binary();
        binary.setContentType("application/pdf");
        binary.setData(pdfBytes);
        return binary;
    }

    private ResourceNotFoundException notFound(String referenceValue) {
        OperationOutcome outcome = Outcomes.error(OperationOutcome.IssueType.NOTFOUND,
                "The requested document uniqueId does not exist, or is no longer served by this hub.");
        log.warn("Document not found: 404 for '{}'", referenceValue);
        return new ResourceNotFoundException("Document not found", outcome);
    }

    private static boolean wantsPdf(HttpServletRequest request) {
        String accept = request != null ? request.getHeader("Accept") : null;
        return accept != null && accept.toLowerCase().contains("application/pdf");
    }

    /**
     * Accepts both a relative reference ({@code DocumentReference/id}) and a logical reference
     * carrying only {@code Reference.identifier} — the identifier-first style the IG mandates so
     * consumers never have to dereference a foreign hub.
     */
    private static String extractReferenceValue(Reference ref) {
        if (ref == null) {
            return null;
        }
        if (ref.hasReference()) {
            return ref.getReference();
        }
        if (ref.hasIdentifier() && ref.getIdentifier().hasValue()) {
            return ref.getIdentifier().getValue();
        }
        return null;
    }
}
