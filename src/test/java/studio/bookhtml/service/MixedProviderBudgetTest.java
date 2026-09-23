package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.store.BookStore;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MixedProviderBudgetTest {
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
    void enhancementBudgetEnforcesStrictCapOfEightPhysicalCallsPerAttempt() throws Exception {
        // CONC-08: Structure + Review + Toc + Retry cannot exceed 8 physical calls on the same attempt
        String attemptId = "attempt-strict-8";
        AtomicInteger transportCalls = new AtomicInteger();

        ManagedTransport transport = (req, deadline, maxBytes, cancelled) -> {
            transportCalls.incrementAndGet();
            return new BoundedHttp.Response(200, "{\"output\":{\"choices\":[]}}".getBytes());
        };

        for (int i = 1; i <= 8; i++) {
            PhysicalCallCommand cmd = PhysicalCallCommand.builder()
                    .kind(PhysicalCallCommand.ExecutionKind.ASSIST_LAYOUT)
                    .provider("qwen")
                    .accountScope("acc-1")
                    .budgetRootId(attemptId)
                    .logicalCallId("logical-op-" + i)
                    .purpose("assist-step-" + i)
                    .deadlineNanos(System.nanoTime() + TimeUnit.SECONDS.toNanos(10))
                    .build();

            CallOutcome outcome = physicalCallService.execute(
                    cmd,
                    () -> HttpRequest.newBuilder(URI.create("https://example.com")).build(),
                    transport,
                    () -> false
            );

            assertInstanceOf(CallOutcome.Succeeded.class, outcome, "第 " + i + " 次物理调用在预算内执行成功");
        }

        assertEquals(8, transportCalls.get());
        assertEquals(0, budgets.remaining(attemptId, AttemptCallBudgetStore.DEFAULT_ENHANCEMENT_BUDGET));

        // 9th call must be blocked due to exhausted budget
        PhysicalCallCommand cmd9 = PhysicalCallCommand.builder()
                .kind(PhysicalCallCommand.ExecutionKind.ASSIST_LAYOUT)
                .provider("qwen")
                .accountScope("acc-1")
                .budgetRootId(attemptId)
                .logicalCallId("logical-op-9")
                .purpose("assist-step-9")
                .deadlineNanos(System.nanoTime() + TimeUnit.SECONDS.toNanos(10))
                .build();

        CallOutcome outcome9 = physicalCallService.execute(
                cmd9,
                () -> HttpRequest.newBuilder(URI.create("https://example.com")).build(),
                transport,
                () -> false
        );

        assertInstanceOf(CallOutcome.NotSent.class, outcome9);
        assertTrue(((CallOutcome.NotSent) outcome9).reason().contains("预算不足"));
        assertEquals(8, transportCalls.get(), "第 9 次调用被预算硬门禁阻断，物理发送次数严格停留在 8");
    }

    @Test
    void failedOrUnknownCallsNeverRefundBudgetWhileUnsentCallsDo() throws Exception {
        // CONC-09: Unsent calls refund reservation; sent failed/unknown calls DO NOT refund
        String attemptId = "attempt-refund-semantics";

        // 1. Unsent call: fails before transport (e.g. deadline expired)
        PhysicalCallCommand unsentCmd = PhysicalCallCommand.builder()
                .kind(PhysicalCallCommand.ExecutionKind.ASSIST_LAYOUT)
                .provider("qwen")
                .accountScope("acc-1")
                .budgetRootId(attemptId)
                .deadlineNanos(System.nanoTime() - 1000) // already expired
                .build();

        CallOutcome unsentOutcome = physicalCallService.execute(
                unsentCmd,
                () -> HttpRequest.newBuilder(URI.create("https://example.com")).build(),
                (req, dl, max, can) -> new BoundedHttp.Response(200, new byte[0]),
                () -> false
        );

        assertInstanceOf(CallOutcome.NotSent.class, unsentOutcome);
        // Reservation was refunded
        assertEquals(8, budgets.remaining(attemptId, 8), "未实际发送的请求预算完全退还");

        // 2. Sent call that fails with HTTP 500
        PhysicalCallCommand failedCmd = PhysicalCallCommand.builder()
                .kind(PhysicalCallCommand.ExecutionKind.ASSIST_LAYOUT)
                .provider("qwen")
                .accountScope("acc-1")
                .budgetRootId(attemptId)
                .deadlineNanos(System.nanoTime() + TimeUnit.SECONDS.toNanos(10))
                .build();

        CallOutcome failedOutcome = physicalCallService.execute(
                failedCmd,
                () -> HttpRequest.newBuilder(URI.create("https://example.com")).build(),
                (req, dl, max, can) -> new BoundedHttp.Response(500, "internal error".getBytes()),
                () -> false
        );

        assertInstanceOf(CallOutcome.Failed.class, failedOutcome);
        // Budget is consumed and NOT refunded
        assertEquals(7, budgets.remaining(attemptId, 8), "已发送的失败调用绝不退还物理预算");
    }
}
