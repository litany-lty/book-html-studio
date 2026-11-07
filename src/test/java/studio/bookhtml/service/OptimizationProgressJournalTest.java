package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.WorkPlan;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationProgressJournalTest {
    @TempDir Path data;

    @Test void reviewPlanFreezePreservesAlreadyCompletedStagesOnRecovery() {
        var journal = new ProgressJournal(data);
        var service = new ProcessingProgressService();
        service.setJournal(journal);
        var id = service.begin("book", 1, 0);
        service.plan("book", 1, id, "PAGE", 1);
        service.stage("book", 1, id, "OCR");
        service.stage("book", 1, id, "STRUCTURE");
        service.stage("book", 1, id, "REVIEW");
        service.plan("book", 1, id, "TEXT_GROUPS", 2);
        service.unitDone("book", 1, id, "first", "SUCCEEDED");
        var live = service.snapshot("book", 1, id);
        var recovered = journal.replay("book", 1).toSnapshot(0, false);
        assertEquals(67, live.weightedPercent());
        assertEquals(live.weightedPercent(), recovered.weightedPercent());
        assertEquals(live.parentPlanHash(), recovered.parentPlanHash());
        assertEquals(live.reviewPlanHash(), recovered.reviewPlanHash());
        assertEquals("INTERRUPTED", recovered.lifecycle());
        assertFalse(recovered.canStop());
    }

    @Test void duplicateUnitAndLateEventsCannotAlterTerminalRecovery() {
        var journal = new ProgressJournal(data);
        var id = UUID.randomUUID();
        journal.recordEvent("book", 1, id, 1, event(1, "PLAN_FROZEN", null, null, null));
        journal.recordEvent("book", 1, id, 1, event(2, "UNIT_DONE", "first", "SUCCEEDED", null));
        journal.recordEvent("book", 1, id, 1, event(3, "UNIT_DONE", "first", "FAILED", null));
        journal.recordEvent("book", 1, id, 1, event(4, "FINISHED", null, null, "CANCELLED"));
        journal.recordEvent("book", 1, id, 1, event(5, "STAGE_TRANSITION", null, null, null));
        journal.recordEvent("book", 1, id, 1, event(6, "UNIT_DONE", "late", "SUCCEEDED", null));
        var replay = journal.replay("book", 1);
        assertEquals("CANCELLED", replay.lifecycle());
        assertEquals(1, replay.succeeded());
        assertEquals(0, replay.failed());
        assertEquals(4, replay.lastEventSeq());
    }

    @Test void conflictingAttemptAtSameGenerationDoesNotReplaceExistingJournal() {
        var journal = new ProgressJournal(data);
        var id = UUID.randomUUID();
        journal.recordEvent("book", 1, id, 1, event(1, "PLAN_FROZEN", null, null, null));
        journal.recordEvent("book", 1, UUID.randomUUID(), 1, event(1, "FINISHED", null, null, "SUCCEEDED"));
        assertEquals(id, journal.replay("book", 1).attemptId());
        assertEquals("INTERRUPTED", journal.replay("book", 1).lifecycle());
    }

    @Test void replacingAnUnfrozenPlanRestoresTheLatestContextIdentity() {
        var journal = new ProgressJournal(data);
        var service = new ProcessingProgressService();
        service.setJournal(journal);
        var id = service.begin("book", 1, 0);
        service.plan("book", 1, id, "PAGE", 1);
        service.plan("book", 1, id, WorkPlan.createDefault("book", 1, 0, 1, "ctx-v2"));
        service.stage("book", 1, id, "OCR");
        service.stage("book", 1, id, "REVIEW");
        service.plan("book", 1, id, "TEXT_GROUPS", 2);
        service.unitDone("book", 1, id, "first", "SUCCEEDED");
        var live = service.snapshot("book", 1, id);
        var recovered = journal.replay("book", 1).toSnapshot(0, false);
        assertEquals("ctx-v2", live.contextHash());
        assertEquals(live.contextHash(), recovered.contextHash());
        assertEquals(live.parentPlanHash(), recovered.parentPlanHash());
        assertEquals(live.reviewPlanHash(), recovered.reviewPlanHash());
        assertEquals(live.weightedPercent(), recovered.weightedPercent());
    }

    private static ProgressJournal.JournalEvent event(long seq, String type, String unit, String outcome, String lifecycle) {
        return new ProgressJournal.JournalEvent(seq, type, "REVIEW", unit, outcome, lifecycle, "test",
                "parent", "review", "context", 2, Instant.now());
    }
}
