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
import studio.bookhtml.domain.Block;
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
    private final JevDecisionClient jev;
    private final DecisionProperties config;
    private final DecisionTransport transport;
    private final ObjectMapper json;

    private final ConcurrentHashMap<String, Object> bookLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JobControl> controls = new ConcurrentHashMap<>();
    private final BlockingQueue<String> queue;
    private Thread worker;

    public DecisionCoordinator(BookStore store, DecisionStore decisions, DecisionBudget budget,
                               PdfIdentity pdfIdentity, CandidateResolutionService resolution,
                               EvidenceCollector evidence, DecisionStateBuilder stateBuilder,
                               JevDecisionClient jev, DecisionProperties config,
                               DecisionTransport transport, ObjectMapper json) {
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
        this.queue = new LinkedBlockingQueue<>(Math.max(1, config.getMaxQueueEntries()));
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
    void recoverInterrupted() {
        // M1：重启恢复需要书目錄存在；QUEUED 重排、RUNNING 标 INTERRUPTED 由 recoverBook 显式执行。
        // start() 不自动全库扫描，避免启动时触碰无关书籍目录。
    }

    /** 指定书的重启恢复（T43）：QUEUED 重排；COMPARING 及之后视为已发送未知，标 INTERRUPTED。 */
    public void recoverBook(String bookId) {
        List<DecisionStore.DecisionJob> jobs;
        try {
            jobs = decisions.listJobs(bookId);
        } catch (IOException e) {
            return;
        }
        for (DecisionStore.DecisionJob job : jobs) {
            if ("QUEUED".equals(job.state())) {
                queue.offer(job.jobId() + "\u0000" + bookId);
            } else if ("RUNNING".equals(job.state()) || "CANCEL_REQUESTED".equals(job.state())) {
                boolean maybeSent = List.of("COMPARING", "PERSISTING", "DONE")
                        .contains(job.progressStage());
                saveJobQuietly(bookId, withState(job,
                        maybeSent ? "INTERRUPTED" : "QUEUED",
                        maybeSent ? job.progressStage() : "LOCATING",
                        maybeSent ? List.of("RESTART_INTERRUPTED_MAY_HAVE_SENT")
                                : List.of("RESTART_REQUEUED"),
                        job.decisionId(), job.verdict()));
                if (!maybeSent) queue.offer(job.jobId() + "\u0000" + bookId);
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
        validateCreate(bookId, sourcePage, issueId, body);
        synchronized (bookLock(bookId)) {
            Page page = readPageOr404(bookId, sourcePage);
            Block block = page.blocks().stream().filter(b -> b != null && body.blockId().equals(b.id()))
                    .findFirst().orElseThrow(() ->
                            new ApiException(HttpStatus.NOT_FOUND, "指定的块不存在"));
            ContentIssue issue = block.issues().stream().filter(i -> i != null && issueId.equals(i.id()))
                    .findFirst().orElseThrow(() ->
                            new ApiException(HttpStatus.NOT_FOUND, "指定的问题不存在"));
            int revision = BookStore.revisionOrZero(page);
            if (revision != body.expectedPageRevision())
                throw new ApiException(HttpStatus.CONFLICT, "页面版本已变化，请刷新后重试");
            String basis = IssueBasis.basisHash(block, issue);
            if (!basis.equals(body.issueBasisHash()))
                throw new ApiException(HttpStatus.CONFLICT, "问题基线已变化，请刷新后重试");
            String admissionKey = admissionKey(bookId, page, block, issue, basis, body.allowFreshVision());
            try {
                DecisionStore.DecisionJob existing = decisions.findByAdmission(bookId, admissionKey);
                if (existing != null && isReusable(existing, page)) {
                    return new CreateResult(
                            "SUCCEEDED".equals(existing.state()) ? 200 : 200, existing);
                }
                if (queue.size() >= config.getMaxQueueEntries())
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "决策队列已满");
                Instant now = Instant.now();
                DecisionStore.DecisionJob job = new DecisionStore.DecisionJob(
                        UUID.randomUUID().toString(), "QUEUED", 1, "LOCATING", admissionKey, null,
                        bookId, sourcePage, block.id(), issueId, null, null, null,
                        body.clientOperationId(), null, List.of(), 0, "UNKNOWN", "NONE",
                        now, now, now.plusSeconds(Math.max(1, config.getJobDeadlineSeconds())),
                        body.allowFreshVision());
                decisions.saveJob(bookId, job);
                if (!queue.offer(job.jobId() + "\u0000" + bookId))
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "决策队列已满");
                return new CreateResult(202, job);
            } catch (ApiException e) {
                throw e;
            } catch (IOException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业保存失败");
            }
        }
    }

    private boolean isReusable(DecisionStore.DecisionJob job, Page current) {
        if (job == null) return false;
        if ("QUEUED".equals(job.state()) || "RUNNING".equals(job.state())) return true;
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
        if (BookStore.revisionOrZero(page) != ref.pageRevision()) return false;
        Block block = page.blocks().stream().filter(b -> b != null && ref.blockId().equals(b.id()))
                .findFirst().orElse(null);
        if (block == null || block.original() == null) return false;
        ContentIssue issue = block.issues().stream()
                .filter(i -> i != null && ref.issueId().equals(i.id())).findFirst().orElse(null);
        if (issue == null) return false;
        try {
            return IssueBasis.basisHash(block, issue).equals(ref.issueBasisHash());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    String admissionKey(String bookId, Page page, Block block, ContentIssue issue,
                        String basis, boolean allowFreshVision) {
        String pdfHash;
        try {
            pdfHash = pdfIdentity.sha256(store.pdf(bookId));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF 身份获取失败");
        }
        String evidenceVersion = block.source() == null ? "" : block.source();
        String scope = config.getProvider() + "|" + config.getModel() + "|" + config.isAllowCloudData();
        return DecisionHash.of(Map.of(
                "book", bookId, "pdf", pdfHash, "pageRevision", BookStore.revisionOrZero(page),
                "issueBasis", basis, "acquisitionPolicy", ACQUISITION_POLICY_VERSION,
                "existingEvidence", evidenceVersion, "allowFreshVision", allowFreshVision,
                "authorizationScope", scope));
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
            saveJobQuietly(bookId, cancelled);
            return cancelled;
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
        if (page.blocks() != null) for (Block block : page.blocks()) {
            if (block != null && block.issues() != null) for (ContentIssue issue : block.issues())
                if (issue != null && issueId.equals(issue.id())) return block.id();
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "指定的问题不存在");
    }

    /** 当前建议：最新仍适用的完成作业；过期只在 history 中说明原因。 */
    public Map<String, Object> currentDecisionView(String bookId, int sourcePage, String issueId) {
        for (DecisionStore.DecisionJob job : listIssueJobs(bookId, issueId)) {
            if (!"SUCCEEDED".equals(job.state()) || job.decisionId() == null) continue;
            if (!"CURRENT".equals(applicability(bookId, job))) continue;
            return decisionSummary(bookId, job);
        }
        return null;
    }

    /** 历史建议（最新 10 条）与各自适用性/过期原因。 */
    public List<Map<String, Object>> decisionHistory(String bookId, int sourcePage, String issueId,
                                                     int limit) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (DecisionStore.DecisionJob job : listIssueJobs(bookId, issueId)) {
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

    private List<DecisionStore.DecisionJob> listIssueJobs(String bookId, String issueId) {
        List<DecisionStore.DecisionJob> jobs;
        try {
            jobs = decisions.listJobs(bookId);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "决策作业读取失败");
        }
        List<DecisionStore.DecisionJob> filtered = new ArrayList<>();
        for (DecisionStore.DecisionJob job : jobs)
            if (issueId.equals(job.issueId())) filtered.add(job);
        filtered.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return filtered;
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
                    summary.put("recommendedCandidateId",
                            DecisionStateBuilder.candidateIdForAlias(set.get(), selected));
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
            if (Instant.now().isAfter(job.deadlineAt())) {
                persistTerminal(bookId, withState(job, "FAILED", "DONE", List.of("QUEUE_TIMEOUT"), null, null));
                return;
            }
            execute(bookId, job, control);
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
        }
    }

    private void execute(String bookId, DecisionStore.DecisionJob queued, JobControl control) {
        Instant now = Instant.now();
        DecisionStore.DecisionJob running = withState(queued, "RUNNING", "LOCATING",
                queued.reasonCodes(), null, null);
        saveJobQuietly(bookId, running);
        BooleanSupplier cancelled = () -> control.cancelled.get() || Thread.currentThread().isInterrupted();
        try {
            // 锁外快照：页面、原文、PDF 身份先冻结，再外呼
            Page page = readPageOr404(bookId, queued.sourcePageNumber());
            Block block = findBlock(page, queued.blockId());
            ContentIssue issue = findIssue(block, queued.issueId());
            String frozenOriginal = block.original();
            DecisionModels.IssueRef.checkSpan(frozenOriginal, issue.start(), issue.end());
            String pdfHash = pdfIdentity.sha256(store.pdf(bookId));
            String originalHash = DecisionHash.sha256Hex(frozenOriginal);
            String spanHash = DecisionHash.sha256Hex(
                    block.id() + "\u0000" + issue.start() + "\u0000" + issue.end()
                            + "\u0000" + frozenOriginal.substring(issue.start(), issue.end()));
            String basis = IssueBasis.basisHash(block, issue);
            DecisionModels.IssueRef ref = new DecisionModels.IssueRef(bookId, pdfHash,
                    page.pageNumber(), BookStore.revisionOrZero(page), block.id(), issue.id(),
                    originalHash, basis, issue.start(), issue.end(), spanHash,
                    IssueBasis.MAPPING_VERSION);
            String target = frozenOriginal.substring(issue.start(), issue.end());
            running = withState(running, "RUNNING", "COLLECTING", running.reasonCodes(), null, null);
            saveJobQuietly(bookId, running);

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
                set = resolution.buildSet(ref, frozenOriginal, target, collected.raws());
                set = mergeLegacy(set, collected.legacy());
            } catch (IllegalArgumentException e) {
                // 无可用实质候选 → HUMAN_REQUIRED，不是网络失败
                persistVerdict(bookId, running, ref, null, null, null, null, null,
                        DecisionModels.Verdict.HUMAN_REQUIRED, null, List.of("NO_CANDIDATE"),
                        DecisionModels.Applicability.CURRENT, 0, "UNKNOWN", null, null, cancelled,
                        UUID.randomUUID().toString());
                return;
            }
            decisions.saveCandidateSet(bookId, set);

            // 状态构建与快照先行持久化
            List<String> neighbors = neighborTexts(page, block, 2);
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
            decisions.saveSnapshot(bookId, snapshot);
            running = withState(running, "RUNNING", "COMPARING", running.reasonCodes(), null, null);
            saveJobQuietly(bookId, running);

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

            // 可用性门禁：OFF/缺 key/未授权/预算不足一律禁呼，原流程继续
            String blocked = config.availabilityReason();
            if (blocked != null) {
                persistVerdict(bookId, running, ref, set, snapshot, built, requestHash, logicalId,
                        DecisionModels.Verdict.UNAVAILABLE, null, List.of("UNAVAILABLE_" + blocked),
                        DecisionModels.Applicability.CURRENT, 0, "UNKNOWN", null, null, cancelled,
                        UUID.randomUUID().toString());
                return;
            }
            if (!budget.tryReserve(bookId, RESERVE_PER_JEV_CALL_MINOR, config.getMonetaryBudgetMinor())) {
                persistVerdict(bookId, running, ref, set, snapshot, built, requestHash, logicalId,
                        DecisionModels.Verdict.UNAVAILABLE, null, List.of("UNAVAILABLE_BUDGET_REJECTED"),
                        DecisionModels.Applicability.CURRENT, 0, "UNKNOWN", null, null, cancelled,
                        UUID.randomUUID().toString());
                return;
            }
            boolean sent = false;
            String physicalId = UUID.randomUUID().toString();
            try {
                if (cancelled.getAsBoolean()) {
                    budget.release(bookId, RESERVE_PER_JEV_CALL_MINOR);
                    persistTerminal(bookId, withState(running, "CANCELLED", "DONE",
                            List.of("CANCELLED_JOB"), null, null));
                    return;
                }
                Map<String, Object> state = new LinkedHashMap<>(built.state());
                JevDecisionClient.CallResult call = jev.callOnce(endpointUrl(), config.getApiKey(),
                        config.getModel(), state, built.questions(),
                        Duration.ofSeconds(Math.max(1, config.getJevAttemptDeadlineSeconds())).toNanos(),
                        config.getMaxRequestBytes(), config.getMaxResponseBytes(), cancelled);
                sent = true;
                // M1 无可靠计费：usage 仅记录，费用记 UNKNOWN 并保留预留
                budget.settleUnknown(bookId);
                DecisionPolicy.Output policy = policyFor(bookId, snapshot, set, built, call, cancelled);
                persistVerdict(bookId, running, ref, set, snapshot, built, requestHash, logicalId,
                        policy.verdict(), policy.recommendedCandidateId(), policy.reasonCodes(),
                        policy.applicability(), RESERVE_PER_JEV_CALL_MINOR, "UNKNOWN",
                        scoresJson(call), call, cancelled, physicalId);
            } catch (JevDecisionClient.JevCallException e) {
                if (!sent) budget.release(bookId, RESERVE_PER_JEV_CALL_MINOR);
                else budget.settleUnknown(bookId);
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
            if ("true".equals(System.getenv("DECISION_DEBUG"))) e.printStackTrace(System.out);
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
        return DecisionPolicy.resolve(new DecisionPolicy.Input(snapshot, set,
                built.aliasToCandidateId(), currentText, call, null, cancelled.getAsBoolean(), view,
                false, built.hardRiskFlags(), false, config.getCalibrationStatus(),
                DecisionPolicy.PILOT_DEFAULT));
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
        Instant now = Instant.now();
        String decisionId = UUID.randomUUID().toString();
        DecisionModels.NormalizedChoice choice = call == null ? null : call.choice();
        DecisionModels.NormalizedNoul gap = call == null ? null : call.gap();
        Map<String, Long> usage = call == null ? null : call.usage();
        String reportedModel = call == null ? null : call.reportedModel();
        String requestId = call == null ? null : call.providerRequestId();
        String responseHash = call == null
                ? DecisionHash.sha256Hex("no-call:" + logicalId) : call.responseHash();
        DecisionModels.DecisionEvidence evidence = new DecisionModels.DecisionEvidence(
                DecisionStore.SCHEMA_VERSION, decisionId, logicalId, physicalId,
                snapshot == null ? "none" : snapshot.snapshotHash(),
                set == null ? "none" : set.candidateSetHash(),
                requestHash == null ? "none" : requestHash,
                DecisionStateBuilder.TEMPLATE_VERSION,
                config.getProvider(), endpointIdentity(), config.getModel(), reportedModel,
                PROVIDER_CONTRACT_VERSION, responseHash, requestId,
                DecisionModels.ExecutionStatus.SUCCEEDED, choice, gap,
                DecisionPolicy.POLICY_VERSION, DecisionPolicy.THRESHOLD_PROFILE,
                verdict, applicability, reasonCodes == null ? List.of() : reasonCodes, List.of(),
                usage, null, reservedMinor,
                "REPORTED".equals(costStatus) ? DecisionModels.CostStatus.REPORTED
                        : "ESTIMATED".equals(costStatus) ? DecisionModels.CostStatus.ESTIMATED
                        : DecisionModels.CostStatus.UNKNOWN,
                scoresJson, now, now, now, running.deadlineAt());
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

    private String requestHash(DecisionModels.DecisionSnapshot snapshot,
                               DecisionStateBuilder.BuiltState built) {
        List<String> aliases = new ArrayList<>(built.aliasToCandidateId().keySet());
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("snapshotHash", snapshot.snapshotHash());
        material.put("aliasesInOrder", aliases);
        material.put("templateVersion", DecisionStateBuilder.TEMPLATE_VERSION);
        material.put("endpointIdentity", ENDPOINT_IDENTITY);
        material.put("model", config.getModel());
        material.put("serializedRequest", DecisionHash.sha256Hex(CanonicalJson.write(built.state())));
        return DecisionHash.of(material);
    }

    DecisionModels.CandidateSet mergeLegacy(DecisionModels.CandidateSet set,
                                            List<DecisionModels.Candidate> legacy) {
        if (legacy == null || legacy.isEmpty()) return set;
        List<DecisionModels.Candidate> merged = new ArrayList<>(set.candidates());
        List<String> truncation = new ArrayList<>(set.truncationReasons());
        List<String> gaps = new ArrayList<>(set.evidenceGaps());
        for (DecisionModels.Candidate candidate : legacy) {
            if (merged.size() >= 6) {
                gaps.add("LEGACY_DEFERRED:" + candidate.candidateId());
                continue;
            }
            if (merged.stream().anyMatch(c -> c.candidateId().equals(candidate.candidateId()))) continue;
            merged.add(candidate);
        }
        if (merged.size() == set.candidates().size() && gaps.size() == set.evidenceGaps().size())
            return set;
        String hash = DecisionModels.CandidateSet.computeHash(set.issueRef(), merged,
                CandidateResolutionService.CANDIDATE_CONFIG_VERSION, set.rawCount(),
                set.truncated() || merged.size() != set.candidates().size(), gaps);
        return new DecisionModels.CandidateSet(hash, set.issueRef(), merged,
                CandidateResolutionService.CANDIDATE_CONFIG_VERSION, set.rawCount(), merged.size(),
                set.truncated(), truncation, gaps, set.hasPlaceholder(), set.allSemanticOnly(),
                Instant.now());
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
        saveJobQuietly(bookId, job);
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
                job.createdAt(), Instant.now(), job.deadlineAt(), job.allowFreshVision());
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
    }

    /** 测试用：同步执行单作业，不经过队列线程。 */
    void runInline(String bookId, String jobId) {
        run(bookId, jobId);
    }
}
