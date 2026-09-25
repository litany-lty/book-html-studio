package studio.bookhtml.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks and enforces physical call budgets per page attempt (B05 / G03 / CONC-08).
 * Ensures:
 * - Enhancement calls (structure + review + toc + retry + split) never exceed attempt budget (default 8)
 * - Safe reservations: uncommitted reservations are refunded; sent calls are never refunded
 */
@Component
public class AttemptCallBudgetStore {
    public static final int DEFAULT_ENHANCEMENT_BUDGET = 8;
    public static final int DEFAULT_OCR_BUDGET = 5;

    private final Map<String, BudgetTracker> trackers = new ConcurrentHashMap<>();

    public BudgetTracker tracker(String budgetRootId, int maxBudget) {
        Objects.requireNonNull(budgetRootId, "budgetRootId");
        return trackers.computeIfAbsent(budgetRootId, id -> new BudgetTracker(id, maxBudget));
    }

    public Reservation claim(String budgetRootId, int maxBudget) {
        return tracker(budgetRootId, maxBudget).claim();
    }

    public int remaining(String budgetRootId, int maxBudget) {
        return tracker(budgetRootId, maxBudget).remaining();
    }

    public static final class BudgetTracker {
        private final String id;
        private final int totalLimit;
        private final AtomicInteger remaining;
        private final AtomicInteger totalSent = new AtomicInteger();

        public BudgetTracker(String id, int totalLimit) {
            this.id = id;
            this.totalLimit = Math.max(1, totalLimit);
            this.remaining = new AtomicInteger(this.totalLimit);
        }

        public String id() { return id; }
        public int totalLimit() { return totalLimit; }
        public int remaining() { return remaining.get(); }
        public int sentCount() { return totalSent.get(); }

        public Reservation claim() {
            while (true) {
                int cur = remaining.get();
                if (cur <= 0) return null;
                if (remaining.compareAndSet(cur, cur - 1)) {
                    return new Reservation(this);
                }
            }
        }

        void refund() {
            while (true) {
                int cur = remaining.get();
                if (cur >= totalLimit) return;
                if (remaining.compareAndSet(cur, cur + 1)) return;
            }
        }

        void recordSent() {
            totalSent.incrementAndGet();
        }
    }

    public static final class Reservation implements PhysicalCallSession.Reservation {
        private final BudgetTracker tracker;
        private final AtomicBoolean sent = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        Reservation(BudgetTracker tracker) {
            this.tracker = Objects.requireNonNull(tracker);
        }

        public synchronized void markSent() {
            if (closed.get()) throw new IllegalStateException("reservation already closed");
            if (sent.compareAndSet(false, true)) {
                tracker.recordSent();
            }
        }

        public boolean isSent() {
            return sent.get();
        }

        @Override
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                if (!sent.get()) {
                    tracker.refund();
                }
            }
        }
    }
}
