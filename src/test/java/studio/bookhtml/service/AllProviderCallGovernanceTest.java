package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.CreateConsentRequest;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.config.PpOcrProperties;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.CloudConsent;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AllProviderCallGovernanceTest {
    @TempDir Path dataDir;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BookStore store;
    private CloudConsentService consentService;
    private ProviderResourceRegistry resources;
    private AttemptCallBudgetStore budgets;
    private DelayedCallQueue delayedQueue;
    private PhysicalCallService physicalCallService;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties app = TestConfigs.config(dataDir, "", "");
        store = new BookStore(app, json);
        consentService = new CloudConsentService(store.consentStore(), store.policyStore(), store.epochStore());
        resources = new ProviderResourceRegistry();
        budgets = new AttemptCallBudgetStore();
        delayedQueue = new DelayedCallQueue();
        physicalCallService = new PhysicalCallService(resources, budgets, delayedQueue, json);
        physicalCallService.setCloudConsentService(consentService);
    }

    @AfterEach
    void tearDown() {
        if (physicalCallService != null) physicalCallService.close();
        if (store != null) store.close();
    }

    @Test
    void unconsentedBookFailsClosedBeforeNetworkSend() throws Exception {
        String bookId = "unconsented-book-" + UUID.randomUUID();
        PhysicalCallCommand command = PhysicalCallCommand.builder()
                .bookId(bookId)
                .page(1)
                .provider("qwen")
                .accountScope("acc-1")
                .budgetRootId("attempt-1")
                .deadlineNanos(System.nanoTime() + TimeUnit.SECONDS.toNanos(10))
                .build();

        AtomicInteger transportInvocations = new AtomicInteger();
        ManagedTransport transport = (req, deadline, maxBytes, cancelled) -> {
            transportInvocations.incrementAndGet();
            return new BoundedHttp.Response(200, "{}".getBytes());
        };

        CallOutcome outcome = physicalCallService.execute(
                command,
                () -> java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://example.com")).build(),
                transport,
                () -> false
        );

        assertInstanceOf(CallOutcome.NotSent.class, outcome);
        assertTrue(((CallOutcome.NotSent) outcome).reason().contains("未获服务端持久云端授权"));
        assertEquals(0, transportInvocations.get(), "未获授权严禁发起物理外发");
    }

    @Test
    void qwenProcessConcurrencyNeverExceedsThreeTotalAndTwoBackground() throws Exception {
        // CONC-01 & CONC-02
        ProviderResourceRegistry.Permit p1 = resources.acquire(ProviderResourceRegistry.POOL_QWEN, false, Duration.ofSeconds(1), () -> false);
        assertNotNull(p1, "第一个后台槽获取成功");
        ProviderResourceRegistry.Permit p2 = resources.acquire(ProviderResourceRegistry.POOL_QWEN, false, Duration.ofSeconds(1), () -> false);
        assertNotNull(p2, "第二个后台槽获取成功 (已达后台上限 2)");

        // 3rd background request must fail/timeout because maxBackground is 2
        ProviderResourceRegistry.Permit p3Bg = resources.acquire(ProviderResourceRegistry.POOL_QWEN, false, Duration.ofMillis(100), () -> false);
        assertNull(p3Bg, "后台并发达 2 后，新后台请求必须被阻断");

        // Foreground request must succeed (total is 2, maxTotal is 3)
        ProviderResourceRegistry.Permit p3Fg = resources.acquire(ProviderResourceRegistry.POOL_QWEN, true, Duration.ofSeconds(1), () -> false);
        assertNotNull(p3Fg, "前台请求允许占用第 3 个总并发槽位");

        // 4th request (even foreground) must fail because total active is 3
        ProviderResourceRegistry.Permit p4 = resources.acquire(ProviderResourceRegistry.POOL_QWEN, true, Duration.ofMillis(100), () -> false);
        assertNull(p4, "总并发达 3 后，任何新请求均被阻断");

        p1.close();
        p2.close();
        p3Fg.close();

        assertEquals(0, resources.inFlight(ProviderResourceRegistry.POOL_QWEN));
        assertEquals(0, resources.backgroundInFlight(ProviderResourceRegistry.POOL_QWEN));
    }

    @Test
    void backgroundBarrierContentionAdmitsAtMostOneWinner() throws Exception {
        // CONC-02: Multiple background threads contending for the last background slot
        // Hold 1 background permit so only 1 remains
        ProviderResourceRegistry.Permit p1 = resources.acquire(ProviderResourceRegistry.POOL_QWEN, false, Duration.ofSeconds(1), () -> false);
        assertNotNull(p1);

        int contestants = 8;
        CyclicBarrier barrier = new CyclicBarrier(contestants);
        CountDownLatch done = new CountDownLatch(contestants);
        List<ProviderResourceRegistry.Permit> won = new CopyOnWriteArrayList<>();

        ExecutorService exec = Executors.newFixedThreadPool(contestants);
        for (int i = 0; i < contestants; i++) {
            exec.submit(() -> {
                try {
                    barrier.await();
                    ProviderResourceRegistry.Permit p = resources.acquire(ProviderResourceRegistry.POOL_QWEN, false, Duration.ofMillis(150), () -> false);
                    if (p != null) {
                        won.add(p);
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        exec.shutdownNow();

        assertEquals(1, won.size(), "越过 barrier 争抢最后一个后台槽，必须最多仅 1 个成功");

        p1.close();
        for (var p : won) p.close();
    }

    @Test
    void doubleCloseDoesNotLeakPermitsOrCorruptCounters() throws Exception {
        // CONC-03: Repeated close does not increase permits
        ProviderResourceRegistry.Permit p = resources.acquire(ProviderResourceRegistry.POOL_QWEN, true, Duration.ofSeconds(1), () -> false);
        assertNotNull(p);
        assertEquals(1, resources.inFlight(ProviderResourceRegistry.POOL_QWEN));

        p.close();
        assertEquals(0, resources.inFlight(ProviderResourceRegistry.POOL_QWEN));

        // Duplicate close
        p.close();
        p.close();
        assertEquals(0, resources.inFlight(ProviderResourceRegistry.POOL_QWEN), "重复 close 绝不增加许可");
    }
}
