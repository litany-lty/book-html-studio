package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.service.UsageContext;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

/**
 * J07：独立决策作业与调度。建议生成不推进 Page.revision，不写 PROCESSING，
 * 不复用/覆盖单书 OCR 的 job.json；有界队列、真实 IO 取消、总 deadline；
 * 关机/重启不自动重发已 SENT 请求。
 */
@Service
public class DecisionCoordinator {
    public static final String ENDPOINT_IDENTITY = "typesafe-systemone-v1-2026-09-21";
    public static final String ENDPOINT_URL = "https://api.typesafe.ai/v1/systemone";
    public static final String PROVIDER_CONTRACT_VERSION = "v1-2026-09-21";
    static final long RESERVE_PER_JEV_CALL_MINOR = 1;
    static final String ACQUISITION_POLICY_VERSION = "acquisition-v1";

    private final BookStore store;
    private final DecisionStore decisions;
    private final DecisionBudget budget;
    private final PdfIdentity pdfIdentity;
    private final CandidateResolutionService resolution;
    private final EvidenceCollector evidence;
    private final DecisionStateBuilder stateBuilder;
    private studio.bookhtml.service.BookContextService bookContext;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBookContext(studio.bookhtml.service.BookContextService bookContext) { this.bookContext = bookContext; }
    private final JevDecisionClient jev;
    private final DecisionProperties config;
    private final DecisionTransport transport;
    private final ObjectMapper json;
    private final DecisionOutboundGate gate;

    private final ConcurrentHashMap<String, Object> bookLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JobControl> controls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SettingsService.Lease> settingsLeases = new ConcurrentHashMap<>();
    private SettingsService settings;
    private final BlockingQueue<String> queue;
    private final Object globalAdmissionLock = new Object();
    private Thread worker;

