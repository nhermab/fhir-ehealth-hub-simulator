package be.ehealth.hub.simulator;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * getTransactionList — MHD ITI-67 Find DocumentReferences over POST
 * {@code [base]/DocumentReference/_search}.
 */
public class TransactionListSearchTest extends AbstractSimulatorTest {

    private static final String SSIN_SYSTEM = "https://www.ehealth.fgov.be/standards/fhir/core/NamingSystem/ssin";
    private static final String SSIN = "79080412345";

    @Test
    @DisplayName("ITI-67: POST _search with application/x-www-form-urlencoded returns a searchset")
    public void testPostSearchSuccess() {
        ResponseEntity<String> response = search(form("patient.identifier", SSIN_SYSTEM + "|" + SSIN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
        assertThat(bundle.hasTimestamp()).isTrue();
        assertThat(bundle.getTotal()).isGreaterThanOrEqualTo(2);
        assertThat(bundle.getEntry()).allSatisfy(entry -> {
            assertThat(entry.getSearch().getMode()).isEqualTo(Bundle.SearchEntryMode.MATCH);
            assertThat(entry.getResource()).isInstanceOf(DocumentReference.class);
            assertThat(entry.getFullUrl()).startsWith("http://localhost:8080/fhir/DocumentReference/");
        });
        assertThat(documentIds(bundle)).contains("DocRefLabReportContainedExample", "DocRefTelemonitoringExample");
    }

    @Test
    @DisplayName("ITI-67: results are ordered by document date, most recent first")
    public void testDefaultSortIsMostRecentFirst() {
        Bundle descending = parse(Bundle.class, search(form("patient.identifier", SSIN)));
        Bundle ascending = parse(Bundle.class, search(form("patient.identifier", SSIN, "_sort", "date")));

        List<String> dated = documentIds(descending).stream().filter(id -> !id.equals("DocRefMinimalExample")).toList();
        assertThat(dated).startsWith("DocRefLabReportContainedExample");
        assertThat(documentIds(ascending)).isNotEqualTo(documentIds(descending));
    }

    @Test
    @DisplayName("ITI-67: unsupported _sort is rejected rather than silently ignored")
    public void testUnsupportedSortRejected() {
        ResponseEntity<String> response = search(form("patient.identifier", SSIN, "_sort", "author"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.VALUE);
    }

    @Test
    @DisplayName("ITI-67: filter by Belgian CD-TRANSACTION category")
    public void testFilterByCategory() {
        Bundle bundle = parse(Bundle.class, search(form("patient.identifier", SSIN, "category", "labresult")));

        assertThat(bundle.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(documents(bundle)).allSatisfy(docRef ->
                assertThat(docRef.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("labresult"));
    }

    @Test
    @DisplayName("ITI-67: filter by clinical LOINC type (18754-2 Holter telemonitoring)")
    public void testFilterByType() {
        Bundle bundle = parse(Bundle.class,
                search(form("patient.identifier", SSIN, "type", "http://loinc.org|18754-2")));

        assertThat(bundle.getTotal()).isEqualTo(1);
        assertThat(documentIds(bundle)).containsExactly("DocRefTelemonitoringExample");
    }

    @Test
    @DisplayName("ITI-67: a category coding of another system does not match")
    public void testCategorySystemIsHonoured() {
        Bundle bundle = parse(Bundle.class,
                search(form("patient.identifier", SSIN, "category", "http://example.org/local|labresult")));

        assertThat(bundle.getTotal()).isZero();
    }

    @Test
    @DisplayName("ITI-67: filter by document date range")
    public void testFilterByDateRange() {
        Bundle bundle = parse(Bundle.class, search(form(
                "patient.identifier", SSIN,
                "date", "ge2026-02-01",
                "date", "le2026-03-31")));

        assertThat(documentIds(bundle)).contains("DocRefLabReportContainedExample");
        assertThat(documentIds(bundle)).doesNotContain("DocRefTelemonitoringExample");
    }

    @Test
    @DisplayName("ITI-67: filter by contained author NIHDI identifier")
    public void testFilterByAuthorIdentifier() {
        Bundle bundle = parse(Bundle.class,
                search(form("patient.identifier", SSIN, "author.identifier", "10000007999")));

        assertThat(bundle.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(documentIds(bundle)).contains("DocRefLabReportContainedExample");
    }

    @Test
    @DisplayName("ITI-67: filter by universal document identifier")
    public void testFilterByIdentifier() {
        Bundle bundle = parse(Bundle.class, search(form(
                "patient.identifier", SSIN,
                "identifier", "urn:ietf:rfc:3986|urn:uuid:7ed170b3-38d1-4ba5-8a60-1f722b107707")));

        assertThat(documentIds(bundle)).containsExactly("DocRefTelemonitoringExample");
    }

    @Test
    @DisplayName("ITI-67: status defaults to current, and an explicit status is applied")
    public void testStatusDefaultsToCurrent() {
        Bundle implicit = parse(Bundle.class, search(form("patient.identifier", SSIN)));
        Bundle explicit = parse(Bundle.class, search(form("patient.identifier", SSIN, "status", "current")));
        Bundle superseded = parse(Bundle.class, search(form("patient.identifier", SSIN, "status", "superseded")));

        assertThat(documents(implicit)).allSatisfy(docRef -> assertThat(docRef.getStatus().toCode()).isEqualTo("current"));
        assertThat(implicit.getTotal()).isEqualTo(explicit.getTotal());
        assertThat(superseded.getTotal()).isZero();
    }

    @Test
    @DisplayName("ITI-67: Bundle.total counts every match while _count limits the page")
    public void testPaginationTotalAndNextLink() {
        Bundle firstPage = parse(Bundle.class, search(form("patient.identifier", SSIN, "_count", "1")));

        assertThat(firstPage.getEntry()).hasSize(1);
        assertThat(firstPage.getTotal()).isGreaterThan(1);
        assertThat(firstPage.getLink("self")).isNotNull();

        Bundle.BundleLinkComponent next = firstPage.getLink("next");
        assertThat(next).as("next link on a truncated result set").isNotNull();
        assertThat(next.getUrl()).contains("_continuation=");
        assertThat(next.getUrl()).doesNotContain(SSIN);
    }

    @Test
    @DisplayName("ITI-67: the opaque continuation token replays the query on the next page")
    public void testContinuationTokenReturnsNextPage() {
        Bundle firstPage = parse(Bundle.class, search(form("patient.identifier", SSIN, "_count", "1")));
        String token = continuationToken(firstPage);

        Bundle secondPage = parse(Bundle.class, search(form("_continuation", token)));

        assertThat(secondPage.getTotal()).isEqualTo(firstPage.getTotal());
        assertThat(secondPage.getEntry()).hasSize(1);
        assertThat(documentIds(secondPage)).isNotEqualTo(documentIds(firstPage));
    }

    @Test
    @DisplayName("ITI-67: paging through every page returns each document exactly once")
    public void testContinuationWalksTheWholeResultSet() {
        Bundle page = parse(Bundle.class, search(form("patient.identifier", SSIN, "_count", "1")));
        int total = page.getTotal();

        List<String> seen = new java.util.ArrayList<>(documentIds(page));
        while (page.getLink("next") != null) {
            page = parse(Bundle.class, search(form("_continuation", continuationToken(page))));
            seen.addAll(documentIds(page));
        }

        assertThat(seen).hasSize(total).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("ITI-67: an unknown continuation token is rejected with HTTP 400")
    public void testUnknownContinuationTokenRejected() {
        ResponseEntity<String> response = search(form("_continuation", "not-a-real-token"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.VALUE);
    }

    @Test
    @DisplayName("ITI-67: missing mandatory patient.identifier returns 400 with issue code required")
    public void testMissingPatientIdentifier() {
        ResponseEntity<String> response = search(form("category", "labresult"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        OperationOutcome outcome = parse(OperationOutcome.class, response);
        assertThat(outcome.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.REQUIRED);
    }

    @Test
    @DisplayName("ITI-67: malformed patient.identifier returns 400 with issue code value")
    public void testMalformedPatientIdentifier() {
        ResponseEntity<String> response = search(form("patient.identifier", "12345"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.VALUE);
    }

    @Test
    @DisplayName("ITI-67: a patient identifier from an unknown naming system is refused")
    public void testUnsupportedPatientIdentifierSystem() {
        ResponseEntity<String> response = search(form("patient.identifier", "http://example.org/mrn|" + SSIN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        OperationOutcome outcome = parse(OperationOutcome.class, response);
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.VALUE);
        assertThat(outcome.getIssueFirstRep().getDiagnostics()).contains("Unsupported patient identifier system");
    }

    @Test
    @DisplayName("ITI-67: the SSIN OID naming system is accepted alongside the canonical URL")
    public void testSsinOidSystemAccepted() {
        Bundle bundle = parse(Bundle.class,
                search(form("patient.identifier", "urn:oid:1.3.6.1.4.1.21297.100.1.1|" + SSIN)));

        assertThat(bundle.getTotal()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("ITI-67: simulated downstream failure adds an OperationOutcome at search.mode=outcome")
    public void testSimulatedPartialFailure() {
        ResponseEntity<String> response = search(form("patient.identifier", SSIN), partialFailureHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);

        List<Bundle.BundleEntryComponent> outcomes = bundle.getEntry().stream()
                .filter(entry -> entry.getSearch().getMode() == Bundle.SearchEntryMode.OUTCOME)
                .toList();
        assertThat(outcomes).hasSize(1);
        OperationOutcome outcome = (OperationOutcome) outcomes.get(0).getResource();
        assertThat(outcome.getIssue()).isNotEmpty();
        assertThat(outcome.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.WARNING);
        assertThat(bundle.getTotal()).as("total counts matches only, never the outcome entry")
                .isEqualTo((int) bundle.getEntry().stream()
                        .filter(entry -> entry.getSearch().getMode() == Bundle.SearchEntryMode.MATCH).count());
    }

    @Test
    @DisplayName("ITI-67: searchtype=local answers from this hub only and reports no fan-out failure")
    public void testLocalSearchTypeSuppressesFanOutFailure() {
        Bundle bundle = parse(Bundle.class,
                search(form("patient.identifier", SSIN, "searchtype", "local"), partialFailureHeaders()));

        assertThat(bundle.getTotal()).isGreaterThanOrEqualTo(2);
        assertThat(bundle.getEntry()).allSatisfy(entry ->
                assertThat(entry.getSearch().getMode()).isEqualTo(Bundle.SearchEntryMode.MATCH));
    }

    @Test
    @DisplayName("ITI-67: an unknown searchtype is rejected with HTTP 400")
    public void testUnknownSearchTypeRejected() {
        ResponseEntity<String> response = search(form("patient.identifier", SSIN, "searchtype", "global"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.VALUE);
    }

    @Test
    @DisplayName("ITI-67: the un-prefixed path POST /DocumentReference/_search is served as well")
    public void testForwardedPostSearch() {
        ResponseEntity<String> response = postForm("/DocumentReference/_search", form("patient.identifier", SSIN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(Bundle.class, response).getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
    }

    private static String continuationToken(Bundle bundle) {
        String url = bundle.getLink("next").getUrl();
        String query = URI.create(url).getQuery();
        assertThat(query).startsWith("_continuation=");
        return query.substring("_continuation=".length());
    }
}
