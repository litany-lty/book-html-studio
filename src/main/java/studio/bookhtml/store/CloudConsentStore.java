package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.CloudConsent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable storage for cloud model consents (G06).
 * Manages per-consent records under consents/<consentId>.json and per-book shortcuts.
 */
@Component
public class CloudConsentStore {
    private final Path dataDir;
    private final ObjectMapper json;
    private final Object lock = new Object();
    private final Map<UUID, CloudConsent> memoryCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CloudConsentStore(AppProperties properties, ObjectMapper json) {
        this(properties.dataDir().toAbsolutePath().normalize(), json);
    }

    public CloudConsentStore(Path dataDir, ObjectMapper json) {
        Path resolved = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        try {
            Files.createDirectories(resolved);
            resolved = resolved.toRealPath();
        } catch (IOException ignored) {}
        this.dataDir = resolved;
        this.json = Objects.requireNonNull(json, "json");
    }

    private Path consentsDir() {
        return dataDir.resolve("consents");
    }

    private Path consentPath(UUID consentId) {
        return consentsDir().resolve(consentId.toString() + ".json");
    }

    private Path bookConsentPath(String bookId) {
        return dataDir.resolve("books").resolve(bookId).resolve("cloud-consent.json");
    }

    public CloudConsent write(CloudConsent consent) throws IOException {
        Objects.requireNonNull(consent, "consent");
        synchronized (lock) {
            Files.createDirectories(consentsDir());
            Path path = consentPath(consent.consentId());
            DurableJson.write(path, consent, json, 256 * 1024);

            if ("BOOK".equals(consent.scope().kind()) && consent.scope().bookId() != null) {
                Path bp = bookConsentPath(consent.scope().bookId());
                if (Files.exists(bp.getParent())) {
                    DurableJson.write(bp, consent, json, 256 * 1024);
                }
            }

            memoryCache.put(consent.consentId(), consent);
            return consent;
        }
    }

    public CloudConsent read(UUID consentId) {
        if (consentId == null) return null;
        CloudConsent cached = memoryCache.get(consentId);
        if (cached != null) return cached;

        synchronized (lock) {
            Path path = consentPath(consentId);
            if (!Files.exists(path)) return null;
            try {
                CloudConsent consent = json.readValue(path.toFile(), CloudConsent.class);
                memoryCache.put(consentId, consent);
                return consent;
            } catch (Exception e) {
                return null;
            }
        }
    }

    public CloudConsent findActiveConsent(String subjectId, String bookId) {
        Objects.requireNonNull(subjectId, "subjectId");
        synchronized (lock) {
            // Check book-specific consent first
            if (bookId != null) {
                Path bp = bookConsentPath(bookId);
                if (Files.exists(bp)) {
                    try {
                        CloudConsent c = json.readValue(bp.toFile(), CloudConsent.class);
                        if (c != null && subjectId.equals(c.subjectId()) && c.permitsBook(bookId)) {
                            return c;
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Scan consents directory for valid matching consent
            Path dir = consentsDir();
            if (!Files.exists(dir)) return null;
            try (var stream = Files.list(dir)) {
                for (Path p : stream.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        CloudConsent c = json.readValue(p.toFile(), CloudConsent.class);
                        if (c != null && subjectId.equals(c.subjectId()) && c.permitsBook(bookId)) {
                            return c;
                        }
                    } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}
            return null;
        }
    }

    public List<CloudConsent> listForSubject(String subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        synchronized (lock) {
            Path dir = consentsDir();
            if (!Files.exists(dir)) return List.of();
            List<CloudConsent> result = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path p : stream.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        CloudConsent c = json.readValue(p.toFile(), CloudConsent.class);
                        if (c != null && subjectId.equals(c.subjectId())) {
                            result.add(c);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}
            result.sort(Comparator.comparing(CloudConsent::issuedAt).reversed());
            return List.copyOf(result);
        }
    }

    public CloudConsent revoke(UUID consentId, Instant now) throws IOException {
        Objects.requireNonNull(consentId, "consentId");
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            CloudConsent current = read(consentId);
            if (current == null) return null;
            if (current.revokedAt() != null) return current;

            CloudConsent revoked = current.withRevocation(now);
            write(revoked);
            return revoked;
        }
    }
}
