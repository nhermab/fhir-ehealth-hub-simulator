package be.ehealth.hub.simulator;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * getTransaction — the Belgian {@code $retrieve-document} operation gatewaying MHD ITI-68 over
 * POST {@code [base]/DocumentReference/$retrieve-document}.
 */
public class RetrieveDocumentOperationTest extends AbstractSimulatorTest {

    @Test
    @DisplayName("ITI-68: returns the laboratory document Bundle with the Composition first")
    public void testRetrieveLabReportDocumentBundle() {
        ResponseEntity<String> response = retrieve(retrieveBody("DocumentReference/DocRefLabReportContainedExample"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
        assertThat(bundle.getIdPart()).isEqualTo("BundleLabReportExample");
        assertThat(bundle.getEntryFirstRep().getResource().fhirType()).isEqualTo("Composition");
    }

    @Test
    @DisplayName("ITI-68: returns the telemonitoring document Bundle")
    public void testRetrieveTelemonitoringDocumentBundle() {
        ResponseEntity<String> response = retrieve(retrieveBody("DocumentReference/DocRefTelemonitoringExample"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = parse(Bundle.class, response);
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
        assertThat(bundle.getIdPart()).isEqualTo("BundleTelemonitoringExample");
        assertThat(bundle.getEntryFirstRep().getResource().fhirType()).isEqualTo("Composition");
    }

    @Test
    @DisplayName("ITI-68: a logical reference by business identifier resolves the same document")
    public void testRetrieveByLogicalIdentifier() {
        ResponseEntity<String> response = retrieve(retrieveBodyByIdentifier(
                "urn:ietf:rfc:3986", "urn:uuid:7ed170b3-38d1-4ba5-8a60-1f722b107707"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(Bundle.class, response).getIdPart()).isEqualTo("BundleTelemonitoringExample");
    }

    @Test
    @DisplayName("ITI-68: Accept application/pdf streams the hub rendering as raw binary")
    public void testRetrieveRenderedPdf() {
        ResponseEntity<byte[]> response = retrieveBinary(
                retrieveBody("DocumentReference/DocRefLabReportContainedExample"), "application/pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/pdf");
        assertThat(response.getBody()).isNotNull();
        assertThat(new String(response.getBody(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("ITI-68: each document is rendered as its own PDF, not a shared default")
    public void testTelemonitoringPdfIsItsOwnRendering() {
        byte[] lab = retrieveBinary(retrieveBody("DocumentReference/DocRefLabReportContainedExample"),
                "application/pdf").getBody();
        byte[] telemonitoring = retrieveBinary(retrieveBody("DocumentReference/DocRefTelemonitoringExample"),
                "application/pdf").getBody();

        assertThat(lab).isNotNull();
        assertThat(telemonitoring).isNotNull();
        assertThat(telemonitoring).isNotEqualTo(lab);
    }

    @Test
    @DisplayName("ITI-68: a document without a hub rendering answers 406 Not Acceptable")
    public void testPdfUnavailableForDocumentWithoutRendering() {
        ResponseEntity<String> response = retrieve(
                retrieveBody("DocumentReference/DocRefMinimalExample"), "application/pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        OperationOutcome outcome = parse(OperationOutcome.class, response);
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.NOTSUPPORTED);
    }

    @Test
    @DisplayName("ITI-68: a withdrawn document answers 410 Gone with an OperationOutcome")
    public void testRetrieveWithdrawnDocument() {
        ResponseEntity<String> response = retrieve(retrieveBody("DocumentReference/withdrawn"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        OperationOutcome outcome = parse(OperationOutcome.class, response);
        assertThat(outcome.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.NOTFOUND);
    }

    @Test
    @DisplayName("ITI-68: a withdrawn document answers 410 Gone for the PDF representation too")
    public void testRetrieveWithdrawnDocumentAsPdf() {
        ResponseEntity<String> response = retrieve(retrieveBody("DocumentReference/withdrawn"), "application/pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.NOTFOUND);
    }

    @Test
    @DisplayName("ITI-68: an unknown document answers 404 Not Found with an OperationOutcome")
    public void testRetrieveNonExistentDocument() {
        ResponseEntity<String> response = retrieve(retrieveBody("DocumentReference/non-existent-id"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.NOTFOUND);
    }

    @Test
    @DisplayName("ITI-68: an unknown document answers 404 for the PDF representation too")
    public void testRetrieveNonExistentDocumentAsPdf() {
        ResponseEntity<String> response = retrieve(retrieveBody("DocumentReference/non-existent-id"),
                "application/pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.NOTFOUND);
    }

    @Test
    @DisplayName("ITI-68: a Parameters body without documentReference answers 400 Bad Request")
    public void testMissingDocumentReferenceParameter() {
        ResponseEntity<String> response = retrieve("""
                { "resourceType": "Parameters", "parameter": [] }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.REQUIRED);
    }

    @Test
    @DisplayName("ITI-68: a document reference with no payload on this hub answers 404")
    public void testRetrieveDocumentWithoutPayload() {
        ResponseEntity<String> response = retrieve(retrieveBody("DocumentReference/DocRefMinimalExample"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(OperationOutcome.class, response).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.NOTFOUND);
    }

    @Test
    @DisplayName("ITI-68: the un-prefixed path POST /DocumentReference/$retrieve-document is served as well")
    public void testForwardedRetrieve() {
        ResponseEntity<String> response = retrieveAt("/DocumentReference/$retrieve-document",
                retrieveBody("DocumentReference/DocRefLabReportContainedExample"), FHIR_JSON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(Bundle.class, response).getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
    }
}
