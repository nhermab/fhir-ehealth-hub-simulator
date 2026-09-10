package be.ehealth.hub.simulator.service;

import be.ehealth.hub.simulator.config.HubSimulatorProperties;
import be.ehealth.hub.simulator.service.DocumentRepository.SearchFilter;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Issues the opaque continuation tokens used for POST-based pagination.
 *
 * <p>transactions.md §2.2 requires that pagination stays on HTTP POST and that
 * "server-generated continuation tokens/parameters MUST remain opaque to the consumer". Keeping
 * the query criteria server-side also means the patient SSIN never appears in the
 * {@code Bundle.link} URL that a consumer might log or bookmark.
 */
@Service
public class SearchContinuationStore {

    private final HubSimulatorProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tokens;

    public SearchContinuationStore(HubSimulatorProperties properties) {
        this.properties = properties;
        this.tokens = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > Math.max(1, SearchContinuationStore.this.properties.getContinuationTokenCacheSize());
            }
        };
    }

    /** Stores the query for the next page and returns the token that replays it. */
    public String issue(SearchFilter filter) {
        byte[] material = new byte[24];
        random.nextBytes(material);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        synchronized (tokens) {
            tokens.put(token, new Entry(filter, Instant.now()));
        }
        return token;
    }

    /** Resolves a token that is still within its time to live. */
    public Optional<SearchFilter> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Duration ttl = Duration.ofSeconds(properties.getContinuationTokenTtlSeconds());
        synchronized (tokens) {
            Entry entry = tokens.get(token);
            if (entry == null) {
                return Optional.empty();
            }
            if (Duration.between(entry.issued(), Instant.now()).compareTo(ttl) > 0) {
                tokens.remove(token);
                return Optional.empty();
            }
            return Optional.of(entry.filter());
        }
    }

    private record Entry(SearchFilter filter, Instant issued) {
    }
}
