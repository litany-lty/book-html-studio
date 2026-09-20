package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R01：搜索索引上限不得截断正文导出。 */
class ExportSearchIndexBoundaryTest {
    @TempDir Path tempDirectory;

    private static Block textBlock(String id, int order, String text) {
        return new Block(id, "text", order, new double[]{.1, .1, .5, .05}, "horizontal-tb",
                text, text, 0.9, false, false, null, "paddle", List.of(id), null,
                new double[]{10, 20, 50, 10}, List.of());
    }

    private static List<Block> manyBlocks(int count, String prefix) {
        List<Block> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) blocks.add(textBlock(prefix + "-" + i, i, "文字内容" + i));
        return blocks;
    }

    private ExportService service(Book book, Map<Integer, Page> pages) throws Exception {
        BookService books = mock(BookService.class);
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        when(books.get(book.id())).thenReturn(book);
        Path source = tempDirectory.resolve(book.id() + ".pdf");
        Files.write(source, "%PDF-test".getBytes(StandardCharsets.UTF_8));
        when(store.pdf(book.id())).thenReturn(source);
        when(store.tmpDir()).thenReturn(tempDirectory.resolve("tmp-" + book.id()));
        for (var e : pages.entrySet()) when(store.readPage(book.id(), e.getKey())).thenReturn(e.getValue());
        when(pdf.render(org.mockito.ArgumentMatchers.any(Path.class), anyInt(), anyInt()))
                .thenReturn(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB));
        return new ExportService(books, store, pdf, new ObjectMapper());
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

    private static Book book(String id, int totalPages) {
        return new Book(id, "标题", "source.pdf", totalPages, Instant.EPOCH, Instant.EPOCH, 0, 0);
    }

    private static Page readyPage(int n, List<Block> blocks) {
        return new Page(n, 600, 800, "READY", "paddle", blocks, List.of(), false, null);
    }

    @Test void indexJustBelowLimitKeepsEverythingComplete() throws Exception {
        Book book = book("e01a", 1);
        ExportService service = service(book, Map.of(1, readyPage(1, manyBlocks(19999, "a"))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("e01a", output, "1");
        Map<String, byte[]> entries = unzip(output.toByteArray());
        assertTrue(entries.containsKey("assets/pages-data/1.js"));
        String data = text(entries, "assets/book.js");
        assertTrue(data.contains("\"searchIndexComplete\":true"));
        assertTrue(data.contains("\"searchIndexCount\":19999"));
    }

    @Test void indexExactlyAtLimitIsComplete() throws Exception {
        Book book = book("e01b", 1);
        ExportService service = service(book, Map.of(1, readyPage(1, manyBlocks(20000, "b"))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("e01b", output, "1");
        Map<String, byte[]> entries = unzip(output.toByteArray());
        assertTrue(entries.containsKey("assets/pages-data/1.js"));
        String data = text(entries, "assets/book.js");
        assertTrue(data.contains("\"searchIndexComplete\":true"));
        assertTrue(data.contains("\"searchIndexCount\":20000"));
    }

    @Test void indexOneOverLimitStillExportsPageAndDeclaresIncomplete() throws Exception {
        Book book = book("e01c", 1);
        ExportService service = service(book, Map.of(1, readyPage(1, manyBlocks(20001, "c"))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("e01c", output, "1");
        Map<String, byte[]> entries = unzip(output.toByteArray());
        assertTrue(entries.containsKey("assets/pages-data/1.js"), "超限当前页正文不得缺失");
        String data = text(entries, "assets/book.js");
        assertTrue(data.contains("\"searchIndexComplete\":false"));
        assertTrue(data.contains("\"searchIndexCount\":20000"));
        assertTrue(data.contains("\"searchIndexTotal\":20001"));
    }

    @Test void twentyOnePagesOfOneThousandBlocksAllExported() throws Exception {
        Book book = book("e01d", 21);
        Map<Integer, Page> pages = new LinkedHashMap<>();
        for (int n = 1; n <= 21; n++) pages.put(n, readyPage(n, manyBlocks(1000, "d" + n)));
        ExportService service = service(book, pages);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("e01d", output, "all");
        Map<String, byte[]> entries = unzip(output.toByteArray());
        for (int n = 1; n <= 21; n++) assertTrue(entries.containsKey("assets/pages-data/" + n + ".js"), "第 " + n + " 页正文缺失");
        String data = text(entries, "assets/book.js");
        assertTrue(data.contains("\"searchIndexComplete\":false"));
        assertTrue(data.contains("\"searchIndexTotal\":21000"));
        assertTrue(data.contains("\"pageCount\":21"));
    }

    @Test void selectionKeepsContiguousNumbersWithSourceMapping() throws Exception {
        Book book = book("e01e", 30);
        ExportService service = service(book, Map.of(
                2, readyPage(2, manyBlocks(3, "s2")),
                5, readyPage(5, manyBlocks(3, "s5")),
                9, readyPage(9, manyBlocks(3, "s9"))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("e01e", output, "2,5,9");
        Map<String, byte[]> entries = unzip(output.toByteArray());
        assertTrue(text(entries, "assets/pages-data/1.js").contains("\"sourcePageNumber\":2"));
        assertTrue(text(entries, "assets/pages-data/2.js").contains("\"sourcePageNumber\":5"));
        assertTrue(text(entries, "assets/pages-data/3.js").contains("\"sourcePageNumber\":9"));
        assertTrue(text(entries, "assets/book.js").contains("\"pageCount\":3"));
    }

    @Test void generatedPageScriptsExecuteInNode() throws Exception {
        Book book = book("e01f", 2);
        ExportService service = service(book, Map.of(
                1, readyPage(1, manyBlocks(5, "f1")),
                2, readyPage(2, manyBlocks(5, "f2"))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("e01f", output, "all");
        Map<String, byte[]> entries = unzip(output.toByteArray());
        Path dir = tempDirectory.resolve("node-e01f");
        Files.createDirectories(dir.resolve("assets/pages-data"));
        Files.write(dir.resolve("assets/book.js"), entries.get("assets/book.js"));
        Files.write(dir.resolve("assets/pages-data/1.js"), entries.get("assets/pages-data/1.js"));
        Files.write(dir.resolve("assets/pages-data/2.js"), entries.get("assets/pages-data/2.js"));
        Path script = dir.resolve("check.cjs");
        Files.writeString(script, """
                const fs = require('fs');
                const path = require('path');
                const dir = __dirname;
                globalThis.__BOOK__ = undefined;
                globalThis.__BOOK_PAGES__ = undefined;
                eval(fs.readFileSync(path.join(dir, 'assets/book.js'), 'utf8'));
                eval(fs.readFileSync(path.join(dir, 'assets/pages-data/1.js'), 'utf8'));
                eval(fs.readFileSync(path.join(dir, 'assets/pages-data/2.js'), 'utf8'));
                const assert = require('assert');
                assert(globalThis.__BOOK__, 'missing __BOOK__');
                assert.strictEqual(globalThis.__BOOK__.pageCount, 2);
                assert(globalThis.__BOOK_PAGES__[1] && globalThis.__BOOK_PAGES__[2], 'missing pages');
                assert.strictEqual(globalThis.__BOOK_PAGES__[1].sourcePageNumber, 1);
                assert.strictEqual(globalThis.__BOOK_PAGES__[1].blocks.length, 5);
                console.log('E01f node execution ok');
                """);
        Process process = new ProcessBuilder("node", script.toString()).redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, "生成的离线脚本必须可执行：" + log);
        assertTrue(log.contains("E01f node execution ok"));
    }
}
