package be.ehealth.hub.simulator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class RetrieveDocumentOperationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private FhirContext fhirContext;
    private IParser jsonParser;

    @BeforeEach
    public void setup() {
        fhirContext = FhirContext.forR4();
        jsonParser = fhirContext.newJsonParser();
    }

    private String getBaseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("ITI-68: POST $retrieve-document returns complete Laboratory Report Document Bundle")
    public void testRetrieveLabReportDocumentBundle() {
        String requestJson = """
            {
              "resourceType": "Parameters",
              "parameter": [
                {
                  "name": "documentReference",
                  "valueReference": {
                    "reference": "DocumentReference/DocRefLabReportContainedExample"
                  }
                }
              ]
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        headers.set("Accept", "application/fhir+json; fhirVersion=4.0");

        HttpEntity<String> request = new HttpEntity<>(requestJson, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/$retrieve-document", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = jsonParser.parseResource(Bundle.class, response.getBody());
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
        assertThat(bundle.getEntry()).isNotEmpty();
        assertThat(bundle.getEntryFirstRep().getResource().getResourceType().name()).isEqualTo("Composition");
    }

    @Test
    @DisplayName("ITI-68: POST $retrieve-document returns complete Telemonitoring Document Bundle")
    public void testRetrieveTelemonitoringDocumentBundle() {
        String requestJson = """
            {
              "resourceType": "Parameters",
              "parameter": [
                {
                  "name": "documentReference",
                  "valueReference": {
                    "reference": "DocumentReference/DocRefTelemonitoringExample"
                  }
                }
              ]
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        headers.set("Accept", "application/fhir+json; fhirVersion=4.0");

        HttpEntity<String> request = new HttpEntity<>(requestJson, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/$retrieve-document", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = jsonParser.parseResource(Bundle.class, response.getBody());
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
        assertThat(bundle.getEntryFirstRep().getResource().getResourceType().name()).isEqualTo("Composition");
    }

    @Test
    @DisplayName("ITI-68: Content Negotiation for rendered PDF (Accept: application/pdf)")
    public void testRetrieveRenderedPdf() {
        String requestJson = """
            {
              "resourceType": "Parameters",
              "parameter": [
                {
                  "name": "documentReference",
                  "valueReference": {
                    "reference": "DocumentReference/DocRefLabReportContainedExample"
                  }
                }
              ]
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        headers.set("Accept", "application/pdf");

        HttpEntity<String> request = new HttpEntity<>(requestJson, headers);
        ResponseEntity<byte[]> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/$retrieve-document", request, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/pdf");
        assertThat(response.getBody()).isNotNull();

        String pdfHeader = new String(response.getBody(), 0, 5, StandardCharsets.ISO_8859_1);
        assertThat(pdfHeader).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("ITI-68: Withdrawn document returns HTTP 410 Gone with OperationOutcome")
    public void testRetrieveWithdrawnDocument() {
        String requestJson = """
            {
              "resourceType": "Parameters",
              "parameter": [
                {
                  "name": "documentReference",
                  "valueReference": {
                    "reference": "DocumentReference/withdrawn"
                  }
                }
              ]
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));

        HttpEntity<String> request = new HttpEntity<>(requestJson, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/$retrieve-document", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        OperationOutcome outcome = jsonParser.parseResource(OperationOutcome.class, response.getBody());
        assertThat(outcome.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
    }

    @Test
    @DisplayName("ITI-68: Non-existent document returns HTTP 404 Not Found with OperationOutcome")
    public void testRetrieveNonExistentDocument() {
        String requestJson = """
            {
              "resourceType": "Parameters",
              "parameter": [
                {
                  "name": "documentReference",
                  "valueReference": {
                    "reference": "DocumentReference/non-existent-id"
                  }
                }
              ]
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));

        HttpEntity<String> request = new HttpEntity<>(requestJson, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/$retrieve-document", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        OperationOutcome outcome = jsonParser.parseResource(OperationOutcome.class, response.getBody());
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.NOTFOUND);
    }

    @Test
    @DisplayName("Direct read: GET /fhir/Bundle/BundleLabReportExample")
    public void testDirectBundleRead() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                getBaseUrl() + "/fhir/Bundle/BundleLabReportExample", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = jsonParser.parseResource(Bundle.class, response.getBody());
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
    }

    @Test
    @DisplayName("Direct read: GET /fhir/DocumentReference/DocRefLabReportContainedExample")
    public void testDirectDocumentReferenceRead() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                getBaseUrl() + "/fhir/DocumentReference/DocRefLabReportContainedExample", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DocumentReference docRef = jsonParser.parseResource(DocumentReference.class, response.getBody());
        assertThat(docRef.getIdPart()).isEqualTo("DocRefLabReportContainedExample");
    }
}
