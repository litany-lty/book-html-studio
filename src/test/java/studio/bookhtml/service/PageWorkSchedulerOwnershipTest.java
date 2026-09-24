package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageWorkSchedulerOwnershipTest {
    PageProcessingService.Request request(String book,int page){return new PageProcessingService.Request(PageAttempt.register(book,page,0,"job",List.of("JOB_BASELINE","JOB_ENHANCEMENT")),"local","auto",false,true,true,()->false,()->true);}
    @Test void separateStageThreadsShareTheExactSameBudgetAndAttempt()throws Exception {
        var engine=mock(PageProcessingService.class);var request=request("book",1);
        QwenExecutionScope.Value value;
        try(var scope=QwenExecutionScope.open(request.attempt(),null,true)){value=QwenExecutionScope.current();}
        when(engine.executionScope(any())).thenReturn(value);
        when(engine.executeBaseline(any(),any())).thenAnswer(inv->{assertSame(value,QwenExecutionScope.current());assertTrue(value.budget().reserve(6));return ((PageExecutionRecord)inv.getArgument(1)).withBaselineCommitted(1);});
        when(engine.executeEnhancement(any(),any())).thenAnswer(inv->{assertSame(value,QwenExecutionScope.current());assertEquals(2,value.budget().remaining());assertTrue(value.budget().reserve(2));assertFalse(value.budget().reserve(1));return ((PageExecutionRecord)inv.getArgument(1)).withSettled("SUCCEEDED","done",2);});
        try(var scheduler=new PageWorkScheduler(engine,1,1)) {
            assertEquals("SUCCEEDED",scheduler.schedule(request,PageWorkScheduler.Priority.P0).get(3,TimeUnit.SECONDS).lifecycle());
            assertNull(QwenExecutionScope.current());verify(engine,times(1)).settle(any(),any());
        }
    }
    @Test void cancelledFutureAndOwnershipRemainUntilPhysicalWorkerExits()throws Exception {
        var engine=mock(PageProcessingService.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(engine.executeBaseline(any(),any())).thenAnswer(inv->{entered.countDown();while(release.getCount()>0)try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException ignore){}return ((PageExecutionRecord)inv.getArgument(1)).withSettled("CANCELLED","cancelled",null);});
        try(var scheduler=new PageWorkScheduler(engine,1,1)) {
            var request=request("book",1);var result=scheduler.schedule(request,PageWorkScheduler.Priority.P0);assertTrue(entered.await(3,TimeUnit.SECONDS));
            scheduler.cancel("book",1);assertFalse(result.isDone());assertEquals(1,scheduler.getActiveWorkCount());
            assertThrows(ExecutionException.class,()->scheduler.schedule(request("book",1),PageWorkScheduler.Priority.P0).get(1,TimeUnit.SECONDS));
            release.countDown();assertThrows(ExecutionException.class,()->result.get(3,TimeUnit.SECONDS));assertEquals(0,scheduler.getActiveWorkCount());
            verify(engine,times(1)).settle(any(),any());
        } finally {release.countDown();}
    }
    @Test void closedSchedulerRejectsAdmissionWithoutDanglingFuture() {
        var engine=mock(PageProcessingService.class);var scheduler=new PageWorkScheduler(engine,1,1);scheduler.close();
        assertThrows(ExecutionException.class,()->scheduler.schedule(request("book",1),PageWorkScheduler.Priority.P0).get(1,TimeUnit.SECONDS));
        assertEquals(0,scheduler.getActiveWorkCount());
    }
}
