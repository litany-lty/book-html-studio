package studio.bookhtml.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal authoritative source change event.
 * Preserves exact target kind, sequence, hashes and commit identities for incremental index catchup and crash recovery.
 */
public record SourceChange(
        long sourceSeq,
        String targetKind,
        String bookId,
        Integer pageNumber,
        UUID commitId,
        String metadataChangeId,
        Integer beforeRevision,
        Integer afterRevision,
        String beforeHash,
        String afterHash,
        String changeReason,
        String state,
        Instant timestamp
) {
    public SourceChange withState(String newState) {
        return new SourceChange(sourceSeq, targetKind, bookId, pageNumber, commitId, metadataChangeId,
                beforeRevision, afterRevision, beforeHash, afterHash, changeReason, newState, timestamp);
    }
}
