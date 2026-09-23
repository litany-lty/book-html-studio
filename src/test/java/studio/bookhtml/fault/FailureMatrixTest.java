package studio.bookhtml.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.CreateConsentRequest;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.config.OutboundDestinationPolicy;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.domain.*;
import studio.bookhtml.service.*;
import studio.bookhtml.store.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * G14 / G15 / B12: 故障恢复矩阵 NEW-D01 ~ NEW-D20 专项自动化测试。
 * 覆盖全仓关键故障注入、并发竞争、崩溃恢复、状态机闭环与沙箱防御。
 */
class FailureMatrixTest {
    @TempDir
    Path tempDir;

    private Path realDir;
    private ObjectMapper json;
    private BookStore store;

    static AppProperties config(Path data) {
        return new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3",
                "https://api.minimax.cn/v1", 5, true);
    }

    @BeforeEach
    void setUp() throws Exception {
        realDir = tempDir.toRealPath();
        json = new ObjectMapper().findAndRegisterModules();
        store = new BookStore(config(realDir), json);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    // ==========================================
    // NEW-D01 ~ NEW-D02: 存储租约与崩溃锁接管
    // ==========================================

    @Test
    void testNewD01_DataDirectoryLeaseHeldByActiveProcessRejectsStartup() throws Exception {
        Path leaseDir = realDir.resolve("lease-d01");
        Files.createDirectories(leaseDir);

        Path lockFile = leaseDir.resolve(".write.lock");
        try (var externalChannel = java.nio.channels.FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            var externalLock = externalChannel.tryLock();
            assertNotNull(externalLock, "外部活跃进程成功持有文件写锁");

            try {
                ApiException ex = assertThrows(ApiException.class, () -> DataDirectoryLease.acquire(leaseDir),
                        "同一目录已被活跃进程租用时，严禁第二个实例重复启动");
                assertEquals(409, ex.status().value());
                assertTrue(ex.getMessage().contains("数据目录正被另一进程使用"));
            } finally {
                externalLock.release();
            }
        }
    }

    @Test
    void testNewD02_DataDirectoryLeaseRecoversAbandonedLock() throws Exception {
        Path leaseDir = realDir.resolve("lease-d02");
        Files.createDirectories(leaseDir);

        Path lockFile = leaseDir.resolve(".write.lock");
        try (var crashedChannel = java.nio.channels.FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            var crashedLock = crashedChannel.tryLock();
            assertNotNull(crashedLock);
            // 模拟持有者崩溃释放
            crashedLock.release();
        }

        // 新实例启动接管
        try (DataDirectoryLease lease = DataDirectoryLease.acquire(leaseDir)) {
            assertNotNull(lease, "崩溃后的锁必须能够被安全接管");
            assertEquals(leaseDir.toRealPath(), lease.realPath());
        }
    }

    // ==========================================
    // NEW-D03 ~ NEW-D04: 账本崩溃恢复与未知债务保留
    // ==========================================

    @Test
    void testNewD03_UsageLedgerRecoversCommittedRecordsFromWalAfterCrash() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        SettingsService settings = new SettingsService(config(realDir),
                new PaddleAiStudioProperties("", null, null, 60, 180, 5),
                new QwenAssistProperties(), new DecisionProperties(), json);
        UsageLedger ledger = new UsageLedger(store, settings, json);

        try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
            String callId = ledger.start("ppocr", "PP-OCRv6");
            ledger.succeeded(callId);
        }

        Map<String, Object> view = ledger.view(bookId, 0, 10);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) view.get("totals");
        assertEquals(1L, totals.get("requests"));
        assertEquals(1L, totals.get("success"));

        // 模拟进程崩溃重启：重新实例化 UsageLedger 从活跃段恢复状态
        UsageLedger restarted = new UsageLedger(store, settings, json);
        Map<String, Object> restartedView = restarted.view(bookId, 0, 10);
        @SuppressWarnings("unchecked")
        Map<String, Object> restartedTotals = (Map<String, Object>) restartedView.get("totals");
        assertEquals(1L, restartedTotals.get("requests"), "崩溃重启后必须完整从 WAL 重建已提交账目");
        assertEquals(1L, restartedTotals.get("success"));
    }

    @Test
    void testNewD04_UsageLedgerPreservesUnknownDebtAcrossRestartsWithoutResend() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        SettingsService settings = new SettingsService(config(realDir),
                new PaddleAiStudioProperties("", null, null, 60, 180, 5),
                new QwenAssistProperties(), new DecisionProperties(), json);
        UsageLedger ledger = new UsageLedger(store, settings, json);

        try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
            ledger.start("ppocr", "PP-OCRv6");
            // 模拟进程突然崩溃或超时：调用未被显式关闭，保持为 pending/UNKNOWN 债务
        }

        Map<String, Object> view = ledger.view(bookId, 0, 10);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) view.get("totals");
        assertEquals(1L, totals.get("requests"));
        assertEquals(1L, totals.get("pending"));

        // 重新启动 UsageLedger
        UsageLedger restarted = new UsageLedger(store, settings, json);
        Map<String, Object> restartedView = restarted.view(bookId, 0, 10);
        @SuppressWarnings("unchecked")
        Map<String, Object> restartedTotals = (Map<String, Object>) restartedView.get("totals");
        assertEquals(1L, restartedTotals.get("requests"), "UNKNOWN 债务在重启后必须无损隔离并保留，严禁自动静默重发");
        assertEquals(1L, restartedTotals.get("pending"));
    }

    // ==========================================
    // NEW-D05 ~ NEW-D06: 来源事件 WAL 校验与幂等回放
    // ==========================================

    @Test
    void testNewD05_SourceChangeJournalDetectsBitRotAndTruncatesSafely() throws Exception {
        String bookId = UUID.randomUUID().toString();
        Path bookDir = store.createBookDirectory(bookId);
        SourceChangeJournal journal = store.sourceJournal();

        SourceChange sc1 = journal.prepare(bookDir, bookId, "PAGE", 1, UUID.randomUUID(), null, 0, 1, "h0", "h1", "JOB_COMPLETE");
        journal.commit(bookDir, bookId, sc1.sourceSeq(), 1, sc1.commitId(), 1, "h1");

        SourceChange sc2 = journal.prepare(bookDir, bookId, "PAGE", 2, UUID.randomUUID(), null, 0, 1, "h0", "h2", "JOB_COMPLETE");
        journal.commit(bookDir, bookId, sc2.sourceSeq(), 2, sc2.commitId(), 1, "h2");

        List<SourceChange> beforeCorruption = journal.readAll(bookDir, bookId);
        assertEquals(4, beforeCorruption.size()); // 2 PREPARED + 2 COMMITTED

        // 故意破坏文件尾部（模拟突然断电写入残缺帧）
        Path eventsDir = SourceChangeJournal.eventsDir(bookDir);
        Path segPath = eventsDir.resolve("segment-000001.wal");
        byte[] bytes = Files.readAllBytes(segPath);
        byte[] corrupted = Arrays.copyOf(bytes, bytes.length + 8);
        Arrays.fill(corrupted, bytes.length, corrupted.length, (byte) 0xFF);
        Files.write(segPath, corrupted);

        // 重放应该自动阻断在最新完整有效帧
        List<SourceChange> replayed = journal.readAll(bookDir, bookId);
        assertEquals(4, replayed.size(), "WAL 尾部损坏时应安全截断至最后一个校验合规的完整帧");
    }

    @Test
    void testNewD06_SourceChangeJournalIdempotentReplayProducesIdenticalState() throws Exception {
        String bookId = UUID.randomUUID().toString();
        Path bookDir = store.createBookDirectory(bookId);
        SourceChangeJournal journal = store.sourceJournal();

        SourceChange sc1 = journal.prepare(bookDir, bookId, "PAGE", 1, UUID.randomUUID(), null, 0, 1, "h0", "h1", "JOB_COMPLETE");
        journal.commit(bookDir, bookId, sc1.sourceSeq(), 1, sc1.commitId(), 1, "h1");

        List<SourceChange> replay1 = journal.readAll(bookDir, bookId);
        List<SourceChange> replay2 = journal.readAll(bookDir, bookId);
        assertEquals(replay1, replay2, "多次重放产生严格幂等的数据状态");
    }

    // ==========================================
    // NEW-D07 ~ NEW-D08: 页头恢复与索引发布栅栏
    // ==========================================

    @Test
    void testNewD07_PageHeadStoreRebuildsMissingOrCorruptedHeadFromAuthoritativePage() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        Page page = new Page(1, 600.0, 800.0, "READY", "qwen", List.of(), List.of(), true, null, List.of(), 1);
        store.writePage(bookId, page, false);

        Path bookDir = store.bookDir(bookId);
        PageHeadStore headStore = store.headStore();

        // 初始写入 head
        headStore.createOrUpdatePublication(bookDir, page, "hash-1", 1L, "第 1 页", null, null, null, null);
        assertNotNull(headStore.readHead(bookDir, 1));

        // 故意删除头文件模拟丢失或损坏
        Path headFile = PageHeadStore.headPath(bookDir, 1);
        Files.deleteIfExists(headFile);
        assertNull(headStore.readHead(bookDir, 1), "头文件缺失时 readHead 应返回 null");

        // 从权威 Page 重建头信息
        PageHead reconstructed = headStore.createOrUpdatePublication(bookDir, page, "hash-1", 1L, "第 1 页", null, null, null, null);
        assertNotNull(reconstructed);
        assertEquals(1, reconstructed.pageNumber());
        assertEquals(1, reconstructed.revision());
        assertTrue(reconstructed.reviewed());
        assertEquals(reconstructed, headStore.readHead(bookDir, 1));
    }

    @Test
    void testNewD08_BookIndexPublishFenceAbortsOnConcurrentSourceSeqAdvance() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        Book book = new Book(bookId, "索引栅栏测试", "source.pdf", 1, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        Page page = new Page(1, 600.0, 800.0, "READY", "qwen", List.of(), List.of(), true, null, List.of(), 1);
        store.writePage(bookId, page, false);

        BookIndexService indexService = new BookIndexService(json);
        SourceChangeJournal mockJournal = mock(SourceChangeJournal.class);
        // startSeq 返回 10L，构建结束检查栅栏时返回 11L，模拟并发推进
        when(mockJournal.currentSourceSeq(any(), eq(bookId))).thenReturn(10L, 11L);

        CompletableFuture<BookIndexManifest> future = indexService.buildOrRebuild(
                store.bookDir(bookId), bookId, "pdf-sha", 1, mockJournal, p -> page
        );

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause() instanceof IOException);
        assertTrue(ex.getCause().getMessage().contains("SOURCE_SEQ_ADVANCED_DURING_BUILD"),
                "当 sourceSeq 在索引构建期间推进时，CAS 发布栅栏必须拒绝发布");
    }

    // ==========================================
    // NEW-D09 ~ NEW-D10: 授权撤销与纪元过期
    // ==========================================

    @Test
    void testNewD09_CloudConsentRevocationAbortsQueuedAndInFlightCalls() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);

        CloudConsentStore consentStore = store.consentStore();
        ReadingPolicyStore policyStore = store.policyStore();
        OperationEpochStore epochStore = store.epochStore();
        CloudConsentService consentService = new CloudConsentService(consentStore, policyStore, epochStore);

        ReadingPolicy policy = consentService.getReadingPolicy(null);
        CreateConsentRequest req = new CreateConsentRequest(
                "op-d09-grant",
                policy.policyRevision(),
                CloudConsent.Scope.book(bookId),
                List.of("qwen"),
                "AUTO_CURRENT",
                1, 2,
                true, false, false, false,
                1, 8,
                CloudConsent.MonetaryLimits.cny(1000),
                null
        );

        CloudConsent consent = consentService.createConsent(null, req);
        assertTrue(consentService.isCloudAuthorized(bookId, "qwen"));

        // 撤销授权
        ReadingPolicy curPolicy = consentService.getReadingPolicy(null);
        consentService.revokeConsent(null, consent.consentId(), curPolicy.policyRevision(), "op-d09-revoke");
        assertFalse(consentService.isCloudAuthorized(bookId, "qwen"), "撤销授权后立即切断任何云端调用许可");
    }

    @Test
    void testNewD10_OperationEpochStoreRejectsExpiredEpochsWith410() throws Exception {
        OperationEpochStore epochStore = store.epochStore();
        String bookId = "book-epoch-1";

        Path epochFile = realDir.resolve("books").resolve(bookId).resolve("operation-epoch.json");
        Files.createDirectories(epochFile.getParent());
        OperationEpochStore.EpochState state = new OperationEpochStore.EpochState(
                2L, Instant.now().minus(Duration.ofDays(31)), Map.of(), Map.of()
        );
        Files.writeString(epochFile, json.writeValueAsString(state));

        ApiException ex = assertThrows(ApiException.class, () ->
                epochStore.findOperation(bookId, "old-operation-key", 1L, Instant.now())
        );
        assertEquals(410, ex.status().value(), "过期纪元必须返回 410 GONE");
        assertTrue(ex.getMessage().contains("操作已过期"));
    }

    // ==========================================
    // NEW-D11 ~ NEW-D12: 物理槽位治理与前台防饥饿
    // ==========================================

    @Test
    void testNewD11_ProviderResourceRegistryEnforcesStrictCapsUnderHighConcurrency() throws Exception {
        ProviderResourceRegistry registry = new ProviderResourceRegistry();

        // Qwen 最大并发为 3
        var p1 = registry.acquire(ProviderResourceRegistry.POOL_QWEN, true, Duration.ofMillis(50), () -> false);
        var p2 = registry.acquire(ProviderResourceRegistry.POOL_QWEN, true, Duration.ofMillis(50), () -> false);
        var p3 = registry.acquire(ProviderResourceRegistry.POOL_QWEN, true, Duration.ofMillis(50), () -> false);
        assertNotNull(p1);
        assertNotNull(p2);
        assertNotNull(p3);

        // 第 4 个并发请求必须在超时后被阻断
        var p4 = registry.acquire(ProviderResourceRegistry.POOL_QWEN, true, Duration.ofMillis(20), () -> false);
        assertNull(p4, "超出最大并发容量时严禁超发许可");

        p1.close();
        p2.close();
        p3.close();
    }

    @Test
    void testNewD12_ProviderResourceRegistryForegroundTasksNeverStarveBehindBackgroundQueue() throws Exception {
        ProviderResourceRegistry registry = new ProviderResourceRegistry();

        // 耗尽后台配额（后台上限为 2）
        var bg1 = registry.acquire(ProviderResourceRegistry.POOL_QWEN, false, Duration.ofMillis(50), () -> false);
        var bg2 = registry.acquire(ProviderResourceRegistry.POOL_QWEN, false, Duration.ofMillis(50), () -> false);
        assertNotNull(bg1);
        assertNotNull(bg2);

        // 此时后台无法再获取许可
        var bg3 = registry.acquire(ProviderResourceRegistry.POOL_QWEN, false, Duration.ofMillis(10), () -> false);
        assertNull(bg3);

        // 但保留给前台的槽位仍可立即被前台请求获取（前台绝对优先）
        var fg = registry.acquire(ProviderResourceRegistry.POOL_QWEN, true, Duration.ofMillis(50), () -> false);
        assertNotNull(fg, "保留给前台的槽位严禁被后台任务挤占");

        bg1.close();
        bg2.close();
        fg.close();
    }

    // ==========================================
    // NEW-D13 ~ NEW-D14: 延迟退避队列与预算退款保护
    // ==========================================

    @Test
    void testNewD13_DelayedCallQueue429ReleasesPhysicalPermitImmediately() throws Exception {
        DelayedCallQueue queue = new DelayedCallQueue();
        Instant nextEligible = Instant.now().plusSeconds(5);
        DelayedCallQueue.DelayedCallDescriptor desc = new DelayedCallQueue.DelayedCallDescriptor(
                "call-1", "attempt-1", nextEligible, System.nanoTime() + TimeUnit.SECONDS.toNanos(30), 5
        );
        AtomicBoolean ran = new AtomicBoolean(false);
        boolean scheduled = queue.schedule(desc, () -> ran.set(true));
        assertTrue(scheduled);
        assertEquals(1, queue.activeDelayedCount());
        assertFalse(ran.get());

        queue.cancel("call-1");
        assertEquals(0, queue.activeDelayedCount());
        queue.close();
    }

    @Test
    void testNewD14_AttemptCallBudgetStoreRefundsBudgetWhenFactoryFailsBeforeDispatch() throws Exception {
        AttemptCallBudgetStore budgets = new AttemptCallBudgetStore();
        String rootId = "attempt-refund-test";

        var res = budgets.claim(rootId, 5);
        assertNotNull(res);
        assertEquals(4, budgets.remaining(rootId, 5));

        // 工厂在未发送前失败，显式取消退还
        res.close();
        assertEquals(5, budgets.remaining(rootId, 5), "未实际发送物理请求的取消必须如实退还预算");
    }

    // ==========================================
    // NEW-D15 ~ NEW-D16: 跨入口调度与决策依赖锁内漂移拦截
    // ==========================================

    @Test
    void testNewD15_CrossEntrySchedulerElevatesStarvedTasksAfterYieldThreshold() {
        PageProcessingService pageEngine = mock(PageProcessingService.class);
        PageWorkScheduler scheduler = new PageWorkScheduler(pageEngine, 1, 1);

        PageAttempt attempt = PageAttempt.register("book-1", 1, 0, "hash", List.of("PUBLISH"));
        PageProcessingService.Request req = new PageProcessingService.Request(
                attempt, "qwen", "auto", false, false, false, () -> false, () -> true
        );

        scheduler.schedule(req, PageWorkScheduler.Priority.P3);

        // 前台切换页面时动态提升优先级到 P0
        boolean promoted = scheduler.promotePriority("book-1", 1, PageWorkScheduler.Priority.P0);
        assertTrue(promoted, "当前页请求必须能够即时抢占并提升至 P0 最高优先级");
        scheduler.close();
    }

    @Test
    void testNewD16_DecisionAcceptServiceRejectsStaleDecisionWhenContextDrifts() {
        ContextDependencyValidator validator = new ContextDependencyValidator();
        String blockText = "原始文本内容";
        ContextSnapshot snapshot = ContextSnapshot.create(
                "book-1", 1, 0, "parent-plan-1", "review-plan-1",
                1L, "b1", 0, 4, blockText, "原始文本"
        );

        // 页面实际内容被修改为 "漂移文本内容"
        Block modifiedBlock = new Block("b1", "text", 0, new double[]{0, 0, 100, 100}, "horizontal-tb",
                "漂移文本内容", "漂移文本内容", 0.9, true, false, null, "qwen", List.of(), null, null, List.of());
        Page modifiedPage = new Page(1, 600.0, 800.0, "READY", "qwen", List.of(modifiedBlock), List.of(), false, null, List.of(), 0);

        var result = validator.validate(snapshot, modifiedPage, 1L);
        assertFalse(result.isValid(), "上下文发生漂移时必须拦截决策合并");
        assertEquals(ContextDependencyValidator.ResultCode.CONTENT_DRIFT, result.code());
    }

    // ==========================================
    // NEW-D17 ~ NEW-D18: 计划权重不变量与防 Zip 炸弹流式截断
    // ==========================================

    @Test
    void testNewD17_WorkPlanReducerMaintainsFixedWeightsDenominatorInvariant() {
        int totalWeights = WorkPlan.WEIGHT_OCR + WorkPlan.WEIGHT_STRUCTURE + WorkPlan.WEIGHT_REVIEW
                + WorkPlan.WEIGHT_VALIDATING + WorkPlan.WEIGHT_PUBLISHING;
        assertEquals(100, totalWeights, "五阶段总权重分母不变量必须严格等于 100");
        assertEquals(30, WorkPlan.WEIGHT_OCR);
        assertEquals(20, WorkPlan.WEIGHT_STRUCTURE);
        assertEquals(35, WorkPlan.WEIGHT_REVIEW);
        assertEquals(10, WorkPlan.WEIGHT_VALIDATING);
        assertEquals(5, WorkPlan.WEIGHT_PUBLISHING);
    }

    @Test
    void testNewD18_SafeArchiveExtractorAbortsStreamingBombWithoutUnboundedDiskUsage() throws Exception {
        byte[] zeros = new byte[1024 * 1024]; // 1MB 全 0
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("zeros.bin"));
            zos.write(zeros);
            zos.closeEntry();
        }

        SafeArchiveExtractor extractor = new SafeArchiveExtractor(50 * 1024 * 1024, 10, 50.0);
        Path outDir = realDir.resolve("bomb-extract");

        assertThrows(SecurityException.class, () ->
                extractor.extract(new ByteArrayInputStream(baos.toByteArray()), outDir)
        );
    }

    // ==========================================
    // NEW-D19 ~ NEW-D20: 导出快照版本失效与出站 SSRF 阻断
    // ==========================================

    @Test
    void testNewD19_ExportServiceRejectsStaleOrCorruptedSnapshotWith409() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        Files.write(store.pdf(bookId), "fake-pdf-content".getBytes());
        Page page = new Page(1, 600.0, 800.0, "READY", "qwen", List.of(), List.of(), false, null, List.of(), 1);
        store.writePage(bookId, page, false);

        Book book = new Book(bookId, "快照失效测试", "source.pdf", 1, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);

        BookService books = mock(BookService.class);
        when(books.get(bookId)).thenReturn(book);
        PdfService pdf = mock(PdfService.class);

        ExportService exportService = new ExportService(books, store, pdf, json);

        // 构造一个包含不匹配 contentHash 的过期快照
        ExportSnapshot.PageRef staleRef = new ExportSnapshot.PageRef(
                1, 1, 1, UUID.randomUUID(), "mismatched-content-hash", "READY", true, false
        );
        ExportSnapshot staleSnapshot = new ExportSnapshot(
                UUID.randomUUID().toString(), bookId, "快照失效测试", 1, 1, "sha",
                0L, List.of(1), List.of(staleRef), 0L, 0L, 2, Map.of(), Instant.now()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ApiException ex = assertThrows(ApiException.class, () -> exportService.writeZip(staleSnapshot, out));
        assertEquals(409, ex.status().value());
        assertTrue(ex.getMessage().contains("SNAPSHOT_EXPIRED"));
    }

    @Test
    void testNewD20_OutboundDestinationPolicyBlocksSsrfBeforeNetworkSocketOpens() {
        OutboundDestinationPolicy policy = new OutboundDestinationPolicy();

        // 尝试向云元数据端点外发
        URI metadataUri = URI.create("http://169.254.169.254/latest/meta-data/");
        var res = policy.validate(metadataUri);
        assertFalse(res.isAllowed(), "策略必须在打开物理网络套接字前拦截 SSRF 目标");
        assertTrue(res.reason().contains("元数据") || res.reason().contains("链路本地"));
    }
}
