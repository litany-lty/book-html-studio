package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.ExportSnapshot;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.decision.DecisionCoordinator;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OptimizationExportTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private Map<String, byte[]> export(boolean lateDecision, String currentTitle) throws Exception {
        BookService books = mock(BookService.class);
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        String id = "snapshot-book";
        Book original = new Book(id, "冻结书名", "book.pdf", 1, Instant.EPOCH, Instant.EPOCH, 1, 0);
        Book renamed = new Book(id, currentTitle, "book.pdf", 1, Instant.EPOCH, Instant.EPOCH, 1, 0);
        when(books.get(id)).thenReturn(original);
        Path source = temp.resolve("source.pdf");
        Files.writeString(source, "%PDF-snapshot-test");
        when(store.pdf(id)).thenReturn(source);
        when(store.exportTmpDir()).thenReturn(temp.resolve("exports"));
        Block block = new Block("b1", "text", 0, new double[]{.1, .1, .8, .1}, "horizontal-tb",
                "序".repeat(130) + "獨特繁體詞", "序".repeat(130) + "独特简体词", .9,
                true, false, null, "test", List.of(), null, null,
                List.of(new ContentIssue("issue-1", "suspected", 0, 1, 0, 1, "疑字", false, null, null)));
        when(store.readPage(id, 1)).thenReturn(new Page(1, 600, 800, "READY", "test", List.of(block), List.of(), false, null));
        when(pdf.render(any(Path.class), anyInt(), anyInt())).thenReturn(new BufferedImage(12, 16, BufferedImage.TYPE_INT_RGB));
        DecisionCoordinator decisions = mock(DecisionCoordinator.class);
        ExportService service = new ExportService(books, store, pdf, json, null, decisions);
        ExportSnapshot snapshot = service.createSnapshot(id, "1");
        assertTrue(snapshot.frozenDecisions().isEmpty());
        when(books.get(id)).thenReturn(renamed);
        if (lateDecision) when(decisions.currentDecisionView(id, 1, "issue-1")).thenReturn(Map.of(
                "admittedRecommendationId", "late", "candidates", List.of(Map.of(
                        "candidateId", "late", "displayText", "甲", "originalText", "甲", "sourceKind", "model"))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip(snapshot, output);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }

    @Test void searchIndexContainsBothFullTextVariants() throws Exception {
        String script = new String(export(false, "冻结书名").get("assets/book.js"), StandardCharsets.UTF_8);
        var payload = json.readTree(script.substring(script.indexOf('=') + 1, script.lastIndexOf(';')));
        var hit = payload.path("searchIndex").get(0);
        assertTrue(hit.path("text").asText().contains("独特简体词"), "关键词不能在第 120 字后丢失");
        assertTrue(hit.path("original").asText().contains("獨特繁體詞"), "离线搜索必须同时保留繁体原文");
    }

    @Test void emptyFrozenDecisionsStayEmptyAfterSnapshotCreation() throws Exception {
        String decisions = new String(export(true, "冻结书名").get("assets/decisions.js"), StandardCharsets.UTF_8);
        assertEquals("globalThis.BOOK_DECISIONS={};", decisions);
    }

    @Test void htmlTitleUsesTheSameSnapshotAsBookMetadata() throws Exception {
        String html = new String(export(false, "导出中改名").get("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("<title>冻结书名</title>"));
        assertFalse(html.contains("导出中改名"));
    }
}
