package be.ehealth.hub.simulator.provider;

import be.ehealth.hub.simulator.config.HubSimulatorProperties;
import be.ehealth.hub.simulator.service.DocumentRepository;
import be.ehealth.hub.simulator.service.DocumentRepository.ObservationSearchResult;
import be.ehealth.hub.simulator.service.DocumentRepository.SearchFilter;
import be.ehealth.hub.simulator.service.DocumentRepository.SearchScope;
import be.ehealth.hub.simulator.service.SearchContinuationStore;
import be.ehealth.hub.simulator.util.Outcomes;
import be.ehealth.hub.simulator.util.SsinValidator;
import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.Offset;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Sort;
import ca.uhn.fhir.rest.api.SortOrderEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * Transaction 3: Laboratory Observation Search (DIGIRELAB / IHE QEDm PCC-44).
 *
 * <p>Exposes {@code POST [base]/Observation/_search}. Responding hubs serve discrete laboratory
 * results conforming to {@code BeInterhubLabObservation} extracted from laboratory reports.
 */
@Component
public class HubObservationResourceProvider implements IResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(HubObservationResourceProvider.class);

    private static final Set<String> SSIN_SYSTEMS = Set.of(
            "https://www.ehealth.fgov.be/standards/fhir/core/NamingSystem/ssin",
            "urn:oid:1.3.6.1.4.1.21297.100.1.1");

    private static final Set<String> SUPPORTED_SORTS = Set.of("date", "-date");

    private static final String SEARCH_PATH = "/Observation/_search";

    private final DocumentRepository repository;
    private final SearchContinuationStore continuationStore;
    private final HubSimulatorProperties properties;

    public HubObservationResourceProvider(DocumentRepository repository,
                                          SearchContinuationStore continuationStore,
                                          HubSimulatorProperties properties) {
        this.repository = repository;
        this.continuationStore = continuationStore;
        this.properties = properties;
    }

    @Override
    public Class<Observation> getResourceType() {
        return Observation.class;
    }

    @Search
    public Bundle search(
            @OptionalParam(name = "patient.identifier") TokenParam patientIdentifier,
            @OptionalParam(name = Observation.SP_CODE) TokenOrListParam code,
            @OptionalParam(name = Observation.SP_CATEGORY) TokenParam category,
            @OptionalParam(name = Observation.SP_DATE) DateRangeParam dateRange,
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
            filter = continuedFilter(continuation.getValue());
        } else {
            filter = newFilter(patientIdentifier, code, category, dateRange, searchType, count, offset, sortOrder(sort));
        }
        filter.setSimulatePartialFailure(partialFailureRequested && filter.getSearchScope() != SearchScope.LOCAL);

        ObservationSearchResult result = repository.searchObservations(filter);
        log.info("searchObservations (Transaction 3): {} match(es) of {} total for patient SSIN {} [scope={}]",
                result.page().size(), result.total(), filter.getPatientSsin(), filter.getSearchScope().toCode());

        return assembleSearchset(filter, result);
    }

    private SearchFilter continuedFilter(String token) {
        return continuationStore.resolve(token).orElseThrow(() -> {
            OperationOutcome outcome = Outcomes.error(OperationOutcome.IssueType.VALUE,
                    "The supplied _continuation token is unknown or expired. Re-run the original "
                            + "POST /Observation/_search query to obtain a fresh result set.");
            log.warn("searchObservations rejected: unknown or expired continuation token");
            return new InvalidRequestException("Unknown or expired continuation token", outcome);
        });
    }

    private SearchFilter newFilter(TokenParam patientIdentifier, TokenOrListParam code, TokenParam category,
                                   DateRangeParam dateRange, TokenParam searchType,
                                   Integer count, Integer offset, String sort) {
        SearchFilter filter = new SearchFilter();
        applyPatientIdentifier(filter, patientIdentifier);
        applyCode(filter, code);

        filter.setCategory(token(category));
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

    private void applyCode(SearchFilter filter, TokenOrListParam code) {
        if (code == null || code.getValuesAsQueryTokens().isEmpty()) {
            OperationOutcome outcome = Outcomes.error(OperationOutcome.IssueType.REQUIRED,
                    "Transaction 3 mandates 'code' specifying one or more LOINC analyte codes (e.g. code=http://loinc.org|1558-6).",
                    "Mandatory search parameter 'code' is missing.");
            log.warn("searchObservations rejected: mandatory code missing");
            throw new InvalidRequestException("Mandatory search parameter 'code' is missing.", outcome);
        }

        for (TokenParam tokenParam : code.getValuesAsQueryTokens()) {
            String tokenValue = token(tokenParam);
            if (tokenValue != null && !tokenValue.isBlank()) {
                filter.addCode(tokenValue);
            }
        }

        if (filter.getCodes().isEmpty()) {
            OperationOutcome outcome = Outcomes.error(OperationOutcome.IssueType.REQUIRED,
                    "Transaction 3 mandates 'code' specifying one or more LOINC analyte codes.",
                    "Mandatory search parameter 'code' is missing or blank.");
            log.warn("searchObservations rejected: mandatory code empty");
            throw new InvalidRequestException("Mandatory search parameter 'code' is missing.", outcome);
        }
    }

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
                    "Belgian Interhub Transaction 3 mandates 'patient.identifier' specifying the national SSIN / INSS.",
                    "Mandatory search parameter 'patient.identifier' is missing.");
            log.warn("searchObservations rejected: mandatory patient.identifier missing");
            throw new InvalidRequestException("Mandatory search parameter 'patient.identifier' is missing.", outcome);
        }

        String system = patientIdentifier.getSystem();
        if (system != null && !system.isBlank() && !SSIN_SYSTEMS.contains(system)) {
            String message = "Unsupported patient identifier system '" + system + "'. Use "
                    + String.join(" or ", SSIN_SYSTEMS) + ".";
            log.warn("searchObservations rejected: {}", message);
            throw new InvalidRequestException(message, Outcomes.error(OperationOutcome.IssueType.VALUE, message));
        }

        SsinValidator.ValidationResult validation =
                SsinValidator.validate(patientIdentifier.getValue(), properties.isStrictSsinChecksum());
        if (!validation.isValid()) {
            log.warn("searchObservations rejected: invalid patient.identifier - {}", validation.getErrorMessage());
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
            log.warn("searchObservations rejected: {}", message);
            return new InvalidRequestException(message, Outcomes.error(OperationOutcome.IssueType.VALUE, message));
        });
    }

    private int clampCount(Integer count) {
        if (count == null || count <= 0) {
            return properties.getDefaultPageSize();
        }
        return Math.min(count, properties.getMaxPageSize());
    }

    private String sortOrder(SortSpec sort) {
        if (sort == null || sort.getParamName() == null || sort.getParamName().isBlank()) {
            return "-date";
        }
        String requested = (sort.getOrder() == SortOrderEnum.DESC ? "-" : "") + sort.getParamName().trim();
        if (!SUPPORTED_SORTS.contains(requested)) {
            String message = "Unsupported _sort '" + requested + "'. Interhub defines -date (default) and date.";
            log.warn("searchObservations rejected: {}", message);
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

    private Bundle assembleSearchset(SearchFilter filter, ObservationSearchResult result) {
        Bundle searchset = new Bundle();
        searchset.setId(UUID.randomUUID().toString());
        searchset.setType(Bundle.BundleType.SEARCHSET);
        searchset.setTimestamp(new Date());
        searchset.setTotal(result.total());
        searchset.addLink().setRelation("self").setUrl(properties.getServerBaseUrl() + SEARCH_PATH);

        for (Observation obs : result.page()) {
            searchset.addEntry()
                    .setFullUrl(properties.getServerBaseUrl() + "/Observation/" + obs.getIdPart())
                    .setResource(obs)
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
}
