package studio.bookhtml.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * U4：真实处理快照。所有计数为非负整数；终态计数之和不超过 total；
 * {@code lastProgressAt} 只在真实阶段/完成单位变化时更新，心跳不算进展。
 * 时间：本地耗时用单调时钟；服务端/浏览器各用各自时间线，不直接相减。
 */
public record ProcessingSnapshot(int schemaVersion,
                                 String bookId,
                                 int pageNumber,
                                 UUID attemptId,
                                 long snapshotVersion,
                                 String lifecycle,
                                 String stage,
                                 String contentAvailability,
                                 int publishedRevision,
                                 Instant startedAt,
                                 Instant stageStartedAt,
                                 Instant lastProgressAt,
                                 UnitCounts units,
                                 boolean canRead,
                                 boolean canStop,
                                 boolean canRetry,
                                 String messageCode) {
    // 内容可用性：ORIGINAL_ONLY / OCR_READABLE / ENHANCED / MANUAL
    // 任务生命周期：QUEUED / RUNNING / DRAINING / SUCCEEDED / PARTIAL / FAILED / CANCELLED / INTERRUPTED
    // 当前阶段：PREPARING / OCR / STRUCTURE / REVIEW / VALIDATING / PUBLISHING
    public record UnitCounts(String kind,
                             int total,
                             int succeeded,
                             int failed,
                             int skipped,
                             int cancelled,
                             int inFlight) {}

    public static ProcessingSnapshot idle(String bookId, int pageNumber, int publishedRevision,
                                          String availability) {
        Instant now = Instant.now();
        return new ProcessingSnapshot(2, bookId, pageNumber, null, 0, "SUCCEEDED", "PUBLISHING",
                availability, publishedRevision, now, now, now,
                new UnitCounts("PAGE", 1, 1, 0, 0, 0, 0),
                true, false, false, "IDLE_NO_TASK");
    }
}
