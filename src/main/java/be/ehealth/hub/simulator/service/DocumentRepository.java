package be.ehealth.hub.simulator.service;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.OperationOutcome;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Read-only index over the sample documents this hub serves.
 *
 * <p>The repository exposes exactly what the two Interhub transactions need:
 * {@code getTransactionList} (MHD ITI-67) searches the DocumentReference index, and
 * {@code getTransaction} ({@code $retrieve-document}) resolves a DocumentReference to its
 * document Bundle or to the hub-rendered PDF.
 */
public interface DocumentRepository {

    /** Search scope requested by the consumer through the Belgian {@code searchtype} parameter. */
    enum SearchScope {
        /** Only the responding hub's own index; no fan-out to connected sources. */
        LOCAL,
        /** The responding hub's index plus its connected sources and partner hubs (default). */
        FEDERATED;

        public static Optional<SearchScope> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.of(FEDERATED);
            }
            for (SearchScope scope : values()) {
                if (scope.name().equalsIgnoreCase(value.trim())) {
                    return Optional.of(scope);
                }
            }
            return Optional.empty();
        }

        public String toCode() {
            return name().toLowerCase();
        }
    }

    /**
     * A validated {@code getTransactionList} query. Instances are treated as immutable once
     * handed to the repository, so a continuation token can safely replay one.
     */
    class SearchFilter {
        private String patientSsin;
        private String patientIdentifierSystem;
        private String category;
        private String type;
        private Date dateFrom;
        private Date dateTo;
        private String authorIdentifier;
        private String status = "current";
        private String id;
        private String identifier;
        private SearchScope searchScope = SearchScope.FEDERATED;
        private String sort = "-date";
        private int count;
        private int offset;
        private boolean simulatePartialFailure;

        public String getPatientSsin() { return patientSsin; }
        public void setPatientSsin(String patientSsin) { this.patientSsin = patientSsin; }

        public String getPatientIdentifierSystem() { return patientIdentifierSystem; }
        public void setPatientIdentifierSystem(String patientIdentifierSystem) { this.patientIdentifierSystem = patientIdentifierSystem; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public Date getDateFrom() { return dateFrom; }
        public void setDateFrom(Date dateFrom) { this.dateFrom = dateFrom; }

        public Date getDateTo() { return dateTo; }
        public void setDateTo(Date dateTo) { this.dateTo = dateTo; }

        public String getAuthorIdentifier() { return authorIdentifier; }
        public void setAuthorIdentifier(String authorIdentifier) { this.authorIdentifier = authorIdentifier; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getIdentifier() { return identifier; }
        public void setIdentifier(String identifier) { this.identifier = identifier; }

        public SearchScope getSearchScope() { return searchScope; }
        public void setSearchScope(SearchScope searchScope) { this.searchScope = searchScope; }

        public String getSort() { return sort; }
        public void setSort(String sort) { this.sort = sort; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public int getOffset() { return offset; }
        public void setOffset(int offset) { this.offset = offset; }

        public boolean isSimulatePartialFailure() { return simulatePartialFailure; }
        public void setSimulatePartialFailure(boolean simulatePartialFailure) { this.simulatePartialFailure = simulatePartialFailure; }

        /** Shallow copy positioned at another offset, used to build continuation tokens. */
        public SearchFilter atOffset(int newOffset) {
            SearchFilter copy = new SearchFilter();
            copy.patientSsin = patientSsin;
            copy.patientIdentifierSystem = patientIdentifierSystem;
            copy.category = category;
            copy.type = type;
            copy.dateFrom = dateFrom;
            copy.dateTo = dateTo;
            copy.authorIdentifier = authorIdentifier;
            copy.status = status;
            copy.id = id;
            copy.identifier = identifier;
            copy.searchScope = searchScope;
            copy.sort = sort;
            copy.count = count;
            copy.offset = newOffset;
            copy.simulatePartialFailure = simulatePartialFailure;
            return copy;
        }
    }

    /**
     * One page of matching DocumentReferences together with the total number of matches,
     * so {@code Bundle.total} can report the whole result set rather than the page.
     */
    record SearchResult(List<DocumentReference> page, int total, int offset) {
        public boolean hasMore() {
            return offset + page.size() < total;
        }
    }

    /**
     * Execute a getTransactionList (ITI-67) query and return the requested page.
     */
    SearchResult searchDocumentReferences(SearchFilter filter);

    /**
     * Find a DocumentReference by logical id, relative reference, masterIdentifier or
     * business identifier.
     */
    Optional<DocumentReference> findDocumentReference(String referenceOrId);

    /**
     * Find the complete clinical document Bundle (type=document) for a DocumentReference.
     */
    Optional<Bundle> findDocumentBundleForReference(String referenceOrId);

    /**
     * Find the hub-rendered PDF for a DocumentReference, if this hub publishes one.
     */
    Optional<byte[]> findPdfForReference(String referenceOrId);

    /**
     * Get the sample OperationOutcome representing downstream partial failure.
     */
    Optional<OperationOutcome> getPartialFailureOutcome();

    /**
     * Check whether a document is known but withdrawn by its source system (HTTP 410 Gone).
     */
    boolean isWithdrawn(String referenceOrId);

    /**
     * Reloads all resources from the filesystem directory.
     */
    void reload();

    /**
     * Returns total count of loaded DocumentReferences.
     */
    int getDocumentReferenceCount();

    /**
     * Returns total count of loaded document Bundles.
     */
    int getDocumentBundleCount();
}
