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
        java.util.concurrent.ConcurrentLinkedQueue<String> grantedIds = new java.util.concurrent.ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try { assertTrue(go.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        String id = budget.reserve(bookId, "test", 1, 2L);
                        if (id != null) grantedIds.add(id);
                    } catch (Exception ignored) {}
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, grantedIds.size());
        assertEquals(2, budget.reservedMinor(bookId));
        String first = grantedIds.poll();
        String second = grantedIds.poll();
        // 未知费用保留预留，不记 0
        budget.retainUnknown(bookId, first);
        assertEquals(2, budget.reservedMinor(bookId));
        // 真实结算按合同扣减
        budget.settleReported(bookId, first, 1);
        assertEquals(1, budget.reservedMinor(bookId));
        assertEquals(1, budget.reportedMinor(bookId));
        // 未发送取消释放
        budget.releaseNotSent(bookId, second);
        assertEquals(0, budget.reservedMinor(bookId));
    }

    @Test void sentUnknownBlocksSecondReserveAndReportedPlusReservedEnforced() throws Exception {
        // JR-05-T01/T03：已发送超时保留预留，limit=1 下第二次被拒；
        // reported=1/reserved=0/limit=1 时新预留被拒；溢出不穿透
        Path temp = Files.createTempDirectory("budget-jr05");
        AppProperties config = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "m", "u", 5,
                "", "m", "u", 5, true);
        BookStore store = new BookStore(config, new ObjectMapper().findAndRegisterModules());
        DecisionStore decisions = new DecisionStore(store, new ObjectMapper().findAndRegisterModules());
        DecisionBudget budget = new DecisionBudget(decisions);
        String bookId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
        store.createBookDirectory(bookId);
        String first = budget.reserve(bookId, "jev-decision", 1, 1L);
        assertNotNull(first);
        budget.markSendIntent(bookId, first);
        // 超时/失败：保留 UNKNOWN
        budget.retainUnknown(bookId, first);
        // 第二次预留必须被拒（已实报 0 + 未结算 1 >= limit 1）
        assertNull(budget.reserve(bookId, "jev-decision", 1, 1L));
        // 结算后 reported=1/reserved=0，limit=1 仍拒绝新预留
        budget.settleReported(bookId, first, 1);
        assertNull(budget.reserve(bookId, "jev-decision", 1, 1L));
        // 极大整数不溢出穿透
        assertNull(budget.reserve(bookId, "jev-decision", Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test void corruptedLedgerBlocksNewCalls() throws Exception {
        // JR-05-T04：账本损坏禁止新外呼，不初始化成 0
        Path temp = Files.createTempDirectory("budget-corrupt");
        AppProperties config = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "m", "u", 5,
                "", "m", "u", 5, true);
        BookStore store = new BookStore(config, new ObjectMapper().findAndRegisterModules());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DecisionStore decisions = new DecisionStore(store, mapper);
        DecisionBudget budget = new DecisionBudget(decisions);
        String bookId = "ffffffff-ffff-ffff-ffff-fffffffffffe";
        store.createBookDirectory(bookId);
        String id = budget.reserve(bookId, "vision-crop", 1, 100L);
        assertNotNull(id);
        // 损坏账本文件
        Path attemptFile = decisions.decisionsDir(bookId).resolve("attempts")
                .resolve(id + ".json");
        Files.writeString(attemptFile, "{corrupted");
        assertThrows(DecisionStore.BudgetUnavailableException.class,
                () -> decisions.budgetTotals(bookId));
        // JR-05-T04：损坏后新预留必须失败（抛 BUDGET_UNAVAILABLE），绝不初始化成 0 继续跑
        assertThrows(DecisionStore.BudgetUnavailableException.class,
                () -> budget.reserve(bookId, "vision-crop", 1, 100L));
    }
}
