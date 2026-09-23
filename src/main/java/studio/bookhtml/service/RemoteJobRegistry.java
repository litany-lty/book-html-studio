package studio.bookhtml.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.store.DurableJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent registry for asynchronous remote OCR jobs (B05 / G04 / CONC-12).
 * Tracks remote handles, state transitions, polling count, crash recovery,
 * and maintains active remote debt slots across restart and poll intervals.
 */
@Component
public class RemoteJobRegistry {
    public static final int MAX_ACTIVE_REMOTE_JOBS = 3;
    public static final int DEFAULT_MAX_POLLS = 36;
    public static final Duration DEFAULT_JOB_TTL = Duration.ofMinutes(15);

    public static final String STATE_RESERVED = "RESERVED";
    public static final String STATE_SUBMITTING = "SUBMITTING";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_TERMINAL_PROVEN = "TERMINAL_PROVEN";
    public static final String STATE_SUBMIT_UNKNOWN = "SUBMIT_UNKNOWN";
    public static final String STATE_REMOTE_UNKNOWN = "REMOTE_UNKNOWN";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteJobRecord(
            String handleId,
            String bookId,
            int page,
            String provider,
            String accountScope,
            String remoteJobId,
            String physicalCallId,
            String state,
            int pollCount,
            int maxPolls,
            Instant nextPollAt,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            String inputFingerprint,
            String usageAttemptId,
            String failureReason
    ) {
        public boolean isActive() {
            return STATE_RESERVED.equals(state)
                    || STATE_SUBMITTING.equals(state)
                    || STATE_RUNNING.equals(state)
                    || STATE_SUBMIT_UNKNOWN.equals(state)
                    || STATE_REMOTE_UNKNOWN.equals(state);
        }

        public boolean isTerminal() {
            return STATE_TERMINAL_PROVEN.equals(state);
        }
    }

    private final Path jobsDir;
    private final ObjectMapper json;
    private final Object lock = new Object();
    private final Map<String, RemoteJobRecord> activeRecords = new ConcurrentHashMap<>();

    @Autowired
    public RemoteJobRegistry(AppProperties properties, ObjectMapper json) {
        this(properties.dataDir().toAbsolutePath().normalize(), json);
    }

    public RemoteJobRegistry(Path dataDir, ObjectMapper json) {
        Path resolved = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        try {
            Files.createDirectories(resolved);
            resolved = resolved.toRealPath();
        } catch (IOException ignored) {}
        this.jobsDir = resolved.resolve("remote-jobs");
        this.json = Objects.requireNonNull(json, "json");
        recover();
    }

    private Path jobPath(String handleId) {
        return jobsDir.resolve(handleId + ".json");
    }

    public void recover() {
        synchronized (lock) {
            activeRecords.clear();
            if (!Files.exists(jobsDir, LinkOption.NOFOLLOW_LINKS)) return;
            try (var stream = Files.list(jobsDir)) {
                for (Path file : stream.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".json")) continue;
                    try {
                        RemoteJobRecord record = json.readValue(file.toFile(), RemoteJobRecord.class);
                        if (record != null && record.isActive()) {
                            activeRecords.put(record.handleId(), record);
                        }
                    } catch (Exception e) {
                        System.getLogger(RemoteJobRegistry.class.getName()).log(System.Logger.Level.WARNING,
                                "Failed to recover remote job from " + file + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.getLogger(RemoteJobRegistry.class.getName()).log(System.Logger.Level.WARNING,
                        "Failed to list remote jobs directory: " + e.getMessage());
            }
        }
    }

    public RemoteJobRecord register(String bookId, int page, String provider, String accountScope,
                                    String inputFingerprint, String usageAttemptId) throws IOException {
        synchronized (lock) {
            long activeCount = activeRecords.values().stream().filter(RemoteJobRecord::isActive).count();
            if (activeCount >= MAX_ACTIVE_REMOTE_JOBS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "活跃远端 OCR 任务已达上限（" + MAX_ACTIVE_REMOTE_JOBS + "），需等待在途任务完成");
            }

            // Check if existing active job matches input fingerprint
            if (inputFingerprint != null && !inputFingerprint.isBlank()) {
                for (RemoteJobRecord existing : activeRecords.values()) {
                    if (existing.isActive() && Objects.equals(existing.inputFingerprint(), inputFingerprint)) {
                        return existing;
                    }
                }
            }

            String handleId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            RemoteJobRecord record = new RemoteJobRecord(
                    handleId, bookId, page, provider, accountScope, null, null,
                    STATE_RESERVED, 0, DEFAULT_MAX_POLLS, now, now, now,
                    now.plus(DEFAULT_JOB_TTL), inputFingerprint, usageAttemptId, null
            );
            persist(record);
            activeRecords.put(handleId, record);
            return record;
        }
    }

    public RemoteJobRecord markSubmitting(String handleId, String physicalCallId) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), physicalCallId,
                    STATE_SUBMITTING, current.pollCount(), current.maxPolls(), current.nextPollAt(),
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), null
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord markRunning(String handleId, String remoteJobId) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), remoteJobId, current.physicalCallId(),
                    STATE_RUNNING, current.pollCount(), current.maxPolls(), current.nextPollAt(),
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), null
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord recordPoll(String handleId, Instant nextPollAt) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), current.physicalCallId(),
                    current.state(), current.pollCount() + 1, current.maxPolls(), nextPollAt,
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), null
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord markTerminal(String handleId, String terminalState, String reason) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), current.physicalCallId(),
                    STATE_TERMINAL_PROVEN, current.pollCount(), current.maxPolls(), null,
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), reason
            );
            persist(updated);
            activeRecords.remove(handleId);
            return updated;
        }
    }

    public RemoteJobRecord markSubmitUnknown(String handleId, String reason) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), current.physicalCallId(),
                    STATE_SUBMIT_UNKNOWN, current.pollCount(), current.maxPolls(), null,
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), reason
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord markRemoteUnknown(String handleId, String reason) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), current.physicalCallId(),
                    STATE_REMOTE_UNKNOWN, current.pollCount(), current.maxPolls(), null,
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), reason
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord get(String handleId) {
        return activeRecords.get(handleId);
    }

    public RemoteJobRecord findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return null;
        for (RemoteJobRecord record : activeRecords.values()) {
            if (Objects.equals(record.inputFingerprint(), fingerprint)) {
                return record;
            }
        }
        return null;
    }

    public List<RemoteJobRecord> findActiveJobs() {
        return List.copyOf(activeRecords.values());
    }

    public int activeJobCount() {
        return (int) activeRecords.values().stream().filter(RemoteJobRecord::isActive).count();
    }

    private RemoteJobRecord getRequired(String handleId) {
        RemoteJobRecord record = activeRecords.get(handleId);
        if (record == null) throw new NoSuchElementException("remote job not found: " + handleId);
        return record;
    }

    private void persist(RemoteJobRecord record) throws IOException {
        Files.createDirectories(jobsDir);
        Path target = jobPath(record.handleId());
        DurableJson.write(target, record, json, 128 * 1024);
    }
}
