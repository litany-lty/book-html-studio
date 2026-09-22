package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScanRecoveryPipelineTest {
    @TempDir Path data;
    private PageProcessor processor(BookStore store,PdfService pdf,PaddleOcrPipeline paddle) throws Exception {
        var nativeText=mock(NativeTextExtractor.class);when(nativeText.extract(any(),anyInt(),anyString())).thenReturn(Optional.empty());
        return new PageProcessor(store,pdf,nativeText,mock(TesseractService.class),mock(CloudOcrPipeline.class),paddle,
                mock(MiniMaxVisionClient.class),mock(QwenLayoutClient.class),mock(QwenTocRecoveryService.class),
                new SparsePageGuard(),mock(VerticalLayoutNormalizer.class),mock(AssistedReviewService.class),new TraditionalConverter());
    }
    @Test void realProcessingPipelineDoesNotAcceptManuscriptAsEmptyFigure() throws Exception {
        var store=mock(BookStore.class);Path f=data.resolve("fixture.pdf");Files.writeString(f,"not-a-real-pdf");
        when(store.pdf("book")).thenReturn(f);when(store.readPage("book",1)).thenReturn(Page.pending(1,800,1000));
        var image=ScanRecoveryTest.manuscript(true,true);var pdf=mock(PdfService.class);when(pdf.renderForOcr(f,1)).thenReturn(image);
        var paddle=mock(PaddleOcrPipeline.class);
        when(paddle.recognize(any(),eq("vertical"),eq(false),eq("paddle-aistudio"),any()))
                .thenAnswer(inv->inv.getArgument(0)==image?List.of(ScanRecoveryTest.figure()):List.of(ScanRecoveryTest.text("b","原图局部文字")));
        var result=processor(store,pdf,paddle).process("book",1,"paddle-aistudio","vertical",false,false,()->false);
        assertEquals(ProcessingResult.Category.TEXT_PARTIAL,result.category());assertEquals("READY",result.page().status());
        assertTrue(result.page().sourceRecords().stream().anyMatch(b->b.source().contains("region-recovery")));
        verify(paddle,times(5)).recognize(any(),eq("vertical"),eq(false),eq("paddle-aistudio"),any());
        verifyNoMoreInteractions(paddle);
    }
    @Test void validEmptyResultCanReachRecoveryButProtocolErrorCannot() throws Exception {
        var store=mock(BookStore.class);Path f=data.resolve("fixture.pdf");Files.writeString(f,"fixture");
        when(store.pdf("book")).thenReturn(f);when(store.readPage("book",1)).thenReturn(Page.pending(1,800,1000));
        var image=ScanRecoveryTest.manuscript(true,false);var pdf=mock(PdfService.class);when(pdf.renderForOcr(f,1)).thenReturn(image);
        var paddle=mock(PaddleOcrPipeline.class);
        when(paddle.recognize(any(),anyString(),anyBoolean(),anyString(),any())).thenAnswer(inv->{
            if(inv.getArgument(0)==image)throw new OcrNoTextException("no text");return List.of(ScanRecoveryTest.text("b","区域结果"));});
        assertEquals(ProcessingResult.Category.TEXT_PARTIAL,processor(store,pdf,paddle).process("book",1,"ppocr","auto",false,false,()->false).category());
        reset(paddle);when(paddle.recognize(any(),anyString(),anyBoolean(),anyString(),any())).thenThrow(new OcrException("malformed JSON"));
        assertThrows(OcrException.class,()->processor(store,pdf,paddle).process("book",1,"ppocr","auto",false,false,()->false));
        verify(paddle,times(1)).recognize(any(),anyString(),anyBoolean(),anyString(),any());
    }
    @Test void disablingExtraRecoveryDoesNotCertifyAnEmptyManuscript() throws Exception {
        var store=mock(BookStore.class); Path f=data.resolve("fixture.pdf"); Files.writeString(f,"fixture");
        when(store.pdf("book")).thenReturn(f); when(store.readPage("book",1)).thenReturn(Page.pending(1,800,1000));
        var image=ScanRecoveryTest.manuscript(true,true); var pdf=mock(PdfService.class); when(pdf.renderForOcr(f,1)).thenReturn(image);
        var paddle=mock(PaddleOcrPipeline.class);
        when(paddle.recognize(any(),anyString(),anyBoolean(),anyString(),any())).thenReturn(List.of(ScanRecoveryTest.figure()));
        var processor=processor(store,pdf,paddle);
        org.springframework.test.util.ReflectionTestUtils.setField(processor,"regionRecoveryEnabled",false);
        assertTrue(assertThrows(OcrException.class,()->processor.process("book",1,"paddle-aistudio","auto",false,false,()->false))
                .getMessage().contains("OCR_EMPTY_UNRESOLVED"));
        verify(paddle,times(1)).recognize(any(),anyString(),anyBoolean(),anyString(),any());
    }

    @Test void batchPersistsPartialNotSuccessfulEmptyAndPreservesFailedRetrySnapshot() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var store=new BookStore(TestConfigs.config(data,"",""),json);
        String id=UUID.randomUUID().toString();store.createBookDirectory(id);var book=new Book(id,"fixture","fixture.pdf",1,Instant.now(),Instant.now(),0,0);store.writeBook(book);store.writePage(id,Page.pending(1,800,1000),false);
        var books=mock(BookService.class);when(books.get(id)).thenReturn(book);
        var pdf=mock(PdfService.class);var image=ScanRecoveryTest.manuscript(true,true);when(pdf.renderForOcr(any(),eq(1))).thenReturn(image);
        var paddle=mock(PaddleOcrPipeline.class);when(paddle.recognize(any(),anyString(),anyBoolean(),anyString(),any()))
                .thenAnswer(inv->inv.getArgument(0)==image?List.of(ScanRecoveryTest.figure()):List.of(ScanRecoveryTest.text("b","部分转录")));
        var jobs=new JobService(store,books,processor(store,pdf,paddle));
        var progress=new ProcessingProgressService();jobs.setProgress(progress);
        try {
            jobs.submit(id,new JobRequest("1","paddle-aistudio","vertical",false,false,false));awaitJob(store,id);
            assertEquals("COMPLETED_WITH_ERRORS",store.readJob(id).status());
            assertEquals("PARTIAL",progress.latest(id,1).lifecycle());assertTrue(progress.latest(id,1).canRead());assertEquals("READY",store.readPage(id,1).status());
            assertTrue(store.readPage(id,1).warnings().stream().anyMatch(w->w.startsWith(OcrTextRecovery.PARTIAL)));
            var old=store.readPage(id,1);
            reset(paddle);when(paddle.recognize(any(),anyString(),anyBoolean(),anyString(),any())).thenReturn(List.of(ScanRecoveryTest.figure()));
            jobs.submit(id,new JobRequest("1","paddle-aistudio","vertical",false,true,false));awaitJob(store,id);
            var retained=store.readPage(id,1);assertEquals("READY",retained.status());
            assertEquals(json.valueToTree(old.blocks()),json.valueToTree(retained.blocks()));assertTrue(retained.error().contains("OCR_EMPTY_UNRESOLVED"));
        } finally {jobs.close();store.close();}
    }
    private static void awaitJob(BookStore store,String id) throws Exception {
        long until=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<until){if(store.readJob(id).status().startsWith("COMPLETED"))return;Thread.sleep(10);}
        fail("job did not finish");
    }
    @Test void splitEmptyHalfIsRetainedAndNeverResubmitsWholeSpread() throws Exception {
        var image=new java.awt.image.BufferedImage(1200,700,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,1200,700);
        g.setColor(java.awt.Color.BLACK);g.fillRect(100,100,300,400);g.fillRect(800,100,300,400);g.dispose();
        var client=mock(QwenOcrClient.class);var pdf=mock(PdfService.class);
        when(pdf.png(any())).thenReturn(new byte[]{1});
        when(client.recognize(any(),anyInt(),anyInt(),eq("auto"),any()))
                .thenThrow(new OcrNoTextException("empty half"))
                .thenReturn(List.of(ScanRecoveryTest.text("b","左半页转录")));
        var blocks=new CloudOcrPipeline(client,pdf).recognize(image,"auto",true,()->false);
        verify(client,times(2)).recognize(any(),anyInt(),anyInt(),eq("auto"),any());
        assertEquals(2,blocks.size());
        assertTrue(blocks.stream().anyMatch(b->"ocr-region-unresolved".equals(b.source())));
        var recovered=OcrTextRecovery.recover(image,blocks,"auto",()->false,(a,b,c)->{fail("must not duplicate paid recovery");return List.of();});
        assertTrue(recovered.warning().startsWith(OcrTextRecovery.PARTIAL));
    }

    @Test void providerParsersDistinguishValidEmptyFromBadEnvelope() throws Exception {
        var json=new ObjectMapper();var empty=json.readTree("{\"pages\":[{\"layouts\":[]}]}");
        assertThrows(OcrNoTextException.class,()->new PaddleOcrParser().parse(empty,800,1000,"auto"));
        assertThrows(OcrNoTextException.class,()->new BaiduPpOcrParser().parse(empty,800,1000,"auto"));
        var bad=json.readTree("{\"pages\":[{\"layouts\":[{}]}]}");
        assertFalse(assertThrows(OcrException.class,()->new PaddleOcrParser().parse(bad,800,1000,"auto")) instanceof OcrNoTextException);
    }
}
