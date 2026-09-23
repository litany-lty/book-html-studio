package studio.bookhtml.service;

import studio.bookhtml.domain.*;
import studio.bookhtml.store.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One page state machine. Entry adapters own scheduling; this class owns publication and settlement. */
public final class PageProcessingService {
    private final BookStore store;
    private final PageProcessor processor;
    private ProcessingProgressService progress;
    private QwenRequestGate gate;
    private ReadingPriority priority;
    public record Request(PageAttempt attempt, String provider, String layout, boolean split,
                          boolean force, boolean assist, BooleanSupplier cancelled, BooleanSupplier owned) {
        public Request { Objects.requireNonNull(attempt); Objects.requireNonNull(cancelled); Objects.requireNonNull(owned); }
        String book() { return attempt.bookId(); }
        int page() { return attempt.pageNumber(); }
        UUID id() { return attempt.attemptId(); }
    }
    public record Result(String lifecycle, String messageCode, Integer publishedRevision) {}
    PageProcessingService(BookStore store, PageProcessor processor) { this.store=store; this.processor=processor; }
    void setProgress(ProcessingProgressService value) { progress=value; }
    void setResources(QwenRequestGate value, ReadingPriority readingPriority) { gate=value; priority=readingPriority; }
    private static final Set<String> TERMINAL=Set.of("SUCCEEDED","PARTIAL","FAILED","CANCELLED","INTERRUPTED","UNKNOWN");
    public Result execute(Request request) {
        Execution e=new Execution(request);
        try (QwenExecutionScope scope=QwenExecutionScope.open(request.attempt(),gate,
                priority!=null && priority.foreground(request.book(),request.page()))) {
            e.run();
        } catch (CancelledException | java.util.concurrent.CancellationException cancelled) {
            e.lifecycle="CANCELLED"; e.message="CANCELLED_CONTENT_KEPT";
        } catch (PageConflictException conflict) {
            e.lifecycle=e.baselineCommitted?"PARTIAL":"FAILED";
            e.message=e.baselineCommitted?"MANUAL_KEPT_ENHANCEMENT_CANDIDATE":"BASELINE_WRITE_FAILED";
            if(e.publishing) e.publicationFailed();
            else if(e.candidate!=null) candidate(request,e.candidate);
        } catch (Exception failure) {
            e.lifecycle=e.baselineCommitted?"PARTIAL":"FAILED";
            e.message=e.baselineCommitted?"ENHANCEMENT_FAILED_BASELINE_KEPT":"PROCESSING_FAILED_CONTENT_KEPT";
            if(e.publishing) e.publicationFailed();
            else if(!e.baselineCommitted && e.old!=null) {
                String detail=JobService.safeDetail(failure);
                e.restore(e.old,"第 "+request.page()+" 页处理失败"+(detail==null?"":"："+detail));
            }
        } finally {
            if(!e.decided && (request.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted())) {
                e.lifecycle="CANCELLED"; e.message="CANCELLED_CONTENT_KEPT";
            }
            try {
                if("SUCCEEDED".equals(e.lifecycle)) completePageUnit(request);
                e.lifecycle=effectiveOutcome(request,e.lifecycle);
            } catch(RuntimeException projectionFailed) {
                e.lifecycle=e.baselineCommitted?"PARTIAL":"FAILED";
                e.message="PROGRESS_REDUCTION_FAILED";
            } finally {
                e.lifecycle=finish(request.attempt(),e.lifecycle,e.message);
                if("UNKNOWN".equals(e.lifecycle)) e.message="ATTEMPT_JOURNAL_WRITE_FAILED";
            }
        }
        return new Result(e.lifecycle,e.message,e.published==null?null:e.published.revision());
    }
    private final class Execution {
        final Request r;
        Page old, published, candidate;
        boolean baselineCommitted, decided, publishing;
        String lifecycle="FAILED", message="PAGE_PROCESSING_FAILED";
        Execution(Request request) { r=request; }
        void run() throws Exception {
            check(r);
            old=store.readPage(r.book(),r.page());
            if(old==null) throw new OcrException("页面记录不存在");
            if(progress!=null) {
                progress.begin(r.attempt(),BookStore.revisionOrZero(old),"READY".equals(old.status()));
                progress.plan(r.book(),r.page(),r.id(),"PAGE",1);
            }
            if("READY".equals(old.status()) && !r.force()) {
                published=old; lifecycle="SUCCEEDED"; message="ALREADY_READABLE"; decided=true; return;
            }
            Page strongest=r.force()?JobService.strongestBaseline(old,store.readOriginalPage(r.book(),r.page())):old;
            stage(r,"OCR");
            store.verifyAttemptForDispatch(r.attempt(),CommitOp.JOB_BASELINE);
            ProcessingResult extracted=processor.processBaseline(r.book(),r.page(),r.provider(),r.layout(),r.split(),r.cancelled());
            check(r);
            if(extracted==null || extracted.page()==null) throw new OcrException("识别返回缺少页面结果");
            candidate=JobService.mergeUnresolvedIssues(old,extracted.page());
            boolean noText=extracted.category()==ProcessingResult.Category.BLANK_CONFIRMED
                    || extracted.category()==ProcessingResult.Category.VISUAL_ONLY;
            if(JobService.isSignificantRegression(strongest,candidate) || !noText && JobService.isEmptyResult(candidate)) {
                candidate(r,candidate);
                restore(strongest,"重识别文字显著减少或为空，保留较完整版本");
                lifecycle="FAILED"; message="BASELINE_REJECTED"; decided=true; return;
            }
            boolean partial=extracted.category()==ProcessingResult.Category.TEXT_PARTIAL;
            boolean enhance=r.assist() && !partial && !noText;
            stage(r,"BASELINE_PUBLISHING");
            publishing=true;
            published=store.commitPage(r.book(),candidate,BookStore.revisionOrZero(old),CommitActor.JOB,
                    r.attempt().commitIdentity(partial?"PARTIAL":enhance?"BASELINE_PUBLISHED":"SUCCEEDED"),CommitOp.JOB_BASELINE);
            publishing=false; baselineCommitted=true;
            try { store.preserveOriginal(r.book(),published); } catch(IOException auxiliary) { warn("ORIGINAL_SNAPSHOT_PENDING"); }
            onPublished(r,published,false);
            if(partial) { lifecycle="PARTIAL"; message="OCR_RECOVERY_PARTIAL"; decided=true; return; }
            if(!enhance) { lifecycle="SUCCEEDED"; message=noText?"NO_TEXT_EVIDENCE_COMPLETE":"BASELINE_ONLY"; decided=true; return; }
            // Persist phase ownership before optional calls; never lose an already-readable baseline.
            store.finishPageAttempt(r.attempt(),"BASELINE_PUBLISHED");
            check(r); stage(r,"STRUCTURE");
            store.verifyAttemptForDispatch(r.attempt(),CommitOp.JOB_ENHANCEMENT);
            PageProcessor.EnrichResult enhanced=processor.enrichBaseline(r.book(),r.page(),published,r.provider(),r.layout(),r.cancelled());
            check(r); stage(r,"VALIDATING");
            if(enhanced==null || enhanced.blocks()==null) throw new OcrException("增强返回缺少结果");
            List<String> warnings=new ArrayList<>(published.warnings()==null?List.of():published.warnings());
            if(enhanced.warnings()!=null) warnings.addAll(enhanced.warnings());
            candidate=JobService.mergeUnresolvedIssues(published,new Page(published.pageNumber(),published.width(),published.height(),
                    "READY",enhanced.actualProvider(),enhanced.blocks(),List.copyOf(warnings),false,null,published.sourceRecords()));
            int before=characters(published.blocks()), after=characters(candidate.blocks());
            if(before>0 && after<before*.6) {
                candidate(r,candidate); lifecycle="PARTIAL"; message="ENHANCEMENT_SKIPPED_BASELINE_KEPT"; decided=true; return;
            }
            lifecycle=effectiveOutcome(r,enhanced.complete()?"SUCCEEDED":"PARTIAL");
            stage(r,"PUBLISHING");
            publishing=true;
            published=store.commitPage(r.book(),candidate,published.revision(),CommitActor.JOB,
                    r.attempt().commitIdentity(lifecycle),CommitOp.JOB_ENHANCEMENT);
            publishing=false;
            onPublished(r,published,"SUCCEEDED".equals(lifecycle));
            message="SUCCEEDED".equals(lifecycle)?"ENHANCED":"ENHANCEMENT_PARTIAL"; decided=true;
        }
        void publicationFailed() {
            if(candidate==null)return;
            List<String> warnings=new ArrayList<>(candidate.warnings()==null?List.of():candidate.warnings());
            warnings.add("第 "+r.page()+" 页识别完成但写入失败，已保留旧版本");
            candidate(r,new Page(candidate.pageNumber(),candidate.width(),candidate.height(),candidate.status(),
                    candidate.provider(),candidate.blocks(),List.copyOf(warnings),candidate.reviewed(),candidate.error(),candidate.sourceRecords()));
            message=baselineCommitted?"ENHANCEMENT_WRITE_FAILED":"BASELINE_WRITE_FAILED";
        }
        void restore(Page baseline,String warning) {
            List<String> notes=new ArrayList<>(baseline.warnings()==null?List.of():baseline.warnings()); notes.add(warning);
            Page restored=new Page(baseline.pageNumber(),baseline.width(),baseline.height(),"READY".equals(baseline.status())?"READY":"FAILED",
                    baseline.provider(),baseline.blocks(),List.copyOf(notes),baseline.reviewed(),warning,baseline.sourceRecords());
            try {
                check(r);
                published=store.commitPage(r.book(),restored,BookStore.revisionOrZero(old),CommitActor.JOB,
                        r.attempt().commitIdentity("FAILED"),CommitOp.JOB_RESTORE);
                onPublished(r,published,false);
            } catch(Exception unavailable) { warn("FAILED_RESULT_NOT_PUBLISHED_CONTENT_KEPT"); }
        }
    }
    private static void check(Request r) {
        if(r.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancelledException();
        if(!r.owned().getAsBoolean()) throw new PageConflictException(r.attempt().expectedRevision(),"处理所有权已失效");
    }
    /** Used for executed pages, rejected admission and physical worker exit. Durable facts win. */
    String finish(PageAttempt owner,String requested,String message) {
        String terminal=requested;
        if(!TERMINAL.contains(requested)) throw new IllegalArgumentException("not a terminal outcome");
        try {
            store.finishPageAttempt(owner,requested);
            PageAttempt saved=store.pageAttempt(owner.bookId(),owner.pageNumber());
            if(saved==null || !saved.attemptId().equals(owner.attemptId()) || saved.generation()!=owner.generation()) terminal="INTERRUPTED";
            else if(TERMINAL.contains(saved.lifecycle())) terminal=saved.lifecycle();
            else terminal="UNKNOWN";
        } catch(Exception unavailable) { terminal="UNKNOWN"; message="ATTEMPT_JOURNAL_WRITE_FAILED"; }
        try {
            if(progress!=null) progress.finish(owner.bookId(),owner.pageNumber(),owner.attemptId(),terminal,message,
                    !Set.of("SUCCEEDED","CANCELLED","UNKNOWN","INTERRUPTED").contains(terminal));
        } catch(RuntimeException projectionFailed) { warn("ATTEMPT_SETTLED_PROGRESS_UNAVAILABLE"); }
        return terminal;
    }
    private void stage(Request r,String stage) { if(progress!=null) progress.stage(r.book(),r.page(),r.id(),stage); }
    private void onPublished(Request r,Page page,boolean enhanced) {
        if(progress!=null) progress.publication(r.book(),r.page(),r.id(),page.revision(),"READY".equals(page.status()),enhanced);
    }
    private void candidate(Request r,Page page) {
        try { store.writeCandidate(r.book(),page); } catch(IOException unavailable) { warn("CANDIDATE_ARCHIVE_UNAVAILABLE"); }
    }
    private static void warn(String code) { System.getLogger(PageProcessingService.class.getName()).log(System.Logger.Level.WARNING,code); }
    private static int characters(List<Block> blocks) {
        return blocks==null?0:blocks.stream().filter(Objects::nonNull).map(Block::original)
                .filter(Objects::nonNull).mapToInt(QualityGate::nonSpace).sum();
    }
    private String effectiveOutcome(Request r,String desired) {
        if(!"SUCCEEDED".equals(desired) || progress==null) return desired;
        var snapshot=progress.snapshot(r.book(),r.page(),r.id());
        if(snapshot!=null && !"PAGE".equals(snapshot.units().kind())) {
            var units=snapshot.units();
            if(units.failed()+units.skipped()+units.cancelled()>0 || units.succeeded()<units.total()) return "PARTIAL";
        }
        return desired;
    }
    private void completePageUnit(Request r) {
        if(progress==null) return;
        var s=progress.snapshot(r.book(),r.page(),r.id());
        if(s!=null && "PAGE".equals(s.units().kind()) && s.units().total()==1
                && s.units().succeeded()+s.units().failed()+s.units().skipped()+s.units().cancelled()==0)
            progress.unitDone(r.book(),r.page(),r.id(),"page-published","SUCCEEDED");
    }
}
