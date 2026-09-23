package studio.bookhtml.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import studio.bookhtml.domain.ProcessingSnapshot;
import studio.bookhtml.domain.WorkPlan;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.DurableJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * G09 / B08: 进度流水日志 (ProgressJournal)。
 * 记录连续且持久的 eventSeq，包含 PLAN_FROZEN, STAGE_TRANSITION, UNIT_DONE, FINISHED 事件；
 * 支持进程重启或崩溃后完整重放恢复进度快照与工作计划。
 */
@Service
public class ProgressJournal {
    public static final int MAX_JOURNAL_BYTES = 65536; // 64 KiB
    public static final int MAX_EVENTS = 1024;

    private BookStore store;
    private Path baseDir;
    private final ObjectMapper json;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JournalEvent(
            long eventSeq,
            String eventType, // "PLAN_FROZEN", "STAGE_TRANSITION", "UNIT_DONE", "FINISHED"
            String stage,
            String unitId,
            String outcome,
            String lifecycle,
            String messageCode,
            String parentPlanHash,
            String reviewPlanHash,
            String contextHash,
            int totalUnits,
            Instant timestamp
    ) {
        public JournalEvent {
            Objects.requireNonNull(eventType, "eventType 不能为空");
            timestamp = timestamp == null ? Instant.now() : timestamp;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JournalRecord(
            int schemaVersion,
            String bookId,
            int pageNumber,
            UUID attemptId,
            long attemptSeq,
            List<JournalEvent> events
    ) {
        public JournalRecord {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    public record JournalRecovery(
            String bookId,
            int pageNumber,
            UUID attemptId,
            long attemptSeq,
            long lastEventSeq,
            WorkPlan workPlan,
            String lifecycle,
            String stage,
            int totalUnits,
            int succeeded,
            int failed,
            int skipped,
            int cancelled,
            String messageCode,
            Instant startedAt,
            Instant lastProgressAt
    ) {
        public ProcessingSnapshot toSnapshot(int publishedRevision, boolean readable) {
            boolean terminal = Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "INTERRUPTED", "UNKNOWN").contains(lifecycle);
            boolean canRetry = terminal && !"SUCCEEDED".equals(lifecycle);
            boolean canStop = !terminal;
            var unitCounts = new ProcessingSnapshot.UnitCounts(
                    "PAGE", totalUnits, succeeded, failed, skipped, cancelled, 0
            );
            String parentPlanHash = workPlan != null ? workPlan.parentPlanHash() : null;
            String reviewPlanHash = workPlan != null ? workPlan.reviewPlanHash() : null;
            String contextHash = workPlan != null ? workPlan.contextHash() : null;
            int weightedPercent = workPlan != null ? workPlan.weightedPercent() : -1;
            double accuracyRatio = workPlan != null ? workPlan.accuracyRatio() : (
                    (succeeded + failed + skipped + cancelled == 0) ? 1.0 : (double) succeeded / (succeeded + failed + skipped + cancelled)
            );
            return new ProcessingSnapshot(
                    2, bookId, pageNumber, attemptId, lastEventSeq, lifecycle, stage,
                    readable ? "OCR_READABLE" : "ORIGINAL_ONLY",
                    publishedRevision, startedAt != null ? startedAt : Instant.now(),
                    lastProgressAt != null ? lastProgressAt : Instant.now(),
                    lastProgressAt != null ? lastProgressAt : Instant.now(),
                    unitCounts, readable, canStop, canRetry, messageCode, attemptSeq,
                    parentPlanHash, reviewPlanHash, contextHash, weightedPercent, accuracyRatio
            );
        }
    }

    public ProgressJournal() {
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    @Autowired(required = false)
    public ProgressJournal(BookStore store) {
        this();
        this.store = store;
    }

    public ProgressJournal(Path baseDir) {
        this();
        this.baseDir = safeRealDir(baseDir);
    }

    public void setStore(BookStore store) {
        this.store = store;
    }

    public void setBaseDir(Path baseDir) {
        this.baseDir = safeRealDir(baseDir);
    }

    private static Path safeRealDir(Path p) {
        if (p == null) return null;
        try {
            return Files.exists(p) ? p.toRealPath() : p.toAbsolutePath().normalize();
        } catch (Exception e) {
            return p.toAbsolutePath().normalize();
        }
    }

    public Path journalPath(String bookId, int pageNumber) {
        if (store != null) {
            Path dir = store.bookDir(bookId);
            try { if (Files.exists(dir)) dir = dir.toRealPath(); } catch (Exception ignore) {}
            return dir.resolve("pages/progress").resolve(pageNumber + ".journal.json");
        }
        if (baseDir != null) {
            Path dir = baseDir;
            try { if (Files.exists(dir)) dir = dir.toRealPath(); } catch (Exception ignore) {}
            return dir.resolve(bookId).resolve("pages/progress").resolve(pageNumber + ".journal.json");
        }
        return null;
    }

    public synchronized boolean hasJournal(String bookId, int pageNumber) {
        Path path = journalPath(bookId, pageNumber);
        return path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    public synchronized void recordEvent(String bookId, int pageNumber, UUID attemptId, long attemptSeq, JournalEvent event) {
        Path path = journalPath(bookId, pageNumber);
        if (path == null) return;

        try {
            JournalRecord record = readRecord(path);
            List<JournalEvent> events;

            if (record != null) {
                // If attempt sequence is older, drop event
                if (record.attemptSeq() > attemptSeq) return;

                if (record.attemptSeq() < attemptSeq || !Objects.equals(record.attemptId(), attemptId)) {
                    // Newer attempt, start fresh
                    events = new ArrayList<>();
                } else {
                    events = new ArrayList<>(record.events());
                    if (!events.isEmpty() && event.eventSeq() <= events.get(events.size() - 1).eventSeq()) {
                        // Monotonic eventSeq violation or duplicate
                        return;
                    }
                }
            } else {
                events = new ArrayList<>();
            }

            if (events.size() >= MAX_EVENTS) {
                // Keep the initial PLAN_FROZEN, and retain last events
                JournalEvent first = events.get(0);
                events = new ArrayList<>(events.subList(events.size() - (MAX_EVENTS / 2), events.size()));
                if (!events.contains(first)) {
                    events.add(0, first);
                }
            }

            events.add(event);
            JournalRecord updated = new JournalRecord(1, bookId, pageNumber, attemptId, attemptSeq, events);
            DurableJson.write(path, updated, json, MAX_JOURNAL_BYTES);
        } catch (IOException e) {
            // Durable journal logging failure should not break in-memory processing, but log warning
            System.getLogger(ProgressJournal.class.getName()).log(System.Logger.Level.WARNING,
                    "Failed to record progress journal event: " + e.getMessage());
        }
    }

    public synchronized JournalRecord readRecord(Path path) {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_JOURNAL_BYTES) return null;
            try (JsonParser parser = json.getFactory().createParser(bytes)) {
                return json.readValue(parser, JournalRecord.class);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized JournalRecovery replay(String bookId, int pageNumber) {
        Path path = journalPath(bookId, pageNumber);
        if (path == null) return null;
        JournalRecord record = readRecord(path);
        if (record == null || record.events().isEmpty()) return null;

        WorkPlan plan = null;
        String stage = "PREPARING";
        String lifecycle = "RUNNING";
        String messageCode = "RECOVERED_FROM_JOURNAL";
        int total = 0, succeeded = 0, failed = 0, skipped = 0, cancelled = 0;
        Instant startedAt = null;
        Instant lastProgressAt = null;
        long lastSeq = 0;

        for (JournalEvent ev : record.events()) {
            if (startedAt == null) startedAt = ev.timestamp();
            lastProgressAt = ev.timestamp();
            lastSeq = ev.eventSeq();

            switch (ev.eventType()) {
                case "PLAN_FROZEN" -> {
                    total = ev.totalUnits();
                    plan = WorkPlan.createDefault(bookId, pageNumber, 0, record.attemptSeq(), ev.contextHash());
                    if (ev.totalUnits() > 0) {
                        plan = plan.freezeReviewSubPlan(ev.totalUnits(), null);
                    }
                    if (ev.parentPlanHash() != null || ev.reviewPlanHash() != null) {
                        plan = plan.withPlanHashes(ev.parentPlanHash(), ev.reviewPlanHash());
                    }
                }
                case "STAGE_TRANSITION" -> {
                    stage = ev.stage() != null ? ev.stage() : stage;
                    if (plan != null) {
                        plan = plan.transitionStage(stage);
                    }
                }
                case "UNIT_DONE" -> {
                    switch (ev.outcome() != null ? ev.outcome() : "") {
                        case "SUCCEEDED" -> succeeded++;
                        case "FAILED" -> failed++;
                        case "CANCELLED" -> cancelled++;
                        default -> skipped++;
                    }
                    if (plan != null && ev.unitId() != null && ev.outcome() != null) {
                        try {
                            plan = plan.recordUnitDone(stage, ev.unitId(), ev.outcome());
                        } catch (Exception ignore) {}
                    }
                }
                case "FINISHED" -> {
                    lifecycle = ev.lifecycle() != null ? ev.lifecycle() : lifecycle;
                    messageCode = ev.messageCode() != null ? ev.messageCode() : messageCode;
                    if (plan != null) {
                        plan = plan.finish(lifecycle);
                    }
                }
                default -> {}
            }
        }

        // If crashed before normal terminal completion, settle as INTERRUPTED
        if (!Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "INTERRUPTED", "UNKNOWN").contains(lifecycle)) {
            lifecycle = "INTERRUPTED";
            messageCode = "CRASH_RECOVERED_INTERRUPTED";
            if (plan != null) {
                plan = plan.finish(lifecycle);
            }
        }

        return new JournalRecovery(
                record.bookId(),
                record.pageNumber(),
                record.attemptId(),
                record.attemptSeq(),
                lastSeq,
                plan,
                lifecycle,
                stage,
                total,
                succeeded,
                failed,
                skipped,
                cancelled,
                messageCode,
                startedAt,
                lastProgressAt
        );
    }
}
