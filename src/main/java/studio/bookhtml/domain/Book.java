package studio.bookhtml.domain;

import java.time.Instant;

public record Book(String id, String title, String filename, int totalPages, Instant createdAt,
                   Instant updatedAt, int processedPages, int reviewedPages, boolean archived) {
    /** Existing callers and book.json files predate the library archive flag. */
    public Book(String id, String title, String filename, int totalPages, Instant createdAt,
                Instant updatedAt, int processedPages, int reviewedPages) {
        this(id, title, filename, totalPages, createdAt, updatedAt, processedPages, reviewedPages, false);
    }
}
