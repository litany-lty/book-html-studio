package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IndexPublishFenceTest {
    @TempDir Path tempDir;
    private Path realDir;
    private ObjectMapper json;
    private BookStore store;
    private BookIndexService indexService;
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
        indexService = store.indexService();

        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        store.writeBook(new Book(bookId, "栅栏门测试书", "f.pdf", 20, Instant.now(), Instant.now(), 0, 0));
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private Block block(String id, String text) {
        return new Block(id, "text", 0, new double[]{0.1, 0.1, 0.8, 0.1}, "horizontal-tb",
                text, text, 0.99, false, false, null, "test", List.of(id), null, null);
    }

    private Page pageWithText(int pageNumber, String text) {
        return new Page(pageNumber, 600.0, 800.0, "READY", "test",
                List.of(block("b" + pageNumber, text)), List.of(), false, null, List.of(block("b" + pageNumber, text)));
    }

    @Test
    void concurrentMissCoalescingExecutesOnlySingleBuild() throws Exception {
        AtomicInteger buildCounter = new AtomicInteger(0);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<BookIndexManifest>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                return indexService.buildOrRebuild(store.bookDir(bookId), bookId, "pdfhash", 20,
                        store.sourceJournal(), p -> {
                            buildCounter.incrementAndGet();
                            return pageWithText(p, "正文 " + p);
                        }).get();
            }));
        }

        startGate.countDown();
        for (var f : futures) {
            BookIndexManifest manifest = f.get(10, TimeUnit.SECONDS);
            assertNotNull(manifest);
            assertEquals("READY", manifest.status());
        }

        // Each page was built exactly once (20 calls total, not 20 * 10 = 200 calls)
        assertEquals(20, buildCounter.get(), "Single-flight coalescing should invoke supplier only for single build");
        executor.shutdown();
    }

    @Test
    void fenceRejectsStaleGenerationIfSourceSeqAdvancesDuringBuild() throws Exception {
        CountDownLatch buildStarted = new CountDownLatch(1);
        CountDownLatch sourceAdvanced = new CountDownLatch(1);

        Future<BookIndexManifest> buildFuture = Executors.newSingleThreadExecutor().submit(() -> {
            return indexService.buildOrRebuild(store.bookDir(bookId), bookId, "pdfhash", 10,
                    store.sourceJournal(), p -> {
                        if (p == 1) {
                            buildStarted.countDown();
                            try {
                                sourceAdvanced.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {}
                        }
                        return pageWithText(p, "正文 " + p);
                    }).get();
        });

        buildStarted.await(5, TimeUnit.SECONDS);

        // Advance sourceSeq while build is iterating pages
        store.sourceJournal().nextSourceSeq(store.bookDir(bookId), bookId);
        sourceAdvanced.countDown();

        ExecutionException ex = assertThrows(ExecutionException.class, () -> buildFuture.get(10, TimeUnit.SECONDS));
        Throwable cause = ex.getCause();
        while (cause.getCause() != null) cause = cause.getCause();
        assertTrue(cause instanceof IOException);
        assertTrue(cause.getMessage().contains("SOURCE_SEQ_ADVANCED_DURING_BUILD"));
    }

    @Test
    void pinnedGenerationIsProtectedFromCleanup() throws Exception {
        // Build generation 1
        BookIndexManifest gen1 = indexService.buildOrRebuild(store.bookDir(bookId), bookId, "pdfhash", 5,
                store.sourceJournal(), p -> pageWithText(p, "第1代正文 " + p)).get();

        String gen1Id = gen1.generationId();
        Path gen1Path = BookIndexService.generationDir(store.bookDir(bookId), gen1Id);
        assertTrue(Files.exists(gen1Path));

        // Pin generation 1 (e.g. active export or reader session)
        indexService.pinGeneration(bookId, gen1Id);
        assertTrue(indexService.isPinned(bookId, gen1Id));

        // Build generation 2
        BookIndexManifest gen2 = indexService.buildOrRebuild(store.bookDir(bookId), bookId, "pdfhash", 5,
                store.sourceJournal(), p -> pageWithText(p, "第2代正文 " + p)).get();

        // Even though generation 2 is active, generation 1 was pinned, so it MUST NOT be deleted
        assertTrue(Files.exists(gen1Path), "Pinned generation 1 must not be deleted");

        // Unpin generation 1 and build generation 3
        indexService.unpinGeneration(bookId, gen1Id);
        assertFalse(indexService.isPinned(bookId, gen1Id));

        BookIndexManifest gen3 = indexService.buildOrRebuild(store.bookDir(bookId), bookId, "pdfhash", 5,
                store.sourceJournal(), p -> pageWithText(p, "第3代正文 " + p)).get();

        // Now generation 1 is unpinned and old, so it should be cleaned up
        assertFalse(Files.exists(gen1Path), "Unpinned old generation 1 should be cleaned up");
        assertTrue(Files.exists(BookIndexService.generationDir(store.bookDir(bookId), gen3.generationId())));
    }
}
