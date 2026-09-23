package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.ReadingPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import studio.bookhtml.config.AppProperties;

/**
 * Storage for reading policy per subject (G06).
 */
@Component
public class ReadingPolicyStore {
    private final Path dataDir;
    private final ObjectMapper json;
    private final Object lock = new Object();
    private final Map<String, ReadingPolicy> policyCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ReadingPolicyStore(AppProperties properties, ObjectMapper json) {
        this(properties.dataDir().toAbsolutePath().normalize(), json);
    }

    public ReadingPolicyStore(Path dataDir, ObjectMapper json) {
        Path resolved = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        try {
            Files.createDirectories(resolved);
            resolved = resolved.toRealPath();
        } catch (IOException ignored) {}
        this.dataDir = resolved;
        this.json = Objects.requireNonNull(json, "json");
    }

    private Path policiesDir() {
        return dataDir.resolve("reading-policies");
    }

    private Path policyPath(String subjectId) {
        return policiesDir().resolve(subjectId + ".json");
    }

    public ReadingPolicy getPolicy(String subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        ReadingPolicy cached = policyCache.get(subjectId);
        if (cached != null) return cached;

        synchronized (lock) {
            Path path = policyPath(subjectId);
            if (!Files.exists(path)) {
                ReadingPolicy def = ReadingPolicy.defaultPolicy(subjectId);
                try {
                    writePolicy(def);
                } catch (IOException ignored) {}
                return def;
            }
            try {
                ReadingPolicy policy = json.readValue(path.toFile(), ReadingPolicy.class);
                policyCache.put(subjectId, policy);
                return policy;
            } catch (Exception e) {
                return ReadingPolicy.defaultPolicy(subjectId);
            }
        }
    }

    public ReadingPolicy updatePolicy(String subjectId, long expectedRevision, ReadingPolicy.WindowConfig newWindow) throws IOException {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(newWindow, "newWindow");

        synchronized (lock) {
            ReadingPolicy current = getPolicy(subjectId);
            if (current.policyRevision() != expectedRevision) {
                throw new ApiException(HttpStatus.CONFLICT, "ReadingPolicy revision mismatch: expected "
                        + expectedRevision + " but was " + current.policyRevision());
            }

            long nextRevision = current.policyRevision() + 1;
            ReadingPolicy updated = new ReadingPolicy(
                    current.schemaVersion(),
                    nextRevision,
                    subjectId,
                    newWindow,
                    current.validConsents(),
                    current.operationEpoch(),
                    Instant.now()
            );

            writePolicy(updated);
            return updated;
        }
    }

    public ReadingPolicy updateConsents(String subjectId, java.util.List<ReadingPolicy.ConsentSummary> validConsents) throws IOException {
        Objects.requireNonNull(subjectId, "subjectId");
        synchronized (lock) {
            ReadingPolicy current = getPolicy(subjectId);
            long nextRevision = current.policyRevision() + 1;
            ReadingPolicy updated = new ReadingPolicy(
                    current.schemaVersion(),
                    nextRevision,
                    subjectId,
                    current.defaultWindow(),
                    validConsents,
                    current.operationEpoch(),
                    Instant.now()
            );
            writePolicy(updated);
            return updated;
        }
    }

    private void writePolicy(ReadingPolicy policy) throws IOException {
        Files.createDirectories(policiesDir());
        Path path = policyPath(policy.subjectId());
        DurableJson.write(path, policy, json, 256 * 1024);
        policyCache.put(policy.subjectId(), policy);
    }
}
