package studio.bookhtml.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-blocking delayed execution queue for 429 rate limit backoff (B05 / G03 / CONC-07).
 * Ensures:
 * - Worker threads and HTTP connections/permits are released immediately upon 429
 * - Lightweight descriptors only (no images or secrets in memory during backoff)
 * - Monotonic total execution deadline is preserved and never reset by retries
 */
@Component
public class DelayedCallQueue implements AutoCloseable {
    public static final int DEFAULT_BACKOFF_SECONDS = 5;
    public static final int MAX_BACKOFF_SECONDS = 300;

    public record DelayedCallDescriptor(
            String logicalCallId,
            String budgetRootId,
            Instant nextEligibleAt,
            long originalDeadlineNanos,
            int retryAfterSeconds
    ) {
        public boolean isExpired() {
            return System.nanoTime() >= originalDeadlineNanos;
        }
    }

    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DelayedCallQueue() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "delayed-call-queue");
            t.setDaemon(true);
            return t;
        });
    }

    public DelayedCallQueue(ScheduledExecutorService scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public static int parseRetryAfter(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return DEFAULT_BACKOFF_SECONDS;
        }
        String stripped = headerValue.strip();
        try {
            int seconds = Integer.parseInt(stripped);
            if (seconds <= 0) return DEFAULT_BACKOFF_SECONDS;
            return Math.min(seconds, MAX_BACKOFF_SECONDS);
        } catch (NumberFormatException notNumber) {
            try {
                TemporalAccessor accessor = DateTimeFormatter.RFC_1123_DATE_TIME.parse(stripped);
                Instant target = Instant.from(accessor);
                long diffSeconds = Duration.between(Instant.now(), target).toSeconds();
                if (diffSeconds <= 0) return DEFAULT_BACKOFF_SECONDS;
                return (int) Math.min(diffSeconds, MAX_BACKOFF_SECONDS);
            } catch (Exception notDate) {
                return DEFAULT_BACKOFF_SECONDS;
            }
        }
    }

    public boolean schedule(DelayedCallDescriptor descriptor, Runnable onReady) {
        if (closed.get() || descriptor == null || descriptor.isExpired()) {
            return false;
        }
        long delayMillis = Math.max(50, Duration.between(Instant.now(), descriptor.nextEligibleAt()).toMillis());
        long remainingNanos = descriptor.originalDeadlineNanos() - System.nanoTime();
        if (TimeUnit.MILLISECONDS.toNanos(delayMillis) >= remainingNanos) {
            return false; // Deadline will expire before retry becomes eligible
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            scheduledTasks.remove(descriptor.logicalCallId());
            if (!closed.get() && !descriptor.isExpired()) {
                onReady.run();
            }
        }, delayMillis, TimeUnit.MILLISECONDS);

        scheduledTasks.put(descriptor.logicalCallId(), future);
        return true;
    }

    public void cancel(String logicalCallId) {
        ScheduledFuture<?> future = scheduledTasks.remove(logicalCallId);
        if (future != null) {
            future.cancel(true);
        }
    }

    public int activeDelayedCount() {
        return scheduledTasks.size();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (ScheduledFuture<?> future : scheduledTasks.values()) {
                future.cancel(true);
            }
            scheduledTasks.clear();
            scheduler.shutdownNow();
        }
    }
}
