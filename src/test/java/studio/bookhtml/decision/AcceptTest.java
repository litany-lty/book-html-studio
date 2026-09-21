package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import studio.bookhtml.service.JobService;
import studio.bookhtml.service.QwenOcrClient;
import studio.bookhtml.service.TraditionalConverter;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.CommitOp;
import studio.bookhtml.store.PageConflictException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * J09（T55–T58；T57 落盘失败）：接受与人工保存/OCR 任务竞争、幂等读回、元数据往返。
 */
class AcceptTest {
    @TempDir Path temp;

    private static final String BOOK = "ffffffff-ffff-ffff-ffff-ffffffffffff";

    private static Block block(String id, String original, List<ContentIssue> issues) {
        return new Block(id, "text", 0, new double[]{0, 0, 0.4, 0.2}, "horizontal-tb",
                original, original, 0.9, true, false, null, "paddle", List.of(id),
                "疑点", new double[]{0, 0, 40, 20}, issues);
    }

    private static ContentIssue issue(String id) {
        return new ContentIssue(id, "suspected", 0, 1, 0, 1, "理由", false, null, "推测");
    }

    private record Fixture(BookStore store, DecisionAcceptService accept,
                           DecisionStore decisions, DecisionProperties config,
                           MockDecisionTransport transport) {}

    private Fixture fixture() throws Exception {
        AppProperties app = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "m", "u", 5,
                "", "m", "u", 5, true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        BookStore store = new BookStore(app, mapper);
        store.createBookDirectory(BOOK);
        store.writeBook(new Book(BOOK, "t", "t.pdf", 9, Instant.now(), Instant.now(), 0, 0));
        Files.write(store.pdf(BOOK), "pdf-bytes".getBytes());
        Block b1 = block("b1", "甲乙", List.of(issue("i1")));
        Block b2 = block("b2", "丙丁", List.of(issue("i2")));
        store.writePage(BOOK, new Page(3, 600, 800, "READY", "paddle", List.of(b1, b2),
                List.of(), false, null, List.of(b1, b2)), false);
        DecisionStore decisions = new DecisionStore(store, mapper);
        TraditionalConverter converter = new TraditionalConverter();
        DecisionProperties config = new DecisionProperties();
        config.setMode("SHADOW");
        config.setProvider("MOCK");
        config.setApiKey("test-key");
        config.setModel("mock-model");
        config.setAllowCloudData(true);
        config.setMonetaryBudgetMinor(100L);
        MockDecisionTransport transport = new MockDecisionTransport();
        return new Fixture(store, new DecisionAcceptService(store, decisions, converter),
                decisions, config, transport);
    }

