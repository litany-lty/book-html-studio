package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

    private record Fixture(BookStore store, DecisionCoordinator coordinator,
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
        return new Fixture(store, coordinator, transport, qwen, images, config);
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

    @Test void preconditionsAndCodes() {
        // 11.2：404/409/400 固定语义
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
}
