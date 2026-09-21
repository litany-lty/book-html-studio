package studio.bookhtml.decision;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JR-01/4.2：不可变决策目标身份。创建任务时从服务器当前页面构建并持久化；
 * 客户端字段只用于前置条件核验。worker、缓存、查询、接受和离线摘要都必须引用
 * 同一目标，不允许执行时静默换成新页面。
 * 接受时三者必须一致：请求指向的目标、持久化决策的目标、锁内当前目标。
 */
public record DecisionTargetIdentity(
        String bookId, String pdfSha256, int sourcePageNumber,
        String blockId, String issueId, int pageRevision,
        String originalTextHash, String issueBasisHash,
        int startUtf16, int endUtf16, String sourceSpanHash,
        String mappingVersion) {
    public DecisionTargetIdentity {
        DecisionModels.requireText("bookId", bookId);
        DecisionModels.requireText("pdfSha256", pdfSha256);
        DecisionModels.requireText("blockId", blockId);
        DecisionModels.requireText("issueId", issueId);
        DecisionModels.requireText("originalTextHash", originalTextHash);
        DecisionModels.requireText("issueBasisHash", issueBasisHash);
        DecisionModels.requireText("sourceSpanHash", sourceSpanHash);
        DecisionModels.requireText("mappingVersion", mappingVersion);
        if (sourcePageNumber < 1) throw new IllegalArgumentException("sourcePageNumber 非法");
        if (pageRevision < 0) throw new IllegalArgumentException("pageRevision 非法");
        if (startUtf16 < 0 || endUtf16 <= startUtf16)
            throw new IllegalArgumentException("问题区间非法");
    }

    /** 准入/请求 hash 的稳定输入（4.2 全字段 + 获取策略 + 证据版本 + 授权作用域）。 */
    public String admissionKey(String acquisitionPolicyVersion, String existingEvidenceVersion,
                               boolean allowFreshVision, String authorizationScope) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("bookId", bookId);
        material.put("pdfSha256", pdfSha256);
        material.put("sourcePageNumber", sourcePageNumber);
        material.put("blockId", blockId);
        material.put("issueId", issueId);
        material.put("pageRevision", pageRevision);
        material.put("originalTextHash", originalTextHash);
        material.put("issueBasisHash", issueBasisHash);
        material.put("startUtf16", startUtf16);
        material.put("endUtf16", endUtf16);
        material.put("sourceSpanHash", sourceSpanHash);
        material.put("mappingVersion", mappingVersion);
        material.put("acquisitionPolicy", acquisitionPolicyVersion);
        material.put("existingEvidence", existingEvidenceVersion);
        material.put("allowFreshVision", allowFreshVision);
        material.put("authorizationScope", authorizationScope);
        return DecisionHash.of(material);
    }

    /** 与另一目标比较：不一致返回原因，否则返回 null。 */
    public String mismatch(DecisionTargetIdentity other) {
        if (other == null) return "TARGET_MISSING";
        if (!bookId.equals(other.bookId())) return "TARGET_BOOK";
        if (!pdfSha256.equals(other.pdfSha256())) return "TARGET_PDF";
        if (sourcePageNumber != other.sourcePageNumber()) return "TARGET_PAGE";
        if (!blockId.equals(other.blockId())) return "TARGET_BLOCK";
        if (!issueId.equals(other.issueId())) return "TARGET_ISSUE";
        if (pageRevision != other.pageRevision()) return "TARGET_REVISION";
        if (!originalTextHash.equals(other.originalTextHash())) return "TARGET_ORIGINAL";
        if (!issueBasisHash.equals(other.issueBasisHash())) return "TARGET_BASIS";
        if (startUtf16 != other.startUtf16() || endUtf16 != other.endUtf16()) return "TARGET_SPAN";
        if (!sourceSpanHash.equals(other.sourceSpanHash())) return "TARGET_SPAN_HASH";
        if (!mappingVersion.equals(other.mappingVersion())) return "TARGET_MAPPING";
        return null;
    }
}
