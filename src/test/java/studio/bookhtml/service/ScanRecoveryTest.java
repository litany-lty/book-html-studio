package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScanRecoveryTest {
    static BufferedImage white() {
        var image=new BufferedImage(800,1000,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,800,1000);g.dispose();return image;
    }
    /** Entirely synthetic marks; no private book or OCR payload checked into Git. */
    static BufferedImage manuscript(boolean watermark,boolean grid) {
        var image=white();var g=image.createGraphics();
        if(watermark){g.setColor(Color.DARK_GRAY);g.fillOval(220,290,540,560);}
        g.setColor(Color.BLACK);
        if(grid){for(int x=35;x<780;x+=58)g.drawLine(x,35,x,955);for(int y=35;y<970;y+=45)g.drawLine(35,y,760,y);}
        for(int x=50;x<750;x+=58)for(int y=55;y<940;y+=45){
            g.drawLine(x,y,x+17,y+6);g.drawLine(x+9,y-4,x+9,y+25);g.drawLine(x,y+21,x+19,y+21);
        }
        g.dispose();return image;
    }
    static Block text(String id,String value) {
        return new Block(id,"text",0,new double[]{.18,.18,.55,.45},"vertical-rl",value,value,.7,true,false,null,"paddle",List.of(id),null,null);
    }
    static Block figure() {return new Block("raw","figure",0,new double[]{0,0,1,1},"horizontal-tb","","",null,true,false,null,"paddle",List.of("raw"),null,null);}

    @Test void marksBehindOverprintAndGridAreNeverBlankOrProvenTextFree() {
        for(boolean watermark:List.of(false,true))for(boolean grid:List.of(false,true)){
            var image=manuscript(watermark,grid);
            var e=ScanTextEvidence.inspect(image);
            assertFalse(e.nearBlank());assertTrue(e.possibleText(),e.toString());
            assertFalse(QualityGate.isTrueBlank(image));image.flush();
        }
    }
    @Test void blankSpecksAreAllowedButOneFaintGlyphVetoesBlankness() {
        var image=white();assertTrue(ScanTextEvidence.inspect(image).nearBlank());
        image.setRGB(20,20,Color.GRAY.getRGB());image.setRGB(790,950,Color.GRAY.getRGB());
        assertTrue(ScanTextEvidence.inspect(image).nearBlank());
        var g=image.createGraphics();g.setColor(new Color(220,220,220));g.fillRect(200,100,12,18);g.dispose();
        assertFalse(ScanTextEvidence.inspect(image).nearBlank());image.flush();
    }
    @Test void opaqueArtworkAndGridAloneDoNotTriggerRecovery() {
        var image=white();var g=image.createGraphics();g.setColor(Color.BLACK);g.fillRect(100,100,500,350);g.dispose();
        assertFalse(ScanTextEvidence.inspect(image).possibleText());assertFalse(ScanTextEvidence.inspect(image).nearBlank());
        var grid=white();g=grid.createGraphics();g.setColor(Color.BLACK);
        for(int x=0;x<800;x+=40)g.drawLine(x,0,x,999);for(int y=0;y<1000;y+=40)g.drawLine(0,y,799,y);g.dispose();
        assertFalse(ScanTextEvidence.inspect(grid).possibleText());
    }
    @Test void invalidEvidenceDoesNotCertifyBlank() { assertFalse(ScanTextEvidence.inspect(null).nearBlank()); }
    @Test void regionalRecoveryIsBoundedTraceableAndKeepsSourceUnchanged() throws Exception {
        var image=manuscript(true,true);int[] before=image.getRGB(0,0,800,1000,null,0,800);
        var count=new AtomicInteger();
        var result=OcrTextRecovery.recover(image,List.of(figure()),"vertical",()->false,(crop,layout,stop)->{
            count.incrementAndGet();assertTrue(crop.getWidth()<image.getWidth());assertEquals("vertical",layout);
            return List.of(text("same-provider-id","候选转录"));
        });
        assertEquals(4,count.get());assertTrue(result.warning().startsWith(OcrTextRecovery.PARTIAL));
        assertEquals(5,result.blocks().size());assertEquals("raw",result.blocks().get(0).id());
        BlockValidator.validate(result.blocks());
        for(var b:result.blocks().subList(1,5)){
            assertTrue(b.source().endsWith(":region-recovery"));assertEquals(List.of(b.id()),b.sourceIds());
            assertTrue(b.uncertain());assertFalse(b.reviewed());
            assertEquals(b.bbox()[0]*800,b.sourceRect()[0],.0001);
            assertEquals(b.bbox()[1]*1000,b.sourceRect()[1],.0001);
        }
        assertArrayEquals(before,image.getRGB(0,0,800,1000,null,0,800));
    }
    @Test void noRecoveryForReadableBaselineBlankOrSimpleIllustration() throws Exception {
        var count=new AtomicInteger();OcrTextRecovery.Recognizer rec=(img,l,c)->{count.incrementAndGet();return List.of();};
        assertNull(OcrTextRecovery.recover(manuscript(true,true),List.of(text("t","正文")),"auto",()->false,rec).warning());
        assertNull(OcrTextRecovery.recover(white(),List.of(),"auto",()->false,rec).warning());
        var solid=white();var g=solid.createGraphics();g.setColor(Color.BLACK);g.fillRect(0,0,600,500);g.dispose();
        assertNull(OcrTextRecovery.recover(solid,List.of(figure()),"auto",()->false,rec).warning());
        assertEquals(0,count.get());
    }
    @Test void stillEmptyIsUnresolvedRatherThanReadyVisualOnly() {
        var count=new AtomicInteger();
        var error=assertThrows(OcrException.class,()->OcrTextRecovery.recover(manuscript(true,true),List.of(figure()),"auto",()->false,(i,l,c)->{
            count.incrementAndGet();throw new OcrNoTextException("none");}));
        assertTrue(error.getMessage().startsWith("[OCR_EMPTY_UNRESOLVED]"));assertEquals(4,count.get());
    }
    @Test void partialRecoveryKeepsUnrecognizedRegionsAndDoesNotClaimSuccess() throws Exception {
        var count=new AtomicInteger();var result=OcrTextRecovery.recover(manuscript(true,true),List.of(),"auto",()->false,(i,l,c)->
                count.incrementAndGet()==1?List.of(text("t","已识别片段")):List.of());
        assertEquals(4,count.get());assertEquals(3,result.blocks().stream().filter(b->"ocr-region-unresolved".equals(b.source())).count());
        assertTrue(result.warning().contains("3 个区域"));assertTrue(result.warning().contains("需对照原稿"));
    }
    @Test void cancelAndFailureNeverStartMorePaidRequests() {
        var count=new AtomicInteger();var cancelled=new AtomicBoolean(true);
        OcrTextRecovery.Recognizer rec=(i,l,c)->{count.incrementAndGet();cancelled.set(true);return List.of(text("t","片段"));};
        assertThrows(CancelledException.class,()->OcrTextRecovery.recover(manuscript(true,true),List.of(),"auto",cancelled::get,rec));
        assertEquals(0,count.get());cancelled.set(false);
        assertThrows(CancelledException.class,()->OcrTextRecovery.recover(manuscript(true,true),List.of(),"auto",cancelled::get,rec));
        assertEquals(1,count.get());
        count.set(0);
        assertThrows(OcrException.class,()->OcrTextRecovery.recover(manuscript(true,true),List.of(),"auto",()->false,(i,l,c)->{count.incrementAndGet();throw new OcrException("transport failed");}));
        assertEquals(1,count.get());
    }
    @Test void malformedGeometryIsRejectedWithoutFurtherRecovery() {
        var count=new AtomicInteger();
        assertThrows(RuntimeException.class,()->OcrTextRecovery.recover(manuscript(true,true),List.of(),"auto",()->false,(i,l,c)->{
            count.incrementAndGet();return List.of(new Block("b","text",0,new double[]{.9,0,.8,1},"horizontal-tb","字","字",null,true,false,null,"ocr",List.of("b"),null,null));}));
        assertEquals(1,count.get());
    }
    @Test void repeatedWordsInSeparateRegionsAreNotDeduplicatedByText() throws Exception {
        var result=OcrTextRecovery.recover(manuscript(true,false),List.of(),"horizontal",()->false,(i,l,c)->List.of(text("id","同文")));
        assertEquals(4,result.blocks().stream().filter(b->"同文".equals(b.original())).count());
    }

    @Test void laterTransportOrInvalidRegionKeepsEarlierValidatedTextAndStops() throws Exception {
        for (boolean invalid : List.of(false,true)) {
            var count = new AtomicInteger();
            var result = OcrTextRecovery.recover(manuscript(true,true), List.of(), "auto", ()->false, (i,l,c)-> {
                if(count.incrementAndGet()==1)return List.of(text("b","已恢复的可信来源片段"));
                if(invalid)return List.of(new Block("bad","text",0,new double[]{.9,0,.8,1},"horizontal-tb",
                        "非法坐标内容","非法坐标内容",null,true,false,null,"ocr",List.of("bad"),null,null));
                throw new OcrException("provider transport failed");
            });
            assertEquals(2,count.get(),"never send remaining regions after a provider failure");
            assertTrue(result.warning().startsWith(OcrTextRecovery.PARTIAL));
            assertTrue(result.blocks().stream().anyMatch(b->"已恢复的可信来源片段".equals(b.original())));
            assertFalse(result.blocks().stream().anyMatch(b->"非法坐标内容".equals(b.original())));
            assertEquals(3,result.blocks().stream().filter(b->"ocr-region-unresolved".equals(b.source())).count());
            BlockValidator.validate(result.blocks());
        }
    }

    @Test void sparseSmallOcrRegionDoesNotHideTheRestOfAnOverprintedManuscript()throws Exception {
        var image=manuscript(true,true);Block tiny=new Block("found","text",0,new double[]{.01,.01,.05,.04},"vertical-rl",
                "零星字","零星字",.8,false,false,null,"ocr",List.of("found"),null,null);
        var calls=new AtomicInteger();var result=OcrTextRecovery.recover(image,List.of(tiny),"auto",()->false,(i,l,c)->{
            calls.incrementAndGet();return List.of(text("region","恢复正文片段"));});
        assertEquals(2,calls.get(),"sparse nonempty recovery uses at most two extra region starts");
        assertEquals("零星字",result.blocks().get(0).original());assertTrue(result.warning().startsWith(OcrTextRecovery.PARTIAL));
        assertTrue(result.blocks().stream().anyMatch(b->"恢复正文片段".equals(b.original())));
    }
}
