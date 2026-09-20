package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Block(String id, String type, int order, double[] bbox, String writingMode,
                    String original, String simplified, Double confidence, boolean uncertain,
                    boolean reviewed, Integer headingLevel, String source,
                    List<String> sourceIds, String suggestion, double[] sourceRect,
                    List<ContentIssue> issues) {
    public Block(String id,String type,int order,double[]bbox,String writingMode,String original,String simplified,
                 Double confidence,boolean uncertain,boolean reviewed,Integer headingLevel,String source,
                 List<String>sourceIds,String suggestion,double[]sourceRect){this(id,type,order,bbox,writingMode,
            original,simplified,confidence,uncertain,reviewed,headingLevel,source,sourceIds,suggestion,sourceRect,List.of());}
    public Block { issues=issues==null?List.of():List.copyOf(issues); }
}
