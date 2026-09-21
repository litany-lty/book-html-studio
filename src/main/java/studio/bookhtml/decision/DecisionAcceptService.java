package studio.bookhtml.decision;

import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.TraditionalConverter;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.CommitOp;

/**
 * J09：人工接受推荐。服务器从本地持久结果读取 decision/candidate 并核对；
 * 替换正文只信候选的 originalScriptText，不信模型/浏览器返回的任意 replacement；
 * 只改目标 issue；确认来源摘要与 Page 同次持久化；sidecar 无接受索引（可从 Page 重建）。
 */
@Service
public class DecisionAcceptService {
    static final Set<String> ACCEPTABLE_VERDICTS = Set.of(
            "RECOMMEND", "KEEP_CURRENT", "CANDIDATES_ONLY", "HUMAN_REQUIRED", "NEED_MORE_EVIDENCE");

    private final BookStore store;
    private final DecisionStore decisions;
    private final TraditionalConverter converter;

    public DecisionAcceptService(BookStore store, DecisionStore decisions,
                                 TraditionalConverter converter) {
        this.store = store;
        this.decisions = decisions;
        this.converter = converter;
    }

    public record AcceptBody(String clientOperationId, String blockId, int expectedPageRevision,
                             String issueBasisHash, String candidateSetHash, String candidateId,
                             boolean userAttestedSourceCheck) {}

    public record AcceptResult(Page committed, boolean idempotent) {}

    public AcceptResult accept(String bookId, int sourcePage, String issueId, String decisionId,
                               AcceptBody body) throws IOException {
        if (bookId == null || bookId.isBlank() || issueId == null || issueId.isBlank()
                || decisionId == null || decisionId.isBlank() || body == null
                || body.clientOperationId() == null || body.clientOperationId().isBlank()
                || body.blockId() == null || body.blockId().isBlank()
                || body.issueBasisHash() == null || body.issueBasisHash().isBlank()
                || body.candidateSetHash() == null || body.candidateSetHash().isBlank()
                || body.candidateId() == null || body.candidateId().isBlank() || sourcePage < 1)
            throw new ApiException(HttpStatus.BAD_REQUEST, "参数非法");
        // 确认请求必须经用户对照原图；没有原图时只能普通人工输入，不能走此路径冒充
        if (!body.userAttestedSourceCheck())
            throw new ApiException(HttpStatus.BAD_REQUEST, "接受须确认已对照原图");
        DecisionModels.DecisionEvidence evidence;
        try {
            evidence = decisions.loadResult(bookId, decisionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "指定的建议不存在"));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "建议读取失败");
        }
        if (!ACCEPTABLE_VERDICTS.contains(evidence.verdict().name()))
            throw new ApiException(HttpStatus.CONFLICT,
                    "当前建议不可接受：" + evidence.verdict().name());
        if (!evidence.candidateSetHash().equals(body.candidateSetHash()))
            throw new ApiException(HttpStatus.CONFLICT, "候选集合已变化，请刷新后重试");
        DecisionModels.CandidateSet set;
        try {
            set = decisions.loadCandidateSet(bookId, evidence.candidateSetHash())
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "候选数据不可用"));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "候选读取失败");
        }
        DecisionModels.Candidate candidate = set.candidates().stream()
                .filter(c -> body.candidateId().equals(c.candidateId())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "候选已不在当前集合"));
        // 原字未知的旧简体推测不能伪造 originalReplacement，转普通人工录入
        if (candidate.originalScriptText() == null || candidate.originalScriptText().isBlank())
            throw new ApiException(HttpStatus.CONFLICT, "该候选无原字转录，请手工录入确认");
        DecisionModels.DecisionSnapshot snapshot;
        try {
            snapshot = decisions.loadSnapshot(bookId, evidence.snapshotHash())
                    .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "建议快照不可用"));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "建议读取失败");
        }
        String simplified = converter.toSimplified(candidate.originalScriptText());
        BookStore.IssueAcceptSpec spec = new BookStore.IssueAcceptSpec(body.blockId(), issueId,
                body.issueBasisHash(), body.candidateSetHash(), candidate.candidateId(),
                candidate.originalScriptText(), simplified,
                CandidateResolutionService.CONVERTER_VERSION, body.clientOperationId(),
                decisionId, snapshot.issueRef().pdfSha256());
        BookStore.IssueAcceptResult applied = store.applyIssueResolution(bookId, sourcePage,
                body.expectedPageRevision(), CommitActor.MANUAL, null, CommitOp.MANUAL_SAVE, spec);
        return new AcceptResult(applied.committed(), applied.idempotent());
    }
}
