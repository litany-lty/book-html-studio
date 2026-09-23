package studio.bookhtml.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("G09: WorkPlan 两层冻结模型与权重 Reducer 专项测试")
class WorkPlanReducerTest {

    @Test
    @DisplayName("哈希确定性：相同输入生成绝对相同哈希，任一参数漂移哈希变更")
    void testHashDeterminism() {
        WorkPlan planA = WorkPlan.createDefault("book-1", 1, 0, 100L, "ctx-hash-1");
        WorkPlan planB = WorkPlan.createDefault("book-1", 1, 0, 100L, "ctx-hash-1");

        assertNotNull(planA.parentPlanHash());
        assertEquals(64, planA.parentPlanHash().length());
        assertEquals(planA.parentPlanHash(), planB.parentPlanHash());

        WorkPlan planDifferentSeq = WorkPlan.createDefault("book-1", 1, 0, 101L, "ctx-hash-1");
        assertNotEquals(planA.parentPlanHash(), planDifferentSeq.parentPlanHash());

        WorkPlan planDifferentCtx = WorkPlan.createDefault("book-1", 1, 0, 100L, "ctx-hash-2");
        assertNotEquals(planA.parentPlanHash(), planDifferentCtx.parentPlanHash());

        WorkPlan frozenA = planA.freezeReviewSubPlan(3, List.of("c1", "c2", "c3"));
        WorkPlan frozenB = planB.freezeReviewSubPlan(3, List.of("c1", "c2", "c3"));
        assertNotNull(frozenA.reviewPlanHash());
        assertEquals(frozenA.reviewPlanHash(), frozenB.reviewPlanHash());

        WorkPlan frozenC = planA.freezeReviewSubPlan(3, List.of("c1", "c2", "c4"));
        assertNotEquals(frozenA.reviewPlanHash(), frozenC.reviewPlanHash());
    }

    @Test
    @DisplayName("两层冻结：父计划哈希与子审查计划哈希绑定，分母冻结")
    void testTwoLevelFreezing() {
        WorkPlan plan = WorkPlan.createDefault("book-test", 5, 2, 42L, "ctx-test-42");
        assertFalse(plan.frozen());
        assertNull(plan.reviewPlanHash());
        assertNull(plan.reviewSubPlan());

        WorkPlan frozen = plan.freezeReviewSubPlan(4, List.of("chunk-1", "chunk-2", "chunk-3", "chunk-4"));
        assertTrue(frozen.frozen());
        assertNotNull(frozen.reviewPlanHash());
        assertNotNull(frozen.reviewSubPlan());
        assertEquals(4, frozen.reviewSubPlan().totalUnits());
        assertEquals(4, frozen.stages().get("REVIEW").totalUnits());

        // Idempotent freeze with same params returns self
        WorkPlan refrozen = frozen.freezeReviewSubPlan(4, List.of("chunk-1", "chunk-2", "chunk-3", "chunk-4"));
        assertSame(frozen, refrozen);
    }

    @Test
    @DisplayName("权重 Reducer 状态转移：OCR(30) -> STRUCTURE(20) -> REVIEW(35) -> VALIDATING(10) -> PUBLISHING(5)")
    void testWeightReducerTransitions() {
        WorkPlan plan = WorkPlan.createDefault("book-flow", 1, 0, 1L, "ctx-1");
        assertEquals(0, plan.weightedPercent());

        // 1. OCR (30%)
        plan = plan.transitionStage("OCR");
        plan = plan.recordUnitDone("OCR", "p1", "SUCCEEDED");
        assertEquals(30, plan.weightedPercent());

        // 2. STRUCTURE (20%)
        plan = plan.transitionStage("STRUCTURE");
        plan = plan.recordUnitDone("STRUCTURE", "p1", "SUCCEEDED");
        assertEquals(50, plan.weightedPercent());

        // 3. REVIEW (35%) with 10 chunks
        plan = plan.freezeReviewSubPlan(10, null);
        plan = plan.transitionStage("REVIEW");

        // 5 chunks completed -> 50 + 35 * 0.5 = 67%
        for (int i = 0; i < 5; i++) {
            plan = plan.recordUnitDone("REVIEW", "chunk-" + i, "SUCCEEDED");
        }
        assertEquals(67, plan.weightedPercent());

        // Remaining 5 chunks -> 50 + 35 = 85%
        for (int i = 5; i < 10; i++) {
            plan = plan.recordUnitDone("REVIEW", "chunk-" + i, "SUCCEEDED");
        }
        assertEquals(85, plan.weightedPercent());

        // 4. VALIDATING (10%) -> 85 + 10 = 95%
        plan = plan.transitionStage("VALIDATING");
        plan = plan.recordUnitDone("VALIDATING", "p1", "SUCCEEDED");
        assertEquals(95, plan.weightedPercent());

        // 5. PUBLISHING (5%) -> 95 + 5 = 100% upon terminal SUCCEEDED
        plan = plan.transitionStage("PUBLISHING");
        plan = plan.recordUnitDone("PUBLISHING", "p1", "SUCCEEDED");
        plan = plan.finish("SUCCEEDED");
        assertEquals(100, plan.weightedPercent());
    }

