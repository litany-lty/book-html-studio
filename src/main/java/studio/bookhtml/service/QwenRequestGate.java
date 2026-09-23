package studio.bookhtml.service;

import org.springframework.stereotype.Component;
import studio.bookhtml.config.QwenAssistProperties;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One process-wide admission state for Qwen requests. Reconfiguration never replaces
 * the ownership of live permits. Only the physical request owner's finally closes it.
 * Waiting descriptors contain no page text or images; one queue slot is reserved for
 * foreground work, which has priority over optional background work.
 */
@Component
public class QwenRequestGate {
    private final QwenAssistProperties config;
    private final Deque<Waiter> waiting = new ArrayDeque<>();
    private int maxConcurrent;
    private int maxBackground;
    private int maxQueued;
    private int activeTotal;
    private int activeBackground;
    private int maxObservedInFlight;
    private long admittedCalls;

    private record Waiter(boolean foreground) {}

    public QwenRequestGate(QwenAssistProperties config) {
        this.config = config;
        refresh();
    }

    /** Lower limits drain naturally; raising limits still counts all existing owners. */
    public synchronized void refresh() {
        maxConcurrent = Math.max(1, Math.min(3, config.getMaxConcurrentRequests()));
        maxBackground = Math.max(0, Math.min(config.getMaxBackgroundRequests(), maxConcurrent - 1));
        maxQueued = Math.max(0, Math.min(24, config.getMaxQueuedChunks()));
        notifyAll();
    }

    public Budget newBudget() {
        return new Budget(Math.max(1, Math.min(8, config.getMaxPhysicalCallsPerPageAttempt())));
    }

    public synchronized Permit acquire(boolean foreground, Duration timeout) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        Duration wait = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (wait.isNegative()) throw new IllegalArgumentException("negative timeout");
        long nanos = wait.compareTo(Duration.ofDays(1)) > 0 ? TimeUnit.DAYS.toNanos(1) : wait.toNanos();
        if (waiting.isEmpty() && available(foreground)) return admit(foreground);
        // Optional background descriptors cannot consume the last foreground queue slot.
        int queueLimit = foreground ? maxQueued : Math.max(0, maxQueued - 1);
        if (nanos == 0 || waiting.size() >= queueLimit) return null;
        Waiter entry = new Waiter(foreground);
        // Waiter identity, not record equality, matters when several callers share priority.
        waiting.addLast(entry);
        long deadline = System.nanoTime() + nanos;
        try {
            while (true) {
                if (available(foreground) && nextEligible() == entry) {
                    removeIdentity(entry);
                    return admit(foreground);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        } finally {
            removeIdentity(entry);
            notifyAll();
        }
    }

    private boolean available(boolean foreground) {
        return activeTotal < maxConcurrent && (foreground || activeBackground < maxBackground);
    }

    private Waiter nextEligible() {
        for (Waiter candidate : waiting) if (candidate.foreground()) return candidate;
        return waiting.peekFirst();
    }

    private void removeIdentity(Waiter entry) {
        waiting.removeIf(candidate -> candidate == entry);
    }

    private Permit admit(boolean foreground) {
        activeTotal++;
        if (!foreground) activeBackground++;
        maxObservedInFlight = Math.max(maxObservedInFlight, activeTotal);
        admittedCalls++;
        return new Permit(foreground, false);
    }

    public synchronized int inFlight() { return activeTotal; }
    public synchronized int backgroundInFlight() { return activeBackground; }
    public synchronized int queued() { return waiting.size(); }
    public synchronized int maxObservedInFlight() { return maxObservedInFlight; }
    /** Compatibility metric: admitted permits, not a substitute for the send ledger. */
    public synchronized long physicalCalls() { return admittedCalls; }
    public synchronized int maxConcurrent() { return maxConcurrent; }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final boolean foreground;
        private final boolean noop;

        private Permit(boolean foreground, boolean noop) {
            this.foreground = foreground;
            this.noop = noop;
        }

        @Override public void close() {
            if (noop || !closed.compareAndSet(false, true)) return;
            synchronized (QwenRequestGate.this) {
                activeTotal--;
                if (!foreground) activeBackground--;
                QwenRequestGate.this.notifyAll();
            }
        }
    }

    /** Compatibility helper for isolated tests; production clients must use acquire. */
    public Permit noopPermit() { return new Permit(true, true); }

    public static final class Budget {
        private final AtomicInteger remaining;
        private final int total;
        private Long deadlineNanos;
        /** One monotonic deadline for the whole shared execution, never reset by retries. */
        public synchronized long deadline(int timeoutSeconds) {
            long now = System.nanoTime();
            long candidate = now + TimeUnit.SECONDS.toNanos(Math.max(1,Math.min(600,timeoutSeconds)));
            if (deadlineNanos == null || candidate - deadlineNanos < 0) deadlineNanos = candidate;
            return deadlineNanos;
        }
        public int limit() { return total; }
        Budget(int total) {
            if (total < 1) throw new IllegalArgumentException("invalid call budget");
            this.total = total;
            remaining = new AtomicInteger(total);
        }
        public boolean reserve(int n) {
            if (n < 1) throw new IllegalArgumentException("reservation must be positive");
            while (true) {
                int current = remaining.get();
                if (current < n) return false;
                if (remaining.compareAndSet(current, current - n)) return true;
            }
        }
        /** Only a reservation proven never sent can be returned by its owner. */
        public void release(int n) {
            if (n < 1) throw new IllegalArgumentException("refund must be positive");
            while (true) {
                int current = remaining.get();
                if (n > total - current) throw new IllegalStateException("call budget over-refund");
                if (remaining.compareAndSet(current, current + n)) return;
            }
        }
        public int remaining() { return remaining.get(); }
        /** A single owner may refund only before attempting a physical send. */
        public Reservation claim() { return reserve(1) ? new Reservation(this) : null; }
        public static final class Reservation implements AutoCloseable {
            private final Budget budget;
            private boolean sent, closed;
            private Reservation(Budget budget) { this.budget = budget; }
            public synchronized void markSent() {
                if (closed || sent) throw new IllegalStateException("reservation already settled");
                sent = true;
            }
            @Override public synchronized void close() {
                if (closed) return;
                closed = true;
                if (!sent) budget.release(1);
            }
        }
    }
}
