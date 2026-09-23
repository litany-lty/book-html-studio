package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.SourceChange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authoritative, append-only source change journal: books/<bookId>/source-events/.
 * Implements strict crash-tail recovery, per-book sequential sourceSeq, and reconciliation
 * without scanning or rewriting full book contents.
 */
public final class SourceChangeJournal {
    private final ObjectMapper json;
    private final DurableEventJournal wal;
    private final Map<String, AtomicLong> seqWatermarks = new ConcurrentHashMap<>();

    public SourceChangeJournal(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
        this.wal = new DurableEventJournal();
    }

    public static Path eventsDir(Path bookDir) {
        return bookDir.resolve("source-events");
    }

    private static String segmentFileName(long segmentIndex) {
        return String.format("segment-%06d.wal", segmentIndex);
    }

    private synchronized SegmentRef activeSegment(Path bookDir, String bookId, long requiredSeq) throws IOException {
        Path dir = eventsDir(bookDir);
        DurableJson.rejectLinks(dir);
        Files.createDirectories(dir);

        List<Path> segments;
        try (var stream = Files.list(dir)) {
            segments = stream
                    .filter(p -> p.getFileName().toString().startsWith("segment-") && p.getFileName().toString().endsWith(".wal"))
                    .sorted()
                    .toList();
        }

        if (segments.isEmpty()) {
            Path seg1 = dir.resolve(segmentFileName(1));
            wal.initSegment(seg1, bookId, UUID.randomUUID(), 1, "0000000000000000000000000000000000000000000000000000000000000000");
            return new SegmentRef(seg1, 1, 0, 1);
        }

        Path latestPath = segments.get(segments.size() - 1);
        var scan = wal.readSegment(latestPath, bookId, true, new ArrayList<>());
        long segIndex = segments.size();
        if (scan.frames().size() >= DurableEventJournal.MAX_SEGMENT_RECORDS || scan.validLength() >= DurableEventJournal.MAX_SEGMENT_BYTES) {
            segIndex++;
            Path nextPath = dir.resolve(segmentFileName(segIndex));
            wal.initSegment(nextPath, bookId, UUID.randomUUID(), requiredSeq, "chained");
            return new SegmentRef(nextPath, segIndex, 0, requiredSeq);
        }

        return new SegmentRef(latestPath, segIndex, scan.frames().size(), scan.header().startSeq());
    }

    private record SegmentRef(Path path, long index, int frameCount, long startSeq) {}

    public synchronized long nextSourceSeq(Path bookDir, String bookId) {
        return seqWatermarks.computeIfAbsent(bookId, id -> {
            try {
                List<SourceChange> all = readAll(bookDir, bookId);
                long max = 0;
                for (SourceChange sc : all) {
                    if (sc.sourceSeq() > max) max = sc.sourceSeq();
                }
                return new AtomicLong(max);
            } catch (Exception ex) {
                return new AtomicLong(0);
            }
        }).incrementAndGet();
    }

    public synchronized long currentSourceSeq(Path bookDir, String bookId) {
        return seqWatermarks.computeIfAbsent(bookId, id -> {
            try {
                List<SourceChange> all = readAll(bookDir, bookId);
                long max = 0;
                for (SourceChange sc : all) {
                    if (sc.sourceSeq() > max) max = sc.sourceSeq();
                }
                return new AtomicLong(max);
            } catch (Exception ex) {
                return new AtomicLong(0);
            }
        }).get();
    }

    public synchronized SourceChange prepare(Path bookDir, String bookId, String targetKind, Integer pageNumber,
                                            UUID commitId, String metadataChangeId, Integer beforeRevision,
                                            Integer afterRevision, String beforeHash, String afterHash,
                                            String changeReason) throws IOException {
        long seq = nextSourceSeq(bookDir, bookId);
        SourceChange event = new SourceChange(seq, targetKind, bookId, pageNumber, commitId, metadataChangeId,
                beforeRevision, afterRevision, beforeHash, afterHash, changeReason, "PREPARED", Instant.now());
        appendEvent(bookDir, bookId, event);
        return event;
    }

    public synchronized SourceChange commit(Path bookDir, String bookId, long sourceSeq, Integer pageNumber,
                                           UUID commitId, int revision, String afterHash) throws IOException {
        SourceChange event = new SourceChange(sourceSeq, "PAGE", bookId, pageNumber, commitId, null,
                null, revision, null, afterHash, "COMMIT", "COMMITTED", Instant.now());
        appendEvent(bookDir, bookId, event);
        return event;
    }

    public synchronized SourceChange commit(Path bookDir, String bookId, long sourceSeq, UUID commitId,
                                           int revision, String afterHash) throws IOException {
        return commit(bookDir, bookId, sourceSeq, null, commitId, revision, afterHash);
    }

    public synchronized SourceChange markNotPublished(Path bookDir, String bookId, long sourceSeq, Integer pageNumber) throws IOException {
        SourceChange event = new SourceChange(sourceSeq, "PAGE", bookId, pageNumber, null, null,
                null, null, null, null, "ABORT", "NOT_PUBLISHED", Instant.now());
        appendEvent(bookDir, bookId, event);
        return event;
    }

