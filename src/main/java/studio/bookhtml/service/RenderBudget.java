package studio.bookhtml.service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;

/**
 * 阶段2：统一渲染资源预算。
 *
 * <p>所有高清渲染入口（预览、OCR、疑字证据、导出）共享同一准入：并发许可 + 在途字节
 * + 堆水位三重检查。等待可取消、有超时、有公平性；资源不足时明确拒绝（429/503），
 * 不靠加大堆或 catch OOM 后继续当作健康。监测（MemoryMXBean 快照）只用于提前调度，
 * 不是 OOM 恢复机制——真正的解码峰值隔离由独立工作进程承担。
 *
 * <p>预算租约的所有退出路径都必须归还配额（try-with-resources + finally），见各调用点短注释。
 */
@Service
public class RenderBudget {
    static final int DEFAULT_MAX_CONCURRENT = 2;
    static final long DEFAULT_MAX_IN_FLIGHT_BYTES = 384L * 1024 * 1024;
    static final long DEFAULT_MAX_WAIT_MS = 30_000;

    private final Semaphore permits;
    private final long maxInFlightBytes;
    private final long maxWaitMs;
    private final LongSupplier usedHeapBytes;
    private final LongSupplier maxHeapBytes;
    private final java.util.concurrent.atomic.AtomicLong inFlightBytes = new java.util.concurrent.atomic.AtomicLong();

    public RenderBudget() {
        this(envInt("RENDER_MAX_CONCURRENT", DEFAULT_MAX_CONCURRENT),
             envLong("RENDER_MAX_IN_FLIGHT_MB", 384) * 1024 * 1024,
             envLong("RENDER_MAX_WAIT_MS", DEFAULT_MAX_WAIT_MS));
    }

    RenderBudget(int maxConcurrent, long maxInFlightBytes, long maxWaitMs) {
        this(maxConcurrent, maxInFlightBytes, maxWaitMs,
             defaultUsedHeap(), defaultMaxHeap());
    }

    RenderBudget(int maxConcurrent, long maxInFlightBytes, long maxWaitMs,
                 LongSupplier usedHeapBytes, LongSupplier maxHeapBytes) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
        this.maxInFlightBytes = maxInFlightBytes;
        this.maxWaitMs = maxWaitMs;
        this.usedHeapBytes = usedHeapBytes;
        this.maxHeapBytes = maxHeapBytes;
    }

    /** 申请预算，等待可被取消或超时；返回租约必须 close。 */
    public Lease acquire(long estimatedBytes, BooleanSupplier cancelled) throws Exception {
        long need = Math.max(0, estimatedBytes);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, maxWaitMs));
        try {
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) throw new CancelledException();
                if (Thread.currentThread().isInterrupted()) throw new CancelledException();
                // 堆水位与在途量先检查，避免许可到手后仍超限
                if (fits(need) && permits.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    // 抢到许可后复查：超限则归还并继续等，成功则租约接管许可
                    if (fits(need)) {
                        long after = inFlightBytes.addAndGet(need);
                        if (after <= maxInFlightBytes) return new Lease(need);
                        inFlightBytes.getAndUpdate(v -> Math.max(0, v - need));
                    }
                    permits.release();
                } else if (!fits(need)) {
                    Thread.sleep(50);
                }
                if (System.nanoTime() >= deadline) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
    }

    private boolean fits(long need) {
        if (inFlightBytes.get() + need > maxInFlightBytes) return false;
        try {
            long max = maxHeapBytes.getAsLong();
            if (max > 0 && usedHeapBytes.getAsLong() + need > max * 0.85) return false;
        } catch (Exception ignored) { }
        return true;
    }

    public long inFlightBytes() { return inFlightBytes.get(); }
    public int availablePermits() { return permits.availablePermits(); }

    public final class Lease implements AutoCloseable {
        private final long bytes;
        private boolean closed;
        private Lease(long bytes) { this.bytes = bytes; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            inFlightBytes.getAndUpdate(v -> Math.max(0, v - bytes));
            permits.release();
        }
    }

    private static LongSupplier defaultUsedHeap() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        return () -> bean.getHeapMemoryUsage().getUsed();
    }

    private static LongSupplier defaultMaxHeap() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        return () -> bean.getHeapMemoryUsage().getMax();
    }

    private static int envInt(String name, int fallback) {
        try { String v = System.getenv(name); return v == null ? fallback : Integer.parseInt(v.strip()); }
        catch (Exception ignored) { return fallback; }
    }

    private static long envLong(String name, long fallback) {
        try { String v = System.getenv(name); return v == null ? fallback : Long.parseLong(v.strip()); }
        catch (Exception ignored) { return fallback; }
    }
}
