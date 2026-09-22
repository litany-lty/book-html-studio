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
    private volatile Semaphore global;
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

    /** 配置变更通过版本化快照生效：重建信号量（在途 permit 保留在旧实例上自然收尾）。 */
    public synchronized void refresh() {
        this.maxConcurrent = Math.max(1, config.getMaxConcurrentRequests());
        this.maxBackground = Math.max(0, Math.min(config.getMaxBackgroundRequests(), maxConcurrent - 1));
        this.maxQueued = Math.max(0, config.getMaxQueuedChunks());
        this.global = new Semaphore(maxConcurrent, true);
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
        if (queued.incrementAndGet() > maxQueued + maxConcurrent) {
            queued.decrementAndGet();
            return null;
        }
        boolean taken = false;
        try {
            if (!foreground && inFlight.get() >= maxBackground) return null;
            long millis = timeout == null ? 30_000 : Math.max(1, timeout.toMillis());
            taken = global.tryAcquire(millis, TimeUnit.MILLISECONDS);
            if (!taken) return null;
            if (!foreground && inFlight.get() >= maxBackground) {
                global.release();
                taken = false;
                return null;
            }
            int now = inFlight.incrementAndGet();
            maxObservedInFlight.accumulateAndGet(now, Math::max);
            physicalCalls.incrementAndGet();
            return new Permit();
        } finally {
            queued.decrementAndGet();
            if (!taken) {
                // 未取得：调用方不持有任何资源。
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

        private Permit() {
            this(false);
        }

        private Permit(boolean noop) {
            this.noop = noop;
        }

        @Override
        public void close() {
            if (closed || noop) return;
            closed = true;
            inFlight.decrementAndGet();
            global.release();
        }
    }

    /** 无闸门时的空 permit（旧路径/单测直调，测试替身可断言）。 */
    public Permit noopPermit() {
        return new Permit(true);
    }

    /** 调用预算：出站前原子预留；未发出的队列取消可释放，已发出不伪装免费撤销。 */
    public static final class Budget {
        private final AtomicInteger remaining;

        Budget(int total) {
            this.remaining = new AtomicInteger(total);
        }

        /** 预留 n 次；不足返回 false（调用方明确部分增强，不偷偷追加）。 */
        public boolean reserve(int n) {
            while (true) {
                int current = remaining.get();
                if (current < n) return false;
                if (remaining.compareAndSet(current, current - n)) return true;
            }
        }

        public void release(int n) {
            remaining.addAndGet(n);
        }

        public int remaining() {
            return remaining.get();
        }
    }
}
