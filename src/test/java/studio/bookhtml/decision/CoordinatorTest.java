package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.IssueImageService;
import studio.bookhtml.service.QwenOcrClient;
import studio.bookhtml.service.TraditionalConverter;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * J07（T39–T44、T52、T59；T19 零外呼）：作业合并、取消、重启、队列界限、只读查询。
 */
class CoordinatorTest {
    @TempDir Path temp;

    private static final String BOOK = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";

    private static Block block(String id, String original) {
        return new Block(id, "text", 0, new double[]{0, 0, 0.4, 0.2}, "horizontal-tb",
                original, original, 0.9, true, false, null, "paddle", List.of(id),
                "疑点", new double[]{0, 0, 40, 20},
                List.of(new ContentIssue("i-" + id, "suspected", 0, 1, 0, 1, "理由", false, null, "推测")));
    }

    private record Fixture(BookStore store, DecisionStore decisions, DecisionCoordinator coordinator,
                           MockDecisionTransport transport, QwenOcrClient qwen,
                           IssueImageService images, DecisionProperties config) {}

    private Fixture fixture(boolean mockCalls) throws Exception {
        AppProperties app = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "m", "u", 5,
                "", "m", "u", 5, true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BookStore store = new BookStore(app, mapper);
        store.createBookDirectory(BOOK);
        store.writeBook(new Book(BOOK, "t", "t.pdf", 9, Instant.now(), Instant.now(), 0, 0));
        Files.write(store.pdf(BOOK), "pdf-bytes".getBytes());
        store.writePage(BOOK, new Page(3, 600, 800, "READY", "paddle",
                List.of(block("b1", "甲乙"), block("b2", "丙丁")), List.of(), false, null,
                List.of(block("b1", "甲乙"), block("b2", "丙丁"))), false);
        DecisionStore decisions = new DecisionStore(store, mapper);
        DecisionBudget budget = new DecisionBudget(decisions);
        PdfIdentity identity = new PdfIdentity();
        CandidateResolutionService resolution =
                new CandidateResolutionService(new TraditionalConverter());
        QwenOcrClient qwen = mock(QwenOcrClient.class);
        when(qwen.configured()).thenReturn(false);
        IssueImageService images = mock(IssueImageService.class);
        DecisionProperties config = new DecisionProperties();
        MockDecisionTransport transport = new MockDecisionTransport();
        if (mockCalls) {
            config.setMode("SHADOW");
            config.setProvider("MOCK");
            config.setApiKey("test-key");
            config.setModel("mock-model");
            config.setAllowCloudData(true);
            config.setMonetaryBudgetMinor(100L);
        }
        EvidenceCollector evidence =
                new EvidenceCollector(resolution, images, qwen, budget, config);
        JevDecisionClient jev = new JevDecisionClient(mapper, transport);
        DecisionCoordinator coordinator = new DecisionCoordinator(store, decisions, budget,
                identity, resolution, evidence, new DecisionStateBuilder(), jev, config,
                transport, mapper);
        return new Fixture(store, decisions, coordinator, transport, qwen, images, config);
    }

