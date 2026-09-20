package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReadingStructureNormalizerTest {
    private static final double[] BOX = {.1, .2, .35, .25};

    @Test
    void promotesRepeatedArrowAndVerticalConnectorStructuresWithoutChangingEvidence() {
        ContentIssue issue = new ContentIssue("issue-1", "suspected", 17, 75, 17, 75,
                "箭头需核对", false, null, "↓↓↑↑↓↑");
        Block arrows = block("arrows", "◎地支六冲：\n子丑寅卯辰巳\n相  $ \\downarrow\\downarrow\\uparrow\\uparrow\\downarrow\\uparrow $ 相冲\n冲 午未申酉戌亥", BOX, List.of(issue));
        Block lines = block("lines", "天干五虎遁：\n甲乙丙丁戊\n己庚辛壬癸\n之|||||||\n年|||||||\n起\n丙戊庚壬甲\n寅寅寅寅", new double[]{.5, .2, .3, .5}, List.of());
        Page source = page(List.of(arrows, lines), false);

        Page normalized = ReadingStructureNormalizer.normalize(source);

        assertEquals("figure", normalized.blocks().get(0).type());
        assertEquals("figure", normalized.blocks().get(1).type());
        assertEquals("text", source.blocks().get(0).type(), "展示归一化不能回写源页");
        assertEquals(arrows.id(), normalized.blocks().get(0).id());
        assertEquals(arrows.original(), normalized.blocks().get(0).original());
        assertEquals(arrows.sourceIds(), normalized.blocks().get(0).sourceIds());
        assertEquals(arrows.issues(), normalized.blocks().get(0).issues());
        assertArrayEquals(arrows.bbox(), normalized.blocks().get(0).bbox());
        assertSame(source.sourceRecords(), normalized.sourceRecords());
    }

    @Test
    void keepsProseNumberedListsShortArrowFlowsAndOrdinaryPipesAsText() {
        List<Block> blocks = List.of(
                block("prose", "这是普通散文的第一行\n只是扫描换行后的第二行", BOX, List.of()),
                block("list", "一、第一项\n二、第二项\n三、第三项", BOX, List.of()),
                block("arrow", "流程说明：\n输入 → 输出\n结束", BOX, List.of()),
                block("two-arrows", "输入 \\rightarrow 中间\n说明 \\rightarrow 输出\n结束", BOX, List.of()),
                block("pipes", "字段说明：\nname | value\nleft || right", BOX, List.of()));

        Page normalized = ReadingStructureNormalizer.normalize(page(blocks, false));

        assertEquals(List.of("text", "text", "text", "text", "text"),
                normalized.blocks().stream().map(Block::type).toList());
    }

    @Test
    void leavesReviewedManualAndInvalidCropBlocksUntouched() {
        Block reviewed = new Block("reviewed", "text", 0, BOX, "horizontal-tb",
                arrowDiagram(), arrowDiagram(), .8, true, true, null, "paddle", List.of("reviewed"), null, null);
        Block manual = new Block("manual", "text", 1, BOX, "horizontal-tb",
                arrowDiagram(), arrowDiagram(), .8, true, false, null, "manual", List.of("manual"), null, null);
        Block invalid = block("invalid", arrowDiagram(), new double[]{.9, .2, .3, .2}, List.of());
        Page source = page(List.of(reviewed, manual, invalid), false);

        assertSame(source, ReadingStructureNormalizer.normalize(source));
        Page reviewedPage = page(List.of(block("page-reviewed", arrowDiagram(), BOX, List.of())), true);
        assertSame(reviewedPage, ReadingStructureNormalizer.normalize(reviewedPage));
    }

    private static String arrowDiagram() {
        return "甲乙丙丁\n相 \\uparrow\\uparrow\\uparrow\\uparrow\n冲 戊己庚辛";
    }

    private static Block block(String id, String text, double[] bbox, List<ContentIssue> issues) {
        return new Block(id, "text", 0, bbox, "horizontal-tb", text, text, .8, true, false,
                null, "paddle", List.of(id), "保留建议", new double[]{10, 20, 30, 40}, issues);
    }

    private static Page page(List<Block> blocks, boolean reviewed) {
        return new Page(22, 600, 800, "READY", "paddle", blocks, List.of("待校对"), reviewed,
                null, blocks, 3);
    }
}
