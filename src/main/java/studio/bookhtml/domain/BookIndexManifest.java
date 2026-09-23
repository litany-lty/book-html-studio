package studio.bookhtml.domain;

import java.time.Instant;

/**
 * Root manifest for a book's persistent derived indexes.
 * Tracks schema version, generation identity, consistent sourceSeq, and aggregate counters.
 */
public record BookIndexManifest(
        int schemaVersion,
        String bookId,
        String pdfSourceHash,
        String generationId,
        long sourceSeq,
        String status, // "READY", "STALE", "BUILDING", "DEGRADED"
        int totalPages,
        int processedPages,
        int reviewedPages,
        Instant updatedAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
