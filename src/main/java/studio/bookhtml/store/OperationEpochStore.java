package studio.bookhtml.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.ReprocessOperation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import studio.bookhtml.config.AppProperties;

/**
 * Manages operationEpoch and durable reprocess operations per book (G07 / B04-03, B04-05).
 * Enforces bounded 4096 operations, 30-day retention for terminal receipts,
 * whole-epoch archival, and HTTP 410 Gone for expired epochs.
 */
@Component
public class OperationEpochStore {
    public static final int MAX_OPERATIONS_PER_EPOCH = 4096;
    public static final Duration MIN_RETENTION = Duration.ofDays(30);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EpochState(
            long epoch,
            Instant epochStartedAt,
            Map<String, ReprocessOperation> activeOperations,
            Map<String, ReprocessOperation> archivedOperations
    ) {
        public EpochState {
            activeOperations = activeOperations == null ? Map.of() : Map.copyOf(activeOperations);
            archivedOperations = archivedOperations == null ? Map.of() : Map.copyOf(archivedOperations);
        }

        public static EpochState initial() {
            return new EpochState(1L, Instant.now(), Map.of(), Map.of());
        }
    }

    private final Path dataDir;
    private final ObjectMapper json;
    private final Object lock = new Object();
    private final Map<String, EpochState> bookStates = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public OperationEpochStore(AppProperties properties, ObjectMapper json) {
        this(properties.dataDir().toAbsolutePath().normalize(), json);
    }

    public OperationEpochStore(Path dataDir, ObjectMapper json) {
        Path resolved = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        try {
            Files.createDirectories(resolved);
            resolved = resolved.toRealPath();
        } catch (IOException ignored) {}
        this.dataDir = resolved;
        this.json = Objects.requireNonNull(json, "json");
    }

    private Path bookEpochPath(String bookId) {
        return dataDir.resolve("books").resolve(bookId).resolve("operation-epoch.json");
    }

    private Path archiveDir(String bookId) {
        return dataDir.resolve("books").resolve(bookId).resolve("operation-epochs");
    }

    private Path archivedEpochPath(String bookId, long epoch) {
        return archiveDir(bookId).resolve(String.format("epoch-%06d.json", epoch));
    }

    public EpochState getState(String bookId) {
        Objects.requireNonNull(bookId, "bookId");
        EpochState cached = bookStates.get(bookId);
        if (cached != null) return cached;

        synchronized (lock) {
            Path path = bookEpochPath(bookId);
            if (!Files.exists(path)) {
                EpochState init = EpochState.initial();
                try {
                    writeState(bookId, init);
                } catch (IOException ignored) {}
                return init;
            }
            try {
                EpochState state = json.readValue(path.toFile(), EpochState.class);
                bookStates.put(bookId, state);
                return state;
            } catch (Exception e) {
                return EpochState.initial();
            }
        }
    }

