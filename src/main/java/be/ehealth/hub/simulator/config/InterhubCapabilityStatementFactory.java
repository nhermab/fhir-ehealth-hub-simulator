package be.ehealth.hub.simulator.config;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Enumerations.SearchParamType;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Optional;

/**
 * Builds the {@code kind = instance} CapabilityStatement this server answers at {@code /metadata}.
 *
 * <p>The Implementation Guide publishes {@code BeInterhubDocumentResponder} as a
 * {@code kind = requirements} statement — a description of what a responding hub must do, not of
 * a running endpoint. A server must answer its own instance statement, so this factory declares
 * precisely the surface the simulator exposes and points at the IG requirements through
 * {@code CapabilityStatement.instantiates}.
 */
@Component
public class InterhubCapabilityStatementFactory {

    public static final String IG_BASE = "https://www.ehealth.fgov.be/standards/fhir/interhub";
    public static final String RESPONDER_REQUIREMENTS = IG_BASE + "/CapabilityStatement/BeInterhubDocumentResponder";
    public static final String DOCUMENT_REFERENCE_PROFILE = IG_BASE + "/StructureDefinition/be-interhub-documentreference";
    public static final String MINIMAL_DOCUMENT_REFERENCE_PROFILE = IG_BASE + "/StructureDefinition/be-interhub-minimal-documentreference";
    public static final String DOCUMENT_BUNDLE_PROFILE = IG_BASE + "/StructureDefinition/be-interhub-document-bundle";
    public static final String LAB_OBSERVATION_PROFILE = IG_BASE + "/StructureDefinition/be-interhub-lab-observation";
    public static final String RETRIEVE_DOCUMENT_OPERATION = IG_BASE + "/OperationDefinition/be-op-retrieve-document";

    private static final String SP_BASE = "http://hl7.org/fhir/SearchParameter/";

    private final HubSimulatorProperties properties;

    public InterhubCapabilityStatementFactory(HubSimulatorProperties properties) {
        this.properties = properties;
    }

    public CapabilityStatement build() {
        CapabilityStatement cs = new CapabilityStatement();
        cs.setId("BeInterhubDocumentResponderSimulator");
        cs.setUrl(properties.getServerBaseUrl() + "/metadata");
        cs.setName("BeInterhubDocumentResponderSimulator");
        cs.setTitle("Belgian Interhub Document Responder (simulator instance)");
        cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        cs.setExperimental(true);
        cs.setDate(new Date());
        cs.setPublisher(properties.getHubName());
        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.addInstantiates(RESPONDER_REQUIREMENTS);
        cs.setDescription(
                "Running instance of a Belgian federated Interhub responding hub. It implements exactly the three "
                        + "Interhub transactions: getTransactionList (MHD ITI-67 Find DocumentReferences) over "
                        + "HTTP POST [base]/DocumentReference/_search, getTransaction (Belgian "
                        + "$retrieve-document, gatewaying MHD ITI-68) over HTTP POST "
                        + "[base]/DocumentReference/$retrieve-document, and laboratory observation search "
                        + "(based on IHE QEDm PCC-44) over HTTP POST [base]/Observation/_search. "
                        + "No other interaction is served.");

        cs.getSoftware()
                .setName("fhir-ehealth-hub-simulator")
                .setVersion(softwareVersion())
                .setReleaseDate(new Date());

        cs.getImplementation()
                .setDescription(properties.getHubName() + " (Home Community ID " + properties.getHubOid()
                        + ", EHP " + properties.getHubEhp() + ") — reference simulator, not a production hub")
                .setUrl(properties.getServerBaseUrl());

        cs.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        cs.addFormat("application/fhir+json");
        cs.addFormat("application/fhir+xml");
        cs.addFormat("json");
        cs.addFormat("xml");

        cs.addRest(buildRest());
        return cs;
    }

    private CapabilityStatementRestComponent buildRest() {
        CapabilityStatementRestComponent rest = new CapabilityStatementRestComponent();
        rest.setMode(RestfulCapabilityMode.SERVER);
        rest.setDocumentation(
                "Belgian Federated Interhub responder. Consumers SHALL use HTTP POST for all transactions so that "
                        + "the patient SSIN and the clinical search criteria never appear in a URL, a proxy access "
                        + "log or a browser history. Pagination stays on POST: the next page is requested by "
                        + "replaying the opaque _continuation parameter from Bundle.link in a new "
                        + "application/x-www-form-urlencoded body.");
        rest.getSecurity().setDescription(
                "This simulator does not authenticate callers. A production responder authenticates the calling hub "
                        + "as specified in the IG (mTLS with an eHealth enterprise certificate, OAuth 2.0 "
                        + "client_credentials, and DPoP (RFC 9449) or HTTP Message Signatures (RFC 9421) request "
                        + "tamper-proofing). Access control — consent and therapeutic links — is the initiating "
                        + "hub's responsibility, never the responder's.");

        rest.addResource(buildDocumentReferenceResource());
        rest.addResource(buildObservationResource());
        return rest;
    }

