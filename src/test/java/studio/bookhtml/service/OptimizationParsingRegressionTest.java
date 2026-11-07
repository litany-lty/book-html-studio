package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.decision.DecisionModels;
import studio.bookhtml.config.QwenAssistProperties;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationParsingRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void baiduUsesPhysicalAspectRatioForHorizontalText() throws Exception {
        var root = mapper.readTree("""
                {"pages":[{"meta":{"page_width":1000,"page_height":100},"layouts":[
                  {"type":"text","text":"示例文字","position":[10,10,200,40]}
                ]}]}
                """);
        assertEquals("horizontal-tb", new BaiduPpOcrParser().parse(root, 1000, 100, "auto").get(0).writingMode());
    }

    @Test
    void baiduUsesPhysicalAspectRatioForVerticalText() throws Exception {
        var root = mapper.readTree("""
                {"pages":[{"meta":{"page_width":100,"page_height":1000},"layouts":[
                  {"type":"text","text":"示例文字","position":[10,10,20,100]}
                ]}]}
                """);
        assertEquals("vertical-rl", new BaiduPpOcrParser().parse(root, 100, 1000, "auto").get(0).writingMode());
    }

    @Test
    void paddleUsesPhysicalAspectRatioForHorizontalText() throws Exception {
        var root = mapper.readTree("""
                {"pages":[{"meta":{"page_width":1000,"page_height":100},"layouts":[
                  {"type":"text","text":"示例文字","position":[10,10,200,40]}
                ]}]}
                """);
        assertEquals("horizontal-tb", new PaddleOcrParser().parse(root, 1000, 100, "auto").get(0).writingMode());
    }

    @Test
    void paddleUsesPhysicalAspectRatioForVerticalText() throws Exception {
        var root = mapper.readTree("""
                {"pages":[{"meta":{"page_width":100,"page_height":1000},"layouts":[
                  {"type":"text","text":"示例文字","position":[10,10,20,100]}
                ]}]}
                """);
        assertEquals("vertical-rl", new PaddleOcrParser().parse(root, 100, 1000, "auto").get(0).writingMode());
    }

    @Test
    void assistedReviewSkipsNullCandidatesBeforeSorting() {
        var block = candidate();
        assertEquals(List.of(block), AssistedReviewService.selectCandidates(Arrays.asList(null, block), 3));
    }

    @Test
    void assistedReviewHonorsZeroLimit() {
        assertTrue(AssistedReviewService.selectCandidates(List.of(candidate()), 0).isEmpty());
    }

    @Test
    void malformedGeometryCannotBecomeRunningHeaderEvidence() {
        for (double[] bbox : List.of(new double[]{.1,.01,.2,Double.NaN},
                new double[]{Double.POSITIVE_INFINITY,.01,.2,.05}, new double[]{.1,-.1,.2,.05})) {
            var block = new Block("header", "heading", 0, bbox, "horizontal-tb",
                    "书名", "书名", .9, false, false, 1, "paddle", List.of("header"), null, null);
            assertNull(BookPresentationService.edgeZoneOf(block));
        }
    }

    @Test
    void verticalNormalizationPreservesUnresolvedIssueAndSimplifiedOffsets() {
        var issue = new ContentIssue("i", "suspected", 0, 1, 0, 1, "字迹不清", false, null, "候选");
        var source = vertical("R-1", 0, .8, false, List.of(issue));
        var result = new VerticalLayoutNormalizer().normalize(List.of(source), List.of(source));
        assertEquals(source.issues(), result.get(0).issues());
        assertEquals(source.original(), result.get(0).original());
        assertEquals(source.simplified(), result.get(0).simplified());
    }

    @Test
    void verticalNormalizationDoesNotMergeAwayHumanReview() {
        var reviewed = vertical("R-1", 0, .8, true, List.of());
        var neighbor = vertical("R-2", 1, .77, false, List.of());
        var result = new VerticalLayoutNormalizer().normalize(List.of(reviewed, neighbor), List.of(reviewed, neighbor));
        assertEquals(2, result.size());
        assertTrue(result.get(0).reviewed());
        assertEquals(reviewed.simplified(), result.get(0).simplified());
    }

    @Test
    void originalHeadingUsesOriginalScriptConfirmation() {
        var resolution = new DecisionModels.ReviewResolution("res", "op", DecisionModels.Origin.MANUAL,
                null, null, null, "a".repeat(64), 0, "b".repeat(64), "學", "学", "version", true, Instant.now(), 1);
        var issue = new ContentIssue("i", "suspected", 0, 1, 0, 1, "人工校正", true, "学", null, resolution);
        var block = new Block("heading", "heading", 0, new double[]{.1,.2,.2,.1}, "horizontal-tb",
                "错", "错", .9, false, true, 1, "manual", List.of("heading"), null, null, List.of(issue));
        assertEquals("學", HeadingText.display(block, false));
        assertEquals("學", HeadingText.displayConfirmed(block));
        assertEquals("学", HeadingText.display(block, true));
    }

    @Test
    void contextEvidenceRejectsMalformedOriginalUnicode() {
        var block = new Block("b", "text", 0, null, "horizontal-tb", "甲\uD800乙", "甲", null,
                false, false, null, "paddle", List.of("b"), null, null);
        assertTrue(EvidenceTextResolver.resolve(block).isEmpty());
    }

    @Test
    void contextEvidenceBudgetIncludesTrailingOriginalText() {
        var issue = new ContentIssue("i", "suspected", 0, 1, 0, 1, "校正", true, "乙".repeat(1000), null);
        var block = new Block("b", "text", 0, null, "horizontal-tb", "甲".repeat(256 * 1024), "甲", null,
                false, true, null, "manual", List.of("b"), null, null, List.of(issue));
        assertTrue(EvidenceTextResolver.resolve(block).isEmpty());
    }

    @Test
    void reviewPlannerDoesNotMergeShortTailBeyondBlockBudget() {
        var config = new QwenAssistProperties();
        config.setReviewChunkBlocksMax(12);
        config.setReviewChunkBlocksMin(6);
        var blocks = java.util.stream.IntStream.range(0, 13).mapToObj(i -> planningBlock(i, "正文")).toList();
        var plan = new QwenTaskPlanner(config).planReview(blocks, "v1", "policy");
        assertEquals(13, plan.chunks().stream().mapToInt(chunk -> chunk.ownedRanges().size()).sum());
        assertTrue(plan.chunks().stream().allMatch(chunk -> chunk.ownedRanges().size() <= 12));
    }

    @Test
    void reviewPlannerDoesNotMergeShortTailBeyondCharacterBudget() {
        var config = new QwenAssistProperties();
        config.setReviewChunkChars(2500);
        var plan = new QwenTaskPlanner(config).planReview(
                List.of(planningBlock(0, "甲".repeat(1400)), planningBlock(1, "乙".repeat(1400))), "v1", "policy");
        assertEquals(2800, plan.chunks().stream().flatMap(chunk -> chunk.ownedRanges().stream())
                .mapToInt(range -> range.end() - range.start()).sum());
        assertTrue(plan.chunks().stream().allMatch(chunk -> chunk.ownedRanges().stream()
                .mapToInt(range -> range.end() - range.start()).sum() <= 2500));
    }

    @Test
    void reviewPlannerSkipsNullBlocksBeforeSorting() {
        var plan = new QwenTaskPlanner(new QwenAssistProperties()).planReview(
                Arrays.asList(null, planningBlock(0, "正文")), "v1", "policy");
        assertEquals(1, plan.chunks().size());
        assertEquals("planning-0", plan.chunks().get(0).ownedRanges().get(0).sourceId());
    }

    private static Block planningBlock(int order, String original) {
        String id = "planning-" + order;
        return new Block(id, "text", order, new double[]{.1,.1,.2,.1}, "horizontal-tb", original, original,
                .9, false, false, null, "paddle", List.of(id), null, null);
    }

    private static Block vertical(String id, int order, double x, boolean reviewed, List<ContentIssue> issues) {
        return new Block(id, "text", order, new double[]{x,.2,.02,.5}, "vertical-rl", "漢字", "汉字",
                .9, true, reviewed, null, "qwen", List.of(id), null, null, issues);
    }

    private static Block candidate() {
        return new Block("candidate", "text", 0, new double[]{.1,.1,.2,.1}, "horizontal-tb",
                "文字", "文字", .5, true, false, null, "ppocr", List.of("candidate"), "待复核", null);
    }
}
