package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * U4 / G09: 真实处理快照。所有计数为非负整数；终态计数之和不超过 total；
 * {@code lastProgressAt} 只在真实阶段/完成单位变化时更新，心跳不算进展。
 * 时间：本地耗时用单调时钟；服务端/浏览器各用各自时间线，不直接相减。
 * V3 扩展：暴露父子计划哈希、上下文哈希、阶段加权完成率与核对准确率。保持 schemaVersion=2 兼容。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
                                 String messageCode,
                                 long attemptSeq,
                                 String parentPlanHash,
                                 String reviewPlanHash,
                                 String contextHash,
                                 int weightedPercent,
                                 double accuracyRatio) {

    public ProcessingSnapshot(int schemaVersion,
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
                              String messageCode,
                              long attemptSeq) {
        this(schemaVersion, bookId, pageNumber, attemptId, snapshotVersion,
                lifecycle, stage, contentAvailability, publishedRevision,
                startedAt, stageStartedAt, lastProgressAt, units, canRead, canStop, canRetry,
                messageCode, attemptSeq, null, null, null, -1, 1.0);
    }

    private static final String SERVER_INSTANCE_ID = UUID.randomUUID().toString();
    /** Event sequences are comparable only within one server process. */
    @JsonProperty(value="serverInstanceId", access=JsonProperty.Access.READ_ONLY)
    public String serverInstanceId() { return SERVER_INSTANCE_ID; }
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

    /** Completed workflow milestones, not elapsed time, OCR accuracy, or an ETA. */
    @JsonProperty("percent")
    public int percent() {
        if ("SUCCEEDED".equals(lifecycle)) return 100;
        int base = switch (stage == null ? "" : stage) {
            case "OCR" -> 10;
            case "BASELINE_PUBLISHING" -> 40;
            case "STRUCTURE" -> 50;
            case "REVIEW" -> 60;
            case "VALIDATING" -> 85;
            case "PUBLISHING" -> 95;
            default -> 0;
        };
        if ("REVIEW".equals(stage) && units != null && units.total() > 0) {
            long done = (long) units.succeeded() + units.failed() + units.skipped() + units.cancelled();
            base += (int) (25 * Math.min(units.total(), Math.max(0, done)) / units.total());
        }
        return Math.min(99, base);
    }

    @Override
    @JsonProperty("weightedPercent")
    public int weightedPercent() {
        return weightedPercent >= 0 ? weightedPercent : percent();
    }

    @Override
    @JsonProperty("accuracyRatio")
    public double accuracyRatio() {
        return accuracyRatio >= 0.0 ? accuracyRatio : 1.0;
    }

    public static ProcessingSnapshot idle(String bookId, int pageNumber, int publishedRevision,
                                          String availability) {
        Instant now = Instant.now();
        return new ProcessingSnapshot(2, bookId, pageNumber, null, 0, "SUCCEEDED", "PUBLISHING",
                availability, publishedRevision, now, now, now,
                new UnitCounts("PAGE", 1, 1, 0, 0, 0, 0),
                true, false, false, "IDLE_NO_TASK", 0);
    }
}
