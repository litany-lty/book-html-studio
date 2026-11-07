package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationDelayedCallQueueTest {
    private static DelayedCallQueue.DelayedCallDescriptor descriptor(String id) {
        return new DelayedCallQueue.DelayedCallDescriptor(id, "attempt", Instant.now().plusMillis(100),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10), 1);
    }

    @Test void duplicateLogicalCallKeepsOneOwnerAndCancellationStopsIt() {
        var timer = new ManualTimer(false);
        var calls = new AtomicInteger();
        try (var queue = new DelayedCallQueue(timer)) {
            assertTrue(queue.schedule(descriptor("same"), calls::incrementAndGet));
            assertFalse(queue.schedule(descriptor("same"), calls::incrementAndGet));
            assertEquals(1, queue.activeDelayedCount());
            queue.cancel("same");
            timer.runPending();
            assertEquals(0, calls.get());
            assertEquals(0, queue.activeDelayedCount());
        }
    }

    @Test void callbackBeforeScheduleReturnsDoesNotLeaveCompletedOwner() {
        var timer = new ManualTimer(true);
        var calls = new AtomicInteger();
        try (var queue = new DelayedCallQueue(timer)) {
            assertTrue(queue.schedule(descriptor("fast"), calls::incrementAndGet));
            assertEquals(1, calls.get());
            assertEquals(0, queue.activeDelayedCount());
            assertTrue(queue.schedule(descriptor("fast"), calls::incrementAndGet));
            assertEquals(2, calls.get());
            assertEquals(0, queue.activeDelayedCount());
        }
    }

    @Test void rejectedTimerLeavesNoOwnerAndReturnsAdmissionFailure() {
        var timer = new ManualTimer(false);
        timer.shutdownNow();
        try (var queue = new DelayedCallQueue(timer)) {
            assertFalse(queue.schedule(descriptor("closed"), () -> fail("must not run")));
            assertEquals(0, queue.activeDelayedCount());
        }
    }

    @Test void callbackMayScheduleNextRetryWithTheSameLogicalId() {
        var timer = new ManualTimer(false);
        var calls = new AtomicInteger();
        try (var queue = new DelayedCallQueue(timer)) {
            assertTrue(queue.schedule(descriptor("retry"), () -> {
                calls.incrementAndGet();
                assertTrue(queue.schedule(descriptor("retry"), calls::incrementAndGet));
            }));
            timer.runPending();
            assertEquals(2, calls.get());
            assertEquals(0, queue.activeDelayedCount());
        }
    }

    private static final class ManualTimer extends ScheduledThreadPoolExecutor {
        private final Queue<ManualFuture> pending = new ArrayDeque<>();
        private final boolean immediate;
        ManualTimer(boolean immediate) { super(1); this.immediate = immediate; }
        @Override public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
            if (isShutdown()) throw new RejectedExecutionException("timer closed");
            var future = new ManualFuture(action);
            if (immediate) future.run(); else pending.add(future);
            return future;
        }
        void runPending() { while (!pending.isEmpty()) pending.remove().run(); }
    }

    private static final class ManualFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
        ManualFuture(Runnable action) { super(action, null); }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
