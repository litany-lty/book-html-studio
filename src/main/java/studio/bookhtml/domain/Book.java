package studio.bookhtml.domain;

import java.time.Instant;

public record Book(String id, String title, String filename, int totalPages, Instant createdAt,
                   Instant updatedAt, int processedPages, int reviewedPages) {}
