package studio.bookhtml.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight execution record tracking a page attempt through progressive stages:
 * QUEUED_BASELINE -> BASELINE_RUNNING -> BASELINE_COMMITTED
 *  -> WAITING_ENHANCEMENT -> ENHANCEMENT_RUNNING
 *  -> VALIDATING -> PUBLISHING -> SETTLED.
 */
public record PageExecutionRecord(
        String bookId,
        int pageNumber,
        UUID attemptId,
        long generation,
        Stage stage,
        Integer publishedRevision,
        String lifecycle,
        String messageCode,
        Instant createdAt,
        Instant updatedAt) {

    public enum Stage {
        QUEUED_BASELINE,
        BASELINE_RUNNING,
        BASELINE_COMMITTED,
        WAITING_ENHANCEMENT,
        ENHANCEMENT_RUNNING,
        VALIDATING,
        PUBLISHING,
        SETTLED
    }

    public PageExecutionRecord {
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(stage, "stage");
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    public static PageExecutionRecord initial(PageAttempt attempt) {
        Instant now = Instant.now();
        return new PageExecutionRecord(
                attempt.bookId(),
                attempt.pageNumber(),
                attempt.attemptId(),
                attempt.generation(),
                Stage.QUEUED_BASELINE,
                null,
                "RUNNING",
                "PENDING",
                now,
                now
        );
    }

    public PageExecutionRecord withStage(Stage newStage) {
        return new PageExecutionRecord(bookId, pageNumber, attemptId, generation, newStage,
                publishedRevision, lifecycle, messageCode, createdAt, Instant.now());
    }

    public PageExecutionRecord withBaselineCommitted(int revision) {
        return new PageExecutionRecord(bookId, pageNumber, attemptId, generation, Stage.BASELINE_COMMITTED,
                revision, lifecycle, messageCode, createdAt, Instant.now());
    }

    public PageExecutionRecord withSettled(String finalLifecycle, String code, Integer finalRevision) {
        return new PageExecutionRecord(bookId, pageNumber, attemptId, generation, Stage.SETTLED,
                finalRevision != null ? finalRevision : publishedRevision, finalLifecycle, code, createdAt, Instant.now());
    }

    public PageExecutionRecord withRunningBaseline() {
        return withStage(Stage.BASELINE_RUNNING);
    }

    public PageExecutionRecord withRunningEnhancement() {
        return withStage(Stage.ENHANCEMENT_RUNNING);
    }

    public boolean isBaselineCommitted() {
        return stage == Stage.BASELINE_COMMITTED || stage == Stage.WAITING_ENHANCEMENT
                || stage == Stage.ENHANCEMENT_RUNNING || stage == Stage.VALIDATING
                || stage == Stage.PUBLISHING || (stage == Stage.SETTLED && publishedRevision != null);
    }

    public boolean isSettled() {
        return stage == Stage.SETTLED;
    }

    public boolean isEligibleForEnhancement() {
        return stage == Stage.BASELINE_COMMITTED
                && publishedRevision != null
                && !"CANCELLED".equals(lifecycle)
                && !"FAILED".equals(lifecycle);
    }
}
