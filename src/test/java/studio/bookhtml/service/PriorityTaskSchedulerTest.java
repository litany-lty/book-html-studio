package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PriorityTaskSchedulerTest {
    private static void waitFor(CountDownLatch latch) {
        try { assertTrue(latch.await(5,TimeUnit.SECONDS)); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new CancelledException(); }
    }
    @Test void foregroundUsesReservedWorkerAndOvertakesQueuedBackground() throws Exception {
        var scheduler=new PriorityTaskScheduler(2,4);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var order=new CopyOnWriteArrayList<String>();
        try {
            var first=scheduler.submit(()->{entered.countDown();waitFor(release);order.add("first");return 1;},false);
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var background=scheduler.submit(()->{order.add("background");return 2;},false);
            var front=scheduler.submit(()->{order.add("front");return 3;},true);
            assertEquals(3,front.get(3,TimeUnit.SECONDS)); assertFalse(background.isDone());
            release.countDown();assertEquals(1,first.get(3,TimeUnit.SECONDS));assertEquals(2,background.get(3,TimeUnit.SECONDS));
            assertEquals(List.of("front","first","background"),order);
        } finally { release.countDown();scheduler.close(); }
    }
    @Test void closeTerminatesQueuedWorkButWaitsForPhysicalExitOfRunningWork() throws Exception {
        var scheduler=new PriorityTaskScheduler(1,4);
        var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var release=new CountDownLatch(1);
        var count=new AtomicInteger();
        var running=scheduler.submit(()-> {
            entered.countDown();
            try { new CountDownLatch(1).await(5,TimeUnit.SECONDS); }
            catch(InterruptedException expected) { count.incrementAndGet(); }
            interrupted.countDown();waitFor(release);return 1;
        },true);
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var queued=scheduler.submit(()->2,true);
            scheduler.close();assertTrue(interrupted.await(3,TimeUnit.SECONDS));scheduler.close();
            assertTrue(queued.isCompletedExceptionally());assertFalse(running.isDone());
            assertThrows(RejectedExecutionException.class,()->scheduler.submit(()->3,true));
            release.countDown();assertEquals(1,running.get(3,TimeUnit.SECONDS));assertEquals(1,count.get());
            assertEquals(0,scheduler.queued());
        } finally { release.countDown();scheduler.close(); }
    }
    @Test void queueBoundReservesOnePlaceForForeground() throws Exception {
        var scheduler=new PriorityTaskScheduler(2,2);
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        try {
            var one=scheduler.submit(()->{entered.countDown();waitFor(release);return 1;},true);
            var two=scheduler.submit(()->{entered.countDown();waitFor(release);return 2;},true);
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            scheduler.submit(()->1,false);
            assertThrows(RejectedExecutionException.class,()->scheduler.submit(()->2,false));
            var front=scheduler.submit(()->3,true);
            release.countDown();one.get(3,TimeUnit.SECONDS);two.get(3,TimeUnit.SECONDS);
            assertEquals(3,front.get(3,TimeUnit.SECONDS));
        } finally { release.countDown();scheduler.close(); }
    }
    @Test void coordinatorCannotReopenAfterClose() {
        var coordinator=new QwenAssistCoordinator();coordinator.close();
        assertThrows(RejectedExecutionException.class,()->coordinator.coordinate("book",1,List.of(),Map.of(),
                new QwenTaskPlanner.PlannedReview(List.of(),List.of(),0,0),Map.of(),null,"auto",true,()->false));
    }
    @Test void closingCoordinatorCancelsStructureAndNeverStartsQueuedImageFactories() throws Exception {
        var coordinator=new QwenAssistCoordinator();
        var structure=org.mockito.Mockito.mock(QwenLayoutClient.class);
        var review=org.mockito.Mockito.mock(QwenTextReviewClient.class);
        coordinator.setRequestGate(new QwenRequestGate(new studio.bookhtml.config.QwenAssistProperties()));
        coordinator.setStructureClient(structure);coordinator.setReviewClient(review);
        org.mockito.Mockito.when(structure.configured()).thenReturn(true);
        var entered=new CountDownLatch(1);var images=new AtomicInteger();
        org.mockito.Mockito.when(structure.structurePlan(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call->{entered.countDown();waitFor(new CountDownLatch(1));return List.of("x");});
        var chunk=new QwenTaskPlanner.ChunkTask("c","TEXT_REVIEW",List.of(new QwenTaskPlanner.OwnedRange("x",0,1)),List.of(),0,"p","v");
        var plan=new QwenTaskPlanner.PlannedReview(List.of(chunk),List.of(),0,2);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(()->coordinator.coordinateLazy("book",1,List.of(),Map.of("x","字"),plan,
                    key->{if(!"__overview__".equals(key))images.incrementAndGet();return new byte[]{1};},null,"auto",true,()->false));
            assertTrue(entered.await(3,TimeUnit.SECONDS));coordinator.close();
            ExecutionException stopped=assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));
            assertInstanceOf(CancelledException.class,stopped.getCause());assertEquals(0,images.get());
            org.mockito.Mockito.verifyNoInteractions(review);
        } finally { coordinator.close();executor.shutdownNow(); }
    }
    @Test void disabledBackgroundCapacityRejectsInsteadOfWaitingUntilDeadline() {
        var scheduler=new PriorityTaskScheduler(1,4);
        try { assertThrows(RejectedExecutionException.class,()->scheduler.submit(()->1,false)); }
        finally { scheduler.close(); }
    }
}
