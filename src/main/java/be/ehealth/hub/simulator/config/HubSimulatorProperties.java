package be.ehealth.hub.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "hub.simulator")
public class HubSimulatorProperties {

    /**
     * Path to directory containing sample FHIR JSON files and binaries.
     */
    private String dataDir = "./data";

    /**
     * Whether to strictly enforce modulo-97 checksum on patient SSIN identifiers.
     * Defaults to false to support synthetic test identities in the FHIR IG.
     */
    private boolean strictSsinChecksum = false;

    /**
     * Whether getTransactionList should simulate downstream repository partial failures by default.
     */
    private boolean simulatePartialFailure = false;

    /**
     * Responding hub OID (Home Community ID).
     */
    private String hubOid = "urn:oid:1.3.6.1.4.1.21297.1.3";

    /**
     * Responding hub eHealth Platform (EHP) number.
     */
    private String hubEhp = "1990000003";

    /**
     * Responding hub display name.
     */
    private String hubName = "CoZo Regional Hub";

    /**
     * Base URL for the simulator FHIR endpoint.
     */
    private String serverBaseUrl = "http://localhost:8080/fhir";

    /**
     * Page size applied to getTransactionList when the consumer supplies no _count.
     */
    private int defaultPageSize = 20;

    /**
     * Upper bound on _count. Larger requested page sizes are clamped to this value.
     */
    private int maxPageSize = 200;

    /**
     * Lifetime (seconds) of an opaque pagination continuation token.
     */
    private long continuationTokenTtlSeconds = 300;

    /**
     * Maximum number of live continuation tokens retained in memory.
     */
    private int continuationTokenCacheSize = 500;

    /**
     * Reference fragments that mark a document as withdrawn by its source system, so that
     * getTransaction answers 410 Gone instead of 404 Not Found. A DocumentReference whose
     * status is entered-in-error is treated as withdrawn regardless of this list.
     */
    private List<String> withdrawnReferences = List.of("withdrawn", "gone");

    /**
     * Hub-rendered PDF available per DocumentReference id, as specified in transactions.md §3.4.
     * The value is the file name of a PDF loaded from the data directory. A DocumentReference
     * absent from this map has no hub rendering and answers 406 to Accept: application/pdf.
     */
    private Map<String, String> pdfRenderings = new LinkedHashMap<>();

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public boolean isStrictSsinChecksum() {
        return strictSsinChecksum;
    }

    public void setStrictSsinChecksum(boolean strictSsinChecksum) {
        this.strictSsinChecksum = strictSsinChecksum;
    }

    public boolean isSimulatePartialFailure() {
        return simulatePartialFailure;
    }

    public void setSimulatePartialFailure(boolean simulatePartialFailure) {
        this.simulatePartialFailure = simulatePartialFailure;
    }

    public String getHubOid() {
        return hubOid;
    }

    public void setHubOid(String hubOid) {
        this.hubOid = hubOid;
    }

    public String getHubEhp() {
        return hubEhp;
    }

    public void setHubEhp(String hubEhp) {
        this.hubEhp = hubEhp;
    }

    public String getHubName() {
        return hubName;
    }

    public void setHubName(String hubName) {
        this.hubName = hubName;
    }

    public String getServerBaseUrl() {
        return serverBaseUrl;
    }

    public void setServerBaseUrl(String serverBaseUrl) {
        this.serverBaseUrl = serverBaseUrl;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public long getContinuationTokenTtlSeconds() {
        return continuationTokenTtlSeconds;
    }

    public void setContinuationTokenTtlSeconds(long continuationTokenTtlSeconds) {
        this.continuationTokenTtlSeconds = continuationTokenTtlSeconds;
    }

    public int getContinuationTokenCacheSize() {
        return continuationTokenCacheSize;
    }

    public void setContinuationTokenCacheSize(int continuationTokenCacheSize) {
        this.continuationTokenCacheSize = continuationTokenCacheSize;
    }

    public List<String> getWithdrawnReferences() {
        return withdrawnReferences;
    }

    public void setWithdrawnReferences(List<String> withdrawnReferences) {
        this.withdrawnReferences = withdrawnReferences;
    }

    public Map<String, String> getPdfRenderings() {
        return pdfRenderings;
    }

    public void setPdfRenderings(Map<String, String> pdfRenderings) {
        this.pdfRenderings = pdfRenderings;
    }
}
