package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.ProcessingSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

/**
 * U4：阶段和单位事件聚合。不负责正式页保存；有界、可恢复终态摘要。
 * 事件只接真实代码入口/出口（派发、处理开始/结束、提交、前端获取成功）。
 */
@Service
public class ProcessingProgressService {
    private static final int MAX_TRACKED = 512;

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private long generation;

    private static final class Entry {
        long generation;
        final String bookId;
        final int pageNumber;
        final UUID attemptId;
        volatile long snapshotVersion;
        volatile String lifecycle = "RUNNING";
        volatile String stage = "PREPARING";
        volatile String availability = "ORIGINAL_ONLY";
        volatile int publishedRevision;
        final Instant startedAt = Instant.now();
        volatile Instant stageStartedAt = Instant.now();
        volatile Instant lastProgressAt = Instant.now();
        volatile String unitKind = "PAGE";
        volatile int total;
        volatile int succeeded;
        volatile int failed;
        volatile int skipped;
        volatile int cancelled;
        volatile int inFlight;
        volatile boolean canRead;
        volatile boolean canStop = true;
        volatile boolean canRetry;
        volatile String messageCode = "PROCESSING_STARTED";

        Entry(String bookId, int pageNumber, UUID attemptId, int publishedRevision) {
            this.bookId = bookId;
            this.pageNumber = pageNumber;
            this.attemptId = attemptId;
            this.publishedRevision = publishedRevision;
        }
    }

    private static String key(String bookId, int pageNumber, UUID attemptId) {
        return bookId + ":" + pageNumber + ":" + attemptId;
    }

    /** 新 attempt 开始（派发时调用）。 */
    public synchronized UUID begin(String bookId, int pageNumber, int publishedRevision) {
        // Telemetry is bounded independently of jobs. Eviction never cancels work.
        while (entries.size() >= MAX_TRACKED) {
            String oldest = entries.entrySet().stream()
                    .filter(e -> !"RUNNING".equals(e.getValue().lifecycle))
                    .map(Map.Entry::getKey).findFirst().orElse(entries.keySet().iterator().next());
            entries.remove(oldest);
        }
        UUID attemptId = UUID.randomUUID();
        Entry entry = new Entry(bookId, pageNumber, attemptId, publishedRevision);
        entry.generation = ++generation;
        entries.put(key(bookId, pageNumber, attemptId), entry);
        return attemptId;
    }

    public synchronized void stage(String bookId, int pageNumber, UUID attemptId, String stage) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || !"RUNNING".equals(e.lifecycle)) return;
        e.stage = stage;
        e.stageStartedAt = Instant.now();
        e.lastProgressAt = Instant.now();
        e.snapshotVersion++;
    }

    public synchronized void plan(String bookId, int pageNumber, UUID attemptId, String unitKind, int total) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || !"RUNNING".equals(e.lifecycle)) return;
        e.unitKind = unitKind;
        e.total = Math.max(0, total);
        e.succeeded = e.failed = e.skipped = e.cancelled = e.inFlight = 0;
        e.lastProgressAt = Instant.now();
        e.snapshotVersion++;
    }

    public synchronized void unitDone(String bookId, int pageNumber, UUID attemptId, boolean ok) {
        unitDone(bookId, pageNumber, attemptId, ok, true);
    }

    public synchronized void unitDone(String bookId, int pageNumber, UUID attemptId, boolean ok, boolean wasInFlight) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || !"RUNNING".equals(e.lifecycle)) return;
        if (e.succeeded + e.failed + e.skipped + e.cancelled >= e.total) return;
        if (ok) e.succeeded++; else e.failed++;
        if (wasInFlight && e.inFlight > 0) e.inFlight--;
        e.lastProgressAt = Instant.now();
        e.snapshotVersion++;
    }

    public synchronized void inFlight(String bookId, int pageNumber, UUID attemptId, int delta) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || !"RUNNING".equals(e.lifecycle)) return;
        e.inFlight = Math.max(0, Math.min(e.total - e.succeeded - e.failed - e.skipped - e.cancelled, e.inFlight + delta));
        e.snapshotVersion++;
    }

    /** 可读基线落盘后调用：此前内存里拿到结果不算可读。 */
    public synchronized void baselinePublished(String bookId, int pageNumber, UUID attemptId,
                                  int revision, boolean enhanced) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || !"RUNNING".equals(e.lifecycle)) return;
        e.publishedRevision = revision;
        e.availability = enhanced ? "ENHANCED" : "OCR_READABLE";
        e.canRead = true;
        e.lastProgressAt = Instant.now();
        e.snapshotVersion++;
    }

    public synchronized void finish(String bookId, int pageNumber, UUID attemptId, String lifecycle,
                       String messageCode, boolean canRetry) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null || !"RUNNING".equals(e.lifecycle)) return;
        e.lifecycle = lifecycle;
        e.messageCode = messageCode;
        e.canRetry = canRetry;
        e.canStop = false;
        e.inFlight = 0;
        e.lastProgressAt = Instant.now();
        e.snapshotVersion++;
    }

    public synchronized ProcessingSnapshot snapshot(String bookId, int pageNumber, UUID attemptId) {
        Entry e = entries.get(key(bookId, pageNumber, attemptId));
        if (e == null) return null;
        return new ProcessingSnapshot(2, e.bookId, e.pageNumber, e.attemptId, e.snapshotVersion,
                e.lifecycle, e.stage, e.availability, e.publishedRevision, e.startedAt,
                e.stageStartedAt, e.lastProgressAt,
                new ProcessingSnapshot.UnitCounts(e.unitKind, e.total, e.succeeded, e.failed,
                        e.skipped, e.cancelled, e.inFlight),
                e.canRead, e.canStop, e.canRetry, e.messageCode);
    }

    /** 本页最新 attempt 的快照（按创建代次，而非不同任务内的事件版本）。 */
    public synchronized ProcessingSnapshot latest(String bookId, int pageNumber) {
        ProcessingSnapshot best = null;
        long newest = -1;
        String prefix = bookId + ":" + pageNumber + ":";
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            ProcessingSnapshot snap = snapshot(bookId, pageNumber, entry.getValue().attemptId);
            if (snap != null && entry.getValue().generation > newest) {
                best = snap;
                newest = entry.getValue().generation;
            }
        }
        return best;
    }
}
