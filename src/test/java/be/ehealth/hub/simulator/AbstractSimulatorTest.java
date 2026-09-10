package be.ehealth.hub.simulator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

/**
 * Shared plumbing for the HTTP tests: every one of them boots the application and talks to the
 * real endpoints, so the assertions describe wire behaviour rather than Java calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractSimulatorTest {

    protected static final String FHIR_JSON = "application/fhir+json; fhirVersion=4.0";

    private static final FhirContext FHIR_CONTEXT = FhirContext.forR4();

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected IParser parser() {
        return FHIR_CONTEXT.newJsonParser();
    }

    protected <T extends IBaseResource> T parse(Class<T> type, ResponseEntity<String> response) {
        return parser().parseResource(type, response.getBody());
    }

    // --- getTransactionList helpers ------------------------------------------------------------

    protected static MultiValueMap<String, String> form(String... keyValuePairs) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            form.add(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return form;
    }

    protected static HttpHeaders partialFailureHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulate-Partial-Failure", "true");
        return headers;
    }

    protected ResponseEntity<String> search(MultiValueMap<String, String> form) {
        return postForm("/fhir/DocumentReference/_search", form, new HttpHeaders());
    }

    protected ResponseEntity<String> search(MultiValueMap<String, String> form, HttpHeaders headers) {
        return postForm("/fhir/DocumentReference/_search", form, headers);
    }

    protected ResponseEntity<String> postForm(String path, MultiValueMap<String, String> form) {
        return postForm(path, form, new HttpHeaders());
    }

    protected ResponseEntity<String> postForm(String path, MultiValueMap<String, String> form, HttpHeaders extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(extra);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.ACCEPT, FHIR_JSON);
        return restTemplate.postForEntity(url(path), new HttpEntity<>(form, headers), String.class);
    }

    protected static List<DocumentReference> documents(Bundle bundle) {
        return bundle.getEntry().stream()
                .filter(entry -> entry.getResource() instanceof DocumentReference)
                .map(entry -> (DocumentReference) entry.getResource())
                .toList();
    }

    protected static List<String> documentIds(Bundle bundle) {
        return documents(bundle).stream().map(DocumentReference::getIdPart).toList();
    }

    // --- getTransaction helpers ----------------------------------------------------------------

    protected static String retrieveBody(String reference) {
        return """
                {
                  "resourceType": "Parameters",
                  "parameter": [
                    {
                      "name": "documentReference",
                      "valueReference": { "reference": "%s" }
                    }
                  ]
                }
                """.formatted(reference);
    }

    protected static String retrieveBodyByIdentifier(String system, String value) {
        return """
                {
                  "resourceType": "Parameters",
                  "parameter": [
                    {
                      "name": "documentReference",
                      "valueReference": {
                        "identifier": { "system": "%s", "value": "%s" }
                      }
                    }
                  ]
                }
                """.formatted(system, value);
    }

    protected ResponseEntity<String> retrieve(String body) {
        return retrieve(body, FHIR_JSON);
    }

    protected ResponseEntity<String> retrieve(String body, String accept) {
        return retrieveAt("/fhir/DocumentReference/$retrieve-document", body, accept);
    }

    protected ResponseEntity<String> retrieveAt(String path, String body, String accept) {
        return restTemplate.postForEntity(url(path), new HttpEntity<>(body, fhirJsonHeaders(accept)), String.class);
    }

    protected ResponseEntity<byte[]> retrieveBinary(String body, String accept) {
        return restTemplate.postForEntity(url("/fhir/DocumentReference/$retrieve-document"),
                new HttpEntity<>(body, fhirJsonHeaders(accept)), byte[].class);
    }

    protected ResponseEntity<String> exchange(HttpMethod method, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, FHIR_JSON);
        return restTemplate.exchange(url(path), method, new HttpEntity<>(headers), String.class);
    }

    private static HttpHeaders fhirJsonHeaders(String accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        headers.set(HttpHeaders.ACCEPT, accept);
        return headers;
    }
}
