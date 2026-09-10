package be.ehealth.hub.simulator;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The responder exposes exactly two transactions — getTransactionList and getTransaction — plus
 * the CapabilityStatement. Everything else is refused with an OperationOutcome that says so,
 * rather than quietly serving a resource the Implementation Guide never specified.
 */
public class InterhubSurfaceTest extends AbstractSimulatorTest {

    @ParameterizedTest(name = "GET {0} is not part of the Interhub surface")
    @ValueSource(strings = {
            "/fhir/DocumentReference/DocRefLabReportContainedExample",
            "/fhir/Bundle/BundleLabReportExample",
            "/fhir/Binary/rendered-lab-report-example-01",
            "/fhir/Patient/PatientPeeters",
            "/fhir/DocumentReference/_history",
            "/fhir/OperationDefinition/be-op-retrieve-document",
            "/Bundle/BundleLabReportExample",
    })
    public void testUnsupportedInteractionsAreRefused(String path) {
        ResponseEntity<String> response = exchange(HttpMethod.GET, path);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        OperationOutcome outcome = parse(OperationOutcome.class, response);
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.NOTSUPPORTED);
        assertThat(outcome.getIssueFirstRep().getDiagnostics())
                .contains("_search")
                .contains("$retrieve-document");
    }

    @Test
    @DisplayName("GET on $retrieve-document is refused with 405 and Allow: POST")
    public void testRetrieveDocumentRequiresPost() {
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/fhir/DocumentReference/$retrieve-document");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getFirst("Allow")).isEqualTo("POST");
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.NOTSUPPORTED);
    }

    @Test
    @DisplayName("GET on _search is refused: discovery is POST-only for Belgian consumers")
    public void testSearchEndpointRequiresPost() {
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/fhir/DocumentReference/_search");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getFirst("Allow")).isEqualTo("POST");
    }

    @Test
    @DisplayName("Creating a DocumentReference is refused: the Implementation Guide is read-only")
    public void testPublishingIsNotSupported() {
        ResponseEntity<String> response = postJson("/fhir/DocumentReference",
                "{\"resourceType\":\"DocumentReference\",\"status\":\"current\"}", FHIR_JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getFirst("Allow")).isEqualTo("GET");
    }

    @Test
    @DisplayName("GET search is still answered for generic MHD conformance testing")
    public void testGetSearchRemainsAvailableForConformanceTesting() {
        ResponseEntity<String> response = exchange(HttpMethod.GET,
                "/fhir/DocumentReference?patient.identifier=79080412345");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(Bundle.class, response).getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
    }

    @Test
    @DisplayName("A PDF is only ever offered on retrieval, never on discovery")
    public void testDiscoveryNeverAnswersPdf() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/pdf");

        ResponseEntity<String> response = postForm("/fhir/DocumentReference/_search",
                form("patient.identifier", "79080412345"), headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.NOTSUPPORTED);
    }
}
