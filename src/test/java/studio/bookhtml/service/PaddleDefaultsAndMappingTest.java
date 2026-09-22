package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaddleDefaultsAndMappingTest {
    @Test void paddleIsDefaultProvider(){assertEquals("paddle-aistudio",new JobRequest("1-20",null,"auto",true,false,true).providerOrDefault());assertEquals("local",new JobRequest("1","local","auto",false,false,false).providerOrDefault());}
    @Test void halfPageMappingKeepsInternalOrderAndStableSource(){Block local=new Block("paddle-layout-7","text",3,new double[]{.2,.1,.4,.5},"vertical-rl","原文","原文",null,true,false,null,"paddle",List.of("paddle-layout-7"),null,new double[]{20,10,40,50});Block mapped=PaddleOcrPipeline.remap(local,"R",1000,1000,2000,0);assertEquals(0,mapped.order());assertArrayEquals(new double[]{.6,.1,.2,.5},mapped.bbox(),1e-9);assertEquals("R-paddle-layout-7",mapped.id());assertEquals(List.of("R-paddle-layout-7"),mapped.sourceIds());assertEquals("paddle:R",mapped.source());assertEquals("原文",mapped.original());}
    @Test void halfPageMappingPreservesSpanDerivedProvenance(){Block local=new Block("paddle-toc","text",0,new double[]{0,0,1,1},"vertical-rl","目录 一","目录 一",null,true,false,null,"paddle-span",List.of("paddle-toc"),null,null);Block mapped=PaddleOcrPipeline.remap(local,"L",0,1000,2000,0);assertEquals("paddle-span:L",mapped.source());assertEquals("L-paddle-toc",mapped.id());}
    @Test void pageProcessorKeepsSelectedAiStudioProvenanceAndDoesNotInvokeOtherProviders() throws Exception {
        BookStore store=mock(BookStore.class);PdfService pdf=mock(PdfService.class);
        NativeTextExtractor nativeText=mock(NativeTextExtractor.class);TesseractService local=mock(TesseractService.class);
        CloudOcrPipeline qwen=mock(CloudOcrPipeline.class);PaddleOcrPipeline paddle=mock(PaddleOcrPipeline.class);
        MiniMaxVisionClient mini=mock(MiniMaxVisionClient.class);QwenLayoutClient assist=mock(QwenLayoutClient.class);
        QwenTocRecoveryService toc=mock(QwenTocRecoveryService.class);
        PageProcessor processor=new PageProcessor(store,pdf,nativeText,local,qwen,paddle,mini,assist,toc,
                mock(VerticalLayoutNormalizer.class),mock(AssistedReviewService.class),new TraditionalConverter());
        Path file=Path.of("test-only.pdf");BufferedImage image=new BufferedImage(100,200,BufferedImage.TYPE_INT_RGB);
        when(store.pdf("book")).thenReturn(file);
        when(store.readPage("book",1)).thenReturn(new Page(1,100,200,"PENDING","",List.of(),List.of(),false,null,List.of()));
        when(nativeText.extract(file,1,"auto")).thenReturn(Optional.empty());
        when(pdf.renderForOcr(file,1)).thenReturn(image);
        Block block=new Block("paddle-aistudio-1","text",0,new double[]{.1,.1,.4,.5},"vertical-rl","原圖","原圖",null,true,false,null,"paddle-aistudio",List.of("paddle-aistudio-1"),null,new double[]{10,20,40,100});
        when(paddle.recognize(eq(image),eq("auto"),eq(true),eq("paddle-aistudio"),any())).thenReturn(List.of(block));
        Page page=processor.process("book",1,"paddle-aistudio","auto",true,false,()->false).page();
        assertEquals("paddle-aistudio",page.provider());assertEquals("原图",page.blocks().get(0).simplified());
        assertEquals("paddle-aistudio",page.sourceRecords().get(0).source());
        verify(paddle).recognize(eq(image),eq("auto"),eq(true),eq("paddle-aistudio"),any());
        verifyNoInteractions(local,qwen,mini,assist,toc);
    }

    @Test void sparsePageGuardKeepsRawPaddleSourceRecordsWhileReadingFallsBackToOriginalImage()throws Exception{
        BookStore store=mock(BookStore.class);PdfService pdf=mock(PdfService.class);NativeTextExtractor nativeText=mock(NativeTextExtractor.class);TesseractService local=mock(TesseractService.class);CloudOcrPipeline qwen=mock(CloudOcrPipeline.class);PaddleOcrPipeline paddle=mock(PaddleOcrPipeline.class);MiniMaxVisionClient mini=mock(MiniMaxVisionClient.class);QwenLayoutClient assist=mock(QwenLayoutClient.class);QwenTocRecoveryService toc=mock(QwenTocRecoveryService.class);PageProcessor processor=new PageProcessor(store,pdf,nativeText,local,qwen,paddle,mini,assist,toc,new SparsePageGuard(),mock(VerticalLayoutNormalizer.class),mock(AssistedReviewService.class),new TraditionalConverter());Path file=Path.of("sparse.pdf");BufferedImage image=new BufferedImage(500,700,BufferedImage.TYPE_INT_RGB);java.awt.Graphics2D g=image.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,500,700);g.dispose();when(store.pdf("book")).thenReturn(file);when(store.readPage("book",2)).thenReturn(new Page(2,500,700,"PENDING","",List.of(),List.of(),false,null,List.of()));when(nativeText.extract(file,2,"auto")).thenReturn(Optional.empty());when(pdf.renderForOcr(file,2)).thenReturn(image);Block raw=new Block("raw-table","table",0,new double[]{0,0,1,1},"horizontal-tb","錯".repeat(4099),"錯".repeat(4099),.9,false,false,null,"paddle",List.of("raw-table"),null,new double[]{0,0,500,700});when(paddle.recognize(eq(image),eq("auto"),eq(false),eq("paddle"),any())).thenReturn(List.of(raw));Page page=processor.process("book",2,"paddle","auto",false,false,()->false).page();assertEquals("paddle+sparse-page-guard",page.provider());assertEquals("figure",page.blocks().get(0).type());assertEquals("",page.blocks().get(0).original());assertEquals("raw-table",page.sourceRecords().get(0).id());assertEquals(4099,page.sourceRecords().get(0).original().length());assertTrue(page.warnings().stream().anyMatch(w->w.contains("原始识别记录")));verifyNoInteractions(toc,assist,qwen,mini,local);
    }
}
