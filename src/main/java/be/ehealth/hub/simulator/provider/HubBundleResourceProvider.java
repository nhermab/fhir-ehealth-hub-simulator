package be.ehealth.hub.simulator.provider;

import be.ehealth.hub.simulator.service.DocumentRepository;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HubBundleResourceProvider implements IResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(HubBundleResourceProvider.class);

    private final DocumentRepository repository;

    public HubBundleResourceProvider(DocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Class<Bundle> getResourceType() {
        return Bundle.class;
    }

    /**
     * Direct retrieval of complete FHIR Document Bundles (type=document) by logical ID
     * (as declared in BeInterhubDocumentResponder rest.resource[1]).
     */
    @Read
    public Bundle read(@IdParam IdType id) {
        String idPart = id.getIdPart();
        log.info("Reading Bundle by ID: {}", idPart);

        return repository.findBundleById(idPart)
                .or(() -> repository.findDocumentBundleForReference(idPart))
                .orElseThrow(() -> {
                    OperationOutcome outcome = new OperationOutcome();
                    outcome.addIssue()
                            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                            .setCode(OperationOutcome.IssueType.NOTFOUND)
                            .setDiagnostics("Bundle with id '" + idPart + "' not found.");
                    return new ResourceNotFoundException("Bundle not found", outcome);
                });
    }
}
