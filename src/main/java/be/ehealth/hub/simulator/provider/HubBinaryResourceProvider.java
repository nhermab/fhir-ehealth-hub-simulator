package be.ehealth.hub.simulator.provider;

import be.ehealth.hub.simulator.service.DocumentRepository;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class HubBinaryResourceProvider implements IResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(HubBinaryResourceProvider.class);

    private final DocumentRepository repository;

    public HubBinaryResourceProvider(DocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Class<Binary> getResourceType() {
        return Binary.class;
    }

    /**
     * Retrieval of Binary resources (such as hub-rendered PDFs) by ID.
     */
    @Read
    public Binary read(@IdParam IdType id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String idPart = id.getIdPart();
        log.info("Reading Binary by ID: {}", idPart);

        byte[] bytes = repository.findBinaryById(idPart)
                .orElseThrow(() -> {
                    OperationOutcome outcome = new OperationOutcome();
                    outcome.addIssue()
                            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                            .setCode(OperationOutcome.IssueType.NOTFOUND)
                            .setDiagnostics("Binary resource with id '" + idPart + "' not found.");
                    return new ResourceNotFoundException("Binary not found", outcome);
                });

        String accept = request != null ? request.getHeader("Accept") : null;
        if (accept != null && accept.contains("application/pdf")) {
            response.setContentType("application/pdf");
            response.setContentLength(bytes.length);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
            return null;
        }

        Binary binary = new Binary();
        binary.setId(idPart);
        binary.setContentType("application/pdf");
        binary.setData(bytes);
        return binary;
    }
}
