package studio.bookhtml.service;

import org.springframework.stereotype.Component;
import studio.bookhtml.config.QwenAssistProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Compatibility facade over the process-wide provider pool, not a second admission counter. */
@Component
public class QwenRequestGate {
    private final QwenAssistProperties config;
    private final ProviderResourceRegistry resources;
    private final PhysicalCallService physicalService;
    /** Isolated construction for unit tests; Spring always supplies the shared registry and gateway. */
    public QwenRequestGate(QwenAssistProperties config) { this(config,new ProviderResourceRegistry(),null); }
    public QwenRequestGate(QwenAssistProperties config,ProviderResourceRegistry resources) { this(config,resources,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public QwenRequestGate(QwenAssistProperties config,ProviderResourceRegistry resources,PhysicalCallService physicalService) {
        this.config=Objects.requireNonNull(config);this.resources=Objects.requireNonNull(resources);
        this.physicalService=physicalService;refresh();
    }
    PhysicalCallService physicalService() { return physicalService; }
    public void refresh() {
        int max=Math.max(1,Math.min(3,config.getMaxConcurrentRequests()));
        resources.configure("qwen",max,Math.max(0,Math.min(config.getMaxBackgroundRequests(),max-1)),
                Math.max(0,Math.min(24,config.getMaxQueuedChunks())));
    }
    public Budget newBudget() { return new Budget(Math.max(1,Math.min(8,config.getMaxPhysicalCallsPerPageAttempt()))); }
    public Permit acquire(boolean foreground,Duration timeout) throws InterruptedException { return acquire(foreground,timeout,()->false); }
    public Permit acquire(boolean foreground,Duration timeout,BooleanSupplier cancelled) throws InterruptedException {
        var permit=resources.acquire("qwen",foreground,timeout,cancelled);
        return permit==null?null:new Permit(permit);
    }
    public int inFlight() { return resources.inFlight("qwen"); }
    public int backgroundInFlight() { return resources.backgroundInFlight("qwen"); }
    public int queued() { return resources.queued("qwen"); }
    public int maxObservedInFlight() { return resources.pool("qwen").maxObservedInFlight(); }
    /** Admission count, not billed sends; use UsageLedger for physical request evidence. */
    public long physicalCalls() { return resources.pool("qwen").admittedCalls(); }
    public int maxConcurrent() { return resources.pool("qwen").maxConcurrent(); }
    public final class Permit implements AutoCloseable {
        private final ProviderResourceRegistry.Permit delegate;
        private Permit(ProviderResourceRegistry.Permit delegate) { this.delegate=delegate; }
        @Override public void close() { if(delegate!=null)delegate.close(); }
    }
    public Permit noopPermit() { return new Permit(null); }

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
        /** Clamp a caller's absolute deadline; a later region never refreshes the clock. */
        public synchronized long constrainDeadline(long absolute) {
            if(deadlineNanos==null || absolute-deadlineNanos<0) deadlineNanos=absolute;
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
        public static final class Reservation implements PhysicalCallSession.Reservation {
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