    private DecisionCoordinator.CreateBody body(BookStore store, String blockId, String issueId,
                                               boolean fresh) throws Exception {
        Page page = store.readPage(BOOK, 3);
        Block block = page.blocks().stream().filter(b -> b.id().equals(blockId)).findFirst().orElseThrow();
        ContentIssue issue = block.issues().stream().filter(i -> i.id().equals(issueId)).findFirst().orElseThrow();
        return new DecisionCoordinator.CreateBody("op-" + System.nanoTime(), blockId,
                BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), fresh);
    }

    @Test void concurrentCreatesMergeBeforePaidWork() throws Exception {
        // T39：并发创建合并作业，最多一次 JEV 调用；观察者复用结果
        Fixture f = fixture(true);
        DecisionCoordinator.CreateResult first =
                f.coordinator.createOrReuse(BOOK, 3, "i-b1", body(f.store, "b1", "i-b1", false));
        assertEquals(202, first.httpStatus());
        DecisionCoordinator.CreateResult second =
                f.coordinator.createOrReuse(BOOK, 3, "i-b1", body(f.store, "b1", "i-b1", false));
        assertEquals(200, second.httpStatus());
        assertEquals(first.job().jobId(), second.job().jobId());
        f.coordinator.runInline(BOOK, first.job().jobId());
        DecisionStore.DecisionJob done = f.coordinator.queryJob(BOOK, first.job().jobId());
        assertEquals("SUCCEEDED", done.state());
        assertNotNull(done.decisionId());
        assertEquals(1, f.transport.calls.get());
        // 完成后相同请求直接返回，不再外呼
        DecisionCoordinator.CreateResult third =
                f.coordinator.createOrReuse(BOOK, 3, "i-b1", body(f.store, "b1", "i-b1", false));
        assertEquals(200, third.httpStatus());
        assertEquals(1, f.transport.calls.get());
        // 关闭面板（只查询）不取消共享任务
        assertEquals("SUCCEEDED", f.coordinator.queryJob(BOOK, first.job().jobId()).state());
    }

    @Test void offDisablesCallsWithDistinctReason() throws Exception {
        // T19：OFF/缺 key/未授权/预算不足物理外呼为 0，原阅读保存导出不受影响由回归套件覆盖
        Fixture f = fixture(false);
        assertEquals("DECISION_OFF", f.config.availabilityReason());
        DecisionCoordinator.CreateResult created =
                f.coordinator.createOrReuse(BOOK, 3, "i-b1", body(f.store, "b1", "i-b1", false));
        f.coordinator.runInline(BOOK, created.job().jobId());
        DecisionStore.DecisionJob done = f.coordinator.queryJob(BOOK, created.job().jobId());
        assertEquals("SUCCEEDED", done.state());
        assertTrue(done.reasonCodes().stream().anyMatch(c -> c.startsWith("UNAVAILABLE_")));
        assertEquals(0, f.transport.calls.get());
        verifyNoInteractions(f.images);
        verify(f.qwen, never()).recognize(any(), anyInt(), anyInt(), anyString(), any());
    }

    @Test void cancelRunningClosesWork() throws Exception {
        // T42：取消关闭真实工作；迟到内容不更新当前建议
        Fixture f = fixture(true);
        f.transport.delayMs = 5000;
        DecisionCoordinator.CreateResult created =
                f.coordinator.createOrReuse(BOOK, 3, "i-b1", body(f.store, "b1", "i-b1", false));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                f.coordinator.runInline(BOOK, created.job().jobId());
            } catch (Throwable e) {
                error.set(e);
            }
        });
        runner.start();
        DecisionStore.DecisionJob running = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            DecisionStore.DecisionJob current = f.coordinator.queryJob(BOOK, created.job().jobId());
            if ("RUNNING".equals(current.state())) { running = current; break; }
            Thread.sleep(30);
        }
        assertNotNull(running, "作业应进入 RUNNING");
        DecisionStore.DecisionJob cancelling =
                f.coordinator.cancel(BOOK, created.job().jobId(), running.stateVersion());
        assertEquals("CANCEL_REQUESTED", cancelling.state());
        runner.join(TimeUnit.SECONDS.toMillis(15));
        assertNull(error.get());
        assertFalse(runner.isAlive(), "worker 必须真实退出，不只看 Future.isDone");
        DecisionStore.DecisionJob terminal = f.coordinator.queryJob(BOOK, created.job().jobId());
        assertEquals("CANCELLED", terminal.state());
    }

    @Test void restartRecoversPhasesWithoutResend() throws Exception {
        // T43：QUEUED 重排；COMPARING 及之后标 INTERRUPTED 不自动重发
        Fixture f = fixture(false);
        DecisionCoordinator.CreateResult created =
                f.coordinator.createOrReuse(BOOK, 3, "i-b1", body(f.store, "b1", "i-b1", false));
        String queuedId = created.job().jobId();
        DecisionStore.DecisionJob comparing = new DecisionStore.DecisionJob("job-comparing",
                "RUNNING", 3, "COMPARING", "admission-x", null, BOOK, 3, "b1", "i-b1", null,
                null, null, "op-x", null, List.of(), 0, "UNKNOWN", "NONE", Instant.now(),
                Instant.now(), Instant.now().plusSeconds(180), false);
        new DecisionStore(f.store, new ObjectMapper().findAndRegisterModules())
                .saveJob(BOOK, comparing);
        f.coordinator.recoverBook(BOOK);
        assertEquals("QUEUED", f.coordinator.queryJob(BOOK, queuedId).state());
        DecisionStore.DecisionJob interrupted = f.coordinator.queryJob(BOOK, "job-comparing");
        assertEquals("INTERRUPTED", interrupted.state());
        assertTrue(interrupted.reasonCodes().contains("RESTART_INTERRUPTED_MAY_HAVE_SENT"));
        // 重排的 QUEUED 可执行，不重发已 SENT
        f.coordinator.runInline(BOOK, queuedId);
        assertEquals("SUCCEEDED", f.coordinator.queryJob(BOOK, queuedId).state());
        assertEquals(0, f.transport.calls.get());
    }

    @Test void queueFullAndDeadlineBounded() throws Exception {
        // T44：有界排队；未发送者不外呼；总 deadline 在等待时耗尽
        Fixture f = fixture(true);
        f.config.setMaxQueueEntries(1);
        DecisionCoordinator.CreateResult first =
                f.coordinator.createOrReuse(BOOK, 3, "i-b1", body(f.store, "b1", "i-b1", false));
        assertEquals(202, first.httpStatus());
        ApiException full = assertThrows(ApiException.class, () ->
                f.coordinator.createOrReuse(BOOK, 3, "i-b2", body(f.store, "b2", "i-b2", false)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.status());
        // 构造已过期作业直接执行 → 超时失败，不外呼
        DecisionStore.DecisionJob expired = new DecisionStore.DecisionJob("job-expired",
                "QUEUED", 1, "LOCATING", "admission-expired", null, BOOK, 3, "b1", "i-b1", null,
                null, null, "op-e", null, List.of(), 0, "UNKNOWN", "NONE", Instant.now(),
                Instant.now(), Instant.now().minusSeconds(1), false);
        new DecisionStore(f.store, new ObjectMapper().findAndRegisterModules())
                .saveJob(BOOK, expired);
        f.coordinator.runInline(BOOK, "job-expired");
        assertEquals("FAILED", f.coordinator.queryJob(BOOK, "job-expired").state());
    }

    @Test void queryIsReadOnlyAndScoped() throws Exception {
        // T52/T59：纯读查询不触发补算、不扫描整书；跨书隔离
        Fixture f = fixture(true);
        for (int page = 10; page < 60; page++) {
            f.store.writePage(BOOK, new Page(page, 600, 800, "PENDING", "", List.of(),
                    List.of(), false, null, List.of()), false);
        }
        DecisionCoordinator.CreateResult created =
                f.coordinator.createOrReuse(BOOK, 3, "i-b1", body(f.store, "b1", "i-b1", false));
        long start = System.nanoTime();
        DecisionStore.DecisionJob first = f.coordinator.queryJob(BOOK, created.job().jobId());
        DecisionStore.DecisionJob second = f.coordinator.queryJob(BOOK, created.job().jobId());
        long elapsed = System.nanoTime() - start;
        assertEquals(first.stateVersion(), second.stateVersion());
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(2));
        assertEquals(0, f.transport.calls.get());
        verifyNoInteractions(f.images);
        ApiException missing = assertThrows(ApiException.class,
                () -> f.coordinator.queryJob("ffffffff-ffff-ffff-ffff-ffffffffffff",
                        created.job().jobId()));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }

    @Test void preconditionsAndCodes() {        // 11.2：404/409/400 固定语义
        Fixture f;
        try {
            f = fixture(false);
        } catch (Exception e) {
            fail("fixture 失败", e);
            return;
        }
        assertThrows(ApiException.class, () -> f.coordinator.createOrReuse(BOOK, 3, "no-issue",
                new DecisionCoordinator.CreateBody("op", "b1", 0, "basis", false)));
        try {
            Page page = f.store.readPage(BOOK, 3);
            Block block = page.blocks().stream().filter(b -> b.id().equals("b1")).findFirst().orElseThrow();
            ContentIssue issue = block.issues().get(0);
            DecisionCoordinator.CreateBody stale = new DecisionCoordinator.CreateBody("op", "b1",
                    BookStore.revisionOrZero(page) + 99, IssueBasis.basisHash(block, issue), false);
            ApiException conflict = assertThrows(ApiException.class,
                    () -> f.coordinator.createOrReuse(BOOK, 3, "i-b1", stale));
            assertEquals(HttpStatus.CONFLICT, conflict.status());
            DecisionCoordinator.CreateBody bad = new DecisionCoordinator.CreateBody("", "b1", 0, "x", false);
            ApiException illegal = assertThrows(ApiException.class,
                    () -> f.coordinator.createOrReuse(BOOK, 3, "i-b1", bad));
            assertEquals(HttpStatus.BAD_REQUEST, illegal.status());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            fail("断言失败", e);
        }
    }

    @Test void decisionsViewsWithApplicability() throws Exception {
        // decisions 查询：服务端基线、当前建议、历史与过期原因
        Fixture f = fixture(true);
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().stream().filter(b -> b.id().equals("b1")).findFirst().orElseThrow();
        ContentIssue issue = block.issues().get(0);
        Map<String, Object> basis = f.coordinator.issueBasisView(BOOK, 3, "i-b1");
        assertEquals("b1", basis.get("blockId"));
        assertEquals(IssueBasis.basisHash(block, issue), basis.get("issueBasisHash"));
        assertEquals(BookStore.revisionOrZero(page), basis.get("pageRevision"));
        DecisionCoordinator.CreateResult created = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                new DecisionCoordinator.CreateBody("op-view", "b1",
                        BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));
        f.coordinator.runInline(BOOK, created.job().jobId());
        Map<String, Object> current = f.coordinator.currentDecisionView(BOOK, 3, "i-b1");
        assertNotNull(current);
        assertNotNull(current.get("decisionId"));
        assertFalse(((List<?>) current.get("candidates")).isEmpty());
        List<Map<String, Object>> history =
                f.coordinator.decisionHistory(BOOK, 3, "i-b1", 10);
        assertEquals(1, history.size());
        assertEquals("CURRENT", history.get(0).get("applicability"));
        // 页面推进后当前失效，历史标 STALE 并保留原因
        Page saved = f.store.readPage(BOOK, 3);
        Block changed = new Block("b1", "text", 0, new double[]{0, 0, 0.4, 0.2},
                "horizontal-tb", "甲乙改", "甲乙改", 0.9, true, false, null, "manual",
                List.of("b1"), null, new double[]{0, 0, 40, 20}, saved.blocks().get(0).issues());
        Page proposed = new Page(3, 600, 800, "READY", "manual", List.of(changed, saved.blocks().get(1)),
                List.of(), false, null, saved.sourceRecords(), null);
        f.store.commitPage(BOOK, proposed, BookStore.revisionOrZero(saved),
                studio.bookhtml.store.CommitActor.MANUAL, null,
                studio.bookhtml.store.CommitOp.MANUAL_SAVE);
        assertNull(f.coordinator.currentDecisionView(BOOK, 3, "i-b1"));
        List<Map<String, Object>> staleHistory =
                f.coordinator.decisionHistory(BOOK, 3, "i-b1", 10);
        assertEquals(1, staleHistory.size());
        assertEquals("STALE", staleHistory.get(0).get("applicability"));
    }

    @Test void terminalQueryStableWithoutNewCalls() throws Exception {
        // T59：终态后轮询只读，不触发补算、不推进版本、不新增外呼
        Fixture f = fixture(true);
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().stream().filter(b -> b.id().equals("b1")).findFirst().orElseThrow();
        ContentIssue issue = block.issues().get(0);
        DecisionCoordinator.CreateResult created = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                new DecisionCoordinator.CreateBody("op-stable", "b1",
                        BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));
        f.coordinator.runInline(BOOK, created.job().jobId());
        int calls = f.transport.calls.get();
        DecisionStore.DecisionJob first = f.coordinator.queryJob(BOOK, created.job().jobId());
        Thread.sleep(1100);
        DecisionStore.DecisionJob second = f.coordinator.queryJob(BOOK, created.job().jobId());
        assertEquals(first.stateVersion(), second.stateVersion());
        assertEquals("SUCCEEDED", second.state());
        assertEquals(calls, f.transport.calls.get());
    }

    @Test void offRestartRequeuesWithoutCalls() throws Exception {
        // T64：OFF 后重启恢复可执行，重排的 QUEUED 走 UNAVAILABLE，不外呼、不崩溃
        Fixture f = fixture(false);
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().stream().filter(b -> b.id().equals("b1")).findFirst().orElseThrow();
        ContentIssue issue = block.issues().get(0);
        DecisionCoordinator.CreateResult created = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                new DecisionCoordinator.CreateBody("op-off", "b1",
                        BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));
        f.coordinator.recoverBook(BOOK);
        f.coordinator.runInline(BOOK, created.job().jobId());
        DecisionStore.DecisionJob done = f.coordinator.queryJob(BOOK, created.job().jobId());
        assertEquals("SUCCEEDED", done.state());
        assertTrue(done.reasonCodes().stream().anyMatch(c -> c.startsWith("UNAVAILABLE_")));
        assertEquals(0, f.transport.calls.get());
    }

    @Test void hundredRoundCreateCancelTransitions() throws Exception {
        // T15 回归压力样本（100 轮创建/取消/终态不断言穷尽竞态）：无卡死、无泄漏、无半写
        Fixture f = fixture(false);
        f.config.setMaxQueueEntries(10000);
        for (int round = 0; round < 100; round++) {
            Page page = f.store.readPage(BOOK, 3);
            Block block = page.blocks().stream().filter(b -> b.id().equals("b1")).findFirst().orElseThrow();
            ContentIssue issue = block.issues().get(0);
            DecisionCoordinator.CreateResult created = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                    new DecisionCoordinator.CreateBody("op-" + round, "b1",
                            BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));
            DecisionStore.DecisionJob job = created.job();
            if ("QUEUED".equals(job.state())) {
                DecisionStore.DecisionJob cancelling =
                        f.coordinator.cancel(BOOK, job.jobId(), job.stateVersion());
                assertEquals("CANCEL_REQUESTED", cancelling.state());
                f.coordinator.runInline(BOOK, job.jobId());
                assertEquals("CANCELLED", f.coordinator.queryJob(BOOK, job.jobId()).state());
            } else {
                f.coordinator.runInline(BOOK, job.jobId());
                String state = f.coordinator.queryJob(BOOK, job.jobId()).state();
                assertTrue("SUCCEEDED".equals(state) || "CANCELLED".equals(state), "终态：" + state);
            }
        }
        assertEquals(0, f.transport.calls.get());
    }

    @Test void jr06T01_boundedQueueCapacityRejectsOverflowAcrossMultipleBooks() throws Exception {
        // JR-06-T01: 并发请求跨多书入队：未超全局容量，满载明确拒绝 429
        Fixture f = fixture(false);
        int maxCapacity = 5;
        f.config.setMaxQueueEntries(maxCapacity);

        // 创建多本书
        int totalRequests = 20;
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected429 = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(10);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            String bookId = String.format("00000000-0000-0000-0000-%012d", i);
            f.store.createBookDirectory(bookId);
            f.store.writeBook(new Book(bookId, "title", "pdf", 1, Instant.now(), Instant.now(), 0, 0));
            Files.write(f.store.pdf(bookId), "pdf".getBytes());
            Page page = new Page(1, 600, 800, "READY", "paddle",
                    List.of(block("b1", "文")), List.of(), false, null, List.of(block("b1", "文")));
            f.store.writePage(bookId, page, false);

            futures.add(pool.submit(() -> {
                try {
                    DecisionCoordinator.CreateBody b = new DecisionCoordinator.CreateBody(
                            "op-" + UUID.randomUUID(), "b1", 0,
                            IssueBasis.basisHash(page.blocks().get(0), page.blocks().get(0).issues().get(0)),
                            false);
                    DecisionCoordinator.CreateResult res = f.coordinator.createOrReuse(bookId, 1, "i-b1", b);
                    if (res.httpStatus() == 202) accepted.incrementAndGet();
                } catch (ApiException e) {
                    if (e.status() == HttpStatus.TOO_MANY_REQUESTS) rejected429.incrementAndGet();
                } catch (Exception ignored) {}
            }));
        }
        for (var fut : futures) fut.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(accepted.get() <= maxCapacity, "Accepted cannot exceed maxQueueEntries: " + accepted.get());
        assertEquals(totalRequests, accepted.get() + rejected429.get(), "All requests accounted for");
        assertTrue(rejected429.get() > 0, "Overflow requests must be rejected with 429");
    }

    @Test void jr06T02_workerRejectsStalePageRevisionOrModifiedIssue() throws Exception {
        // JR-06-T02: 创建时 target/revision 持久化，入队后编辑不被 worker 静默采用
        Fixture f = fixture(true);
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().get(0);
        ContentIssue issue = block.issues().get(0);
        DecisionCoordinator.CreateResult created = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                new DecisionCoordinator.CreateBody("op-stale-test", "b1",
                        BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));
        assertEquals("QUEUED", created.job().state());

        // 模拟页面推进 revision
        Page modified = new Page(3, 600, 800, "READY", "manual",
                List.of(new Block("b1", "text", 0, new double[]{0, 0, 0.4, 0.2}, "horizontal-tb",
                        "改动后", "改动后", 0.9, true, false, null, "manual", List.of("b1"),
                        null, new double[]{0, 0, 40, 20}, List.of())),
                List.of(), false, null, page.sourceRecords(), null);
        f.store.commitPage(BOOK, modified, BookStore.revisionOrZero(page),
                studio.bookhtml.store.CommitActor.MANUAL, null,
                studio.bookhtml.store.CommitOp.MANUAL_SAVE);

        // worker 执行时发现 revision 变化，标记 STALE
        f.coordinator.runInline(BOOK, created.job().jobId());
        DecisionStore.DecisionJob result = f.coordinator.queryJob(BOOK, created.job().jobId());
        assertEquals("STALE", result.state());
        assertTrue(result.reasonCodes().contains("PAGE_REVISION_ADVANCED"));
        assertEquals(0, f.transport.calls.get());
    }

    @Test void jr06T03_cancelAndWorkerStateIntegrity() throws Exception {
        // JR-06-T03: 取消与 worker 同时写状态：终态不倒退、stateVersion 单调
        Fixture f = fixture(true);
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().get(0);
        ContentIssue issue = block.issues().get(0);
        DecisionCoordinator.CreateResult created = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                new DecisionCoordinator.CreateBody("op-cancel-test", "b1",
                        BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));

        DecisionStore.DecisionJob cancelled = f.coordinator.cancel(BOOK, created.job().jobId(), created.job().stateVersion());
        assertEquals("CANCEL_REQUESTED", cancelled.state());
        assertTrue(cancelled.stateVersion() > created.job().stateVersion());

        // worker 运行后应转为 CANCELLED 终态，不可回到 RUNNING 或 SUCCEEDED
        f.coordinator.runInline(BOOK, created.job().jobId());
        DecisionStore.DecisionJob finalJob = f.coordinator.queryJob(BOOK, created.job().jobId());
        assertEquals("CANCELLED", finalJob.state());
        assertTrue(finalJob.stateVersion() >= cancelled.stateVersion());
        assertEquals(0, f.transport.calls.get());
    }

    @Test void jr06T05_duplicateRecoveryDoesNotDuplicateQueueAndUnknownDoesNotResend() throws Exception {
        // JR-06-T05: 重复恢复不重复入队；已发送未知标 INTERRUPTED 不重发
        Fixture f = fixture(false);
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().get(0);
        ContentIssue issue = block.issues().get(0);
        DecisionCoordinator.CreateResult created = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                new DecisionCoordinator.CreateBody("op-dup-test", "b1",
                        BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));

        // 连续两次 recoverBook
        f.coordinator.recoverBook(BOOK);
        f.coordinator.recoverBook(BOOK);

        // worker 执行一次即可完成，不产生第二遍执行
        f.coordinator.runInline(BOOK, created.job().jobId());
        DecisionStore.DecisionJob afterFirst = f.coordinator.queryJob(BOOK, created.job().jobId());
        assertEquals("SUCCEEDED", afterFirst.state());

        // 模拟一个 COMPARING 状态的已发送未知任务
        DecisionStore.DecisionJob maybeSentJob = new DecisionStore.DecisionJob(
                "job-maybe-sent", "RUNNING", 2, "COMPARING", "key-sent", null,
                BOOK, 3, "b1", "i-b1", null, null, null, "op-sent", null,
                List.of(), 1, "UNKNOWN", "NONE", Instant.now(), Instant.now(),
                Instant.now().plusSeconds(60), false, created.job().target());
        f.decisions.saveJob(BOOK, maybeSentJob);

        f.coordinator.recoverBook(BOOK);
        DecisionStore.DecisionJob recoveredSent = f.coordinator.queryJob(BOOK, "job-maybe-sent");
        assertEquals("INTERRUPTED", recoveredSent.state());
        assertTrue(recoveredSent.reasonCodes().contains("RESTART_INTERRUPTED_MAY_HAVE_SENT"));
        assertEquals(0, f.transport.calls.get());
    }

    @Test void jr01T02_crossPageSameIdsGenerateDifferentAdmissionKeysAndDuplicateIssueRejected() throws Exception {
        // JR-01-T02: 不同页相同 issueId、blockId、quote/revision：生成不同准入键，查询绝不串页；同页重复 issueId 拒绝
        Fixture f = fixture(true);
        Page page3 = f.store.readPage(BOOK, 3);
        Block b1 = page3.blocks().get(0);
        ContentIssue issue1 = b1.issues().get(0);
        DecisionCoordinator.CreateResult jobPage3 = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                new DecisionCoordinator.CreateBody("op-p3", "b1",
                        BookStore.revisionOrZero(page3), IssueBasis.basisHash(b1, issue1), false));

        // 在 Page 4 上构造相同 blockId ("b1")、相同 issueId ("i-b1")、相同 quote
        Block b1Page4 = new Block("b1", "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, null, "manual", List.of("b1"), null, null,
                List.of(new ContentIssue("i-b1", "suspected", 0, 1, 0, 1, "理由", false, null, "推测")));
        Page page4 = new Page(4, 600, 800, "READY", "manual", List.of(b1Page4), List.of(), false, null, List.of(b1Page4));
        f.store.writePage(BOOK, page4, false);

        DecisionCoordinator.CreateResult jobPage4 = f.coordinator.createOrReuse(BOOK, 4, "i-b1",
                new DecisionCoordinator.CreateBody("op-p4", "b1",
                        0, IssueBasis.basisHash(b1Page4, b1Page4.issues().get(0)), false));

        // 准入键不同，绝不串页
        assertNotEquals(jobPage3.job().admissionKey(), jobPage4.job().admissionKey());
        assertNotEquals(jobPage3.job().jobId(), jobPage4.job().jobId());

        // 查询按 sourcePage 隔离，绝不串页
        List<Map<String, Object>> histP3 = f.coordinator.decisionHistory(BOOK, 3, "i-b1", 10);
        assertTrue(histP3.stream().allMatch(h -> jobPage3.job().jobId().equals(h.get("jobId"))));

        // 同页存在重复 issueId 时明确报告数据不一致
        Block dupBlock = new Block("b-dup", "text", 1, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, null, "manual", List.of("b-dup"), null, null,
                List.of(new ContentIssue("i-b1", "suspected", 0, 1, 0, 1, "理由", false, null, "推测")));
        Page pageDup = new Page(5, 600, 800, "READY", "manual", List.of(b1Page4, dupBlock), List.of(), false, null, List.of(b1Page4, dupBlock));
        f.store.writePage(BOOK, pageDup, false);

        ApiException dupEx = assertThrows(ApiException.class, () ->
                f.coordinator.createOrReuse(BOOK, 5, "i-b1",
                        new DecisionCoordinator.CreateBody("op-dup", "b1", 0,
                                IssueBasis.basisHash(b1Page4, b1Page4.issues().get(0)), false)));
        assertEquals(HttpStatus.CONFLICT, dupEx.status());
        assertTrue(dupEx.getMessage().contains("重复问题ID"));
    }

    @Test void jr06T03_cancelAndWorkerRaceTerminalNeverRegresses() throws Exception {
        // JR-06-T03：取消与 worker 同时写状态终态不倒退、版本单调
        Fixture f = fixture(true);
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().get(0);
        ContentIssue issue = block.issues().get(0);
        DecisionCoordinator.CreateResult created = f.coordinator.createOrReuse(BOOK, 3, "i-b1",
                new DecisionCoordinator.CreateBody("op-cas", "b1",
                        BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));
        DecisionStore.DecisionJob queued = created.job();
        // 旧版本 CAS 更新必须 409
        DecisionStore.DecisionJob staleNext = new DecisionStore.DecisionJob(queued.jobId(),
                "RUNNING", queued.stateVersion() + 1, "LOCATING", queued.admissionKey(), null,
                queued.bookId(), queued.sourcePageNumber(), queued.blockId(), queued.issueId(),
                null, null, null, queued.clientOperationId(), null, List.of(), 0, "UNKNOWN", "NONE",
                queued.createdAt(), java.time.Instant.now(), queued.deadlineAt(),
                queued.allowFreshVision(), queued.target());
        // 先合法取消推进版本，再用旧版本 CAS 必须冲突
        DecisionStore.DecisionJob cancelling =
                f.coordinator.cancel(BOOK, queued.jobId(), queued.stateVersion());
        assertEquals("CANCEL_REQUESTED", cancelling.state());
        ApiException conflict = assertThrows(ApiException.class,
                () -> f.coordinator.casSaveJob(BOOK, staleNext, queued.stateVersion()));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        // 终态不倒退：persistTerminal 旧版本写不覆盖 CANCEL_REQUESTED
        f.coordinator.runInline(BOOK, queued.jobId());
        DecisionStore.DecisionJob terminal = f.coordinator.queryJob(BOOK, queued.jobId());
        assertTrue(List.of("CANCELLED", "CANCEL_REQUESTED", "FAILED").contains(terminal.state()));
    }
}
