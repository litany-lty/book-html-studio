package studio.bookhtml.service;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 阶段2与B01：统一渲染资源预算。
 *
 * <p>底层委托给 {@link ResourceBudgetManager} 进行单点全局资源核算。
 * 保持原有接口和构造函数完全兼容，同时支持“渲染执行槽在渲染结束后释放、图像字节租约转移给工件”的细粒度所有权生命周期。
 */
@Service
public class RenderBudget {
    static final int DEFAULT_MAX_CONCURRENT = ResourceBudgetManager.DEFAULT_MAX_CONCURRENT;
    static final long DEFAULT_MAX_IN_FLIGHT_BYTES = ResourceBudgetManager.DEFAULT_MAX_IN_FLIGHT_BYTES;
    static final long DEFAULT_MAX_WAIT_MS = ResourceBudgetManager.DEFAULT_MAX_WAIT_MS;

    private final ResourceBudgetManager manager;

    @Autowired
    public RenderBudget(ResourceBudgetManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager 不能为 null");
    }

    public RenderBudget() {
        this(new ResourceBudgetManager());
    }

    RenderBudget(int maxConcurrent, long maxInFlightBytes, long maxWaitMs) {
        this(new ResourceBudgetManager(maxConcurrent, maxInFlightBytes, maxWaitMs));
    }

    RenderBudget(int maxConcurrent, long maxInFlightBytes, long maxWaitMs,
                 LongSupplier usedHeapBytes, LongSupplier maxHeapBytes) {
        this(new ResourceBudgetManager(maxConcurrent, maxInFlightBytes, maxWaitMs, usedHeapBytes, maxHeapBytes));
    }

    public ResourceBudgetManager manager() {
        return manager;
    }

    /** 申请预算，等待可被取消或超时；返回租约必须 close。 */
    public Lease acquire(long estimatedBytes, BooleanSupplier cancelled) throws Exception {
        long need = Math.max(0, estimatedBytes);
        ResourceBudgetManager.Ticket permit = manager.acquireRenderPermit(cancelled);
        ResourceBudgetManager.Ticket imageBytes = null;
        try {
            if (need > 0) {
                imageBytes = manager.acquireImageBytes(need, cancelled);
            }
            return new Lease(need, permit, imageBytes);
        } catch (Exception e) {
            permit.close();
            throw e;
        }
    }

    public long inFlightBytes() { return manager.inFlightImageBytes(); }
    public int availablePermits() { return manager.availableRenderPermits(); }

    public static final class Lease implements AutoCloseable {
        private final long bytes;
        private final ResourceBudgetManager.Ticket permitTicket;
        private final ResourceBudgetManager.Ticket imageByteTicket;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(long bytes, ResourceBudgetManager.Ticket permitTicket, ResourceBudgetManager.Ticket imageByteTicket) {
            this.bytes = bytes;
            this.permitTicket = permitTicket;
            this.imageByteTicket = imageByteTicket;
        }

        public long bytes() {
            return bytes;
        }

        /**
         * 释放渲染并发槽许可，但分离并保留底层图像字节租约供 ImageArtifact 持有。
         */
        public ResourceBudgetManager.Ticket detachImageByteTicket() {
            if (closed.get()) {
                throw new IllegalStateException("Lease 已关闭");
            }
            // 立即释放渲染槽
            if (permitTicket != null) {
                permitTicket.close();
            }
            // 标记 Lease 本身已释放（不再重复释放字节）
            closed.set(true);
            return imageByteTicket;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (imageByteTicket != null) {
                    imageByteTicket.close();
                }
                if (permitTicket != null) {
                    permitTicket.close();
                }
            }
        }
    }
}
