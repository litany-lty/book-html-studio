package studio.bookhtml.domain;

import java.util.List;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record Page(int pageNumber, double width, double height, String status, String provider,
                   List<Block> blocks, List<String> warnings, boolean reviewed, String error,
                   List<Block> sourceRecords, Integer revision) {
    public Page(int pageNumber,double width,double height,String status,String provider,List<Block>blocks,List<String>warnings,boolean reviewed,String error){this(pageNumber,width,height,status,provider,blocks,warnings,reviewed,error,null,null);}
    public Page(int pageNumber,double width,double height,String status,String provider,List<Block>blocks,List<String>warnings,boolean reviewed,String error,List<Block>sourceRecords){this(pageNumber,width,height,status,provider,blocks,warnings,reviewed,error,sourceRecords,null);}
    @com.fasterxml.jackson.annotation.JsonProperty("schemaVersion") public int schemaVersion() { return 1; }
    public static Page pending(int n, double width, double height) {
        return new Page(n, width, height, "PENDING", null, List.of(), List.of("本页尚未处理"), false, null);
    }
}
