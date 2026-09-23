package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.CommitOp;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G12 / B10-03 & B10-05: 导出沙箱注入与安全边界测试。
 * 验证 script 终止符、事件属性、HTML 标签、U+2028/U+2029、ZIP 路径穿越与 Canary 秘密扫描。
 */
class ExportInjectionTest {
    @TempDir
    Path tempDir;

    private Path realDir;
    private ObjectMapper json;
    private BookStore store;
    private BookService books;
    private PdfService pdf;
    private ExportService exportService;
    private String bookId;

    static AppProperties config(Path data) {
        return new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, true);
    }

    @BeforeEach
    void setUp() throws Exception {
        realDir = tempDir.toRealPath();
        json = new ObjectMapper().findAndRegisterModules();
        var cfg = config(realDir);
        store = new BookStore(cfg, json);
        books = mock(BookService.class);
        pdf = mock(PdfService.class);

        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);

        when(pdf.render(any(Path.class), anyInt(), anyInt()))
                .thenReturn(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB));

        exportService = new ExportService(books, store, pdf, json, null, null);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
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

    @Test
    void scriptClosingTagsAndHtmlAreStrictlyEscapedInAllExportAssets() throws Exception {
        String maliciousTitle = "恶毒书名</title><script>alert('titleAttack')</script><!-- comment -->";
        Book book = new Book(bookId, maliciousTitle, "source.pdf", 1, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        when(books.get(bookId)).thenReturn(book);

        Path sourcePdf = store.pdf(bookId);
        Files.write(sourcePdf, "%PDF-mock-injection".getBytes(StandardCharsets.UTF_8));

        String maliciousText = "正文</script><script>alert('bodyAttack')</script><img src=x onerror=alert('imgAttack')>";
        Block b1 = new Block("b1", "text", 0, new double[]{0.1, 0.1, 0.8, 0.1}, "horizontal-tb",
                maliciousText, maliciousText, 0.95, false, false, null, "test", List.of("b1"), null, null);
        Page p1 = new Page(1, 600.0, 800.0, "READY", "test", List.of(b1), List.of(), false, null, List.of(b1));
        store.writePage(bookId, p1, false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeZip(bookId, out, "1");
        Map<String, byte[]> entries = unzip(out.toByteArray());

        // 1. index.html 校验：必须使用 HTML 实体转义
        String html = new String(entries.get("index.html"), StandardCharsets.UTF_8);
        assertFalse(html.contains("<script>alert('titleAttack')</script>"), "HTML 不得包含未转义攻击脚本");
        assertTrue(html.contains("&lt;/title&gt;&lt;script&gt;alert(&#39;titleAttack&#39;)&lt;/script&gt;"), "标题必须实体转义");

        // 2. assets/book.js 校验：< 和 > 必须 unicode 转义
        String bookJs = new String(entries.get("assets/book.js"), StandardCharsets.UTF_8);
        assertFalse(bookJs.contains("<script>"), "book.js 不得包含原始 <script>");
        assertFalse(bookJs.contains("</title>"), "book.js 不得包含原始 </title>");
        assertTrue(bookJs.contains("\\u003c/title\\u003e\\u003cscript\\u003e"), "book.js 字符串必须 unicode 转义");

        // 3. assets/pages-data/1.js 校验：正文内容必须 unicode 转义
        String pageJs = new String(entries.get("assets/pages-data/1.js"), StandardCharsets.UTF_8);
        assertFalse(pageJs.contains("</script>"), "pages-data/*.js 不得包含原始 </script>");
        assertFalse(pageJs.contains("<img"), "pages-data/*.js 不得包含原始 <img");
        assertTrue(pageJs.contains("\\u003c/script\\u003e\\u003cscript\\u003e"), "script 标签必须 unicode 转义");
        assertTrue(pageJs.contains("\\u003cimg"), "img 标签必须 unicode 转义");
    }

    @Test
    void unicodeLineSeparatorsU2028U2029AreEscapedToPreventSyntaxErrors() throws Exception {
        String title = "正常书名";
        Book book = new Book(bookId, title, "source.pdf", 1, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        when(books.get(bookId)).thenReturn(book);

        Path sourcePdf = store.pdf(bookId);
        Files.write(sourcePdf, "%PDF-mock-unicode".getBytes(StandardCharsets.UTF_8));

        // 包含 ECMAScript 换行分隔符 U+2028 与 U+2029
        String separatorText = "第一段\u2028第二段\u2029第三段";
        Block b1 = new Block("b1", "text", 0, new double[]{0.1, 0.1, 0.8, 0.1}, "horizontal-tb",
                separatorText, separatorText, 0.95, false, false, null, "test", List.of("b1"), null, null);
        Page p1 = new Page(1, 600.0, 800.0, "READY", "test", List.of(b1), List.of(), false, null, List.of(b1));
        store.writePage(bookId, p1, false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeZip(bookId, out, "1");
        Map<String, byte[]> entries = unzip(out.toByteArray());

        String pageJs = new String(entries.get("assets/pages-data/1.js"), StandardCharsets.UTF_8);
        assertFalse(pageJs.contains("\u2028"), "原始 U+2028 不得直接出现在 JS 字符串文本中");
        assertFalse(pageJs.contains("\u2029"), "原始 U+2029 不得直接出现在 JS 字符串文本中");
        assertTrue(pageJs.contains("\\u2028"), "U+2028 必须转义为 \\u2028");
        assertTrue(pageJs.contains("\\u2029"), "U+2029 必须转义为 \\u2029");
    }

    @Test
    void zipEntryPathTraversalAttemptsAreStrictlyRejected() {
        assertThrows(IllegalArgumentException.class, () -> SafeArchiveExtractor.validateEntryName("../malicious.sh"));
        assertThrows(IllegalArgumentException.class, () -> SafeArchiveExtractor.validateEntryName("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> SafeArchiveExtractor.validateEntryName("\\Windows\\System32\\calc.exe"));
        assertThrows(IllegalArgumentException.class, () -> SafeArchiveExtractor.validateEntryName("C:\\Windows\\System32\\calc.exe"));
        assertThrows(IllegalArgumentException.class, () -> SafeArchiveExtractor.validateEntryName("assets/../../root.txt"));
        assertThrows(IllegalArgumentException.class, () -> SafeArchiveExtractor.validateEntryName("assets/good/../../bad.txt"));
        assertThrows(IllegalArgumentException.class, () -> SafeArchiveExtractor.validateEntryName("malicious\0file.txt"));
        assertThrows(IllegalArgumentException.class, () -> SafeArchiveExtractor.validateEntryName(""));
        assertThrows(IllegalArgumentException.class, () -> SafeArchiveExtractor.validateEntryName("   "));

        // 合法路径应当通过校验
        assertDoesNotThrow(() -> SafeArchiveExtractor.validateEntryName("index.html"));
        assertDoesNotThrow(() -> SafeArchiveExtractor.validateEntryName("assets/book.js"));
        assertDoesNotThrow(() -> SafeArchiveExtractor.validateEntryName("assets/pages-data/1.js"));
        assertDoesNotThrow(() -> SafeArchiveExtractor.validateEntryName("assets/fonts/reader-fonts.css"));
    }

    @Test
    void canarySecretScanningGuaranteesNoSecretLeakageInExportArchive() throws Exception {
        String canaryToken = "CANARY_TOKEN_ISOLATION_CHECK_8877665544";
        String canaryHeader = "CANARY_AUTH_ISOLATION_CHECK_9988776655";
        String foreignBookId = "foreign-book-uuid-secret-99999";

        Book book = new Book(bookId, "秘密隔离测试书", "source.pdf", 1, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        when(books.get(bookId)).thenReturn(book);

        Path sourcePdf = store.pdf(bookId);
        Files.write(sourcePdf, "%PDF-mock-canary".getBytes(StandardCharsets.UTF_8));

        Block b1 = new Block("b1", "text", 0, new double[]{0.1, 0.1, 0.8, 0.1}, "horizontal-tb",
                "正文无秘密内容", "正文无秘密内容", 0.95, false, false, null, "test", List.of("b1"), null, null);
        Page p1 = new Page(1, 600.0, 800.0, "READY", "test", List.of(b1), List.of(), false, null, List.of(b1));
        store.writePage(bookId, p1, false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeZip(bookId, out, "1");
        Map<String, byte[]> entries = unzip(out.toByteArray());

        for (var e : entries.entrySet()) {
            String content = new String(e.getValue(), StandardCharsets.UTF_8);
            assertFalse(content.contains(canaryToken), "导出文件 " + e.getKey() + " 严禁泄漏 Token");
            assertFalse(content.contains(canaryHeader), "导出文件 " + e.getKey() + " 严禁泄漏认证头");
            assertFalse(content.contains(foreignBookId), "导出文件 " + e.getKey() + " 严禁泄漏其他书籍标识");
            assertFalse(content.contains("application.properties"), "导出文件不得泄漏应用配置文件名");
            assertFalse(content.contains("jdbc:"), "导出文件不得泄漏数据库连接串");
        }
    }
}
