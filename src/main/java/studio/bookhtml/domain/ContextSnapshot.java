package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import studio.bookhtml.decision.DecisionHash;
import studio.bookhtml.decision.DecisionModels;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * G08 / B07: 严格上下文快照 (ContextSnapshot)。
 * 严格绑定父工作计划 (parentPlanHash) 与子评审计划 (reviewPlanHash)；
 * 记录 UTF-16 区间、Unicode 码点计数、UTF-8 字节长度与最大字节预算；
 * 记录持久来源事件序列号 (eventSeq) 与不可变 contextHash；
 * 禁止切断 UTF-16 代理对。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextSnapshot(
        String snapshotId,
        String bookId,
        int pageNumber,
        int pageRevision,
        String parentPlanHash,
        String reviewPlanHash,
        long eventSeq,
        String blockId,
        int startUtf16,
        int endUtf16,
        int codePointCount,
        int byteLength,
        int maxByteBudget,
        String targetText,
        String surroundingContext,
        String contextHash,
        Instant createdAt) {

    public static final int DEFAULT_MAX_BYTE_BUDGET = 16 * 1024; // 16 KiB

    public ContextSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId 不能为空");
        Objects.requireNonNull(bookId, "bookId 不能为空");
        Objects.requireNonNull(parentPlanHash, "parentPlanHash 不能为空");
        Objects.requireNonNull(reviewPlanHash, "reviewPlanHash 不能为空");
        Objects.requireNonNull(blockId, "blockId 不能为空");
        Objects.requireNonNull(targetText, "targetText 不能为空");
        if (pageNumber < 1) throw new IllegalArgumentException("pageNumber 必须 >= 1");
        if (pageRevision < 0) throw new IllegalArgumentException("pageRevision 必须 >= 0");
        if (startUtf16 < 0 || endUtf16 <= startUtf16)
            throw new IllegalArgumentException("UTF-16 区间非法: [" + startUtf16 + ", " + endUtf16 + ")");
        if (maxByteBudget <= 0)
            throw new IllegalArgumentException("maxByteBudget 必须 > 0");
        if (byteLength > maxByteBudget)
            throw new IllegalArgumentException("上下文超出字节预算: " + byteLength + " > " + maxByteBudget);
        surroundingContext = surroundingContext == null ? "" : surroundingContext;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public static ContextSnapshot create(
            String bookId,
            int pageNumber,
            int pageRevision,
            String parentPlanHash,
            String reviewPlanHash,
            long eventSeq,
            String blockId,
            int startUtf16,
            int endUtf16,
            String fullBlockText,
            String surroundingContext,
            int maxByteBudget) {

        if (fullBlockText == null) {
            throw new IllegalArgumentException("fullBlockText 不能为空");
        }
        if (startUtf16 < 0 || endUtf16 <= startUtf16 || endUtf16 > fullBlockText.length()) {
            throw new IllegalArgumentException("区间越界: [" + startUtf16 + ", " + endUtf16 + ") 长度=" + fullBlockText.length());
        }
        if (!isBoundary(fullBlockText, startUtf16) || !isBoundary(fullBlockText, endUtf16)) {
            throw new IllegalArgumentException("区间切断了 UTF-16 代理对");
        }

        String targetText = fullBlockText.substring(startUtf16, endUtf16);
        int codePoints = targetText.codePointCount(0, targetText.length());
        int byteLen = targetText.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen > maxByteBudget) {
            throw new IllegalArgumentException("上下文超出字节预算: " + byteLen + " > " + maxByteBudget);
        }

        String snapshotId = UUID.randomUUID().toString();
        String contextHash = computeContextHash(
                bookId, pageNumber, pageRevision, parentPlanHash, reviewPlanHash,
                eventSeq, blockId, startUtf16, endUtf16, targetText, surroundingContext);

        return new ContextSnapshot(
                snapshotId, bookId, pageNumber, pageRevision, parentPlanHash, reviewPlanHash,
                eventSeq, blockId, startUtf16, endUtf16, codePoints, byteLen, maxByteBudget,
                targetText, surroundingContext, contextHash, Instant.now());
    }

    public static ContextSnapshot create(
            String bookId,
            int pageNumber,
            int pageRevision,
            String parentPlanHash,
            String reviewPlanHash,
            long eventSeq,
            String blockId,
            int startUtf16,
            int endUtf16,
            String fullBlockText,
            String surroundingContext) {
        return create(bookId, pageNumber, pageRevision, parentPlanHash, reviewPlanHash,
                eventSeq, blockId, startUtf16, endUtf16, fullBlockText, surroundingContext, DEFAULT_MAX_BYTE_BUDGET);
    }

    public static ContextSnapshot fromDecisionSnapshot(
            DecisionModels.DecisionSnapshot decisionSnapshot,
            String parentPlanHash,
            String reviewPlanHash,
            long eventSeq,
            String fullBlockText) {
        DecisionModels.IssueRef ref = decisionSnapshot.issueRef();
        return create(
                ref.bookId(),
                ref.sourcePageNumber(),
                ref.pageRevision(),
                parentPlanHash,
                reviewPlanHash,
                eventSeq,
                ref.blockId(),
                ref.startUtf16(),
                ref.endUtf16(),
                fullBlockText,
                decisionSnapshot.context() != null ? decisionSnapshot.context().toString() : "",
                DEFAULT_MAX_BYTE_BUDGET
        );
    }

    public static String computeContextHash(
            String bookId,
            int pageNumber,
            int pageRevision,
            String parentPlanHash,
            String reviewPlanHash,
            long eventSeq,
            String blockId,
            int startUtf16,
            int endUtf16,
            String targetText,
            String surroundingContext) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("bookId", bookId);
        map.put("pageNumber", pageNumber);
        map.put("pageRevision", pageRevision);
        map.put("parentPlanHash", parentPlanHash);
        map.put("reviewPlanHash", reviewPlanHash);
        map.put("eventSeq", eventSeq);
        map.put("blockId", blockId);
        map.put("startUtf16", startUtf16);
        map.put("endUtf16", endUtf16);
        map.put("targetText", targetText);
        map.put("surroundingContext", surroundingContext == null ? "" : surroundingContext);
        return DecisionHash.of(map);
    }

    public static boolean isBoundary(String s, int offset) {
        if (s == null || offset < 0 || offset > s.length()) return false;
        return offset == 0 || offset == s.length()
                || !(Character.isHighSurrogate(s.charAt(offset - 1))
                && Character.isLowSurrogate(s.charAt(offset)));
    }
}
