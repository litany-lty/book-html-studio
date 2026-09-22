package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.*;
import studio.bookhtml.config.*;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitOp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * U4 门禁：可读基线与真实阶段。隔离合成夹具 + 可控替身，不调用付费模型；
 * 真实性能标为未验证。
 */
class ProcessingProgressTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private BookStore store;
    private JobService jobs;
    private ReadingWindowService windows;
    private SettingsService settings;
    private BookService books;
    private PageProcessor processor;
    private ProcessingProgressService progress;
    private Book book;

    @AfterEach void close() {
        if (windows != null) windows.close();
        if (jobs != null) jobs.close();
        if (store != null) store.close();
    }

    private void setup(int pages) throws Exception {
        AppProperties app = TestConfigs.config(data, "", "");
        store = spy(new BookStore(app, json));
        String id = UUID.randomUUID().toString();
        store.createBookDirectory(id);
        book = new Book(id, "test", "test.pdf", pages, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        for (int n = 1; n <= pages; n++) store.writePage(id, Page.pending(n, 600, 800), false);
        books = mock(BookService.class);
        when(books.get(id)).thenReturn(book);
        processor = mock(PageProcessor.class);
        settings = new SettingsService(app, new PaddleAiStudioProperties("test-token", null, null, 30, 60, 1),
                new QwenAssistProperties(), new DecisionProperties(), json);
        jobs = new JobService(store, books, processor);
        jobs.setSettings(settings);
        progress = new ProcessingProgressService();
        jobs.setProgress(progress);
        windows = new ReadingWindowService(store, jobs, settings, clock, Duration.ofSeconds(1));
        windows.setProgress(progress);
    }

    private ReadingWindowRequest assistRequest(UUID id, long sequence, int page) {
        return new ReadingWindowRequest(id, sequence, page, "paddle-aistudio", "auto", false, true, true, true);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("condition did not become true");
    }

    private static Block text(String id, String content) {
        return new Block(id, "text", 0, new double[]{.1, .1, .5, .06}, "horizontal-tb",
                content, content, 0.9, false, false, null, "paddle", List.of(id), null,
                new double[]{10, 20, 50, 10}, List.of());
    }

    private static ProcessingResult baselineResult(int n, String content) {
        Block b = text("b" + n, content);
        return new ProcessingResult(new Page(n, 600, 800, "READY", "paddle-aistudio", List.of(b),
                List.of(), false, null, List.of(b)), ProcessingResult.Category.TEXT);
    }

    @Test void prog04_baselineReadableWhileEnhancementBlocked() throws Exception {
        setup(5);
        CountDownLatch enrichEntered = new CountDownLatch(1);
        CountDownLatch enrichRelease = new CountDownLatch(1);
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> baselineResult(inv.getArgument(1), "基线正文" + inv.getArgument(1)));
        when(processor.enrichBaseline(eq(book.id()), anyInt(), any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    int n = inv.getArgument(1);
                    if (n == 1) {
                        enrichEntered.countDown(); // The barrier belongs to the displayed page, not a neighbour.
                        assertTrue(enrichRelease.await(4, TimeUnit.SECONDS));
                    }
                    return new PageProcessor.EnrichResult(List.of(text("e" + n, "增强正文" + n)),
                            "paddle-aistudio+qwen-assist", List.of());
                });
        UUID session = UUID.randomUUID();
        windows.update(book.id(), assistRequest(session, 1, 1));
        clock.advance(Duration.ofSeconds(1));
        windows.tick();
        // 基线落盘即宣布可读，不等增强。
        await(() -> { var b = store.readPage(book.id(), 1).blocks(); return !b.isEmpty() && "基线正文1".equals(b.get(0).original()); });
        assertTrue(enrichEntered.await(3, TimeUnit.SECONDS), "增强在后台继续");
        // 增强阻塞期间：基线可读，任务仍显示增强中。
        Page during = store.readPage(book.id(), 1);
        assertEquals("基线正文1", during.blocks().get(0).original());
        ProcessingSnapshot snap = progress.latest(book.id(), 1);
        assertNotNull(snap, "真实阶段事件已记录");
        assertEquals("OCR_READABLE", snap.contentAvailability());
        assertTrue(snap.canRead());
        enrichRelease.countDown();
        await(() -> "增强正文1".equals(store.readPage(book.id(), 1).blocks().get(0).original()));
        await(() -> {
            ProcessingSnapshot s = progress.latest(book.id(), 1);
            return s != null && "SUCCEEDED".equals(s.lifecycle());
        }); // Durable page publication happens before the terminal telemetry event.
        ProcessingSnapshot done = progress.latest(book.id(), 1);
        assertEquals("ENHANCED", done.contentAvailability());
        assertEquals("SUCCEEDED", done.lifecycle());
    }

    @Test void prog_regressionInEnhancementKeepsBaselineAsPartial() throws Exception {
        setup(3);
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> baselineResult(inv.getArgument(1), "基线正文原始长文本内容"));
        when(processor.enrichBaseline(eq(book.id()), anyInt(), any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> new PageProcessor.EnrichResult(List.of(text("e", "短")),
                        "paddle-aistudio+qwen-assist", List.of()));
        UUID session = UUID.randomUUID();
        windows.update(book.id(), assistRequest(session, 1, 1));
        clock.advance(Duration.ofSeconds(1));
        windows.tick();
        await(() -> { var b = store.readPage(book.id(), 1).blocks(); return !b.isEmpty() && "基线正文原始长文本内容".equals(b.get(0).original()); });
        await(() -> {
            ProcessingSnapshot s = progress.latest(book.id(), 1);
            return s != null && "PARTIAL".equals(s.lifecycle());
        });
        // 显著缩水增强被拒绝，基线保留，不可读整页降级。
        assertEquals("基线正文原始长文本内容", store.readPage(book.id(), 1).blocks().get(0).original());
        assertNotNull(store.readCandidate(book.id(), 1), "失败增强候选可追溯");
    }

    @Test void prog_manualSaveDuringEnhancementNeverOverwritten() throws Exception {
        setup(3);
        CountDownLatch enrichEntered = new CountDownLatch(1);
        CountDownLatch enrichRelease = new CountDownLatch(1);
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> baselineResult(inv.getArgument(1), "基线正文"));
        when(processor.enrichBaseline(eq(book.id()), anyInt(), any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    if ((int) inv.getArgument(1) == 1) {
                        enrichEntered.countDown();
                        assertTrue(enrichRelease.await(4, TimeUnit.SECONDS));
                    }
                    return new PageProcessor.EnrichResult(List.of(text("e1", "增强改写")),
                            "paddle-aistudio+qwen-assist", List.of());
                });
        UUID session = UUID.randomUUID();
        windows.update(book.id(), assistRequest(session, 1, 1));
        clock.advance(Duration.ofSeconds(1));
        windows.tick();
        await(() -> { var b = store.readPage(book.id(), 1).blocks(); return !b.isEmpty() && "基线正文".equals(b.get(0).original()); });
        assertTrue(enrichEntered.await(3, TimeUnit.SECONDS));
        // 人工在此期间保存：直接写 manual 页（绕过 attempting？用 store 写构造冲突场景）。
        Page baseline = store.readPage(book.id(), 1);
        Page manual = new Page(1, 600, 800, "READY", "manual", List.of(text("m", "人工定稿")),
                List.of(), true, null, baseline.sourceRecords());
        store.writePage(book.id(), manual, false);
        enrichRelease.countDown();
        await(() -> {
            ProcessingSnapshot s = progress.latest(book.id(), 1);
            return s != null && "PARTIAL".equals(s.lifecycle());
        });
        assertEquals("人工定稿", store.readPage(book.id(), 1).blocks().get(0).original(),
                "人工版本优先，旧增强不得覆盖");
    }

    @Test void prog_storeRejectsEnhancementOverManual() throws Exception {
        setup(2);
        store.writePage(book.id(), new Page(1, 600, 800, "READY", "manual",
                List.of(text("m", "人工")), List.of(), true, null, List.of(text("m", "人工"))), false);
        Page manual = store.readPage(book.id(), 1);
        Page enriched = new Page(1, 600, 800, "READY", "paddle-aistudio+qwen-assist",
                List.of(text("e", "增强")), List.of(), false, null, manual.sourceRecords());
        var ex = assertThrows(Exception.class, () -> store.commitPage(book.id(), enriched,
                BookStore.revisionOrZero(manual), studio.bookhtml.store.CommitActor.JOB,
                "reading:00000000-0000-0000-0000-000000000000:1", CommitOp.JOB_ENHANCEMENT));
        assertEquals("人工", store.readPage(book.id(), 1).blocks().get(0).original());
    }

    @Test void prog_progressContractCountsAndTimestamps() {
        ProcessingProgressService service = new ProcessingProgressService();
        UUID attempt = service.begin("book", 7, 3);
        service.stage("book", 7, attempt, "OCR");
        service.plan("book", 7, attempt, "REVIEW_CHUNK", 6);
        service.inFlight("book", 7, attempt, 2);
        ProcessingSnapshot before = service.snapshot("book", 7, attempt);
        service.unitDone("book", 7, attempt, true);
        service.unitDone("book", 7, attempt, true);
        service.unitDone("book", 7, attempt, false);
        ProcessingSnapshot after = service.snapshot("book", 7, attempt);
        assertEquals(6, after.units().total());
        assertEquals(2, after.units().succeeded());
        assertEquals(1, after.units().failed());
        assertEquals(0, after.units().inFlight(), "2 在途被 3 次完成抵扣，不为负");
        assertTrue(after.snapshotVersion() > before.snapshotVersion());
        assertFalse(after.lastProgressAt().isBefore(before.lastProgressAt()));
        // 在途计数变化不伪造为模型进展（lastProgressAt 仅阶段/完成单位更新）。
        Instant mark = after.lastProgressAt();
        service.inFlight("book", 7, attempt, -1);
        assertEquals(mark, service.snapshot("book", 7, attempt).lastProgressAt());
        service.baselinePublished("book", 7, attempt, 4, false);
        assertEquals("OCR_READABLE", service.snapshot("book", 7, attempt).contentAvailability());
        assertEquals(4, service.snapshot("book", 7, attempt).publishedRevision());
        service.finish("book", 7, attempt, "PARTIAL", "REVIEW_IN_BACKGROUND", true);
        ProcessingSnapshot done = service.snapshot("book", 7, attempt);
        assertEquals("PARTIAL", done.lifecycle());
        assertTrue(done.canRetry());
        assertFalse(done.canStop());
    }

    @Test void prog_restartReconcilesIntentsWithoutRedispatch() throws Exception {
        setup(2);
        // 模拟崩溃前状态：页 PROCESSING + 未终态意图。
        store.writePage(book.id(), new Page(1, 600, 800, "PROCESSING", "paddle-aistudio",
                List.of(), List.of(), false, null, List.of()), false);
        PageAttempt intent = PageAttempt.register(book.id(), 1, 0, null,
                List.of("JOB_START", "JOB_BASELINE", "JOB_ENHANCEMENT", "JOB_RESTORE"));
        store.writeSidecar(store.pageAttemptsPath(book.id()),
                new PageAttempt.Journal(Map.of(intent.key(), intent)));
        jobs.reconcileAttemptIntents();
        PageAttempt.Journal journal = store.readSidecar(store.pageAttemptsPath(book.id()), PageAttempt.Journal.class);
        assertEquals("INTERRUPTED", journal.intents().get(intent.key()).lifecycle());
        verifyNoInteractions(processor);
    }

    @Test void prog_readablePagesCountedSeparatelyFromCompletion() throws Exception {
        setup(4);
        store.writePage(book.id(), new Page(1, 600, 800, "READY", "paddle",
                List.of(text("a", "甲")), List.of(), false, null, List.of(text("a", "甲"))), false);
        UUID session = UUID.randomUUID();
        windows.update(book.id(), new ReadingWindowRequest(session, 1L, 1, "paddle-aistudio", "auto",
                false, false, true, true));
        ReadingWindowResponse snapshot = windows.get(book.id(), session);
        assertEquals(1, snapshot.readablePages(), "已可读页单独计数，不与 job completed 混淆");
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return instant; }
        synchronized void advance(Duration duration) { instant = instant.plus(duration); }
    }
}
