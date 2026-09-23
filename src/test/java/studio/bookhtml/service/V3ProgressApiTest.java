package studio.bookhtml.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.ProcessingSnapshot;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("G09: V3 进度 API、持久 Journal 重放与 V2 兼容性专项测试")
class V3ProgressApiTest {

    @Test
    @DisplayName("进度流水持久化与重放：进程崩溃后精准恢复阶段、单位计数、计划哈希与未完成中断态")
    void testProgressJournalPersistenceAndRecovery(@TempDir Path tempDir) {
        ProgressJournal journal = new ProgressJournal(tempDir);
        ProcessingProgressService service = new ProcessingProgressService();
        service.setJournal(journal);

        UUID attemptId = service.begin("book-v3", 1, 0);
        service.stage("book-v3", 1, attemptId, "OCR");
        service.stage("book-v3", 1, attemptId, "STRUCTURE");
        service.stage("book-v3", 1, attemptId, "REVIEW");
        service.plan("book-v3", 1, attemptId, "TEXT_GROUPS", 4);
        service.unitDone("book-v3", 1, attemptId, "chunk-1", "SUCCEEDED");
        service.unitDone("book-v3", 1, attemptId, "chunk-2", "SUCCEEDED");
        service.unitDone("book-v3", 1, attemptId, "chunk-3", "FAILED");

        ProcessingSnapshot beforeCrash = service.snapshot("book-v3", 1, attemptId);
        assertNotNull(beforeCrash);
        assertEquals("REVIEW", beforeCrash.stage());
        assertEquals(4, beforeCrash.units().total());
        assertEquals(2, beforeCrash.units().succeeded());
        assertEquals(1, beforeCrash.units().failed());
        assertNotNull(beforeCrash.parentPlanHash());
        assertNotNull(beforeCrash.reviewPlanHash());

        // Simulate crash: instantiate new service with same storage journal
        ProgressJournal recoveryJournal = new ProgressJournal(tempDir);
        ProgressJournal.JournalRecovery recovery = recoveryJournal.replay("book-v3", 1);
        assertNotNull(recovery, "崩溃后流水记录必须可反查");
        assertEquals(attemptId, recovery.attemptId());
        assertEquals("REVIEW", recovery.stage());
        assertEquals(4, recovery.totalUnits());
        assertEquals(2, recovery.succeeded());
        assertEquals(1, recovery.failed());
        assertEquals(0, recovery.skipped());
        assertEquals(0, recovery.cancelled());
        assertEquals("INTERRUPTED", recovery.lifecycle(), "未完成任务重启后收敛为 INTERRUPTED");
        assertEquals("CRASH_RECOVERED_INTERRUPTED", recovery.messageCode());

        // Test snapshot projection from recovered journal
        ProcessingSnapshot recoveredSnapshot = recovery.toSnapshot(0, false);
        assertEquals(2, recoveredSnapshot.schemaVersion(), "V3 扩展保持 schemaVersion=2 兼容");
        assertEquals("INTERRUPTED", recoveredSnapshot.lifecycle());
        assertEquals("REVIEW", recoveredSnapshot.stage());
        assertTrue(recoveredSnapshot.canRetry());
        assertFalse(recoveredSnapshot.canStop());
        assertEquals(beforeCrash.parentPlanHash(), recoveredSnapshot.parentPlanHash());
        assertEquals(beforeCrash.reviewPlanHash(), recoveredSnapshot.reviewPlanHash());
        assertEquals(2.0 / 3.0, recoveredSnapshot.accuracyRatio(), 0.001);
    }

    @Test
    @DisplayName("PARTIAL 结算语义：切片未完全成功或未达全量时结算为 PARTIAL 与 WORK_PLAN_INCOMPLETE")
    void testPartialSettlementSemantics(@TempDir Path tempDir) {
        ProgressJournal journal = new ProgressJournal(tempDir);
        ProcessingProgressService service = new ProcessingProgressService();
        service.setJournal(journal);

        UUID attemptId = service.begin("book-partial", 2, 1);
        service.stage("book-partial", 2, attemptId, "REVIEW");
        service.plan("book-partial", 2, attemptId, "TEXT_GROUPS", 4);
        service.unitDone("book-partial", 2, attemptId, "c1", "SUCCEEDED");
        service.unitDone("book-partial", 2, attemptId, "c2", "SUCCEEDED");
        service.unitDone("book-partial", 2, attemptId, "c3", "FAILED");
        service.unitDone("book-partial", 2, attemptId, "c4", "SUCCEEDED");

        // Attempt finish with SUCCEEDED, but because 1 unit failed, settles as PARTIAL
        service.finish("book-partial", 2, attemptId, "SUCCEEDED", "FINISHED_ALL", false);

        ProcessingSnapshot snap = service.snapshot("book-partial", 2, attemptId);
        assertEquals("PARTIAL", snap.lifecycle(), "存在失败切片必须收敛为 PARTIAL");
        assertEquals("WORK_PLAN_INCOMPLETE", snap.messageCode());
        assertTrue(snap.canRetry());
        assertFalse(snap.canStop());
        assertEquals(0.75, snap.accuracyRatio(), 0.001);
        assertTrue(snap.percent() < 100);

        // Journal also recorded FINISHED with PARTIAL
        var replay = journal.replay("book-partial", 2);
        assertNotNull(replay);
        assertEquals("PARTIAL", replay.lifecycle());
        assertEquals("WORK_PLAN_INCOMPLETE", replay.messageCode());
    }

