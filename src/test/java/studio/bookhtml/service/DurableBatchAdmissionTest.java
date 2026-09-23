package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.CreateConsentRequest;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.DurableJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableBatchAdmissionTest {
    @TempDir Path dataDir;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BookStore store;
    private JobService jobs;
    private CloudConsentService consentService;
    private PageProcessor processor;
    private String bookId;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties app = TestConfigs.config(dataDir, "", "");
        store = new BookStore(app, json);
        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        Book book = new Book(bookId, "批量测试书", "batch.pdf", 10, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        for (int n = 1; n <= 10; n++) {
            store.writePage(bookId, Page.pending(n, 600, 800), false);
        }

        BookService books = mock(BookService.class);
        when(books.get(bookId)).thenReturn(book);
        processor = mock(PageProcessor.class);
        jobs = new JobService(store, books, processor);

        consentService = new CloudConsentService(store.consentStore(), store.policyStore(), store.epochStore());
        jobs.setCloudConsentService(consentService);
        jobs.setOperationEpochStore(store.epochStore());

        // Grant consent
        var policy = consentService.getReadingPolicy("local-owner");
        consentService.createConsent("local-owner", new CreateConsentRequest(
                "op-batch-setup", policy.policyRevision(), CloudConsent.Scope.book(bookId),
                List.of("paddle-aistudio"), "AUTO_CURRENT", 1, 2, true, false, false, true,
                1, 8, CloudConsent.MonetaryLimits.cny(1000), null));
    }

    @AfterEach
    void tearDown() {
        if (jobs != null) jobs.close();
        if (store != null) store.close();
    }

    @Test
    void rangeCompressionAndDurableBatchFileCreation() throws Exception {
        List<Integer> pages = List.of(1, 2, 3, 5, 6, 7, 10);
        List<DurableBatchAdmission.PageRange> ranges = DurableBatchAdmission.compressRanges(pages);
        assertEquals(3, ranges.size());
        assertEquals(1, ranges.get(0).from());
        assertEquals(3, ranges.get(0).to());
        assertEquals(5, ranges.get(1).from());
        assertEquals(7, ranges.get(1).to());
        assertEquals(10, ranges.get(2).from());
        assertEquals(10, ranges.get(2).to());

        Job submitted = jobs.submit(bookId, new JobRequest("1-3,5-7,10", "paddle-aistudio", "auto", false, false, false));
        assertNotNull(submitted);

        Path batchFile = store.bookDir(bookId).resolve("batches").resolve(submitted.id() + ".json");
        assertTrue(Files.exists(batchFile), "批量准入轻量记录必须已落盘");
        DurableBatchAdmission loaded = json.readValue(batchFile.toFile(), DurableBatchAdmission.class);
        assertEquals(submitted.id(), loaded.jobId());
        assertEquals(bookId, loaded.bookId());
        assertEquals(3, loaded.pageRanges().size());
        assertEquals(7, loaded.admittedRevisions().size());
    }

    @Test
    void pageEditedDuringQueueingIsProtectedFromOverwrite() throws Exception {
        CountDownLatch inWorker = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(processor.processBaseline(eq(bookId), eq(1), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    inWorker.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return new ProcessingResult(Page.pending(1, 600, 800), ProcessingResult.Category.TEXT);
                });

        try {
            // Submit pages 1 and 2 with force=true
            Job job = jobs.submit(bookId, new JobRequest("1-2", "paddle-aistudio", "auto", false, true, false));
            assertTrue(inWorker.await(3, TimeUnit.SECONDS));

            // While page 1 is being processed, edit page 2 to revision 1
            Block b = new Block("m2", "text", 0, new double[]{.1, .1, .8, .1}, "horizontal-tb", "人工新版", "人工新版",
                    1.0, false, true, null, "manual", List.of("m2"), null, null);
            Page page2Edited = new Page(2, 600, 800, "READY", "manual", List.of(b), List.of(), true, null, List.of(b));
            store.writePage(bookId, page2Edited, true);

            release.countDown();

            // Wait for job to finish
            long deadline = System.currentTimeMillis() + 5000;
            while (!store.readJob(bookId).status().startsWith("COMPLETED") && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            Job finished = store.readJob(bookId);
            assertTrue(finished.errors().stream().anyMatch(e -> e.contains("确认后已更新")),
                    "排队期间发生人工编辑的页面必须被保护，不能被批量覆写");
            assertEquals("人工新版", store.readPage(bookId, 2).blocks().get(0).original());
        } finally {
            release.countDown();
        }
    }
}