    /** 走完整协调器拿到可接受 decision（MOCK 通道，UNVALIDATED → CANDIDATES_ONLY 可接受）。 */
    private String prepareDecision(Fixture f) throws Exception {
        DecisionBudget budget = new DecisionBudget(f.decisions);
        CandidateResolutionService resolution =
                new CandidateResolutionService(new TraditionalConverter());
        QwenOcrClient qwen = mock(QwenOcrClient.class);
        when(qwen.configured()).thenReturn(false);
        IssueImageService images = mock(IssueImageService.class);
        EvidenceCollector evidence =
                new EvidenceCollector(resolution, images, qwen, budget, f.config);
        JevDecisionClient jev = new JevDecisionClient(new ObjectMapper().findAndRegisterModules(),
                f.transport);
        DecisionCoordinator coordinator = new DecisionCoordinator(f.store, f.decisions, budget,
                new PdfIdentity(), resolution, evidence, new DecisionStateBuilder(), jev,
                f.config, f.transport, new ObjectMapper().findAndRegisterModules());
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().stream().filter(b -> b.id().equals("b1")).findFirst().orElseThrow();
        ContentIssue issue = block.issues().get(0);
        DecisionCoordinator.CreateResult created = coordinator.createOrReuse(BOOK, 3, "i1",
                new DecisionCoordinator.CreateBody("op-prepare", "b1",
                        BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue), false));
        coordinator.runInline(BOOK, created.job().jobId());
        DecisionStore.DecisionJob job = coordinator.queryJob(BOOK, created.job().jobId());
        assertEquals("SUCCEEDED", job.state());
        assertNotNull(job.decisionId());
        return job.decisionId();
    }

    private DecisionAcceptService.AcceptBody acceptBody(Fixture f, String decisionId,
                                                       String candidateId) throws Exception {
        DecisionModels.DecisionEvidence evidence =
                f.decisions.loadResult(BOOK, decisionId).orElseThrow();
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().stream().filter(b -> b.id().equals("b1")).findFirst().orElseThrow();
        ContentIssue issue = block.issues().get(0);
        return new DecisionAcceptService.AcceptBody("op-accept-1", "b1",
                BookStore.revisionOrZero(page), IssueBasis.basisHash(block, issue),
                evidence.candidateSetHash(), candidateId, true);
    }

    private String currentCandidate(Fixture f, String decisionId) throws Exception {
        DecisionModels.DecisionEvidence evidence =
                f.decisions.loadResult(BOOK, decisionId).orElseThrow();
        DecisionModels.CandidateSet set =
                f.decisions.loadCandidateSet(BOOK, evidence.candidateSetHash()).orElseThrow();
        return set.candidates().stream()
                .filter(c -> "甲".equals(c.originalScriptText())).findFirst().orElseThrow().candidateId();
    }

    @Test void acceptWritesOnlyTargetIssueWithAudit() throws Exception {
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        String candidateId = currentCandidate(f, decisionId);
        int before = BookStore.revisionOrZero(f.store.readPage(BOOK, 3));
        DecisionAcceptService.AcceptResult result =
                f.accept.accept(BOOK, 3, "i1", decisionId, acceptBody(f, decisionId, candidateId));
        assertFalse(result.idempotent());
        Page after = f.store.readPage(BOOK, 3);
        assertEquals(before + 1, BookStore.revisionOrZero(after));
        ContentIssue confirmed = after.blocks().stream().filter(b -> b.id().equals("b1"))
                .findFirst().orElseThrow().issues().get(0);
        assertTrue(confirmed.resolved());
        assertEquals("甲", confirmed.replacement());
        assertNotNull(confirmed.resolution());
        assertEquals(DecisionModels.Origin.JEV_ASSISTED, confirmed.resolution().origin());
        assertEquals("op-accept-1", confirmed.resolution().clientOperationId());
        assertEquals(candidateId, confirmed.resolution().candidateId());
        assertEquals(decisionId, confirmed.resolution().decisionId());
        assertTrue(confirmed.resolution().userAttestedSourceCheck());
        // 只动目标 issue：b2 不变，整块/整页 reviewed 不顺手置真
        ContentIssue other = after.blocks().stream().filter(b -> b.id().equals("b2"))
                .findFirst().orElseThrow().issues().get(0);
        assertFalse(other.resolved());
        assertFalse(after.reviewed());
        assertFalse(after.blocks().stream().anyMatch(Block::reviewed));
        // sourceRecords 不动
        assertEquals(2, after.sourceRecords().size());
    }

    @Test void concurrentAcceptAndManualAndJobRace() throws Exception {
        // T55：接受与人工保存、OCR JOB_START、另一个接受请求竞争，唯一有效结果
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        String candidateId = currentCandidate(f, decisionId);
        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        DecisionAcceptService.AcceptBody body = acceptBody(f, decisionId, candidateId);
                        // 不同线程用不同 operationId → 只有一个能成功，其余 409
                        DecisionAcceptService.AcceptBody mine = new DecisionAcceptService.AcceptBody(
                                "op-race-" + index, body.blockId(), body.expectedPageRevision(),
                                body.issueBasisHash(), body.candidateSetHash(), body.candidateId(), true);
                        f.accept.accept(BOOK, 3, "i1", decisionId, mine);
                        ok.incrementAndGet();
                    } catch (ApiException e) {
                        conflicts.incrementAndGet();
                    } catch (Exception e) {
                        conflicts.incrementAndGet();
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, ok.get());
        assertEquals(2, conflicts.get());
        Page after = f.store.readPage(BOOK, 3);
        long resolved = after.blocks().stream().flatMap(b -> b.issues().stream())
                .filter(ContentIssue::resolved).count();
        assertEquals(1, resolved);
    }

    @Test void idempotentRetryAndLostResponseReadback() throws Exception {
        // T56：同 operationId 同内容重发不加版本；同 ID 换候选拒绝；丢失响应按 operationId 读回
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        String candidateId = currentCandidate(f, decisionId);
        DecisionAcceptService.AcceptBody body = acceptBody(f, decisionId, candidateId);
        DecisionAcceptService.AcceptResult first = f.accept.accept(BOOK, 3, "i1", decisionId, body);
        assertFalse(first.idempotent());
        int rev = BookStore.revisionOrZero(f.store.readPage(BOOK, 3));
        // 重发（旧 expectedRevision）：幂等成功，不加版本
        DecisionAcceptService.AcceptResult retry = f.accept.accept(BOOK, 3, "i1", decisionId, body);
        assertTrue(retry.idempotent());
        assertEquals(rev, BookStore.revisionOrZero(f.store.readPage(BOOK, 3)));
        // 同 operationId 换候选 → 拒绝
        DecisionModels.DecisionEvidence evidence =
                f.decisions.loadResult(BOOK, decisionId).orElseThrow();
        DecisionModels.CandidateSet set =
                f.decisions.loadCandidateSet(BOOK, evidence.candidateSetHash()).orElseThrow();
        String other = set.candidates().stream()
                .filter(c -> !c.candidateId().equals(candidateId)).findFirst().orElseThrow().candidateId();
        DecisionAcceptService.AcceptBody forged = new DecisionAcceptService.AcceptBody(
                body.clientOperationId(), body.blockId(), body.expectedPageRevision(),
                body.issueBasisHash(), body.candidateSetHash(), other, true);
        ApiException forgedRejected = assertThrows(ApiException.class,
                () -> f.accept.accept(BOOK, 3, "i1", decisionId, forged));
        assertEquals(HttpStatus.CONFLICT, forgedRejected.status());
        // 同 operationId 换候选（有原字）→ 锁内幂等核对拒绝重放
        Page committed = f.store.readPage(BOOK, 3);
        Block confirmedBlock = committed.blocks().stream().filter(b -> b.id().equals("b1"))
                .findFirst().orElseThrow();
        BookStore.IssueAcceptSpec replay = new BookStore.IssueAcceptSpec("b1", "i1",
                IssueBasis.basisHash(confirmedBlock, confirmedBlock.issues().get(0)),
                evidence.candidateSetHash(), "cand-other",
                "乙", "乙", CandidateResolutionService.CONVERTER_VERSION,
                body.clientOperationId(), decisionId,
                confirmedBlock.issues().get(0).resolution().basisPdfSha256());
        assertThrows(PageConflictException.class, () -> f.store.applyIssueResolution(BOOK, 3,
                BookStore.revisionOrZero(committed), CommitActor.MANUAL, null,
                CommitOp.MANUAL_SAVE, replay));
        // 读回依据已提交 operationId（文本碰巧相同不算成功）：换无 resolution 的新操作旧版本 → 409
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().stream().filter(b -> b.id().equals("b1")).findFirst().orElseThrow();
        DecisionAcceptService.AcceptBody fresh = new DecisionAcceptService.AcceptBody("op-new",
                "b1", rev - 1, IssueBasis.basisHash(block, block.issues().get(0)),
                evidence.candidateSetHash(), candidateId, true);
        assertThrows(PageConflictException.class,
                () -> f.accept.accept(BOOK, 3, "i1", decisionId, fresh));
    }

    @Test void manualSaveConflictAndJobOccupancy() throws Exception {
        // T55 一部分：OCR 任务占用下接受被拒绝；人工保存同样互斥由既有 A1 套件覆盖
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        String candidateId = currentCandidate(f, decisionId);
        // 模拟 OCR 任务登记占用：PROCESSING + JOB_START
        Page current = f.store.readPage(BOOK, 3);
        int rev = BookStore.revisionOrZero(current);
        studio.bookhtml.domain.Job job = new studio.bookhtml.domain.Job("job-ocr", "RUNNING", 0, 1,
                3, null, List.of(), Instant.now(), List.of(3), "paddle", "auto", false, false, true,
                "fp");
        f.store.writeJob(BOOK, job);
        Page processing = new Page(3, 600, 800, "PROCESSING", "paddle", current.blocks(),
                current.warnings(), false, null, current.sourceRecords(), null);
        f.store.commitPage(BOOK, processing, rev, CommitActor.JOB, "job-ocr", CommitOp.JOB_START);
        ApiException occupied = assertThrows(ApiException.class, () ->
                f.accept.accept(BOOK, 3, "i1", decisionId, acceptBody(f, decisionId, candidateId)));
        assertEquals(HttpStatus.CONFLICT, occupied.status());
    }

    @Test void pageWriteFailureNeverReportsSuccess() throws Exception {
        // T57：Page 原子写失败不报确认成功；未测的真实磁盘满单列 BLOCKED（见 FAILURES 文档）
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        String candidateId = currentCandidate(f, decisionId);
        int rev = BookStore.revisionOrZero(f.store.readPage(BOOK, 3));
        BookStore.failNextIoAt("atomic");
        try {
            assertThrows(Exception.class,
                    () -> f.accept.accept(BOOK, 3, "i1", decisionId, acceptBody(f, decisionId, candidateId)));
        } finally {
            BookStore.clearIoFailure();
        }
        Page after = f.store.readPage(BOOK, 3);
        assertEquals(rev, BookStore.revisionOrZero(after));
        assertFalse(after.blocks().stream().flatMap(b -> b.issues().stream())
                .anyMatch(ContentIssue::resolved));
    }

    @Test void budgetPersistFailureBlocksSend() throws Exception {
        // T57：预算预留落盘失败 → 不发送
        Fixture f = fixture();
        DecisionStore failing = new DecisionStore(f.store,
                new ObjectMapper().findAndRegisterModules()) {
            @Override
            public synchronized void saveAttempt(String bookId, AttemptLedger attempt)
                    throws java.io.IOException {
                throw new java.io.IOException("injected budget failure");
            }
            @Override
            public synchronized void saveBudgetState(String bookId, BudgetState state)
                    throws java.io.IOException {
                throw new java.io.IOException("injected budget failure");
            }
        };
        DecisionBudget budget = new DecisionBudget(failing);
        assertFalse(budget.tryReserve(BOOK, 1, 100L));
        assertEquals(0, budget.reservedMinor(BOOK));
    }

    @Test void resolutionSurvivesCopiesAndLegacyReadsUnknown() throws Exception {
        // T58：审计元数据在复制/合并/历史路径保留；旧 11 参数数据兼容且不虚构来源
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        String candidateId = currentCandidate(f, decisionId);
        f.accept.accept(BOOK, 3, "i1", decisionId, acceptBody(f, decisionId, candidateId));
        Page committed = f.store.readPage(BOOK, 3);
        ContentIssue confirmed = committed.blocks().stream().filter(b -> b.id().equals("b1"))
                .findFirst().orElseThrow().issues().get(0);
        // JSON 往返保留
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Page roundtrip = mapper.readValue(mapper.writeValueAsBytes(committed), Page.class);
        assertEquals(confirmed.resolution(),
                roundtrip.blocks().stream().filter(b -> b.id().equals("b1"))
                        .findFirst().orElseThrow().issues().get(0).resolution());
        // 旧 11 参数构造不虚构确认来源
        ContentIssue legacy = new ContentIssue("old", "suspected", 0, 1, 0, 1, "r", true, "替", "推");
        assertNull(legacy.resolution());
        // mapIssues（simplify 路径）不清空 resolution
        Page reprocessed = new Page(3, 600, 800, "READY", "paddle", committed.blocks(),
                List.of(), false, null, committed.sourceRecords(), null);
        List<ContentIssue> mapped = studio.bookhtml.service.PageProcessor.mapIssues(
                committed.blocks().get(0).original(),
                committed.blocks().get(0).issues(), new TraditionalConverter());
        assertEquals(confirmed.resolution(), mapped.get(0).resolution());
        assertNotNull(reprocessed);
        // mergeUnresolvedIssues 保留旧确认（同源重识别不丢已确认）
        Page merged = JobService.mergeUnresolvedIssues(committed, committed);
        assertEquals(confirmed.resolution(), merged.blocks().stream()
                .filter(b -> b.id().equals("b1")).findFirst().orElseThrow().issues().get(0)
                .resolution());
    }

    @Test void legacyReplacementWithoutOriginalMustUseManualPath() throws Exception {
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        DecisionModels.DecisionEvidence evidence =
                f.decisions.loadResult(BOOK, decisionId).orElseThrow();
        DecisionModels.CandidateSet set =
                f.decisions.loadCandidateSet(BOOK, evidence.candidateSetHash()).orElseThrow();
        String legacyId = set.candidates().stream()
                .filter(c -> c.sourceKind() == DecisionModels.SourceKind.LEGACY_INFERENCE)
                .findFirst().orElseThrow().candidateId();
        Page page = f.store.readPage(BOOK, 3);
        Block block = page.blocks().stream().filter(b -> b.id().equals("b1")).findFirst().orElseThrow();
        DecisionAcceptService.AcceptBody body = new DecisionAcceptService.AcceptBody("op-legacy",
                "b1", BookStore.revisionOrZero(page), IssueBasis.basisHash(block, block.issues().get(0)),
                evidence.candidateSetHash(), legacyId, true);
        ApiException rejected = assertThrows(ApiException.class,
                () -> f.accept.accept(BOOK, 3, "i1", decisionId, body));
        assertEquals(HttpStatus.CONFLICT, rejected.status());
    }

    @Test void decisionFromPositionACannotBeAcceptedAtPositionB() throws Exception {
        // JR-01-T01: A 的 decision 用于 B：409，B 的 Page 字节/版本/疑点不变
        Fixture f = fixture();
        String decisionIdA = prepareDecision(f);
        String candidateIdA = currentCandidate(f, decisionIdA);
        Page beforePage = f.store.readPage(BOOK, 3);
        int revBefore = BookStore.revisionOrZero(beforePage);
        Block blockB = beforePage.blocks().stream().filter(b -> b.id().equals("b2")).findFirst().orElseThrow();
        ContentIssue issueB = blockB.issues().get(0);
        DecisionModels.DecisionEvidence evidenceA = f.decisions.loadResult(BOOK, decisionIdA).orElseThrow();

        DecisionAcceptService.AcceptBody bodyForB = new DecisionAcceptService.AcceptBody(
                "op-cross-target", "b2", revBefore, IssueBasis.basisHash(blockB, issueB),
                evidenceA.candidateSetHash(), candidateIdA, true);

        ApiException rejected = assertThrows(ApiException.class,
                () -> f.accept.accept(BOOK, 3, "i2", decisionIdA, bodyForB));
        assertEquals(HttpStatus.CONFLICT, rejected.status());

        Page afterPage = f.store.readPage(BOOK, 3);
        assertEquals(revBefore, BookStore.revisionOrZero(afterPage));
        ContentIssue issueBAfter = afterPage.blocks().stream().filter(b -> b.id().equals("b2"))
                .findFirst().orElseThrow().issues().get(0);
        assertFalse(issueBAfter.resolved());
        assertNull(issueBAfter.replacement());
    }

    @Test void contextEditOrPdfReplacedRejectsOldDecisionRebind() throws Exception {
        // JR-01-T04: 只改目标之外的上下文或更换来源 PDF：旧决策不能通过新 revision/basis 重新绑定
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        String candidateId = currentCandidate(f, decisionId);

        // 1. 更换来源 PDF 内容
        Files.write(f.store.pdf(BOOK), "different-pdf-bytes".getBytes());
        ApiException pdfChanged = assertThrows(ApiException.class,
                () -> f.accept.accept(BOOK, 3, "i1", decisionId, acceptBody(f, decisionId, candidateId)));
        assertEquals(HttpStatus.CONFLICT, pdfChanged.status());
        assertTrue(pdfChanged.getMessage().contains("来源 PDF") || pdfChanged.getMessage().contains("PDF"));

        // 恢复原 PDF 内容
        Files.write(f.store.pdf(BOOK), "pdf-bytes".getBytes());

        // 2. 改动目标之外的块（b2），推进页面版本
        Page page = f.store.readPage(BOOK, 3);
        int oldRev = BookStore.revisionOrZero(page);
        Block b1 = page.blocks().get(0);
        Block b2Changed = new Block("b2", "text", 1, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "丙丁改", "丙丁改", 0.9, false, false, null, "manual", List.of("b2"), null, null,
                page.blocks().get(1).issues());
        Page proposed = new Page(3, 600, 800, "READY", "manual", List.of(b1, b2Changed),
                List.of(), false, null, page.sourceRecords(), null);
        f.store.commitPage(BOOK, proposed, oldRev, studio.bookhtml.store.CommitActor.MANUAL, null,
                studio.bookhtml.store.CommitOp.MANUAL_SAVE);

        // 用新的 expectedRevision (oldRev + 1) 重新绑定旧快照 -> 拒绝
        DecisionModels.DecisionEvidence evidence = f.decisions.loadResult(BOOK, decisionId).orElseThrow();
        DecisionAcceptService.AcceptBody rebindBody = new DecisionAcceptService.AcceptBody(
                "op-rebind", "b1", oldRev + 1, IssueBasis.basisHash(b1, b1.issues().get(0)),
                evidence.candidateSetHash(), candidateId, true);
        ApiException rebindRejected = assertThrows(ApiException.class,
                () -> f.accept.accept(BOOK, 3, "i1", decisionId, rebindBody));
        assertEquals(HttpStatus.CONFLICT, rebindRejected.status());
    }

    @Test void candidateHashOrEvidenceTamperedRejected() throws Exception {
        // JR-01-T05: candidateSet/snapshot/evidence 相互串换、hash 不符：拒绝接受
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        String candidateId = currentCandidate(f, decisionId);
        DecisionAcceptService.AcceptBody body = acceptBody(f, decisionId, candidateId);

        // 串改 candidateSetHash
        DecisionAcceptService.AcceptBody tamperedSetHash = new DecisionAcceptService.AcceptBody(
                body.clientOperationId(), body.blockId(), body.expectedPageRevision(),
                body.issueBasisHash(), "tampered-candidate-set-hash", body.candidateId(), true);
        ApiException setRejected = assertThrows(ApiException.class,
                () -> f.accept.accept(BOOK, 3, "i1", decisionId, tamperedSetHash));
        assertEquals(HttpStatus.CONFLICT, setRejected.status());
    }

    @Test void offOrShadowModeRejectsAcceptance() throws Exception {
        // JR-08-T01: OFF/SHADOW 模式矩阵：不开放 JEV 接受入口（403 FORBIDDEN）
        Fixture f = fixture();
        String decisionId = prepareDecision(f);
        String candidateId = currentCandidate(f, decisionId);
        DecisionAcceptService.AcceptBody body = acceptBody(f, decisionId, candidateId);

        // OFF mode
        studio.bookhtml.config.DecisionProperties offProps = new studio.bookhtml.config.DecisionProperties();
        offProps.setMode("OFF");
        DecisionAcceptService offAccept = new DecisionAcceptService(f.store, f.decisions,
                new TraditionalConverter(), new PdfIdentity(), offProps);
        ApiException offEx = assertThrows(ApiException.class,
                () -> offAccept.accept(BOOK, 3, "i1", decisionId, body));
        assertEquals(HttpStatus.FORBIDDEN, offEx.status());

        // SHADOW mode
        studio.bookhtml.config.DecisionProperties shadowProps = new studio.bookhtml.config.DecisionProperties();
        shadowProps.setMode("SHADOW");
        DecisionAcceptService shadowAccept = new DecisionAcceptService(f.store, f.decisions,
                new TraditionalConverter(), new PdfIdentity(), shadowProps);
        ApiException shadowEx = assertThrows(ApiException.class,
                () -> shadowAccept.accept(BOOK, 3, "i1", decisionId, body));
        assertEquals(HttpStatus.FORBIDDEN, shadowEx.status());
    }
}