    private CapabilityStatementRestResourceComponent buildDocumentReferenceResource() {
        CapabilityStatementRestResourceComponent resource = new CapabilityStatementRestResourceComponent();
        resource.setType("DocumentReference");
        resource.setProfile(DOCUMENT_REFERENCE_PROFILE);
        resource.addSupportedProfile(MINIMAL_DOCUMENT_REFERENCE_PROFILE);
        resource.setDocumentation(
                "Discovery envelope returned by getTransactionList. Authors, authenticator, custodian and the "
                        + "patient snapshot travel as contained resources, and relationships use logical references "
                        + "(Reference.identifier without Reference.reference), so a consumer never has to "
                        + "dereference another hub to render the list.");
        resource.setVersioning(CapabilityStatement.ResourceVersionPolicy.NOVERSION);
        resource.setReadHistory(false);
        resource.setUpdateCreate(false);
        resource.setConditionalCreate(false);
        resource.setConditionalUpdate(false);
        resource.setConditionalDelete(CapabilityStatement.ConditionalDeleteStatus.NOTSUPPORTED);

        resource.addInteraction()
                .setCode(TypeRestfulInteraction.SEARCHTYPE)
                .setDocumentation(
                        "getTransactionList (MHD ITI-67 Find DocumentReferences). SHALL be invoked as POST "
                                + "[base]/DocumentReference/_search with an application/x-www-form-urlencoded body. "
                                + "GET [base]/DocumentReference with the same parameters is accepted for generic "
                                + "IHE MHD conformance testing only and MUST NOT be used by Belgian consumers.");

        resource.addOperation()
                .setName("retrieve-document")
                .setDefinition(RETRIEVE_DOCUMENT_OPERATION)
                .setDocumentation(
                        "getTransaction. POST [base]/DocumentReference/$retrieve-document with a Parameters body "
                                + "carrying parameter[name=documentReference].valueReference (a relative reference or "
                                + "a logical reference by business identifier). Returns the "
                                + DOCUMENT_BUNDLE_PROFILE + " document Bundle directly, or — with "
                                + "Accept: application/pdf — the hub-rendered PDF as a raw binary stream. "
                                + "410 Gone marks a document withdrawn by its source system, 404 Not Found an "
                                + "unknown one, and 406 Not Acceptable a document this hub does not render as PDF.");

        addSearchParam(resource, "patient.identifier", SearchParamType.TOKEN, null,
                "Mandatory. Patient national SSIN / INSS as system|value. Both "
                        + "https://www.ehealth.fgov.be/standards/fhir/core/NamingSystem/ssin and "
                        + "urn:oid:1.3.6.1.4.1.21297.100.1.1 are accepted; any other system is rejected with 400. "
                        + "A bare 11-digit value without a system is also accepted.");
        addSearchParam(resource, "category", SearchParamType.TOKEN, SP_BASE + "DocumentReference-category",
                "Belgian CD-TRANSACTION document category (sumehr, labresult, discharge, telemonitoring, ...). "
                        + "Matches any coding present in the CodeableConcept, national or local.");
        addSearchParam(resource, "type", SearchParamType.TOKEN, SP_BASE + "clinical-type",
                "Clinical document type, normally a LOINC code such as http://loinc.org|11502-2.");
        addSearchParam(resource, "date", SearchParamType.DATE, SP_BASE + "DocumentReference-date",
                "Document date/time, not the hub indexing time. Repeat with the ge/le/gt/lt prefixes to bound a range.");
        addSearchParam(resource, "author.identifier", SearchParamType.TOKEN, null,
                "Authoring practitioner NIHDI or institution NIHDI/CBE identifier. Matches identifiers on the "
                        + "contained authoring parties as well as inline logical references on author.identifier.");
        addSearchParam(resource, "status", SearchParamType.TOKEN, SP_BASE + "DocumentReference-status",
                "Metadata status. Defaults to current when the consumer supplies no value.");
        addSearchParam(resource, "_id", SearchParamType.TOKEN, SP_BASE + "Resource-id",
                "Logical id of a single document reference.");
        addSearchParam(resource, "identifier", SearchParamType.TOKEN, SP_BASE + "clinical-identifier",
                "Universal (masterIdentifier / uniqueId) or hub-source-local document identifier.");
        addSearchParam(resource, "searchtype", SearchParamType.TOKEN, null,
                "Belgian search scope: federated (default) fans out to connected sources and partner hubs; local "
                        + "restricts the answer to this hub's own index and therefore never reports downstream "
                        + "partial failures.");
        addSearchParam(resource, "_count", SearchParamType.NUMBER, null,
                "Page size. Defaults to " + properties.getDefaultPageSize() + " and is clamped to "
                        + properties.getMaxPageSize() + ".");
        addSearchParam(resource, "_sort", SearchParamType.STRING, null,
                "Ordering of the result set: -date (default, most recent first) or date.");
        addSearchParam(resource, "_continuation", SearchParamType.TOKEN, null,
                "Opaque next-page token issued in Bundle.link[relation=next].url. Replay it alone in a POST "
                        + "_search body; every other parameter is then ignored. Tokens expire after "
                        + properties.getContinuationTokenTtlSeconds() + " seconds.");

        return resource;
    }