    public synchronized SourceChange markNotPublished(Path bookDir, String bookId, long sourceSeq) throws IOException {
        return markNotPublished(bookDir, bookId, sourceSeq, null);
    }

    private synchronized void appendEvent(Path bookDir, String bookId, SourceChange event) throws IOException {
        byte[] payload = json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        if (payload.length > DurableEventJournal.MAX_FRAME_PAYLOAD_BYTES) {
            throw new IOException("source event payload exceeds bound");
        }
        SegmentRef seg = activeSegment(bookDir, bookId, event.sourceSeq());
        wal.append(seg.path(), event.sourceSeq(), payload);
    }

    public synchronized List<SourceChange> readAll(Path bookDir, String bookId) throws IOException {
        Path dir = eventsDir(bookDir);
        DurableJson.rejectLinks(dir);
        if (!Files.exists(dir)) return List.of();

        List<Path> segments;
        try (var stream = Files.list(dir)) {
            segments = stream
                    .filter(p -> p.getFileName().toString().startsWith("segment-") && p.getFileName().toString().endsWith(".wal"))
                    .sorted()
                    .toList();
        }

        List<SourceChange> result = new ArrayList<>();
        for (Path seg : segments) {
            var scan = wal.readSegment(seg, bookId, true, new ArrayList<>());
            for (var frame : scan.frames()) {
                SourceChange sc = json.readValue(frame.payload(), SourceChange.class);
                result.add(sc);
            }
        }
        return List.copyOf(result);
    }

    public synchronized List<SourceChange> readSince(Path bookDir, String bookId, long sinceSeq, int maxLimit) throws IOException {
        List<SourceChange> all = readAll(bookDir, bookId);
        List<SourceChange> filtered = new ArrayList<>();
        for (SourceChange sc : all) {
            if (sc.sourceSeq() > sinceSeq) {
                filtered.add(sc);
                if (filtered.size() >= maxLimit) break;
            }
        }
        return List.copyOf(filtered);
    }

    public synchronized SourceChange latest(Path bookDir, String bookId) throws IOException {
        List<SourceChange> all = readAll(bookDir, bookId);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /**
     * B03-01: Reconcile unresolved PREPARED source events against disk truth.
     * Prevents duplicate execution, resolves crashed boundaries, and updates journal.
     */
    public synchronized void reconcile(Path bookDir, String bookId, BookStore store) throws IOException {
        List<SourceChange> events = readAll(bookDir, bookId);
        if (events.isEmpty()) return;

        Map<String, SourceChange> latestByTarget = new LinkedHashMap<>();
        for (SourceChange sc : events) {
            String targetKey = sc.targetKind() + ":" + (sc.pageNumber() == null ? "0" : sc.pageNumber());
            latestByTarget.put(targetKey, sc);
        }

        for (SourceChange latest : latestByTarget.values()) {
            if (!"PREPARED".equals(latest.state())) continue;

            if ("PAGE".equals(latest.targetKind()) && latest.pageNumber() != null) {
                int pageNum = latest.pageNumber();
                Page page = store.readPage(bookId, pageNum);
                if (page == null) {
                    markNotPublished(bookDir, bookId, latest.sourceSeq());
                    continue;
                }
                String pageHash = store.pageCommitJournal().hash(page);
                int rev = BookStore.revisionOrZero(page);

                boolean matchesPublished = (latest.commitId() != null && latest.commitId().equals(page.lastCommitId())
                        || latest.afterHash() != null && latest.afterHash().equals(pageHash))
                        && latest.afterRevision() != null && rev == latest.afterRevision();

                if (matchesPublished) {
                    // Page published successfully! Settle PREPARED -> COMMITTED
                    commit(bookDir, bookId, latest.sourceSeq(), pageNum, latest.commitId(), rev, pageHash);
                } else if (latest.beforeRevision() != null && rev == latest.beforeRevision()
                        && Objects.equals(latest.beforeHash(), pageHash)) {
                    // Page was not published before crash
                    markNotPublished(bookDir, bookId, latest.sourceSeq(), pageNum);
                } else if (latest.afterRevision() != null && rev > latest.afterRevision()) {
                    // Subsequent commit already settled
                    commit(bookDir, bookId, latest.sourceSeq(), pageNum, latest.commitId(), rev, pageHash);
                } else {
                    // Unreconciled state: mark UNKNOWN
                    SourceChange unknown = new SourceChange(latest.sourceSeq(), latest.targetKind(), bookId,
                            latest.pageNumber(), latest.commitId(), latest.metadataChangeId(), latest.beforeRevision(),
                            latest.afterRevision(), latest.beforeHash(), latest.afterHash(), "RECONCILE_UNKNOWN",
                            "UNKNOWN", Instant.now());
                    appendEvent(bookDir, bookId, unknown);
                }
            }
        }
    }
}
