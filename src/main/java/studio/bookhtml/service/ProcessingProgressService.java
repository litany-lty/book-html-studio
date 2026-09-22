package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.ProcessingSnapshot;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Event-driven progress. No I/O or model calls while holding this short-lived lock. */
@Service
public class ProcessingProgressService {
    private static final int MAX_TRACKED = 512;
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "INTERRUPTED");
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<String, UUID> latestAttempts = new LinkedHashMap<>();

    private static final class Entry {
        final String bookId;
        final int pageNumber;
        final UUID attemptId;
        final Instant startedAt = Instant.now();
        long version;
        String lifecycle = "RUNNING", stage = "PREPARING", availability = "ORIGINAL_ONLY";
        String kind = "PAGE", message = "PROCESSING_STARTED";
        int revision, total, succeeded, failed, skipped, cancelled, inFlight;
        Instant stageStartedAt = startedAt, lastProgressAt = startedAt;
        boolean canRead, canRetry;
        Entry(String bookId, int pageNumber, UUID attemptId, int revision) {
            this.bookId = bookId; this.pageNumber = pageNumber; this.attemptId = attemptId; this.revision = revision;
        }
        void changed() { version++; lastProgressAt = Instant.now(); }
        boolean incomplete;
        int done() { return succeeded + failed + skipped + cancelled; }
    }

    private static String pageKey(String bookId, int pageNumber) { return bookId + ":" + pageNumber; }
    private Entry owned(String bookId, int pageNumber, UUID attemptId) {
        Entry e = entries.get(attemptId);
        return e != null && e.bookId.equals(bookId) && e.pageNumber == pageNumber ? e : null;
    }
    private Entry running(String bookId, int pageNumber, UUID attemptId) {
        Entry e = owned(bookId, pageNumber, attemptId);
        return e != null && !TERMINAL.contains(e.lifecycle) ? e : null;
    }

    public synchronized UUID begin(String bookId, int pageNumber, int publishedRevision) {
        if (entries.size() >= MAX_TRACKED) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext() && entries.size() >= MAX_TRACKED) {
                Entry old = iterator.next().getValue();
                if (!TERMINAL.contains(old.lifecycle)) continue;
                iterator.remove();
                latestAttempts.remove(pageKey(old.bookId, old.pageNumber), old.attemptId);
            }
        }
        if (entries.size() >= MAX_TRACKED) throw new IllegalStateException("Progress capacity exhausted");
        UUID id = UUID.randomUUID();
        entries.put(id, new Entry(bookId, pageNumber, id, publishedRevision));
        // A new attempt is newer even when an old attempt emitted many more events.
        latestAttempts.put(pageKey(bookId, pageNumber), id);
        return id;
    }

    public synchronized void stage(String bookId, int pageNumber, UUID attemptId, String stage) {
        Entry e = running(bookId, pageNumber, attemptId);
        if (e == null || stage == null || stage.equals(e.stage)) return;
        e.stage = stage; e.stageStartedAt = Instant.now(); e.changed();
    }

    public synchronized void plan(String bookId, int pageNumber, UUID attemptId, String kind, int total) {
        if (total < 0) throw new IllegalArgumentException("Negative unit count");
        Entry e = running(bookId, pageNumber, attemptId);
        if (e == null) return;
        if (java.util.Objects.equals(e.kind, kind) && e.total == total) return;
        e.kind = kind; e.total = total;
        e.succeeded = e.failed = e.skipped = e.cancelled = e.inFlight = 0;
        e.changed();
    }

    public synchronized void unitDone(String bookId, int pageNumber, UUID attemptId, boolean ok) {
        Entry e = running(bookId, pageNumber, attemptId);
        if (e == null || e.done() >= e.total) return;
        if (ok) e.succeeded++; else e.failed++;
        e.inFlight = Math.max(0, e.inFlight - 1); e.changed();
    }

    public synchronized void inFlight(String bookId, int pageNumber, UUID attemptId, int delta) {
        Entry e = running(bookId, pageNumber, attemptId);
        if (e == null) return;
        int next = (int) Math.max(0L, Math.min((long) e.total - e.done(), (long) e.inFlight + delta));
        if (next != e.inFlight) { e.inFlight = next; e.version++; }
        // Dispatch/heartbeat is not a completed unit; it does not refresh lastProgressAt.
    }

    public synchronized void baselinePublished(String bookId, int pageNumber, UUID attemptId,
                                                int revision, boolean enhanced) {
        Entry e = running(bookId, pageNumber, attemptId);
        if (e == null) return;
        e.revision = revision; e.availability = enhanced ? "ENHANCED" : "OCR_READABLE";
        e.canRead = true; e.changed();
    }

    public synchronized void markIncomplete(String bookId, int pageNumber, UUID attemptId) {
        Entry e = running(bookId, pageNumber, attemptId);
        if (e != null && !e.incomplete) { e.incomplete = true; e.changed(); }
    }

    public synchronized void finish(String bookId, int pageNumber, UUID attemptId, String lifecycle,
                                     String messageCode, boolean canRetry) {
        if (!TERMINAL.contains(lifecycle)) throw new IllegalArgumentException("Invalid terminal lifecycle");
        Entry e = running(bookId, pageNumber, attemptId);
        if (e == null) return;
        boolean unfinished = e.incomplete || e.failed > 0 || e.skipped > 0 || e.cancelled > 0
                || e.done() < e.total;
        boolean partial = "SUCCEEDED".equals(lifecycle) && unfinished;
        e.lifecycle = partial ? "PARTIAL" : lifecycle;
        e.message = partial ? "WORK_PLAN_INCOMPLETE" : messageCode;
        e.canRetry = canRetry || partial;
        e.inFlight = 0;
        e.changed();
    }

    public synchronized ProcessingSnapshot snapshot(String bookId, int pageNumber, UUID attemptId) {
        Entry e = owned(bookId, pageNumber, attemptId);
        if (e == null) return null;
        return new ProcessingSnapshot(2, e.bookId, e.pageNumber, e.attemptId, e.version,
                e.lifecycle, e.stage, e.availability, e.revision, e.startedAt, e.stageStartedAt, e.lastProgressAt,
                new ProcessingSnapshot.UnitCounts(e.kind, e.total, e.succeeded, e.failed, e.skipped, e.cancelled, e.inFlight),
                e.canRead, !TERMINAL.contains(e.lifecycle), e.canRetry, e.message);
    }

    public synchronized ProcessingSnapshot latest(String bookId, int pageNumber) {
        UUID id = latestAttempts.get(pageKey(bookId, pageNumber));
        return id == null ? null : snapshot(bookId, pageNumber, id);
    }
}
