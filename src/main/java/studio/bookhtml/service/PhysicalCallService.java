package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Unified physical model call orchestrator (B05 / G03).
 * Enforces:
 * - Authoritative consent gate before sending
 * - Multi-provider account concurrency limits (ProviderResourceRegistry)
 * - Page attempt physical budgets (AttemptCallBudgetStore)
 * - Durable WAL tracking (UsageLedger) with exact state transitions
 * - Non-blocking delayed backoff (DelayedCallQueue) on 429
 * - Safe resource and stream disposal (BoundedHttp)
 */
@Service
public class PhysicalCallService implements AutoCloseable {
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    private final ProviderResourceRegistry resources;
    private final AttemptCallBudgetStore budgets;
    private final DelayedCallQueue delayedQueue;
    private final ObjectMapper json;
    private UsageLedger ledger;
    private CloudConsentService consentService;

    @Autowired
    public PhysicalCallService(ProviderResourceRegistry resources,
                               AttemptCallBudgetStore budgets,
                               DelayedCallQueue delayedQueue,
                               ObjectMapper json) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.delayedQueue = Objects.requireNonNull(delayedQueue, "delayedQueue");
        this.json = Objects.requireNonNull(json, "json");
    }

    private studio.bookhtml.config.OutboundDestinationPolicy outboundPolicy =
            new studio.bookhtml.config.OutboundDestinationPolicy();

    @Autowired(required = false)
    public void setOutboundPolicy(studio.bookhtml.config.OutboundDestinationPolicy outboundPolicy) {
        if (outboundPolicy != null) {
            this.outboundPolicy = outboundPolicy;
        }
    }

    public studio.bookhtml.config.OutboundDestinationPolicy outboundPolicy() {
        return outboundPolicy;
    }

    @Autowired(required = false)
    public void setUsageLedger(UsageLedger ledger) {
        this.ledger = ledger;
    }

    @Autowired(required = false)
    public void setCloudConsentService(CloudConsentService consentService) {
        this.consentService = consentService;
    }

    public ProviderResourceRegistry resources() { return resources; }
    public AttemptCallBudgetStore budgets() { return budgets; }
    public DelayedCallQueue delayedQueue() { return delayedQueue; }

    public CallOutcome execute(PhysicalCallCommand command, RequestFactory factory,
                               ManagedTransport transport, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(transport, "transport");

        // 1. Consent verification
        if (consentService != null && command.bookId() != null && !command.bookId().isBlank()) {
            boolean authorized = consentService.isCloudAuthorized(command.bookId(), command.provider());
            if (!authorized) {
                return new CallOutcome.NotSent("未获服务端持久云端授权: " + command.provider());
            }
        }

        // 2. Cancellation and monotonic deadline checks
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new CancelledException();
        }
        long now = System.nanoTime();
        long remainingNanos = command.deadlineNanos() - now;
        if (command.deadlineNanos() > 0 && remainingNanos <= 0) {
            return new CallOutcome.NotSent("调用执行期限已到，未发送请求");
        }

        // 3. Acquire concurrency permit from ProviderResourceRegistry
        Duration timeout = remainingNanos > 0 ? Duration.ofNanos(remainingNanos) : Duration.ofSeconds(30);
        ProviderResourceRegistry.Permit permit = resources.acquire(
                command.provider(), command.foreground(), timeout, cancelled);
        if (permit == null) {
            return new CallOutcome.NotSent("并发队列已满，保留原文");
        }

        // 4. Claim attempt call budget
        int maxBudget = command.kind() == PhysicalCallCommand.ExecutionKind.OCR
                ? AttemptCallBudgetStore.DEFAULT_OCR_BUDGET
                : AttemptCallBudgetStore.DEFAULT_ENHANCEMENT_BUDGET;
        AttemptCallBudgetStore.Reservation budgetReservation = budgets.claim(command.budgetRootId(), maxBudget);
        if (budgetReservation == null) {
            permit.close();
            return new CallOutcome.NotSent("页面增强调用预算不足，保留原文");
        }

        String ledgerId = null;
        if (ledger != null) {
            try {
                ledgerId = ledger.prepare(command.provider(), command.model());
            } catch (IOException e) {
                budgetReservation.close();
                permit.close();
                throw new OcrException("用量账本准备失败，未发送请求", e);
            }
        }

        boolean sent = false;
        try {
            // 5. Build HTTP request via RequestFactory
            if (cancelled != null && cancelled.getAsBoolean()) {
                throw new CancelledException();
            }
            long deadlineCheck = command.deadlineNanos() - System.nanoTime();
            if (command.deadlineNanos() > 0 && deadlineCheck <= 0) {
                return new CallOutcome.NotSent("构建请求前期限已到，未发送请求");
            }

            HttpRequest request = factory.buildRequest();
            if (request == null) {
                return new CallOutcome.NotSent("请求工厂未生成有效请求");
            }

            if (outboundPolicy != null && request.uri() != null) {
                studio.bookhtml.config.OutboundDestinationPolicy.ValidationResult check =
                        outboundPolicy.validate(request.uri());
                if (!check.isAllowed()) {
                    return new CallOutcome.NotSent("外发请求目的地址被策略阻断: " + check.reason());
                }
            }

            // 6. Record possibly-sent state in durable ledger before network transport
            if (ledger != null && ledgerId != null) {
                ledger.sending(ledgerId);
            }
            budgetReservation.markSent();
            sent = true;

            // 7. Execute transport with remaining monotonic deadline
            long transportRemaining = command.deadlineNanos() > 0
                    ? Math.max(1, command.deadlineNanos() - System.nanoTime())
                    : TimeUnit.SECONDS.toNanos(60);

            BoundedHttp.Response response = transport.send(request, transportRemaining, DEFAULT_MAX_RESPONSE_BYTES, cancelled);
            if (response == null) {
                if (ledger != null && ledgerId != null) ledger.unknown(ledgerId);
                return new CallOutcome.OutcomeUnknown("传输未返回响应");
            }

            int status = response.status();
            // Handle 429 rate limit backoff (CONC-07)
            if (status == 429) {
                if (ledger != null && ledgerId != null) {
                    try { ledger.failed(ledgerId); } catch (Exception ignored) {}
                }
                int retryAfter = DelayedCallQueue.DEFAULT_BACKOFF_SECONDS;
                Instant nextEligibleAt = Instant.now().plusSeconds(retryAfter);
                return new CallOutcome.RetryEligible(
                        nextEligibleAt, command.deadlineNanos(), command.logicalCallId(), retryAfter);
            }

            if (status >= 200 && status < 300) {
                if (ledger != null && ledgerId != null) {
                    try {
                        JsonNode root = json.readTree(response.body());
                        ledger.captureUsage(ledgerId, root);
                        ledger.succeeded(ledgerId);
                    } catch (Exception e) {
                        try { ledger.succeeded(ledgerId); } catch (Exception ignored) {}
                    }
                }
                return new CallOutcome.Succeeded(status, response.body(), ledgerId, Map.of());
            } else {
                if (ledger != null && ledgerId != null) {
                    try { ledger.failed(ledgerId); } catch (Exception ignored) {}
                }
                return new CallOutcome.Failed(status, "HTTP " + status, true);
            }
        } catch (CancelledException e) {
            if (ledger != null && ledgerId != null) {
                try {
                    if (sent) ledger.unknown(ledgerId);
                    else ledger.notSent(ledgerId);
                } catch (Exception ignored) {}
            }
            throw e;
        } catch (Exception e) {
            if (ledger != null && ledgerId != null) {
                try {
                    if (sent) ledger.unknown(ledgerId);
                    else ledger.notSent(ledgerId);
                } catch (Exception ignored) {}
            }
            if (sent) {
                return new CallOutcome.OutcomeUnknown(e.getMessage());
            } else {
                return new CallOutcome.NotSent(e.getMessage());
            }
        } finally {
            budgetReservation.close();
            permit.close();
        }
    }

    @Override
    public void close() {
        delayedQueue.close();
    }
}
