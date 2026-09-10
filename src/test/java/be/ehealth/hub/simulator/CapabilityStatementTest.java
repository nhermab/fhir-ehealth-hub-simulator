package be.ehealth.hub.simulator;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Enumerations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CapabilityStatement a running responder publishes: an instance statement that claims
 * conformance to the IG's {@code kind = requirements} statement and advertises exactly the two
 * transactions it serves.
 */
public class CapabilityStatementTest extends AbstractSimulatorTest {

    @Test
    @DisplayName("GET /fhir/metadata publishes an instance statement pointing at the IG requirements")
    public void testInstanceCapabilityStatement() {
        CapabilityStatement cs = capabilityStatement("/fhir/metadata");

        assertThat(cs.getStatus()).isEqualTo(Enumerations.PublicationStatus.ACTIVE);
        assertThat(cs.getKind()).isEqualTo(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        assertThat(cs.getFhirVersion()).isEqualTo(Enumerations.FHIRVersion._4_0_1);
        assertThat(cs.getInstantiates()).extracting(canonical -> canonical.getValue())
                .contains("https://www.ehealth.fgov.be/standards/fhir/interhub/CapabilityStatement/BeInterhubDocumentResponder");
        assertThat(cs.getImplementation().getUrl()).isEqualTo("http://localhost:8080/fhir");
        assertThat(cs.getFormat()).extracting(format -> format.getValue()).contains("application/fhir+json");
    }

    @Test
    @DisplayName("The statement advertises DocumentReference search and $retrieve-document, and nothing else")
    public void testAdvertisedSurfaceIsTheTwoTransactions() {
        CapabilityStatement cs = capabilityStatement("/fhir/metadata");

        List<CapabilityStatement.CapabilityStatementRestResourceComponent> resources =
                cs.getRestFirstRep().getResource();
        assertThat(resources).hasSize(1);

        CapabilityStatement.CapabilityStatementRestResourceComponent docRef = resources.get(0);
        assertThat(docRef.getType()).isEqualTo("DocumentReference");
        assertThat(docRef.getProfile())
                .isEqualTo("https://www.ehealth.fgov.be/standards/fhir/interhub/StructureDefinition/be-interhub-documentreference");
        assertThat(docRef.getInteraction()).extracting(i -> i.getCode().toCode())
                .containsExactly("search-type");
        assertThat(docRef.getOperation()).extracting(CapabilityStatement.CapabilityStatementRestResourceOperationComponent::getName)
                .containsExactly("retrieve-document");
        assertThat(docRef.getOperationFirstRep().getDefinition())
                .isEqualTo("https://www.ehealth.fgov.be/standards/fhir/interhub/OperationDefinition/be-op-retrieve-document");
        assertThat(cs.getRestFirstRep().getInteraction()).isEmpty();
    }

    @Test
    @DisplayName("Every search parameter the IG defines for ITI-67 is advertised")
    public void testSearchParametersAdvertised() {
        CapabilityStatement cs = capabilityStatement("/fhir/metadata");

        assertThat(cs.getRestFirstRep().getResourceFirstRep().getSearchParam())
                .extracting(CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent::getName)
                .contains("patient.identifier", "category", "type", "date", "author.identifier", "status",
                        "_id", "identifier", "searchtype", "_count", "_sort", "_continuation");
    }

    @Test
    @DisplayName("The un-prefixed path GET /metadata is served as well")
    public void testMetadataForwarded() {
        assertThat(capabilityStatement("/metadata").fhirType()).isEqualTo("CapabilityStatement");
    }

    private CapabilityStatement capabilityStatement(String path) {
        ResponseEntity<String> response = exchange(HttpMethod.GET, path);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(CapabilityStatement.class, response);
    }
}