    @Test
    @DisplayName("冻结分母不变量：冻结后不可变更分母，完成单位不可超额")
    void testDenominatorImmutability() {
        WorkPlan plan = WorkPlan.createDefault("book-guard", 2, 1, 20L, "ctx-guard");
        WorkPlan frozen = plan.freezeReviewSubPlan(3, List.of("c1", "c2", "c3"));

        // Changing denominator on frozen plan throws IllegalStateException
        assertThrows(IllegalStateException.class, () -> frozen.freezeReviewSubPlan(4, List.of("c1", "c2", "c3", "c4")));
        assertThrows(IllegalStateException.class, () -> frozen.freezeReviewSubPlan(2, List.of("c1", "c2")));

        WorkPlan running = frozen.transitionStage("REVIEW");
        running = running.recordUnitDone("REVIEW", "c1", "SUCCEEDED");
        running = running.recordUnitDone("REVIEW", "c2", "SUCCEEDED");
        running = running.recordUnitDone("REVIEW", "c3", "SUCCEEDED");
        assertEquals(3, running.totalEndedUnits());

        // Unit exceeds frozen plan throws IllegalStateException
        final WorkPlan fullPlan = running;
        assertThrows(IllegalStateException.class, () -> fullPlan.recordUnitDone("REVIEW", "c4", "SUCCEEDED"));

        // Idempotent duplicate unit doesn't throw and doesn't change counts
        WorkPlan idemp = fullPlan.recordUnitDone("REVIEW", "c3", "SUCCEEDED");
        assertEquals(3, idemp.totalEndedUnits());

        // Invalid outcome throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> fullPlan.recordUnitDone("REVIEW", "c-invalid", "INVALID_STATUS"));
    }

    @Test
    @DisplayName("完成率与准确率分离：100% 切片完成率下允许 80% 准确率，PARTIAL 结算正确")
    void testCompletionRateVsAccuracyRatio() {
        WorkPlan plan = WorkPlan.createDefault("book-acc", 3, 0, 5L, "ctx-acc");
        plan = plan.transitionStage("OCR").recordUnitDone("OCR", "p1", "SUCCEEDED");
        plan = plan.transitionStage("STRUCTURE").recordUnitDone("STRUCTURE", "p1", "SUCCEEDED");

        // 10 review chunks: 8 SUCCEEDED, 2 FAILED
        plan = plan.freezeReviewSubPlan(10, null);
        plan = plan.transitionStage("REVIEW");
        for (int i = 0; i < 8; i++) {
            plan = plan.recordUnitDone("REVIEW", "chunk-" + i, "SUCCEEDED");
        }
        for (int i = 8; i < 10; i++) {
            plan = plan.recordUnitDone("REVIEW", "chunk-" + i, "FAILED");
        }

        // 10 units finished out of 10 -> review stage 100% completed, weightedPercent = 85%
        assertEquals(85, plan.weightedPercent());
        // Stage completed units = 10 (8 succeeded + 2 failed)
        assertEquals(10, plan.stages().get("REVIEW").completedUnits());

        // Total accuracy across all completed units:
        // OCR (1 succ) + STRUCTURE (1 succ) + REVIEW (8 succ, 2 fail) = 10 succ out of 12 completed units = 10/12 ≈ 0.8333
        // REVIEW stage specific accuracy: 8 / 10 = 0.80
        assertEquals(0.80, plan.stages().get("REVIEW").accuracyRatio(), 0.001);
        assertEquals(10.0 / 12.0, plan.accuracyRatio(), 0.001);

        // Advance to publishing and settle with PARTIAL
        plan = plan.transitionStage("VALIDATING").recordUnitDone("VALIDATING", "p1", "SUCCEEDED");
        plan = plan.transitionStage("PUBLISHING").recordUnitDone("PUBLISHING", "p1", "SUCCEEDED");
        plan = plan.finish("PARTIAL");

        assertEquals("PARTIAL", plan.lifecycle());
        // Since lifecycle is PARTIAL (not SUCCEEDED), weightedPercent is capped at <= 99
        assertTrue(plan.weightedPercent() <= 99);
        assertTrue(plan.weightedPercent() >= 95);
        assertEquals(0.80, plan.stages().get("REVIEW").accuracyRatio(), 0.001);
    }
}