    @Test
    @DisplayName("V2 适配器向后兼容：保持 schemaVersion=2、serverInstanceId、里程碑 percent 及所有 V2 字段")
    void testV2AdapterBackwardCompatibility() {
        ProcessingProgressService service = new ProcessingProgressService();
        UUID attemptId = service.begin("book-v2", 1, 0);

        ProcessingSnapshot snap = service.snapshot("book-v2", 1, attemptId);
        assertEquals(2, snap.schemaVersion());
        assertNotNull(snap.serverInstanceId());
        assertEquals("RUNNING", snap.lifecycle());
        assertEquals("PREPARING", snap.stage());
        assertEquals(0, snap.percent());
        assertTrue(snap.canStop());
        assertFalse(snap.canRetry());
        assertEquals("PROCESSING_STARTED", snap.messageCode());

        // Advance to PUBLISHING and finish
        service.stage("book-v2", 1, attemptId, "PUBLISHING");
        service.baselinePublished("book-v2", 1, attemptId, 1, true);
        assertEquals(95, service.snapshot("book-v2", 1, attemptId).percent());

        service.finish("book-v2", 1, attemptId, "SUCCEEDED", "DONE", false);
        ProcessingSnapshot done = service.snapshot("book-v2", 1, attemptId);
        assertEquals(100, done.percent());
        assertEquals("SUCCEEDED", done.lifecycle());
        assertEquals(1, done.publishedRevision());
        assertTrue(done.canRead());
    }

    @Test
    @DisplayName("崩溃中断恢复：在任意阶段直接断电或崩溃均能准确恢复为 INTERRUPTED 且可重试")
    void testCrashMidwayProducesInterruptedWithRetry(@TempDir Path tempDir) {
        ProgressJournal journal = new ProgressJournal(tempDir);
        ProcessingProgressService service = new ProcessingProgressService();
        service.setJournal(journal);

        UUID attemptId = service.begin("book-crash", 3, 0);
        service.stage("book-crash", 3, attemptId, "OCR");

        // Crash happens without service.finish()
        ProgressJournal crashReader = new ProgressJournal(tempDir);
        var recovery = crashReader.replay("book-crash", 3);
        assertNotNull(recovery);
        assertEquals("OCR", recovery.stage());
        assertEquals("INTERRUPTED", recovery.lifecycle());
        assertEquals("CRASH_RECOVERED_INTERRUPTED", recovery.messageCode());

        ProcessingSnapshot snapshot = recovery.toSnapshot(0, false);
        assertTrue(snapshot.canRetry());
        assertFalse(snapshot.canStop());
    }

    @Test
    @DisplayName("世代递增与隔离：同一页的新尝试覆盖旧流水，跨页独立隔离")
    void testMonotonicAttemptSeqAndIsolation(@TempDir Path tempDir) {
        ProgressJournal journal = new ProgressJournal(tempDir);
        ProcessingProgressService service = new ProcessingProgressService();
        service.setJournal(journal);

        UUID first = service.begin("book-seq", 1, 0);
        service.stage("book-seq", 1, first, "OCR");
        service.finish("book-seq", 1, first, "FAILED", "ERR", true);

        // Second generation attempt on same page
        UUID second = service.begin("book-seq", 1, 1);
        service.stage("book-seq", 1, second, "STRUCTURE");

        var replay = journal.replay("book-seq", 1);
        assertNotNull(replay);
        assertEquals(second, replay.attemptId());
        assertEquals("STRUCTURE", replay.stage());

        // Page 2 is separate
        UUID page2 = service.begin("book-seq", 2, 0);
        service.stage("book-seq", 2, page2, "VALIDATING");

        var replay2 = journal.replay("book-seq", 2);
        assertNotNull(replay2);
        assertEquals(page2, replay2.attemptId());
        assertEquals("VALIDATING", replay2.stage());
    }
}
