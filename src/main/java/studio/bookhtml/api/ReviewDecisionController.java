package studio.bookhtml.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import studio.bookhtml.decision.DecisionAcceptService;
import studio.bookhtml.decision.DecisionCoordinator;
import studio.bookhtml.decision.DecisionModels;
import studio.bookhtml.decision.DecisionStateBuilder;
import studio.bookhtml.decision.DecisionStore;
import studio.bookhtml.store.BookStore;

/**
 * J07：决策作业与建议查询接口（新增应用接口，不是 TypeSafe API）。
 * 只做参数校验、作用域与服务调用；不持有模型密钥的前端能力；
 * 不返回 token/Authorization/供应商原始 debug body/外部可执行链接。
 */
@RestController
@RequestMapping("/api")
public class ReviewDecisionController {
    private final DecisionCoordinator coordinator;
    private final DecisionAcceptService acceptService;
    private final DecisionStore decisions;
    private final BookStore store;
    private final studio.bookhtml.config.DecisionProperties decisionConfig;
    private final studio.bookhtml.decision.DecisionBudget budget;

    public ReviewDecisionController(DecisionCoordinator coordinator, DecisionAcceptService acceptService,
                                    DecisionStore decisions, BookStore store) {
        this(coordinator, acceptService, decisions, store, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public ReviewDecisionController(DecisionCoordinator coordinator, DecisionAcceptService acceptService,
                                    DecisionStore decisions, BookStore store,
                                    studio.bookhtml.config.DecisionProperties decisionConfig,
                                    studio.bookhtml.decision.DecisionBudget budget) {
        this.coordinator = coordinator;
        this.acceptService = acceptService;
        this.decisions = decisions;
        this.store = store;
        this.decisionConfig = decisionConfig;
        this.budget = budget;
    }

    public record CreateDecisionJobBody(String clientOperationId, String blockId,
                                        Integer expectedPageRevision, String issueBasisHash,
                                        Boolean allowFreshVision) {}

    public record CancelBody(Integer expectedStateVersion) {}

    public record AcceptDecisionBody(String clientOperationId, String blockId,
                                     Integer expectedPageRevision, String issueBasisHash,
                                     String candidateSetHash, String candidateId,
                                     Boolean userAttestedSourceCheck) {}

    @RequestMapping(value = "/books/{bookId}/pages/{page}/issues/{issueId}/decisions/{decisionId}/accept",
            method = RequestMethod.POST)
    public Map<String, Object> accept(@PathVariable("bookId") String bookId,
                                      @PathVariable("page") int page,
                                      @PathVariable("issueId") String issueId,
                                      @PathVariable("decisionId") String decisionId,
                                      @RequestBody(required = false) AcceptDecisionBody body)
            throws java.io.IOException {
        if (body == null || body.clientOperationId() == null || body.blockId() == null
                || body.expectedPageRevision() == null || body.issueBasisHash() == null
                || body.candidateSetHash() == null || body.candidateId() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "参数非法");
        DecisionAcceptService.AcceptResult result = acceptService.accept(bookId, page, issueId,
                decisionId, new DecisionAcceptService.AcceptBody(body.clientOperationId(),
                        body.blockId(), body.expectedPageRevision(), body.issueBasisHash(),
                        body.candidateSetHash(), body.candidateId(),
                        Boolean.TRUE.equals(body.userAttestedSourceCheck())));
        Map<String, Object> response = new LinkedHashMap<>();
        // 成功后返回此次实际 committed Page/revision，不再 readPage 取可能被推进的版本
        response.put("pageRevision", BookStore.revisionOrZero(result.committed()));
        response.put("idempotent", result.idempotent());
        response.put("resolved", true);
        response.put("issueId", issueId);
        response.put("decisionId", decisionId);
        return response;
    }

    @RequestMapping(value = "/books/{bookId}/pages/{page}/issues/{issueId}/decision-jobs",
            method = RequestMethod.POST)
    public ResponseEntity<Map<String, Object>> create(
            @PathVariable("bookId") String bookId,
            @PathVariable("page") int page,
            @PathVariable("issueId") String issueId,
            @RequestBody(required = false) CreateDecisionJobBody body) {
        if (body == null || body.clientOperationId() == null || body.blockId() == null
                || body.expectedPageRevision() == null || body.issueBasisHash() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "参数非法");
        DecisionCoordinator.CreateBody request = new DecisionCoordinator.CreateBody(
                body.clientOperationId(), body.blockId(), body.expectedPageRevision(),
                body.issueBasisHash(), Boolean.TRUE.equals(body.allowFreshVision()));
        DecisionCoordinator.CreateResult result =
                coordinator.createOrReuse(bookId, page, issueId, request);
        return ResponseEntity.status(result.httpStatus()).body(jobView(result.job()));
    }

    @RequestMapping(value = "/books/{bookId}/decision-jobs/{jobId}", method = RequestMethod.GET)
    public Map<String, Object> query(@PathVariable("bookId") String bookId,
                                     @PathVariable("jobId") String jobId) {
        return jobView(coordinator.queryJob(bookId, jobId));
    }

    @RequestMapping(value = "/books/{bookId}/decision-jobs/{jobId}/cancel",
            method = RequestMethod.POST)
    public Map<String, Object> cancel(@PathVariable("bookId") String bookId,
                                      @PathVariable("jobId") String jobId,
                                      @RequestBody(required = false) CancelBody body) {
        if (body == null || body.expectedStateVersion() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "参数非法");
        return jobView(coordinator.cancel(bookId, jobId, body.expectedStateVersion()));
    }

    @RequestMapping(value = "/books/{bookId}/pages/{page}/issues/{issueId}/decisions",
            method = RequestMethod.GET)
    public Map<String, Object> decisions(@PathVariable("bookId") String bookId,
                                         @PathVariable("page") int page,
                                         @PathVariable("issueId") String issueId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("bookId", bookId);
        response.put("sourcePageNumber", page);
        response.put("issueId", issueId);
        response.put("basis", coordinator.issueBasisView(bookId, page, issueId));
        response.put("current", coordinator.currentDecisionView(bookId, page, issueId));
        response.put("history", coordinator.decisionHistory(bookId, page, issueId, 10));
        // JR-08-T01：前端模式门需要知道当前模式；SHADOW/OFF 下不展示正式推荐接受入口
        try {
            response.put("decisionMode", decisionConfig == null ? "UNKNOWN" : decisionConfig.getMode());
        } catch (Exception ignored) {
            response.put("decisionMode", "UNKNOWN");
        }
        return response;
    }

    /** 轻量状态与结果链接（11.3 必要字段；进度按实际阶段显示，不用假百分比）。 */
    private Map<String, Object> jobView(DecisionStore.DecisionJob job) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", job.jobId());
        view.put("jobState", job.state());
        view.put("stateVersion", job.stateVersion());
        view.put("progressStage", job.progressStage());
        Map<String, Object> issueRef = new LinkedHashMap<>();
        issueRef.put("bookId", job.bookId());
        issueRef.put("sourcePageNumber", job.sourcePageNumber());
        issueRef.put("blockId", job.blockId());
        issueRef.put("issueId", job.issueId());
        view.put("issueRef", issueRef);
        if (job.decisionId() != null) {
            view.put("decisionId", job.decisionId());
            view.put("applicability", applicabilityOf(job));
            view.put("verdict", job.verdict());
            view.put("candidateSummaries", candidateSummaries(job));
            view.put("modelPreferredCandidateId", modelPreferredOf(job));
            view.put("admittedRecommendationId", admittedOf(job));
            view.put("recommendedCandidateId", admittedOf(job));
            view.put("reasonCodes", job.reasonCodes());
        } else {
            view.put("reasonCodes", job.reasonCodes());
        }
        view.put("physicalAttemptCount", physicalAttemptCount(job));
        view.put("visionAttemptCount", visionAttemptCount(job));
        view.put("jevAttemptCount", jevAttemptCount(job));
        view.put("reservedCostMinor", job.reservedCostMinor());
        view.put("knownCostMinor", knownCostMinor(job));
        view.put("unknownCostStatus", unknownCostStatus(job));
        view.put("costStatus", job.costStatus());
        // JR-05-T06：不再把 reserved 误标为 cloudCalls；保留字段仅作兼容并如实为物理次数
        view.put("cloudCalls", physicalAttemptCount(job));
        view.put("usageStatus", job.costStatus());
        view.put("cancellationState", job.cancellationState());
        return view;
    }

    /** JR-05-T06：物理次数与费用分开；未知费用单列，绝不把 reserved 记成金额或 cloudCalls。 */
    private int physicalAttemptCount(DecisionStore.DecisionJob job) {
        return visionAttemptCount(job) + jevAttemptCount(job);
    }

    private int visionAttemptCount(DecisionStore.DecisionJob job) {
        // 作业创建时的 allowFreshVision 只表示请求意图；实际视觉次数以执行期 collector 为准。
        // 当前作业记录未持久化分项计数时，按“无完成证据=0，有 JEV 预留=按执行路径推导”保守返回，
        // 并优先从预算账本按 purpose 计数（账本为权威）。
        int fromLedger = countAttemptsByPurpose(job.bookId(), "vision-crop");
        if (fromLedger >= 0) {
            // 账本是整书累计，不能归因到单作业时返回 0/1 的作业级保守值 + 书级总量字段
            // 这里返回作业级：仅当本作业有完成证据且非零费用时不虚报
            return job.decisionId() == null ? 0 : Math.min(fromLedger, 1);
        }
        return 0;
    }

    private int jevAttemptCount(DecisionStore.DecisionJob job) {
        if (job.decisionId() == null) return 0;
        // JEV 每次执行最多一次物理 attempt（JR-04 上限 1）；有 decisionId 且有预留即 1
        return job.reservedCostMinor() > 0 ? 1 : 0;
    }

    private long knownCostMinor(DecisionStore.DecisionJob job) {
        if (budget == null) return 0;
        try {
            return budget.totals(job.bookId())[1];
        } catch (Exception e) {
            return -1;
        }
    }

    private String unknownCostStatus(DecisionStore.DecisionJob job) {
        if (budget == null) return "UNKNOWN";
        try {
            long[] totals = budget.totals(job.bookId());
            return totals[0] > 0 ? "UNKNOWN_RETAINED" : "NONE";
        } catch (Exception e) {
            return "BUDGET_UNAVAILABLE";
        }
    }

    private int countAttemptsByPurpose(String bookId, String purposePrefix) {
        if (budget == null) return -1;
        try {
            // DecisionBudget.totals 不分 purpose；这里通过 store 扫描（有界）计数，失败返回 -1
            return decisions.countAttemptsByPurpose(bookId, purposePrefix);
        } catch (Exception e) {
            return -1;
        }
    }

    private String applicabilityOf(DecisionStore.DecisionJob job) {
        // JR-08-T06：读取缓存时重算当前适用性，不复用 applicabilityAtWrite 旧标签
        try {
            String recomputed = coordinator.applicability(job.bookId(), job);
            if (recomputed != null) return recomputed;
        } catch (Exception ignored) {
        }
        try {
            var evidence = decisions.loadResult(job.bookId(), job.decisionId());
            if (evidence.isEmpty()) return "STALE";
            return evidence.get().applicabilityAtWrite().name();
        } catch (Exception e) {
            return "STALE";
        }
    }

    private List<Map<String, Object>> candidateSummaries(DecisionStore.DecisionJob job) {
        try {
            var evidence = decisions.loadResult(job.bookId(), job.decisionId());
            if (evidence.isEmpty()) return List.of();
            var set = decisions.loadCandidateSet(job.bookId(), evidence.get().candidateSetHash());
            if (set.isEmpty()) return List.of();
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (DecisionModels.Candidate candidate : set.get().candidates()) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("candidateId", candidate.candidateId());
                summary.put("displayText", candidate.simplifiedDisplayText() == null
                        ? "" : candidate.simplifiedDisplayText());
                summary.put("sourceKind", candidate.sourceKind().name());
                summary.put("alignment", candidate.alignmentStatus().name());
                summaries.add(summary);
            }
            return summaries;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String modelPreferredOf(DecisionStore.DecisionJob job) {
        try {
            var evidence = decisions.loadResult(job.bookId(), job.decisionId());
            if (evidence.isEmpty() || evidence.get().choice() == null) return null;
            var set = decisions.loadCandidateSet(job.bookId(), evidence.get().candidateSetHash());
            if (set.isEmpty()) return null;
            return DecisionStateBuilder.candidateIdForAlias(set.get(),
                    evidence.get().choice().selectedAlias());
        } catch (Exception e) {
            return null;
        }
    }

    private String admittedOf(DecisionStore.DecisionJob job) {
        // JR-08-T01/T06：正式推荐 = RECOMMEND/KEEP_CURRENT + 当前适用 CURRENT + ASSIST 模式；其余一律 null
        String verdict = job.verdict();
        if (!"RECOMMEND".equals(verdict) && !"KEEP_CURRENT".equals(verdict))
            return null;
        try {
            String applicability = coordinator.applicability(job.bookId(), job);
            if (!"CURRENT".equals(applicability)) return null;
        } catch (Exception e) {
            return null;
        }
        try {
            String mode = decisionConfig == null ? null : decisionConfig.getMode();
            if (!"ASSIST".equalsIgnoreCase(mode)) return null;
        } catch (Exception e) {
            return null;
        }
        return modelPreferredOf(job);
    }

    private String recommendedOf(DecisionStore.DecisionJob job) {
        return admittedOf(job);
    }
}
