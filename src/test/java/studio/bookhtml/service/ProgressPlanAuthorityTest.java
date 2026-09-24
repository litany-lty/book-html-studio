package studio.bookhtml.service;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProgressPlanAuthorityTest {
    @Test void childFreezeNeverChangesParentIdentity() {
        var p=WorkPlan.createDefault("book",1,0,1,"context");
        var frozen=p.freezeReviewSubPlan(2,List.of("one","two"));
        assertEquals(p.parentPlanHash(),frozen.parentPlanHash());
        assertEquals(frozen.parentPlanHash(),WorkPlan.computeParentPlanHash("book",1,0,1,"context",frozen.stages()));
    }
    @Test void childUnitsMustBeDistinctAndCoverTheDeclaredDenominator() {
        var p=WorkPlan.createDefault("book",1,0,1,"context");
        assertThrows(IllegalArgumentException.class,()->p.freezeReviewSubPlan(2,List.of("one","one")));
        assertThrows(IllegalArgumentException.class,()->p.freezeReviewSubPlan(2,List.of("one")));
    }
    @Test void legacyPagePlanDoesNotFreezeOrConsumeReviewUnits() {
        var p=new ProcessingProgressService();UUID id=p.begin("book",1,0);
        p.plan("book",1,id,"PAGE",1);p.stage("book",1,id,"REVIEW");p.plan("book",1,id,"TEXT_GROUPS",4);
        for(int i=1;i<=4;i++) {
            p.unitDone("book",1,id,"unit-"+i,"SUCCEEDED");
            assertEquals(35*i/4,p.latest("book",1).weightedPercent());
            assertEquals(i,p.latest("book",1).units().succeeded());
        }
    }
    @Test void rejectedPlanDoesNotMutateLiveCountsOrHashes() {
        var p=new ProcessingProgressService();UUID id=p.begin("book",1,0);
        p.stage("book",1,id,"REVIEW");p.plan("book",1,id,"TEXT_GROUPS",4);
        p.unitDone("book",1,id,"first","SUCCEEDED");var before=p.latest("book",1);
        var changed=WorkPlan.createDefault("book",1,0,1,"").freezeReviewSubPlan(2,null).transitionStage("REVIEW");
        assertThrows(IllegalStateException.class,()->p.plan("book",1,id,changed));
        assertEquals(before,p.latest("book",1));
    }
    private PageAttempt authority(UUID id,long generation,String lifecycle) {
        var a=mock(PageAttempt.class);when(a.attemptId()).thenReturn(id);when(a.generation()).thenReturn(generation);
        when(a.lifecycle()).thenReturn(lifecycle);when(a.startedAt()).thenReturn(Instant.EPOCH);when(a.updatedAt()).thenReturn(Instant.EPOCH);
        return a;
    }
    @Test void cachedOldSuccessCannotHideNewInterruptedAttempt() {
        var p=new ProcessingProgressService();UUID first=p.begin("book",1,0);p.finish("book",1,first,"SUCCEEDED","done",false);
        var store=mock(BookStore.class);UUID next=UUID.randomUUID();
        var a=authority(next,2,"INTERRUPTED");when(store.pageAttempt("book",1)).thenReturn(a);
        p.setStore(store);p.setJournal(mock(ProgressJournal.class));
        var actual=p.latest("book",1);assertEquals(next,actual.attemptId());assertEquals("INTERRUPTED",actual.lifecycle());
    }
    @Test void contradictorySameGenerationCannotBeShownAsSuccess() {
        var p=new ProcessingProgressService();UUID first=p.begin("book",1,0);p.finish("book",1,first,"SUCCEEDED","done",false);
        var store=mock(BookStore.class);var a=authority(UUID.randomUUID(),1,"INTERRUPTED");when(store.pageAttempt("book",1)).thenReturn(a);
        p.setStore(store);p.setJournal(mock(ProgressJournal.class));
        assertEquals("UNKNOWN",p.latest("book",1).lifecycle());assertFalse(p.latest("book",1).canRetry());
    }
    @Test void persistedCancellationSupersedesSameAttemptTelemetry() {
        var p=new ProcessingProgressService();UUID id=p.begin("book",1,0);p.stage("book",1,id,"OCR");
        long old=p.latest("book",1).snapshotVersion();var store=mock(BookStore.class);
        var a=authority(id,1,"CANCELLED");when(store.pageAttempt("book",1)).thenReturn(a);
        p.setStore(store);p.setJournal(mock(ProgressJournal.class));
        var actual=p.latest("book",1);assertEquals("CANCELLED",actual.lifecycle());
        assertTrue(actual.snapshotVersion()>old);assertFalse(actual.canStop());
        assertEquals(actual,p.latest("book",1),"polling does not fabricate further progress");
    }

    @Test void cancellationBetweenFinalCheckAndJobWriteCannotLeaveRunningJob() throws Exception {
        var stored=new java.util.concurrent.atomic.AtomicReference<>(new Job("owned","RUNNING",1,1,null,null,List.of(),Instant.EPOCH));
        var store=mock(BookStore.class);when(store.readJob("book")).thenAnswer(inv->stored.get());
        doAnswer(inv->{stored.set(inv.getArgument(1));return null;}).when(store).writeJob(eq("book"),any(Job.class));
        var jobs=new JobService(store,mock(BookService.class),mock(PageProcessor.class));
        var runningType=Class.forName(JobService.class.getName()+"$Running");
        var ctor=runningType.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object running=ctor.newInstance("book","fingerprint",null);
        var cancelled=runningType.getDeclaredField("cancelled");cancelled.setAccessible(true);cancelled.set(running,true);
        var active=JobService.class.getDeclaredField("active");active.setAccessible(true);active.set(jobs,running);
        try {
            var write=JobService.class.getDeclaredMethod("writeIfCurrent",runningType,String.class,Job.class);write.setAccessible(true);
            write.invoke(jobs,running,"owned",new Job("owned","COMPLETED",1,1,null,null,List.of(),Instant.EPOCH));
            assertEquals("CANCELLED",stored.get().status());
            assertEquals(1,stored.get().completed());
        } finally { active.set(jobs,null);jobs.close(); }
    }
}
