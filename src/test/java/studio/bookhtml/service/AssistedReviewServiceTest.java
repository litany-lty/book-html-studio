package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistedReviewServiceTest {
    private Block block(String id,double x,double y){return new Block(id,"text",Integer.parseInt(id.substring(1)),new double[]{x,y,.15,.15},"horizontal-tb","原文"+id,"原文"+id,null,true,false,null,"qwen+minimax",List.of(id),"疑点",null);}
    @Test void deduplicatesOverlapsAndLimitsRegions(){List<Block>input=new ArrayList<>();input.add(block("b0",.1,.1));input.add(block("b1",.11,.11));for(int i=2;i<8;i++)input.add(block("b"+i,.1*i,.5));List<Block>selected=AssistedReviewService.selectCandidates(input,3);assertEquals(3,selected.size());assertFalse(selected.stream().map(Block::id).toList().contains("b1"));}
    @Test void localRereadNeverOverwritesOriginal()throws Exception{QwenOcrClient qwen=mock(QwenOcrClient.class);when(qwen.recognize(any(),anyInt(),anyInt(),anyString(),any())).thenReturn(List.of(new Block("r","text",0,new double[]{0,0,1,1},"horizontal-tb","不同候选","不同候选",null,true,false,null,"qwen",List.of("r"),null,null)));AssistedReviewService service=new AssistedReviewService(qwen);Block source=block("b0",.1,.1);List<Block>result=service.review(new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),List.of(source),()->false);assertEquals(source.original(),result.get(0).original());assertTrue(result.get(0).suggestion().contains("不同候选"));}
}
