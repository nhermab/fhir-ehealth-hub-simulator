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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TransactionListSearchTest {

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
    @DisplayName("ITI-67: POST /fhir/DocumentReference/_search with application/x-www-form-urlencoded")
    public void testPostSearchSuccess() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Accept", "application/fhir+json; fhirVersion=4.0");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("patient.identifier", "https://www.ehealth.fgov.be/standards/fhir/core/NamingSystem/ssin|79080412345");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/_search", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        Bundle bundle = jsonParser.parseResource(Bundle.class, response.getBody());
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
        assertThat(bundle.getTotal()).isGreaterThanOrEqualTo(2);
        assertThat(bundle.getEntry()).isNotEmpty();

        // Verify entries have search.mode = match
        boolean foundLab = false;
        boolean foundTelemon = false;
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() instanceof DocumentReference docRef) {
                assertThat(entry.getSearch().getMode()).isEqualTo(Bundle.SearchEntryMode.MATCH);
                if (docRef.getIdPart().contains("LabReport")) foundLab = true;
                if (docRef.getIdPart().contains("Telemonitoring")) foundTelemon = true;
            }
        }
        assertThat(foundLab).isTrue();
        assertThat(foundTelemon).isTrue();
    }

    @Test
    @DisplayName("ITI-67: Filter by category (labresult)")
    public void testPostSearchFilterByCategory() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("patient.identifier", "79080412345");
        form.add("category", "labresult");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/_search", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = jsonParser.parseResource(Bundle.class, response.getBody());
        assertThat(bundle.getTotal()).isGreaterThanOrEqualTo(1);

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() instanceof DocumentReference docRef) {
                assertThat(docRef.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("labresult");
            }
        }
    }

    @Test
    @DisplayName("ITI-67: Filter by clinical LOINC type (18754-2 for Holter telemonitoring)")
    public void testPostSearchFilterByType() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("patient.identifier", "79080412345");
        form.add("type", "http://loinc.org|18754-2");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/_search", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = jsonParser.parseResource(Bundle.class, response.getBody());
        assertThat(bundle.getTotal()).isEqualTo(1);
        DocumentReference docRef = (DocumentReference) bundle.getEntryFirstRep().getResource();
        assertThat(docRef.getIdPart()).contains("Telemonitoring");
    }

    @Test
    @DisplayName("ITI-67: Missing mandatory patient.identifier returns HTTP 400 with OperationOutcome (code=required)")
    public void testPostSearchMissingPatientIdentifier() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("category", "labresult");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/_search", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        OperationOutcome outcome = jsonParser.parseResource(OperationOutcome.class, response.getBody());
        assertThat(outcome.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.REQUIRED);
    }

    @Test
    @DisplayName("ITI-67: Malformed patient.identifier returns HTTP 400 with OperationOutcome (code=value)")
    public void testPostSearchInvalidPatientIdentifier() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("patient.identifier", "12345"); // Invalid length

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/_search", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        OperationOutcome outcome = jsonParser.parseResource(OperationOutcome.class, response.getBody());
        assertThat(outcome.getIssueFirstRep().getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.ERROR);
        assertThat(outcome.getIssueFirstRep().getCode()).isEqualTo(OperationOutcome.IssueType.VALUE);
    }

    @Test
    @DisplayName("ITI-67: Simulated downstream partial failure returns searchset with OperationOutcome (search.mode=outcome)")
    public void testPostSearchSimulatePartialFailure() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("X-Simulate-Partial-Failure", "true");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("patient.identifier", "79080412345");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/fhir/DocumentReference/_search", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = jsonParser.parseResource(Bundle.class, response.getBody());

        boolean hasMatchMode = false;
        boolean hasOutcomeMode = false;
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getSearch().getMode() == Bundle.SearchEntryMode.MATCH) {
                hasMatchMode = true;
                assertThat(entry.getResource()).isInstanceOf(DocumentReference.class);
            } else if (entry.getSearch().getMode() == Bundle.SearchEntryMode.OUTCOME) {
                hasOutcomeMode = true;
                assertThat(entry.getResource()).isInstanceOf(OperationOutcome.class);
                OperationOutcome oo = (OperationOutcome) entry.getResource();
                assertThat(oo.getIssue()).isNotEmpty();
                assertThat(oo.getIssue().get(0).getSeverity()).isEqualTo(OperationOutcome.IssueSeverity.WARNING);
            }
        }
        assertThat(hasMatchMode).isTrue();
        assertThat(hasOutcomeMode).isTrue();
    }

    @Test
    @DisplayName("ITI-67: Forwarding support for POST /DocumentReference/_search (without /fhir prefix)")
    public void testForwardedPostSearch() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("patient.identifier", "79080412345");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                getBaseUrl() + "/DocumentReference/_search", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Bundle bundle = jsonParser.parseResource(Bundle.class, response.getBody());
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
        assertThat(bundle.getTotal()).isGreaterThanOrEqualTo(2);
    }
}
