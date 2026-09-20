package studio.bookhtml.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;

import static org.junit.jupiter.api.Assertions.*;

class RenderBudgetTest {
    @Test void acquireAndReleaseRestoresPermits() throws Exception {
        RenderBudget budget = new RenderBudget(2, 64L * 1024 * 1024, 1000, () -> 0, () -> 1024L * 1024 * 1024);
        try (RenderBudget.Lease first = budget.acquire(1024, () -> false)) {
            assertEquals(1, budget.availablePermits());
            try (RenderBudget.Lease second = budget.acquire(1024, () -> false)) {
                assertEquals(0, budget.availablePermits());
            }
            assertEquals(1, budget.availablePermits());
        }
        assertEquals(2, budget.availablePermits());
        assertEquals(0, budget.inFlightBytes());
    }

    @Test void contentionTimesOutInsteadOfOom() throws Exception {
        RenderBudget budget = new RenderBudget(1, 64L * 1024 * 1024, 300, () -> 0, () -> 1024L * 1024 * 1024);
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (RenderBudget.Lease ignored = budget.acquire(1024, () -> false)) {
                holderReady.countDown();
                assertTrue(releaseHolder.await(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        holder.start();
        assertTrue(holderReady.await(2, TimeUnit.SECONDS));
        try {
            ApiException busy = assertThrows(ApiException.class, () -> budget.acquire(1024, () -> false));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, busy.status());
        } finally {
            releaseHolder.countDown();
            holder.join(3000);
        }
        // 异常路径归还配额，后续可继续申请
        try (RenderBudget.Lease again = budget.acquire(1024, () -> false)) {
            assertNotNull(again);
        }
    }

    @Test void cancelledWaitThrowsWithoutConsumingPermit() {
        RenderBudget budget = new RenderBudget(1, 64L * 1024 * 1024, 5000, () -> 0, () -> 1024L * 1024 * 1024);
        assertThrows(CancelledException.class, () -> budget.acquire(1024, () -> true));
        assertEquals(1, budget.availablePermits());
    }

    @Test void heapPressureRefusesInsteadOfOom() {
        // 已用堆 900M/上限 1000M：新申请 200M 将超过 85% 水位，应等待后明确拒绝
        RenderBudget budget = new RenderBudget(2, 64L * 1024 * 1024, 300,
                () -> 900L * 1024 * 1024, () -> 1000L * 1024 * 1024);
        ApiException busy = assertThrows(ApiException.class, () -> budget.acquire(200L * 1024 * 1024, () -> false));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, busy.status());
        assertEquals(2, budget.availablePermits());
    }

    @Test void concurrentAcquirersShareFairly() throws Exception {
        int threads = 4;
        RenderBudget budget = new RenderBudget(2, 256L * 1024 * 1024, 5000, () -> 0, () -> 8L * 1024 * 1024 * 1024);
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger live = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    try (RenderBudget.Lease ignored = budget.acquire(1024, () -> false)) {
                        peak.accumulateAndGet(live.incrementAndGet(), Math::max);
                        Thread.sleep(50);
                        live.decrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(peak.get() <= 2, "并发不得超过许可数");
        assertEquals(2, budget.availablePermits());
    }
}
