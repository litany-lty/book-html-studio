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

    private static studio.bookhtml.domain.ContentIssue issue(String id,boolean resolved,String replacement){
        return new studio.bookhtml.domain.ContentIssue(id,"suspected",0,2,0,2,"理由",resolved,replacement,"推测");
    }
    private static Block blockWithIssues(String id){
        return new Block(id,"text",0,new double[]{.1,.1,.3,.3},"horizontal-tb","原文"+id,"原文"+id,
                null,true,false,null,"qwen",List.of(id),"疑点",null,List.of(issue("i1",false,""),issue("i2",true,"已确认")));
    }
    @Test void reviewKeepsIssuesAndResolutionsOnAllBranches()throws Exception{
        // T05：成功分支保留全部 issues（含已确认的 replacement）
        QwenOcrClient qwen=mock(QwenOcrClient.class);
        when(qwen.recognize(any(),anyInt(),anyInt(),anyString(),any())).thenReturn(List.of(
                new Block("r","text",0,new double[]{0,0,1,1},"horizontal-tb","候选","候选",null,true,false,null,"qwen",List.of("r"),null,null)));
        AssistedReviewService service=new AssistedReviewService(qwen);
        Block source=blockWithIssues("b0");
        Block done=service.review(new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),List.of(source),()->false).get(0);
        assertEquals(List.of("i1","i2"),done.issues().stream().map(studio.bookhtml.domain.ContentIssue::id).toList());
        assertEquals("已确认",done.issues().stream().filter(i->i.id().equals("i2")).findFirst().orElseThrow().replacement());
        assertEquals("qwen",done.source().split("\\+")[0]);
        assertTrue(done.source().contains("qwen-review"));
        // T05：失败分支同样保留
        QwenOcrClient failing=mock(QwenOcrClient.class);
        when(failing.recognize(any(),anyInt(),anyInt(),anyString(),any())).thenThrow(new RuntimeException("boom"));
        Block kept=new AssistedReviewService(failing).review(new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),List.of(source),()->false).get(0);
        assertEquals(2,kept.issues().size());
        assertEquals("已确认",kept.issues().get(1).replacement());
        assertTrue(kept.suggestion().contains("失败"));
    }
    @Test void reviewedBlocksAreSkippedWithoutOcr()throws Exception{
        // T06：人工已确认目标默认不进入复识别
        QwenOcrClient qwen=mock(QwenOcrClient.class);
        AssistedReviewService service=new AssistedReviewService(qwen);
        Block confirmed=new Block("b0","text",0,new double[]{.1,.1,.3,.3},"horizontal-tb","原文","原文",
                null,true,true,null,"qwen",List.of("b0"),"疑点",null,List.of(issue("i1",true,"好")));
        Block out=service.review(new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),List.of(confirmed),()->false).get(0);
        assertSame(confirmed,out);
        verifyNoInteractions(qwen);
    }
    @Test void callerOwnedPageImageIsNeverFlushed()throws Exception{
        // T07：调用者的整页图不被关闭；第二目标异常不影响第一目标结果
        BufferedImage page=mock(BufferedImage.class);
        QwenOcrClient qwen=mock(QwenOcrClient.class);
        when(qwen.recognize(any(),anyInt(),anyInt(),anyString(),any())).thenThrow(new RuntimeException("boom"));
        AssistedReviewService service=new AssistedReviewService(qwen);
        Block source=blockWithIssues("b0");
        Block out=service.review(page,List.of(source),()->false).get(0);
        assertEquals(2,out.issues().size());
        verify(page,never()).flush();
    }
    @Test void cancellationPropagatesWithoutPartialCommit()throws Exception{
        QwenOcrClient qwen=mock(QwenOcrClient.class);
        AssistedReviewService service=new AssistedReviewService(qwen);
        assertThrows(CancelledException.class,()->service.review(
                new BufferedImage(500,500,BufferedImage.TYPE_INT_RGB),List.of(block("b0",.1,.1)),()->true));
        verifyNoInteractions(qwen);
    }
}
