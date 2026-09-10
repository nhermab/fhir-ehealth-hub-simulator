package be.ehealth.hub.simulator.service;

import be.ehealth.hub.simulator.config.HubSimulatorProperties;
import be.ehealth.hub.simulator.util.SsinValidator;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import jakarta.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory index built from the hand-maintained sample resources under {@code data/}.
 *
 * <p>Only the two Interhub transactions read from this index: ITI-67 searches
 * {@link #searchDocumentReferences}, and {@code $retrieve-document} resolves a reference through
 * {@link #findDocumentBundleForReference} / {@link #findPdfForReference}.
 */
@Service
public class DocumentRepositoryImpl implements DocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentRepositoryImpl.class);

    /** Extension carrying the hub that holds the document, used by {@code searchtype=local}. */
    private static final String HOME_COMMUNITY_ID_EXTENSION =
            "https://www.ehealth.fgov.be/standards/fhir/interhub/StructureDefinition/be-ext-home-community-id";

    private final HubSimulatorProperties properties;
    private final FhirContext fhirContext;

    private final List<DocumentReference> allDocumentReferences = new ArrayList<>();
    /** Every alias a consumer may use in $retrieve-document: id, relative reference, uniqueId, local ids. */
    private final Map<String, DocumentReference> docRefByIdentifiers = new ConcurrentHashMap<>();
    private final Map<String, List<DocumentReference>> docRefsByPatientSsin = new ConcurrentHashMap<>();

    /** Document Bundles by their own id and identifier; used only while cross-linking. */
    private final Map<String, Bundle> documentBundlesByIdentifier = new ConcurrentHashMap<>();
    /** PDF files by file name and base name; used only while cross-linking. */
    private final Map<String, byte[]> pdfFiles = new ConcurrentHashMap<>();

    /** What getTransaction actually serves, keyed by DocumentReference logical id. */
    private final Map<String, Bundle> payloadByDocRefId = new ConcurrentHashMap<>();
    private final Map<String, byte[]> renderingByDocRefId = new ConcurrentHashMap<>();

    private final Map<String, OperationOutcome> operationOutcomes = new ConcurrentHashMap<>();

    private OperationOutcome defaultPartialFailureOutcome;

    public DocumentRepositoryImpl(HubSimulatorProperties properties) {
        this.properties = properties;
        this.fhirContext = FhirContext.forR4();
    }

    @PostConstruct
    public void init() {
        reload();
    }

    // -------------------------------------------------------------------------------------------
    // Loading & indexing
    // -------------------------------------------------------------------------------------------

    @Override
    public synchronized void reload() {
        log.info("Loading FHIR resources from data directory: {}", properties.getDataDir());

        allDocumentReferences.clear();
        docRefByIdentifiers.clear();
        docRefsByPatientSsin.clear();
        documentBundlesByIdentifier.clear();
        pdfFiles.clear();
        payloadByDocRefId.clear();
        renderingByDocRefId.clear();
        operationOutcomes.clear();
        defaultPartialFailureOutcome = null;

        Path dataPath = Paths.get(properties.getDataDir());
        if (!Files.exists(dataPath)) {
            log.warn("Data directory does not exist: {}. Creating it.", dataPath.toAbsolutePath());
            try {
                Files.createDirectories(dataPath);
            } catch (Exception e) {
                log.error("Failed to create data directory", e);
            }
            return;
        }

        IParser parser = fhirContext.newJsonParser();

        try {
            List<IBaseResource> resources = new ArrayList<>();
            for (File file : FileUtils.listFiles(dataPath.toFile(), null, true)) {
                String ext = FilenameUtils.getExtension(file.getName()).toLowerCase();
                if ("json".equals(ext)) {
                    parseJsonFile(file, parser).ifPresent(resources::add);
                } else if ("pdf".equals(ext)) {
                    loadPdfFile(file);
                }
            }

            // Indexing order is deliberate and independent of the filesystem: standalone
            // resources win, and the IG's searchset example only contributes what they missed.
            resources.stream()
                    .filter(DocumentReference.class::isInstance)
                    .forEach(resource -> indexDocumentReference((DocumentReference) resource));
            resources.stream()
                    .filter(resource -> resource instanceof Bundle bundle
                            && bundle.getType() == Bundle.BundleType.DOCUMENT)
                    .forEach(resource -> indexDocumentBundle((Bundle) resource));
            resources.stream()
                    .filter(OperationOutcome.class::isInstance)
                    .forEach(resource -> indexOperationOutcome((OperationOutcome) resource));
            resources.stream()
                    .filter(resource -> resource instanceof Bundle bundle
                            && bundle.getType() == Bundle.BundleType.SEARCHSET)
                    .forEach(resource -> indexSearchsetBundle((Bundle) resource));

            crossLinkDocumentReferencesAndBundles();

            log.info("Repository loaded: {} DocumentReference(s), {} document Bundle(s), {} PDF rendering(s), "
                            + "{} OperationOutcome(s); {} reference(s) resolve to a payload, {} to a rendering",
                    allDocumentReferences.size(), getDocumentBundleCount(), countPdfFiles(),
                    operationOutcomes.size(), payloadByDocRefId.size(), renderingByDocRefId.size());

        } catch (Exception e) {
            log.error("Error scanning data directory: {}", e.getMessage(), e);
        }
    }

    private Optional<IBaseResource> parseJsonFile(File file, IParser parser) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return Optional.of(parser.parseResource(fis));
        } catch (Exception e) {
            log.debug("Skipping unparseable JSON file {}: {}", file.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    private void indexOperationOutcome(OperationOutcome outcome) {
        if (outcome.hasIdElement()) {
            operationOutcomes.put(outcome.getIdPart(), outcome);
        }
        if (defaultPartialFailureOutcome == null) {
            defaultPartialFailureOutcome = outcome;
        }
    }

    private void loadPdfFile(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            pdfFiles.put(file.getName(), bytes);
            pdfFiles.put(FilenameUtils.getBaseName(file.getName()), bytes);
            log.info("Loaded PDF rendering: {} ({} bytes)", file.getName(), bytes.length);
        } catch (Exception e) {
            log.error("Failed to read PDF file {}: {}", file.getName(), e.getMessage());
        }
    }

    private void indexDocumentReference(DocumentReference docRef) {
        String id = docRef.getIdPart();
        if (id != null && docRefByIdentifiers.containsKey(id)) {
            return; // already indexed from a standalone file; do not duplicate from a searchset
        }

        allDocumentReferences.add(docRef);

        if (id != null) {
            docRefByIdentifiers.put(id, docRef);
            docRefByIdentifiers.put("DocumentReference/" + id, docRef);
        }

        if (docRef.hasMasterIdentifier() && docRef.getMasterIdentifier().hasValue()) {
            docRefByIdentifiers.putIfAbsent(docRef.getMasterIdentifier().getValue(), docRef);
        }

        for (Identifier identifier : docRef.getIdentifier()) {
            if (identifier.hasValue()) {
                docRefByIdentifiers.putIfAbsent(identifier.getValue(), docRef);
            }
        }

        String ssin = extractSsin(docRef);
        if (ssin != null) {
            String normalized = SsinValidator.normalize(ssin);
            docRefsByPatientSsin.computeIfAbsent(normalized, k -> new ArrayList<>()).add(docRef);
            log.debug("Indexed DocumentReference {} under patient SSIN {}", id, normalized);
        }
    }

    private void indexDocumentBundle(Bundle bundle) {
        String id = bundle.getIdPart();
        if (id != null) {
            documentBundlesByIdentifier.put(id, bundle);
        }
        if (bundle.hasIdentifier() && bundle.getIdentifier().hasValue()) {
            documentBundlesByIdentifier.putIfAbsent(bundle.getIdentifier().getValue(), bundle);
        }
    }

    /**
     * The IG ships a complete searchset example. Its DocumentReferences are the same resources
     * that also exist as standalone files, so only genuinely new ones are added; its
     * OperationOutcome becomes the partial-failure sample.
     */
    private void indexSearchsetBundle(Bundle bundle) {
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (!entry.hasResource()) {
                continue;
            }
            Resource res = entry.getResource();
            if (res instanceof DocumentReference docRef) {
                indexDocumentReference(docRef);
            } else if (res instanceof OperationOutcome outcome) {
                indexOperationOutcome(outcome);
            }
        }
    }

    /**
     * Links each DocumentReference to its payload. The structured payload is found through the
     * attachment URL (last path segment) or the masterIdentifier; the hub-rendered PDF comes
     * either from an {@code application/pdf} content entry (transactions.md §3.4) or from the
     * explicit {@code hub.simulator.pdf-renderings} mapping.
     */
    private void crossLinkDocumentReferencesAndBundles() {
        for (DocumentReference docRef : allDocumentReferences) {
            String id = docRef.getIdPart();
            if (id == null) {
                continue;
            }

            for (DocumentReference.DocumentReferenceContentComponent content : docRef.getContent()) {
                Attachment attachment = content.getAttachment();
                if (attachment == null || !attachment.hasUrl()) {
                    continue;
                }
                String lastSegment = lastSegment(attachment.getUrl());

                if ("application/pdf".equalsIgnoreCase(attachment.getContentType())) {
                    byte[] pdfBytes = pdfFile(lastSegment);
                    if (pdfBytes != null) {
                        renderingByDocRefId.putIfAbsent(id, pdfBytes);
                    }
                } else {
                    Bundle payload = documentBundlesByIdentifier.get(lastSegment);
                    if (payload != null) {
                        payloadByDocRefId.putIfAbsent(id, payload);
                    }
                }
            }

            // The IG's uniqueId convention: Bundle.identifier of the payload equals the
            // DocumentReference masterIdentifier, which is the reliable link when the
            // attachment URL points at an id this hub does not use internally.
            if (!payloadByDocRefId.containsKey(id) && docRef.hasMasterIdentifier()) {
                Bundle payload = documentBundlesByIdentifier.get(docRef.getMasterIdentifier().getValue());
                if (payload != null) {
                    payloadByDocRefId.put(id, payload);
                }
            }

            String configuredPdf = properties.getPdfRenderings().get(id);
            if (configuredPdf != null && !renderingByDocRefId.containsKey(id)) {
                byte[] pdfBytes = pdfFile(configuredPdf);
                if (pdfBytes != null) {
                    renderingByDocRefId.put(id, pdfBytes);
                    log.debug("DocumentReference {} advertises hub rendering {}", id, configuredPdf);
                } else {
                    log.warn("Configured PDF rendering '{}' for DocumentReference {} was not found in {}",
                            configuredPdf, id, properties.getDataDir());
                }
            }
        }
    }

    private byte[] pdfFile(String nameOrBaseName) {
        byte[] bytes = pdfFiles.get(nameOrBaseName);
        return bytes != null ? bytes : pdfFiles.get(nameOrBaseName + ".pdf");
    }

    private long countPdfFiles() {
        return pdfFiles.keySet().stream().filter(name -> name.endsWith(".pdf")).count();
    }

    private static String lastSegment(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private String extractSsin(DocumentReference docRef) {
        if (docRef.hasSubject() && docRef.getSubject().hasIdentifier() && docRef.getSubject().getIdentifier().hasValue()) {
            return docRef.getSubject().getIdentifier().getValue();
        }

        for (Resource contained : docRef.getContained()) {
            if (contained instanceof Patient patient) {
                for (Identifier id : patient.getIdentifier()) {
                    if (id.hasValue() && SsinValidator.normalize(id.getValue()).length() == 11) {
                        return id.getValue();
                    }
                }
            }
        }

        return null;
    }

    // -------------------------------------------------------------------------------------------
    // getTransactionList (ITI-67)
    // -------------------------------------------------------------------------------------------

    @Override
    public SearchResult searchDocumentReferences(SearchFilter filter) {
        String ssin = SsinValidator.normalize(filter.getPatientSsin());

        List<DocumentReference> matches = docRefsByPatientSsin.getOrDefault(ssin, List.of()).stream()
                .filter(docRef -> matchesScope(docRef, filter.getSearchScope()))
                .filter(docRef -> matchesStatus(docRef, filter.getStatus()))
                .filter(docRef -> matchesCategory(docRef, filter.getCategory()))
                .filter(docRef -> matchesType(docRef, filter.getType()))
                .filter(docRef -> matchesDate(docRef, filter.getDateFrom(), filter.getDateTo()))
                .filter(docRef -> matchesAuthor(docRef, filter.getAuthorIdentifier()))
                .filter(docRef -> matchesLogicalId(docRef, filter.getId()))
                .filter(docRef -> matchesIdentifier(docRef, filter.getIdentifier()))
                .sorted(comparator(filter.getSort()))
                .collect(Collectors.toList());

        int total = matches.size();
        int offset = Math.min(Math.max(filter.getOffset(), 0), total);
        int end = filter.getCount() > 0 ? Math.min(offset + filter.getCount(), total) : total;

        return new SearchResult(List.copyOf(matches.subList(offset, end)), total, offset);
    }

    private boolean matchesScope(DocumentReference docRef, SearchScope scope) {
        if (scope != SearchScope.LOCAL) {
            return true;
        }
        Extension home = docRef.getExtensionByUrl(HOME_COMMUNITY_ID_EXTENSION);
        if (home == null || home.getValue() == null) {
            return false;
        }
        String value = home.getValue().primitiveValue();
        return properties.getHubOid().equalsIgnoreCase(value) || properties.getHubEhp().equalsIgnoreCase(value);
    }

    private boolean matchesStatus(DocumentReference docRef, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return docRef.hasStatus() && status.equalsIgnoreCase(docRef.getStatus().toCode());
    }

    private boolean matchesCategory(DocumentReference docRef, String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        return docRef.getCategory().stream()
                .flatMap(cc -> cc.getCoding().stream())
                .anyMatch(coding -> tokenMatches(token, coding.getSystem(), coding.getCode()));
    }

    private boolean matchesType(DocumentReference docRef, String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        return docRef.hasType() && docRef.getType().getCoding().stream()
                .anyMatch(coding -> tokenMatches(token, coding.getSystem(), coding.getCode()));
    }

    private boolean matchesDate(DocumentReference docRef, Date from, Date to) {
        if (from == null && to == null) {
            return true;
        }
        if (!docRef.hasDate()) {
            return false;
        }
        Date docDate = docRef.getDate();
        if (from != null && docDate.before(from)) {
            return false;
        }
        return to == null || !docDate.after(to);
    }

    /**
     * Matches the authoring party by identifier, whether it is carried on a contained resource
     * (MHD Comprehensive) or inline as a logical reference on {@code author.identifier}.
     */
    private boolean matchesAuthor(DocumentReference docRef, String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        return authorIdentifiers(docRef).stream()
                .anyMatch(identifier -> tokenMatches(token, identifier.getSystem(), identifier.getValue()));
    }

    private List<Identifier> authorIdentifiers(DocumentReference docRef) {
        List<Identifier> identifiers = new ArrayList<>();
        for (Reference author : docRef.getAuthor()) {
            if (author.hasIdentifier()) {
                identifiers.add(author.getIdentifier());
            }
        }
        for (Resource contained : docRef.getContained()) {
            if (contained instanceof Practitioner practitioner) {
                identifiers.addAll(practitioner.getIdentifier());
            } else if (contained instanceof Organization organization) {
                identifiers.addAll(organization.getIdentifier());
            } else if (contained instanceof PractitionerRole role) {
                identifiers.addAll(role.getIdentifier());
            } else if (contained instanceof Device device) {
                identifiers.addAll(device.getIdentifier());
            }
        }
        return identifiers;
    }

    private boolean matchesLogicalId(DocumentReference docRef, String id) {
        return id == null || id.isBlank() || id.equals(docRef.getIdPart());
    }

    private boolean matchesIdentifier(DocumentReference docRef, String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        List<Identifier> identifiers = new ArrayList<>(docRef.getIdentifier());
        if (docRef.hasMasterIdentifier()) {
            identifiers.add(docRef.getMasterIdentifier());
        }
        return identifiers.stream()
                .anyMatch(identifier -> tokenMatches(token, identifier.getSystem(), identifier.getValue()));
    }

    /**
     * FHIR R4 token matching for the {@code system|code}, {@code system|}, {@code |code} and bare
     * {@code code} forms.
     */
    private static boolean tokenMatches(String token, String system, String code) {
        int pipe = token.indexOf('|');
        if (pipe < 0) {
            return token.equalsIgnoreCase(code);
        }
        String tokenSystem = token.substring(0, pipe);
        String tokenCode = token.substring(pipe + 1);
        if (!tokenSystem.isEmpty() && !tokenSystem.equalsIgnoreCase(system)) {
            return false;
        }
        return tokenCode.isEmpty() || tokenCode.equalsIgnoreCase(code);
    }

    private static Comparator<DocumentReference> comparator(String sort) {
        Comparator<DocumentReference> byDate = Comparator.comparing(
                docRef -> docRef.hasDate() ? docRef.getDate() : new Date(0));
        Comparator<DocumentReference> ordered = "date".equalsIgnoreCase(sort) ? byDate : byDate.reversed();
        // Stable, deterministic order for documents sharing a date (or having none).
        return ordered.thenComparing(docRef -> Objects.requireNonNullElse(docRef.getIdPart(), ""));
    }

    // -------------------------------------------------------------------------------------------
    // getTransaction ($retrieve-document)
    // -------------------------------------------------------------------------------------------

    @Override
    public Optional<DocumentReference> findDocumentReference(String referenceOrId) {
        if (referenceOrId == null || referenceOrId.isBlank()) {
            return Optional.empty();
        }

        DocumentReference direct = docRefByIdentifiers.get(referenceOrId.trim());
        if (direct != null) {
            return Optional.of(direct);
        }
        return Optional.ofNullable(docRefByIdentifiers.get(stripDocumentReferencePrefix(referenceOrId)));
    }

    private static String stripDocumentReferencePrefix(String referenceOrId) {
        String cleaned = referenceOrId.trim();
        int marker = cleaned.lastIndexOf("DocumentReference/");
        return marker >= 0 ? cleaned.substring(marker + "DocumentReference/".length()) : cleaned;
    }

    @Override
    public Optional<Bundle> findDocumentBundleForReference(String referenceOrId) {
        return findDocumentReference(referenceOrId).map(docRef -> payloadByDocRefId.get(docRef.getIdPart()));
    }

    @Override
    public Optional<byte[]> findPdfForReference(String referenceOrId) {
        return findDocumentReference(referenceOrId).map(docRef -> renderingByDocRefId.get(docRef.getIdPart()));
    }

    @Override
    public Optional<OperationOutcome> getPartialFailureOutcome() {
        if (defaultPartialFailureOutcome != null) {
            return Optional.of(defaultPartialFailureOutcome.copy());
        }

        // Fallback matching transactions.md §2.4.3 when the sample outcome is absent from data/.
        OperationOutcome outcome = new OperationOutcome();
        outcome.setId("outcome-partial-timeout-example");
        addPartialFailureIssue(outcome, OperationOutcome.IssueType.TIMEOUT, "timeout", "Timeout",
                "Downstream clinical repository timeout",
                "Timeout communicating with connected laboratory repository (NIHDI: 71000012). "
                        + "Results from this facility may be incomplete or omitted from this list.");
        addPartialFailureIssue(outcome, OperationOutcome.IssueType.TRANSIENT, "transient", "Transient Issue",
                "Downstream system unavailable (maintenance)",
                "Connected hub source (NIHDI: 72000034) is currently unavailable due to scheduled maintenance. "
                        + "Historical documents from this organisation are temporarily excluded.");
        return Optional.of(outcome);
    }

    private static void addPartialFailureIssue(OperationOutcome outcome, OperationOutcome.IssueType type,
                                               String code, String display, String text, String diagnostics) {
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
        issue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
        issue.setCode(type);
        issue.getDetails().addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/issue-type")
                .setCode(code)
                .setDisplay(display);
        issue.getDetails().setText(text);
        issue.setDiagnostics(diagnostics);
    }

    /**
     * A document is withdrawn when its source system retracted it: the hub still knows the
     * reference but can no longer resolve the payload, which the IG maps to 410 Gone rather
     * than 404 Not Found (transactions.md §4.2).
     */
    @Override
    public boolean isWithdrawn(String referenceOrId) {
        if (referenceOrId == null || referenceOrId.isBlank()) {
            return false;
        }

        Optional<DocumentReference> docRef = findDocumentReference(referenceOrId);
        if (docRef.isPresent()) {
            return docRef.get().getStatus() == Enumerations.DocumentReferenceStatus.ENTEREDINERROR;
        }

        String id = stripDocumentReferencePrefix(referenceOrId).toLowerCase();
        return properties.getWithdrawnReferences().stream()
                .map(String::toLowerCase)
                .anyMatch(id::contains);
    }

    @Override
    public int getDocumentReferenceCount() {
        return allDocumentReferences.size();
    }

    @Override
    public int getDocumentBundleCount() {
        return (int) documentBundlesByIdentifier.values().stream().distinct().count();
    }
}
