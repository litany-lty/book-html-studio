package studio.bookhtml.service;

import org.springframework.stereotype.Component;
import studio.bookhtml.config.QwenAssistProperties;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * U5：Qwen 共享出站闸门。所有页面、所有 Qwen 辅助种类（结构、局部核对、
 * 目录恢复）共用同一全局上限，不是每页 N 个；旧可达路径无旁路。
 *
 * <p>前台优先但不强杀：后台请求只在空闲额度超过保留量时进入；前台用满全部额度。
 * 429 退避不持槽 sleep（调用方释放后重取）。物理 permit 只在请求与响应流实际
 * 收尾后释放；逻辑取消不等于物理连接结束。
 */
@Component
public class QwenRequestGate {
    private final QwenAssistProperties config;
    private ReadingPriorityService priority;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setPriority(ReadingPriorityService priority) { this.priority = priority; }
    private final Object lock = new Object();
    private int backgroundInFlight, foregroundWaiting;
    private volatile int maxConcurrent;
    private volatile int maxBackground;
    private volatile int maxQueued;

    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxObservedInFlight = new AtomicInteger();
    private final AtomicLong physicalCalls = new AtomicLong();

    public QwenRequestGate(QwenAssistProperties config) {
        this.config = config;
        refresh();
    }

    /** Refresh limits without replacing accounting for still-running physical requests. */
    public void refresh() {
        synchronized (lock) {
            maxConcurrent = Math.max(1, config.getMaxConcurrentRequests());
            maxBackground = Math.max(0, Math.min(config.getMaxBackgroundRequests(), maxConcurrent - 1));
            maxQueued = Math.max(0, config.getMaxQueuedChunks());
            lock.notifyAll();
        }
    }

    /** 每页面尝试的物理调用预算（结构 + 局部组 + 目录恢复 + 重试共享）。 */
    public Budget newBudget() {
        return new Budget(Math.max(1, config.getMaxPhysicalCallsPerPageAttempt()));
    }

    /**
     * 获取执行槽。foreground=true 可用全部额度；background 仅当在途数低于
     * 后台上限时进入（为当前阅读页保留 1 个）。排队元素只计数，不持有图片。
     *
     * @return permit（用完必须 close），超时或队列满返回 null
     */
    public Permit acquire(boolean foreground, Duration timeout) throws InterruptedException {
        foreground = priority == null ? foreground : priority.foreground(foreground);
        long nanos = timeout == null ? TimeUnit.SECONDS.toNanos(30) : timeout.toNanos();
        long start = System.nanoTime();
        synchronized (lock) {
            // Immediate admission does not use a queue entry. Queue capacity is a hard bound.
            if (available(foreground)) return admit(foreground);
            if (nanos <= 0 || queued.get() >= maxQueued) return null;
            queued.incrementAndGet();
            if (foreground) foregroundWaiting++;
            try {
                while (!available(foreground)) {
                    long left = nanos - (System.nanoTime() - start);
                    if (left <= 0) return null;
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                }
                return admit(foreground);
            } finally {
                queued.decrementAndGet();
                if (foreground) foregroundWaiting--;
                lock.notifyAll();
            }
        }
    }

    private boolean available(boolean foreground) {
        return inFlight.get() < maxConcurrent && (foreground
                || (foregroundWaiting == 0 && backgroundInFlight < maxBackground
                    && inFlight.get() < maxConcurrent - 1));
    }

    private Permit admit(boolean foreground) {
        int now = inFlight.incrementAndGet();
        if (!foreground) backgroundInFlight++;
        maxObservedInFlight.accumulateAndGet(now, Math::max);
        physicalCalls.incrementAndGet();
        return new Permit(false, !foreground);
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int maxObservedInFlight() {
        return maxObservedInFlight.get();
    }

    public long physicalCalls() {
        return physicalCalls.get();
    }

    public int maxQueued() { return Math.max(1, maxQueued); }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    /** 物理 permit：只在请求与响应流实际收尾后释放。 */
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final boolean noop, background;
        private Permit(boolean noop, boolean background) {
            this.noop = noop; this.background = background;
        }
        @Override public void close() {
            if (noop || !closed.compareAndSet(false, true)) return;
            synchronized (lock) {
                inFlight.decrementAndGet();
                if (background) backgroundInFlight--;
                lock.notifyAll();
            }
        }
    }

    public Permit noopPermit() { return new Permit(true, false); }

    /** 调用预算：出站前原子预留；未发出的队列取消可释放，已发出不伪装免费撤销。 */
    public static final class Budget {
        private final AtomicInteger remaining;
        private final int total;

        Budget(int total) {
            this.total = Math.max(0, total);
            this.remaining = new AtomicInteger(this.total);
        }

        /** 预留 n 次；不足返回 false（调用方明确部分增强，不偷偷追加）。 */
        public boolean reserve(int n) {
            if (n <= 0) throw new IllegalArgumentException("positive reservation required");
            while (true) {
                int current = remaining.get();
                if (current < n) return false;
                if (remaining.compareAndSet(current, current - n)) return true;
            }
        }

        public void release(int n) {
            if (n <= 0) throw new IllegalArgumentException("positive refund required");
            remaining.updateAndGet(value -> (int) Math.min(total, (long) value + n));
        }

        public int remaining() {
            return remaining.get();
        }
    }
}
