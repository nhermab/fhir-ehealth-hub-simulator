package be.ehealth.hub.simulator;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction 3: Laboratory Observation Search (DIGIRELAB / IHE QEDm PCC-44)
 * over POST {@code [base]/Observation/_search}.
 */
public class ObservationSearchTest extends AbstractSimulatorTest {

    private static final String SSIN_SYSTEM = "https://www.ehealth.fgov.be/standards/fhir/core/NamingSystem/ssin";
    private static final String SSIN = "79080412345";
    private static final String LOINC_GLUCOSE = "http://loinc.org|1558-6";
    private static final String LOINC_CREATININE = "http://loinc.org|2160-0";

    protected ResponseEntity<String> searchObservations(MultiValueMap<String, String> form) {
        return postForm("/fhir/Observation/_search", form, new HttpHeaders());
    }

    protected ResponseEntity<String> searchObservations(MultiValueMap<String, String> form, HttpHeaders headers) {
        return postForm("/fhir/Observation/_search", form, headers);
    }

    protected static List<Observation> observations(Bundle bundle) {
        return bundle.getEntry().stream()
                .filter(entry -> entry.getResource() instanceof Observation)
                .map(entry -> (Observation) entry.getResource())
                .toList();
    }

    protected static List<String> observationIds(Bundle bundle) {
        return observations(bundle).stream().map(Observation::getIdPart).toList();
    }

    @Test
    @DisplayName("Transaction 3: POST Observation/_search with patient.identifier and LOINC code returns matching lab observation")
    public void testPostSearchGlucoseSuccess() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", SSIN_SYSTEM + "|" + SSIN,
                "code", LOINC_GLUCOSE
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
        assertThat(bundle.hasTimestamp()).isTrue();
        assertThat(bundle.getTotal()).isEqualTo(1);
        assertThat(bundle.getEntry()).allSatisfy(entry -> {
            assertThat(entry.getSearch().getMode()).isEqualTo(Bundle.SearchEntryMode.MATCH);
            assertThat(entry.getResource()).isInstanceOf(Observation.class);
            assertThat(entry.getFullUrl()).startsWith("http://localhost:8080/fhir/Observation/");
        });

