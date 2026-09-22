package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U5：共享闸门。6 页同时有辅助任务时实际在途 ≤3；结构/目录/核对共用同一上限。
 */
class QwenRequestGateTest {

    private static QwenAssistProperties config(int maxConcurrent, int maxBackground, int maxQueued) {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setMaxConcurrentRequests(maxConcurrent);
        config.setMaxBackgroundRequests(maxBackground);
        config.setMaxQueuedChunks(maxQueued);
        return config;
    }

    @Test void qw01_globalCapSharedAcrossPages() throws Exception {
        QwenRequestGate gate = new QwenRequestGate(config(3, 2, 24));
        int threads = 6;
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try (QwenRequestGate.Permit permit = gate.acquire(true, Duration.ofSeconds(5))) {
                    assertNotNull(permit, "前台在 5s 内应取得槽位");
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "3 个槽位应被占满");
        Thread.sleep(200);
        assertTrue(gate.maxObservedInFlight() <= 3, "实际在途不得超过全局上限");
        assertEquals(3, gate.inFlight());
        release.countDown();
        assertTrue(done.await(8, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "无异常：" + errors);
        assertEquals(0, gate.inFlight(), "响应流收尾后释放");
    }

    @Test void qw03_foregroundReservedWhileBackgroundWaits() throws Exception {
        QwenRequestGate gate = new QwenRequestGate(config(3, 2, 24));
        List<QwenRequestGate.Permit> held = new ArrayList<>();
        held.add(gate.acquire(false, Duration.ofSeconds(2)));
        held.add(gate.acquire(false, Duration.ofSeconds(2)));
        assertNotNull(held.get(0));
        assertNotNull(held.get(1));
        // 后台已占 2（上限），第 3 个后台请求不得进入保留槽。
        assertNull(gate.acquire(false, Duration.ofMillis(300)), "后台不挤占前台保留槽");
        // 前台仍可取得第 3 个槽。
        QwenRequestGate.Permit foreground = gate.acquire(true, Duration.ofSeconds(2));
        assertNotNull(foreground, "前台优先取得可用槽，不强杀计费中请求");
        foreground.close();
        for (QwenRequestGate.Permit permit : held) permit.close();
        assertEquals(0, gate.inFlight());
    }

    @Test void qw_queueBoundedAndBudgetReserved() throws Exception {
        QwenAssistProperties properties = config(1, 0, 0);
        QwenRequestGate gate = new QwenRequestGate(properties);
        QwenRequestGate.Permit held = gate.acquire(true, Duration.ofSeconds(2));
        assertNotNull(held);
        // 队列 0 + 并发 1 已满：再取超时返回 null，不无限排队。
        assertNull(gate.acquire(true, Duration.ofMillis(200)));
        held.close();
        QwenRequestGate.Budget budget = gate.newBudget();
        assertTrue(budget.reserve(7));
        assertFalse(budget.reserve(2), "预算不足明确部分增强，不偷偷追加");
        budget.release(7);
        assertEquals(8, budget.remaining());
    }

    @Test void qw_permitsReleasedExactlyOnce() {
        QwenRequestGate gate = new QwenRequestGate(config(3, 2, 24));
        AtomicInteger errors = new AtomicInteger();
        try {
            QwenRequestGate.Permit permit;
            try {
                permit = gate.acquire(true, Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            assertNotNull(permit);
            permit.close();
            permit.close();
        } catch (Throwable e) {
            errors.incrementAndGet();
        }
        assertEquals(0, errors.get());
        assertEquals(0, gate.inFlight());
    }
    @Test void refreshKeepsPhysicalOccupancyAndNegativeBudgetCannotMintCalls() throws Exception {
        QwenAssistProperties properties = config(3, 2, 24);
        QwenRequestGate gate = new QwenRequestGate(properties);
        var first = gate.acquire(true, Duration.ofSeconds(1));
        var second = gate.acquire(true, Duration.ofSeconds(1));
        var third = gate.acquire(true, Duration.ofSeconds(1));
        properties.setMaxConcurrentRequests(1); gate.refresh();
        assertEquals(3, gate.inFlight());
        assertNull(gate.acquire(true, Duration.ofMillis(20)));
        first.close(); second.close();
        assertNull(gate.acquire(true, Duration.ofMillis(20)));
        third.close();
        try (var next = gate.acquire(true, Duration.ofSeconds(1))) { assertNotNull(next); }
        var budget = gate.newBudget();
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.release(-1));
        budget.release(Integer.MAX_VALUE);
        assertEquals(8, budget.remaining());
    }

}
