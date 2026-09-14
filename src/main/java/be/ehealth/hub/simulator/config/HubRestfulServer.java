package be.ehealth.hub.simulator.config;

import be.ehealth.hub.simulator.provider.HubDocumentReferenceResourceProvider;
import be.ehealth.hub.simulator.provider.HubObservationResourceProvider;
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

/**
 * The HAPI server behind {@code /fhir/*}.
 *
 * <p>Exactly two resource providers are registered: DocumentReference (search and $retrieve-document)
 * and Observation (search). Every other resource type and interaction is therefore
 * unknown to HAPI as well as blocked by {@code InterhubGatewayFilter}.
 */
@Component
public class HubRestfulServer extends RestfulServer {

    private static final Logger log = LoggerFactory.getLogger(HubRestfulServer.class);

    private final HubDocumentReferenceResourceProvider docRefProvider;
    private final HubObservationResourceProvider observationProvider;
    private final InterhubCapabilityStatementFactory capabilityStatementFactory;

    public HubRestfulServer(FhirContext fhirContext,
                            HubDocumentReferenceResourceProvider docRefProvider,
                            HubObservationResourceProvider observationProvider,
                            InterhubCapabilityStatementFactory capabilityStatementFactory) {
        super(fhirContext);
        this.docRefProvider = docRefProvider;
        this.observationProvider = observationProvider;
        this.capabilityStatementFactory = capabilityStatementFactory;
    }

    @Override
    protected void initialize() throws ServletException {
        super.initialize();
        log.info("Initializing Belgian Interhub FHIR RestfulServer (R4)...");

        setDefaultResponseEncoding(EncodingEnum.JSON);
        setDefaultPrettyPrint(true);

        setResourceProviders(List.of(docRefProvider, observationProvider));

        LoggingInterceptor loggingInterceptor = new LoggingInterceptor();
        loggingInterceptor.setMessageFormat(
                "Interhub ${requestVerb} ${requestUrl} - ${operationType} in ${processingTimeMillis}ms");
        registerInterceptor(loggingInterceptor);

        // Renders responses readably when a browser opens an endpoint directly.
        registerInterceptor(new ResponseHighlighterInterceptor());

        StaticCapabilityStatementInterceptor capabilityStatement = new StaticCapabilityStatementInterceptor();
        capabilityStatement.setCapabilityStatement(capabilityStatementFactory.build());
        registerInterceptor(capabilityStatement);

        log.info("Interhub responder ready: getTransactionList (POST /DocumentReference/_search), "
                + "getTransaction (POST /DocumentReference/$retrieve-document), and "
                + "laboratory observation search (POST /Observation/_search)");
    }
}
