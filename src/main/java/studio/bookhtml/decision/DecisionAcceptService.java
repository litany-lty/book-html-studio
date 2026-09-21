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
    /**
     * JR-08-T06：可接受 verdict 范围。RECOMMEND/KEEP_CURRENT 为正式推荐；
     * CANDIDATES_ONLY/HUMAN_REQUIRED/NEED_MORE_EVIDENCE 为用户已对照原图后的手动确认
     *（仍须满足 JR-01 目标绑定 + JR-03 EXACT 对齐 + 原字已知，不视为程序正式推荐）。
     * STALE/CANCELLED/UNAVAILABLE 一律拒绝。
     */
    static final Set<String> ACCEPTABLE_VERDICTS = Set.of(
            "RECOMMEND", "KEEP_CURRENT", "CANDIDATES_ONLY", "HUMAN_REQUIRED", "NEED_MORE_EVIDENCE");

    private final BookStore store;
    private final DecisionStore decisions;
    private final TraditionalConverter converter;
    private final PdfIdentity pdfIdentity;
    private final studio.bookhtml.config.DecisionProperties config;

    @org.springframework.beans.factory.annotation.Autowired
    public DecisionAcceptService(BookStore store, DecisionStore decisions,
                                 TraditionalConverter converter, PdfIdentity pdfIdentity,
                                 studio.bookhtml.config.DecisionProperties config) {
        this.store = store;
        this.decisions = decisions;
        this.converter = converter;
        this.pdfIdentity = pdfIdentity != null ? pdfIdentity : new PdfIdentity();
        this.config = config != null ? config : assistConfig();
    }

    public DecisionAcceptService(BookStore store, DecisionStore decisions,
                                 TraditionalConverter converter, PdfIdentity pdfIdentity) {
        this(store, decisions, converter, pdfIdentity, assistConfig());
    }

    public DecisionAcceptService(BookStore store, DecisionStore decisions,
                                 TraditionalConverter converter) {
        this(store, decisions, converter, new PdfIdentity(), assistConfig());
    }

    private static studio.bookhtml.config.DecisionProperties assistConfig() {
        studio.bookhtml.config.DecisionProperties p = new studio.bookhtml.config.DecisionProperties();
        p.setMode("ASSIST");
        return p;
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
        if (config != null && !"ASSIST".equalsIgnoreCase(config.getMode()))
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "当前决策模式未开放接受建议（当前为 " + config.getMode() + "，仅在 ASSIST 模式下可用）");
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
        DecisionModels.DecisionSnapshot snapshot;
        try {
            snapshot = decisions.loadSnapshot(bookId, evidence.snapshotHash())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "建议快照不存在"));
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "建议读取失败");
        }
        if (!evidence.candidateSetHash().equals(snapshot.candidateSetHash()))
            throw new ApiException(HttpStatus.CONFLICT, "快照候选集合与建议结果不一致");
        if (!evidence.candidateSetHash().equals(body.candidateSetHash()))
            throw new ApiException(HttpStatus.CONFLICT, "候选集合已变化，请刷新后重试");

        // JR-01: 完整目标绑定核验（请求目标 = 快照目标；锁内当前目标在下文二次核对）
        DecisionModels.IssueRef ref = snapshot.issueRef();
        if (!bookId.equals(ref.bookId())
                || sourcePage != ref.sourcePageNumber()
                || !body.blockId().equals(ref.blockId())
                || !issueId.equals(ref.issueId())
                || body.expectedPageRevision() != ref.pageRevision()
                || !body.issueBasisHash().equals(ref.issueBasisHash())) {
            throw new studio.bookhtml.store.PageConflictException(ref.pageRevision(),
                    "建议目标与请求不匹配，拒绝跨位置或跨版本接受");
        }
        // 快照目标全量字段（原文/区间/映射）必须与请求隐含目标一致；请求不能用新 basis 重绑旧快照
        if (ref.startUtf16() < 0 || ref.endUtf16() <= ref.startUtf16()
                || ref.originalTextHash() == null || ref.sourceSpanHash() == null
                || ref.mappingVersion() == null || ref.pdfSha256() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "建议快照目标不完整，拒绝接受");
        }

        // JR-01-T04: 核对来源 PDF 内容身份
        try {
            String currentPdfSha256 = pdfIdentity.sha256(store.pdf(bookId));
            if (!ref.pdfSha256().equals(currentPdfSha256)) {
                throw new ApiException(HttpStatus.CONFLICT, "来源 PDF 内容已变化，旧建议不可接受");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "来源 PDF 读取失败");
        }

        DecisionModels.CandidateSet set;
        try {
            set = decisions.loadCandidateSet(bookId, evidence.candidateSetHash())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "候选数据不存在"));
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "候选读取失败");
        }
        DecisionModels.Candidate candidate = set.candidates().stream()
                .filter(c -> body.candidateId().equals(c.candidateId())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "候选已不在当前集合"));
        // 原字未知的旧简体推测不能伪造 originalReplacement，转普通人工录入
        if (candidate.originalScriptText() == null || candidate.originalScriptText().isBlank())
            throw new ApiException(HttpStatus.CONFLICT, "该候选无原字转录，请手工录入确认");
        // JR-03: 候选范围对齐检查
        if (candidate.alignmentStatus() != DecisionModels.AlignmentStatus.EXACT)
            throw new ApiException(HttpStatus.CONFLICT, "候选范围未精确对齐，不得直接接受");
        if (!ref.sourceSpanHash().equals(candidate.sourceSpanHash()))
            throw new ApiException(HttpStatus.CONFLICT, "候选对应文本区间与当前问题不匹配");
        if (candidate.sourcePageNumber() != ref.sourcePageNumber())
            throw new ApiException(HttpStatus.CONFLICT, "候选页码与当前问题不匹配");
        if (!ref.pdfSha256().equals(candidate.pdfSha256()))
            throw new ApiException(HttpStatus.CONFLICT, "候选来源 PDF 与当前问题不匹配");

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
