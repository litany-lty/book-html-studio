package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.ProcessingSnapshot;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Real stage/unit events; attempt-local snapshots are updated atomically, never by a timer. */
@Service
public class ProcessingProgressService {
    private static final int MAX_TRACKED = 512;
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "INTERRUPTED");
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<PageKey, UUID> latest = new ConcurrentHashMap<>();
    private final AtomicLong order = new AtomicLong();
    private record PageKey(String bookId, int pageNumber) {}

    private static final class Entry {
        final PageKey page;
        final UUID attemptId;
        final long order;
        long version;
        String lifecycle = "RUNNING", stage = "PREPARING", availability = "ORIGINAL_ONLY";
        int publishedRevision;
        final Instant startedAt = Instant.now();
        Instant stageStartedAt = startedAt, lastProgressAt = startedAt;
        String unitKind = "PAGE", messageCode = "PROCESSING_STARTED";
        int total, succeeded, failed, skipped, cancelled, inFlight, percent;
        boolean canRead, canStop = true, canRetry;
        Entry(PageKey page, UUID attemptId, long order, int revision) {
            this.page = page; this.attemptId = attemptId; this.order = order;
            this.publishedRevision = Math.max(0, revision);
        }
        boolean terminal() { return TERMINAL.contains(lifecycle); }
        void progressed() { lastProgressAt = Instant.now(); version++; }
    }

    /** New attempts are ordered by creation, not by incomparable per-attempt event counters. */
    public synchronized UUID begin(String bookId, int pageNumber, int publishedRevision) {
        Objects.requireNonNull(bookId, "bookId");
        if (pageNumber < 1) throw new IllegalArgumentException("pageNumber must be positive");
        prune();
        UUID id = UUID.randomUUID();
        PageKey key = new PageKey(bookId, pageNumber);
        entries.put(id, new Entry(key, id, order.incrementAndGet(), publishedRevision));
        latest.put(key, id);
        return id;
    }

    private void prune() {
        if (entries.size() < MAX_TRACKED) return;
        List<Entry> completed = entries.values().stream().filter(e -> {
            synchronized (e) { return e.terminal(); }
        }).sorted(Comparator.comparingLong(e -> e.order)).toList();
        for (Entry e : completed) {
            if (entries.size() < MAX_TRACKED) break;
            entries.remove(e.attemptId, e);
            latest.remove(e.page, e.attemptId);
        }
        // Running attempts are never evicted. Their upper bound is the job admission limit.
    }

    private Entry entry(String bookId, int pageNumber, UUID attemptId) {
        if (attemptId == null) return null;
        Entry e = entries.get(attemptId);
        return e != null && e.page.equals(new PageKey(bookId, pageNumber)) ? e : null;
    }

    public void stage(String bookId, int pageNumber, UUID attemptId, String stage) {
        Entry e = entry(bookId, pageNumber, attemptId);
        if (e == null) return;
        synchronized (e) {
            if (e.terminal() || Objects.equals(e.stage, stage)) return;
            e.stage = Objects.requireNonNull(stage);
            e.stageStartedAt = Instant.now();
            // Four milestones: extract, baseline publish, enhancement, final publish.
            if ("VALIDATING".equals(stage)) e.percent = Math.max(e.percent, 75);
            if ("PUBLISHING".equals(stage)) e.percent = Math.max(e.percent, e.canRead ? 75 : 25);
            e.progressed();
        }
    }

    public void plan(String bookId, int pageNumber, UUID attemptId, String unitKind, int total) {
        if (total < 0) throw new IllegalArgumentException("total must be nonnegative");
        Entry e = entry(bookId, pageNumber, attemptId);
        if (e == null) return;
        synchronized (e) {
            if (e.terminal()) return;
            e.unitKind = Objects.requireNonNull(unitKind); e.total = total;
            e.succeeded = e.failed = e.skipped = e.cancelled = e.inFlight = 0;
            e.progressed();
        }
    }

    public void unitDone(String bookId, int pageNumber, UUID attemptId, boolean ok) {
        Entry e = entry(bookId, pageNumber, attemptId);
        if (e == null) return;
        synchronized (e) {
            if (e.terminal() || e.succeeded + e.failed + e.skipped + e.cancelled >= e.total) return;
            if (ok) e.succeeded++; else e.failed++;
            if (e.canRead && !"PAGE".equals(e.unitKind) && e.total > 0) {
                long done = (long) e.succeeded + e.failed + e.skipped + e.cancelled;
                e.percent = Math.max(e.percent, 50 + (int) (25L * done / e.total));
            }
            e.inFlight = Math.max(0, e.inFlight - 1);
            e.progressed();
        }
    }

    public void inFlight(String bookId, int pageNumber, UUID attemptId, int delta) {
        Entry e = entry(bookId, pageNumber, attemptId);
        if (e == null) return;
        synchronized (e) {
            if (e.terminal()) return;
            int remaining = Math.max(0, e.total - e.succeeded - e.failed - e.skipped - e.cancelled);
            int next = (int) Math.min(remaining, Math.max(0L, (long) e.inFlight + delta));
            if (next != e.inFlight) { e.inFlight = next; e.version++; }
            // Queue movement and heartbeats are not completed work.
        }
    }

    /** Only called after a readable revision was committed successfully. */
    public void baselinePublished(String bookId, int pageNumber, UUID attemptId, int revision, boolean enhanced) {
        Entry e = entry(bookId, pageNumber, attemptId);
        if (e == null) return;
        synchronized (e) {
            if (e.terminal()) return;
            e.publishedRevision = revision;
            e.availability = enhanced ? "ENHANCED" : "OCR_READABLE";
            e.canRead = true;
            e.percent = Math.max(e.percent, enhanced ? 90 : 50);
            e.progressed();
        }
    }

    public void finish(String bookId, int pageNumber, UUID attemptId, String lifecycle,
                       String messageCode, boolean canRetry) {
        if (!TERMINAL.contains(lifecycle)) throw new IllegalArgumentException("terminal lifecycle required");
        Entry e = entry(bookId, pageNumber, attemptId);
        if (e == null) return;
        synchronized (e) {
            if (e.terminal()) return; // Late child completion cannot resurrect or replace a terminal attempt.
            e.lifecycle = lifecycle; e.messageCode = messageCode; e.canRetry = canRetry;
            e.canStop = false; e.inFlight = 0;
            if ("SUCCEEDED".equals(lifecycle) && e.canRead) e.percent = 100;
            e.progressed();
        }
    }

    public ProcessingSnapshot snapshot(String bookId, int pageNumber, UUID attemptId) {
        Entry e = entry(bookId, pageNumber, attemptId);
        if (e == null) return null;
        synchronized (e) {
            return new ProcessingSnapshot(2, e.page.bookId(), e.page.pageNumber(), e.attemptId, e.version,
                    e.lifecycle, e.stage, e.availability, e.publishedRevision, e.startedAt,
                    e.stageStartedAt, e.lastProgressAt,
                    new ProcessingSnapshot.UnitCounts(e.unitKind, e.total, e.succeeded, e.failed,
                            e.skipped, e.cancelled, e.inFlight),
                    e.canRead, e.canStop, e.canRetry, e.messageCode, e.percent);
        }
    }

    public ProcessingSnapshot latest(String bookId, int pageNumber) {
        return snapshot(bookId, pageNumber, latest.get(new PageKey(bookId, pageNumber)));
    }
}
