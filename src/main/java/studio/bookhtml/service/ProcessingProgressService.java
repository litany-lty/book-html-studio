package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.PageAttempt;
import studio.bookhtml.domain.ProcessingSnapshot;

import java.time.Instant;
import java.util.*;

/** Event-only, consistent reduction. Storage owns attempt identity; this is a bounded projection. */
@Service
public class ProcessingProgressService {
    private studio.bookhtml.store.BookStore store;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setStore(studio.bookhtml.store.BookStore store) { this.store=store; }
    private static final int MAX_TRACKED = 512;
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "INTERRUPTED", "UNKNOWN");
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Entry> latestByPage = new HashMap<>();

    private static final class Entry {
        final String bookId;
        final int pageNumber;
        final UUID attemptId;
        final long attemptSeq;
        long snapshotVersion;
        String lifecycle = "RUNNING";
        String stage = "PREPARING";
        String availability = "ORIGINAL_ONLY";
        int publishedRevision;
        final Instant startedAt = Instant.now();
        Instant stageStartedAt = startedAt;
        Instant lastProgressAt = startedAt;
        String unitKind = "PAGE";
        int total, succeeded, failed, skipped, cancelled, inFlight;
        long compatibilityUnit;
        final Set<String> endedUnits = new HashSet<>();
        boolean canRead;
        boolean canStop = true;
        boolean canRetry;
        String messageCode = "PROCESSING_STARTED";

        Entry(String bookId, int pageNumber, UUID attemptId, long attemptSeq, int revision, boolean readable) {
            this.bookId = bookId; this.pageNumber = pageNumber; this.attemptId = attemptId;
            this.attemptSeq = attemptSeq; this.publishedRevision = revision; this.canRead = readable;
            if (readable) availability = "OCR_READABLE";
        }
        boolean terminal() { return TERMINAL.contains(lifecycle); }
        void changed() { snapshotVersion++; lastProgressAt = Instant.now(); }
    }

    private static String pageKey(String bookId, int pageNumber) { return bookId + ":" + pageNumber; }
    private static String key(String bookId, int pageNumber, UUID attemptId) { return pageKey(bookId, pageNumber) + ":" + attemptId; }

    /** Compatibility entry for isolated callers; production uses the persistent PageAttempt overload. */
    public synchronized UUID begin(String bookId, int pageNumber, int revision) {
        Entry previous = latestByPage.get(pageKey(bookId, pageNumber));
        return begin(bookId, pageNumber, UUID.randomUUID(), previous == null ? 1 : Math.addExact(previous.attemptSeq, 1), revision, false);
    }

    public synchronized UUID begin(PageAttempt attempt, int revision, boolean readable) {
        Objects.requireNonNull(attempt);
        return begin(attempt.bookId(), attempt.pageNumber(), attempt.attemptId(), attempt.generation(), revision, readable);
    }

    private UUID begin(String bookId, int pageNumber, UUID attemptId, long seq, int revision, boolean readable) {
        String id = key(bookId, pageNumber, attemptId);
        Entry existing = entries.get(id);
        if (existing != null) return existing.attemptId;
        Entry latest = latestByPage.get(pageKey(bookId, pageNumber));
        if (latest != null && seq == latest.attemptSeq && !attemptId.equals(latest.attemptId))
            throw new IllegalStateException("attempt sequence collision");
        if (entries.size() >= MAX_TRACKED) {
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext() && entries.size() >= MAX_TRACKED) {
                Entry candidate = iterator.next().getValue();
                if (!candidate.terminal()) continue;
                iterator.remove();
                latestByPage.remove(pageKey(candidate.bookId, candidate.pageNumber), candidate);
            }
        }
        if (entries.size() >= MAX_TRACKED) throw new IllegalStateException("progress capacity exhausted");
        Entry entry = new Entry(bookId, pageNumber, attemptId, seq, revision, readable);
        entries.put(id, entry);
        // Event counts from different attempts are incomparable. Only the authority's sequence orders attempts.
        if (latest == null || seq > latest.attemptSeq) latestByPage.put(pageKey(bookId, pageNumber), entry);
        return attemptId;
    }

    public synchronized void stage(String bookId, int pageNumber, UUID attemptId, String stage) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal() || Objects.equals(e.stage, stage)) return;
        e.stage = Objects.requireNonNull(stage); e.stageStartedAt = Instant.now(); e.changed();
    }

    public synchronized void plan(String bookId, int pageNumber, UUID attemptId, String unitKind, int total) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal()) return;
        if (total < 0 || total > 4096) throw new IllegalArgumentException("invalid plan size");
        if (!e.endedUnits.isEmpty() && (e.total != total || !Objects.equals(e.unitKind, unitKind)))
            throw new IllegalStateException("completed plan denominator is frozen");
        if (e.total == total && Objects.equals(e.unitKind, unitKind)) return;
        e.unitKind = Objects.requireNonNull(unitKind); e.total = total;
        e.inFlight = Math.min(e.inFlight, Math.max(0, total - e.endedUnits.size())); e.changed();
    }

    /** Compatibility adapter for callers with one completion callback per unit. */
    public synchronized void unitDone(String bookId, int pageNumber, UUID attemptId, boolean ok) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal() || e.endedUnits.size() >= e.total) return;
        unitDone(bookId, pageNumber, attemptId, "compat:" + (++e.compatibilityUnit), ok ? "SUCCEEDED" : "FAILED");
    }

    public synchronized void unitDone(String bookId, int pageNumber, UUID attemptId, String unitId, String outcome) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal() || e.endedUnits.contains(unitId)) return;
        if (unitId == null || unitId.isBlank() || unitId.length() > 200) throw new IllegalArgumentException("invalid unit id");
        if (!Set.of("SUCCEEDED", "FAILED", "DEFERRED", "SKIPPED", "CANCELLED").contains(outcome))
            throw new IllegalArgumentException("invalid unit outcome");
        if (e.endedUnits.size() >= e.total) throw new IllegalStateException("unit exceeds frozen plan");
        e.endedUnits.add(unitId);
        switch (outcome) {
            case "SUCCEEDED" -> e.succeeded++;
            case "FAILED" -> e.failed++;
            case "CANCELLED" -> e.cancelled++;
            default -> e.skipped++;
        }
        e.inFlight = Math.min(Math.max(0, e.inFlight - 1), e.total - e.endedUnits.size()); e.changed();
    }

    public synchronized void inFlight(String bookId, int pageNumber, UUID attemptId, int delta) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal()) return;
        int next = (int) Math.max(0, Math.min((long) e.total - e.endedUnits.size(), (long) e.inFlight + delta));
        if (next == e.inFlight) return;
        e.inFlight = next; e.snapshotVersion++; // admission/heartbeat is not completed work
    }

    /** Must be called with the actual commit return value, never an inferred revision. */
    public synchronized void baselinePublished(String bookId, int pageNumber, UUID attemptId, int revision, boolean enhanced) {
        publication(bookId,pageNumber,attemptId,revision,true,enhanced);
    }
    public synchronized void publication(String bookId,int pageNumber,UUID attemptId,int revision,boolean readable,boolean enhanced) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal() || revision < e.publishedRevision) return;
        e.publishedRevision = revision;
        e.availability = readable ? enhanced ? "ENHANCED" : "OCR_READABLE" : "ORIGINAL_ONLY";
        e.canRead = readable; e.changed();
    }

    public synchronized void finish(String bookId, int pageNumber, UUID attemptId, String lifecycle, String messageCode, boolean canRetry) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal()) return;
        if (!TERMINAL.contains(lifecycle)) throw new IllegalArgumentException("not a terminal lifecycle");
        boolean incomplete = "SUCCEEDED".equals(lifecycle)
                && (e.failed + e.skipped + e.cancelled > 0 || e.endedUnits.size() < e.total);
        e.lifecycle = incomplete ? "PARTIAL" : lifecycle;
        e.messageCode = incomplete ? "WORK_PLAN_INCOMPLETE" : messageCode; e.canRetry = canRetry || incomplete; e.canStop = false; e.inFlight = 0; e.changed();
    }

    public synchronized ProcessingSnapshot snapshot(String bookId, int pageNumber, UUID attemptId) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        return e == null ? null : snapshot(e);
    }

    private ProcessingSnapshot snapshot(Entry e) {
        return new ProcessingSnapshot(2, e.bookId, e.pageNumber, e.attemptId, e.snapshotVersion,
                e.lifecycle, e.stage, e.availability, e.publishedRevision, e.startedAt,
                e.stageStartedAt, e.lastProgressAt,
                new ProcessingSnapshot.UnitCounts(e.unitKind, e.total, e.succeeded, e.failed, e.skipped, e.cancelled, e.inFlight),
                e.canRead, e.canStop, e.canRetry, e.messageCode, e.attemptSeq);
    }

    public ProcessingSnapshot latest(String bookId, int pageNumber) {
        synchronized(this) {
            Entry entry=latestByPage.get(pageKey(bookId,pageNumber));
            if(entry!=null) return snapshot(entry);
        }
        if(store==null) return null;
        // Never hold the progress monitor while acquiring the storage authority monitor.
        PageAttempt attempt=store.pageAttempt(bookId,pageNumber);
        if(attempt==null) return null;
        var page=store.readPage(bookId,pageNumber);
        boolean readable=page!=null && "READY".equals(page.status());
        String lifecycle=attempt.lifecycle();
        boolean terminal=TERMINAL.contains(lifecycle);
        ProcessingSnapshot recovered=new ProcessingSnapshot(2,bookId,pageNumber,attempt.attemptId(),0,
                lifecycle,"RECOVERED",readable?"OCR_READABLE":"ORIGINAL_ONLY",
                attempt.expectedRevision(),attempt.startedAt(),attempt.updatedAt(),attempt.updatedAt(),
                new ProcessingSnapshot.UnitCounts("RECOVERED",0,0,0,0,0,0),readable,!terminal,
                terminal && !Set.of("UNKNOWN","SUCCEEDED").contains(lifecycle),"PERSISTED_ATTEMPT_STATE",attempt.generation());
        synchronized(this) {
            Entry latest=latestByPage.get(pageKey(bookId,pageNumber));
            return latest!=null && latest.attemptSeq>=attempt.generation()?snapshot(latest):recovered;
        }
    }
}
