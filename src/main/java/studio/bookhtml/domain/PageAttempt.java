package studio.bookhtml.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * U2：页面级处理登记。内存 Map 只用于调度，不单独作为最终写入权限；
 * 最终写入仍以 {@code BookStore} 同一目录锁内的 revision/来源校验为准。
 *
 * <p>重启不恢复：预约本身是进程内状态，重启后旧会话不再派发新任务，
 * 也不自动重发云请求（见 {@code ReadingWindowService} 会话语义）。
 * 持久化恢复意图见 U4（ProcessingSnapshot）。
 */
public record PageAttempt(String bookId,
                          int pageNumber,
                          UUID runId,
                          UUID attemptId,
                          long generation,
                          int expectedRevision,
                          String expectedSourceHash,
                          List<String> allowedCommitOps,
                          String lifecycle,
                          Instant startedAt,
                          Instant updatedAt) {
    public PageAttempt {
        allowedCommitOps = allowedCommitOps == null ? List.of() : List.copyOf(allowedCommitOps);
    }

    public static PageAttempt register(String bookId, int pageNumber, int expectedRevision,
                                       String expectedSourceHash, List<String> allowedCommitOps) {
        Instant now = Instant.now();
        return new PageAttempt(bookId, pageNumber, UUID.randomUUID(), UUID.randomUUID(), 1,
                expectedRevision, expectedSourceHash, allowedCommitOps, "RUNNING", now, now);
    }

    public PageAttempt nextGeneration() {
        return new PageAttempt(bookId, pageNumber, runId, UUID.randomUUID(), generation + 1,
                expectedRevision, expectedSourceHash, allowedCommitOps, "RUNNING", startedAt, Instant.now());
    }

    public PageAttempt withLifecycle(String next) {
        return new PageAttempt(bookId, pageNumber, runId, attemptId, generation,
                expectedRevision, expectedSourceHash, allowedCommitOps, next, startedAt, Instant.now());
    }

    public String key() {
        return bookId + ":" + pageNumber;
    }
}
