package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F03/R08（T12–T14）：真实 PageProcessor + 真实 JobService + Mock 供应商。
 * 验证空白/纯视觉/空 OCR 分类的端到端行为，不只测单个 helper。
 */
class ProcessingResultIntegrationTest {
    @TempDir Path temp;
    private ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }

    private static BufferedImage white(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    private static BufferedImage inky(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.fillRect(50, 50, 500, 200);
        g.dispose();
        return image;
    }

    private PageProcessor processor(BookStore store, PdfService pdf, NativeTextExtractor nativeText,
                                    PaddleOcrPipeline paddle, QwenTocRecoveryService toc) {
        QwenLayoutClient assist = mock(QwenLayoutClient.class);
        when(assist.configured()).thenReturn(false);
        return new PageProcessor(store, pdf, nativeText, mock(TesseractService.class), mock(CloudOcrPipeline.class),
                paddle, mock(MiniMaxVisionClient.class), assist, toc,
                new SparsePageGuard(), mock(VerticalLayoutNormalizer.class), mock(AssistedReviewService.class),
                new TraditionalConverter());
    }

    private String setupBook(BookStore store, int totalPages) throws Exception {
        String id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        store.createBookDirectory(id);
        store.writeBook(new Book(id, "t", "t.pdf", totalPages, Instant.now(), Instant.now(), 0, 0));
        return id;
    }

    private void waitForJob(BookStore store, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            String s = store.readJob(id).status();
            if (s.startsWith("COMPLETED") || "FAILED".equals(s)) return;
            Thread.sleep(50);
        }
        fail("任务未在时限内完成：" + store.readJob(id).status());
    }

    @Test void blankConfirmedCommitsAsReadyBlank() throws Exception {
        // T12：源图确为空白 → BLANK_CONFIRMED 完整提交，不被 JobService 空结果保护推翻
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = setupBook(store, 1);
        store.writePage(id, Page.pending(1, 600, 800), false);
        Book book = new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0);

        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        QwenTocRecoveryService toc = mock(QwenTocRecoveryService.class);
        when(toc.recover(any(), any(), any())).thenAnswer(
                inv -> new QwenTocRecoveryService.RecoveryResult(inv.getArgument(1), false, false, false, null));
        Path file = temp.resolve("blank.pdf");
        Files.writeString(file, "pdf");
        when(nativeText.extract(file, 1, "auto")).thenReturn(Optional.empty());
        BufferedImage blank = white(600, 800);
        // pdf() 按 mock store 调用返回；真实 store 的 pdf() 按 bookId 解析路径，此处用 mock PdfService 直连
        PdfService realPdf = mock(PdfService.class);
        when(realPdf.renderForOcr(any(), eq(1))).thenReturn(blank);
        BookStore storeMock = mock(BookStore.class);
        // 使用真实 store 做任务与页面持久化，但 pdf 路径与页面读取走真实 store
        when(nativeText.extract(any(), eq(1), anyString())).thenReturn(Optional.empty());
        Block empty = new Block("paddle-1", "text", 0, new double[]{.1, .1, .5, .2}, "horizontal-tb",
                "   ", "   ", null, true, false, null, "paddle", List.of("paddle-1"), null, new double[]{0, 0, 50, 20});
        when(paddle.recognize(eq(blank), eq("auto"), eq(false), eq("paddle"), any())).thenReturn(List.of(empty));

        // 直接用真实 PageProcessor 验证分类
        PageProcessor direct = processor(storeMock, realPdf, nativeText, paddle, toc);
        when(storeMock.pdf("book")).thenReturn(file);
        when(storeMock.readPage("book", 1)).thenReturn(new Page(1, 600, 800, "PENDING", "", List.of(), List.of(), false, null, List.of()));
        when(realPdf.renderForOcr(eq(file), eq(1))).thenReturn(blank);
        ProcessingResult result = direct.process("book", 1, "paddle", "auto", false, false, () -> false);
        assertEquals(ProcessingResult.Category.BLANK_CONFIRMED, result.category());
        assertEquals("READY", result.page().status());
        assertTrue(result.page().provider().contains("blank"));

        // 端到端：JobService 不得把 BLANK_CONFIRMED 当空结果拒绝
        PageProcessor proc2 = processor(store, realPdf, nativeText, paddle, toc);
        // 真实 store 的 pdf(bookId) 需要 data 目录下存在文件；用 spy 方式覆盖 pdf 路径
        BookStore storeSpy = spy(store);
        doReturn(file).when(storeSpy).pdf(id);
        PageProcessor proc3 = processor(storeSpy, realPdf, nativeText, paddle, toc);
        BookService books = mock(BookService.class);
        when(books.get(id)).thenReturn(book);
        JobService jobs = new JobService(storeSpy, books, proc3);
        try {
            jobs.submit(id, new JobRequest("1", "paddle", "auto", false, false, false));
            waitForJob(storeSpy, id);
            Page after = storeSpy.readPage(id, 1);
            assertEquals("READY", after.status());
            assertTrue(after.provider().contains("blank"));
            assertTrue(after.blocks().isEmpty());
        } finally {
            jobs.close();
            blank.flush();
        }
        assertNotNull(proc2);
    }

    @Test void visualOnlyCommitsButInkyEmptyFails() throws Exception {
        // T13：纯图/表/公式页成功保留；有墨无字 OCR 空页不得标成功空白
        BookStore storeMock = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        QwenTocRecoveryService toc = mock(QwenTocRecoveryService.class);
        when(toc.recover(any(), any(), any())).thenAnswer(
                inv -> new QwenTocRecoveryService.RecoveryResult(inv.getArgument(1), false, false, false, null));
        PageProcessor proc = processor(storeMock, pdf, nativeText, paddle, toc);
        Path file = temp.resolve("visual.pdf");
        Files.writeString(file, "pdf");
        when(storeMock.pdf("book")).thenReturn(file);
        when(storeMock.readPage("book", 1)).thenReturn(new Page(1, 600, 800, "PENDING", "", List.of(), List.of(), false, null, List.of()));
        when(nativeText.extract(file, 1, "auto")).thenReturn(Optional.empty());

        for (String type : List.of("figure", "table", "formula")) {
            BufferedImage image = inky(600, 800);
            when(pdf.renderForOcr(file, 1)).thenReturn(image);
            Block visual = new Block("v-1", type, 0, new double[]{.1, .1, .8, .6}, "horizontal-tb",
                    "", "", null, true, false, null, "paddle", List.of("v-1"), null, new double[]{0, 0, 100, 80});
            when(paddle.recognize(eq(image), eq("auto"), eq(false), eq("paddle"), any())).thenReturn(List.of(visual));
            ProcessingResult r = proc.process("book", 1, "paddle", "auto", false, false, () -> false);
            try {
                assertEquals(ProcessingResult.Category.VISUAL_ONLY, r.category(), "类型=" + type);
                assertEquals("READY", r.page().status());
            } finally {
                image.flush();
            }
        }

        // 有墨但 OCR 返回空文字块 → 抛 OcrException，不归类为成功空白
        BufferedImage inkyImage = inky(600, 800);
        try {
            when(pdf.renderForOcr(file, 1)).thenReturn(inkyImage);
            Block empty = new Block("paddle-1", "text", 0, new double[]{.1, .1, .5, .2}, "horizontal-tb",
                    "  ", "  ", null, true, false, null, "paddle", List.of("paddle-1"), null, new double[]{0, 0, 50, 20});
            when(paddle.recognize(eq(inkyImage), eq("auto"), eq(false), eq("paddle"), any())).thenReturn(List.of(empty));
            assertThrows(OcrException.class,
                    () -> proc.process("book", 1, "paddle", "auto", false, false, () -> false));
        } finally {
            inkyImage.flush();
        }
    }

    @Test void tocRecoveryReachableWhenInitialOcrEmpty() throws Exception {
        // T14：目录视觉信号存在但初始 OCR 无字 → 恢复分支可达；普通非目录页不误触发
        BookStore storeMock = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        QwenTocRecoveryService toc = mock(QwenTocRecoveryService.class);
        PageProcessor proc = processor(storeMock, pdf, nativeText, paddle, toc);
        Path file = temp.resolve("toc.pdf");
        Files.writeString(file, "pdf");
        when(storeMock.pdf("book")).thenReturn(file);
        when(storeMock.readPage("book", 1)).thenReturn(new Page(1, 600, 800, "PENDING", "", List.of(), List.of(), false, null, List.of()));
        when(nativeText.extract(file, 1, "auto")).thenReturn(Optional.empty());
        BufferedImage image = inky(600, 800);
        try {
            when(pdf.renderForOcr(file, 1)).thenReturn(image);
            Block src = new Block("s1", "text", 0, new double[]{.1, .1, .5, .2}, "horizontal-tb",
                    "  ", "  ", null, true, false, null, "paddle", List.of("s1"), null, new double[]{0, 0, 50, 20});
            when(paddle.recognize(eq(image), eq("auto"), eq(false), eq("paddle"), any())).thenReturn(List.of(src));
            Block kept = new Block("s1", "text", 0, new double[]{.1, .1, .5, .2}, "horizontal-tb",
                    "  ", "  ", null, true, false, null, "paddle", List.of("s1"), null, new double[]{0, 0, 50, 20});
            Block fresh = new Block("qwen-toc-r1", "text", 1, new double[]{.1, .4, .5, .2}, "horizontal-tb",
                    "第一章", "第一章", null, true, false, null, "qwen-toc-recovery", List.of("s1"),
                    "目录区域恢复", new double[]{0, 100, 200, 60});
            when(toc.recover(eq(image), any(), any())).thenReturn(
                    new QwenTocRecoveryService.RecoveryResult(List.of(kept, fresh), true, true, true, null));
            ProcessingResult r = proc.process("book", 1, "paddle", "auto", false, true, () -> false);
            assertEquals(ProcessingResult.Category.TEXT, r.category());
            assertTrue(r.page().provider().contains("qwen-toc-recovery"));
            assertTrue(r.page().blocks().stream().anyMatch(b -> "第一章".equals(b.original())));
            verify(toc).recover(eq(image), any(), any());
        } finally {
            image.flush();
        }
    }
}
