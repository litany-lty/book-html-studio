package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CloudOcrPipelineTest {
    @Test void splitsOnlyWhenLandscapeHasCentralGutter(){BufferedImage spread=new BufferedImage(1200,700,BufferedImage.TYPE_INT_RGB);Graphics2D g=spread.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,1200,700);g.setColor(Color.BLACK);g.fillRect(100,100,300,400);g.fillRect(800,100,300,400);g.dispose();assertTrue(CloudOcrPipeline.shouldSplit(spread));g=spread.createGraphics();g.setColor(Color.BLACK);g.fillRect(570,0,60,700);g.dispose();assertFalse(CloudOcrPipeline.shouldSplit(spread));}
    @Test void mapsHalfCoordinatesToFullPageWithUniqueIds(){Block local=new Block("qwen-line-1","text",0,new double[]{.1,.2,.2,.3},"vertical-rl","字","字",null,true,false,null,"qwen",List.of("qwen-line-1"),null,new double[]{1,2,3,4});Block mapped=CloudOcrPipeline.remap(local,"R",500,500,1000);assertEquals("R-qwen-line-1",mapped.id());assertArrayEquals(new double[]{.55,.2,.1,.3},mapped.bbox(),1e-9);assertEquals(List.of("R-qwen-line-1"),mapped.sourceIds());}
}
