package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T17：渲染与导出并发交错——在用文件不被清理、隔离目录正确、ZIP 引用完整。
 * （真实慢渲染用可控 mock 模拟；真实磁盘满单列 NOT_RUN。）
 */
class ExportParallelTest {
    @TempDir Path temp;

    @Test void concurrentExportsWithSlowRenderBothSucceed() throws Exception {
        AppProperties config = new AppProperties(temp.resolve("data"), 300, 5000, 2400,
                "tesseract", "", "m", "u", 5, "", "m", "u", 5, true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BookStore store = new BookStore(config, mapper);
        String id = "22222222-3333-4444-5555-666666666666";
        store.createBookDirectory(id);
        store.writeBook(new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
        Files.write(store.pdf(id), "pdf".getBytes());
        Block block = new Block("b1", "text", 0, new double[]{0, 0, 0.4, 0.2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, null, "paddle", List.of("b1"), null,
                new double[]{0, 0, 40, 20}, List.of());
        store.writePage(id, new Page(1, 600, 800, "READY", "paddle", List.of(block),
                List.of(), false, null, List.of(block)), false);
        BookService books = mock(BookService.class);
        when(books.get(id)).thenReturn(
                new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
        PdfService pdf = mock(PdfService.class);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger renders = new AtomicInteger();
        when(pdf.render(any(), eq(1), anyInt())).thenAnswer(inv -> {
            renders.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS), "渲染应并行进入");
            BufferedImage image = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
            return image;
        });
        ExportService service = new ExportService(books, store, pdf, mapper, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<byte[]> first = pool.submit(() -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                service.writeZip(id, out, "1");
                return out.toByteArray();
            });
            Future<byte[]> second = pool.submit(() -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                service.writeZip(id, out, "1");
                return out.toByteArray();
            });
            assertTrue(entered.await(15, TimeUnit.SECONDS), "两个导出应并行渲染");
            release.countDown();
            byte[] zip1 = first.get(30, TimeUnit.SECONDS);
            byte[] zip2 = second.get(30, TimeUnit.SECONDS);
            for (byte[] bytes : new byte[][]{zip1, zip2}) {
                boolean hasPages = false, hasDecisions = false;
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        zip.readAllBytes();
                        if (entry.getName().equals("assets/pages-data/1.js")) hasPages = true;
                        if (entry.getName().equals("assets/decisions.js")) hasDecisions = true;
                    }
                }
                assertTrue(hasPages && hasDecisions, "ZIP 引用完整");
            }
            // 在用/残留暂存清理：无 .part 残留
            try (Stream<Path> files = Files.list(store.exportTmpDir())) {
                assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".part")));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
