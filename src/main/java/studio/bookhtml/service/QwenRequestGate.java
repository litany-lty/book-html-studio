package studio.bookhtml.service;

import org.springframework.stereotype.Component;
import studio.bookhtml.config.QwenAssistProperties;

import java.time.Duration;
import java.util.concurrent.Semaphore;
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
    private final Object monitor = new Object();
    private int backgroundInFlight;
    private int foregroundWaiting;
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

    /** 配置变更通过版本化快照生效：更新准入上限，在途请求自然收尾，不重置信号量或凭空增加槽位。 */
    public void refresh() {
      synchronized (monitor) {
        this.maxConcurrent = Math.max(1, config.getMaxConcurrentRequests());
        this.maxBackground = Math.max(0, Math.min(config.getMaxBackgroundRequests(), maxConcurrent - 1));
        this.maxQueued = Math.max(0, config.getMaxQueuedChunks());
        monitor.notifyAll();
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
        long remaining = (timeout == null ? Duration.ofSeconds(30) : timeout).toNanos();
        if (remaining <= 0) return null;
        final long deadline = System.nanoTime() + remaining;
        synchronized (monitor) {
            if (queued.get() >= maxQueued + maxConcurrent) return null;
            queued.incrementAndGet();
            if (foreground) foregroundWaiting++;
            try {
                while (inFlight.get() >= maxConcurrent || (!foreground &&
                        (backgroundInFlight >= maxBackground || foregroundWaiting > 0))) {
                    if (remaining <= 0) return null;
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                    remaining = deadline - System.nanoTime();
                }
                if (!foreground) backgroundInFlight++;
                int now = inFlight.incrementAndGet();
                maxObservedInFlight.accumulateAndGet(now, Math::max);
                physicalCalls.incrementAndGet();
                return new Permit(false, foreground);
            } finally {
                queued.decrementAndGet();
                if (foreground) foregroundWaiting--;
                monitor.notifyAll();
            }
        }
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

    public int maxConcurrent() {
        return maxConcurrent;
    }

    /** 物理 permit：只在请求与响应流实际收尾后释放。 */
    public final class Permit implements AutoCloseable {
        private boolean closed;
        private final boolean noop;
        private final boolean foreground;
        private Permit(boolean noop, boolean foreground) {
            this.noop = noop;
            this.foreground = foreground;
        }
        @Override public void close() {
            synchronized (monitor) {
                if (closed || noop) return;
                closed = true;
                inFlight.decrementAndGet();
                if (!foreground) backgroundInFlight--;
                monitor.notifyAll();
            }
        }
    }

    public Permit noopPermit() { return new Permit(true, true); }

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
            if (n <= 0) throw new IllegalArgumentException("Positive reservation required");
            while (true) {
                int current = remaining.get();
                if (current < n) return false;
                if (remaining.compareAndSet(current, current - n)) return true;
            }
        }

        public void release(int n) {
            if (n <= 0) throw new IllegalArgumentException("Positive release required");
            remaining.updateAndGet(value -> (int) Math.min(total, (long) value + n));
        }

        public int remaining() {
            return remaining.get();
        }
    }
}
