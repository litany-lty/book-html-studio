package studio.bookhtml.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageProcessorAdvertisementTest {
    @TempDir Path temp;

    @Test void pureAdvertisementPageKeepsRawSourceAndOriginalImage() throws Exception {
        Fixture f = new Fixture(temp.resolve("ad.pdf"));
        when(f.nativeText.extract(f.file, 1, "auto")).thenReturn(Optional.empty());
        BufferedImage image = new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB);
        when(f.pdf.render(f.file, 1, 1800)).thenReturn(image);
        Block raw = block("ad", "更多低价资料微信 abc123", new double[]{.1, .01, .7, .03});
        when(f.local.recognize(eq(image), eq("auto"), eq(false), any())).thenReturn(List.of(raw));

        ProcessingResult result = f.processor.process("book", 1, "local", "auto", false, false, () -> false);
        assertEquals(ProcessingResult.Category.VISUAL_ONLY, result.category());
        assertEquals("READY", result.page().status());
        assertEquals("advertisement", result.page().blocks().get(0).type());
        assertEquals("text", result.page().sourceRecords().get(0).type());
        assertEquals(raw.original(), result.page().blocks().get(0).original());
        assertTrue(result.page().warnings().stream().anyMatch(w -> w.contains("原稿和原始识别记录保留")));
        assertTrue(Files.exists(f.file), "原始 PDF 不可被清理");
    }

    @Test void lowCoverageNativePreviewFailureRunsOcr() throws Exception {
        Fixture f = new Fixture(temp.resolve("overlay.pdf"));
        when(f.nativeText.extract(f.file, 1, "auto"))
                .thenReturn(Optional.of(List.of(block("overlay", "覆", new double[]{.1, .1, .02, .02}))));
        when(f.pdf.render(f.file, 1, 900)).thenThrow(new IOException("preview failed"));
        BufferedImage image = new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB);
        when(f.pdf.render(f.file, 1, 1800)).thenReturn(image);
        Block body = block("body", "扫描正文内容", new double[]{.1, .2, .7, .1});
        when(f.local.recognize(eq(image), eq("auto"), eq(false), any())).thenReturn(List.of(body));

        Page page = f.processor.process("book", 1, "local", "auto", false, false, () -> false).page();
        assertEquals("local", page.provider());
        assertEquals("body", page.blocks().get(0).id());
        assertEquals("body", page.sourceRecords().get(0).id());
        assertTrue(page.warnings().stream().anyMatch(w -> w.contains("预览失败")));
        verify(f.local).recognize(eq(image), eq("auto"), eq(false), any());
    }

    @Test void cancellationDuringPreviewIsNotSwallowed() throws Exception {
        Fixture f = new Fixture(temp.resolve("cancel.pdf"));
        when(f.nativeText.extract(f.file, 1, "auto"))
                .thenReturn(Optional.of(List.of(block("overlay", "覆", new double[]{.1, .1, .02, .02}))));
        when(f.pdf.render(f.file, 1, 900)).thenThrow(new CancelledException());

        assertThrows(CancelledException.class,
                () -> f.processor.process("book", 1, "local", "auto", false, false, () -> false));
        verifyNoInteractions(f.local);
    }

    private static Block block(String id, String text, double[] bbox) {
        return new Block(id, "text", 0, bbox, "horizontal-tb", text, text, .9, false, false,
                null, "local", List.of(id), null, null);
    }

    private static class Fixture {
        final Path file;
        final BookStore store = mock(BookStore.class);
        final PdfService pdf = mock(PdfService.class);
        final NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        final TesseractService local = mock(TesseractService.class);
        final PageProcessor processor = new PageProcessor(store, pdf, nativeText, local,
                mock(CloudOcrPipeline.class), mock(PaddleOcrPipeline.class), mock(MiniMaxVisionClient.class),
                mock(QwenLayoutClient.class), mock(QwenTocRecoveryService.class), new SparsePageGuard(),
                mock(VerticalLayoutNormalizer.class), mock(AssistedReviewService.class), new TraditionalConverter());

        Fixture(Path file) throws IOException {
            this.file = file;
            Files.writeString(file, "local test fixture");
            when(store.pdf("book")).thenReturn(file);
            when(store.readPage("book", 1)).thenReturn(Page.pending(1, 400, 600));
        }
    }
}