    @org.springframework.beans.factory.annotation.Autowired
    public DecisionCoordinator(BookStore store, DecisionStore decisions, DecisionBudget budget,
                               PdfIdentity pdfIdentity, CandidateResolutionService resolution,
                               EvidenceCollector evidence, DecisionStateBuilder stateBuilder,
                               JevDecisionClient jev, DecisionProperties config,
                               DecisionTransport transport, ObjectMapper json,
                               DecisionOutboundGate gate) {
        this.store = store;
        this.decisions = decisions;
        this.budget = budget;
        this.pdfIdentity = pdfIdentity;
        this.resolution = resolution;
        this.evidence = evidence;
        this.stateBuilder = stateBuilder;
        this.jev = jev;
        this.config = config;
        this.transport = transport;
        this.json = json;
        this.gate = gate != null ? gate : new DecisionOutboundGate(config);
        this.queue = new LinkedBlockingQueue<>();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setSettings(SettingsService settings) { this.settings = settings; }

    private void admitLease(String jobId) {
        if (settings != null) settingsLeases.computeIfAbsent(jobId, ignored -> settings.beginWork());
    }
    private void releaseLease(String jobId) {
        SettingsService.Lease lease = settingsLeases.remove(jobId);
        if (lease != null) lease.close();
    }

    public DecisionCoordinator(BookStore store, DecisionStore decisions, DecisionBudget budget,
                               PdfIdentity pdfIdentity, CandidateResolutionService resolution,
                               EvidenceCollector evidence, DecisionStateBuilder stateBuilder,
                               JevDecisionClient jev, DecisionProperties config,
                               DecisionTransport transport, ObjectMapper json) {
        this(store, decisions, budget, pdfIdentity, resolution, evidence, stateBuilder,
                jev, config, transport, json, new DecisionOutboundGate(config));
    }

    @PostConstruct
    void start() {
        worker = new Thread(this::drain, "decision-worker");
        worker.setDaemon(true);
        worker.start();
        recoverInterrupted();
    }

    @PreDestroy
    void stop() {
        if (worker != null) worker.interrupt();
    }

    private Object bookLock(String bookId) {
        return bookLocks.computeIfAbsent(bookId, k -> new Object());
    }

    /** 重启恢复：QUEUED 按策略重排；已发送未知（COMPARING 及之后）标 INTERRUPTED 不自动重发。 */
    public void recoverInterrupted() {
        try {
            if (store != null) {
                List<Book> books = store.listBooks();
                if (books != null) {
                    for (Book b : books) {
                        if (b != null && b.id() != null) {
                            recoverBook(b.id());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 指定书的重启恢复（JR-06）：QUEUED 重排；COMPARING 及之后视为已发送未知，标 INTERRUPTED；重复恢复不重复入队。 */
    public void recoverBook(String bookId) {
        List<DecisionStore.DecisionJob> jobs;
        try {
            jobs = decisions.listJobs(bookId);
        } catch (IOException e) {
            return;
        }
        for (DecisionStore.DecisionJob job : jobs) {
            String item = job.jobId() + "\u0000" + bookId;
            synchronized (globalAdmissionLock) {
                if (queue.contains(item)) {
                    continue; // JR-06-T05：重复恢复不重复入队
                }
                if ("QUEUED".equals(job.state())) {
                    if (Instant.now().isAfter(job.deadlineAt())) {
                        persistTerminal(bookId, withState(job, "FAILED", "DONE",
                                List.of("DEADLINE_EXPIRED_ON_RESTART"), null, null));
                    } else if (queue.size() < config.getMaxQueueEntries()) {
                        admitLease(job.jobId());
                        if (!queue.offer(item)) releaseLease(job.jobId());
                    } else {
                        persistTerminal(bookId, withState(job, "FAILED", "DONE",
                                List.of("QUEUE_FULL_ON_RESTART"), null, null));
                    }
                } else if ("RUNNING".equals(job.state()) || "CANCEL_REQUESTED".equals(job.state())) {
                    boolean maybeSent = List.of("COMPARING", "PERSISTING", "DONE")
                            .contains(job.progressStage());
                    DecisionStore.DecisionJob updated = withState(job,
                            maybeSent ? "INTERRUPTED" : "QUEUED",
                            maybeSent ? job.progressStage() : "LOCATING",
                            maybeSent ? List.of("RESTART_INTERRUPTED_MAY_HAVE_SENT")
                                    : List.of("RESTART_REQUEUED"),
                            job.decisionId(), job.verdict());
                    saveJobQuietly(bookId, updated);
                    if (!maybeSent) {
                        if (Instant.now().isAfter(job.deadlineAt())) {
                            persistTerminal(bookId, withState(updated, "FAILED", "DONE",
                                    List.of("DEADLINE_EXPIRED_ON_RESTART"), null, null));
                        } else if (queue.size() < config.getMaxQueueEntries()) {
                            admitLease(job.jobId());
                            if (!queue.offer(item)) releaseLease(job.jobId());
                        } else {
                            persistTerminal(bookId, withState(updated, "FAILED", "DONE",
                                    List.of("QUEUE_FULL_ON_RESTART"), null, null));
                        }
                    }
                }
            }
        }
    }

    public record CreateBody(String clientOperationId, String blockId, int expectedPageRevision,
                             String issueBasisHash, boolean allowFreshVision) {}

    public record CreateResult(int httpStatus, DecisionStore.DecisionJob job) {}

    /**
     * 创建/复用决策作业。clientOperationId 是重复 HTTP 的幂等身份，不进 admissionKey；
     * 相同语义在途请求复用同一作业；完全相同的完成结果直接返回。
     */
    public CreateResult createOrReuse(String bookId, int sourcePage, String issueId, CreateBody body) {
        SettingsService.Lease admissionLease = settings == null ? null : settings.beginWork();
        boolean transferred = false;
        try {
        validateCreate(bookId, sourcePage, issueId, body);
        synchronized (bookLock(bookId)) {
            Page page = readPageOr404(bookId, sourcePage);
            Block block = page.blocks().stream().filter(b -> b != null && body.blockId().equals(b.id()))
                    .findFirst().orElseThrow(() ->
                            new ApiException(HttpStatus.NOT_FOUND, "指定的块不存在"));
            ContentIssue issue = block.issues().stream().filter(i -> i != null && issueId.equals(i.id()))
                    .findFirst().orElseThrow(() ->
                            new ApiException(HttpStatus.NOT_FOUND, "指定的问题不存在"));
            long issueCount = page.blocks().stream()
                    .filter(b -> b != null && b.issues() != null)
                    .flatMap(b -> b.issues().stream())
                    .filter(i -> i != null && issueId.equals(i.id()))
                    .count();
            if (issueCount > 1)
                throw new ApiException(HttpStatus.CONFLICT, "页面数据不一致：同页存在重复问题ID");
            int revision = BookStore.revisionOrZero(page);
            if (revision != body.expectedPageRevision())
                throw new ApiException(HttpStatus.CONFLICT, "页面版本已变化，请刷新后重试");
            String basis = IssueBasis.basisHash(block, issue);
            if (!basis.equals(body.issueBasisHash()))
                throw new ApiException(HttpStatus.CONFLICT, "问题基线已变化，请刷新后重试");
            DecisionTargetIdentity target = buildTargetIdentity(bookId, page, block, issue, basis);
            String admissionKey = admissionKey(target, block, body.allowFreshVision());
            try {
                DecisionStore.DecisionJob existing = decisions.findByAdmission(bookId, admissionKey);
                if (existing != null && isReusable(existing, page)) {
                    return new CreateResult(
                            "SUCCEEDED".equals(existing.state()) ? 200 : 200, existing);
                }
                // JR-06：先持久化作业文件，再入队发布；杜绝“ worker 先消费、文件后落盘”的竞态。
                // 入队失败（满载）时尽力删除刚落盘的 QUEUED 文件，保持“满载无副作用”。
                Instant now = Instant.now();
                DecisionStore.DecisionJob job = new DecisionStore.DecisionJob(
                        UUID.randomUUID().toString(), "QUEUED", 1, "LOCATING", admissionKey, null,
                        bookId, sourcePage, block.id(), issueId, null, null, null,
                        body.clientOperationId(), null, List.of(), 0, "UNKNOWN", "NONE",
                        now, now, now.plusSeconds(Math.max(1, config.getJobDeadlineSeconds())),
                        body.allowFreshVision(), target);
                String queueItem = job.jobId() + "\u0000" + bookId;
                if (admissionLease != null) settingsLeases.put(job.jobId(), admissionLease);
                boolean admitted = false;
                try {
                    decisions.saveJob(bookId, job);
                } catch (IOException e) {
                    releaseLease(job.jobId());
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业保存失败");
                }
                try {
                    synchronized (globalAdmissionLock) {
                        if (queue.size() >= config.getMaxQueueEntries() || !queue.offer(queueItem)) {
                            try {
                                decisions.deleteJob(bookId, job.jobId());
                            } catch (Exception ignored) {
                            }
                            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "决策队列已满");
                        }
                    }
                    admitted = true;
                    transferred = true;
                    return new CreateResult(202, job);
                } finally {
                    if (!admitted) settingsLeases.remove(job.jobId());
                }
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业保存失败");
            }
        }
        } finally {
            if (!transferred && admissionLease != null) admissionLease.close();
        }
    }

    private boolean isReusable(DecisionStore.DecisionJob job, Page current) {
        if (job == null) return false;
        // JR-01：QUEUED/RUNNING 复用必须核对持久化目标与当前目标一致，不只凭 admissionKey 恰好不同
        if ("QUEUED".equals(job.state()) || "RUNNING".equals(job.state())) {
            if (job.target() == null) return false;
            try {
                Block block = current.blocks().stream()
                        .filter(b -> b != null && job.blockId().equals(b.id())).findFirst().orElse(null);
                if (block == null) return false;
                ContentIssue issue = block.issues().stream()
                        .filter(i -> i != null && job.issueId().equals(i.id())).findFirst().orElse(null);
                if (issue == null) return false;
                String basis = IssueBasis.basisHash(block, issue);
                DecisionTargetIdentity currentTarget =
                        buildTargetIdentity(job.bookId(), current, block, issue, basis);
                return job.target().mismatch(currentTarget) == null;
            } catch (Exception e) {
                return false;
            }
        }
        if (!"SUCCEEDED".equals(job.state())) return false;
        // 完成结果仅当仍适用于当前版本才复用
        try {
            if (job.decisionId() == null) return false;
            Optional<DecisionModels.DecisionEvidence> evidence =
                    decisions.loadResult(job.bookId(), job.decisionId());
            if (evidence.isEmpty()) return false;
            DecisionModels.DecisionSnapshot snapshot =
                    decisions.loadSnapshot(job.bookId(), evidence.get().snapshotHash()).orElse(null);
            if (snapshot == null) return false;
            return snapshotApplies(snapshot, current);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean snapshotApplies(DecisionModels.DecisionSnapshot snapshot, Page page) {
        DecisionModels.IssueRef ref = snapshot.issueRef();
        // JR-01：全量目标比对（revision/block/issue/basis + pdf/原文/区间/映射），不只比 basis
        if (BookStore.revisionOrZero(page) != ref.pageRevision()) return false;
        Block block = page.blocks().stream().filter(b -> b != null && ref.blockId().equals(b.id()))
                .findFirst().orElse(null);
        if (block == null || block.original() == null) return false;
        ContentIssue issue = block.issues().stream()
                .filter(i -> i != null && ref.issueId().equals(i.id())).findFirst().orElse(null);
        if (issue == null) return false;
        try {
            if (!IssueBasis.basisHash(block, issue).equals(ref.issueBasisHash())) return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            String pdfHash = pdfIdentity.sha256(store.pdf(ref.bookId()));
            if (!ref.pdfSha256().equals(pdfHash)) return false;
        } catch (Exception e) {
            return false;
        }
        String original = block.original();
        if (!DecisionHash.sha256Hex(original).equals(ref.originalTextHash())) return false;
        if (issue.start() != ref.startUtf16() || issue.end() != ref.endUtf16()) return false;
        String span;
        try {
            span = original.substring(issue.start(), issue.end());
        } catch (Exception e) {
            return false;
        }
        String spanHash = DecisionHash.sha256Hex(
                block.id() + "\u0000" + issue.start() + "\u0000" + issue.end() + "\u0000" + span);
        if (!spanHash.equals(ref.sourceSpanHash())) return false;
        return IssueBasis.MAPPING_VERSION.equals(ref.mappingVersion());
    }

    DecisionTargetIdentity buildTargetIdentity(String bookId, Page page, Block block, ContentIssue issue, String basis) {
        String pdfHash;
        try {
            pdfHash = pdfIdentity.sha256(store.pdf(bookId));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF 身份获取失败");
        }
        String original = block.original() == null ? "" : block.original();
        String originalHash = DecisionHash.sha256Hex(original);
        String span = (original.length() >= issue.end() && issue.start() >= 0 && issue.end() >= issue.start())
                ? original.substring(issue.start(), issue.end()) : "";
        String spanHash = DecisionHash.sha256Hex(block.id() + "\u0000" + issue.start() + "\u0000" + issue.end() + "\u0000" + span);
        return new DecisionTargetIdentity(
                bookId, pdfHash, page.pageNumber(), block.id(), issue.id(),
                BookStore.revisionOrZero(page), originalHash, basis,
                issue.start(), issue.end(), spanHash, IssueBasis.MAPPING_VERSION);
    }

    String admissionKey(DecisionTargetIdentity target, Block block, boolean allowFreshVision) {
        String evidenceVersion = block.source() == null ? "" : block.source();
        String scope = config.getProvider() + "|" + config.getModel() + "|" + config.isAllowCloudData();
        return target.admissionKey(ACQUISITION_POLICY_VERSION, evidenceVersion, allowFreshVision, scope);
    }

    String admissionKey(String bookId, Page page, Block block, ContentIssue issue,
                        String basis, boolean allowFreshVision) {
        DecisionTargetIdentity target = buildTargetIdentity(bookId, page, block, issue, basis);
        return admissionKey(target, block, allowFreshVision);
    }

    private void validateCreate(String bookId, int sourcePage, String issueId, CreateBody body) {
        if (bookId == null || bookId.isBlank() || issueId == null || issueId.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "参数非法");
        if (sourcePage < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "参数非法");
        if (body == null || body.clientOperationId() == null || body.clientOperationId().isBlank()
                || body.blockId() == null || body.blockId().isBlank()
                || body.issueBasisHash() == null || body.issueBasisHash().isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "参数非法");
    }

    private Page readPageOr404(String bookId, int sourcePage) {
        try {
            return store.readPage(bookId, sourcePage);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "指定的书/页不存在");
        }
    }

    /** 纯读查询：不触发新 OCR/JEV，不扫描整本书。 */
    public DecisionStore.DecisionJob queryJob(String bookId, String jobId) {
        try {
            return decisions.loadJob(bookId, jobId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "决策作业不存在"));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业读取失败");
        }
    }

    /** 取消绑定 jobId 与当前 stateVersion；关闭面板/不再订阅不等同取消。 */
    public DecisionStore.DecisionJob cancel(String bookId, String jobId, int expectedStateVersion) {
        synchronized (bookLock(bookId)) {
            DecisionStore.DecisionJob job = queryJob(bookId, jobId);
            if (job.stateVersion() != expectedStateVersion)
                throw new ApiException(HttpStatus.CONFLICT, "作业状态已变化，请刷新后重试");
            if (isTerminal(job.state())) return job;
            JobControl control = controls.get(jobId);
            if (control != null) control.cancelled.set(true);
            DecisionStore.DecisionJob cancelled = withState(job, "CANCEL_REQUESTED", "CANCELLING",
                    job.reasonCodes(), job.decisionId(), job.verdict());
            // JR-06-T03/T06：取消经 CAS 落盘，失败抛错不静默
            casSaveJobOutsideLock(bookId, cancelled, expectedStateVersion);
            return cancelled;
        }
    }

    private void casSaveJobOutsideLock(String bookId, DecisionStore.DecisionJob next, int expectedVersion) {
        // 调用方已持 bookLock，直接校验版本后落盘，避免二次加锁死锁
        DecisionStore.DecisionJob current = queryJob(bookId, next.jobId());
        if (current == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "决策作业不存在");
        if (current.stateVersion() != expectedVersion)
            throw new ApiException(HttpStatus.CONFLICT, "作业状态已变化，请刷新后重试");
        if (isTerminal(current.state()))
            throw new ApiException(HttpStatus.CONFLICT, "作业已终结");
        try {
            decisions.saveJob(bookId, next);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业保存失败");
        }
    }

    private static boolean isTerminal(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state)
                || "INTERRUPTED".equals(state);
    }

    /** 查询装配：问题基线（服务端签发）。 */
    public Map<String, Object> issueBasisView(String bookId, int sourcePage, String issueId) {
        Page page = readPageOr404(bookId, sourcePage);
        String blockId = findBlockId(page, issueId);
        Block block = findBlock(page, blockId);
        ContentIssue issue = findIssue(block, issueId);
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("blockId", block.id());
        basis.put("issueBasisHash", IssueBasis.basisHash(block, issue));
        basis.put("pageRevision", BookStore.revisionOrZero(page));
        basis.put("mappingVersion", IssueBasis.MAPPING_VERSION);
        return basis;
    }

    private String findBlockId(Page page, String issueId) {
        String found = null;
        if (page.blocks() != null) for (Block block : page.blocks()) {
            if (block != null && block.issues() != null) for (ContentIssue issue : block.issues())
                if (issue != null && issueId.equals(issue.id())) {
                    if (found != null)
                        throw new ApiException(HttpStatus.CONFLICT, "页面数据不一致：同页存在重复问题ID");
                    found = block.id();
                }
        }
        if (found != null) return found;
        throw new ApiException(HttpStatus.NOT_FOUND, "指定的问题不存在");
    }

    /** 当前建议：最新仍适用的完成作业；过期只在 history 中说明原因。 */
    public Map<String, Object> currentDecisionView(String bookId, int sourcePage, String issueId) {
        // JR-01-T02：先解析唯一 block（重复 issueId 直接 409），再按 block 过滤，不取第一条
        Page page = readPageOr404(bookId, sourcePage);
        String blockId = findBlockId(page, issueId);
        for (DecisionStore.DecisionJob job : listIssueJobs(bookId, sourcePage, blockId, issueId)) {
            if (!"SUCCEEDED".equals(job.state()) || job.decisionId() == null) continue;
            if (!"CURRENT".equals(applicability(bookId, job))) continue;
            return decisionSummary(bookId, job);
        }
        return null;
    }

    /** 历史建议（最新 10 条）与各自适用性/过期原因。 */
    public List<Map<String, Object>> decisionHistory(String bookId, int sourcePage, String issueId,
                                                     int limit) {
        Page page = readPageOr404(bookId, sourcePage);
        String blockId = findBlockId(page, issueId);
        List<Map<String, Object>> history = new ArrayList<>();
        for (DecisionStore.DecisionJob job : listIssueJobs(bookId, sourcePage, blockId, issueId)) {
            if (job.decisionId() == null) continue;
            if (history.size() >= Math.max(1, limit)) break;
            Map<String, Object> summary = decisionSummary(bookId, job);
            summary.put("applicability", applicability(bookId, job));
            history.add(summary);
        }
        return history;
    }

    /** 适用性重算：当前页版本/基线/策略变化即过期，不篡改已持久证据。 */
    public String applicability(String bookId, DecisionStore.DecisionJob job) {
        if (job == null || job.decisionId() == null) return "STALE";
        if ("CANCELLED".equals(job.state())) return "CANCELLED";
        try {
            Optional<DecisionModels.DecisionEvidence> evidence =
                    decisions.loadResult(bookId, job.decisionId());
            if (evidence.isEmpty()) return "STALE";
            DecisionModels.DecisionEvidence result = evidence.get();
            if (!DecisionPolicy.POLICY_VERSION.equals(result.policyVersion())
                    || !DecisionPolicy.THRESHOLD_PROFILE.equals(result.thresholdProfileVersion()))
                return "POLICY_CHANGED";
            DecisionModels.DecisionSnapshot snapshot =
                    decisions.loadSnapshot(bookId, result.snapshotHash()).orElse(null);
            if (snapshot == null) return "STALE";
            Page current;
            try {
                current = store.readPage(bookId, snapshot.issueRef().sourcePageNumber());
            } catch (Exception e) {
                return "STALE";
            }
            return snapshotApplies(snapshot, current) ? "CURRENT" : "STALE";
        } catch (IOException e) {
            return "STALE";
        }
    }

    private List<DecisionStore.DecisionJob> listIssueJobs(String bookId, int sourcePage, String issueId) {
        return listIssueJobs(bookId, sourcePage, null, issueId);
    }

    private List<DecisionStore.DecisionJob> listIssueJobs(String bookId, int sourcePage,
                                                          String blockId, String issueId) {
        List<DecisionStore.DecisionJob> jobs;
        try {
            jobs = decisions.listJobs(bookId);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业读取失败");
        }
        List<DecisionStore.DecisionJob> filtered = new ArrayList<>();
        for (DecisionStore.DecisionJob job : jobs)
            if (issueId.equals(job.issueId()) && (sourcePage <= 0 || job.sourcePageNumber() == sourcePage)
                    && (blockId == null || blockId.equals(job.blockId())))
                filtered.add(job);
        filtered.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return filtered;
    }

    private List<DecisionStore.DecisionJob> listIssueJobs(String bookId, String issueId) {
        return listIssueJobs(bookId, -1, issueId);
    }

    private Map<String, Object> decisionSummary(String bookId, DecisionStore.DecisionJob job) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobId", job.jobId());
        summary.put("jobState", job.state());
        summary.put("jobStateVersion", job.stateVersion());
        summary.put("decisionId", job.decisionId());
        summary.put("verdict", job.verdict());
        summary.put("reasonCodes", job.reasonCodes());
        try {
            Optional<DecisionModels.DecisionEvidence> evidence =
                    decisions.loadResult(bookId, job.decisionId());
            if (evidence.isPresent()) {
                summary.put("candidateSetHash", evidence.get().candidateSetHash());
                summary.put("templateVersion", evidence.get().questionTemplateVersion());
                summary.put("policyVersion", evidence.get().policyVersion());
                String reported = evidence.get().reportedModel();
                String requested = evidence.get().requestedModel();
                String model = (reported != null && !reported.isBlank()) ? reported
                        : (requested != null && !requested.isBlank()) ? requested
                        : (config != null ? config.getModel() : null);
                summary.put("model", model);
                summary.put("provider", evidence.get().provider());
                Optional<DecisionModels.CandidateSet> set =
                        decisions.loadCandidateSet(bookId, evidence.get().candidateSetHash());
                if (set.isPresent()) {
                    List<Map<String, Object>> candidates = new ArrayList<>();
                    List<DecisionModels.Candidate> list = set.get().candidates();
                    for (int i = 0; i < list.size(); i++) {
                        DecisionModels.Candidate candidate = list.get(i);
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("alias", "C" + i);
                        entry.put("candidateId", candidate.candidateId());
                        entry.put("displayText", candidate.simplifiedDisplayText() == null
                                ? "" : candidate.simplifiedDisplayText());
                        entry.put("originalText", candidate.originalScriptText());
                        entry.put("sourceKind", candidate.sourceKind().name());
                        entry.put("alignment", candidate.alignmentStatus().name());
                        candidates.add(entry);
                    }
                    summary.put("candidates", candidates);
                    String selected = evidence.get().choice() == null ? null
                            : evidence.get().choice().selectedAlias();
                    String rawChoiceId = DecisionStateBuilder.candidateIdForAlias(set.get(), selected);
                    summary.put("modelPreferredCandidateId", rawChoiceId);
                    boolean isAdmitted = ("RECOMMEND".equals(job.verdict()) || "KEEP_CURRENT".equals(job.verdict()))
                            && "CURRENT".equals(applicability(bookId, job))
                            && "ASSIST".equalsIgnoreCase(config.getMode());
                    String admittedId = isAdmitted ? rawChoiceId : null;
                    summary.put("admittedRecommendationId", admittedId);
                    summary.put("recommendedCandidateId", admittedId);
                }
            }
        } catch (IOException ignored) {
        }
        return summary;
    }

    private void drain() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String item = queue.poll(500, TimeUnit.MILLISECONDS);
                if (item == null) continue;
                int split = item.indexOf('\u0000');
                run(item.substring(split + 1), item.substring(0, split));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run(String bookId, String jobId) {
        JobControl control = new JobControl();
        controls.put(jobId, control);
        try {
            DecisionStore.DecisionJob job = queryJob(bookId, jobId);
            if (!"QUEUED".equals(job.state()) || control.cancelled.get()) {
                finishCancelIfRequested(bookId, job);
                return;
            }
            if (Duration.between(job.createdAt(), Instant.now()).getSeconds() > config.getQueueWaitTimeoutSeconds()) {
                persistTerminal(bookId, withState(job, "FAILED", "DONE", List.of("QUEUE_TIMEOUT"), null, null));
                return;
            }
            // JR-06-T04：总期限自入队起计；进程内剩余用单调时钟推导，避免墙钟跳变重置
            long wallRemaining = Duration.between(Instant.now(), job.deadlineAt()).toNanos();
            control.wallRemainingAtRunStartNanos = wallRemaining;
            if (wallRemaining <= 0) {
                persistTerminal(bookId, withState(job, "FAILED", "DONE", List.of("JOB_TIMEOUT"), null, null));
                return;
            }
            try (UsageContext.Scope ignored = UsageContext.open(bookId, job.sourcePageNumber(), "JEV_DECISION")) {
                execute(bookId, job, control);
            }
        } catch (Exception e) {
            try {
                DecisionStore.DecisionJob job = queryJob(bookId, jobId);
                if (!isTerminal(job.state()))
                    persistTerminal(bookId, withState(job, "FAILED", "DONE",
                            List.of("INTERNAL_ERROR"), null, null));
            } catch (Exception ignored) {
            }
        } finally {
            controls.remove(jobId);
            releaseLease(jobId);
        }
    }

    private void execute(String bookId, DecisionStore.DecisionJob queued, JobControl control) {
        Instant now = Instant.now();
        DecisionStore.DecisionJob running = withState(queued, "RUNNING", "LOCATING",
                queued.reasonCodes(), null, null);
        try {
            decisions.saveJob(bookId, running);
        } catch (IOException e) {
            persistTerminal(bookId, withState(running, "FAILED", "DONE", List.of("STAGE_PERSISTENCE_FAILED"), null, null));
            return;
        }
        BooleanSupplier cancelled = () -> control.cancelled.get() || Thread.currentThread().isInterrupted();
        try {
            // 锁外快照：页面、原文、PDF 身份先冻结，再外呼
            Page page = readPageOr404(bookId, queued.sourcePageNumber());
            if (queued.target() != null) {
                int rev = BookStore.revisionOrZero(page);
                if (rev != queued.target().pageRevision()) {
                    persistTerminal(bookId, withState(running, "STALE", "DONE",
                            List.of("PAGE_REVISION_ADVANCED"), null, null));
                    return;
                }
            }
            Block block = findBlockOrNull(page, queued.blockId());
            if (block == null) {
                persistTerminal(bookId, withState(running, "STALE", "DONE",
                        List.of("BLOCK_NOT_FOUND"), null, null));
                return;
            }
            ContentIssue issue = findIssueOrNull(block, queued.issueId());
            if (issue == null) {
                persistTerminal(bookId, withState(running, "STALE", "DONE",
                        List.of("ISSUE_NOT_FOUND"), null, null));
                return;
            }
            String basis = IssueBasis.basisHash(block, issue);
            if (queued.target() != null && !queued.target().issueBasisHash().equals(basis)) {
                persistTerminal(bookId, withState(running, "STALE", "DONE",
                        List.of("ISSUE_BASIS_CHANGED"), null, null));
                return;
            }
            String frozenOriginal = block.original();
            DecisionModels.IssueRef.checkSpan(frozenOriginal, issue.start(), issue.end());
            String pdfHash = pdfIdentity.sha256(store.pdf(bookId));
            String originalHash = DecisionHash.sha256Hex(frozenOriginal);
            String spanHash = DecisionHash.sha256Hex(
                    block.id() + "\u0000" + issue.start() + "\u0000" + issue.end()
                            + "\u0000" + frozenOriginal.substring(issue.start(), issue.end()));
            DecisionModels.IssueRef ref = new DecisionModels.IssueRef(bookId, pdfHash,
                    page.pageNumber(), BookStore.revisionOrZero(page), block.id(), issue.id(),
                    originalHash, basis, issue.start(), issue.end(), spanHash,
                    IssueBasis.MAPPING_VERSION);
            String target = frozenOriginal.substring(issue.start(), issue.end());
            running = withState(running, "RUNNING", "COLLECTING", running.reasonCodes(), null, null);
            try {
                decisions.saveJob(bookId, running);
            } catch (IOException e) {
                persistTerminal(bookId, withState(running, "FAILED", "DONE", List.of("STAGE_PERSISTENCE_FAILED"), null, null));
                return;
            }

            // 候选收集（含受控新增视觉，上限默认 1）
            AtomicInteger freshCalls = new AtomicInteger();
            EvidenceCollector.Collection collected = evidence.collect(ref, frozenOriginal, block,
                    issue, bookId, page, queued.allowFreshVision(), false, freshCalls, cancelled);
            if (cancelled.getAsBoolean()) {
                persistTerminal(bookId, withState(running, "CANCELLED", "DONE",
                        List.of("CANCELLED_JOB"), null, null));
                return;
            }
            DecisionModels.CandidateSet set;
            try {
                set = resolution.buildSet(ref, frozenOriginal, target, collected.raws(),
                        collected.reasons(), config.getMaxCandidatesPerIssue());
                set = mergeLegacy(set, collected.legacy());
            } catch (IllegalArgumentException e) {
                // 无可用实质候选 → HUMAN_REQUIRED，不是网络失败
                persistVerdict(bookId, running, ref, null, null, null, null, null,
                        DecisionModels.Verdict.HUMAN_REQUIRED, null, List.of("NO_CANDIDATE"),
                        DecisionModels.Applicability.CURRENT, 0, "UNKNOWN", null, null, cancelled,
                        UUID.randomUUID().toString());
                return;
            }
            try {
                decisions.saveCandidateSet(bookId, set);
            } catch (IOException e) {
                persistTerminal(bookId, withState(running, "FAILED", "DONE", List.of("STAGE_PERSISTENCE_FAILED"), null, null));
                return;
            }

            // 状态构建与快照先行持久化
            List<String> neighbors = new java.util.ArrayList<>(neighborTexts(page, block, 2));
            if (bookContext != null) neighbors.add("READ_ONLY_UNVERIFIED_BOOK_CONTEXT=" +
                    bookContext.snapshot(bookId, page.pageNumber()));
            DecisionStateBuilder.BuiltState built;
            try {
                built = stateBuilder.build(ref, frozenOriginal, set, neighbors, false,
                        config.getMaxRequestBytes());
            } catch (DecisionStateBuilder.InputTooLargeException e) {
                persistVerdict(bookId, running, ref, set, null, null, null, null,
                        DecisionModels.Verdict.HUMAN_REQUIRED, null, List.of("INPUT_TOO_LARGE"),
                        DecisionModels.Applicability.CURRENT, 0, "UNKNOWN", null, null, cancelled,
                        UUID.randomUUID().toString());
                return;
            }
            DecisionModels.DecisionSnapshot snapshot =
                    DecisionStateBuilder.snapshot(ref, set.candidateSetHash(), built);
            try {
                decisions.saveSnapshot(bookId, snapshot);
            } catch (IOException e) {
                persistTerminal(bookId, withState(running, "FAILED", "DONE", List.of("STAGE_PERSISTENCE_FAILED"), null, null));
                return;
            }
            running = withState(running, "RUNNING", "COMPARING", running.reasonCodes(), null, null);
            try {
                decisions.saveJob(bookId, running);
            } catch (IOException e) {
                persistTerminal(bookId, withState(running, "FAILED", "DONE", List.of("STAGE_PERSISTENCE_FAILED"), null, null));
                return;
            }

            // 请求内容冻结后才生成 requestHash；相同内容复用已有完成证据，零费用
            String requestHash = requestHash(snapshot, built);
            DecisionModels.DecisionEvidence cached = decisions.findEvidenceByRequestHash(bookId, requestHash);
            String logicalId = UUID.randomUUID().toString();
            if (cached != null) {
                DecisionPolicy.Output policy = policyFor(bookId, snapshot, set, built,
                        cached.choice() == null ? null : new JevDecisionClient.CallResult(cached.choice(),
                                cached.evidenceGap(), Map.of(), cached.usageReported(),
                                cached.reportedModel(), cached.providerRequestId(), cached.responseHash()),
                        cancelled);
                persistTerminal(bookId, withState(running, "SUCCEEDED", "DONE",
                        policy.reasonCodes(), cached.decisionId(), policy.verdict().name()));
                return;
            }

            // 统一外发门控：检查模式、数据授权、供应商与期限（剩余用单调时钟）
            String provider = "MOCK".equalsIgnoreCase(config.getProvider()) ? "MOCK" : "TYPESAFE";
            long remainingNanos = remainingNanos(control);
            if (remainingNanos <= 0) {
                persistTerminal(bookId, withState(running, "FAILED", "DONE", List.of("JOB_TIMEOUT"), null, null));
                return;
            }
            DecisionOutboundGate.GateVerdict gateVerdict = gate.check(new DecisionOutboundGate.GateRequest(
                    DecisionOutboundGate.Purpose.JEV, provider, remainingNanos,
                    new DecisionOutboundGate.CapabilityView(false, config.getApiKey(), config.getModel())));
            if (!gateVerdict.allowed()) {
                persistVerdict(bookId, running, ref, set, snapshot, built, requestHash, logicalId,
                        DecisionModels.Verdict.UNAVAILABLE, null, null,
                        List.of("UNAVAILABLE_" + gateVerdict.reasonCode()),
                        DecisionModels.Applicability.CURRENT, 0, "UNKNOWN", null, null, cancelled,
                        UUID.randomUUID().toString());
                return;
            }
            String attemptId;
            try {
                attemptId = budget.reserve(bookId, "jev-decision", RESERVE_PER_JEV_CALL_MINOR,
                        config.getMonetaryBudgetMinor());
            } catch (IOException e) {
                persistVerdict(bookId, running, ref, set, snapshot, built, requestHash, logicalId,
                        DecisionModels.Verdict.UNAVAILABLE, null, null,
                        List.of("UNAVAILABLE_BUDGET_UNAVAILABLE"),
                        DecisionModels.Applicability.CURRENT, 0, "UNKNOWN", null, null, cancelled,
                        UUID.randomUUID().toString());
                return;
            }
            if (attemptId == null) {
                persistVerdict(bookId, running, ref, set, snapshot, built, requestHash, logicalId,
                        DecisionModels.Verdict.UNAVAILABLE, null, null,
                        List.of("UNAVAILABLE_BUDGET_REJECTED"),
                        DecisionModels.Applicability.CURRENT, 0, "UNKNOWN", null, null, cancelled,
                        UUID.randomUUID().toString());
                return;
            }
            boolean sent = false;
            String physicalId = attemptId;
            try {
                if (cancelled.getAsBoolean()) {
                    try { budget.releaseNotSent(bookId, physicalId); } catch (IOException ignored) {}
                    persistTerminal(bookId, withState(running, "CANCELLED", "DONE",
                            List.of("CANCELLED_JOB"), null, null));
                    return;
                }
                budget.markSendIntent(bookId, physicalId);
                sent = true;
                Map<String, Object> state = new LinkedHashMap<>(built.state());
                long jevRemainingNanos = remainingNanos(control);
                if (jevRemainingNanos <= 0) {
                    persistTerminal(bookId, withState(running, "FAILED", "DONE", List.of("JOB_TIMEOUT"), null, null));
                    return;
                }
                long jevAttemptNanos = Math.min(
                        Duration.ofSeconds(Math.max(1, config.getJevAttemptDeadlineSeconds())).toNanos(),
                        jevRemainingNanos);
                JevDecisionClient.CallResult call = jev.callOnce(endpointUrl(), config.getApiKey(),
                        config.getModel(), state, built.questions(),
                        jevAttemptNanos,
                        config.getMaxRequestBytes(), config.getMaxResponseBytes(), cancelled);
                // M1 无可靠计费：usage 仅记录，费用记 UNKNOWN 并保留预留
                try { budget.retainUnknown(bookId, physicalId); } catch (IOException ignored) {}
                DecisionPolicy.Output policy = policyFor(bookId, snapshot, set, built, call, cancelled);
                persistVerdict(bookId, running, ref, set, snapshot, built, requestHash, logicalId,
                        policy.verdict(), policy.modelPreferredCandidateId(), policy.admittedRecommendationId(),
                        policy.reasonCodes(),
                        policy.applicability(), RESERVE_PER_JEV_CALL_MINOR, "UNKNOWN",
                        scoresJson(call), call, cancelled, physicalId);
            } catch (JevDecisionClient.JevCallException e) {
                if (!sent) {
                    try { budget.releaseNotSent(bookId, physicalId); } catch (IOException ignored) {}
                } else {
                    try { budget.retainUnknown(bookId, physicalId); } catch (IOException ignored) {}
                }
                // 取消走 CANCELLED；其余失败分别展示，由用户明确重试产生新 attempt
                if (e.kind() == JevDecisionClient.Kind.CANCELLED || control.cancelled.get())
                    persistTerminal(bookId, withState(running, "CANCELLED", "DONE",
                            List.of("CANCELLED_JOB"), null, null));
                else
                    persistTerminal(bookId, withState(running, "FAILED", "DONE",
                            List.of("JEV_" + e.kind().name()), null, null));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // Never print provider exceptions: upstream messages may contain credentials or source text.
            persistTerminal(bookId, withState(running, "FAILED", "DONE",
                    List.of("INTERNAL_ERROR"), null, null));
        }
    }

    private String endpointUrl() {
        // Mock 只走回环 dummy 地址（传输层忽略 URL，只解析请求体）；真实通道走冻结官方地址
        if ("MOCK".equalsIgnoreCase(config.getProvider())) return "http://127.0.0.1:9/decision-mock";
        return ENDPOINT_URL;
    }

    private String endpointIdentity() {
        if ("MOCK".equalsIgnoreCase(config.getProvider())) return "mock-synthetic-test";
        return ENDPOINT_IDENTITY;
    }

    private DecisionPolicy.Output policyFor(String bookId, DecisionModels.DecisionSnapshot snapshot,
                                            DecisionModels.CandidateSet set,
                                            DecisionStateBuilder.BuiltState built,
                                            JevDecisionClient.CallResult call,
                                            BooleanSupplier cancelled) {
        Page current;
        try {
            current = store.readPage(bookId, snapshot.issueRef().sourcePageNumber());
        } catch (Exception e) {
            current = null;
        }
        DecisionPolicy.CurrentView view = null;
        if (current != null) {
            Block block = findBlockOrNull(current, snapshot.issueRef().blockId());
            ContentIssue issue = block == null ? null
                    : findIssueOrNull(block, snapshot.issueRef().issueId());
            if (block != null && issue != null && block.original() != null) {
                view = new DecisionPolicy.CurrentView(bookId, snapshot.issueRef().pdfSha256(),
                        current.pageNumber(), BookStore.revisionOrZero(current), block.id(),
                        issue.id(), DecisionHash.sha256Hex(block.original()),
                        IssueBasis.basisHash(block, issue),
                        DecisionHash.sha256Hex(block.id() + "\u0000" + issue.start() + "\u0000"
                                + issue.end() + "\u0000" + block.original().substring(
                                        issue.start(), issue.end())));
            }
        }
        String currentText = null;
        try {
            Page saved = store.readPage(bookId, snapshot.issueRef().sourcePageNumber());
            Block block = findBlockOrNull(saved, snapshot.issueRef().blockId());
            if (block != null && block.original() != null)
                currentText = block.original().substring(
                        snapshot.issueRef().startUtf16(), snapshot.issueRef().endUtf16());
        } catch (Exception ignored) {
        }
        // A configured calibration string must be bound to the model actually used; a settings edit
        // or a provider-side model switch cannot silently retain formal recommendation eligibility.
        String calibratedStatus = config.getCalibrationStatus();
        String profile = config.getCalibrationProfile();
        String expectedModelPart = "model=" + config.getModel();
        if (profile == null || java.util.Arrays.stream(profile.split("\\|")).noneMatch(expectedModelPart::equals)
                || call == null || call.reportedModel() == null || !call.reportedModel().equals(config.getModel()))
            calibratedStatus = "UNVALIDATED";
        return DecisionPolicy.resolve(new DecisionPolicy.Input(snapshot, set,
                built.aliasToCandidateId(), currentText, call, null, cancelled.getAsBoolean(), view,
                false, built.hardRiskFlags(), false, calibratedStatus,
                config.getCalibrationProfile(),
                DecisionPolicy.PILOT_DEFAULT));
    }

    private void persistVerdict(String bookId, DecisionStore.DecisionJob running,
                                DecisionModels.IssueRef ref, DecisionModels.CandidateSet set,
                                DecisionModels.DecisionSnapshot snapshot,
                                DecisionStateBuilder.BuiltState built, String requestHash,
                                String logicalId, DecisionModels.Verdict verdict,
                                String modelPreferredCandidateId, String admittedRecommendationId,
                                List<String> reasonCodes,
                                DecisionModels.Applicability applicability, long reservedMinor,
                                String costStatus, String scoresJson,
                                JevDecisionClient.CallResult call, BooleanSupplier cancelled,
                                String physicalId) {
        Instant now = Instant.now();
        String decisionId = UUID.randomUUID().toString();
        DecisionModels.NormalizedChoice choice = call == null ? null : call.choice();
        DecisionModels.NormalizedNoul gap = call == null ? null : call.gap();
        Map<String, Long> usage = call == null ? null : call.usage();
        String reportedModel = call == null ? null : call.reportedModel();
        String requestId = call == null ? null : call.providerRequestId();
        String responseHash = call == null
                ? DecisionHash.sha256Hex("no-call:" + logicalId) : call.responseHash();
        DecisionModels.ExecutionStatus execStatus;
        String cacheRequestHash;
        if (call == null || call.choice() == null || verdict == DecisionModels.Verdict.UNAVAILABLE) {
            execStatus = DecisionModels.ExecutionStatus.FAILED;
            cacheRequestHash = "none";
        } else {
            execStatus = DecisionModels.ExecutionStatus.SUCCEEDED;
            cacheRequestHash = requestHash == null ? "none" : requestHash;
        }
        DecisionModels.DecisionEvidence evidence = new DecisionModels.DecisionEvidence(
                DecisionStore.SCHEMA_VERSION, decisionId, logicalId, physicalId,
                snapshot == null ? "none" : snapshot.snapshotHash(),
                set == null ? "none" : set.candidateSetHash(),
                cacheRequestHash,
                DecisionStateBuilder.TEMPLATE_VERSION,
                config.getProvider(), endpointIdentity(), config.getModel(), reportedModel,
                PROVIDER_CONTRACT_VERSION, responseHash, requestId,
                execStatus, choice, gap,
                DecisionPolicy.POLICY_VERSION, DecisionPolicy.THRESHOLD_PROFILE,
                verdict, applicability, reasonCodes == null ? List.of() : reasonCodes, List.of(),
                usage, null, reservedMinor,
                "REPORTED".equals(costStatus) ? DecisionModels.CostStatus.REPORTED
                        : "ESTIMATED".equals(costStatus) ? DecisionModels.CostStatus.ESTIMATED
                        : DecisionModels.CostStatus.UNKNOWN,
                scoresJson, admittedRecommendationId, endpointIdentity(),
                now, now, now, running.deadlineAt());
        try {
            decisions.saveResult(bookId, evidence);
        } catch (IOException e) {
            persistTerminal(bookId, withState(running, "FAILED", "DONE",
                    List.of("PERSIST_FAILED"), null, null));
            return;
        }
        persistTerminal(bookId, withState(running, "SUCCEEDED", "DONE", reasonCodes,
                decisionId, verdict.name()));
    }

    private void persistVerdict(String bookId, DecisionStore.DecisionJob running,
                                DecisionModels.IssueRef ref, DecisionModels.CandidateSet set,
                                DecisionModels.DecisionSnapshot snapshot,
                                DecisionStateBuilder.BuiltState built, String requestHash,
                                String logicalId, DecisionModels.Verdict verdict,
                                String recommendedCandidateId, List<String> reasonCodes,
                                DecisionModels.Applicability applicability, long reservedMinor,
                                String costStatus, String scoresJson,
                                JevDecisionClient.CallResult call, BooleanSupplier cancelled,
                                String physicalId) {
        String admitted = (verdict == DecisionModels.Verdict.RECOMMEND || verdict == DecisionModels.Verdict.KEEP_CURRENT)
                ? recommendedCandidateId : null;
        persistVerdict(bookId, running, ref, set, snapshot, built, requestHash, logicalId,
                verdict, recommendedCandidateId, admitted, reasonCodes, applicability,
                reservedMinor, costStatus, scoresJson, call, cancelled, physicalId);
    }

    private String scoresJson(JevDecisionClient.CallResult call) {
        if (call == null || call.scores() == null || call.scores().isEmpty()) return null;
        Map<String, Object> scores = new LinkedHashMap<>();
        for (Map.Entry<String, JevDecisionClient.ScoreAnswer> e : call.scores().entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("score", e.getValue().score());
            entry.put("probabilities", e.getValue().probabilities());
            entry.put("confidence", e.getValue().confidence());
            scores.put(e.getKey(), entry);
        }
        return CanonicalJson.write(scores);
    }

    private long remainingNanos(JobControl control) {
        // JR-06-T04：进程内剩余 = 入队总期限剩余（墙钟一次采样） - 单调已耗；不逐段重置
        long elapsed = System.nanoTime() - control.runStartNano;
        long remaining = control.wallRemainingAtRunStartNanos - elapsed;
        return remaining;
    }

    private String requestHash(DecisionModels.DecisionSnapshot snapshot,
                               DecisionStateBuilder.BuiltState built) {
        List<String> aliases = new ArrayList<>(built.aliasToCandidateId().keySet());
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("snapshotHash", snapshot.snapshotHash());
        material.put("aliasesInOrder", aliases);
        material.put("templateVersion", DecisionStateBuilder.TEMPLATE_VERSION);
        material.put("provider", config.getProvider());
        material.put("endpointIdentity", endpointIdentity());
        material.put("model", config.getModel());
        material.put("policyVersion", DecisionPolicy.POLICY_VERSION);
        material.put("serializedRequest", DecisionHash.sha256Hex(CanonicalJson.write(built.state())));
        return DecisionHash.of(material);
    }

    DecisionModels.CandidateSet mergeLegacy(DecisionModels.CandidateSet set,
                                            List<DecisionModels.Candidate> legacy) {
        return resolution.mergeLegacy(set, legacy);
    }

    private List<String> neighborTexts(Page page, Block block, int window) {
        List<Block> ordered = page.blocks().stream().filter(b -> b != null)
                .sorted((a, b) -> Integer.compare(a.order(), b.order())).toList();
        int index = -1;
        for (int i = 0; i < ordered.size(); i++)
            if (ordered.get(i).id().equals(block.id())) { index = i; break; }
        if (index < 0) return List.of();
        List<String> neighbors = new ArrayList<>();
        for (int i = Math.max(0, index - window); i < Math.min(ordered.size(), index + window + 1); i++) {
            if (i == index) continue;
            String original = ordered.get(i).original();
            if (original != null && !original.isBlank()) neighbors.add(original);
        }
        return neighbors;
    }

    private void finishCancelIfRequested(String bookId, DecisionStore.DecisionJob job) {
        if ("CANCEL_REQUESTED".equals(job.state()))
            persistTerminal(bookId, withState(job, "CANCELLED", "DONE",
                    List.of("CANCELLED_JOB"), null, null));
    }

    private void persistTerminal(String bookId, DecisionStore.DecisionJob job) {
        synchronized (bookLock(bookId)) {
            DecisionStore.DecisionJob current = queryJob(bookId, job.jobId());
            if (current != null && isTerminal(current.state())) {
                return; // JR-06-T03: 终态不倒退、不被覆盖
            }
            // JR-06-T03：取消与 worker 竞态时 worker 负责收尾 CANCEL_REQUESTED→CANCELLED；
            // 版本已推进时按当前版本+1 重建终态，不丢弃取消、不倒退
            if (current != null && current.stateVersion() != job.stateVersion() - 1) {
                if ("CANCELLED".equals(job.state())
                        && "CANCEL_REQUESTED".equals(current.state())) {
                    DecisionStore.DecisionJob fixed = withState(current, "CANCELLED", "DONE",
                            job.reasonCodes(), job.decisionId(), job.verdict());
                    try {
                        decisions.saveJob(bookId, fixed);
                    } catch (IOException e) {
                        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业保存失败");
                    }
                    return;
                }
                // worker 旧版本终态写与取消/新状态冲突时保留新状态，不覆盖
                return;
            }
            try {
                decisions.saveJob(bookId, job);
            } catch (IOException e) {
                // JR-06-T06：终态落盘失败必须显式失败，不吞异常制造成功假象
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业保存失败");
            }
        }
    }

    /** JR-06-T03：CAS 更新；期望版本不符抛 409，不覆盖新状态。 */
    void casSaveJob(String bookId, DecisionStore.DecisionJob next, int expectedVersion) {
        synchronized (bookLock(bookId)) {
            DecisionStore.DecisionJob current = queryJob(bookId, next.jobId());
            if (current == null)
                throw new ApiException(HttpStatus.NOT_FOUND, "决策作业不存在");
            if (current.stateVersion() != expectedVersion)
                throw new ApiException(HttpStatus.CONFLICT, "作业状态已变化，请刷新后重试");
            if (isTerminal(current.state()))
                throw new ApiException(HttpStatus.CONFLICT, "作业已终结");
            try {
                decisions.saveJob(bookId, next);
            } catch (IOException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业保存失败");
            }
        }
    }

    private void saveJobQuietly(String bookId, DecisionStore.DecisionJob job) {
        try {
            decisions.saveJob(bookId, job);
        } catch (IOException ignored) {
        }
    }

    private DecisionStore.DecisionJob withState(DecisionStore.DecisionJob job, String state,
                                                String stage, List<String> reasons,
                                                String decisionId, String verdict) {
        return new DecisionStore.DecisionJob(job.jobId(), state, job.stateVersion() + 1, stage,
                job.admissionKey(), job.requestHash(), job.bookId(), job.sourcePageNumber(),
                job.blockId(), job.issueId(), job.snapshotHash(), job.candidateSetHash(), decisionId,
                job.clientOperationId(), verdict, reasons == null ? List.of() : reasons,
                job.reservedCostMinor(), job.costStatus(), job.cancellationState(),
                job.createdAt(), Instant.now(), job.deadlineAt(), job.allowFreshVision(),
                job.target());
    }

    private Block findBlock(Page page, String blockId) {
        Block block = findBlockOrNull(page, blockId);
        if (block == null) throw new ApiException(HttpStatus.NOT_FOUND, "指定的块不存在");
        return block;
    }

    private Block findBlockOrNull(Page page, String blockId) {
        if (page.blocks() == null) return null;
        return page.blocks().stream().filter(b -> b != null && blockId.equals(b.id()))
                .findFirst().orElse(null);
    }

    private ContentIssue findIssue(Block block, String issueId) {
        ContentIssue issue = findIssueOrNull(block, issueId);
        if (issue == null) throw new ApiException(HttpStatus.NOT_FOUND, "指定的问题不存在");
        return issue;
    }

    private ContentIssue findIssueOrNull(Block block, String issueId) {
        if (block.issues() == null) return null;
        return block.issues().stream().filter(i -> i != null && issueId.equals(i.id()))
                .findFirst().orElse(null);
    }

    private static final class JobControl {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long runStartNano = System.nanoTime();
        volatile long wallRemainingAtRunStartNanos = Long.MAX_VALUE;
    }

    /** 测试用：同步执行单作业，不经过队列线程。 */
    public void runInline(String bookId, String jobId) {
        run(bookId, jobId);
    }
}
