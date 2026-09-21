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
    private final DecisionStore decisions;
    private final BookStore store;

    public ReviewDecisionController(DecisionCoordinator coordinator, DecisionStore decisions,
                                    BookStore store) {
        this.coordinator = coordinator;
        this.decisions = decisions;
        this.store = store;
    }

    public record CreateDecisionJobBody(String clientOperationId, String blockId,
                                        Integer expectedPageRevision, String issueBasisHash,
                                        Boolean allowFreshVision) {}

    public record CancelBody(Integer expectedStateVersion) {}

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
        response.put("decisions", List.of());
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
            view.put("recommendedCandidateId", recommendedOf(job));
            view.put("reasonCodes", job.reasonCodes());
        } else {
            view.put("reasonCodes", job.reasonCodes());
        }
        view.put("cloudCalls", job.reservedCostMinor());
        view.put("usageStatus", job.costStatus());
        view.put("cancellationState", job.cancellationState());
        return view;
    }

    private String applicabilityOf(DecisionStore.DecisionJob job) {
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

    private String recommendedOf(DecisionStore.DecisionJob job) {
        try {
            var evidence = decisions.loadResult(job.bookId(), job.decisionId());
            if (evidence.isEmpty() || evidence.get().choice() == null) return null;
            // 别名按候选顺序确定派生，读时同样推导；只在有明确候选的 verdict 下返回
            String verdict = job.verdict();
            if (!"RECOMMEND".equals(verdict) && !"KEEP_CURRENT".equals(verdict)
                    && !"CANDIDATES_ONLY".equals(verdict) && !"HUMAN_REQUIRED".equals(verdict))
                return null;
            var set = decisions.loadCandidateSet(job.bookId(), evidence.get().candidateSetHash());
            if (set.isEmpty()) return null;
            return DecisionStateBuilder.candidateIdForAlias(set.get(),
                    evidence.get().choice().selectedAlias());
        } catch (Exception e) {
            return null;
        }
    }
}