        Observation obs = observations(bundle).get(0);
        assertThat(obs.getIdPart()).isEqualTo("InterhubObsGlucoseDiscreteExample");
        assertThat(obs.getSubject().getIdentifier().getValue()).isEqualTo(SSIN);
        assertThat(obs.getCode().getCodingFirstRep().getCode()).isEqualTo("1558-6");
        assertThat(obs.getValueQuantity().getValue().intValue()).isEqualTo(92);
        assertThat(obs.getValueQuantity().getUnit()).isEqualTo("mg/dL");
        assertThat(obs.getDerivedFromFirstRep().getIdentifier().getValue())
                .isEqualTo("urn:oid:1.3.6.1.4.1.21297.100.2.1.815933567");
        assertThat(obs.getExtensionByUrl("https://www.ehealth.fgov.be/standards/fhir/interhub/StructureDefinition/be-ext-home-community-id")
                .getValue().primitiveValue()).isEqualTo("urn:oid:1.3.6.1.4.1.21297.1.3");
    }

    @Test
    @DisplayName("Transaction 3: search by bare LOINC code without system matches")
    public void testSearchByBareCode() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", SSIN,
                "code", "1558-6"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);
        assertThat(bundle.getTotal()).isEqualTo(1);
        assertThat(observationIds(bundle)).containsExactly("InterhubObsGlucoseDiscreteExample");
    }

    @Test
    @DisplayName("Transaction 3: search by creatinine code returns creatinine observation")
    public void testSearchByCreatinineCode() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", SSIN,
                "code", LOINC_CREATININE
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);
        assertThat(bundle.getTotal()).isEqualTo(1);
        assertThat(observationIds(bundle)).containsExactly("InterhubObsCreatinineDiscreteExample");
    }

    @Test
    @DisplayName("Transaction 3: search by comma-separated LOINC codes returns multiple matches")
    public void testSearchByMultipleCodes() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", SSIN,
                "code", LOINC_GLUCOSE + "," + LOINC_CREATININE
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);
        assertThat(bundle.getTotal()).isEqualTo(2);
        assertThat(observationIds(bundle)).containsExactlyInAnyOrder(
                "InterhubObsGlucoseDiscreteExample",
                "InterhubObsCreatinineDiscreteExample"
        );
    }

    @Test
    @DisplayName("Transaction 3: search with non-matching code returns 0 results")
    public void testSearchByNonMatchingCode() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", SSIN,
                "code", "http://loinc.org|99999-9"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);
        assertThat(bundle.getTotal()).isEqualTo(0);
        assertThat(observations(bundle)).isEmpty();
    }

    @Test
    @DisplayName("Transaction 3: mandatory code parameter missing is rejected with 400")
    public void testMissingCodeRejected() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", SSIN
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        OperationOutcome outcome = parse(OperationOutcome.class, response);
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.REQUIRED);
        assertThat(outcome.getIssueFirstRep().getDiagnostics()).contains("code");
    }

    @Test
    @DisplayName("Transaction 3: mandatory patient.identifier missing is rejected with 400")
    public void testMissingPatientRejected() {
        ResponseEntity<String> response = searchObservations(form(
                "code", LOINC_GLUCOSE
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        OperationOutcome outcome = parse(OperationOutcome.class, response);
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.REQUIRED);
    }

    @Test
    @DisplayName("Transaction 3: invalid SSIN format is rejected with 400")
    public void testInvalidSsinRejected() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", "12345",
                "code", LOINC_GLUCOSE
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Transaction 3: unsupported patient identifier system is rejected with 400")
    public void testUnsupportedPatientSystemRejected() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", "http://example.org/mrn|79080412345",
                "code", LOINC_GLUCOSE
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Transaction 3: unsupported _sort is rejected with 400")
    public void testUnsupportedSortRejected() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", SSIN,
                "code", LOINC_GLUCOSE,
                "_sort", "status"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Transaction 3: date filtering with ge and le bounds")
    public void testDateFiltering() {
        ResponseEntity<String> inRange = searchObservations(form(
                "patient.identifier", SSIN,
                "code", LOINC_GLUCOSE,
                "date", "ge2026-01-01"
        ));
        assertThat(parse(Bundle.class, inRange).getTotal()).isEqualTo(1);

        ResponseEntity<String> outOfRange = searchObservations(form(
                "patient.identifier", SSIN,
                "code", LOINC_GLUCOSE,
                "date", "le2025-01-01"
        ));
        assertThat(parse(Bundle.class, outOfRange).getTotal()).isEqualTo(0);
    }

    @Test
    @DisplayName("Transaction 3: local scope returns observations matching hub OID")
    public void testLocalScope() {
        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", SSIN,
                "code", LOINC_GLUCOSE,
                "searchtype", "local"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(Bundle.class, response).getTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("Transaction 3: simulated partial failure appends OperationOutcome warning")
    public void testSimulatePartialFailure() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulate-Partial-Failure", "true");

        ResponseEntity<String> response = searchObservations(form(
                "patient.identifier", SSIN,
                "code", LOINC_GLUCOSE,
                "searchtype", "federated"
        ), headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);
        assertThat(bundle.getEntry()).anySatisfy(entry -> {
            assertThat(entry.getSearch().getMode()).isEqualTo(Bundle.SearchEntryMode.OUTCOME);
            assertThat(entry.getResource()).isInstanceOf(OperationOutcome.class);
        });
    }

    @Test
    @DisplayName("Transaction 3: pagination with opaque continuation token")
    public void testPaginationWithContinuationToken() {
        ResponseEntity<String> firstPageResp = searchObservations(form(
                "patient.identifier", SSIN,
                "code", LOINC_GLUCOSE + "," + LOINC_CREATININE,
                "_count", "1"
        ));

        assertThat(firstPageResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle firstPage = parse(Bundle.class, firstPageResp);
        assertThat(firstPage.getTotal()).isEqualTo(2);
        assertThat(firstPage.getEntry()).hasSize(1);

        Bundle.BundleLinkComponent nextLink = firstPage.getLink().stream()
                .filter(link -> "next".equals(link.getRelation()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected next link"));

        URI nextUri = URI.create(nextLink.getUrl());
        String query = nextUri.getQuery();
        assertThat(query).startsWith("_continuation=");
        String token = query.substring("_continuation=".length());

        ResponseEntity<String> secondPageResp = searchObservations(form(
                "_continuation", token
        ));

        assertThat(secondPageResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle secondPage = parse(Bundle.class, secondPageResp);
        assertThat(secondPage.getEntry()).hasSize(1);
        assertThat(observationIds(secondPage).get(0)).isNotEqualTo(observationIds(firstPage).get(0));
    }

    @Test
    @DisplayName("Transaction 3: direct GET on Observation/_search or Observation is refused with 405")
    public void testObservationRequiresPost() {
        ResponseEntity<String> searchResp = exchange(HttpMethod.GET, "/fhir/Observation/_search");
        assertThat(searchResp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(searchResp.getHeaders().getFirst("Allow")).isEqualTo("POST");

        ResponseEntity<String> baseResp = exchange(HttpMethod.GET, "/fhir/Observation");
        assertThat(baseResp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(baseResp.getHeaders().getFirst("Allow")).isEqualTo("POST");

        ResponseEntity<String> readResp = exchange(HttpMethod.GET, "/fhir/Observation/InterhubObsGlucoseDiscreteExample");
        assertThat(readResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
