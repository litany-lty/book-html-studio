package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.store.BookStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * J04（T40）：小预算下并发预留原子、不透支、拒绝发生在外呼前。
 */
class BudgetConcurrencyTest {
    @Test void concurrentReservationsNeverOverdraw() throws Exception {
        Path temp = Files.createTempDirectory("budget-test");
        AppProperties config = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "m", "u", 5,
                "", "m", "u", 5, true);
        BookStore store = new BookStore(config, new ObjectMapper().findAndRegisterModules());
        DecisionStore decisions = new DecisionStore(store, new ObjectMapper().findAndRegisterModules());
        DecisionBudget budget = new DecisionBudget(decisions);
        String bookId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
        store.createBookDirectory(bookId);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try { assertTrue(go.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (budget.tryReserve(bookId, 1, 2L)) granted.incrementAndGet();
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, granted.get());
        assertEquals(2, budget.reservedMinor(bookId));
        // 未知费用保留预留，不记 0
        budget.settleUnknown(bookId);
        assertEquals(2, budget.reservedMinor(bookId));
        // 真实结算按合同扣减
        budget.settleReported(bookId, 1, 1);
        assertEquals(1, budget.reservedMinor(bookId));
        assertEquals(1, budget.reportedMinor(bookId));
        // 未发送取消释放
        budget.release(bookId, 1);
        assertEquals(0, budget.reservedMinor(bookId));
    }
}
