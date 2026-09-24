package studio.bookhtml.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.PageAttempt;
import studio.bookhtml.domain.PageExecutionRecord;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CrossEntrySchedulerTest {

    private PageProcessingService pageEngine;
    private PageWorkScheduler scheduler;
    private final String bookId = "book-cross-scheduler-test";

    @BeforeEach
    void setUp() {
        pageEngine = mock(PageProcessingService.class);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private PageProcessingService.Request createRequest(String book, int page) {
        PageAttempt attempt = PageAttempt.register(book, page, 0, "hash", List.of("PUBLISH"));
        return new PageProcessingService.Request(attempt, "paddle-aistudio", "auto", false, false, false, () -> false, () -> true);
    }

    @Test
    void testPriorityOrderingAndPromotionWithoutDuplicates() throws Exception {
        CountDownLatch pause = new CountDownLatch(1);
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());

        when(pageEngine.executeBaseline(any(), any())).thenAnswer(inv -> {
            pause.await(5, TimeUnit.SECONDS);
            PageProcessingService.Request req = inv.getArgument(0);
            executionOrder.add(req.page());
            PageExecutionRecord record = inv.getArgument(1);
            return record.withSettled("SUCCEEDED", "OK", 1);
        });
        when(pageEngine.settle(any(), any())).thenReturn(new PageProcessingService.Result("SUCCEEDED", "OK", 1));

        // Use 1 worker thread to ensure strict serial dispatch ordering
        scheduler = new PageWorkScheduler(pageEngine, 1, 1);

        // Submit initial dummy item to hold the single worker thread
        PageProcessingService.Request blockerReq = createRequest(bookId, 999);
        CompletableFuture<PageProcessingService.Result> blockerFuture = scheduler.schedule(blockerReq, PageWorkScheduler.Priority.P0);

        // Give worker thread a moment to pick up blockerReq
        Thread.sleep(50);

        // Submit P3 (page 1), P2 (page 2), P1 (page 3)
        PageProcessingService.Request firstRequest=createRequest(bookId,1);
        CompletableFuture<PageProcessingService.Result> f1 = scheduler.schedule(firstRequest, PageWorkScheduler.Priority.P3);
        CompletableFuture<PageProcessingService.Result> f2 = scheduler.schedule(createRequest(bookId, 2), PageWorkScheduler.Priority.P2);
        CompletableFuture<PageProcessingService.Result> f3 = scheduler.schedule(createRequest(bookId, 3), PageWorkScheduler.Priority.P1);

        // Promote page 1 to P0: dynamic priority promotion
        boolean promoted = scheduler.promotePriority(bookId, 1, PageWorkScheduler.Priority.P0);
        assertTrue(promoted);

        // Scheduling page 1 again with P0 should return the same future without duplicate queue entries
        CompletableFuture<PageProcessingService.Result> f1Again = scheduler.schedule(firstRequest, PageWorkScheduler.Priority.P0);
        assertSame(f1, f1Again);

        // Release blocker
        pause.countDown();

        CompletableFuture.allOf(blockerFuture, f1, f2, f3).get(5, TimeUnit.SECONDS);

        // Expected execution order: 999 (blocker), then 1 (promoted to P0), then 3 (P1), then 2 (P2)
        assertEquals(List.of(999, 1, 3, 2), executionOrder);
    }

    @Test
    void testStarvationPreventionRuleOf8() throws Exception {
        CountDownLatch pause = new CountDownLatch(1);
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());

        when(pageEngine.executeBaseline(any(), any())).thenAnswer(inv -> {
            pause.await(5, TimeUnit.SECONDS);
            PageProcessingService.Request req = inv.getArgument(0);
            executionOrder.add(req.page());
            PageExecutionRecord record = inv.getArgument(1);
            return record.withSettled("SUCCEEDED", "OK", 1);
        });
        when(pageEngine.settle(any(), any())).thenReturn(new PageProcessingService.Result("SUCCEEDED", "OK", 1));

        scheduler = new PageWorkScheduler(pageEngine, 1, 1);

        // Blocker submitted as P3 so consecutiveForeground starts at 0
        PageProcessingService.Request blockerReq = createRequest(bookId, 999);
        CompletableFuture<PageProcessingService.Result> blockerFuture = scheduler.schedule(blockerReq, PageWorkScheduler.Priority.P3);
        Thread.sleep(50);

        // Submit 1 P3 task (page 100)
        CompletableFuture<PageProcessingService.Result> fP3 = scheduler.schedule(createRequest(bookId, 100), PageWorkScheduler.Priority.P3);

        // Submit 10 P1 tasks (pages 1..10)
        List<CompletableFuture<PageProcessingService.Result>> foregroundFutures = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            foregroundFutures.add(scheduler.schedule(createRequest(bookId, i), PageWorkScheduler.Priority.P1));
        }

        // Release blocker
        pause.countDown();

        blockerFuture.get(5, TimeUnit.SECONDS);
        fP3.get(5, TimeUnit.SECONDS);
        CompletableFuture.allOf(foregroundFutures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);

        // Execution order after blocker (index 0 is 999):
        // 8 foreground tasks (pages 1..8) -> indices 1..8
        // 9th task MUST be page 100 (P3 background task) -> index 9
        // 10th and 11th tasks are remaining foreground tasks (pages 9, 10)
        assertEquals(999, executionOrder.get(0));
        for (int i = 1; i <= 8; i++) {
            assertEquals(i, executionOrder.get(i));
        }
        assertEquals(100, executionOrder.get(9), "Starvation prevention should schedule P3 after 8 consecutive foreground tasks");
        assertEquals(9, executionOrder.get(10));
        assertEquals(10, executionOrder.get(11));
    }

    @Test
    void testP0PreemptsStarvationRule() throws Exception {
        CountDownLatch pause = new CountDownLatch(1);
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());

        when(pageEngine.executeBaseline(any(), any())).thenAnswer(inv -> {
            pause.await(5, TimeUnit.SECONDS);
            PageProcessingService.Request req = inv.getArgument(0);
            executionOrder.add(req.page());
            PageExecutionRecord record = inv.getArgument(1);
            return record.withSettled("SUCCEEDED", "OK", 1);
        });
        when(pageEngine.settle(any(), any())).thenReturn(new PageProcessingService.Result("SUCCEEDED", "OK", 1));

        scheduler = new PageWorkScheduler(pageEngine, 1, 1);

        // Blocker
        PageProcessingService.Request blockerReq = createRequest(bookId, 999);
        CompletableFuture<PageProcessingService.Result> blockerFuture = scheduler.schedule(blockerReq, PageWorkScheduler.Priority.P0);
        Thread.sleep(50);

        // Submit 1 P3 task
        CompletableFuture<PageProcessingService.Result> fP3 = scheduler.schedule(createRequest(bookId, 100), PageWorkScheduler.Priority.P3);

        // Submit 8 P1 tasks
        for (int i = 1; i <= 8; i++) {
            scheduler.schedule(createRequest(bookId, i), PageWorkScheduler.Priority.P1);
        }

        // Submit 1 P0 task (page 50)
        scheduler.schedule(createRequest(bookId, 50), PageWorkScheduler.Priority.P0);

        // Release blocker
        pause.countDown();

        blockerFuture.get(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        // P0 task must precede P3 even though 8 foreground tasks exist
        // First is blocker (999), second is P0 (50)
        assertEquals(999, executionOrder.get(0));
        assertEquals(50, executionOrder.get(1), "P0 must preempt all lower priorities regardless of queue state");
    }

    @Test
    void testCancellationAndBookCancellation() throws Exception {
        scheduler = new PageWorkScheduler(pageEngine, 1, 1);

        CountDownLatch pause = new CountDownLatch(1);
        when(pageEngine.executeBaseline(any(), any())).thenAnswer(inv -> {
            pause.await(5, TimeUnit.SECONDS);
            PageExecutionRecord record = inv.getArgument(1);
            return record.withSettled("SUCCEEDED", "OK", 1);
        });

        // Blocker
        scheduler.schedule(createRequest(bookId, 999), PageWorkScheduler.Priority.P0);
        Thread.sleep(50);

        // Enqueue items
        CompletableFuture<PageProcessingService.Result> f1 = scheduler.schedule(createRequest(bookId, 1), PageWorkScheduler.Priority.P2);
        CompletableFuture<PageProcessingService.Result> f2 = scheduler.schedule(createRequest(bookId, 2), PageWorkScheduler.Priority.P2);
        CompletableFuture<PageProcessingService.Result> fOther = scheduler.schedule(createRequest("other-book", 1), PageWorkScheduler.Priority.P2);

        // Cancel page 1
        scheduler.cancel(bookId, 1);
        assertTrue(f1.isCompletedExceptionally());
        assertThrows(ExecutionException.class, f1::get);

        // Cancel all remaining for bookId
        scheduler.cancelBook(bookId);
        assertTrue(f2.isCompletedExceptionally());

        // other-book should not be cancelled
        assertFalse(fOther.isDone());

        pause.countDown();
    }
}
