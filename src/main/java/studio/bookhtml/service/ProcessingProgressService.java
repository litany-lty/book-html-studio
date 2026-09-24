package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.PageAttempt;
import studio.bookhtml.domain.ProcessingSnapshot;
import studio.bookhtml.domain.WorkPlan;
import studio.bookhtml.store.BookStore;

import java.time.Instant;
import java.util.*;

/** Event-only, consistent reduction. Storage owns attempt identity; this is a bounded projection. */
@Service
public class ProcessingProgressService {
    private studio.bookhtml.store.BookStore store;
    private ProgressJournal journal;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setStore(studio.bookhtml.store.BookStore store) {
        this.store = store;
        if (this.journal == null && store != null) {
            this.journal = new ProgressJournal(store);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setJournal(ProgressJournal journal) {
        this.journal = journal;
    }

    public ProgressJournal getJournal() {
        return this.journal;
    }

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

        WorkPlan workPlan;
        long eventSeqCounter = 0;

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
        entry.workPlan = WorkPlan.createDefault(bookId, pageNumber, revision, seq, null);
        entries.put(id, entry);
        // Event counts from different attempts are incomparable. Only the authority's sequence orders attempts.
        if (latest == null || seq > latest.attemptSeq) latestByPage.put(pageKey(bookId, pageNumber), entry);
        return attemptId;
    }

    public synchronized void stage(String bookId, int pageNumber, UUID attemptId, String stage) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal() || Objects.equals(e.stage, stage)) return;
        e.stage = Objects.requireNonNull(stage); e.stageStartedAt = Instant.now(); e.changed();
        if (e.workPlan != null) {
            e.workPlan = e.workPlan.transitionStage(stage);
        }
        if (journal != null) {
            journal.recordEvent(e.bookId, e.pageNumber, e.attemptId, e.attemptSeq,
                    new ProgressJournal.JournalEvent(
                            ++e.eventSeqCounter, "STAGE_TRANSITION", stage, null, null, null, null,
                            e.workPlan != null ? e.workPlan.parentPlanHash() : null,
                            e.workPlan != null ? e.workPlan.reviewPlanHash() : null,
                            e.workPlan != null ? e.workPlan.contextHash() : null,
                            e.total, Instant.now()
                    ));
        }
    }

    public synchronized void plan(String bookId, int pageNumber, UUID attemptId, String unitKind, int total) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal()) return;
        if (total < 0 || total > 4096) throw new IllegalArgumentException("invalid plan size");
        if (!e.endedUnits.isEmpty() && (e.total != total || !Objects.equals(e.unitKind, unitKind)))
            throw new IllegalStateException("completed plan denominator is frozen");
        if (e.total == total && Objects.equals(e.unitKind, unitKind)) return;
        Objects.requireNonNull(unitKind);
        if(!"PAGE".equals(unitKind) && unitKind.isBlank())throw new IllegalArgumentException("invalid unit kind");
        // Validate the derived plan before updating the live counters. PAGE is not a review plan.
        WorkPlan next=e.workPlan;
        if(next!=null && !"PAGE".equals(unitKind))next=next.freezeReviewSubPlan(total,null);
        e.workPlan=next;e.unitKind=unitKind;e.total=total;
        e.inFlight=Math.min(e.inFlight,Math.max(0,total-e.endedUnits.size()));e.changed();
        if (journal != null) {
            journal.recordEvent(e.bookId, e.pageNumber, e.attemptId, e.attemptSeq,
                    new ProgressJournal.JournalEvent(
                            ++e.eventSeqCounter, "PLAN_FROZEN", e.stage, null, null, null, null,
                            e.workPlan != null ? e.workPlan.parentPlanHash() : null,
                            e.workPlan != null ? e.workPlan.reviewPlanHash() : null,
                            e.workPlan != null ? e.workPlan.contextHash() : null,
                            e.total, Instant.now()
                    ));
        }
    }

    public synchronized void plan(String bookId, int pageNumber, UUID attemptId, WorkPlan workPlan) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || e.terminal()) return;
        Objects.requireNonNull(workPlan);
        if(!e.bookId.equals(workPlan.bookId())||e.pageNumber!=workPlan.pageNumber()
                ||e.attemptSeq!=workPlan.eventSeq()||e.workPlan.pageRevision()!=workPlan.pageRevision()
                ||!"RUNNING".equals(workPlan.lifecycle())||!e.stage.equals(workPlan.currentStage()))
            throw new IllegalArgumentException("work plan belongs to another execution or stage");
        String parent=WorkPlan.computeParentPlanHash(workPlan.bookId(),workPlan.pageNumber(),workPlan.pageRevision(),
                workPlan.eventSeq(),workPlan.contextHash(),workPlan.stages());
        if(!parent.equals(workPlan.parentPlanHash()))throw new IllegalArgumentException("invalid parent plan hash");
        if(workPlan.reviewSubPlan()!=null) {
            var child=workPlan.reviewSubPlan();
            String hash=WorkPlan.computeReviewPlanHash(e.bookId,e.pageNumber,e.attemptSeq,workPlan.contextHash(),parent,child.totalUnits(),child.unitIds());
            if(!hash.equals(child.reviewPlanHash())||!hash.equals(workPlan.reviewPlanHash()))throw new IllegalArgumentException("invalid child plan hash");
        }
        if(!e.endedUnits.isEmpty() || e.workPlan.frozen()) {
            if(!e.workPlan.equals(workPlan))throw new IllegalStateException("cannot replace a frozen or progressed plan");
            return;
        }
        e.workPlan=workPlan;
        if(workPlan.reviewSubPlan()!=null){e.total=workPlan.reviewSubPlan().totalUnits();e.unitKind="CHUNK";}
        e.changed();
        if (journal != null) {
            journal.recordEvent(e.bookId, e.pageNumber, e.attemptId, e.attemptSeq,
                    new ProgressJournal.JournalEvent(
                            ++e.eventSeqCounter, "PLAN_FROZEN", e.stage, null, null, null, null,
                            workPlan.parentPlanHash(), workPlan.reviewPlanHash(), workPlan.contextHash(),
                            e.total, Instant.now()
                    ));
        }
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
        WorkPlan next=e.workPlan;
        if(next!=null) {
            String planStage="PAGE".equals(e.unitKind)?e.stage:"REVIEW";
            next=next.recordUnitDone(planStage,unitId,outcome);
        }
        e.workPlan=next;e.endedUnits.add(unitId);
        switch(outcome){case "SUCCEEDED"->e.succeeded++;case "FAILED"->e.failed++;case "CANCELLED"->e.cancelled++;default->e.skipped++;}
        e.inFlight=Math.min(Math.max(0,e.inFlight-1),e.total-e.endedUnits.size());e.changed();
        if (journal != null) {
            journal.recordEvent(e.bookId, e.pageNumber, e.attemptId, e.attemptSeq,
                    new ProgressJournal.JournalEvent(
                            ++e.eventSeqCounter, "UNIT_DONE", e.stage, unitId, outcome, null, null,
                            e.workPlan != null ? e.workPlan.parentPlanHash() : null,
                            e.workPlan != null ? e.workPlan.reviewPlanHash() : null,
                            e.workPlan != null ? e.workPlan.contextHash() : null,
                            e.total, Instant.now()
                    ));
        }
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
        if (e.workPlan != null) {
            e.workPlan = e.workPlan.finish(e.lifecycle);
        }
        if (journal != null) {
            journal.recordEvent(e.bookId, e.pageNumber, e.attemptId, e.attemptSeq,
                    new ProgressJournal.JournalEvent(
                            ++e.eventSeqCounter, "FINISHED", e.stage, null, null, e.lifecycle, e.messageCode,
                            e.workPlan != null ? e.workPlan.parentPlanHash() : null,
                            e.workPlan != null ? e.workPlan.reviewPlanHash() : null,
                            e.workPlan != null ? e.workPlan.contextHash() : null,
                            e.total, Instant.now()
                    ));
        }
    }

    public synchronized ProcessingSnapshot snapshot(String bookId, int pageNumber, UUID attemptId) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        return e == null ? null : snapshot(e);
    }

    private ProcessingSnapshot snapshot(Entry e) {
        String parentPlanHash = e.workPlan != null ? e.workPlan.parentPlanHash() : null;
        String reviewPlanHash = e.workPlan != null ? e.workPlan.reviewPlanHash() : null;
        String contextHash = e.workPlan != null ? e.workPlan.contextHash() : null;
        int weightedPercent = e.workPlan != null ? e.workPlan.weightedPercent() : -1;
        double accuracyRatio = e.workPlan != null ? e.workPlan.accuracyRatio() : (
                (e.succeeded + e.failed + e.skipped + e.cancelled == 0) ? 1.0 : (double) e.succeeded / (e.succeeded + e.failed + e.skipped + e.cancelled)
        );
        return new ProcessingSnapshot(2, e.bookId, e.pageNumber, e.attemptId, e.snapshotVersion,
                e.lifecycle, e.stage, e.availability, e.publishedRevision, e.startedAt,
                e.stageStartedAt, e.lastProgressAt,
                new ProcessingSnapshot.UnitCounts(e.unitKind, e.total, e.succeeded, e.failed, e.skipped, e.cancelled, e.inFlight),
                e.canRead, e.canStop, e.canRetry, e.messageCode, e.attemptSeq,
                parentPlanHash, reviewPlanHash, contextHash, weightedPercent, accuracyRatio);
    }

    private ProcessingSnapshot memory(String book,int page) {
        synchronized(this){Entry e=latestByPage.get(pageKey(book,page));return e==null?null:snapshot(e);}
    }
    private boolean same(PageAttempt authority,ProcessingSnapshot candidate) {
        return candidate!=null && authority.generation()==candidate.attemptSeq()
                && authority.attemptId().equals(candidate.attemptId());
    }
    /** Authority wins over telemetry. Do not acquire the storage monitor inside the progress monitor. */
    public ProcessingSnapshot latest(String bookId,int pageNumber) {
        ProcessingSnapshot live=memory(bookId,pageNumber);
        if(store==null)return live;
        PageAttempt authority=store.pageAttempt(bookId,pageNumber);
        // An admission may have completed between the authority read and this snapshot.
        ProcessingSnapshot newer=memory(bookId,pageNumber);
        if(newer!=null&&(live==null||newer.attemptSeq()>=live.attemptSeq()))live=newer;
        if(authority!=null&&live!=null&&live.attemptSeq()>authority.generation())return live;
        if(authority!=null&&same(authority,live)
                && (authority.lifecycle().equals(live.lifecycle())
                || !TERMINAL.contains(authority.lifecycle())&&!"DRAINING".equals(authority.lifecycle())&&!TERMINAL.contains(live.lifecycle())))return live;
        var head=store.headStore()==null?null:store.headStore().readHead(store.bookDir(bookId),pageNumber);
        var page=head==null?store.readPage(bookId,pageNumber):null;
        boolean readable=head!=null?head.processed():page!=null&&"READY".equals(page.status());
        int revision=head!=null?head.revision():BookStore.revisionOrZero(page);
        ProcessingSnapshot logged=null;
        if(journal!=null&&journal.hasJournal(bookId,pageNumber)) {
            var recovery=journal.replay(bookId,pageNumber);
            if(recovery!=null)logged=recovery.toSnapshot(revision,readable);
        }
        if(authority!=null) {
            if(same(authority,logged)&&authority.lifecycle().equals(logged.lifecycle()))return logged;
            String lifecycle=authority.lifecycle();boolean terminal=TERMINAL.contains(lifecycle);
            long version=Math.max(same(authority,live)?live.snapshotVersion():-1,same(authority,logged)?logged.snapshotVersion():-1)+1;
            if((live!=null&&live.attemptSeq()==authority.generation()&&!same(authority,live))
                    ||(logged!=null&&logged.attemptSeq()==authority.generation()&&!same(authority,logged))) {
                lifecycle="UNKNOWN";terminal=true;
            }
            return new ProcessingSnapshot(2,bookId,pageNumber,authority.attemptId(),version,lifecycle,"RECOVERED",
                    readable?"OCR_READABLE":"ORIGINAL_ONLY",revision,authority.startedAt(),authority.updatedAt(),authority.updatedAt(),
                    new ProcessingSnapshot.UnitCounts("RECOVERED",0,0,0,0,0,0),readable,!terminal,
                    terminal&&!Set.of("UNKNOWN","SUCCEEDED").contains(lifecycle),"PERSISTED_ATTEMPT_STATE",authority.generation());
        }
        if(live!=null)return live;
        if(logged!=null)return logged; // Legacy books without attempt authority retain their historical projection.
        if(head!=null&&head.attemptId()!=null) {
            String lifecycle=head.attemptLifecycle()==null?"UNKNOWN":head.attemptLifecycle();boolean terminal=TERMINAL.contains(lifecycle);
            return new ProcessingSnapshot(2,bookId,pageNumber,head.attemptId(),0,lifecycle,head.attemptStage()==null?"RECOVERED":head.attemptStage(),
                    readable?"OCR_READABLE":"ORIGINAL_ONLY",revision,head.updatedAt(),head.updatedAt(),head.updatedAt(),
                    new ProcessingSnapshot.UnitCounts("RECOVERED",0,0,0,0,0,0),readable,!terminal,
                    terminal&&!Set.of("UNKNOWN","SUCCEEDED").contains(lifecycle),"PERSISTED_ATTEMPT_HEAD",head.attemptSeq()==null?1:head.attemptSeq());
        }
        return null;
    }
}
