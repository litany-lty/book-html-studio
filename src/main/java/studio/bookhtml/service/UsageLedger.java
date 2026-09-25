package studio.bookhtml.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.store.AtomicManifestStore;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.DurableEventJournal;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-book durable append-only physical-call ledger (v2).
 * Backed by bounded event journals (WAL), in-memory state, checkpoints,
 * atomic manifests, and cursor/frozen snapshot pagination.
 */
@Service
public class UsageLedger {
    private static final int MAX_ENTRY_BYTES = 8_192;
    private static final Set<PosixFilePermission> DIR_PERMS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final BookStore books;
    private final SettingsService settings;
    private final ObjectMapper json;
    private final AtomicManifestStore manifestStore;
    private final DurableEventJournal journal = new DurableEventJournal();
    private final Object[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    private final ConcurrentHashMap<String, BookLedgerState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FrozenSnapshot> snapshots = new ConcurrentHashMap<>();

    public record Entry(String id, String bookId, Instant createdAt, Instant updatedAt,
                        Integer pageNumber, String operation, String provider, String model,
                        String status, Long inputTokens, Long outputTokens, String feeKind,
                        String currency, String amount, SettingsService.Rate priceSnapshot,
                        String executionId, String taskHash, Long attemptSeq) {
        public Entry(String id, String bookId, Instant createdAt, Instant updatedAt,
                     Integer pageNumber, String operation, String provider, String model,
                     String status, Long inputTokens, Long outputTokens, String feeKind,
                     String currency, String amount, SettingsService.Rate priceSnapshot,
                     String executionId, String taskHash) {
            this(id, bookId, createdAt, updatedAt, pageNumber, operation, provider, model, status, inputTokens,
                    outputTokens, feeKind, currency, amount, priceSnapshot, executionId, taskHash, null);
        }
        public Entry(String id, String bookId, Instant createdAt, Instant updatedAt,
                     Integer pageNumber, String operation, String provider, String model,
                     String status, Long inputTokens, Long outputTokens, String feeKind,
                     String currency, String amount, SettingsService.Rate priceSnapshot) {
            this(id, bookId, createdAt, updatedAt, pageNumber, operation, provider, model, status, inputTokens,
                    outputTokens, feeKind, currency, amount, priceSnapshot, null, null);
        }
    }

    public record LedgerEvent(
            int schemaVersion,
            String eventId,
            long ledgerSeq,
            String physicalCallId,
            String logicalCallId,
            String executionKind,
            String attemptId,
            Long attemptSeq,
            String decisionJobId,
            String bookId,
            Integer pageNumber,
            String provider,
            String model,
            String credentialScopeId,
            Integer policyRevision,
            String transition,
            String requestFingerprint,
            Instant createdAt,
            Long inputTokens,
            Long outputTokens,
            String feeKind,
            String currency,
            String amount,
            SettingsService.Rate priceSnapshot,
            String previousEventHash
    ) {}

    public record Checkpoint(
            int schemaVersion,
            String bookId,
            long generation,
            long appliedThroughSeq,
            String lastEventHash,
            List<Entry> entries,
            Map<String, Object> totals,
            Map<String, Object> byProvider,
            Instant createdAt
    ) {}

    private record FrozenSnapshot(
            String token,
            String bookId,
            long generation,
            long ledgerSeq,
            Instant createdAt,
            Instant expiresAt,
            List<Entry> frozenEntries,
            Totals frozenTotals,
            Map<String, Totals> frozenByProvider
    ) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static final class BookLedgerState {
        final String bookId;
        long generation = 1;
        long appliedThroughSeq = 0;
        String lastEventHash = "0000000000000000000000000000000000000000000000000000000000000000";
        UUID activeSegmentId = UUID.randomUUID();
        int activeSegmentCount = 0;
        long activeSegmentBytes = 0;
        final Map<String, Entry> entries = new LinkedHashMap<>();
        final List<Entry> ordered = new ArrayList<>();
        final Totals totals = new Totals();
        final Map<String, Totals> byProvider = new LinkedHashMap<>();
        final List<AtomicManifestStore.SegmentRef> sealedSegments = new ArrayList<>();
        boolean recoveryRequired = false;
        String recoveryError = null;

        BookLedgerState(String bookId) {
            this.bookId = bookId;
        }
    }

    public UsageLedger(BookStore books, SettingsService settings, ObjectMapper json) {
        this.books = books;
        this.settings = settings;
        this.json = json;
        this.manifestStore = new AtomicManifestStore(json);
    }

    private Object lock(String bookId) {
        return locks[Math.floorMod(bookId.hashCode(), locks.length)];
    }

    public String start(String provider, String model) throws IOException {
        return record(provider, model, "SENT_UNKNOWN");
    }

    public String prepare(String provider, String model) throws IOException {
        return record(provider, model, "PREPARED");
    }

    public void sending(String id) throws IOException {
        change(id, "SENT_UNKNOWN", null, null);
    }

    public void notSent(String id) throws IOException {
        change(id, "NOT_SENT", null, null);
    }

    public void unknown(String id) throws IOException {
        change(id, "OUTCOME_UNKNOWN", null, null);
    }

    public void pending(String id) throws IOException {
        change(id, "PENDING", null, null);
    }

    public void succeeded(String id) throws IOException {
        change(id, "SUCCEEDED", null, null);
    }

    public void failed(String id) throws IOException {
        change(id, "FAILED", null, null);
    }

    public void usage(String id, Long inputTokens, Long outputTokens) throws IOException {
        if (inputTokens != null && inputTokens < 0 || outputTokens != null && outputTokens < 0) {
            throw new IOException("invalid usage");
        }
        change(id, null, inputTokens, outputTokens);
    }

    public void captureUsage(String id, JsonNode root) throws IOException {
        if (root == null) return;
        JsonNode node = root.path("usage");
        if (!node.isObject()) node = root.path("data").path("usage");
        if (!node.isObject()) return;
        Long input = firstLong(node, "input_tokens", "prompt_tokens", "inputTokens");
        Long output = firstLong(node, "output_tokens", "completion_tokens", "outputTokens");
        if (input != null || output != null) usage(id, input, output);
    }

    private static Long firstLong(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0)
                return value.longValue();
        }
        return null;
    }

    public void cacheReused(String provider, String model) throws IOException {
        UsageContext.Value context = requireContext();
        String bookId = context.bookId();
        synchronized (lock(bookId)) {
            BookLedgerState state = ensureLoaded(bookId);
            String id = UUID.randomUUID().toString();
            Instant now = Instant.now();
            Entry entry = new Entry(id, bookId, now, now, context.pageNumber(),
                    context.operation(), provider, model, "CACHE_REUSED", null, null,
                    "CACHE_REUSE", null, null, null, executionId(context), taskHash(context), attemptSeq());
            appendEventAndApply(state, entry, "CACHE_REUSED");
        }
    }

    private String record(String provider, String model, String status) throws IOException {
        UsageContext.Value context = requireContext();
        String bookId = context.bookId();
        synchronized (lock(bookId)) {
            BookLedgerState state = ensureLoaded(bookId);
            String id = UUID.randomUUID().toString();
            SettingsService.Rate rate = settings.state().billingRates().stream()
                    .filter(r -> r.provider().equals(provider) && r.model().equals(model)).findFirst().orElse(null);
            Instant now = Instant.now();
            Entry entry = new Entry(id, bookId, now, now, context.pageNumber(), context.operation(),
                    provider, model, status, null, null, "UNKNOWN", null, null, rate,
                    executionId(context), taskHash(context), attemptSeq());
            appendEventAndApply(state, entry, status);
            return id;
        }
    }

    private void change(String id, String status, Long inputTokens, Long outputTokens) throws IOException {
        UsageContext.Value context = requireContext();
        String bookId = context.bookId();
        synchronized (lock(bookId)) {
            BookLedgerState state = ensureLoaded(bookId);
            Entry old = state.entries.get(id);
            if (old == null || "CACHE_REUSED".equals(old.status())) {
                throw new IOException("usage attempt absent");
            }
            if ("SENT_UNKNOWN".equals(status) && !"PREPARED".equals(old.status())) {
                throw new IOException("send intent is not prepared");
            }
            if ("PREPARED".equals(old.status()) && status != null
                    && !Set.of("SENT_UNKNOWN", "NOT_SENT").contains(status)) {
                throw new IOException("prepared request has not been sent");
            }
            if (status != null && Set.of("SUCCEEDED", "FAILED", "NOT_SENT").contains(old.status())
                    && !old.status().equals(status)) {
                throw new IOException("usage terminal outcome already recorded");
            }
            if ("NOT_SENT".equals(old.status()) && (inputTokens != null || outputTokens != null)) {
                throw new IOException("unsent request cannot have token usage");
            }
            Long input = inputTokens == null ? old.inputTokens() : inputTokens;
            Long output = outputTokens == null ? old.outputTokens() : outputTokens;
            String nextStatus = status == null ? old.status() : status;
            boolean requestPriced = old.priceSnapshot() != null && !old.priceSnapshot().perRequest().isEmpty();
            Estimate estimate = requestPriced && !"SUCCEEDED".equals(nextStatus)
                    ? null : estimate(old.priceSnapshot(), input, output);

            Entry updated = new Entry(old.id(), old.bookId(), old.createdAt(), Instant.now(),
                    old.pageNumber(), old.operation(), old.provider(), old.model(), nextStatus,
                    input, output, estimate == null ? "UNKNOWN" : "ESTIMATED",
                    estimate == null ? null : estimate.currency(),
                    estimate == null ? null : estimate.amount(), old.priceSnapshot(), old.executionId(), old.taskHash(), old.attemptSeq());

            appendEventAndApply(state, updated, nextStatus);
        }
    }

    private record Estimate(String currency, String amount) {}

    private static Estimate estimate(SettingsService.Rate rate, Long input, Long output) {
        if (rate == null) return null;
        if (!rate.perRequest().isEmpty()) return new Estimate(rate.currency(), amount(new BigDecimal(rate.perRequest())));
        if (input == null || output == null || rate.inputPerMillion().isEmpty() || rate.outputPerMillion().isEmpty())
            return null;
        BigDecimal total = new BigDecimal(rate.inputPerMillion()).multiply(BigDecimal.valueOf(input))
                .add(new BigDecimal(rate.outputPerMillion()).multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
        return new Estimate(rate.currency(), amount(total));
    }

    private static String amount(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public Map<String, Object> view(String bookId, int offset, int limit) throws IOException {
        return view(bookId, offset, limit, null, null, null);
    }

    public Map<String, Object> view(String bookId, int offset, int limit, String asOfValue) throws IOException {
        return view(bookId, offset, limit, asOfValue, null, null);
    }

    public Map<String, Object> view(String bookId, int offset, int limit, String asOfValue, String cursorValue) throws IOException {
        return view(bookId, offset, limit, asOfValue, cursorValue, null);
    }

    public Map<String, Object> view(String bookId, int offset, int limit, String asOfValue, String cursorValue, String snapshotToken) throws IOException {
        if (offset < 0 || offset > 10_000 || limit < 1 || limit > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "用量分页参数无效");
        EntryPosition cursor = parseCursor(cursorValue);
        if (cursor != null && offset != 0) throw new ApiException(HttpStatus.BAD_REQUEST, "游标分页不使用 offset");

        Instant asOf;
        try {
            asOf = asOfValue == null || asOfValue.isBlank() ? Instant.now() : Instant.parse(asOfValue);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "用量截止时间无效");
        }

        synchronized (lock(bookId)) {
            BookLedgerState state = ensureLoaded(bookId);

            List<Entry> sourceEntries;
            Totals effectiveTotals;
            Map<String, Totals> effectiveByProvider;
            String token = snapshotToken;
            String consistency;

            if (snapshotToken != null && !snapshotToken.isBlank()) {
                FrozenSnapshot snap = snapshots.get(snapshotToken);
                if (snap == null || snap.isExpired() || !snap.bookId().equals(bookId)) {
                    throw new ApiException(HttpStatus.GONE, "分页快照已过期或无效");
                }
                sourceEntries = snap.frozenEntries();
                effectiveTotals = snap.frozenTotals();
                effectiveByProvider = snap.frozenByProvider();
                consistency = "FROZEN_LEDGER_PREFIX";
            } else {
                sourceEntries = new ArrayList<>(state.ordered);
                effectiveTotals = state.totals.copy();
                effectiveByProvider = new LinkedHashMap<>();
                state.byProvider.forEach((k, v) -> effectiveByProvider.put(k, v.copy()));
                consistency = asOfValue != null ? "LEGACY_CREATED_CUTOFF" : "FROZEN_LEDGER_PREFIX";

                // Create a frozen snapshot token
                token = UUID.randomUUID().toString();
                cleanupExpiredSnapshots();
                if (snapshots.size() >= 16) {
                    // Evict oldest snapshot
                    snapshots.keySet().stream().findFirst().ifPresent(snapshots::remove);
                }
                snapshots.put(token, new FrozenSnapshot(token, bookId, state.generation, state.appliedThroughSeq,
                        Instant.now(), Instant.now().plusSeconds(300), List.copyOf(sourceEntries),
                        effectiveTotals, Collections.unmodifiableMap(effectiveByProvider)));
            }

            int retain = (cursor == null ? offset : 0) + limit;
            Comparator<Entry> order = Comparator.comparing(Entry::createdAt).thenComparing(Entry::id);
            PriorityQueue<Entry> page = new PriorityQueue<>(retain + 1, order.reversed());
            Totals filteredTotals = new Totals();
            Map<String, Totals> filteredByProvider = new LinkedHashMap<>();
            Instant[] first = {null};
            long[] count = {0}, after = {0};

            for (Entry e : sourceEntries) {
                if (e.createdAt().isAfter(asOf)) continue;
                count[0]++;
                if (first[0] == null || e.createdAt().isBefore(first[0])) first[0] = e.createdAt();
                filteredTotals.add(e);
                filteredByProvider.computeIfAbsent(e.provider() + "\u0000" + e.model(), ignored -> new Totals()).add(e);
                if (cursor != null && (e.createdAt().compareTo(cursor.createdAt()) < 0
                        || e.createdAt().equals(cursor.createdAt()) && e.id().compareTo(cursor.id()) <= 0)) continue;
                after[0]++;
                if (page.size() < retain) page.add(e);
                else if (order.compare(e, page.peek()) < 0) {
                    page.poll();
                    page.add(e);
                }
            }

            List<Entry> selected = new ArrayList<>(page);
            selected.sort(order);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bookId", bookId);
            result.put("recordedSince", first[0] == null ? null : first[0].toString());
            result.put("asOf", asOf.toString());
            result.put("historicalCoverage", "SINCE_TRACKING_ONLY");
            result.put("totals", filteredTotals.view());
            List<Map<String, Object>> providers = new ArrayList<>();
            filteredByProvider.forEach((key, values) -> {
                int separator = key.indexOf('\u0000');
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("provider", key.substring(0, separator));
                row.put("model", key.substring(separator + 1));
                row.putAll(values.view());
                providers.add(row);
            });
            result.put("providers", providers);
            int from = Math.min(cursor == null ? offset : 0, selected.size());
            List<Entry> slice = selected.subList(from, selected.size());
            result.put("entries", slice.stream().map(UsageLedger::entryView).toList());
            boolean more = after[0] > retain;
            result.put("nextOffset", cursor == null && more && offset + limit <= 10_000 ? offset + limit : null);
            result.put("nextCursor", more && !slice.isEmpty() ? cursor(slice.get(slice.size() - 1)) : null);
            result.put("snapshotToken", token);
            result.put("consistency", consistency);
            return result;
        }
    }

    private void cleanupExpiredSnapshots() {
        snapshots.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    public void recordAdjustment(String bookId, String physicalCallId, Long inputDelta, Long outputDelta, String note) throws IOException {
        synchronized (lock(bookId)) {
            BookLedgerState state = ensureLoaded(bookId);
            Entry old = state.entries.get(physicalCallId);
            if (old == null) throw new IOException("original call not found for adjustment: " + physicalCallId);

            long nextInput = (old.inputTokens() == null ? 0 : old.inputTokens()) + (inputDelta == null ? 0 : inputDelta);
            long nextOutput = (old.outputTokens() == null ? 0 : old.outputTokens()) + (outputDelta == null ? 0 : outputDelta);
            if (nextInput < 0 || nextOutput < 0) throw new IOException("adjustment produces negative token count");

            Estimate estimate = estimate(old.priceSnapshot(), nextInput, nextOutput);
            Entry adjusted = new Entry(old.id(), old.bookId(), old.createdAt(), Instant.now(),
                    old.pageNumber(), old.operation(), old.provider(), old.model(), old.status(),
                    nextInput, nextOutput, estimate == null ? "UNKNOWN" : "ESTIMATED",
                    estimate == null ? null : estimate.currency(),
                    estimate == null ? null : estimate.amount(), old.priceSnapshot(), old.executionId(), old.taskHash(), old.attemptSeq());

            appendEventAndApply(state, adjusted, "ADJUSTMENT");
        }
    }

    public void archive(String bookId, Instant olderThan) throws IOException {
        synchronized (lock(bookId)) {
            BookLedgerState state = ensureLoaded(bookId);
            Path base = usageV2Dir(bookId);
            Path archiveDir = base.resolve("archives").resolve(UUID.randomUUID().toString());
            ensureDirectory(archiveDir);

            List<AtomicManifestStore.SegmentRef> remainingSealed = new ArrayList<>();
            List<AtomicManifestStore.SegmentRef> toArchive = new ArrayList<>();

            for (AtomicManifestStore.SegmentRef ref : state.sealedSegments) {
                // Check if segment has any active/unknown entries
                Path segPath = base.resolve(ref.filename());
                if (Files.exists(segPath, LinkOption.NOFOLLOW_LINKS)) {
                    DurableEventJournal.SegmentScanResult scan = journal.readSegment(segPath, bookId, false, null);
                    boolean hasUnknown = false;
                    for (DurableEventJournal.JournalFrame frame : scan.frames()) {
                        LedgerEvent ev = json.readValue(frame.payload(), LedgerEvent.class);
                        if ("SENT_UNKNOWN".equals(ev.transition()) || "OUTCOME_UNKNOWN".equals(ev.transition()) || "PENDING".equals(ev.transition())) {
                            hasUnknown = true;
                            break;
                        }
                    }
                    if (!hasUnknown && olderThan != null) {
                        toArchive.add(ref);
                        Path target = archiveDir.resolve(Paths.get(ref.filename()).getFileName().toString());
                        Files.move(segPath, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        remainingSealed.add(ref);
                    }
                }
            }

            if (!toArchive.isEmpty()) {
                state.sealedSegments.clear();
                state.sealedSegments.addAll(remainingSealed);
                state.generation++;
                publishManifestAndCheckpoint(state);
            }
        }
    }

    public AuditResult auditIntegrity(String bookId) {
        synchronized (lock(bookId)) {
            try {
                Path base = usageV2Dir(bookId);
                if (!manifestStore.exists(base)) {
                    if (Files.exists(usageDir(bookId))) {
                        checkLegacyDirForCorruption(bookId, null);
                    }
                    return new AuditResult(true, 0, 0, null);
                }
                AtomicManifestStore.Manifest manifest = manifestStore.read(base, bookId);
                long totalEvents = 0;
                long totalBytes = 0;

                for (AtomicManifestStore.SegmentRef ref : manifest.sealedSegments()) {
                    Path p = base.resolve(ref.filename());
                    if (!Files.exists(p)) return new AuditResult(false, totalEvents, totalBytes, "Missing sealed segment: " + ref.filename());
                    String hash = DurableEventJournal.sha256Hex(p);
                    if (!hash.equals(ref.sha256())) return new AuditResult(false, totalEvents, totalBytes, "Hash mismatch for " + ref.filename());
                    totalBytes += Files.size(p);
                    totalEvents += ref.recordCount();
                }

                if (manifest.activeSegment() != null) {
                    Path activePath = base.resolve(manifest.activeSegment().filename());
                    if (Files.exists(activePath)) {
                        DurableEventJournal.SegmentScanResult scan = journal.readSegment(activePath, bookId, false, null);
                        totalEvents += scan.frames().size();
                        totalBytes += Files.size(activePath);
                    }
                }
                return new AuditResult(true, totalEvents, totalBytes, null);
            } catch (Exception e) {
                return new AuditResult(false, 0, 0, e.getMessage());
            }
        }
    }

    public record AuditResult(boolean healthy, long totalEvents, long totalBytes, String errorMessage) {}

    public Map<String, Object> diagnose(String bookId) throws IOException {
        synchronized (lock(bookId)) {
            BookLedgerState state = ensureLoaded(bookId);
            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("bookId", bookId);
            diag.put("healthy", !state.recoveryRequired);
            diag.put("generation", state.generation);
            diag.put("appliedThroughSeq", state.appliedThroughSeq);
            diag.put("activeSegmentId", state.activeSegmentId.toString());
            diag.put("activeSegmentRecords", state.activeSegmentCount);
            diag.put("activeSegmentBytes", state.activeSegmentBytes);
            diag.put("sealedSegmentsCount", state.sealedSegments.size());
            diag.put("totalTrackedCalls", state.entries.size());
            return diag;
        }
    }

    private void appendEventAndApply(BookLedgerState state, Entry entry, String transition) throws IOException {
        validateEntry(state.bookId, entry.id() + ".json", entry);
        state.appliedThroughSeq++;
        long seq = state.appliedThroughSeq;
        String eventId = UUID.randomUUID().toString();

        LedgerEvent event = new LedgerEvent(
                1, eventId, seq, entry.id(), entry.id(), "PAGE",
                entry.executionId(), entry.attemptSeq(), null, entry.bookId(), entry.pageNumber(),
                entry.provider(), entry.model(), null, 0, transition,
                entry.taskHash(), entry.updatedAt(), entry.inputTokens(), entry.outputTokens(),
                entry.feeKind(), entry.currency(), entry.amount(), entry.priceSnapshot(), state.lastEventHash);

        byte[] payload = json.writeValueAsBytes(event);
        Path base = usageV2Dir(state.bookId);
        Path activePath = activeSegmentPath(base, state.activeSegmentId);

        if (!Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)) {
            journal.initSegment(activePath, state.bookId, state.activeSegmentId, seq, state.lastEventHash);
        }

        long segBytes = journal.append(activePath, seq, payload);
        state.activeSegmentBytes = segBytes;
        state.activeSegmentCount++;
        state.lastEventHash = studio.bookhtml.decision.DecisionHash.sha256Hex(
                state.lastEventHash + ":" + seq + ":" + eventId);

        // Update in-memory state
        state.entries.put(entry.id(), entry);
        int existingIdx = -1;
        for (int i = 0; i < state.ordered.size(); i++) {
            if (state.ordered.get(i).id().equals(entry.id())) {
                existingIdx = i;
                break;
            }
        }
        if (existingIdx >= 0) {
            state.ordered.set(existingIdx, entry);
        } else {
            state.ordered.add(entry);
        }

        saveLegacy(state.bookId, entry);

        // Recompute totals for this transition
        recomputeTotals(state);

        // Check if segment needs to be sealed
        if (state.activeSegmentCount >= DurableEventJournal.MAX_SEGMENT_RECORDS
                || state.activeSegmentBytes >= DurableEventJournal.MAX_SEGMENT_BYTES) {
            sealCurrentSegmentAndCheckpoint(state);
        }
    }

    private void recomputeTotals(BookLedgerState state) {
        state.totals.clear();
        state.byProvider.clear();
        for (Entry e : state.ordered) {
            state.totals.add(e);
            state.byProvider.computeIfAbsent(e.provider() + "\u0000" + e.model(), ignored -> new Totals()).add(e);
        }
    }

    private void sealCurrentSegmentAndCheckpoint(BookLedgerState state) throws IOException {
        Path base = usageV2Dir(state.bookId);
        Path activePath = activeSegmentPath(base, state.activeSegmentId);
        String sha = DurableEventJournal.sha256Hex(activePath);
        String filename = "sealed/" + state.activeSegmentId + ".wal";
        Path sealedPath = base.resolve(filename);

        journal.sealSegment(activePath, sealedPath);

        long startSeq = state.appliedThroughSeq - state.activeSegmentCount + 1;
        state.sealedSegments.add(new AtomicManifestStore.SegmentRef(
                state.activeSegmentId.toString(), filename, startSeq, state.appliedThroughSeq, state.activeSegmentCount, sha));

        // Open new active segment
        state.activeSegmentId = UUID.randomUUID();
        state.activeSegmentCount = 0;
        state.activeSegmentBytes = 0;
        state.generation++;

        publishManifestAndCheckpoint(state);
    }

    private void publishManifestAndCheckpoint(BookLedgerState state) throws IOException {
        Path base = usageV2Dir(state.bookId);
        Path cpDir = base.resolve("checkpoints").resolve(String.valueOf(state.generation));
        ensureDirectory(cpDir);
        Path cpPath = cpDir.resolve("checkpoint.json");

        Map<String, Object> byProv = new LinkedHashMap<>();
        state.byProvider.forEach((k, v) -> byProv.put(k, v.view()));

        Checkpoint cp = new Checkpoint(1, state.bookId, state.generation, state.appliedThroughSeq,
                state.lastEventHash, List.copyOf(state.ordered), state.totals.view(),
                byProv, Instant.now());

        byte[] cpBytes = json.writeValueAsBytes(cp);
        Files.write(cpPath, cpBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String cpSha = studio.bookhtml.decision.DecisionHash.sha256Hex(new String(cpBytes, StandardCharsets.UTF_8));

        AtomicManifestStore.SegmentRef activeRef = new AtomicManifestStore.SegmentRef(
                state.activeSegmentId.toString(), "active/" + state.activeSegmentId + ".wal",
                state.appliedThroughSeq + 1, state.appliedThroughSeq + 1, 0, "");

        AtomicManifestStore.CheckpointRef cpRef = new AtomicManifestStore.CheckpointRef(
                state.generation, state.appliedThroughSeq, "checkpoints/" + state.generation + "/checkpoint.json", cpSha);

        AtomicManifestStore.Manifest manifest = new AtomicManifestStore.Manifest(
                1, state.bookId, state.generation, state.appliedThroughSeq, activeRef,
                List.copyOf(state.sealedSegments), cpRef, Instant.now(), "HEALTHY");

        manifestStore.write(base, manifest);
    }

    private BookLedgerState ensureLoaded(String bookId) throws IOException {
        BookLedgerState existing = states.get(bookId);
        checkLegacyDirForCorruption(bookId, existing);
        if (existing != null) {
            if (existing.recoveryRequired) {
                throw new IOException("usage ledger damaged / recovery required: " + existing.recoveryError);
            }
            return existing;
        }

        BookLedgerState state = new BookLedgerState(bookId);
        Path base = usageV2Dir(bookId);

        if (manifestStore.exists(base)) {
            // Load from v2 manifest and checkpoint
            AtomicManifestStore.Manifest manifest = manifestStore.read(base, bookId);
            if ("RECOVERY_REQUIRED".equals(manifest.state())) {
                state.recoveryRequired = true;
                state.recoveryError = "Manifest marked RECOVERY_REQUIRED";
                states.put(bookId, state);
                throw new IOException("usage ledger damaged: manifest state is RECOVERY_REQUIRED");
            }
            state.generation = manifest.generation();
            state.appliedThroughSeq = manifest.appliedThroughSeq();
            state.sealedSegments.addAll(manifest.sealedSegments());

            // Read checkpoint if available
            if (manifest.checkpoint() != null) {
                Path cpPath = base.resolve(manifest.checkpoint().filename());
                if (Files.exists(cpPath, LinkOption.NOFOLLOW_LINKS)) {
                    Checkpoint cp = json.readValue(Files.readAllBytes(cpPath), Checkpoint.class);
                    state.lastEventHash = cp.lastEventHash();
                    for (Entry e : cp.entries()) {
                        state.entries.put(e.id(), e);
                        state.ordered.add(e);
                    }
                    recomputeTotals(state);
                }
            }

            // Replay active segment frames beyond checkpoint
            if (manifest.activeSegment() != null) {
                state.activeSegmentId = UUID.fromString(manifest.activeSegment().segmentId());
                Path activePath = base.resolve(manifest.activeSegment().filename());
                if (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)) {
                    List<String> warnings = new ArrayList<>();
                    DurableEventJournal.SegmentScanResult scan = journal.readSegment(activePath, bookId, true, warnings);
                    state.activeSegmentCount = scan.frames().size();
                    state.activeSegmentBytes = scan.validLength();
                    for (DurableEventJournal.JournalFrame frame : scan.frames()) {
                        if (frame.seq() > state.appliedThroughSeq) {
                            LedgerEvent ev = json.readValue(frame.payload(), LedgerEvent.class);
                            applyEventToState(state, ev);
                        }
                    }
                }
            }
        } else if (Files.exists(usageDir(bookId), LinkOption.NOFOLLOW_LINKS)) {
            // Migrate legacy usage directory
            migrateLegacyUsage(state);
        } else {
            // Empty new book
            ensureDirectory(usageDir(bookId));
            ensureDirectory(base);
            ensureDirectory(base.resolve("active"));
            ensureDirectory(base.resolve("sealed"));
            ensureDirectory(base.resolve("checkpoints"));
            ensureDirectory(base.resolve("staging"));
            publishManifestAndCheckpoint(state);
        }

        states.put(bookId, state);
        return state;
    }

    private void applyEventToState(BookLedgerState state, LedgerEvent ev) {
        state.appliedThroughSeq = ev.ledgerSeq();
        state.lastEventHash = studio.bookhtml.decision.DecisionHash.sha256Hex(
                state.lastEventHash + ":" + ev.ledgerSeq() + ":" + ev.eventId());
        Entry existing = state.entries.get(ev.physicalCallId());
        Instant createdAt = existing != null ? existing.createdAt() : ev.createdAt();
        Entry entry = new Entry(ev.physicalCallId(), ev.bookId(), createdAt, ev.createdAt(),
                ev.pageNumber(), "OCR_PAGE", ev.provider(), ev.model(), ev.transition(),
                ev.inputTokens(), ev.outputTokens(), ev.feeKind(), ev.currency(), ev.amount(),
                ev.priceSnapshot(), ev.attemptId(), ev.requestFingerprint(), ev.attemptSeq());
        state.entries.put(entry.id(), entry);
        int idx = -1;
        for (int i = 0; i < state.ordered.size(); i++) {
            if (state.ordered.get(i).id().equals(entry.id())) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) state.ordered.set(idx, entry);
        else state.ordered.add(entry);
        recomputeTotals(state);
    }

    private void migrateLegacyUsage(BookLedgerState state) throws IOException {
        Path legacyDir = usageDir(state.bookId);
        List<Entry> legacyEntries = new ArrayList<>();
        try (var stream = Files.list(legacyDir)) {
            for (Iterator<Path> it = stream.iterator(); it.hasNext();) {
                Path p = it.next();
                if (recoverLegacyTemporary(state.bookId, p)) continue;
                legacyEntries.add(readPath(state.bookId, p));
            }
        }
        legacyEntries.sort(Comparator.comparing(Entry::createdAt).thenComparing(Entry::id));

        Path base = usageV2Dir(state.bookId);
        ensureDirectory(base);
        ensureDirectory(base.resolve("active"));
        ensureDirectory(base.resolve("sealed"));
        ensureDirectory(base.resolve("checkpoints"));
        ensureDirectory(base.resolve("staging"));

        for (Entry e : legacyEntries) {
            state.appliedThroughSeq++;
            long seq = state.appliedThroughSeq;
            String eventId = UUID.randomUUID().toString();
            LedgerEvent ev = new LedgerEvent(
                    1, eventId, seq, e.id(), e.id(), "PAGE",
                    e.executionId(), e.attemptSeq(), null, e.bookId(), e.pageNumber(),
                    e.provider(), e.model(), null, 0, e.status(),
                    e.taskHash(), e.updatedAt(), e.inputTokens(), e.outputTokens(),
                    e.feeKind(), e.currency(), e.amount(), e.priceSnapshot(), state.lastEventHash);

            byte[] payload = json.writeValueAsBytes(ev);
            Path activePath = activeSegmentPath(base, state.activeSegmentId);
            if (!Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)) {
                journal.initSegment(activePath, state.bookId, state.activeSegmentId, seq, state.lastEventHash);
            }
            long segBytes = journal.append(activePath, seq, payload);
            state.activeSegmentBytes = segBytes;
            state.activeSegmentCount++;
            state.lastEventHash = studio.bookhtml.decision.DecisionHash.sha256Hex(
                    state.lastEventHash + ":" + seq + ":" + eventId);

            state.entries.put(e.id(), e);
            state.ordered.add(e);
        }

        recomputeTotals(state);
        publishManifestAndCheckpoint(state);
    }

    private void checkLegacyDirForCorruption(String bookId, BookLedgerState state) throws IOException {
        Path dir = usageDir(bookId);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("usage ledger unsafe");
        }
        try (var paths = Files.list(dir)) {
            for (Iterator<Path> it = paths.iterator(); it.hasNext();) {
                Path path = it.next();
                if (recoverLegacyTemporary(bookId, path)) continue;
                Entry e = readPath(bookId, path);
                if (state != null) {
                    Entry cur = state.entries.get(e.id());
                    if (cur == null || !cur.equals(e)) {
                        state.entries.put(e.id(), e);
                        int idx = -1;
                        for (int i = 0; i < state.ordered.size(); i++) {
                            if (state.ordered.get(i).id().equals(e.id())) {
                                idx = i;
                                break;
                            }
                        }
                        if (idx >= 0) state.ordered.set(idx, e);
                        else state.ordered.add(e);
                        recomputeTotals(state);
                    }
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("usage ledger damaged", e);
        }
    }

    private void saveLegacy(String bookId, Entry entry) throws IOException {
        validateEntry(bookId, entry.id() + ".json", entry);
        Path dir = usageDir(bookId), temp = null;
        try {
            ensureDirectory(dir);
            Path target = dir.resolve(entry.id() + ".json");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target))
                throw new IOException("usage ledger unsafe");
            Path staging = privateDirectory(books.bookDir(bookId).resolve("usage-staging"));
            temp = Files.createTempFile(staging, "entry-", ".tmp");
            setPermissions(temp, FILE_PERMS);
            byte[] bytes = json.writeValueAsBytes(entry);
            if (bytes.length > MAX_ENTRY_BYTES) throw new IOException("usage entry too large");
            boolean interrupted = Thread.interrupted();
            try (var file = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(dir);
        } finally { if (temp != null) Files.deleteIfExists(temp); }
    }

    private Path usageDir(String bookId) {
        return books.bookDir(bookId).resolve("usage");
    }

    private Path usageV2Dir(String bookId) {
        return books.bookDir(bookId).resolve("usage-v2");
    }

    private Path activeSegmentPath(Path base, UUID segmentId) {
        return base.resolve("active").resolve(segmentId + ".wal");
    }

    private static EntryPosition parseCursor(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 160 || !value.matches("[A-Za-z0-9_-]+"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "用量游标无效");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            int split = decoded.indexOf('|');
            if (split < 1 || split != decoded.lastIndexOf('|')) throw new IllegalArgumentException();
            String id = decoded.substring(split + 1);
            if (!id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException();
            return new EntryPosition(Instant.parse(decoded.substring(0, split)), id);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "用量游标无效");
        }
    }

    private static String cursor(Entry e) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (e.createdAt().toString() + "|" + e.id()).getBytes(StandardCharsets.US_ASCII));
    }

    private record EntryPosition(Instant createdAt, String id) {}

    private static Map<String, Object> entryView(Entry e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id());
        out.put("createdAt", e.createdAt());
        out.put("updatedAt", e.updatedAt());
        out.put("pageNumber", e.pageNumber());
        out.put("operation", e.operation());
        out.put("executionId", e.executionId());
        out.put("taskHash", e.taskHash());
        out.put("attemptSeq", e.attemptSeq());
        out.put("provider", e.provider());
        out.put("model", e.model());
        out.put("status", e.status());
        out.put("inputTokens", e.inputTokens());
        out.put("outputTokens", e.outputTokens());
        out.put("feeKind", e.feeKind());
        out.put("currency", e.currency());
        out.put("amount", e.amount());
        return out;
    }

    public static final class Totals {
        long requests, success, failed, pending, prepared, cacheHits, notSent, unpriced, unknownInput, unknownOutput;
        BigInteger input = BigInteger.ZERO, output = BigInteger.ZERO;
        final Map<String, BigDecimal> estimates = new LinkedHashMap<>();

        public Totals() {}

        public void clear() {
            requests = success = failed = pending = prepared = cacheHits = notSent = unpriced = unknownInput = unknownOutput = 0;
            input = BigInteger.ZERO;
            output = BigInteger.ZERO;
            estimates.clear();
        }

        public Totals copy() {
            Totals t = new Totals();
            t.requests = this.requests;
            t.success = this.success;
            t.failed = this.failed;
            t.pending = this.pending;
            t.prepared = this.prepared;
            t.cacheHits = this.cacheHits;
            t.notSent = this.notSent;
            t.unpriced = this.unpriced;
            t.unknownInput = this.unknownInput;
            t.unknownOutput = this.unknownOutput;
            t.input = this.input;
            t.output = this.output;
            t.estimates.putAll(this.estimates);
            return t;
        }

        public void add(Entry e) {
            if ("CACHE_REUSED".equals(e.status())) {
                cacheHits++;
                return;
            }
            if ("NOT_SENT".equals(e.status())) {
                notSent++;
                return;
            }
            if ("PREPARED".equals(e.status())) {
                prepared++;
                return;
            }
            requests++;
            switch (e.status()) {
                case "SUCCEEDED" -> success++;
                case "FAILED" -> failed++;
                default -> pending++;
            }
            if (e.inputTokens() == null) unknownInput++;
            else input = input.add(BigInteger.valueOf(e.inputTokens()));
            if (e.outputTokens() == null) unknownOutput++;
            else output = output.add(BigInteger.valueOf(e.outputTokens()));
            if ("ESTIMATED".equals(e.feeKind())) {
                estimates.merge(e.currency(), new BigDecimal(e.amount()), BigDecimal::add);
            } else {
                unpriced++;
            }
        }

        public Map<String, Object> view() {
            List<Map<String, String>> amounts = new ArrayList<>();
            estimates.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> amounts.add(Map.of("currency", e.getKey(), "amount", amount(e.getValue()))));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requests", requests);
            result.put("success", success);
            result.put("failed", failed);
            result.put("pending", pending);
            result.put("cacheHits", cacheHits);
            result.put("notSent", notSent);
            result.put("prepared", prepared);
            result.put("inputTokens", input);
            result.put("outputTokens", output);
            result.put("unknownInputTokenRequests", unknownInput);
            result.put("unknownOutputTokenRequests", unknownOutput);
            result.put("unpricedRequests", unpriced);
            result.put("reportedAmounts", List.of());
            result.put("estimatedAmounts", amounts);
            return result;
        }
    }

    private boolean recoverLegacyTemporary(String bookId, Path path) throws IOException {
        if (!path.getFileName().toString().matches("entry-[0-9]{1,20}\\.tmp")) return false;
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_ENTRY_BYTES) throw new IOException("unsafe legacy usage temporary");
        Path quarantine = privateDirectory(books.bookDir(bookId).resolve("usage-recovery"));
        Files.move(path, quarantine.resolve(path.getFileName() + "-" + UUID.randomUUID()), StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(quarantine);
        forceDirectory(usageDir(bookId));
        System.getLogger(UsageLedger.class.getName()).log(System.Logger.Level.WARNING,
                "USAGE_ORPHAN_PRESERVED: committed records remain authoritative; no automatic resend");
        return true;
    }

    private static Path privateDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("usage directory unsafe");
        setPermissions(directory, DIR_PERMS);
        return directory;
    }

    private static void ensureDirectory(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir);
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("directory unsafe: " + dir);
        }
        setPermissions(dir, DIR_PERMS);
    }

    private Entry readPath(String bookId, Path path) throws IOException {
        if (!path.getFileName().toString().matches("[0-9a-f-]{36}\\.json")
                || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_ENTRY_BYTES) throw new IOException("usage ledger damaged");
        Entry e;
        try (var parser = json.getFactory().createParser(Files.readAllBytes(path))) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree = json.readTree(parser);
            if (tree == null || !tree.isObject() || parser.nextToken() != null)
                throw new IOException("trailing usage record");
            for (String field : List.of("pageNumber", "inputTokens", "outputTokens", "attemptSeq")) {
                JsonNode value = tree.get(field);
                if (value != null && !value.isNull() && (!value.isIntegralNumber() || !value.canConvertToLong()
                        || field.equals("pageNumber") && !value.canConvertToInt()))
                    throw new IOException("invalid usage integer");
            }
            e = json.treeToValue(tree, Entry.class);
        } catch (Exception ex) {
            throw new IOException("usage ledger damaged", ex);
        }
        e = normalizeLegacyOperation(e);
        validateEntry(bookId, path.getFileName().toString(), e);
        return e;
    }

    private static Entry normalizeLegacyOperation(Entry e) {
        if (e == null || e.operation() == null || e.executionId() != null || e.taskHash() != null || e.attemptSeq() != null)
            return e;
        String operation = e.operation(), task;
        if (operation.startsWith("QWEN_STRUCTURE:")) {
            task = operation.substring("QWEN_STRUCTURE:".length());
            if (e.pageNumber() == null || !task.equals(String.valueOf(e.pageNumber()))) return e;
            operation = "QWEN_STRUCTURE";
        } else if (operation.startsWith("QWEN_TEXT_REVIEW:")) {
            task = operation.substring("QWEN_TEXT_REVIEW:".length());
            if (!task.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}")) return e;
            operation = "QWEN_TEXT_REVIEW";
        } else return e;
        if (!"qwen".equals(e.provider())) return e;
        return new Entry(e.id(), e.bookId(), e.createdAt(), e.updatedAt(), e.pageNumber(), operation,
                e.provider(), e.model(), e.status(), e.inputTokens(), e.outputTokens(), e.feeKind(),
                e.currency(), e.amount(), e.priceSnapshot(), null, studio.bookhtml.decision.DecisionHash.sha256Hex(task));
    }

    private static void validateEntry(String bookId, String filename, Entry e) throws IOException {
        if (e == null || !bookId.equals(e.bookId()) || !filename.equals(e.id() + ".json")
                || e.createdAt() == null || e.updatedAt() == null || e.createdAt().isAfter(e.updatedAt())
                || e.provider() == null || e.model() == null || e.operation() == null
                || !e.model().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
                || !e.operation().matches("[A-Z][A-Z0-9_]{0,39}")
                || e.pageNumber() != null && e.pageNumber() < 1
                || !Set.of("paddle-aistudio", "ppocr", "qwen", "jev", "minimax").contains(e.provider())
                || !Set.of("PREPARED", "SENT_UNKNOWN", "PENDING", "OUTCOME_UNKNOWN", "NOT_SENT", "SUCCEEDED", "FAILED", "CACHE_REUSED").contains(e.status())
                || !Set.of("UNKNOWN", "ESTIMATED", "CACHE_REUSE").contains(e.feeKind())
                || e.inputTokens() != null && e.inputTokens() < 0
                || e.outputTokens() != null && e.outputTokens() < 0
                || ("ESTIMATED".equals(e.feeKind()) != (e.currency() != null && e.amount() != null))
                || e.currency() != null && !Set.of("CNY", "USD").contains(e.currency())
                || e.amount() != null && !validAmount(e.amount())
                || e.executionId() != null && !canonicalUuid(e.executionId())
                || e.attemptSeq() != null && (e.attemptSeq() < 1 || e.executionId() == null)
                || e.taskHash() != null && !e.taskHash().matches("[0-9a-f]{64}")
                || e.priceSnapshot() != null && !validPriceSnapshot(e)
                || ("CACHE_REUSED".equals(e.status()) != "CACHE_REUSE".equals(e.feeKind())))
            throw new IOException("usage ledger damaged");
    }

    private static boolean validAmount(String value) {
        try {
            return value.length() <= 48 && value.matches("[0-9]+(?:\\.[0-9]{1,12})?")
                    && new BigDecimal(value).signum() >= 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean validPriceSnapshot(Entry e) {
        SettingsService.Rate r = e.priceSnapshot();
        return e.provider().equals(r.provider()) && e.model().equals(r.model())
                && r.currency() != null && Set.of("CNY", "USD").contains(r.currency())
                && r.perRequest() != null && r.inputPerMillion() != null && r.outputPerMillion() != null
                && (r.perRequest().isEmpty() || validAmount(r.perRequest()))
                && (r.inputPerMillion().isEmpty() || validAmount(r.inputPerMillion()))
                && (r.outputPerMillion().isEmpty() || validAmount(r.outputPerMillion()))
                && (Set.of("paddle-aistudio", "ppocr").contains(e.provider())
                ? r.inputPerMillion().isEmpty() && r.outputPerMillion().isEmpty()
                : r.perRequest().isEmpty());
    }

    private static final AtomicBoolean FORCE_WARNING = new AtomicBoolean();

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null)
            Files.setPosixFilePermissions(path, permissions);
    }

    private static void forceDirectory(Path directory) {
        boolean interrupted = Thread.interrupted();
        try (var channel = java.nio.channels.FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException unavailable) {
            if (FORCE_WARNING.compareAndSet(false, true))
                System.getLogger(UsageLedger.class.getName()).log(
                        System.Logger.Level.WARNING, "Usage ledger directory fsync unavailable; power-loss durability is filesystem dependent");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static String executionId(UsageContext.Value context) throws IOException {
        var scope = QwenExecutionScope.current();
        if (scope == null) return null;
        if (!scope.bookId().equals(context.bookId()) || !java.util.Objects.equals(scope.pageNumber(), context.pageNumber()))
            throw new IOException("mismatched page execution scope");
        return scope.executionId().toString();
    }

    private static boolean canonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Long attemptSeq() {
        var scope = QwenExecutionScope.current();
        return scope == null || scope.attemptSeq() < 1 ? null : scope.attemptSeq();
    }

    private static String taskHash(UsageContext.Value context) {
        if (context.taskId() == null) return null;
        return studio.bookhtml.decision.DecisionHash.sha256Hex(context.taskId());
    }

    private static UsageContext.Value requireContext() throws IOException {
        UsageContext.Value context = UsageContext.current();
        if (context == null || context.bookId() == null || context.operation() == null
                || !context.operation().matches("[A-Z][A-Z0-9_]{0,39}")
                || context.pageNumber() != null && context.pageNumber() < 1
                || context.taskId() != null && context.taskId().length() > 256)
            throw new IOException("usage scope missing");
        return context;
    }
}
