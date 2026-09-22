package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Non-secret durable receipt. Replaying it returns a result, never a new execution. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReprocessOperation(String bookId, String fingerprint, UUID attemptId,
                                 long attemptSeq, Job response, Instant acceptedAt,
                                 Instant expiresAt, String lifecycle) {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "PARTIAL", "FAILED",
            "CANCELLED", "INTERRUPTED", "UNKNOWN");

    public ReprocessOperation {
        if (bookId == null || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || attemptId == null || attemptSeq < 1 || response == null || response.id() == null
                || response.pages() == null || response.pages().size() != 1
                || response.pages().get(0) < 1 || acceptedAt == null || expiresAt == null
                || !expiresAt.isAfter(acceptedAt) || lifecycle == null
                || !(TERMINAL.contains(lifecycle) || "RUNNING".equals(lifecycle)))
            throw new IllegalArgumentException("invalid operation receipt");
    }

    public boolean terminal() { return TERMINAL.contains(lifecycle); }

    public ReprocessOperation finish(String outcome, Instant now) {
        if (terminal() || !TERMINAL.contains(outcome)) return this;
        String status = switch (outcome) {
            case "SUCCEEDED" -> "COMPLETED";
            case "PARTIAL" -> "COMPLETED_WITH_ERRORS";
            default -> outcome;
        };
        int completed = "SUCCEEDED".equals(outcome) || "PARTIAL".equals(outcome) ? 1 : 0;
        Job result = new Job(response.id(), status, completed, 1, response.currentPage(),
                null, response.errors(), now, response.pages(), response.provider(), response.layout(),
                response.splitSpreads(), response.force(), response.assist(), response.fingerprint());
        return new ReprocessOperation(bookId, fingerprint, attemptId, attemptSeq, result,
                acceptedAt, expiresAt, outcome);
    }
}
