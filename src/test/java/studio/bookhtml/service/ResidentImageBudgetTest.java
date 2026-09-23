package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.api.ApiException;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResidentImageBudgetTest {

    @Test
    void acquireRejectsNegativeOrOverflowBytes() {
        ResourceBudgetManager manager = new ResourceBudgetManager(3, 100 * 1024 * 1024, 200);
        assertThrows(IllegalArgumentException.class, () -> manager.acquireImageBytes(-100, () -> false));
        assertThrows(IllegalArgumentException.class, () -> ResourceBudgetManager.calculatePixelBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(ApiException.class, () -> manager.acquireImageBytes(200 * 1024 * 1024, () -> false));
    }

    @Test
    void closeIsIdempotentAndThreadSafe() throws Exception {
        ResourceBudgetManager manager = new ResourceBudgetManager(3, 10 * 1024 * 1024, 5_000);
        long bytes = 1024 * 1024;
        ResourceBudgetManager.Ticket ticket = manager.acquireImageBytes(bytes, () -> false);
        assertEquals(bytes, manager.inFlightImageBytes());

        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    ticket.close();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, manager.inFlightImageBytes(), "Closing ticket multiple times concurrently must not double-release");
    }

    @Test
    void concurrencyAndMaxBytesNeverExceededUnderLoad() throws Exception {
        int maxConcurrent = 4;
        long maxBytes = 16 * 1024 * 1024; // 16 MB
        ResourceBudgetManager manager = new ResourceBudgetManager(maxConcurrent, maxBytes, 10_000);

        int workers = 100;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workers);
        AtomicInteger activePermits = new AtomicInteger();
        AtomicInteger maxObservedPermits = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    long needed = 2 * 1024 * 1024; // 2 MB each
                    try (var permit = manager.acquireRenderPermit(() -> false)) {
                        int current = activePermits.incrementAndGet();
                        maxObservedPermits.accumulateAndGet(current, Math::max);
                        try (var ticket = manager.acquireImageBytes(needed, () -> false)) {
                            assertTrue(manager.inFlightImageBytes() <= maxBytes, "inFlight bytes exceeded max");
                            Thread.sleep(10);
                        } finally {
                            activePermits.decrementAndGet();
                        }
                    }
                } catch (ApiException e) {
                    // 429 too many requests under contention is acceptable
                } catch (Exception e) {
                    fail("Unexpected failure: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        for (Future<?> f : futures) {
            f.get();
        }

        assertTrue(maxObservedPermits.get() <= maxConcurrent, "Max concurrent permits exceeded");
        assertEquals(0, manager.inFlightImageBytes(), "All bytes should be released");
        assertEquals(maxConcurrent, manager.availableRenderPermits(), "All permits should be returned");
    }

    @Test
    void imageArtifactLifecycleThrowsOnClosed() {
        ResourceBudgetManager manager = new ResourceBudgetManager(2, 10 * 1024 * 1024, 5_000);
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageArtifact artifact = manager.wrapImage(img);

        assertNotNull(artifact.image());
        assertFalse(artifact.isClosed());
        assertTrue(artifact.reservedBytes() > 0);
        assertTrue(manager.inFlightImageBytes() > 0);

        artifact.close();
        assertTrue(artifact.isClosed());
        assertThrows(IllegalStateException.class, artifact::image);
        assertEquals(0, manager.inFlightImageBytes());

        // Repeated close
        assertDoesNotThrow(artifact::close);
        assertEquals(0, manager.inFlightImageBytes());
    }

    @Test
    void imageArtifactRetainSharesOwnership() {
        ResourceBudgetManager manager = new ResourceBudgetManager(2, 10 * 1024 * 1024, 5_000);
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageArtifact original = manager.wrapImage(img);
        long initialBytes = manager.inFlightImageBytes();

        ImageArtifact retained = original.retain();
        assertEquals(initialBytes, manager.inFlightImageBytes(), "Retaining same raster does not duplicate reserved bytes");

        // Close original
        original.close();
        assertTrue(original.isClosed());
        assertThrows(IllegalStateException.class, original::image);

        // Retained copy is still open and image is accessible
        assertFalse(retained.isClosed());
        assertNotNull(retained.image());
        assertEquals(initialBytes, manager.inFlightImageBytes(), "Bytes still held by retained copy");

        // Close retained copy
        retained.close();
        assertTrue(retained.isClosed());
        assertThrows(IllegalStateException.class, retained::image);
        assertEquals(0, manager.inFlightImageBytes());
    }
}
