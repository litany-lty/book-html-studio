package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VerticalLayoutNormalizerTest {
    private final VerticalLayoutNormalizer normalizer=new VerticalLayoutNormalizer();
    @Test void page25GeometryRestoresRightToLeftOrderAndAbsorbsFigureLines(){
        List<Block>sources=List.of(
            source("R-qwen-line-2",1,.908,.590,.015,.218,"或重要幹部的離職而影響公"),
            source("R-qwen-line-3",2,.906,.264,.015,.05,"福"),
            source("R-qwen-line-46",45,.598,.257,.016,.466,"財運也罷，運途也罷"),
            source("L-qwen-line-2",47,.418,.577,.015,.236,"下面我們再舉一則命例來加以"),
            source("L-qwen-line-5",48,.391,.358,.015,.05,"府"),
            source("L-qwen-line-35",80,.104,.262,.016,.319,"越滾越不可收拾，最後免不了被迫收攤。"));
        List<Block>wrong=List.of(
            structured("a",0,"text",List.of("R-qwen-line-46"),sources.get(2).bbox(),"財運也罷，運途也罷"),
            structured("b",1,"text",List.of("R-qwen-line-2"),sources.get(0).bbox(),"或重要幹部的離職而影響公"),
            structured("rf",2,"figure",List.of(),new double[]{.7,.27,.26,.31},""),
            structured("c",3,"text",List.of("L-qwen-line-35"),sources.get(5).bbox(),"越滾越不可收拾，最後免不了被迫收攤。"),
            structured("d",4,"text",List.of("L-qwen-line-2"),sources.get(3).bbox(),"下面我們再舉一則命例來加以"),
            structured("lf",5,"figure",List.of(),new double[]{.25,.27,.16,.29},""),
            structured("unplaced-R-qwen-line-3",6,"text",List.of("R-qwen-line-3"),sources.get(1).bbox(),"福"),
            structured("unplaced-L-qwen-line-5",7,"text",List.of("L-qwen-line-5"),sources.get(4).bbox(),"府"));
        List<Block>result=normalizer.normalize(wrong,sources);String reading=result.stream().map(Block::original).reduce("",(a,b)->a+"|"+b);
        assertTrue(reading.indexOf("或重要")<reading.indexOf("財運也罷"));
        assertTrue(reading.indexOf("下面我們")<reading.indexOf("越滾越"));
        List<String>ids=result.stream().flatMap(b->b.sourceIds().stream()).toList();assertEquals(sources.size(),ids.size());assertEquals(sources.size(),new HashSet<>(ids).size());assertEquals(sources.stream().map(Block::id).collect(java.util.stream.Collectors.toSet()),new HashSet<>(ids));
        assertTrue(result.stream().filter(b->"figure".equals(b.type())).allMatch(b->!b.sourceIds().isEmpty()));assertFalse(result.stream().anyMatch(b->b.id().startsWith("unplaced-")&&(b.original().equals("福")||b.original().equals("府"))));
    }
    @Test void mergesAdjacentSameTopColumnsButNotAcrossPartition(){Block r1=source("R-qwen-line-1",0,.9,.6,.015,.2,"甲"),r2=source("R-qwen-line-2",1,.875,.604,.015,.2,"乙"),l1=source("L-qwen-line-1",2,.45,.6,.015,.2,"丙");List<Block>result=normalizer.normalize(List.of(structured("r1",0,"text",List.of(r1.id()),r1.bbox(),r1.original()),structured("r2",1,"text",List.of(r2.id()),r2.bbox(),r2.original()),structured("l1",2,"text",List.of(l1.id()),l1.bbox(),l1.original())),List.of(r1,r2,l1));assertEquals(2,result.size());assertEquals("甲乙",result.get(0).original());assertEquals(List.of("R-qwen-line-1","R-qwen-line-2"),result.get(0).sourceIds());assertEquals("丙",result.get(1).original());}
    private static Block source(String id,int order,double x,double y,double w,double h,String text){return new Block(id,"text",order,new double[]{x,y,w,h},"vertical-rl",text,text,null,true,false,null,id.startsWith("R-")?"qwen:R":"qwen:L",List.of(id),null,null);}
    private static Block structured(String id,int order,String type,List<String>ids,double[]bbox,String text){return new Block(id,type,order,bbox,"vertical-rl",text,text,null,false,false,null,"qwen+minimax",ids,id.startsWith("unplaced-")?"辅助模型未放置此行，已自动补回":null,null);}
}
