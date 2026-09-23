package studio.bookhtml.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Process-wide concurrency and resource registry for AI model physical calls (B05 / G03).
 * Enforces:
 * - Qwen process concurrency <= 3 total, background <= 2
 * - Main OCR concurrency <= 3 total, background <= 2
 * - Remote jobs concurrency <= 3 total
 * - Waiter queue capacity <= 24, reserving >= 1 slot for foreground work
 * - Safe reconfiguration draining without permit generation leakage (CONC-01..CONC-04)
 */
@Component
public class ProviderResourceRegistry {
    public static final String POOL_QWEN = "qwen";
    public static final String POOL_OCR = "ocr";
    public static final String POOL_MINIMAX = "minimax";
    public static final String POOL_REMOTE_JOBS = "remote-jobs";

    private final Map<String, PoolState> pools = new ConcurrentHashMap<>();

    public ProviderResourceRegistry() {
        // Standard initial caps per specification
        pools.put(POOL_QWEN, new PoolState(POOL_QWEN, 3, 2, 24));
        pools.put(POOL_OCR, new PoolState(POOL_OCR, 3, 2, 24));
        pools.put(POOL_MINIMAX, new PoolState(POOL_MINIMAX, 3, 2, 24));
        pools.put(POOL_REMOTE_JOBS, new PoolState(POOL_REMOTE_JOBS, 3, 2, 24));
    }

    public static String canonicalCategory(String provider) {
        if (provider == null) return POOL_OCR;
        String lower = provider.toLowerCase(Locale.ROOT);
        if (lower.contains("qwen") || lower.contains("dashscope")) return POOL_QWEN;
        if (lower.contains("minimax")) return POOL_MINIMAX;
        if (lower.contains("paddle") || lower.contains("ppocr") || lower.contains("baidu")) return POOL_OCR;
        return lower;
    }

    public PoolState pool(String category) {
        String key = canonicalCategory(category);
        return pools.computeIfAbsent(key, k -> new PoolState(k, 3, 2, 24));
    }

    public Permit acquire(String category, boolean foreground, Duration timeout, BooleanSupplier cancelled)
            throws InterruptedException {
        return pool(category).acquire(foreground, timeout, cancelled);
    }

    public Permit acquireRemoteJob(Duration timeout, BooleanSupplier cancelled) throws InterruptedException {
        return pool(POOL_REMOTE_JOBS).acquire(true, timeout, cancelled);
    }

    public void configure(String category, int maxConcurrent, int maxBackground, int maxQueued) {
        pool(category).reconfigure(maxConcurrent, maxBackground, maxQueued);
    }

    public int inFlight(String category) {
        return pool(category).inFlight();
    }

    public int backgroundInFlight(String category) {
        return pool(category).backgroundInFlight();
    }

    public int queued(String category) {
        return pool(category).queued();
    }

    public int remoteJobsInFlight() {
        return pool(POOL_REMOTE_JOBS).inFlight();
    }

    public static final class PoolState {
        private final String name;
        private final Object lock = new Object();
        private final Deque<Waiter> waiting = new ArrayDeque<>();
        private int maxConcurrent;
        private int maxBackground;
        private int maxQueued;
        private int activeTotal;
        private int activeBackground;
        private int maxObservedInFlight;
        private long admittedCalls;

        private record Waiter(boolean foreground) {}

        PoolState(String name, int maxConcurrent, int maxBackground, int maxQueued) {
            this.name = name;
            this.maxConcurrent = Math.max(1, maxConcurrent);
            this.maxBackground = Math.max(0, Math.min(maxBackground, this.maxConcurrent - 1));
            this.maxQueued = Math.max(0, maxQueued);
        }

        public void reconfigure(int maxConcurrent, int maxBackground, int maxQueued) {
            synchronized (lock) {
                this.maxConcurrent = Math.max(1, maxConcurrent);
                this.maxBackground = Math.max(0, Math.min(maxBackground, this.maxConcurrent - 1));
                this.maxQueued = Math.max(0, maxQueued);
                lock.notifyAll();
            }
        }

        public Permit acquire(boolean foreground, Duration timeout, BooleanSupplier cancelled)
                throws InterruptedException {
            if (Thread.interrupted()) throw new InterruptedException();
            Duration wait = timeout == null ? Duration.ofSeconds(30) : timeout;
            if (wait.isNegative()) throw new IllegalArgumentException("negative timeout");
            long nanos = wait.compareTo(Duration.ofDays(1)) > 0 ? TimeUnit.DAYS.toNanos(1) : wait.toNanos();

            synchronized (lock) {
                if (cancelled != null && cancelled.getAsBoolean()) throw new CancelledException();
                if (waiting.isEmpty() && available(foreground)) {
                    return admit(foreground);
                }
                // Foreground work has priority and reserved slot in queue
                int queueLimit = foreground ? maxQueued : Math.max(0, maxQueued - 1);
                if (nanos == 0 || waiting.size() >= queueLimit) {
                    return null;
                }

                Waiter entry = new Waiter(foreground);
                waiting.addLast(entry);
                long deadline = System.nanoTime() + nanos;
                try {
                    while (true) {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            throw new CancelledException();
                        }
                        if (available(foreground) && nextEligible() == entry) {
                            waiting.removeIf(w -> w == entry);
                            return admit(foreground);
                        }
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) return null;
                        TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                    }
                } finally {
                    waiting.removeIf(w -> w == entry);
                    lock.notifyAll();
                }
            }
        }

        private boolean available(boolean foreground) {
            return activeTotal < maxConcurrent && (foreground || activeBackground < maxBackground);
        }

        private Waiter nextEligible() {
            for (Waiter candidate : waiting) {
                if (candidate.foreground()) return candidate;
            }
            return waiting.peekFirst();
        }

        private Permit admit(boolean foreground) {
            activeTotal++;
            if (!foreground) activeBackground++;
            maxObservedInFlight = Math.max(maxObservedInFlight, activeTotal);
            admittedCalls++;
            return new Permit(this, foreground);
        }

        void release(boolean foreground) {
            synchronized (lock) {
                activeTotal--;
                if (!foreground) activeBackground--;
                lock.notifyAll();
            }
        }

        public int inFlight() { synchronized (lock) { return activeTotal; } }
        public int backgroundInFlight() { synchronized (lock) { return activeBackground; } }
        public int queued() { synchronized (lock) { return waiting.size(); } }
        public int maxObservedInFlight() { synchronized (lock) { return maxObservedInFlight; } }
        public long admittedCalls() { synchronized (lock) { return admittedCalls; } }
        public int maxConcurrent() { synchronized (lock) { return maxConcurrent; } }
        public int maxBackground() { synchronized (lock) { return maxBackground; } }
    }

    public static final class Permit implements AutoCloseable {
        private final PoolState pool;
        private final boolean foreground;
        private final AtomicBoolean closed = new AtomicBoolean();

        Permit(PoolState pool, boolean foreground) {
            this.pool = Objects.requireNonNull(pool);
            this.foreground = foreground;
        }

        public boolean foreground() { return foreground; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                pool.release(foreground);
            }
        }
    }
}
