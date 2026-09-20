package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportStagingTest {
    @TempDir Path temp;

    private static Block block(String id) {
        return new Block(id, "text", 0, new double[]{.1, .1, .4, .1}, "horizontal-tb",
                "标题", "标题", 0.8, false, true, 2, "qwen", List.of(), null, null);
    }

    @Test void successLeavesNoStagingParts() throws Exception {
        BookService books = mock(BookService.class);
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        Path dataTmp = temp.resolve("data-tmp");
        Files.createDirectories(dataTmp);
        when(store.tmpDir()).thenReturn(dataTmp);
        Book book = new Book("b1", "书", "source.pdf", 1, Instant.EPOCH, Instant.EPOCH, 1, 1);
        Page ready = new Page(1, 600, 800, "READY", "qwen", List.of(block("h")), List.of(), true, null);
        Path source = temp.resolve("source.pdf");
        Files.write(source, "%PDF-stage".getBytes(StandardCharsets.UTF_8));
        when(books.get("b1")).thenReturn(book);
        when(store.pdf("b1")).thenReturn(source);
        when(store.readPage("b1", 1)).thenReturn(ready);
        when(pdf.render(source, 1, 1800)).thenReturn(new java.awt.image.BufferedImage(120, 160, java.awt.image.BufferedImage.TYPE_INT_RGB));
        ExportService service = new ExportService(books, store, pdf, new ObjectMapper());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeZip("b1", output, "1");
        assertTrue(output.size() > 0);
        try (var stream = Files.list(dataTmp)) {
            assertTrue(stream.filter(p -> p.getFileName().toString().endsWith(".part")).findAny().isEmpty(),
                    "成功后临时分片必须清理");
        }
        Map<String, byte[]> entries = unzip(output.toByteArray());
        assertTrue(entries.containsKey("assets/book.js"));
        assertTrue(new String(entries.get("assets/book.js"), StandardCharsets.UTF_8).startsWith("globalThis.__BOOK__="));
    }

    @Test void failureSendsNoPartialZip() throws Exception {
        BookService books = mock(BookService.class);
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        Path dataTmp = temp.resolve("data-tmp-fail");
        Files.createDirectories(dataTmp);
        when(store.tmpDir()).thenReturn(dataTmp);
        Book book = new Book("b2", "书", "source.pdf", 1, Instant.EPOCH, Instant.EPOCH, 0, 0);
        Page ready = new Page(1, 600, 800, "READY", "qwen", List.of(block("h")), List.of(), true, null);
        Path source = temp.resolve("source2.pdf");
        Files.write(source, "%PDF-fail".getBytes(StandardCharsets.UTF_8));
        when(books.get("b2")).thenReturn(book);
        when(store.pdf("b2")).thenReturn(source);
        when(store.readPage("b2", 1)).thenReturn(ready);
        when(pdf.render(source, 1, 1800)).thenThrow(new java.io.IOException("渲染失败"));
        ExportService service = new ExportService(books, store, pdf, new ObjectMapper());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(Exception.class, () -> service.writeZip("b2", output, "1"));
        assertEquals(0, output.size(), "失败不得向客户端发送残缺 ZIP");
        try (var stream = Files.list(dataTmp)) {
            assertTrue(stream.filter(p -> p.getFileName().toString().endsWith(".part")).findAny().isEmpty(),
                    "失败后临时分片必须清理");
        }
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                result.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return result;
    }
}
