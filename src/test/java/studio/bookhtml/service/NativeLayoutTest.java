package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeLayoutTest {
    @Test void verticalColumnsReadRightToLeft(){List<NativeTextExtractor.Glyph>g=List.of(new NativeTextExtractor.Glyph("左",.2,.2,.03,.05,0),new NativeTextExtractor.Glyph("右",.8,.2,.03,.05,0),new NativeTextExtractor.Glyph("一",.8,.3,.03,.05,0));List<Block>b=NativeTextExtractor.toBlocks(g,"vertical-rl",new TraditionalConverter());assertEquals("右一",b.get(0).original());assertEquals("左",b.get(1).original());}
    @Test void separatesDistantColumnsAndPreservesEnglishSpace(){List<NativeTextExtractor.Glyph>g=List.of(new NativeTextExtractor.Glyph("hello",.1,.2,.08,.03,0),new NativeTextExtractor.Glyph("world",.2,.2,.08,.03,0),new NativeTextExtractor.Glyph("other",.75,.2,.08,.03,0));List<Block>b=NativeTextExtractor.toBlocks(g,"horizontal-tb",new TraditionalConverter());assertEquals(2,b.size());assertEquals("hello world",b.get(0).original());}
    @Test void spreadOrderFollowsWritingDirection(){BufferedImage image=new BufferedImage(1000,500,BufferedImage.TYPE_INT_RGB);assertEquals(500,TesseractService.split(image,"vertical").get(0).x());assertEquals(0,TesseractService.split(image,"horizontal").get(0).x());}
    @Test void narrowEnglishGlyphsOnWidePageRemainHorizontal(){List<NativeTextExtractor.Glyph>g=List.of(new NativeTextExtractor.Glyph("I",.1,.2,.006,.04,0),new NativeTextExtractor.Glyph("l",.12,.2,.006,.04,0),new NativeTextExtractor.Glyph("o",.14,.2,.012,.04,0),new NativeTextExtractor.Glyph("v",.16,.2,.012,.04,0),new NativeTextExtractor.Glyph("e",.18,.2,.012,.04,0));assertEquals("horizontal-tb",NativeTextExtractor.chooseLayout(g,"auto"));}
    @Test void horizontalChinesePositionsBeatGlyphAspectRatio(){List<NativeTextExtractor.Glyph>g=List.of(new NativeTextExtractor.Glyph("天",.1,.3,.02,.05,0),new NativeTextExtractor.Glyph("地",.14,.3,.02,.05,0),new NativeTextExtractor.Glyph("人",.18,.3,.02,.05,0),new NativeTextExtractor.Glyph("和",.22,.3,.02,.05,0));assertEquals("horizontal-tb",NativeTextExtractor.chooseLayout(g,"auto"));}
}
