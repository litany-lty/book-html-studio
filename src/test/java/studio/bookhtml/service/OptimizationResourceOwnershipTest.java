package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationResourceOwnershipTest {
    private static ResourceBudgetManager manager() {
        return new ResourceBudgetManager(1, 1024, 1000, () -> 0, () -> Long.MAX_VALUE);
    }

    @Test void cancellationWhileWaitingCannotAcquireAReleasedRenderPermit() throws Exception {
        var manager = manager();
        var entered = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var pool = Executors.newSingleThreadExecutor();
        try (var held = manager.acquireRenderPermit(() -> false)) {
            var pending = pool.submit(() -> {
                try (var acquired = manager.acquireRenderPermit(() -> {
                    entered.countDown(); return cancelled.get();
                })) { return "ADMITTED"; }
                catch (CancelledException expected) { return "CANCELLED"; }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            held.close();
            assertEquals("CANCELLED", pending.get(2, TimeUnit.SECONDS));
            assertEquals(1, manager.availableRenderPermits());
        } finally { pool.shutdownNow(); }
    }

    @Test void encodedGrowthRejectsForeignTicketWithoutChangingEitherBudget() throws Exception {
        var owner = manager();
        var stranger = manager();
        try (var ticket = owner.acquireEncodedBytes(64, () -> false)) {
            assertFalse(stranger.tryGrowEncoded(ticket, 64));
            assertEquals(64, owner.inFlightEncodedBytes());
            assertEquals(0, stranger.inFlightEncodedBytes());
            assertEquals(64, ticket.reservedBytes());
        }
        assertEquals(0, owner.inFlightEncodedBytes());
    }

    @Test void encodedGrowthCannotOverflowTheCombinedByteLimit() throws Exception {
        var manager = manager();
        try (var ticket = manager.acquireEncodedBytes(64, () -> false)) {
            assertFalse(manager.tryGrowEncoded(ticket, Long.MAX_VALUE));
            assertEquals(64, manager.inFlightEncodedBytes());
            assertEquals(64, ticket.reservedBytes());
        }
        assertEquals(0, manager.inFlightEncodedBytes());
    }

    @Test void detachingImageLeaseWinsOwnershipBeforeReleasingRenderPermit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var permitClosed = new AtomicBoolean();
        var manager = new ResourceBudgetManager(1, 1024, 1000, () -> 0, () -> Long.MAX_VALUE) {
            @Override public Ticket acquireRenderPermit(java.util.function.BooleanSupplier cancelled) {
                return new Ticket() {
                    public long reservedBytes() { return 0; }
                    public boolean isClosed() { return permitClosed.get(); }
                    public void close() {
                        if (!permitClosed.compareAndSet(false, true)) return;
                        entered.countDown();
                        try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    }
                };
            }
        };
        var pool = Executors.newSingleThreadExecutor();
        var lease = new RenderBudget(manager).acquire(64, () -> false);
        try {
            var detached = pool.submit(lease::detachImageByteTicket);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            lease.close();
            release.countDown();
            try (var imageTicket = detached.get(2, TimeUnit.SECONDS)) {
                assertFalse(imageTicket.isClosed());
                assertEquals(64, manager.inFlightImageBytes());
            }
            assertEquals(0, manager.inFlightImageBytes());
        } finally { release.countDown(); lease.close(); pool.shutdownNow(); }
    }
}
