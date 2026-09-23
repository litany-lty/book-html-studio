package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContextSnapshot;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.nio.charset.StandardCharsets;

/**
 * G08 / B07: 上下文依赖校验器 (ContextDependencyValidator)。
 * 校验决策建议所依赖的上下文快照与当前页面实时状态、来源事件序列的一致性；
 * 支持在 BookStore 目录写锁内进行原子复核，防止过期决策覆盖人工修改。
 */
@Service
public class ContextDependencyValidator {

    public enum ResultCode {
        VALID,
        PAGE_NOT_FOUND,
        PAGE_MISMATCH,
        STALE_REVISION,
        EVENT_DRIFT,
        PLAN_MISMATCH,
        BLOCK_NOT_FOUND,
        BOUNDS_OUT_OF_RANGE,
        SURROGATE_SPLIT,
        CONTENT_DRIFT,
        HASH_MISMATCH,
        BUDGET_EXCEEDED
    }

    public record ValidationOutcome(boolean isValid, ResultCode code, String reason) {
        public static ValidationOutcome ok() {
            return new ValidationOutcome(true, ResultCode.VALID, null);
        }

        public static ValidationOutcome fail(ResultCode code, String reason) {
            return new ValidationOutcome(false, code, reason);
        }
    }

    /**
     * 校验 ContextSnapshot 与当前 Page 及当前 eventSeq 的有效性。
     */
    public ValidationOutcome validate(ContextSnapshot snapshot, Page currentPage, long currentEventSeq) {
        if (snapshot == null) {
            return ValidationOutcome.fail(ResultCode.VALID, "快照为空");
        }
        if (currentPage == null) {
            return ValidationOutcome.fail(ResultCode.PAGE_NOT_FOUND, "当前页面不存在");
        }
        if (currentPage.pageNumber() != snapshot.pageNumber()) {
            return ValidationOutcome.fail(ResultCode.PAGE_MISMATCH,
                    "页码不匹配: 期望 " + snapshot.pageNumber() + " 实际 " + currentPage.pageNumber());
        }
        int currentRev = BookStore.revisionOrZero(currentPage);
        if (currentRev != snapshot.pageRevision()) {
            return ValidationOutcome.fail(ResultCode.STALE_REVISION,
                    "页面版本已过时: 当前版本 " + currentRev + " != 快照版本 " + snapshot.pageRevision());
        }
        if (snapshot.eventSeq() > 0 && currentEventSeq > 0 && currentEventSeq < snapshot.eventSeq()) {
            return ValidationOutcome.fail(ResultCode.EVENT_DRIFT,
                    "来源事件时序回退: 当前 seq=" + currentEventSeq + " < 快照 seq=" + snapshot.eventSeq());
        }

        Block block = currentPage.blocks() == null ? null : currentPage.blocks().stream()
                .filter(b -> b != null && snapshot.blockId().equals(b.id()))
                .findFirst()
                .orElse(null);
        if (block == null) {
            return ValidationOutcome.fail(ResultCode.BLOCK_NOT_FOUND, "指定的文本块已不存在: " + snapshot.blockId());
        }

        String fullText = block.original();
        if (fullText == null) {
            return ValidationOutcome.fail(ResultCode.CONTENT_DRIFT, "文本块原文为空");
        }

        if (snapshot.startUtf16() < 0 || snapshot.endUtf16() <= snapshot.startUtf16()
                || snapshot.endUtf16() > fullText.length()) {
            return ValidationOutcome.fail(ResultCode.BOUNDS_OUT_OF_RANGE,
                    "区间越界: [" + snapshot.startUtf16() + ", " + snapshot.endUtf16() + ") 块文本长度=" + fullText.length());
        }

        if (!ContextSnapshot.isBoundary(fullText, snapshot.startUtf16())
                || !ContextSnapshot.isBoundary(fullText, snapshot.endUtf16())) {
            return ValidationOutcome.fail(ResultCode.SURROGATE_SPLIT, "区间切断了 UTF-16 代理对");
        }

        String actualSpan = fullText.substring(snapshot.startUtf16(), snapshot.endUtf16());
        if (!actualSpan.equals(snapshot.targetText())) {
            return ValidationOutcome.fail(ResultCode.CONTENT_DRIFT,
                    "上下文原文已变化: 期望 '" + snapshot.targetText() + "' 实际 '" + actualSpan + "'");
        }

        int actualCodePoints = actualSpan.codePointCount(0, actualSpan.length());
        if (actualCodePoints != snapshot.codePointCount()) {
            return ValidationOutcome.fail(ResultCode.CONTENT_DRIFT,
                    "码点计数不一致: 期望 " + snapshot.codePointCount() + " 实际 " + actualCodePoints);
        }

        int byteLen = actualSpan.getBytes(StandardCharsets.UTF_8).length;
        if (byteLen > snapshot.maxByteBudget()) {
            return ValidationOutcome.fail(ResultCode.BUDGET_EXCEEDED,
                    "上下文超出最大字节预算: " + byteLen + " > " + snapshot.maxByteBudget());
        }

        String recomputedHash = ContextSnapshot.computeContextHash(
                snapshot.bookId(), snapshot.pageNumber(), snapshot.pageRevision(),
                snapshot.parentPlanHash(), snapshot.reviewPlanHash(), snapshot.eventSeq(),
                snapshot.blockId(), snapshot.startUtf16(), snapshot.endUtf16(),
                actualSpan, snapshot.surroundingContext());
        if (!recomputedHash.equals(snapshot.contextHash())) {
            return ValidationOutcome.fail(ResultCode.HASH_MISMATCH, "上下文哈希不一致");
        }

        return ValidationOutcome.ok();
    }

    /**
     * 在 BookStore 目录写锁内复核 IssueAcceptSpec。
     */
    public ValidationOutcome validateInLock(Page currentPage, BookStore.IssueAcceptSpec spec, long currentEventSeq) {
        if (spec == null) {
            return ValidationOutcome.ok();
        }

        if (spec.contextSnapshot() != null) {
            ContextSnapshot snapshot = spec.contextSnapshot();
            if (spec.parentPlanHash() != null && !spec.parentPlanHash().equals(snapshot.parentPlanHash())) {
                return ValidationOutcome.fail(ResultCode.PLAN_MISMATCH,
                        "父计划哈希不匹配: " + spec.parentPlanHash() + " != " + snapshot.parentPlanHash());
            }
            if (spec.reviewPlanHash() != null && !spec.reviewPlanHash().equals(snapshot.reviewPlanHash())) {
                return ValidationOutcome.fail(ResultCode.PLAN_MISMATCH,
                        "评审子计划哈希不匹配: " + spec.reviewPlanHash() + " != " + snapshot.reviewPlanHash());
            }
            if (spec.contextHash() != null && !spec.contextHash().equals(snapshot.contextHash())) {
                return ValidationOutcome.fail(ResultCode.HASH_MISMATCH,
                        "上下文哈希不匹配: " + spec.contextHash() + " != " + snapshot.contextHash());
            }
            return validate(snapshot, currentPage, currentEventSeq);
        }

        if (spec.contextHash() != null) {
            // 当只传入 contextHash 时进行基线检查
            Block block = currentPage.blocks() == null ? null : currentPage.blocks().stream()
                    .filter(b -> b != null && spec.blockId().equals(b.id()))
                    .findFirst()
                    .orElse(null);
            if (block == null) {
                return ValidationOutcome.fail(ResultCode.BLOCK_NOT_FOUND, "指定的块不存在");
            }
        }

        return ValidationOutcome.ok();
    }
}
