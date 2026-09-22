package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.ProcessingSnapshot;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingProgressServiceTest {
    @Test void latestMeansNewAttemptNotMostEvents() {
        var service = new ProcessingProgressService();
        UUID old = service.begin("a", 1, 0);
        for (int i = 0; i < 100; i++) service.stage("a", 1, old, "REVIEW");
        service.finish("a", 1, old, "SUCCEEDED", "DONE", false);
        UUID fresh = service.begin("a", 1, 3);
        assertEquals(fresh, service.latest("a", 1).attemptId());
        assertEquals(0, service.latest("a", 1).percent());
    }
    @Test void chunkEventsAreAtomicAndBoundedAndTerminalCannotResurrect() throws Exception {
        var service = new ProcessingProgressService();
        UUID id = service.begin("book", 2, 0);
        service.stage("book", 2, id, "REVIEW"); service.plan("book", 2, id, "TEXT_GROUPS", 100);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 150; i++) tasks.add(pool.submit(() -> service.unitDone("book", 2, id, true)));
            for (Future<?> task : tasks) task.get(3, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(100, service.latest("book", 2).units().succeeded());
        assertEquals(85, service.latest("book", 2).percent());
        service.finish("book", 2, id, "FAILED", "WRITE_FAILED", true);
        service.stage("book", 2, id, "PUBLISHING");
        service.finish("book", 2, id, "SUCCEEDED", "LATE", false);
        assertEquals("FAILED", service.latest("book", 2).lifecycle());
        assertTrue(service.latest("book", 2).percent() < 100);
    }
    @Test void onlySuccessfulPublicationReachesOneHundred() {
        var service = new ProcessingProgressService(); UUID id = service.begin("book", 1, 2);
        service.stage("book", 1, id, "PUBLISHING"); assertEquals(95, service.latest("book", 1).percent());
        service.baselinePublished("book", 1, id, 3, true);
        service.finish("book", 1, id, "SUCCEEDED", "DONE", false);
        assertEquals(100, service.latest("book", 1).percent());
        assertEquals(3, service.latest("book", 1).publishedRevision());
    }
    @Test void telemetryRetentionBoundDoesNotChooseAnotherBook() {
        var service = new ProcessingProgressService();
        for (int n = 1; n < 600; n++) { UUID id = service.begin("a", n, 0); service.finish("a", n, id, "SUCCEEDED", "DONE", false); }
        UUID current = service.begin("b", 599, 0);
        assertEquals(current, service.latest("b", 599).attemptId());
        assertNull(service.latest("a", 1));
    }
}
