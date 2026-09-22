package studio.bookhtml.service;

import org.springframework.stereotype.Component;
import studio.bookhtml.config.QwenAssistProperties;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One process-wide bounded gate; refresh never replaces permits held by live requests. */
@Component
public class QwenRequestGate {
    private final QwenAssistProperties config;
    private int maxConcurrent, maxBackground, maxQueued;
    private int queued, foregroundWaiting, inFlight, backgroundInFlight, maxObservedInFlight;
    private long physicalCalls;

    public QwenRequestGate(QwenAssistProperties config) {
        this.config = config;
        refresh();
    }

    public synchronized void refresh() {
        maxConcurrent = Math.max(1, Math.min(32, config.getMaxConcurrentRequests()));
        maxBackground = Math.max(0, Math.min(config.getMaxBackgroundRequests(), maxConcurrent - 1));
        maxQueued = Math.max(0, Math.min(1024, config.getMaxQueuedChunks()));
        notifyAll(); // Reducing limits drains existing work; it does not mint new permits.
    }

    public Budget newBudget() {
        return new Budget(Math.max(1, config.getMaxPhysicalCallsPerPageAttempt()));
    }

    private boolean available(boolean foreground) {
        return inFlight < maxConcurrent && (foreground
                || (backgroundInFlight < maxBackground && foregroundWaiting == 0));
    }

    public synchronized Permit acquire(boolean foreground, Duration timeout) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        if (!available(foreground) && queued >= maxQueued) return null;
        long nanos = timeout == null ? TimeUnit.SECONDS.toNanos(30) : Math.max(1, timeout.toNanos());
        long deadline = System.nanoTime() + Math.min(nanos, TimeUnit.MINUTES.toNanos(10));
        queued++;
        if (foreground) foregroundWaiting++;
        try {
            while (!available(foreground)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            inFlight++;
            if (!foreground) backgroundInFlight++;
            maxObservedInFlight = Math.max(maxObservedInFlight, inFlight);
            physicalCalls++;
            return new Permit(!foreground, false);
        } finally {
            queued--;
            if (foreground) foregroundWaiting--;
            notifyAll();
        }
    }

    public synchronized int inFlight() { return inFlight; }
    public synchronized int maxObservedInFlight() { return maxObservedInFlight; }
    public synchronized long physicalCalls() { return physicalCalls; }
    public synchronized int maxConcurrent() { return maxConcurrent; }
    public synchronized int maxQueued() { return maxQueued; }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final boolean background, noop;
        private Permit(boolean background, boolean noop) { this.background = background; this.noop = noop; }
        @Override public void close() {
            if (noop || !closed.compareAndSet(false, true)) return;
            synchronized (QwenRequestGate.this) {
                inFlight--;
                if (background) backgroundInFlight--;
                QwenRequestGate.this.notifyAll();
            }
        }
    }

    public Permit noopPermit() { return new Permit(false, true); }

    /** Reserve EACH physical outbound attempt, including 429 retries. Never refund a sent request. */
    public static final class Budget {
        private final AtomicInteger remaining;
        private final int total;
        Budget(int total) {
            if (total <= 0) throw new IllegalArgumentException("non-positive budget");
            this.total = total;
            this.remaining = new AtomicInteger(total);
        }
        public boolean reserve(int count) {
            if (count <= 0) throw new IllegalArgumentException("non-positive reservation");
            while (true) {
                int current = remaining.get();
                if (current < count) return false;
                if (remaining.compareAndSet(current, current - count)) return true;
            }
        }
        public void release(int count) {
            if (count <= 0) throw new IllegalArgumentException("non-positive release");
            remaining.updateAndGet(current -> {
                if ((long) current + count > total) throw new IllegalStateException("budget over-release");
                return current + count;
            });
        }
        public int remaining() { return remaining.get(); }
    }
}
