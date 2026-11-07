package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationArtifactOwnershipTest {
    @Test void subimageCreationAndCloseCannotLeaveAnUnbudgetedLiveRaster() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB) {
            @Override public BufferedImage getSubimage(int x, int y, int width, int height) {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return super.getSubimage(x, y, width, height);
            }
        };
        var manager = new ResourceBudgetManager(1, 4096, 1000, () -> 0, () -> Long.MAX_VALUE);
        var original = manager.wrapImage(image);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var child = pool.submit(() -> original.subImage(0, 0, 5, 5));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var closing = pool.submit(original::close);
            try { closing.get(200, TimeUnit.MILLISECONDS); }
            catch (TimeoutException expectedWhenCreationOwnsArtifact) { }
            release.countDown();
            try (var retained = child.get(2, TimeUnit.SECONDS)) {
                closing.get(2, TimeUnit.SECONDS);
                assertNotNull(retained.image());
                assertEquals(400, manager.inFlightImageBytes());
            }
            assertEquals(0, manager.inFlightImageBytes());
        } finally { release.countDown(); original.close(); pool.shutdownNow(); }
    }

    @Test void encodedStreamRetainsBytesUntilItsOwnClose() throws Exception {
        var manager = new ResourceBudgetManager(1, 4096, 1000, () -> 0, () -> Long.MAX_VALUE);
        var artifact = manager.wrapEncoded(new byte[]{1, 2, 3}, "image/png");
        try (var stream = artifact.openStream()) {
            artifact.close();
            assertEquals(3, manager.inFlightEncodedBytes());
            assertEquals(1, stream.read());
            assertEquals(3, artifact.byteLength());
            assertThrows(IllegalStateException.class, artifact::openStream);
        } finally { artifact.close(); }
        assertEquals(0, manager.inFlightEncodedBytes());
    }
}