    private CapabilityStatementRestResourceComponent buildObservationResource() {
        CapabilityStatementRestResourceComponent resource = new CapabilityStatementRestResourceComponent();
        resource.setType("Observation");
        resource.setProfile(LAB_OBSERVATION_PROFILE);
        resource.setDocumentation(
                "Laboratory observations returned by Transaction 3 (based on IHE QEDm PCC-44). Every reference "
                        + "is a logical reference by national business identifier (subject by SSIN, performer by "
                        + "NIHDI/CBE, derivedFrom by source document uniqueId), so the responding hub needs no "
                        + "endpoints for Patient or Practitioner.");
        resource.setVersioning(CapabilityStatement.ResourceVersionPolicy.NOVERSION);
        resource.setReadHistory(false);
        resource.setUpdateCreate(false);
        resource.setConditionalCreate(false);
        resource.setConditionalUpdate(false);
        resource.setConditionalDelete(CapabilityStatement.ConditionalDeleteStatus.NOTSUPPORTED);

        resource.addInteraction()
                .setCode(TypeRestfulInteraction.SEARCHTYPE)
                .setDocumentation(
                        "Mandatory laboratory observation search via HTTP POST to [base]/Observation/_search with "
                                + "application/x-www-form-urlencoded body. Returns BeInterhubLabObservation resources "
                                + "extracted from laboratory report documents, each carrying logical references only.");

        addSearchParam(resource, "patient.identifier", SearchParamType.TOKEN, null,
                "Mandatory. Patient national SSIN / INSS as system|value. Both "
                        + "https://www.ehealth.fgov.be/standards/fhir/core/NamingSystem/ssin and "
                        + "urn:oid:1.3.6.1.4.1.21297.100.1.1 are accepted.");
        addSearchParam(resource, "code", SearchParamType.TOKEN, SP_BASE + "clinical-code",
                "Mandatory. One or more LOINC analyte codes (e.g. http://loinc.org|1558-6).");
        addSearchParam(resource, "category", SearchParamType.TOKEN, SP_BASE + "Observation-category",
                "Optional, for IHE QEDm compatibility: http://terminology.hl7.org/CodeSystem/observation-category|laboratory.");
        addSearchParam(resource, "date", SearchParamType.DATE, SP_BASE + "clinical-date",
                "Filters observations by effectiveDateTime timestamp range (ge, le, gt, lt).");
        addSearchParam(resource, "searchtype", SearchParamType.TOKEN, null,
                "Belgian federation scope: federated (default, fans out across connected hubs) or local "
                        + "(searches only local hub index).");
        addSearchParam(resource, "_count", SearchParamType.NUMBER, null,
                "Maximum number of observations returned per page.");
        addSearchParam(resource, "_sort", SearchParamType.STRING, null,
                "Result ordering: -date (default, newest first) or date.");
        addSearchParam(resource, "_continuation", SearchParamType.TOKEN, null,
                "Opaque next-page token issued in Bundle.link[relation=next].url.");

        return resource;
    }

    private void addSearchParam(CapabilityStatementRestResourceComponent resource, String name,
                                SearchParamType type, String definition, String documentation) {
        CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent param = resource.addSearchParam()
                .setName(name)
                .setType(type)
                .setDocumentation(documentation);
        if (definition != null) {
            param.setDefinition(definition);
        }
    }

    private String softwareVersion() {
        return Optional.ofNullable(getClass().getPackage().getImplementationVersion()).orElse("1.0.0-SNAPSHOT");
    }
}
