package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IssueImageServiceTest {
    @TempDir Path temp;

    @Test
    void createsFixedCellGlyphAtlasFromPaddleSpanAndMatchesTraditionalToSimplified() throws Exception {
        assertEquals("宫", new TraditionalConverter().toSimplified("宮"));
        Fixture fixture = fixture("""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[{
                  "layout_id":"layout-1","type":"vertical_text","text":"命宮在西","position":[100,20,40,300],
                  "span_boxes":[{"text":"命宫在西","location":[[100,20],[140,20],[140,320],[100,320]]}]
                }]}]}}
                """);
        ContentIssue issue = issue("glyph", 3, 4, false);
        Page page = page(block("paddle-layout-1", "命宮在西", "paddle", new double[]{100,20,40,300}, List.of(issue)));

        IssueImageService.Snippet snippet = fixture.service().locate("book", page).get("glyph");

        assertEquals("glyphs", snippet.mode());
        assertEquals(1, snippet.glyphCount());
        assertEquals(1, snippet.boxes().size());
        assertTrue(snippet.contextBbox()[3] > snippet.bbox()[3] * 3,"字图应同时保留整列原稿上下文");
        BufferedImage atlas = ImageIO.read(new ByteArrayInputStream(snippet.png()));
        assertEquals(64, atlas.getWidth());
        assertEquals(72, atlas.getHeight());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(snippet.contextPng())));
    }

    @Test
    void validatesOnlyTargetNeighborhoodInsteadOfRejectingForAnUnrelatedBlankCell() throws Exception {
        Fixture fixture = fixture("""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[{
                  "layout_id":"layout-1","type":"vertical_text","text":"甲乙丙丁戊","position":[100,20,40,300],
                  "span_boxes":[{"text":"甲乙丙丁戊","location":[[100,20],[140,20],[140,320],[100,320]]}]
                }]}]}}
                """, firstGlyphOnlyImage());
        Page page = page(block("paddle-layout-1", "甲乙丙丁戊", "paddle", new double[]{100,20,40,300},
                List.of(issue("first",0,1,false))));

        IssueImageService.Snippet snippet = fixture.service().locate("book",page).get("first");

        assertEquals("glyphs",snippet.mode(),"非目标位置缺墨不应否决已具备局部边界和墨迹证据的目标字");
        assertEquals(1,snippet.boxes().size());
    }

    @Test
    void usesGeometricallyCredibleSingleColumnLayoutWhenSpanBoxesAreAbsent() throws Exception {
        Fixture fixture = fixture("""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[{
                  "layout_id":"layout-1","type":"vertical_text","text":"命宮在西","position":[100,20,40,300]
                }]}]}}
                """);
        Page page = page(block("paddle-layout-1", "命宮在西", "paddle", new double[]{100,20,40,300},
                List.of(issue("single-column",3,4,false))));

        IssueImageService.Snippet snippet = fixture.service().locate("book",page).get("single-column");

        assertEquals("glyphs",snippet.mode());
        assertEquals(1,snippet.glyphCount());
    }

    @Test
    void narrowsBroadTraditionalIssueToTheSingleDifferingGlyphWithoutMutatingIssue() throws Exception {
        Fixture fixture = fixture("""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[{
                  "layout_id":"layout-1","type":"vertical_text","text":"命宮在西","position":[100,20,40,240],
                  "span_boxes":[{"text":"命宫在西","location":[[100,20],[140,20],[140,260],[100,260]]}]
                }]}]}}
                """);
        ContentIssue broad = new ContentIssue("broad","suspected",0,4,0,4,"图像依据",false,null,"命宫在酉");
        Page page = page(block("paddle-layout-1","命宮在西","paddle",new double[]{100,20,40,240},List.of(broad)));

        IssueImageService.Snippet snippet = fixture.service().locate("book",page).get("broad");

        assertEquals("glyphs",snippet.mode());
        assertEquals(1,snippet.glyphCount(),"聚焦证据只应展示真正不同的“西/酉”一字");
        assertEquals(0,broad.start());
        assertEquals(4,broad.end(),"持久化 issue 范围不得被局部聚焦修改");
        assertTrue(snippet.contextBbox()[3] > snippet.bbox()[3] * 2);
    }

    @Test
    void includesResolvedIssuesAndFallsBackToHonestSourceRegionWhenSpanIsMissing() throws Exception {
        Fixture fixture = fixture("""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[{
                  "layout_id":"layout-1","type":"vertical_text","text":"甲乙丙丁","position":[80,50,160,240]
                }]}]}}
                """);
        ContentIssue resolved = issue("resolved", 1, 2, true);
        Page page = page(block("paddle-layout-1", "甲乙丙丁", "paddle", new double[]{80,50,160,240}, List.of(resolved)));

        IssueImageService.Snippet snippet = fixture.service().locate("book", page).get("resolved");

        assertNotNull(snippet);
        assertEquals("region", snippet.mode());
        assertEquals(1, snippet.glyphCount());
        assertTrue(snippet.boxes().isEmpty());
        assertArrayEquals(snippet.bbox(),snippet.contextBbox());
        assertArrayEquals(snippet.png(),snippet.contextPng());
        assertTrue(snippet.bbox()[2] < .5, "区域降级应保留真实布局框，而不是整页或虚构字框");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(snippet.png())));
    }

    @Test
    void rejectsDifferentCachedTextAndDoesNotMapOldOffsetsAfterManualTextChange() throws Exception {
        Fixture fixture = fixture("""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[{
                  "layout_id":"layout-1","type":"vertical_text","text":"甲乙","position":[40,40,50,200],
                  "span_boxes":[{"text":"甲乙","location":[[40,40],[90,40],[90,240],[40,240]]}]
                }]},{"meta":{"page_width":400,"page_height":400},"layouts":[{
                  "layout_id":"layout-2","type":"vertical_text","text":"不相同","position":[40,40,50,200],
                  "span_boxes":[{"text":"不相同","location":[[40,40],[90,40],[90,240],[40,240]]}]
                }]},{"meta":{"page_width":100,"page_height":400},"layouts":[{
                  "layout_id":"layout-3","type":"vertical_text","text":"甲乙","position":[40,40,50,200],
                  "span_boxes":[{"text":"甲乙","location":[[40,40],[90,40],[90,240],[40,240]]}]
                }]}]}}
                """);
        Block current = new Block("paddle-layout-1","text",0,new double[]{.6,.2,.2,.4},"vertical-rl",
                "改乙","改乙",.8,true,false,null,"manual",List.of("paddle-layout-1"),null,new double[]{40,40,50,200},List.of(issue("changed",0,1,false)));
        Block oldSource = new Block("paddle-layout-1","text",0,current.bbox(),current.writingMode(),"甲乙","甲乙",
                .8,true,false,null,"paddle",current.sourceIds(),null,current.sourceRect());
        Page changed = new Page(1,400,400,"READY","manual",List.of(current),List.of(),false,null,List.of(oldSource));

        IssueImageService.Snippet changedSnippet = fixture.service().locate("book",changed).get("changed");
        assertEquals("region",changedSnippet.mode());
        assertTrue(changedSnippet.bbox()[0] > .55,"手改原文后必须退回当前块真实区域，不能沿用旧来源偏移");

        Block different = new Block("paddle-layout-2","text",0,new double[]{.6,.2,.2,.4},"vertical-rl",
                "甲乙","甲乙",.8,true,false,null,"paddle",List.of("paddle-layout-2"),null,new double[]{40,40,50,200},List.of(issue("different",0,1,false)));
        IssueImageService.Snippet differentSnippet = fixture.service().locate("book",page(different)).get("different");
        assertTrue(differentSnippet.bbox()[0] > .55,"非同文缓存候选必须拒绝，不能跨书误配");

        Block wrongRatio = new Block("paddle-layout-3","text",0,new double[]{.6,.2,.2,.4},"vertical-rl",
                "甲乙","甲乙",.8,true,false,null,"paddle",List.of("paddle-layout-3"),null,new double[]{40,40,50,200},List.of(issue("ratio",0,1,false)));
        IssueImageService.Snippet ratioSnippet = fixture.service().locate("book",page(wrongRatio)).get("ratio");
        assertTrue(ratioSnippet.bbox()[0] > .55,"缓存页宽高比不兼容时必须拒绝跨页候选");
    }

    @Test
    void ambiguousSameTextLayoutsWithDifferentSpansFallBackToBlockRegion() throws Exception {
        Fixture fixture = fixture("""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[
                  {"layout_id":"layout-1","type":"vertical_text","text":"甲乙","position":[40,40,50,200],"span_boxes":[{"text":"甲乙","location":[[40,40],[90,40],[90,240],[40,240]]}]},
                  {"layout_id":"layout-1","type":"vertical_text","text":"甲乙","position":[40,40,50,200],"span_boxes":[{"text":"甲乙","location":[[140,40],[190,40],[190,240],[140,240]]}]}
                ]}]}}
                """);
        Block block = new Block("paddle-layout-1","text",0,new double[]{.6,.2,.2,.4},"vertical-rl",
                "甲乙","甲乙",.8,true,false,null,"paddle",List.of("paddle-layout-1"),null,new double[]{40,40,50,200},List.of(issue("ambiguous",0,1,false)));

        IssueImageService.Snippet snippet = fixture.service().locate("book",page(block)).get("ambiguous");
        assertEquals("region",snippet.mode());
        assertTrue(snippet.bbox()[0] > .55,"相同文本和布局框但字框不同应判歧义并诚实退回块区域");
    }

    @Test
    void unrelatedCacheChangeDoesNotInvalidatePageSnippet() throws Exception {
        Fixture fixture = fixture("""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[]}]}}
                """);
        Page page = page(block("paddle-layout-1", "甲乙", "paddle", new double[]{80,50,60,240}, List.of(issue("i",0,1,false))));

        fixture.service().locate("book", page);
        // 阶段2：他书缓存的无关变化（空白差异）不再使本页失效，不重复高清渲染
        Files.writeString(fixture.cacheFile(), Files.readString(fixture.cacheFile()) + "\n");
        fixture.service().locate("book", page);

        verify(fixture.pdf(), times(1)).renderForOcr(fixture.pdfPath(), 1);
    }

    @Test
    void relevantLayoutChangeRecomputesPageSnippet() throws Exception {
        Fixture fixture = fixture("""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[]}]}}
                """);
        Page page = page(block("paddle-layout-1", "甲乙", "paddle", new double[]{80,50,60,240}, List.of(issue("i",0,1,false))));
        assertEquals("region", fixture.service().locate("book", page).get("i").mode());

        // 本页相关布局出现后重新计算，升级为精确字框
        Files.writeString(fixture.cacheFile(), """
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[{
                  "layout_id":"layout-1","type":"vertical_text","text":"甲乙","position":[80,50,60,240],
                  "span_boxes":[{"text":"甲乙","location":[[80,50],[140,50],[140,290],[80,290]]}]
                }]}]}}
                """);
        assertEquals("glyphs", fixture.service().locate("book", page).get("i").mode());
        verify(fixture.pdf(), times(2)).renderForOcr(fixture.pdfPath(), 1);
    }

    @Test
    void readsAiStudioCacheWithoutMixingLegacyLayoutIds() throws Exception {
        Fixture fixture = fixture("{\"result\":{\"pages\":[]}}");
        Page page = page(block("paddle-aistudio-layout-1", "甲乙丙丁戊", "paddle-aistudio",
                new double[]{100,20,40,300}, List.of(issue("ai",0,1,false))));
        assertEquals("region",fixture.service().locate("book",page).get("ai").mode());
        Path directory=temp.resolve("cache/paddle-aistudio");Files.createDirectories(directory);
        Files.writeString(directory.resolve("fixture.json"),"""
                {"result":{"pages":[{"meta":{"page_width":400,"page_height":400},"layouts":[{
                  "layout_id":"aistudio-layout-1","type":"vertical_text","text":"甲乙丙丁戊","position":[100,20,40,300],
                  "span_boxes":[{"text":"甲乙丙丁戊","location":[[100,20],[140,20],[140,320],[100,320]]}]
                }]}]}}
                """);
        assertEquals("glyphs",fixture.service().locate("book",page).get("ai").mode(),"新增通道缓存应使旧区域缓存失效并支持精确定位");
        Page legacy=page(block("paddle-layout-1","甲乙丙丁戊","paddle",new double[]{100,20,40,300},List.of(issue("old",0,1,false))));
        assertEquals("region",fixture.service().locate("book",legacy).get("old").mode(),"不得将另一通道布局冒充旧通道来源");
    }

    @Test
    void pdfFailureIsReportedAndNeverReplacedWithGuess() throws Exception {
        Path pdfPath = temp.resolve("broken.pdf"); Files.writeString(pdfPath, "pdf");
        BookService books = mock(BookService.class); PdfService pdf = mock(PdfService.class);
        when(books.pdfPath("book")).thenReturn(pdfPath);
        when(pdf.renderForOcr(pdfPath, 1)).thenThrow(new IOException("path details must not escape"));
        IssueImageService service = new IssueImageService(books,pdf,TestConfigs.config(temp,"",""),new ObjectMapper(),new TraditionalConverter());
        Page page = page(block("paddle-layout-1", "甲", "paddle", new double[]{0,0,1,1}, List.of(issue("i",0,1,false))));

        ApiException error = assertThrows(ApiException.class, () -> service.locate("book",page));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.status());
        assertEquals("疑点原图生成失败", error.getMessage());
    }

    private Fixture fixture(String cacheJson) throws Exception {
        return fixture(cacheJson,image());
    }

    private Fixture fixture(String cacheJson,BufferedImage source) throws Exception {
        Path pdfPath = temp.resolve("source.pdf"); Files.writeString(pdfPath, "pdf");
        Path cacheDir = temp.resolve("cache/paddle"); Files.createDirectories(cacheDir);
        Path cacheFile = cacheDir.resolve("fixture.json"); Files.writeString(cacheFile, cacheJson);
        BookService books = mock(BookService.class); PdfService pdf = mock(PdfService.class);
        when(books.pdfPath("book")).thenReturn(pdfPath);
        when(pdf.renderForOcr(pdfPath,1)).thenAnswer(ignored -> copy(source));
        IssueImageService service = new IssueImageService(books,pdf,TestConfigs.config(temp,"",""),new ObjectMapper(),new TraditionalConverter());
        return new Fixture(service,pdf,pdfPath,cacheFile);
    }

    private static BufferedImage image() {
        BufferedImage image = new BufferedImage(400,400,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0,0,400,400);
            graphics.setColor(Color.BLACK);
            for (int i=0;i<5;i++) graphics.fillRect(108,32+i*60,24,34);
        } finally { graphics.dispose(); }
        return image;
    }

    private static BufferedImage firstGlyphOnlyImage() {
        BufferedImage image = new BufferedImage(400,400,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0,0,400,400);
            graphics.setColor(Color.BLACK); graphics.fillRect(108,32,24,34);
        } finally { graphics.dispose(); }
        return image;
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=copy.createGraphics();try{graphics.drawImage(source,0,0,null);}finally{graphics.dispose();}return copy;
    }

    private static ContentIssue issue(String id,int start,int end,boolean resolved) {
        return new ContentIssue(id,"suspected",start,end,start,end,"图像依据",resolved,resolved?"字":null,"候");
    }

    private static Block block(String id,String text,String source,double[] sourceRect,List<ContentIssue> issues) {
        return new Block(id,"text",0,new double[]{.2,.125,.15,.6},"vertical-rl",text,text,.8,true,false,null,source,List.of(id),null,sourceRect,issues);
    }

    private static Page page(Block block) {
        Block source = new Block(block.id(),block.type(),block.order(),block.bbox(),block.writingMode(),block.original(),block.simplified(),block.confidence(),true,false,null,block.source(),block.sourceIds(),null,block.sourceRect());
        return new Page(1,400,400,"READY","paddle",List.of(block),List.of(),false,null,List.of(source));
    }

    private record Fixture(IssueImageService service,PdfService pdf,Path pdfPath,Path cacheFile) {}
}
