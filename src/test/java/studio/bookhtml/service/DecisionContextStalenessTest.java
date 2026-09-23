package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.decision.*;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.ContextSnapshot;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.PageConflictException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G08 / B07: JEV 接受锁内依赖校验与上下文时效性测试。
 * 验证父计划/评审子计划严格绑定、页面文本漂移、版本推进与目录写锁内依赖防并发覆盖。
 */
class DecisionContextStalenessTest {

    @TempDir
    Path tempDir;

    private static final String BOOK = "11111111-2222-3333-4444-555555555555";
    private BookStore store;
    private DecisionStore decisions;
    private DecisionAcceptService acceptService;
    private DecisionCoordinator coordinator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties app = TestConfigs.config(tempDir, "", "");
        mapper = new ObjectMapper().findAndRegisterModules();
        store = new BookStore(app, mapper);
        store.createBookDirectory(BOOK);
        store.writeBook(new Book(BOOK, "测试典籍", "test.pdf", 10, Instant.now(), Instant.now(), 0, 0));
        Files.write(store.pdf(BOOK), "pdf-bytes-for-staleness-test".getBytes());

        ContentIssue issue1 = new ContentIssue("i1", "suspected", 0, 1, 0, 1, "理由", false, null, "推测");
        Block b1 = new Block("b1", "text", 0, new double[]{0, 0, 0.4, 0.2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, true, false, null, "test", List.of("b1"),
                "疑点", new double[]{0, 0, 40, 20}, List.of(issue1));
        store.writePage(BOOK, new Page(1, 600, 800, "READY", "test", List.of(b1),
                List.of(), false, null, List.of(b1)), false);

        decisions = new DecisionStore(store, mapper);
        TraditionalConverter converter = new TraditionalConverter();
        DecisionProperties config = new DecisionProperties();
        config.setMode("ASSIST");
        config.setProvider("MOCK");
        config.setApiKey("test-key");
        config.setModel("mock-model");
        config.setAllowCloudData(true);
        config.setMonetaryBudgetMinor(100L);

        MockDecisionTransport transport = new MockDecisionTransport();
        acceptService = new DecisionAcceptService(store, decisions, converter, new PdfIdentity(), config);

        DecisionBudget budget = new DecisionBudget(decisions);
        CandidateResolutionService resolution = new CandidateResolutionService(converter);
        QwenOcrClient qwen = mock(QwenOcrClient.class);
        when(qwen.configured()).thenReturn(false);
        IssueImageService images = mock(IssueImageService.class);
        EvidenceCollector evidenceCollector = new EvidenceCollector(resolution, images, qwen, budget, config);
        JevDecisionClient jev = new JevDecisionClient(mapper, transport);
        coordinator = new DecisionCoordinator(store, decisions, budget, new PdfIdentity(),
                resolution, evidenceCollector, new DecisionStateBuilder(), jev, config, transport, mapper);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private record PreparedDecision(String decisionId, String candidateId, String issueBasisHash, String candidateSetHash) {}

    private PreparedDecision createPreparedDecision() throws Exception {
        Page page = store.readPage(BOOK, 1);
        Block block = page.blocks().get(0);
        ContentIssue issue = block.issues().get(0);
        String basisHash = IssueBasis.basisHash(block, issue);

        DecisionCoordinator.CreateResult created = coordinator.createOrReuse(BOOK, 1, "i1",
                new DecisionCoordinator.CreateBody("op-prepare-1", "b1",
                        BookStore.revisionOrZero(page), basisHash, false));
        coordinator.runInline(BOOK, created.job().jobId());
        DecisionStore.DecisionJob job = coordinator.queryJob(BOOK, created.job().jobId());
        assertEquals("SUCCEEDED", job.state());

        DecisionModels.DecisionEvidence evidence = decisions.loadResult(BOOK, job.decisionId()).orElseThrow();
        DecisionModels.CandidateSet candidateSet = decisions.loadCandidateSet(BOOK, evidence.candidateSetHash()).orElseThrow();
        String candidateId = candidateSet.candidates().get(0).candidateId();

        return new PreparedDecision(job.decisionId(), candidateId, basisHash, evidence.candidateSetHash());
    }

    @Test
    void acceptSucceedsWhenContextSnapshotAndPlanMatch() throws Exception {
        PreparedDecision prep = createPreparedDecision();
        Page page = store.readPage(BOOK, 1);
        Block block = page.blocks().get(0);

        String parentPlan = "parent-plan-001";
        String reviewPlan = "review-sub-001";
        long curSeq = store.sourceJournal().currentSourceSeq(store.bookDir(BOOK), BOOK);

        ContextSnapshot snapshot = ContextSnapshot.create(
                BOOK, 1, BookStore.revisionOrZero(page), parentPlan, reviewPlan,
                curSeq, "b1", 0, 1, block.original(), "上下文"
        );
        decisions.saveContextSnapshot(BOOK, snapshot);

        DecisionAcceptService.AcceptBody body = new DecisionAcceptService.AcceptBody(
                "op-accept-ok", "b1", BookStore.revisionOrZero(page), prep.issueBasisHash(),
                prep.candidateSetHash(), prep.candidateId(), true,
                parentPlan, reviewPlan, snapshot.snapshotId()
        );

        DecisionAcceptService.AcceptResult result = acceptService.accept(BOOK, 1, "i1", prep.decisionId(), body);
        assertNotNull(result);
        assertEquals(1, BookStore.revisionOrZero(result.committed()));

        Page updated = store.readPage(BOOK, 1);
        assertEquals(1, BookStore.revisionOrZero(updated));
        ContentIssue resolvedIssue = updated.blocks().get(0).issues().get(0);
        assertTrue(resolvedIssue.resolved());
        assertNotNull(resolvedIssue.resolution());
    }

    @Test
    void rejectsWhenParentOrReviewPlanMismatch() throws Exception {
        PreparedDecision prep = createPreparedDecision();
        Page page = store.readPage(BOOK, 1);
        Block block = page.blocks().get(0);

        String parentPlan = "parent-plan-001";
        String reviewPlan = "review-sub-001";
        long curSeq = store.sourceJournal().currentSourceSeq(store.bookDir(BOOK), BOOK);

        ContextSnapshot snapshot = ContextSnapshot.create(
                BOOK, 1, BookStore.revisionOrZero(page), parentPlan, reviewPlan,
                curSeq, "b1", 0, 1, block.original(), "上下文"
        );
        decisions.saveContextSnapshot(BOOK, snapshot);

        // 1. 父计划哈希不匹配
        DecisionAcceptService.AcceptBody badParentBody = new DecisionAcceptService.AcceptBody(
                "op-bad-parent", "b1", BookStore.revisionOrZero(page), prep.issueBasisHash(),
                prep.candidateSetHash(), prep.candidateId(), true,
                "wrong-parent-plan", reviewPlan, snapshot.snapshotId()
        );
        ApiException ex1 = assertThrows(ApiException.class, () ->
                acceptService.accept(BOOK, 1, "i1", prep.decisionId(), badParentBody));
        assertEquals(HttpStatus.CONFLICT, ex1.status());
        assertTrue(ex1.getMessage().contains("父计划哈希不匹配"));

        // 2. 评审子计划哈希不匹配
        DecisionAcceptService.AcceptBody badReviewBody = new DecisionAcceptService.AcceptBody(
                "op-bad-review", "b1", BookStore.revisionOrZero(page), prep.issueBasisHash(),
                prep.candidateSetHash(), prep.candidateId(), true,
                parentPlan, "wrong-review-plan", snapshot.snapshotId()
        );
        ApiException ex2 = assertThrows(ApiException.class, () ->
                acceptService.accept(BOOK, 1, "i1", prep.decisionId(), badReviewBody));
        assertEquals(HttpStatus.CONFLICT, ex2.status());
        assertTrue(ex2.getMessage().contains("评审计划哈希不匹配"));
    }

    @Test
    void rejectsWhenSurroundingTextOrSpanDrifts() throws Exception {
        PreparedDecision prep = createPreparedDecision();
        Page page = store.readPage(BOOK, 1);
        Block block = page.blocks().get(0);

        String parentPlan = "parent-plan-001";
        String reviewPlan = "review-sub-001";
        long curSeq = store.sourceJournal().currentSourceSeq(store.bookDir(BOOK), BOOK);

        // 创建基于原文字符 "甲" (0..1) 的快照
        ContextSnapshot snapshot = ContextSnapshot.create(
                BOOK, 1, BookStore.revisionOrZero(page), parentPlan, reviewPlan,
                curSeq, "b1", 0, 1, block.original(), "上下文"
        );
        decisions.saveContextSnapshot(BOOK, snapshot);

        // 模拟外部并发修改了块原文（例如从 "甲乙" 改为 "丙乙"，原字改变）
        Block modifiedBlock = new Block("b1", block.type(), block.order(), block.bbox(),
                block.writingMode(), "丙乙", "丙乙", block.confidence(),
                block.uncertain(), block.reviewed(), block.headingLevel(), block.source(),
                block.sourceIds(), block.suggestion(), block.sourceRect(), block.issues());
        Page modifiedPage = new Page(1, page.width(), page.height(), page.status(), page.provider(),
                List.of(modifiedBlock), page.warnings(), false, null, List.of(modifiedBlock));
        store.writePage(BOOK, modifiedPage, false);

        DecisionAcceptService.AcceptBody body = new DecisionAcceptService.AcceptBody(
                "op-drift", "b1", BookStore.revisionOrZero(modifiedPage), prep.issueBasisHash(),
                prep.candidateSetHash(), prep.candidateId(), true,
                parentPlan, reviewPlan, snapshot.snapshotId()
        );

        assertThrows(PageConflictException.class, () ->
                acceptService.accept(BOOK, 1, "i1", prep.decisionId(), body));
    }

    @Test
    void rejectsWhenPageRevisionAdvances() throws Exception {
        PreparedDecision prep = createPreparedDecision();
        Page page = store.readPage(BOOK, 1);
        Block block = page.blocks().get(0);

        String parentPlan = "parent-plan-001";
        String reviewPlan = "review-sub-001";
        long curSeq = store.sourceJournal().currentSourceSeq(store.bookDir(BOOK), BOOK);

        ContextSnapshot snapshot = ContextSnapshot.create(
                BOOK, 1, BookStore.revisionOrZero(page), parentPlan, reviewPlan,
                curSeq, "b1", 0, 1, block.original(), "上下文"
        );
        decisions.saveContextSnapshot(BOOK, snapshot);

        // 推进页面版本（例如普通人工保存导致 revision 递增）
        store.writePage(BOOK, page, true);
        Page advanced = store.readPage(BOOK, 1);
        assertTrue(BookStore.revisionOrZero(advanced) > 0);

        // 传入旧 revision 执行接受
        DecisionAcceptService.AcceptBody body = new DecisionAcceptService.AcceptBody(
                "op-stale-rev", "b1", BookStore.revisionOrZero(page), prep.issueBasisHash(),
                prep.candidateSetHash(), prep.candidateId(), true,
                parentPlan, reviewPlan, snapshot.snapshotId()
        );

        assertThrows(PageConflictException.class, () ->
                acceptService.accept(BOOK, 1, "i1", prep.decisionId(), body));
    }

    @Test
    void lockInTOCTOUProtectionRejectsStaleSpecDirectlyInStore() throws Exception {
        Page page = store.readPage(BOOK, 1);
        Block block = page.blocks().get(0);
        ContentIssue issue = block.issues().get(0);
        String basisHash = IssueBasis.basisHash(block, issue);

        // 构造一个 targetText 与当前页面不一致的 ContextSnapshot
        ContextSnapshot staleSnapshot = new ContextSnapshot(
                "snap-stale", BOOK, 1, 0, "parent-plan", "review-plan", 1L,
                "b1", 0, 1, 1, 3, 1024,
                "错", "上下文",
                ContextSnapshot.computeContextHash(BOOK, 1, 0, "parent-plan", "review-plan", 1L,
                        "b1", 0, 1, "错", "上下文"),
                Instant.now()
        );

        BookStore.IssueAcceptSpec spec = new BookStore.IssueAcceptSpec(
                "b1", "i1", basisHash, "cs-hash", "c1",
                "甲", "甲", "v1", "op-direct-lock", "d1", null,
                "parent-plan", "review-plan", staleSnapshot.contextHash(), staleSnapshot
        );

        // 直接在 BookStore.applyIssueResolution 中调用（目录写锁内），断言触发 PageConflictException
        PageConflictException ex = assertThrows(PageConflictException.class, () ->
                store.applyIssueResolution(BOOK, 1, 0, studio.bookhtml.store.CommitActor.MANUAL,
                        null, studio.bookhtml.store.CommitOp.MANUAL_SAVE, spec));
        assertTrue(ex.getMessage().contains("JEV建议依赖的上下文在提交锁内已失效或发生并发冲突"));
    }
}
