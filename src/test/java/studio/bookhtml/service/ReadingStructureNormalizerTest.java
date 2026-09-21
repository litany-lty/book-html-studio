package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.IntStream;

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

    @Test
    void displaysRepeatedShortCellOcrAsCropWithoutChangingTranscriptOrIssue() {
        String repeated = "生日\n五行局\n" + "未午\n未申\n未酉\n未卯\n".repeat(90);
        ContentIssue issue = new ContentIssue("cell-issue", "suspected", 0, 2, 0, 2,
                "首格待核对", false, null, null);
        Block source = block("dense", repeated, new double[]{.65, .08, .23, .84}, List.of(issue));
        Page page = page(List.of(source), false);

        Page reading = ReadingStructureNormalizer.normalize(page);

        assertEquals("figure", reading.blocks().get(0).type());
        assertSame(source.original(), reading.blocks().get(0).original());
        assertSame(source.issues(), reading.blocks().get(0).issues());
        assertSame(source.sourceIds(), reading.blocks().get(0).sourceIds());
        assertArrayEquals(source.bbox(), reading.blocks().get(0).bbox());
        assertSame(page.sourceRecords(), reading.sourceRecords());
        assertEquals("text", page.blocks().get(0).type());
    }

    @Test
    void displaysFlattenedFourColumnRecordsAsCropButNotShortListsOrNormalVerticalText() {
        String chart = "甲先生造\n甲子\n乙丑\n丙寅\n丁卯\n"
                + "乙先生造\n戊辰\n己巳\n庚午\n辛未\n"
                + "丙先生造\n壬申\n癸酉\n甲戌\n乙亥";
        Block matrix = block("matrix", chart, new double[]{.13, .28, .6, .13}, List.of());
        Block twoRecords = block("list", chart.substring(0, chart.indexOf("丙先生造")),
                new double[]{.13, .28, .6, .13}, List.of());
        String distinct = IntStream.range(0, 100).mapToObj(i -> "第" + i).reduce((a, b) -> a + "\n" + b).orElseThrow();
        Block vertical = block("vertical", distinct, new double[]{.65, .08, .23, .84}, List.of());
        Block prose = block("prose", "这是一段正常的长篇竖排文字，不应因为文字数量很多就改为图片。".repeat(10),
                new double[]{.65, .08, .23, .84}, List.of());
        Page source = page(List.of(matrix, twoRecords, vertical, prose), false);

        Page reading = ReadingStructureNormalizer.normalize(source);

        assertEquals(List.of("figure", "text", "text", "text"), reading.blocks().stream().map(Block::type).toList());
        assertSame(matrix.original(), reading.blocks().get(0).original());
        assertSame(source.sourceRecords(), reading.sourceRecords());
        Page reviewedPage = page(List.of(matrix), true);
        assertSame(reviewedPage, ReadingStructureNormalizer.normalize(reviewedPage));
        Block reviewed = new Block(matrix.id(), matrix.type(), matrix.order(), matrix.bbox(), matrix.writingMode(),
                matrix.original(), matrix.simplified(), matrix.confidence(), matrix.uncertain(),
                true, matrix.headingLevel(), matrix.source(), matrix.sourceIds(), matrix.suggestion(),
                matrix.sourceRect(), matrix.issues());
        Block manual = new Block(matrix.id(), matrix.type(), matrix.order(), matrix.bbox(), matrix.writingMode(),
                matrix.original(), matrix.simplified(), matrix.confidence(), matrix.uncertain(),
                false, matrix.headingLevel(), "manual", matrix.sourceIds(), matrix.suggestion(),
                matrix.sourceRect(), matrix.issues());
        assertSame(reviewed, ReadingStructureNormalizer.normalize(page(List.of(reviewed), false)).blocks().get(0));
        assertSame(manual, ReadingStructureNormalizer.normalize(page(List.of(manual), false)).blocks().get(0));
    }

    @Test
    void regroupsOnlyVerifiedVerticalSpreadKeepingEachHalfAndSourceEvidence() {
        List<Block> blocks = spreadBlocks();
        Page source = spreadPage(blocks, false);

        Page reading = ReadingStructureNormalizer.normalize(source);

        assertEquals(List.of("r-head", "r-1", "r-2", "r-3", "r-number", "r-last", "l-head",
                        "l-1", "l-2", "l-3", "l-number"),
                reading.blocks().stream().map(Block::id).toList());
        assertEquals(IntStream.range(0, blocks.size()).boxed().toList(),
                reading.blocks().stream().map(Block::order).toList());
        assertEquals(List.of("r-head", "r-1", "r-2", "r-3", "r-number", "l-head", "r-last"),
                source.blocks().stream().limit(7).map(Block::id).toList(), "原始页序不能回写");
        assertSame(source.sourceRecords(), reading.sourceRecords());
        Block original = blocks.get(6), moved = reading.blocks().get(5);
        assertSame(original.original(), moved.original());
        assertSame(original.bbox(), moved.bbox());
        assertSame(original.sourceIds(), moved.sourceIds());
        assertSame(original.issues(), moved.issues());
    }

    @Test
    void refusesSpreadInferenceForConflictsCrossGutterHorizontalTextOrManualWork() {
        List<Block> blocks = spreadBlocks();
        Page reviewed = spreadPage(blocks, true);
        assertSame(reviewed, ReadingStructureNormalizer.normalize(reviewed));

        List<Block> wrongNumber = new ArrayList<>(blocks);
        wrongNumber.set(10, copy(blocks.get(10), "117", null, null, false, null));
        Page conflicting = spreadPage(wrongNumber, false);
        assertSame(conflicting, ReadingStructureNormalizer.normalize(conflicting));

        List<Block> crossing = new ArrayList<>(blocks);
        crossing.set(6, copy(blocks.get(6), null, new double[]{.47, .1, .1, .7}, null, false, null));
        Page spanning = spreadPage(crossing, false);
        assertSame(spanning, ReadingStructureNormalizer.normalize(spanning));

        List<Block> horizontal = blocks.stream().map(b -> copy(b, null, null, "horizontal-tb", false, null)).toList();
        Page horizontalPage = spreadPage(horizontal, false);
        assertSame(horizontalPage, ReadingStructureNormalizer.normalize(horizontalPage));

        List<Block> manual = new ArrayList<>(blocks);
        manual.set(6, copy(blocks.get(6), null, null, null, false, "manual"));
        Page manualPage = spreadPage(manual, false);
        assertSame(manualPage, ReadingStructureNormalizer.normalize(manualPage));
    }

    private static List<Block> spreadBlocks() {
        return List.of(
                spreadBlock("r-head", "heading", 0, .70, "horizontal-tb", "右页眉"),
                spreadBlock("r-1", "text", 1, .85, "vertical-rl", "右一"),
                spreadBlock("r-2", "text", 2, .75, "vertical-rl", "右二"),
                spreadBlock("r-3", "text", 3, .65, "vertical-rl", "右三"),
                spreadBlock("r-number", "page-number", 4, .72, "horizontal-tb", "114"),
                spreadBlock("l-head", "heading", 5, .25, "horizontal-tb", "左页眉"),
                spreadBlock("r-last", "text", 6, .56, "vertical-rl", "右末列"),
                spreadBlock("l-1", "text", 7, .40, "vertical-rl", "左一"),
                spreadBlock("l-2", "text", 8, .30, "vertical-rl", "左二"),
                spreadBlock("l-3", "text", 9, .20, "vertical-rl", "左三"),
                spreadBlock("l-number", "page-number", 10, .26, "horizontal-tb", "115"));
    }

    private static Block spreadBlock(String id, String type, int order, double x, String mode, String text) {
        double height = "text".equals(type) ? .7 : .03;
        return new Block(id, type, order, new double[]{x, .1, .03, height}, mode,
                text, text, .8, true, false, null, "paddle", List.of(id), null, null, List.of());
    }

    private static Block copy(Block b, String text, double[] bbox, String mode, boolean reviewed, String source) {
        return new Block(b.id(), b.type(), b.order(), bbox == null ? b.bbox() : bbox,
                mode == null ? b.writingMode() : mode, text == null ? b.original() : text,
                text == null ? b.simplified() : text, b.confidence(), b.uncertain(), reviewed,
                b.headingLevel(), source == null ? b.source() : source, b.sourceIds(), b.suggestion(),
                b.sourceRect(), b.issues());
    }

    private static Page spreadPage(List<Block> blocks, boolean reviewed) {
        return new Page(61, 1024, 782, "READY", "paddle", blocks, List.of(), reviewed,
                null, blocks, 3);
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
