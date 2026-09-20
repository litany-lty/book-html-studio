package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SparsePageGuardTest {
    private final SparsePageGuard guard=new SparsePageGuard();

    @Test void extremelySparsePageRejectsFullPageTableClaimButKeepsTraceableOriginalImage(){BufferedImage image=blankWithSpecks();Block hallucinated=block("table",new double[]{.01,.01,.98,.98},"10000");SparsePageGuard.GuardResult result=guard.apply(image,List.of(hallucinated));assertTrue(result.guarded());assertEquals(1,result.blocks().size());Block fallback=result.blocks().get(0);assertEquals("figure",fallback.type());assertEquals("sparse-page-guard",fallback.source());assertEquals(List.of("raw"),fallback.sourceIds());assertEquals("",fallback.original());assertTrue(result.warning().contains("原始识别记录"));assertEquals("10000",hallucinated.original());image.flush();}

    @Test void characterCountDoesNotControlGuardAndDenseOrShallowEvidenceIsPreserved(){BufferedImage sparse=blankWithSpecks();String longText="錯".repeat(4099);assertTrue(guard.apply(sparse,List.of(block("table",new double[]{0,0,1,1},longText))).guarded());BufferedImage shallow=new BufferedImage(500,700,BufferedImage.TYPE_INT_RGB);Graphics2D g=shallow.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,500,700);g.setColor(new Color(200,200,200));for(int y=100;y<600;y+=30)g.fillRect(80,y,340,5);g.dispose();assertFalse(guard.apply(shallow,List.of(block("table",new double[]{0,0,1,1},"可见的浅色原稿"))).guarded());BufferedImage dense=new BufferedImage(500,700,BufferedImage.TYPE_INT_RGB);Graphics2D d=dense.createGraphics();d.setColor(Color.WHITE);d.fillRect(0,0,500,700);d.setColor(Color.BLACK);for(int y=60;y<650;y+=20)d.drawString("真实正文和表格内容",80,y);d.dispose();assertFalse(guard.apply(dense,List.of(block("table",new double[]{0,0,1,1},longText))).guarded());sparse.flush();shallow.flush();dense.flush();}

    @Test void emptyVisualOrPartialTextClaimIsNotReclassifiedAsSparseOcrHallucination(){BufferedImage image=blankWithSpecks();assertFalse(guard.apply(image,List.of(block("figure",new double[]{0,0,1,1},""))).guarded());assertFalse(guard.apply(image,List.of(block("table",new double[]{.2,.2,.4,.4},"10000"))).guarded());image.flush();}

    private static BufferedImage blankWithSpecks(){BufferedImage image=new BufferedImage(500,700,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,500,700);g.setColor(Color.GRAY);g.fillRect(20,20,1,1);g.fillRect(400,500,1,1);g.dispose();return image;}
    private static Block block(String type,double[]bbox,String text){return new Block("raw",type,0,bbox,"horizontal-tb",text,text,.9,false,false,null,"paddle",List.of("raw"),null,new double[]{0,0,500,700});}
}
