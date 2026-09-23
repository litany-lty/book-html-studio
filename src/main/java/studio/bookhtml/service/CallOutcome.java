package studio.bookhtml.service;

import java.time.Instant;
import java.util.Map;

/**
 * Unified outcome of a single admitted physical call (B05 / G03).
 */
public sealed interface CallOutcome permits
        CallOutcome.Succeeded,
        CallOutcome.Failed,
        CallOutcome.OutcomeUnknown,
        CallOutcome.NotSent,
        CallOutcome.RetryEligible {

    record Succeeded(int statusCode, byte[] body, String responseId, Map<String, Object> usageInfo) implements CallOutcome {
        public Succeeded {
            body = body == null ? new byte[0] : body.clone();
            usageInfo = usageInfo == null ? Map.of() : Map.copyOf(usageInfo);
        }
    }

    record Failed(int statusCode, String reason, boolean known) implements CallOutcome {}

    record OutcomeUnknown(String reason) implements CallOutcome {}

    record NotSent(String reason) implements CallOutcome {}

    record RetryEligible(
            Instant nextEligibleAt,
            long originalDeadlineNanos,
            String logicalCallId,
            int retryAfterSeconds
    ) implements CallOutcome {}
}
