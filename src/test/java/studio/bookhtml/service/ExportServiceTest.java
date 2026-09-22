package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.store.BookStore;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void focusDrawerCascadeMakesTheOpenPanelVisibleAboveTheScrimAtEveryWidth() throws Exception {
        String css = generated("css");
        String script = generated("javascript");

        assertFalse(css.contains("body.focus-reading .sidebar{display:none}"),
                "专注模式不能无条件隐藏已打开的目录");
        assertEquals("none", property(css, "body.focus-reading .sidebar:not(.open)", "display"));
        assertEquals("inline-flex", property(css, "body.focus-reading .drawer-button", "display"));
        assertEquals("block", property(css, ".sidebar.open", "display"));
        assertEquals("block", property(css, ".sidebar.open .close-drawer", "display"));
        assertEquals("block", property(css, ".reading-options-dialog[open]", "display"),
                "桌面阅读设置打开后不能被旧的隐藏样式盖住");
        assertEquals("none", property(css, ".reading-options-dialog:not([open])", "display"),
                "阅读设置关闭后不能被移动端样式重新显示");
        assertTrue(Integer.parseInt(property(css, ".sidebar.open", "z-index"))
                        > Integer.parseInt(property(css, ".scrim", "z-index")),
                "打开的目录必须位于遮罩上方");
        String mobile = css.substring(css.indexOf("@media(max-width:900px)"));
        assertTrue(mobile.contains(".sidebar.open{transform:translateX(0)}"), "移动端打开态必须进入视口");

        assertTrue(script.contains("classList.add('open')"));
        assertTrue(script.contains("[data-close-drawer]').addEventListener('click',closeDrawer)"));
        assertTrue(script.contains("[data-scrim]').addEventListener('click',closeDrawer)"));
        assertTrue(script.contains("if(e.key==='Escape')closeDrawer()"));
    }

    @Test
    void exportsOriginalIssueAtlasAlongsideResolvedIssuesForReopening() throws Exception {
        BookService books = mock(BookService.class);
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        IssueImageService crops = mock(IssueImageService.class);
        ExportService service = new ExportService(books, store, pdf, new ObjectMapper(), crops);
        Book book = new Book("issue-book", "原字", "source.pdf", 1, Instant.EPOCH, Instant.EPOCH, 1, 0);
        ContentIssue issue = new ContentIssue("issue-1", "suspected", 0, 2, 0, 2, "图像依据", true, "", "推测");
        Block block = new Block("block-1", "text", 0, new double[]{.1,.1,.3,.5}, "vertical-rl",
                "原字", "原字", null, true, false, null, "paddle", List.of("block-1"), null, null, List.of(issue));
        Page page = new Page(1, 600, 800, "READY", "paddle", List.of(block), List.of(), false, null);
        Path source = tempDirectory.resolve("issue-source.pdf");
        Files.writeString(source, "%PDF-original");
        byte[] atlas = new byte[]{1,2,3};
        byte[] context = new byte[]{4,5,6};
        when(books.get("issue-book")).thenReturn(book);
        when(store.pdf("issue-book")).thenReturn(source);
        when(store.readPage("issue-book", 1)).thenReturn(page);
        when(pdf.render(source, 1, 1800)).thenReturn(new BufferedImage(120,160,BufferedImage.TYPE_INT_RGB));
        when(crops.locate("issue-book", page)).thenReturn(Map.of("issue-1",
                new IssueImageService.Snippet("glyphs", 2, block.bbox(), List.of(block.bbox()), atlas,
                        new double[]{.1,.1,.3,.8}, context)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("issue-book", output, "1");
        Map<String, byte[]> entries = unzip(output.toByteArray());
        assertArrayEquals(atlas, entries.get("assets/issues/1-1.png"));
        assertArrayEquals(context, entries.get("assets/issues/1-1-context.png"));
        String data = text(entries, "assets/book.js");
        String pageData = text(entries, "assets/pages-data/1.js");
        assertTrue(data.contains("\"schemaVersion\":2"));
        assertTrue(data.contains("\"searchIndex\""));
        assertTrue(pageData.contains("\"issueImages\""));
        assertTrue(pageData.contains("\"glyphCount\":2"));
        assertTrue(pageData.contains("\"src\":\"assets/issues/1-1.png\""));
        assertTrue(pageData.contains("\"contextSrc\":\"assets/issues/1-1-context.png\""));
        assertTrue(pageData.contains("\"contextBbox\""));
        assertTrue(pageData.contains("\"resolved\":true"));
    }

    @Test
    void exportsOfflineReaderWithReferencedCropsAndWithoutRenderingPendingPages() throws Exception {
        BookService books = mock(BookService.class);
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        ObjectMapper json = new ObjectMapper();
        ExportService service = new ExportService(books, store, pdf, json);

        String maliciousTitle = "坏书</title><script>bookAttack()</script>";
        Book book = new Book("book-1", maliciousTitle, "source.pdf", 3, Instant.EPOCH, Instant.EPOCH, 99, 99);
        Block heading = block("heading", "heading", 0, new double[]{0.05, 0.05, 0.8, 0.1}, "<img src=x onerror=textAttack()>", "简体<script>textAttack()</script>", 1);
        Block figure = block("same/id", "figure", 1, new double[]{0.1, 0.2, 0.35, 0.25}, "图表原文", "图表识别文字", null);
        Block table = block("same?id", "table", 2, new double[]{0.5, 0.2, 0.35, 0.25}, "表格原文", "表格识别文字", null);
        Page ready = new Page(1, 600, 800, "READY", "qwen", List.of(heading, figure, table), List.of(), true, null);
        Page pending = Page.pending(2, 600, 800);
        Page failed = new Page(3, 600, 800, "FAILED", "qwen", List.of(), List.of("识别失败"), false, "服务错误");
        byte[] sourceBytes = "%PDF-test-source".getBytes(StandardCharsets.UTF_8);
        Path sourcePdf = tempDirectory.resolve("source.pdf");
        Files.write(sourcePdf, sourceBytes);

        when(books.get("book-1")).thenReturn(book);
        when(store.pdf("book-1")).thenReturn(sourcePdf);
        when(store.readPage("book-1", 1)).thenReturn(ready);
        when(store.readPage("book-1", 2)).thenReturn(pending);
        when(store.readPage("book-1", 3)).thenReturn(failed);
        when(pdf.render(sourcePdf, 1, 1800)).thenReturn(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("book-1", output);
        Map<String, byte[]> entries = unzip(output.toByteArray());

        assertTrue(entries.containsKey("index.html"));
        assertTrue(entries.containsKey("assets/book.js"));
        assertTrue(entries.containsKey("assets/app.js"));
        assertTrue(entries.containsKey("assets/issue-review.js"));
        assertTrue(entries.containsKey("assets/issue-review.css"));
        assertTrue(entries.containsKey("assets/pages/1.png"));
        assertFalse(entries.containsKey("assets/pages/2.png"));
        assertFalse(entries.containsKey("assets/pages/3.png"));
        assertTrue(entries.containsKey("assets/figures/1-2.png"));
        assertTrue(entries.containsKey("assets/figures/1-3.png"));
        assertArrayEquals(sourceBytes, entries.get("source.pdf"));

        String html = text(entries, "index.html");
        String data = text(entries, "assets/book.js");
        String pageData = text(entries, "assets/pages-data/1.js");
        String app = text(entries, "assets/app.js");
        assertTrue(html.contains("assets/fonts/reader-fonts.css"));
        assertTrue(html.contains("assets/reader-fonts.js"));
        assertTrue(app.contains("init?.('select[data-reader-font]'"),
                "字体选择不能命中同样带 data-reader-font 的 HTML 根节点");
        var fontAssets = new ObjectMapper().readTree(entries.get("assets/fonts/manifest.json")).path("assets");
        assertTrue(fontAssets.isArray() && fontAssets.size() > 10);
        for (var asset : fontAssets) {
            String filename = asset.path("file").asText();
            byte[] bytes = entries.get("assets/fonts/" + filename);
            assertTrue(bytes != null, "离线字体资源缺失：" + filename);
            assertEquals(asset.path("bytes").asLong(), bytes.length, filename);
            assertEquals(asset.path("sha256").asText(), java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), filename);
            if (filename.endsWith(".css")) assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("https://"));
        }
        assertFalse(html.contains("<script>bookAttack()"));
        assertTrue(html.contains("&lt;/title&gt;&lt;script&gt;bookAttack()&lt;/script&gt;"));
        assertFalse(data.contains("<script>"));
        assertFalse(data.contains("<img"));
        assertFalse(pageData.contains("<script>"));
        assertTrue(pageData.contains("\\u003cscript\\u003etextAttack()"));
        assertTrue(data.contains("\"processedPages\":1"));
        assertTrue(data.contains("\"reviewedPages\":1"));
        assertTrue(data.contains("\"schemaVersion\":2"));
        assertTrue(data.contains("\"searchIndex\""));
        assertTrue(entries.containsKey("assets/pages-data/1.js"));
        assertTrue(entries.containsKey("assets/pages-data/2.js"));
        assertTrue(entries.containsKey("assets/pages-data/3.js"));
        assertTrue(pageData.contains("\"asset\":\"assets/figures/1-2.png\""));
        assertTrue(pageData.contains("\"asset\":\"assets/figures/1-3.png\""));
        assertTrue(app.contains("i.src=b.asset"));
        assertTrue(app.contains("source.pdf#page="));
        assertTrue(app.contains("尚未转换"));
        assertTrue(html.contains("assets/issue-review.js"));
        assertTrue(html.contains("assets/reading-layout.js"));
        assertTrue(entries.containsKey("assets/reading-layout.js"));
        assertTrue(entries.containsKey("assets/reader-navigation.js"));
        assertTrue(html.contains("assets/reader-navigation.js"));
        assertTrue(app.contains("issueReview.appendText"));
        assertTrue(app.contains("issueReview.appendText(caption,b,text)"));
        assertTrue(app.contains("node('details','figure-transcript')"));
        assertTrue(app.contains("图内识别文字（可展开校对）"));
        assertTrue(app.contains("issueReview.appendText(label,b,textFor(b))"));
        String issueReview = text(entries, "assets/issue-review.js");
        assertFalse(issueReview.contains("（模糊缺损内容推测）"));
        // J10/T60：离线默认保真阅读，未确认推测不进入正文；辅助推荐显式开启才显示并标记
        assertTrue(issueReview.contains("readingLayout.appendConfirmedIssueText"));
        assertTrue(issueReview.contains("readingLayout.appendAssistedIssueText"));
        assertTrue(issueReview.contains("globalThis.BOOK_DECISIONS"));
        assertTrue(entries.containsKey("assets/decisions.js"));
        assertTrue(app.contains("BookReadingLayout.canJoin"));
        assertTrue(app.contains("payload.outline"));
        assertTrue(app.contains("__BOOK_PAGES__"));
        assertTrue(app.contains("loadPaged"));
        assertTrue(app.contains("searchIndex"));
        assertTrue(app.contains("navigation.activeIndex(outline,state.page,state.blockId)"));
        assertTrue(app.contains("navigation.progress"));
        assertTrue(app.contains("addEventListener('input'"));
        assertTrue(app.contains("addEventListener('change'"));
        assertTrue(app.contains("aria-valuetext"));
        assertFalse(app.contains("function headingsFor"));
        assertTrue(html.contains("<details class=\"notice\""));
        assertTrue(app.contains("noticeBox.open=p.status==='FAILED'"));
        assertTrue(issueReview.contains("input.maxLength = 1000"));
        assertFalse(issueReview.contains("innerHTML"));

        verify(pdf).render(sourcePdf, 1, 1800);
        verify(pdf, never()).render(sourcePdf, 2, 1800);
        verify(pdf, never()).render(sourcePdf, 3, 1800);
        verify(pdf, never()).cropPng(sourcePdf, 1, 1800, figure.bbox());
    }

    @Test
    void exportsDetectedRelationshipTextAsReferencedFigureWithoutRewritingStoredPage() throws Exception {
        BookService books = mock(BookService.class);
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        ExportService service = new ExportService(books, store, pdf, new ObjectMapper());
        Book book = new Book("relations", "关系图", "source.pdf", 1, Instant.EPOCH, Instant.EPOCH, 1, 0);
        Block relation = new Block("relation", "text", 0, new double[]{.1, .2, .4, .3}, "horizontal-tb",
                "甲乙丙丁\n相 \\uparrow\\uparrow\\uparrow\\uparrow\n冲 戊己庚辛",
                "甲乙丙丁\n相 \\uparrow\\uparrow\\uparrow\\uparrow\n冲 戊己庚辛",
                .8, true, false, null, "paddle", List.of("relation"), null, null);
        Block invalid = new Block("invalid-relation", "text", 1, new double[]{.9, .2, .3, .3}, "horizontal-tb",
                relation.original(), relation.simplified(), .8, true, false, null, "paddle",
                List.of("invalid-relation"), null, null);
        Page page = new Page(1, 600, 800, "READY", "paddle", List.of(relation, invalid), List.of(), false, null);
        Path source = tempDirectory.resolve("relations.pdf");
        Files.writeString(source, "%PDF-relations");
        when(books.get("relations")).thenReturn(book);
        when(store.pdf("relations")).thenReturn(source);
        when(store.readPage("relations", 1)).thenReturn(page);
        when(pdf.render(source, 1, 1800)).thenReturn(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("relations", output);
        Map<String, byte[]> entries = unzip(output.toByteArray());
        String data = text(entries, "assets/book.js");
        String pageData = text(entries, "assets/pages-data/1.js");

        assertTrue(entries.containsKey("assets/figures/1-1.png"));
        assertFalse(entries.containsKey("assets/figures/1-2.png"), "无效 bbox 不得尝试导出裁图");
        assertTrue(pageData.contains("\"type\":\"figure\""));
        assertTrue(pageData.contains("\"asset\":\"assets/figures/1-1.png\""));
        assertTrue(pageData.contains("\"id\":\"invalid-relation\",\"type\":\"text\""));
        assertTrue(pageData.contains("\\\\uparrow\\\\uparrow"), "原 OCR 转录仍需保留供搜索与展开核对");
        assertEquals("text", page.blocks().get(0).type(), "导出不得回写存储快照");
    }

    private static Block block(String id, String type, int order, double[] bbox, String original, String simplified, Integer headingLevel) {
        return new Block(id, type, order, bbox, "horizontal-tb", original, simplified, 0.8, false, true, headingLevel, "qwen", List.of(), null, null);
    }

    @Test
    void exportsOnlySelectedPagesWithOriginalPdfLinksAndContiguousNavigation() throws Exception {
        BookService books = mock(BookService.class);
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        ExportService service = new ExportService(books, store, pdf, new ObjectMapper());
        Book book = new Book("selection", "样本", "source.pdf", 30, Instant.EPOCH, Instant.EPOCH, 0, 0);
        Path source = tempDirectory.resolve("selection.pdf");
        Files.writeString(source, "%PDF-sample");
        when(books.get("selection")).thenReturn(book);
        when(store.pdf("selection")).thenReturn(source);
        Page page2 = new Page(2, 600, 800, "READY", "paddle",
                List.of(block("heading-2", "heading", 0, new double[]{.1,.1,.4,.1}, "第二页标题", "第二页标题", 2)),
                List.of(), false, null);
        Page page5 = new Page(5, 600, 800, "READY", "paddle",
                List.of(block("heading-5", "heading", 0, new double[]{.1,.1,.4,.1}, "第五页标题", "第五页标题", 3)),
                List.of(), false, null);
        when(store.readPage("selection", 2)).thenReturn(page2);
        when(store.readPage("selection", 5)).thenReturn(page5);
        when(pdf.render(source, 2, 1800)).thenReturn(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB));
        when(pdf.render(source, 5, 1800)).thenReturn(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("selection", output, "2,5");
        Map<String, byte[]> entries = unzip(output.toByteArray());
        String data = text(entries, "assets/book.js");
        String app = text(entries, "assets/app.js");
        String exported1 = text(entries, "assets/pages-data/1.js");
        String exported2 = text(entries, "assets/pages-data/2.js");
        assertTrue(data.contains("\"totalPages\":2"));
        assertTrue(data.contains("\"sourceTotalPages\":30"));
        assertTrue(exported2.contains("\"sourcePageNumber\":5"));
        assertTrue(exported1.contains("\"sourcePageNumber\":2"));
        assertTrue(data.contains("\"outline\":[{\"pageNumber\":1,\"blockId\":\"heading-2\""));
        assertTrue(data.contains("{\"pageNumber\":2,\"blockId\":\"heading-5\""));
        assertTrue(app.contains("sourcePageNumber"));
        assertTrue(app.contains("p.sourcePageNumber||state.page"));
        verify(store, never()).readPage("selection", 1);
        verify(store, never()).readPage("selection", 3);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                result.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return result;
    }

    private static String text(Map<String, byte[]> entries, String name) {
        return new String(entries.get(name), StandardCharsets.UTF_8);
    }

    private static String generated(String methodName) throws Exception {
        Method method = ExportService.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (String) method.invoke(null);
    }

    private static String property(String css, String selector, String name) {
        String marker = selector + "{";
        int start = css.indexOf(marker);
        assertTrue(start >= 0, "缺少 CSS 选择器 " + selector);
        int end = css.indexOf('}', start + marker.length());
        assertTrue(end > start, "CSS 规则未闭合 " + selector);
        String declarations = css.substring(start + marker.length(), end);
        for (String declaration : declarations.split(";")) {
            int separator = declaration.indexOf(':');
            if (separator > 0 && name.equals(declaration.substring(0, separator).strip())) {
                return declaration.substring(separator + 1).strip();
            }
        }
        throw new AssertionError("选择器 " + selector + " 缺少属性 " + name);
    }
}
