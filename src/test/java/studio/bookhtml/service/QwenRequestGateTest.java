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
    @Test void refreshKeepsExistingOwnersAndDuplicateConcurrentCloseIsHarmless() throws Exception {
        QwenAssistProperties properties = config(3, 2, 0);
        QwenRequestGate gate = new QwenRequestGate(properties);
        var first = gate.acquire(true, Duration.ZERO);
        var second = gate.acquire(false, Duration.ZERO);
        var third = gate.acquire(false, Duration.ZERO);
        assertNotNull(first); assertNotNull(second); assertNotNull(third);
        gate.refresh();
        assertNull(gate.acquire(true, Duration.ZERO), "refresh must not mint extra capacity");
        properties.setMaxConcurrentRequests(1);
        gate.refresh();
        first.close();
        second.close();
        assertEquals(1, gate.inFlight());
        assertNull(gate.acquire(true, Duration.ZERO), "lowering capacity drains old owners");
        var start = new CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await(); third.close(); return null;
            }));
            start.countDown();
            for (var future : futures) future.get(3, TimeUnit.SECONDS);
            assertEquals(0, gate.inFlight());
            assertEquals(0, gate.backgroundInFlight());
            var only = gate.acquire(true, Duration.ZERO);
            assertNotNull(only);
            assertNull(gate.acquire(true, Duration.ZERO));
            only.close();
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void concurrentBackgroundAdmissionsDoNotCrossTheirCap() throws Exception {
        var gate = new QwenRequestGate(config(3, 2, 0));
        var occupied = gate.acquire(false, Duration.ZERO);
        var start = new CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(12);
        var winners = new CopyOnWriteArrayList<QwenRequestGate.Permit>();
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> {
                start.await();
                var permit = gate.acquire(false, Duration.ZERO);
                if (permit != null) winners.add(permit);
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(3, TimeUnit.SECONDS);
            assertEquals(1, winners.size());
            assertEquals(2, gate.backgroundInFlight());
            var foreground = gate.acquire(true, Duration.ZERO);
            assertNotNull(foreground);
            foreground.close();
        } finally {
            start.countDown(); winners.forEach(QwenRequestGate.Permit::close);
            occupied.close(); pool.shutdownNow();
        }
    }

}