    public ReprocessOperation findOperation(String bookId, String operationKey, Long clientEpoch, Instant now) {
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(operationKey, "operationKey");
        if (now == null) now = Instant.now();

        synchronized (lock) {
            EpochState state = getState(bookId);

            // If client specified an epoch
            if (clientEpoch != null) {
                if (clientEpoch > state.epoch()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "非法 operationEpoch");
                }
                if (clientEpoch < state.epoch()) {
                    // Check archived epoch file
                    ReprocessOperation archived = readArchivedOperation(bookId, clientEpoch, operationKey);
                    if (archived != null) {
                        return archived;
                    }
                    throw new ApiException(HttpStatus.GONE, "操作已过期 (epoch " + clientEpoch + ")，请确认后重新发起；未重新派发");
                }
            }

            // Check active in current epoch
            ReprocessOperation op = state.activeOperations().get(operationKey);
            if (op != null) return op;

            // Check archived in current state
            ReprocessOperation localArchived = state.archivedOperations().get(operationKey);
            if (localArchived != null) return localArchived;

            // Check previous archived epochs if no client epoch specified
            if (state.epoch() > 1) {
                for (long e = state.epoch() - 1; e >= 1; e--) {
                    ReprocessOperation prev = readArchivedOperation(bookId, e, operationKey);
                    if (prev != null) return prev;
                }
            }

            return null;
        }
    }

    private ReprocessOperation readArchivedOperation(String bookId, long epoch, String operationKey) {
        Path p = archivedEpochPath(bookId, epoch);
        if (!Files.exists(p)) return null;
        try {
            EpochState archivedState = json.readValue(p.toFile(), EpochState.class);
            if (archivedState != null && archivedState.activeOperations() != null) {
                return archivedState.activeOperations().get(operationKey);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public ReprocessOperation recordOperation(String bookId, String operationKey, ReprocessOperation op, Long expectedEpoch) throws IOException {
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(op, "op");

        synchronized (lock) {
            EpochState current = getState(bookId);
            if (expectedEpoch != null && expectedEpoch != current.epoch()) {
                throw new ApiException(HttpStatus.GONE, "操作 epoch 已变化 (当前 " + current.epoch() + "，预期 " + expectedEpoch + ")，请刷新后重试");
            }

            Map<String, ReprocessOperation> active = new LinkedHashMap<>(current.activeOperations());

            if (active.size() >= MAX_OPERATIONS_PER_EPOCH) {
                // Try whole-epoch rotation / archive
                current = tryRotateEpoch(bookId, current);
                active = new LinkedHashMap<>(current.activeOperations());
                if (active.size() >= MAX_OPERATIONS_PER_EPOCH) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "本书操作回执容量已满 (" + MAX_OPERATIONS_PER_EPOCH + ")，需归档维护；重启不会清空幂等保护");
                }
            }

            active.put(operationKey, op);
            EpochState next = new EpochState(current.epoch(), current.epochStartedAt(), active, current.archivedOperations());
            writeState(bookId, next);
            return op;
        }
    }

    public void updateOperation(String bookId, String operationKey, String lifecycle, Instant now) throws IOException {
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(operationKey, "operationKey");
        synchronized (lock) {
            EpochState current = getState(bookId);
            ReprocessOperation existing = current.activeOperations().get(operationKey);
            if (existing == null) return;

            ReprocessOperation updated = existing.finish(lifecycle, now);
            Map<String, ReprocessOperation> active = new LinkedHashMap<>(current.activeOperations());
            active.put(operationKey, updated);
            EpochState next = new EpochState(current.epoch(), current.epochStartedAt(), active, current.archivedOperations());
            writeState(bookId, next);
        }
    }

    private EpochState tryRotateEpoch(String bookId, EpochState current) throws IOException {
        Instant now = Instant.now();
        Instant cutoff = now.minus(MIN_RETENTION);

        // Can only close an epoch if terminal operations exist that can be archived
        Map<String, ReprocessOperation> toArchive = new LinkedHashMap<>();
        Map<String, ReprocessOperation> remaining = new LinkedHashMap<>();

        for (var entry : current.activeOperations().entrySet()) {
            ReprocessOperation op = entry.getValue();
            boolean isTerminal = op.terminal();
            boolean isUnknown = "UNKNOWN".equals(op.lifecycle());
            boolean isOldEnough = op.acceptedAt().isBefore(cutoff);

            // Active, UNKNOWN, or younger than 30 days are retained
            if (!isTerminal || isUnknown || !isOldEnough) {
                remaining.put(entry.getKey(), op);
            } else {
                toArchive.put(entry.getKey(), op);
            }
        }

        if (toArchive.isEmpty()) {
            // Cannot rotate if all 4096 are non-archivable
            return current;
        }

        // Archive the whole closed epoch to a file
        Files.createDirectories(archiveDir(bookId));
        Path archivePath = archivedEpochPath(bookId, current.epoch());
        EpochState archivedEpoch = new EpochState(current.epoch(), current.epochStartedAt(), toArchive, Map.of());
        DurableJson.write(archivePath, archivedEpoch, json, 1024 * 1024);

        long nextEpoch = current.epoch() + 1;
        EpochState nextState = new EpochState(nextEpoch, now, remaining, Map.of());
        writeState(bookId, nextState);
        return nextState;
    }

    private void writeState(String bookId, EpochState state) throws IOException {
        Path p = bookEpochPath(bookId);
        if (Files.exists(p.getParent())) {
            DurableJson.write(p, state, json, 1024 * 1024);
        }
        bookStates.put(bookId, state);
    }
}
