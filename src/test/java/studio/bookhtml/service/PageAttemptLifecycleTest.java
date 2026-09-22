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
 * U2 门禁：任务生命周期、重试与授权。全部使用隔离合成夹具与可控替身，
 * 不调用付费模型；并发/取消时序用 CountDownLatch 排列，不靠 sleep 猜测。
 */
class PageAttemptLifecycleTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private BookStore store;
    private JobService jobs;
    private ReadingWindowService windows;
    private SettingsService settings;
    private BookService books;
    private PageProcessor processor;
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
        windows = new ReadingWindowService(store, jobs, settings, clock, Duration.ofSeconds(1));
    }

    private ReadingWindowRequest request(UUID id, long sequence, int page) {
        return new ReadingWindowRequest(id, sequence, page, "paddle-aistudio", "auto", false, false, true, true);
    }

    private ReadingWindowRequest retry(UUID id, long sequence, int page) {
        return new ReadingWindowRequest(id, sequence, page, "paddle-aistudio", "auto", false, false, true, false, false, true);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("condition did not become true");
    }

    private UUID reservationOf() {
        return UUID.fromString(store.readJob(book.id()).id().substring("reading:".length()));
    }

    private static Block text(String id, String content) {
        return new Block(id, "text", 0, new double[]{.1, .1, .5, .06}, "horizontal-tb",
                content, content, 0.9, false, false, null, "paddle", List.of(id), null,
                new double[]{10, 20, 50, 10}, List.of());
    }

    private static ProcessingResult readyWith(int n, String content) {
        Block b = text("b" + n, content);
        return new ProcessingResult(new Page(n, 600, 800, "READY", "paddle-aistudio", List.of(b),
                List.of(), false, null, List.of(b)), ProcessingResult.Category.TEXT);
    }

    @Test void safe01_readyRetryKeepsReadableBlocksWhileBlocked() throws Exception {
        setup(5);
        store.writePage(book.id(), new Page(1, 600, 800, "READY", "paddle-aistudio",
                List.of(text("b1", "旧可读正文")), List.of(), false, null,
                List.of(text("b1", "旧可读正文"))), false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> calls = new CopyOnWriteArrayList<>();
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int n = inv.getArgument(1);
                    calls.add(n);
                    if (n == 1) { entered.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS)); return readyWith(1, "新识别正文"); }
                    return readyWith(n, "背景正文" + n);
                });
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 1));
        clock.advance(Duration.ofSeconds(1));
        windows.tick();
        await(() -> "READY".equals(store.readPage(book.id(), 2).status()));
        windows.update(book.id(), retry(session, 2, 1));
        assertTrue(entered.await(3, TimeUnit.SECONDS), "重试 attempt 应已派发");
        // 阻塞期间：原正文仍可读，快照未先写空 PENDING。
        // 注：单 status 字段在重处理期间为 PROCESSING（旧机制），但内容保持可读；
        // 内容可用性与任务生命周期的彻底分离见 U4 ProcessingSnapshot。
        Page during = store.readPage(book.id(), 1);
        assertNotEquals("PENDING", during.status(), "重试不得先清空快照");
        assertFalse(during.blocks().isEmpty(), "重处理期间原正文仍可读");
        assertEquals(1, during.blocks().size());
        assertEquals("旧可读正文", during.blocks().get(0).original());
        assertEquals(1, calls.stream().filter(n -> n == 1).count(), "同一操作不重复派发");
        assertTrue(jobs.attemptSnapshot().containsKey(book.id() + ":1"), "重试页已登记 attempt");
        release.countDown();
        await(() -> "新识别正文".equals(store.readPage(book.id(), 1).blocks().get(0).original()));
    }

    @Test void safe02_manualAndReviewedPagesRejectedWithoutOverwrite() throws Exception {
        setup(5);
        store.writePage(book.id(), new Page(2, 600, 800, "READY", "manual",
                List.of(text("m", "人工正文")), List.of(), true, null,
                List.of(text("m", "人工正文"))), false);
        store.writePage(book.id(), new Page(3, 600, 800, "READY", "paddle-aistudio",
                List.of(text("r", "已校对正文")), List.of(), true, null,
                List.of(text("r", "已校对正文"))), false);
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 2));
        UUID reservation = reservationOf();
        PageReprocessRequest manual = new PageReprocessRequest(0, "op-manual", false, "paddle-aistudio", false);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> jobs.requestReprocess(reservation, book.id(), 2, manual,
                        "paddle-aistudio", "auto", false, false)).status());
        PageReprocessRequest reviewed = new PageReprocessRequest(0, "op-reviewed", false, "paddle-aistudio", false);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> jobs.requestReprocess(reservation, book.id(), 3, reviewed,
                        "paddle-aistudio", "auto", false, false)).status());
        // 前端禁用与否不影响保护；内容 untouched 且无新物理调用。
        assertEquals("人工正文", store.readPage(book.id(), 2).blocks().get(0).original());
        assertEquals("已校对正文", store.readPage(book.id(), 3).blocks().get(0).original());
        verifyNoInteractions(processor);
    }

    @Test void safe03_staleRevisionConfirmationRejected() throws Exception {
        setup(3);
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 1));
        UUID reservation = reservationOf();
        PageReprocessRequest stale = new PageReprocessRequest(999, "op-stale", true, "paddle-aistudio", false);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> jobs.requestReprocess(reservation, book.id(), 1, stale,
                        "paddle-aistudio", "auto", false, false)).status());
    }

    @Test void safe05_sameOperationIdReturnsSameAttemptWithoutNewPhysicalCall() throws Exception {
        setup(3);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> calls = new CopyOnWriteArrayList<>();
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int n = inv.getArgument(1);
                    calls.add(n);
                    if (n == 1) {
                        entered.countDown();
                        assertTrue(release.await(4, TimeUnit.SECONDS));
                    }
                    return readyWith(n, "x");
                });
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 1));
        clock.advance(Duration.ofSeconds(1));
        windows.tick();
        await(() -> "READY".equals(store.readPage(book.id(), 2).status()));
        UUID reservation = reservationOf();
        PageReprocessRequest first = new PageReprocessRequest(
                BookStore.revisionOrZero(store.readPage(book.id(), 1)), "op-once", false, "paddle-aistudio", false);
        Job one = jobs.requestReprocess(reservation, book.id(), 1, first, "paddle-aistudio", "auto", false, false);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        Job two = jobs.requestReprocess(reservation, book.id(), 1, first, "paddle-aistudio", "auto", false, false);
        assertEquals(one.id(), two.id(), "同 operationId 同参数返回同一任务");
        assertEquals(1, calls.stream().filter(n -> n == 1).count(), "本页物理请求不重复");
        // 不同参数同 ID 拒绝。
        PageReprocessRequest changed = new PageReprocessRequest(
                BookStore.revisionOrZero(store.readPage(book.id(), 1)), "op-once", false, "ppocr", false);
        assertThrows(ApiException.class, () -> jobs.requestReprocess(reservation, book.id(), 1, changed,
                "ppocr", "auto", false, false));
        release.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 1).status()));
    }

    @Test void safe0607_cancelKeepsRegistrationUntilWorkerFinishesThenRestores() throws Exception {
        setup(5);
        store.writePage(book.id(), new Page(3, 600, 800, "READY", "paddle-aistudio",
                List.of(text("b3", "旧正文")), List.of(), false, null,
                List.of(text("b3", "旧正文"))), false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 中断后继续等待释放：排列“发送—取消—仍占用—响应收尾—恢复—释放”。
        when(processor.processBaseline(eq(book.id()), eq(3), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(4, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        assertTrue(release.await(4, TimeUnit.SECONDS));
                    }
                    return readyWith(3, "取消后返回的新文本");
                });
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 3));
        // 直接经准入重试 READY 页，派发阻塞 attempt。
        UUID reservation = reservationOf();
        int rev3 = BookStore.revisionOrZero(store.readPage(book.id(), 3));
        jobs.requestReprocess(reservation, book.id(), 3,
                new PageReprocessRequest(rev3, "op-cancel", false, "paddle-aistudio", false),
                "paddle-aistudio", "auto", false, false);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        jobs.cancelReadingPage(reservation, 3);
        // U2：取消后登记不提前释放（物理槽仍被占用中）。
        assertTrue(jobs.readingJobActive(reservation, 3), "取消后登记保留到 worker 收尾");
        release.countDown();
        // 取消后返回的新内容不发布；旧可读版本恢复，无无主 PROCESSING。
        await(() -> !jobs.readingJobActive(reservation, 3));
        Page restored = store.readPage(book.id(), 3);
        assertNotEquals("PROCESSING", restored.status(), "无无主 PROCESSING");
        assertEquals("READY", restored.status());
        assertEquals("旧正文", restored.blocks().get(0).original(), "恢复不覆盖旧可读版本");
    }

    @Test void safe09_rapidJumpsNeverResubmitInFlightPages() throws Exception {
        setup(12);
        CountDownLatch entered5 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> calls = new CopyOnWriteArrayList<>();
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int n = inv.getArgument(1);
                    calls.add(n);
                    if (n == 5) { entered5.countDown(); assertTrue(release.await(4, TimeUnit.SECONDS)); }
                    return readyWith(n, "t" + n);
                });
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 5));
        clock.advance(Duration.ofSeconds(1));
        windows.tick();
        assertTrue(entered5.await(3, TimeUnit.SECONDS));
        // 连续快速跳页 6 次：只更换待执行队列，不杀在途、不重发。
        for (int i = 0; i < 6; i++) {
            windows.update(book.id(), request(session, 2 + i, 6 + (i % 5)));
            clock.advance(Duration.ofMillis(200));
            windows.tick();
        }
        assertEquals(1, calls.stream().filter(n -> n == 5).count(), "在途页不被反复杀掉重发");
        release.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 5).status()));
    }

    @Test void safe1011_bothConfiguredStillPrimaryOnlyWithoutParallelAuthorization() throws Exception {
        setup(10);
        settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"ppocr\":{\"apiKey\":\"pp-key\",\"secretKey\":\"pp-secret\"}}}"));
        // 中心页已就绪：后台最多占 2 槽，预留 1 槽供跳页；次通道仍未获授权。
        store.writePage(book.id(), new Page(1, 600, 800, "READY", "paddle-aistudio",
                List.of(text("b1", "t")), List.of(), false, null,
                List.of(text("b1", "t"))), false);
        List<String> providers = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    providers.add(inv.getArgument(2));
                    entered.countDown();
                    assertTrue(release.await(4, TimeUnit.SECONDS));
                    return readyWith(inv.getArgument(1), "t");
                });
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 1));
        clock.advance(Duration.ofSeconds(1));
        windows.tick();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(2, providers.size(), "后台不耗尽当前页保留槽");
            assertTrue(providers.stream().allMatch("paddle-aistudio"::equals), "次通道请求数为 0");
        } finally { release.countDown(); }
        // 等待后台写收尾再结束，避免临时目录清理时仍有打开句柄。
        await(() -> "READY".equals(store.readPage(book.id(), 3).status()));
    }

    @Test void safe15_diskFailureNeverReportedAsSuccessAndKeepsOldContent() throws Exception {
        setup(3);
        store.writePage(book.id(), new Page(1, 600, 800, "READY", "paddle-aistudio",
                List.of(text("b1", "旧正文")), List.of(), false, null,
                List.of(text("b1", "旧正文"))), false);
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> readyWith(inv.getArgument(1), "新正文"));
        // 仅最终发布提交失败（磁盘满）：开始标记与恢复路径正常。
        // U4：发布操作现为 JOB_BASELINE（基线）/ JOB_ENHANCEMENT（增强）。
        doAnswer(inv -> {
            CommitOp op = inv.getArgument(5);
            if (op == CommitOp.JOB_COMPLETE || op == CommitOp.JOB_BASELINE
                    || op == CommitOp.JOB_ENHANCEMENT) throw new java.io.IOException("disk full");
            return inv.callRealMethod();
        }).when(store).commitPage(eq(book.id()), any(), anyInt(), any(), any(), any());
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 1));
        clock.advance(Duration.ofSeconds(1));
        windows.tick();
        // 随读路径无批量 job 终态通道：以候选落盘 + 旧内容保留为断言。
        await(() -> store.readCandidate(book.id(), 2) != null);
        // 失败写入不转成功：诊断留在候选 warnings，旧内容仍在。
        Page candidate = store.readCandidate(book.id(), 2);
        assertTrue(candidate.warnings().stream().anyMatch(w -> w.contains("写入失败")),
                "恢复与候选保存须产生可诊断结果");
        Page kept = store.readPage(book.id(), 1);
        assertEquals("旧正文", kept.blocks().get(0).original(), "旧内容仍在");
    }

    @Test void safe16_readyInflightSeparatedFromSuccessMark() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("pom.xml"))) root = root.getParent();
        assertNotNull(root);
        String js = Files.readString(root.resolve("src/main/resources/static/reading-window.js"));
        assertTrue(js.contains("readyInFlight"), "U2：在途集合与成功标记分离");
        int successSet = js.indexOf("seenReady.set(item.pageNumber, `${item.pageNumber}:${page.revision}`)");
        assertTrue(successSet > js.indexOf("api.page(item.bookId"), "U2：成功标记只在 GET 成功后写");
        assertTrue(js.contains("readyInFlight.delete(item.pageNumber)"), "U2：失败/取消清理在途标记");
        assertFalse(js.contains("seenReady.set(pageNumber, key);\n      readyQueue.push"),
                "U2：入队前不再预写成功标记");
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return instant; }
        synchronized void advance(Duration duration) { instant = instant.plus(duration); }
    }
}
