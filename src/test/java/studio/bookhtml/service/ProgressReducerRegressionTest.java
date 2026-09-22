package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.PageAttempt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProgressReducerRegressionTest {
    @Test void newerAttemptWinsEvenWhenOlderAttemptHasMoreEventsOrArrivesLate() {
        var service = new ProcessingProgressService();
        var first = PageAttempt.register("book", 1, 2, "source", List.of("JOB_BASELINE"));
        service.begin(first, 2, true);
        for (int i = 0; i < 50; i++) service.stage("book", 1, first.attemptId(), "stage-" + i);
        var second = first.nextGeneration(3, "new-source", List.of("JOB_BASELINE"));
        service.begin(second, 3, true);
        service.stage("book", 1, first.attemptId(), "late-event");
        service.finish("book", 1, first.attemptId(), "FAILED", "OLD_FAILURE", true);
        assertEquals(second.attemptId(), service.latest("book", 1).attemptId());
        assertEquals(2, service.latest("book", 1).attemptSeq());
        assertEquals(0, service.latest("book", 1).snapshotVersion());
        assertEquals(3, second.expectedRevision());
        assertEquals("new-source", second.expectedSourceHash());
        var lateStart = new ProcessingProgressService();
        lateStart.begin(second, 3, true);
        lateStart.begin(first, 2, true);
        assertEquals(second.attemptId(), lateStart.latest("book", 1).attemptId());
    }

    @Test void concurrentUniqueUnitsAreCountedOnceAndTerminalStateCannotRevive() throws Exception {
        var service = new ProcessingProgressService();
        var attempt = service.begin("book", 2, 0);
        service.plan("book", 2, attempt, "REVIEW_CHUNK", 100);
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 100; i++) {
                final String unit = "chunk-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    service.unitDone("book", 2, attempt, unit, "SUCCEEDED");
                    service.unitDone("book", 2, attempt, unit, "SUCCEEDED");
                    var snapshot = service.snapshot("book", 2, attempt);
                    assertTrue(snapshot.units().succeeded() <= 100);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            var complete = service.snapshot("book", 2, attempt);
            assertEquals(100, complete.units().succeeded());
            assertEquals(101, complete.snapshotVersion(), "one plan + exactly 100 unique completions");
            assertThrows(IllegalStateException.class, () -> service.plan("book", 2, attempt, "REVIEW_CHUNK", 101));
            service.finish("book", 2, attempt, "SUCCEEDED", "DONE", false);
            var terminal = service.snapshot("book", 2, attempt);
            service.stage("book", 2, attempt, "OCR");
            service.inFlight("book", 2, attempt, 100);
            service.unitDone("book", 2, attempt, "late", "FAILED");
            service.finish("book", 2, attempt, "RUNNING", "LATE", true);
            service.baselinePublished("book", 2, attempt, 99, true);
            assertEquals(terminal, service.snapshot("book", 2, attempt));
            assertEquals(terminal, service.latest("book", 2), "read-only polling never advances progress");
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
