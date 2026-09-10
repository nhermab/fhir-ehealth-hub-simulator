package be.ehealth.hub.simulator.config;

import be.ehealth.hub.simulator.provider.HubBinaryResourceProvider;
import be.ehealth.hub.simulator.provider.HubBundleResourceProvider;
import be.ehealth.hub.simulator.provider.HubDocumentReferenceResourceProvider;
import be.ehealth.hub.simulator.service.DocumentRepository;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.LoggingInterceptor;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import ca.uhn.fhir.rest.server.interceptor.StaticCapabilityStatementInterceptor;
import jakarta.servlet.ServletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HubRestfulServer extends RestfulServer {

    private static final Logger log = LoggerFactory.getLogger(HubRestfulServer.class);

    private final HubDocumentReferenceResourceProvider docRefProvider;
    private final HubBundleResourceProvider bundleProvider;
    private final HubBinaryResourceProvider binaryProvider;
    private final DocumentRepository repository;
    private final HubSimulatorProperties properties;

    public HubRestfulServer(
            HubDocumentReferenceResourceProvider docRefProvider,
            HubBundleResourceProvider bundleProvider,
            HubBinaryResourceProvider binaryProvider,
            DocumentRepository repository,
            HubSimulatorProperties properties
    ) {
        super(FhirContext.forR4());
        this.docRefProvider = docRefProvider;
        this.bundleProvider = bundleProvider;
        this.binaryProvider = binaryProvider;
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    protected void initialize() throws ServletException {
        super.initialize();
        log.info("Initializing Belgian Interhub FHIR RestfulServer (R4)...");

        // Set encoding defaults
        setDefaultResponseEncoding(EncodingEnum.JSON);
        setDefaultPrettyPrint(true);

        // Register Resource Providers
        setResourceProviders(List.of(docRefProvider, bundleProvider, binaryProvider));

        // Interceptor: Request Logging
        LoggingInterceptor loggingInterceptor = new LoggingInterceptor();
        loggingInterceptor.setMessageFormat("Interhub Request: ${operationType} ${idOrResourceName} - Status: ${statusCode} in ${processingTimeMillis}ms");
        registerInterceptor(loggingInterceptor);

        // Interceptor: Response Highlighting for browser viewing
        ResponseHighlighterInterceptor highlighter = new ResponseHighlighterInterceptor();
        registerInterceptor(highlighter);

        // Interceptor: Custom CapabilityStatement from Belgian Interhub IG
        repository.getCapabilityStatement().ifPresent(cs -> {
            StaticCapabilityStatementInterceptor csInterceptor = new StaticCapabilityStatementInterceptor();
            csInterceptor.setCapabilityStatement(cs);
            registerInterceptor(csInterceptor);
            log.info("Registered static CapabilityStatement 'BeInterhubDocumentResponder'");
        });

        log.info("Belgian Interhub FHIR RestfulServer initialized successfully.");
    }
}
