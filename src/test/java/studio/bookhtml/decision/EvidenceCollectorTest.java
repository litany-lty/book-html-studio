package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.IssueImageService;
import studio.bookhtml.service.QwenOcrClient;
import studio.bookhtml.service.TraditionalConverter;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * J03（T37/T38/T18）：通道无关入口、新增视觉次数上限、像素预算与取消隔离。
 */
class EvidenceCollectorTest {
    @TempDir Path temp;

    private static Block block(String id, String original, String source, Double confidence) {
        return new Block(id, "text", 0, new double[]{0, 0, 0.4, 0.2}, "horizontal-tb",
                original, original, confidence, true, false, null, source, List.of(id),
                "疑点", new double[]{0, 0, 40, 20},
                List.of(new ContentIssue("i1", "suspected", 0, 1, 0, 1, "理由", false, null, "推测")));
    }

    private static Page page(Block... blocks) {
        return new Page(3, 600, 800, "READY", "paddle", List.of(blocks), List.of(), false, null, List.of(blocks));
    }

    private static DecisionModels.IssueRef ref() {
        return new DecisionModels.IssueRef("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "pdfhash", 3, 5, "b1", "i1",
                "otext", "basis", 0, 1, "span", "map-v1");
    }

    private static byte[] tinyPng() throws Exception {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 40, 20);
        g.setColor(Color.BLACK);
        g.fillRect(5, 5, 20, 10);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        image.flush();
        return out.toByteArray();
    }

    private EvidenceCollector collector(QwenOcrClient qwen, IssueImageService images,
                                        DecisionBudget budget, DecisionProperties config) {
        return new EvidenceCollector(new CandidateResolutionService(new TraditionalConverter()),
                images, qwen, budget, config);
    }

    private DecisionBudget budget() throws Exception {
        AppProperties app = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "m", "u", 5,
                "", "m", "u", 5, true);
        BookStore store = new BookStore(app, new ObjectMapper().findAndRegisterModules());
        store.createBookDirectory("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        store.writeBook(new Book("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "t", "t.pdf", 9, Instant.now(), Instant.now(), 0, 0));
        return new DecisionBudget(new DecisionStore(store, new ObjectMapper().findAndRegisterModules()));
    }

    private DecisionProperties config() {
        DecisionProperties config = new DecisionProperties();
        config.setMonetaryBudgetMinor(100L);
        return config;
    }

    @Test void allChannelsEnterSameEntryWithoutVision() throws Exception {
        // T37：各通道同类未确认 issue 进入同一受控入口；通道未配置如实说明，不伪造记录
        QwenOcrClient qwen = mock(QwenOcrClient.class);
        when(qwen.configured()).thenReturn(false);
        IssueImageService images = mock(IssueImageService.class);
        EvidenceCollector collector = collector(qwen, images, budget(), config());
        for (String source : List.of("native", "paddle", "paddle-aistudio", "qwen", "local")) {
            Block b = block("b1", "甲乙", source, 0.9);
            EvidenceCollector.Collection collected = collector.collect(ref(), "甲乙", b,
                    b.issues().get(0), "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", page(b), true, false, new AtomicInteger(), () -> false);
            assertEquals(1, collected.raws().size());
            assertEquals(1, collected.legacy().size());
            assertEquals(DecisionModels.SourceKind.LEGACY_INFERENCE, collected.legacy().get(0).sourceKind());
            assertTrue(collected.reasons().contains("VISION_CHANNEL_NOT_CONFIGURED"));
        }
        verifyNoInteractions(images);
        verify(qwen, never()).recognize(any(), anyInt(), anyInt(), anyString(), any());
    }

    @Test void freshVisionCappedAtOneByDefault() throws Exception {
        // T38：默认至多一次新增视觉识别；显式授权到 2；仍不清楚则停止，不循环追问
        QwenOcrClient qwen = mock(QwenOcrClient.class);
        when(qwen.configured()).thenReturn(true);
        Block crop = block("r", "不得", "qwen", 0.9);
        when(qwen.recognize(any(), anyInt(), anyInt(), anyString(), any()))
                .thenReturn(List.of(crop));
        IssueImageService.Snippet snippet = new IssueImageService.Snippet("region", 0,
                new double[]{0, 0, 0.2, 0.1}, List.of(), tinyPng(), new double[]{0, 0, 0.4, 0.2}, tinyPng());
        IssueImageService images = mock(IssueImageService.class);
        when(images.locateOne(any(), any(), any())).thenReturn(snippet);
        DecisionProperties config = config();
        EvidenceCollector collector = collector(qwen, images, budget(), config);
        Block b = block("b1", "甲乙", "paddle", 0.9);
        AtomicInteger calls = new AtomicInteger();
        EvidenceCollector.Collection first = collector.collect(ref(), "甲乙", b, b.issues().get(0),
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", page(b), true, false, calls, () -> false);
        assertEquals(1, first.freshVisionAttempts());
        assertEquals(2, first.raws().size());
        assertEquals("不得", first.raws().get(1).text());
        assertEquals(DecisionModels.SourceKind.CROP_OCR, first.raws().get(1).sourceKind());
        // 第二次：默认上限拒绝，不再外呼
        EvidenceCollector.Collection second = collector.collect(ref(), "甲乙", b, b.issues().get(0),
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", page(b), true, false, calls, () -> false);
        assertEquals(0, second.freshVisionAttempts());
        assertTrue(second.reasons().contains("VISION_CALL_CAP_REACHED"));
        verify(qwen, times(1)).recognize(any(), anyInt(), anyInt(), anyString(), any());
        // 显式授权到 2：配置同步提升后允许第二次，第三次仍停
        config.setMaxFreshVisionCallsPerIssue(2);
        EvidenceCollector.Collection third = collector.collect(ref(), "甲乙", b, b.issues().get(0),
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", page(b), true, true, calls, () -> false);
        assertEquals(1, third.freshVisionAttempts());
        EvidenceCollector.Collection fourth = collector.collect(ref(), "甲乙", b, b.issues().get(0),
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", page(b), true, true, calls, () -> false);
        assertEquals(0, fourth.freshVisionAttempts());
        verify(qwen, times(2)).recognize(any(), anyInt(), anyInt(), anyString(), any());
    }

    @Test void pixelBudgetCancelAndFailureIsolated() throws Exception {
        // T18：高清裁图失败/像素超限/取消隔离到对应任务；注入异常与真实磁盘满分别标注
        QwenOcrClient qwen = mock(QwenOcrClient.class);
        when(qwen.configured()).thenReturn(true);
        DecisionBudget budget = budget();
        DecisionProperties config = config();
        Block b = block("b1", "甲乙", "paddle", 0.9);

        // 像素超限：不调用 OCR
        IssueImageService bigImages = mock(IssueImageService.class);
        when(bigImages.locateOne(any(), any(), any())).thenReturn(
                new IssueImageService.Snippet("region", 0, new double[]{0, 0, 1, 1}, List.of(),
                        new byte[5 * 1024 * 1024], new double[]{0, 0, 1, 1}, new byte[1]));
        EvidenceCollector oversized = collector(qwen, bigImages, budget, config);
        EvidenceCollector.Collection capped = oversized.collect(ref(), "甲乙", b, b.issues().get(0),
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", page(b), true, false, new AtomicInteger(), () -> false);
        assertTrue(capped.reasons().contains("PIXEL_BUDGET_EXCEEDED"));
        assertEquals(0, capped.freshVisionAttempts());
        verify(qwen, never()).recognize(any(), anyInt(), anyInt(), anyString(), any());

        // 取消：不调用 OCR，不预留
        IssueImageService images = mock(IssueImageService.class);
        EvidenceCollector cancelled = collector(qwen, images, budget, config);
        EvidenceCollector.Collection abort = cancelled.collect(ref(), "甲乙", b, b.issues().get(0),
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", page(b), true, false, new AtomicInteger(), () -> true);
        assertTrue(abort.reasons().contains("CANCELLED_BEFORE_VISION"));
        verify(qwen, never()).recognize(any(), anyInt(), anyInt(), anyString(), any());
        verify(images, never()).locateOne(any(), any(), any());

        // OCR 抛异常：失败隔离，预留保留（费用未知不记 0）
        when(qwen.recognize(any(), anyInt(), anyInt(), anyString(), any()))
                .thenThrow(new RuntimeException("boom"));
        IssueImageService.Snippet snippet = new IssueImageService.Snippet("region", 0,
                new double[]{0, 0, 0.2, 0.1}, List.of(), tinyPng(), new double[]{0, 0, 0.4, 0.2}, tinyPng());
        when(images.locateOne(any(), any(), any())).thenReturn(snippet);
        EvidenceCollector failing = collector(qwen, images, budget, config);
        EvidenceCollector.Collection failed = failing.collect(ref(), "甲乙", b, b.issues().get(0),
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", page(b), true, false, new AtomicInteger(), () -> false);
        assertTrue(failed.reasons().contains("VISION_FAILED") || failed.reasons().contains("VISION_NOT_SENT"));
        assertEquals(1, budget.reservedMinor("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

        // 无原图：可理解原因，不伪造
        when(images.locateOne(any(), any(), any())).thenReturn(null);
        EvidenceCollector.Collection noImage = failing.collect(ref(), "甲乙", b, b.issues().get(0),
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", page(b), true, false, new AtomicInteger(), () -> false);
        assertTrue(noImage.reasons().contains("EVIDENCE_UNAVAILABLE"));
    }
}
