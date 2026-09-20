package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PageRangesAndBlocksTest {
    @Test void parsesAndDeduplicatesRanges(){assertEquals(List.of(1,2,3,5),PageRanges.parse("1-3,2,5",5));assertEquals(List.of(1,2,3),PageRanges.parse("all",3));}
    @Test void rejectsOutOfRange(){assertThrows(ApiException.class,()->PageRanges.parse("0,2",3));assertThrows(ApiException.class,()->PageRanges.parse("3-1",3));}
    @Test void validatesBoundingBoxes(){Block ok=block("x",new double[]{.1,.2,.3,.4},0);assertDoesNotThrow(()->BlockValidator.validate(List.of(ok)));assertThrows(ApiException.class,()->BlockValidator.validate(List.of(block("x",new double[]{.9,.2,.3,.4},0))));assertThrows(ApiException.class,()->BlockValidator.validate(List.of(block("x",new double[]{.1,.2,.3,.4},0),block("x",new double[]{.2,.2,.3,.4},1))));}
    @Test void validatesIssuesAndMapsSimplifiedOffsets()throws Exception{String original="髮型模糊後臺";ContentIssue issue=new ContentIssue("i1","unreadable",2,4,0,0,"字迹缺损",false,null,"後臺可能");List<ContentIssue>mapped=PageProcessor.mapIssues(original,List.of(issue),new TraditionalConverter());Block block=new Block("x","text",0,new double[]{0,0,1,1},"vertical-rl",original,new TraditionalConverter().toSimplified(original),null,true,false,null,"test",List.of("x"),null,null,mapped);assertDoesNotThrow(()->BlockValidator.validate(List.of(block)));assertEquals(new TraditionalConverter().toSimplified(original.substring(0,2)).length(),mapped.get(0).simplifiedStart());assertEquals("后台可能",mapped.get(0).inferredText());}
    @Test void rejectsOverlappingOrAutoResolvedIssues(){ContentIssue a=new ContentIssue("a","suspected",0,2,0,2,"疑字",false,null,"甲");ContentIssue overlap=new ContentIssue("b","unreadable",1,3,1,3,"缺损",false,null,null);Block bad=new Block("x","text",0,new double[]{0,0,1,1},"horizontal-tb","繁體字","繁体字",null,true,false,null,"test",List.of("x"),null,null,List.of(a,overlap));assertThrows(ApiException.class,()->BlockValidator.validate(List.of(bad)));ContentIssue autoResolved=new ContentIssue("c","suspected",0,1,0,1,"疑字",true,null,"甲");Block unresolved=new Block("y","text",0,new double[]{0,0,1,1},"horizontal-tb","字","字",null,true,false,null,"test",List.of("y"),null,null,List.of(autoResolved));assertThrows(ApiException.class,()->BlockValidator.validate(List.of(unresolved)));}
    static Block block(String id,double[]bbox,int order){return new Block(id,"text",order,bbox,"horizontal-tb","繁體","繁体",null,true,false,null,"test",null,null,null);}
}
