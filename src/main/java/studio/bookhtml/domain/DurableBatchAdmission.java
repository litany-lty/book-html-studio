package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.*;

/**
 * Lightweight, durable admission record for batch OCR jobs (G07 / B04-04).
 * Binds per-page expected revision, parameters fingerprint, policy revision,
 * and parent jobId without storing bulky Page or Future objects in memory.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DurableBatchAdmission(
        int schemaVersion,
        String batchId,
        String bookId,
        String jobId,
        List<PageRange> pageRanges,
        Map<Integer, Integer> admittedRevisions,
        String parametersFingerprint,
        long policyRevision,
        UUID consentId,
        Instant admittedAt,
        String status
) {
    public record PageRange(int from, int to) {
        public PageRange {
            if (from < 1 || to < from) throw new IllegalArgumentException("invalid page range: " + from + "-" + to);
        }
        public boolean contains(int page) { return page >= from && page <= to; }
    }

    public DurableBatchAdmission {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(parametersFingerprint, "parametersFingerprint");
        Objects.requireNonNull(admittedAt, "admittedAt");
        pageRanges = pageRanges == null ? List.of() : List.copyOf(pageRanges);
        admittedRevisions = admittedRevisions == null ? Map.of() : Map.copyOf(admittedRevisions);
        status = status == null ? "ACTIVE" : status;
    }

    public static DurableBatchAdmission create(String bookId, String jobId, List<Integer> pages,
                                               Map<Integer, Integer> pageRevisions, String fingerprint,
                                               long policyRevision, UUID consentId) {
        List<PageRange> ranges = compressRanges(pages);
        return new DurableBatchAdmission(1, UUID.randomUUID().toString(), bookId, jobId,
                ranges, pageRevisions, fingerprint, policyRevision, consentId, Instant.now(), "ACTIVE");
    }

    public boolean isPageAdmitted(int page) {
        return admittedRevisions.containsKey(page);
    }

    public Integer admittedRevision(int page) {
        return admittedRevisions.get(page);
    }

    public DurableBatchAdmission withStatus(String newStatus) {
        return new DurableBatchAdmission(schemaVersion, batchId, bookId, jobId, pageRanges,
                admittedRevisions, parametersFingerprint, policyRevision, consentId, admittedAt, newStatus);
    }

    public static List<PageRange> compressRanges(List<Integer> pages) {
        if (pages == null || pages.isEmpty()) return List.of();
        List<Integer> sorted = new ArrayList<>(new TreeSet<>(pages));
        List<PageRange> result = new ArrayList<>();
        int start = sorted.get(0);
        int prev = start;
        for (int i = 1; i < sorted.size(); i++) {
            int current = sorted.get(i);
            if (current == prev + 1) {
                prev = current;
            } else {
                result.add(new PageRange(start, prev));
                start = current;
                prev = current;
            }
        }
        result.add(new PageRange(start, prev));
        return List.copyOf(result);
    }
}
