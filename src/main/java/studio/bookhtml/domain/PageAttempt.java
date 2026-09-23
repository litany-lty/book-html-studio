package studio.bookhtml.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * U2：页面级处理登记。内存 Map 只用于调度，不单独作为最终写入权限；
 * 最终写入仍以 {@code BookStore} 同一目录锁内的 revision/来源校验为准。
 *
 * <p>预约是进程内状态，重启不自动重发云请求。Journal 同时保存权威尝试序号
 * 与重处理幂等回执；同一操作在新会话或重启后只返回原结果，不获得新执行权限。
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
                          Instant updatedAt, Binding binding) {
    public record Binding(String jobId, String pdfSha256, boolean overwriteAuthorized) {
        public Binding {
            if (jobId == null || jobId.isBlank() || jobId.length() > 200
                    || jobId.codePoints().anyMatch(Character::isISOControl)
                    || pdfSha256 == null || !(pdfSha256.equals("ABSENT") || pdfSha256.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("invalid attempt binding");
        }
    }
    /** Legacy identities can be read but cannot authorize new writes. */
    public PageAttempt(String bookId, int pageNumber, UUID runId, UUID attemptId, long generation,
                       int expectedRevision, String expectedSourceHash, List<String> allowedCommitOps,
                       String lifecycle, Instant startedAt, Instant updatedAt) {
        this(bookId,pageNumber,runId,attemptId,generation,expectedRevision,expectedSourceHash,
                allowedCommitOps,lifecycle,startedAt,updatedAt,null);
    }
    public PageAttempt bind(String jobId, String pdfSha256, boolean overwrite) {
        return new PageAttempt(bookId,pageNumber,runId,attemptId,generation,expectedRevision,
                expectedSourceHash,allowedCommitOps,lifecycle,startedAt,updatedAt,
                new Binding(jobId,pdfSha256,overwrite));
    }
    public String commitIdentity(String outcome) {
        return "attempt:" + attemptId + ":" + generation + ":" + outcome;
    }
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
        return nextGeneration(expectedRevision, expectedSourceHash, allowedCommitOps);
    }

    public PageAttempt nextGeneration(int revision, String sourceHash, List<String> operations) {
        Instant now = Instant.now();
        return new PageAttempt(bookId, pageNumber, runId, UUID.randomUUID(), Math.addExact(generation, 1),
                revision, sourceHash, operations, "RUNNING", now, now);
    }

    public PageAttempt withLifecycle(String next) {
        return new PageAttempt(bookId, pageNumber, runId, attemptId, generation,
                expectedRevision, expectedSourceHash, allowedCommitOps, next, startedAt, Instant.now(), binding);
    }

    public String key() {
        return bookId + ":" + pageNumber;
    }

    /** Persistent attempts and replay receipts. On restart, unfinished work becomes
     * INTERRUPTED; a readable page alone is never proof of the attempt's success. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Journal(Map<String, PageAttempt> intents, Map<String, ReprocessOperation> operations) {
        public static final int MAX_OPERATIONS = 4096;
        public Journal {
            intents = intents == null ? Map.of() : Map.copyOf(intents);
            operations = operations == null ? Map.of() : Map.copyOf(operations);
            if (operations.size() > MAX_OPERATIONS) throw new IllegalArgumentException("operation capacity exceeded");
        }
        /** Reads legacy intent-only journals without manufacturing replay receipts. */
        public Journal(Map<String, PageAttempt> intents) { this(intents, Map.of()); }
        @com.fasterxml.jackson.annotation.JsonProperty("schemaVersion") public int schemaVersion() { return 3; }

        public Journal withIntents(Map<String, PageAttempt> next) {
            Map<String, ReprocessOperation> updated = new java.util.LinkedHashMap<>(operations);
            operations.forEach((key, operation) -> {
                PageAttempt attempt = next.get(operation.bookId() + ":" + operation.response().pages().get(0));
                if (attempt != null && attempt.attemptId().equals(operation.attemptId())
                        && attempt.generation() == operation.attemptSeq())
                    updated.put(key, operation.finish(attempt.lifecycle(), attempt.updatedAt()));
            });
            return new Journal(next, updated);
        }
        public static Journal empty() { return new Journal(Map.of()); }
    }
}
