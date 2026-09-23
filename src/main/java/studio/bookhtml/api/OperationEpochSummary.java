package studio.bookhtml.api;

import java.time.Instant;

public record OperationEpochSummary(
        long epoch,
        String bookId,
        String subjectId,
        int activeOperations,
        int totalOperations,
        int capacityLimit,
        Instant epochStartedAt
) {}
