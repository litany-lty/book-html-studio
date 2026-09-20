package studio.bookhtml.domain;

import java.time.Instant;
import java.util.List;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record Job(String id, String status, int completed, int total, Integer currentPage,
                  String error, List<String> errors, Instant updatedAt,
                  List<Integer> pages, String provider, String layout,
                  Boolean splitSpreads, Boolean force, Boolean assist, String fingerprint) {
    public Job(String id, String status, int completed, int total, Integer currentPage,
               String error, List<String> errors, Instant updatedAt) {
        this(id, status, completed, total, currentPage, error, errors, updatedAt,
             null, null, null, null, null, null, null);
    }
    public static Job idle() { return new Job(null, "IDLE", 0, 0, null, null, List.of(), Instant.now()); }
}
