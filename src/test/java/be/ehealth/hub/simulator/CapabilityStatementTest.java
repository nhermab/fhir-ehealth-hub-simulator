package be.ehealth.hub.simulator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CapabilityStatementTest {

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
    @DisplayName("CapabilityStatement: GET /fhir/metadata returns BeInterhubDocumentResponder")
    public void testGetMetadata() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                getBaseUrl() + "/fhir/metadata", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CapabilityStatement cs = jsonParser.parseResource(CapabilityStatement.class, response.getBody());
        assertThat(cs.getResourceType().name()).isEqualTo("CapabilityStatement");
        assertThat(cs.getStatus()).isEqualTo(org.hl7.fhir.r4.model.Enumerations.PublicationStatus.ACTIVE);

        // Verify DocumentReference rest resource definition
        boolean hasDocRef = false;
        boolean hasRetrieveDocOp = false;
        for (CapabilityStatement.CapabilityStatementRestComponent rest : cs.getRest()) {
            for (CapabilityStatement.CapabilityStatementRestResourceComponent res : rest.getResource()) {
                if ("DocumentReference".equals(res.getType())) {
                    hasDocRef = true;
                    for (CapabilityStatement.CapabilityStatementRestResourceOperationComponent op : res.getOperation()) {
                        if ("retrieve-document".equals(op.getName())) {
                            hasRetrieveDocOp = true;
                        }
                    }
                }
            }
        }
        assertThat(hasDocRef).isTrue();
        assertThat(hasRetrieveDocOp).isTrue();
    }

    @Test
    @DisplayName("CapabilityStatement: Forwarded GET /metadata without /fhir prefix")
    public void testGetMetadataForwarded() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                getBaseUrl() + "/metadata", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CapabilityStatement cs = jsonParser.parseResource(CapabilityStatement.class, response.getBody());
        assertThat(cs.getResourceType().name()).isEqualTo("CapabilityStatement");
    }
}
