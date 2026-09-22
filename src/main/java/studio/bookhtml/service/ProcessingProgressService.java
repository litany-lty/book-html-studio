package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.ProcessingSnapshot;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Real, process-local events only. Telemetry must never authorize or block a page commit. */
@Service
public class ProcessingProgressService {
    private static final int MAX_TRACKED = 512;
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "INTERRUPTED");
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<PageKey, UUID> latestAttempts = new LinkedHashMap<>();

    private record PageKey(String bookId, int pageNumber) {}

    private static final class Entry {
        final PageKey page;
        final UUID attemptId;
        final Instant startedAt = Instant.now();
        Instant stageStartedAt = startedAt, lastProgressAt = startedAt;
        long version;
        String lifecycle = "RUNNING", stage = "PREPARING", availability = "ORIGINAL_ONLY";
        String unitKind = "STAGE", messageCode = "PROCESSING_STARTED";
        int publishedRevision, total = 1, succeeded, failed, skipped, cancelled, inFlight;
        boolean canRead, canStop = true, canRetry;

        Entry(PageKey page, UUID attemptId, int revision) {
            this.page = page;
            this.attemptId = attemptId;
            this.publishedRevision = revision;
        }
        int done() { return succeeded + failed + skipped + cancelled; }
        void advanced() { lastProgressAt = Instant.now(); version++; }
    }

    /** Creation order, not event count, determines the latest attempt. */
    public synchronized UUID begin(String bookId, int pageNumber, int publishedRevision) {
        if (entries.size() >= MAX_TRACKED) {
            Iterator<Map.Entry<UUID, Entry>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext() && entries.size() >= MAX_TRACKED) {
                Map.Entry<UUID, Entry> item = iterator.next();
                if (!TERMINAL.contains(item.getValue().lifecycle)) continue;
                latestAttempts.remove(item.getValue().page, item.getKey());
                iterator.remove();
            }
        }
        // Losing optional telemetry is safer than growing without bound or rejecting OCR.
        if (entries.size() >= MAX_TRACKED) return null;
        UUID attemptId = UUID.randomUUID();
        PageKey page = new PageKey(bookId, pageNumber);
        entries.put(attemptId, new Entry(page, attemptId, Math.max(0, publishedRevision)));
        latestAttempts.put(page, attemptId);
        return attemptId;
    }

    private Entry active(String bookId, int pageNumber, UUID attemptId) {
        Entry entry = entries.get(attemptId);
        return entry != null && entry.page.equals(new PageKey(bookId, pageNumber))
                && !TERMINAL.contains(entry.lifecycle) ? entry : null;
    }

    public synchronized void stage(String bookId, int pageNumber, UUID attemptId, String stage) {
        Entry entry = active(bookId, pageNumber, attemptId);
        if (entry == null || stage == null || stage.equals(entry.stage)) return;
        entry.stage = stage;
        entry.stageStartedAt = Instant.now();
        // Each stage starts with one completion checkpoint. A fixed chunk plan can replace it.
        entry.unitKind = "STAGE";
        entry.total = 1;
        entry.succeeded = entry.failed = entry.skipped = entry.cancelled = entry.inFlight = 0;
        entry.advanced();
    }

    public synchronized void plan(String bookId, int pageNumber, UUID attemptId, String kind, int total) {
        if (total < 0) throw new IllegalArgumentException("negative work total");
        Entry entry = active(bookId, pageNumber, attemptId);
        if (entry == null) return;
        if (kind != null && kind.equals(entry.unitKind) && total == entry.total) return;
        entry.unitKind = kind == null ? "STAGE" : kind;
        entry.total = total;
        entry.succeeded = entry.failed = entry.skipped = entry.cancelled = entry.inFlight = 0;
        entry.advanced();
    }

    public synchronized void unitDone(String bookId, int pageNumber, UUID attemptId, boolean ok) {
        Entry entry = active(bookId, pageNumber, attemptId);
        if (entry == null || entry.done() >= entry.total) return;
        if (ok) entry.succeeded++; else entry.failed++;
        if (entry.inFlight > 0) entry.inFlight--;
        entry.advanced();
    }

    public synchronized void inFlight(String bookId, int pageNumber, UUID attemptId, int delta) {
        Entry entry = active(bookId, pageNumber, attemptId);
        if (entry == null) return;
        int next = (int) Math.max(0L, Math.min((long) entry.total - entry.done(), (long) entry.inFlight + delta));
        if (next == entry.inFlight) return;
        entry.inFlight = next;
        entry.version++; // A queue/heartbeat change is not completed work.
    }

    /** Called only AFTER a durable commit. Never equate a received model response with publication. */
    public synchronized void baselinePublished(String bookId, int pageNumber, UUID attemptId,
                                                int revision, boolean enhanced) {
        Entry entry = active(bookId, pageNumber, attemptId);
        if (entry == null) return;
        entry.publishedRevision = revision;
        entry.availability = enhanced ? "ENHANCED" : "OCR_READABLE";
        entry.canRead = true;
        entry.advanced();
    }

    public synchronized void finish(String bookId, int pageNumber, UUID attemptId, String lifecycle,
                                    String messageCode, boolean canRetry) {
        if (!TERMINAL.contains(lifecycle)) throw new IllegalArgumentException("not a terminal lifecycle");
        Entry entry = active(bookId, pageNumber, attemptId);
        if (entry == null) return;
        entry.lifecycle = lifecycle;
        entry.messageCode = messageCode;
        entry.canRetry = canRetry;
        entry.canStop = false;
        entry.inFlight = 0;
        if ("SUCCEEDED".equals(lifecycle)) entry.succeeded += entry.total - entry.done();
        entry.advanced();
    }

    public synchronized ProcessingSnapshot snapshot(String bookId, int pageNumber, UUID attemptId) {
        Entry entry = entries.get(attemptId);
        if (entry == null || !entry.page.equals(new PageKey(bookId, pageNumber))) return null;
        return snapshot(entry);
    }

    /** O(1), coherent snapshot: concurrent chunks cannot lose increments or tear counters. */
    public synchronized ProcessingSnapshot latest(String bookId, int pageNumber) {
        Entry entry = entries.get(latestAttempts.get(new PageKey(bookId, pageNumber)));
        return entry == null ? null : snapshot(entry);
    }

    private static ProcessingSnapshot snapshot(Entry entry) {
        return new ProcessingSnapshot(2, entry.page.bookId(), entry.page.pageNumber(), entry.attemptId,
                entry.version, entry.lifecycle, entry.stage, entry.availability, entry.publishedRevision,
                entry.startedAt, entry.stageStartedAt, entry.lastProgressAt,
                new ProcessingSnapshot.UnitCounts(entry.unitKind, entry.total, entry.succeeded,
                        entry.failed, entry.skipped, entry.cancelled, entry.inFlight),
                entry.canRead, entry.canStop, entry.canRetry, entry.messageCode);
    }
}
