package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U5：任务规划。owned/context 分离、预算与降级；超长原文不截断。
 */
class QwenTaskPlannerTest {

    private static QwenTaskPlanner planner() {
        return new QwenTaskPlanner(new QwenAssistProperties());
    }

    private static Block text(String id, int order, String content) {
        return new Block(id, "text", order, new double[]{.1, .1 + order * .05, .5, .04},
                "horizontal-tb", content, content, 0.9, false, false, null, "paddle",
                List.of(id), null, new double[]{10, 20, 50, 10}, List.of());
    }

    private static Block visual(String id, int order, String type) {
        return new Block(id, type, order, new double[]{.1, .1, .8, .4}, "horizontal-tb",
                "图注", "图注", 0.9, false, false, null, "paddle",
                List.of(id), null, new double[]{10, 20, 80, 40}, List.of());
    }

    @Test void qw_groupsBoundedAndOwnedNeverOverlap() {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 20; i++) blocks.add(text("b" + i, i, "正文内容" + i + "填充文字"));
        QwenTaskPlanner.PlannedReview plan = planner().planReview(blocks, "v1", "u3.1");
        assertFalse(plan.chunks().isEmpty());
        Set<String> ownedKeys = new HashSet<>();
        for (QwenTaskPlanner.ChunkTask chunk : plan.chunks()) {
            assertTrue(chunk.ownedRanges().size() >= 1 && chunk.ownedRanges().size() <= 12,
                    "每组 6–12 个短块（尾组允许合并）");
            for (QwenTaskPlanner.OwnedRange owned : chunk.ownedRanges()) {
                assertTrue(ownedKeys.add(owned.sourceId() + ":" + owned.start() + ":" + owned.end()),
                        "拥有写入权的区间不得重叠");
            }
        }
        assertEquals(20, ownedKeys.size(), "计划内来源全覆盖，未核对不算核对成功");
    }

    @Test void qw_atomicVisualNeverSplitByBlockCount() {
        List<Block> blocks = new ArrayList<>();
        blocks.add(text("t0", 0, "前言"));
        blocks.add(visual("fig", 1, "figure"));
        blocks.add(visual("tab", 2, "table"));
        blocks.add(visual("for", 3, "formula"));
        blocks.add(text("t4", 4, "后记"));
        QwenTaskPlanner.PlannedReview plan = planner().planReview(blocks, "v1", "u3.1");
        assertEquals(3, plan.skippedAtomic(), "原子区域不被硬切");
        for (QwenTaskPlanner.ChunkTask chunk : plan.chunks()) {
            for (QwenTaskPlanner.OwnedRange owned : chunk.ownedRanges()) {
                assertFalse(Set.of("fig", "tab", "for").contains(owned.sourceId()));
            }
        }
    }

    @Test void qw_longBlockSlicedByParentIdWithoutBreakingSurrogates() {
        String pair = "\uD83D\uDE00";
        StringBuilder longText = new StringBuilder();
        while (longText.length() < 2400) longText.append("甲");
        longText.append(pair);
        while (longText.length() < 3000) longText.append("乙");
        Block longBlock = text("long", 0, longText.toString());
        QwenTaskPlanner.PlannedReview plan =
                planner().planReview(List.of(longBlock), "v1", "u3.1");
        assertTrue(plan.chunks().size() >= 2, "超长块必须分解，不能直接截断");
        for (QwenTaskPlanner.ChunkTask chunk : plan.chunks()) {
            for (QwenTaskPlanner.OwnedRange owned : chunk.ownedRanges()) {
                assertEquals("long", owned.sourceId(), "保持父块 ID，不制造新来源");
                String slice = longText.substring(owned.start(), owned.end());
                assertFalse(slice.isEmpty());
                // 边界不落代理对中间。
                if (owned.start() > 0) {
                    assertFalse(Character.isLowSurrogate(longText.charAt(owned.start()))
                            && (owned.start() >= longText.length()
                                    || !Character.isHighSurrogate(longText.charAt(owned.start() - 1))
                                    || !Character.isLowSurrogate(longText.charAt(owned.start()))));
                }
            }
        }
        int covered = plan.chunks().stream()
                .flatMap(c -> c.ownedRanges().stream()).mapToInt(r -> r.end() - r.start()).sum();
        assertEquals(longText.length(), covered, "切片无交叠且覆盖计划区间");
    }

    @Test void qw_overBudgetDefersWithExplicitPartial() {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 80; i++) blocks.add(text("b" + i, i, "内容" + i));
        QwenTaskPlanner.PlannedReview plan = planner().planReview(blocks, "v1", "u3.1");
        assertFalse(plan.deferred().isEmpty(), "超预算保留剩余原文，不虚报全检");
        assertTrue(plan.chunks().size() + 2 <= 8, "结构 1 + 局部 N + 重试 1 在预算内");
        assertTrue(plan.estimatedCalls() <= 8);
    }

    @Test void qw_contextRangesAreReadOnlyAndNonOverlappingWrites() {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 20; i++) blocks.add(text("b" + i, i, "内容" + i + "填充"));
        QwenTaskPlanner.PlannedReview plan = planner().planReview(blocks, "v1", "u3.1");
        assertTrue(plan.chunks().size() >= 2);
        for (QwenTaskPlanner.ChunkTask chunk : plan.chunks()) {
            Set<String> ownedKeys = new HashSet<>();
            for (QwenTaskPlanner.OwnedRange owned : chunk.ownedRanges()) {
                ownedKeys.add(owned.sourceId() + ":" + owned.start() + ":" + owned.end());
            }
            for (QwenTaskPlanner.ContextRange ctx : chunk.contextRanges()) {
                assertFalse(ownedKeys.contains(ctx.sourceId() + ":" + ctx.start() + ":" + ctx.end()),
                        "上下文与写入权区间不得重叠");
            }
        }
    }
}
