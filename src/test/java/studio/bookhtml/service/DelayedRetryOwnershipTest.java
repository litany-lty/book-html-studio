package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DelayedRetryOwnershipTest {
    @TempDir Path dataDir;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BookStore store;
    private ProviderResourceRegistry resources;
    private AttemptCallBudgetStore budgets;
    private DelayedCallQueue delayedQueue;
    private PhysicalCallService physicalCallService;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties app = TestConfigs.config(dataDir, "", "");
        store = new BookStore(app, json);
        resources = new ProviderResourceRegistry();
        budgets = new AttemptCallBudgetStore();
        delayedQueue = new DelayedCallQueue();
        physicalCallService = new PhysicalCallService(resources, budgets, delayedQueue, json);
    }

    @AfterEach
    void tearDown() {
        if (physicalCallService != null) physicalCallService.close();
        if (store != null) store.close();
    }

    @Test
    void retryAfterParsingHandlesIntegersDatesAndMalformedValuesConservatively() {
        assertEquals(15, DelayedCallQueue.parseRetryAfter("15"));
        assertEquals(Integer.MAX_VALUE, DelayedCallQueue.parseRetryAfter("99999"), "超出本地等待预算时延期，不能提前重试");
        assertEquals(5, DelayedCallQueue.parseRetryAfter("-10"), "负值保守使用 5 秒默认值");
        assertEquals(5, DelayedCallQueue.parseRetryAfter("invalid-date-string"), "非格式化字符串默认 5 秒");
        assertEquals(5, DelayedCallQueue.parseRetryAfter(null));
        assertEquals(5, DelayedCallQueue.parseRetryAfter("   "));

        // RFC 1123 HTTP-date in future
        Instant future = Instant.now().plusSeconds(42);
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(future.atZone(java.time.ZoneOffset.UTC));
        int parsed = DelayedCallQueue.parseRetryAfter(httpDate);
        assertTrue(parsed >= 40 && parsed <= 44, "规范 HTTP 日期解析准确");
    }

    @Test
    void rateLimit429ReleasesPhysicalPermitImmediatelyAllowingOtherRequests() throws Exception {
        // CONC-07: 429 returns RetryEligible, permit released immediately
        PhysicalCallCommand cmd1 = PhysicalCallCommand.builder()
                .provider("qwen")
                .accountScope("acc-1")
                .budgetRootId("attempt-429")
                .logicalCallId("op-429-1")
                .deadlineNanos(System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
                .build();

        ManagedTransport transport429 = (req, deadline, maxBytes, cancelled) ->
                new BoundedHttp.Response(429, "rate limited".getBytes());

        CallOutcome outcome = physicalCallService.execute(
                cmd1,
                () -> java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://example.com")).build(),
                transport429,
                () -> false
        );

        assertInstanceOf(CallOutcome.RetryEligible.class, outcome);
        CallOutcome.RetryEligible retry = (CallOutcome.RetryEligible) outcome;
        assertEquals("op-429-1", retry.logicalCallId());

        // Assert that physical permit is ALREADY released
        assertEquals(0, resources.inFlight(ProviderResourceRegistry.POOL_QWEN),
                "429 进入退避时，物理许可已释放");

        // Another request can immediately acquire permit without blocking
        ProviderResourceRegistry.Permit anotherPermit = resources.acquire(
                ProviderResourceRegistry.POOL_QWEN, true, Duration.ofSeconds(1), () -> false);
        assertNotNull(anotherPermit, "其他请求可立即获得并发槽位执行");
        anotherPermit.close();
    }

    @Test
    void expiredDeadlineRejectsDelayedScheduling() {
        long pastDeadline = System.nanoTime() - TimeUnit.SECONDS.toNanos(5);
        DelayedCallQueue.DelayedCallDescriptor expiredDesc = new DelayedCallQueue.DelayedCallDescriptor(
                "op-expired", "budget-1", Instant.now().plusSeconds(2), pastDeadline, 2);

        AtomicBoolean ran = new AtomicBoolean();
        boolean scheduled = delayedQueue.schedule(expiredDesc, () -> ran.set(true));

        assertFalse(scheduled, "已超过总期限的描述符禁止排入延迟重试");
        assertFalse(ran.get());
    }

    @Test
    void scheduledDelayedCallFiresWhenEligible() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        long futureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        DelayedCallQueue.DelayedCallDescriptor desc = new DelayedCallQueue.DelayedCallDescriptor(
                "op-delayed", "budget-1", Instant.now().plusMillis(100), futureDeadline, 1);

        boolean scheduled = delayedQueue.schedule(desc, latch::countDown);
        assertTrue(scheduled);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "到达准入时间后回调被正确触发");
    }
}
