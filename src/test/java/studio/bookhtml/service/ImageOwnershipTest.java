package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImageOwnershipTest {

    @Test
    void subImageRetainsParentRasterUntilBothClose() {
        ResourceBudgetManager manager = new ResourceBudgetManager(2, 10 * 1024 * 1024, 5_000);
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        ImageArtifact parent = manager.wrapImage(img);
        long reserved = parent.reservedBytes();

        ImageArtifact sub = parent.subImage(10, 10, 50, 50);
        assertEquals(reserved, manager.inFlightImageBytes(), "Shared raster does not duplicate bytes");

        // Close parent first
        parent.close();
        assertTrue(parent.isClosed());
        assertEquals(reserved, manager.inFlightImageBytes(), "Sub-image still holds raster");
        assertNotNull(sub.image());
        assertEquals(50, sub.image().getWidth());
        assertEquals(50, sub.image().getHeight());

        // Now close sub-image
        sub.close();
        assertTrue(sub.isClosed());
        assertEquals(0, manager.inFlightImageBytes(), "All raster bytes released after last consumer closes");
    }

    @Test
    void encodedImageArtifactLifecycleAndSha256() throws Exception {
        ResourceBudgetManager manager = new ResourceBudgetManager(2, 10 * 1024 * 1024, 5_000);
        byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        EncodedImageArtifact encoded = manager.wrapEncoded(data, "image/png");

        assertEquals(8, encoded.byteLength());
        assertNotNull(encoded.sha256());
        assertFalse(encoded.sha256().isBlank());
        assertEquals(8, manager.inFlightEncodedBytes());

        try (InputStream in = encoded.openStream()) {
            byte[] read = in.readAllBytes();
            assertArrayEquals(data, read);
        }

        encoded.close();
        assertTrue(encoded.isClosed());
        assertEquals(0, manager.inFlightEncodedBytes());
        assertThrows(IllegalStateException.class, encoded::openStream);
    }

    @Test
    void boundedByteOutputStreamRejectsExcessCapacity() throws IOException {
        long limit = 100;
        try (BoundedByteOutputStream out = new BoundedByteOutputStream(limit)) {
            out.write(new byte[50]);
            assertEquals(50, out.size());

            assertThrows(IOException.class, () -> out.write(new byte[60]),
                    "Writing beyond max capacity must fail before buffer expansion");
        }
    }

    @Test
    void pdfServiceRenderArtifactHoldsLeaseUntilCallerCloses(@TempDir Path tempDir) throws Exception {
        Path pdfPath = tempDir.resolve("sample.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdfPath.toFile());
        }

        ResourceBudgetManager manager = new ResourceBudgetManager(3, 100 * 1024 * 1024, 10_000);
        RenderBudget budget = new RenderBudget(manager);
        PdfService pdfService = new PdfService(budget, null);

        assertEquals(0, manager.inFlightImageBytes());

        ImageArtifact artifact = pdfService.renderArtifact(pdfPath, 1, 800);
        assertNotNull(artifact);
        assertTrue(manager.inFlightImageBytes() > 0, "Image artifact must hold in-flight bytes after render() returns");
        assertNotNull(artifact.image());

        artifact.close();
        assertEquals(0, manager.inFlightImageBytes(), "Closing returned artifact must release in-flight bytes");
    }
}
