package studio.bookhtml.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Small persistent page publication and attempt head.
 * Provides O(1) status, revision and attempt projection without reading full page content or whole-book attempt journals.
 */
public record PageHead(
        int pageNumber,
        int revision,
        UUID commitId,
        String status,
        boolean processed,
        boolean reviewed,
        int unresolvedCount,
        int readingBlocksCount,
        String title,
        double width,
        double height,
        String contentHash,
        long appliedSourceSeq,
        Instant updatedAt,
        UUID attemptId,
        Long attemptSeq,
        String attemptLifecycle,
        String attemptStage
) {
    public PageHead withAttempt(UUID attemptId, Long attemptSeq, String attemptLifecycle, String attemptStage) {
        return new PageHead(pageNumber, revision, commitId, status, processed, reviewed, unresolvedCount,
                readingBlocksCount, title, width, height, contentHash, appliedSourceSeq, Instant.now(),
                attemptId, attemptSeq, attemptLifecycle, attemptStage);
    }

    public PageHead withPublication(int revision, UUID commitId, String status, boolean processed, boolean reviewed,
                                   int unresolvedCount, int readingBlocksCount, String title,
                                   double width, double height, String contentHash, long appliedSourceSeq) {
        return new PageHead(pageNumber, revision, commitId, status, processed, reviewed, unresolvedCount,
                readingBlocksCount, title, width, height, contentHash, appliedSourceSeq, Instant.now(),
                attemptId, attemptSeq, attemptLifecycle, attemptStage);
    }
}
