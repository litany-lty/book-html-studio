package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AdvertisementFilterTest {
    @TempDir Path temp;
    private final TraditionalConverter converter = new TraditionalConverter();

    @Test void marksOnlyHighConfidenceIndependentMarginBlockAndPreservesEvidence() {
        Block ad = block("ad", "heading", "更多低價資料 微 信：de mo user123",
                new double[]{.15, .005, .7, .035}, false, "paddle", List.of());
        AdvertisementFilter.Result result = AdvertisementFilter.classify(List.of(ad), false, converter);
        assertEquals(1, result.marked());
        Block marked = result.blocks().get(0);
        assertEquals("advertisement", marked.type());
        assertEquals(ad.id(), marked.id());
        assertEquals(ad.original(), marked.original());
        assertEquals(ad.simplified(), marked.simplified());
        assertSame(ad.bbox(), marked.bbox());
        assertSame(ad.sourceIds(), marked.sourceIds());
        assertSame(ad.issues(), marked.issues());
        assertEquals(ad.order(), marked.order());
        BlockValidator.validate(result.blocks());
        assertEquals(1, AdvertisementFilter.classify(List.of(block("bottom", "text", "购买资料加微信 abc123",
                new double[]{.2, .93, .5, .035}, false, "qwen", List.of())), false, converter).marked());
    }

    @Test void contactAlonePublisherInfoBodyDiscussionAndMixedBodyRemainVisible() {
        List<Block> benign = List.of(
                block("phone", "text", "微信 123456789", new double[]{.1, .01, .5, .03}, false, "ocr", List.of()),
                block("url", "text", "购买地址 https://publisher.example", new double[]{.1, .01, .5, .03}, false, "ocr", List.of()),
                block("publisher", "text", "出版社 版权所有 电话 123456789", new double[]{.1, .01, .5, .03}, false, "ocr", List.of()),
                block("middle", "text", "本章讨论购买资料加微信 abc123", new double[]{.1, .4, .7, .04}, false, "ocr", List.of()),
                block("mixed", "text", "购买资料加微信 abc123。这里是完整正文段落，不可整块隐藏。",
                        new double[]{.1, .04, .8, .04}, false, "ocr", List.of()),
                block("side", "text", "购买资料加微信 abc123", new double[]{.01, .2, .04, .5}, false, "ocr", List.of()));
        AdvertisementFilter.Result result = AdvertisementFilter.classify(benign, false, converter);
        assertEquals(0, result.marked());
        assertEquals(benign, result.blocks());
    }

    @Test void reviewedManualAndResolvedIssueRemainVisibleButUnresolvedIssueKeepsEvidenceOnAdvertisement() {
        String ad = "更多低价资料微信 abc123";
        double[] top = {.1, .01, .7, .03};
        ContentIssue issue = new ContentIssue("i", "suspected", 0, 1, 0, 1,
                "需核对", false, null, null);
        ContentIssue resolved = new ContentIssue("r", "suspected", 0, 1, 0, 1,
                "已核对", true, "更", null);
        List<Block> blocks = List.of(
                block("reviewed", "text", ad, top, true, "ocr", List.of()),
                block("manual", "text", ad, top, false, "manual", List.of()),
                block("issue", "text", ad, top, false, "ocr", List.of(issue)),
                block("resolved", "text", ad, top, false, "ocr", List.of(resolved)));
        AdvertisementFilter.Result result = AdvertisementFilter.classify(blocks, false, converter);
        assertEquals(1, result.marked());
        assertEquals(1, result.heldForReview());
        assertSame(blocks.get(0), result.blocks().get(0));
        assertSame(blocks.get(1), result.blocks().get(1));
        assertEquals("advertisement", result.blocks().get(2).type());
        assertSame(issue, result.blocks().get(2).issues().get(0));
        assertSame(blocks.get(3), result.blocks().get(3));
        assertEquals(0, AdvertisementFilter.classify(List.of(block("page", "text", ad, top, false, "ocr", List.of())),
                true, converter).marked());
    }

    @Test void onlineSearchPageStatsAndOutlineDoNotCountAdvertisement() throws Exception {
        var config = TestConfigs.config(temp, "", "");
        BookStore store = new BookStore(config, new ObjectMapper().findAndRegisterModules());
        String id = "66666666-6666-6666-6666-666666666666";
        try {
            store.createBookDirectory(id);
            store.writeBook(new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
            Block ad = AdvertisementFilter.classify(List.of(block("ad", "heading", "更多低价资料微信 abc123",
                    new double[]{.1, .01, .7, .03}, false, "ocr", List.of())), false, converter).blocks().get(0);
            Block body = block("body", "text", "正文內容", new double[]{.1, .2, .7, .1}, false, "ocr", List.of());
            Page page = new Page(1, 600, 800, "READY", "paddle", List.of(ad, body), List.of(), false, null,
                    List.of(block("ad", "heading", ad.original(), ad.bbox(), false, "ocr", List.of()), body));
            store.writePage(id, page, false);
            BookService service = new BookService(store, mock(PdfService.class), config);
            assertTrue(service.search(id, "abc123").isEmpty());
            assertEquals(1, service.search(id, "正文").size());
            assertEquals(1, service.pages(id).get(0).blockCount());
            assertEquals(1, service.pages(id).get(0).uncertainCount(), "仅统计正文疑点，不计广告疑点");
            assertTrue(service.outline(id).isEmpty());
            assertEquals(2, store.readPage(id, 1).sourceRecords().size(), "原始记录不被过滤");
        } finally {
            store.close();
        }
    }

    private static Block block(String id, String type, String text, double[] bbox,
                               boolean reviewed, String source, List<ContentIssue> issues) {
        return new Block(id, type, 0, bbox, "horizontal-tb", text, text,
                .9, true, reviewed, null, source, List.of(id), "原始建议", null, issues);
    }
}
