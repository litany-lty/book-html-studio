package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import studio.bookhtml.domain.Block;

import java.util.*;

@Component
public class PaddleOcrParser {
    public List<Block> parse(JsonNode root,int imageWidth,int imageHeight,String requestedLayout)throws OcrException{
        try{
            JsonNode pages=root.path("pages");
            if(!pages.isArray()||pages.isEmpty())throw new OcrException("PaddleOCR-VL 结果缺少 pages");
            if(pages.size()!=1)throw new OcrException("PaddleOCR-VL 单页任务返回了异常页数");
            JsonNode page=pages.get(0);int coordinateWidth=dimension(page.path("meta").path("page_width"),imageWidth),coordinateHeight=dimension(page.path("meta").path("page_height"),imageHeight);JsonNode layouts=page.path("layouts");
            if(!layouts.isArray())throw new OcrException("PaddleOCR-VL 结果缺少 layouts");
            List<Block>blocks=new ArrayList<>();Set<String>ids=new HashSet<>();int order=0;
            for(JsonNode layout:layouts){
                if(!layout.isObject())continue;
                String remoteType=layout.path("type").asText("").strip();
                String type=mapType(remoteType);boolean unknown=!knownType(remoteType);
                double[]raw=coordinates(layout);double[]bbox=normalize(raw,coordinateWidth,coordinateHeight);
                String text=layout.path("text").asText("");String directory=directoryText(layout.path("span_boxes"));boolean fromDirectorySpans=directory!=null;if(fromDirectorySpans)text=directory;else if(text.isBlank())text=spanText(layout.path("span_boxes"));
                if("table".equals(type))text=plainTable(text.isBlank()?layout.path("table_html").asText(""):text);
                String remoteId=layout.path("layout_id").asText("").strip();
                String base=remoteId.isBlank()?"layout-"+(order+1):remoteId.replaceAll("[^A-Za-z0-9._-]","-");
                if(base.length()>96)base=base.substring(0,96);String id="paddle-"+base;int duplicate=2;while(!ids.add(id))id="paddle-"+base+"-"+(duplicate++);
                String mode=writingMode(remoteType,requestedLayout,bbox);
                String suggestion=unknown?"PaddleOCR-VL 返回未知布局类型 "+safeLabel(remoteType)+"，已保守保留为正文块":null;
                boolean uncertain=unknown||text.isBlank();
                blocks.add(new Block(id,type,order++,bbox,mode,text,text,null,uncertain,false,null,fromDirectorySpans?"paddle-span":"paddle",List.of(id),suggestion,raw));
            }
            if(blocks.isEmpty()){if(layouts.isEmpty())throw new OcrNoTextException("PaddleOCR-VL 未检出内容块");throw new OcrException("PaddleOCR-VL 未返回带有效坐标的内容块");}
            BlockValidator.validate(blocks);return List.copyOf(blocks);
        }catch(OcrException e){throw e;}catch(Exception e){throw new OcrException("PaddleOCR-VL 结果 JSON 或坐标无效",e);}
    }
    private static boolean knownType(String value){return switch(value){case"text","vertical_text","paragraph","content","title","doc_title","paragraph_title","section_title","header","footer","number","page_number","page-number","table","figure","image","chart","formula","equation","caption","figure_title","table_title"->true;default->false;};}
    private static String mapType(String value){return switch(value){case"title","doc_title","paragraph_title","section_title"->"heading";case"number","page_number","page-number","footer"->"page-number";case"table"->"table";case"figure","image","chart"->"figure";case"formula","equation"->"formula";case"caption","figure_title","table_title"->"caption";default->"text";};}
    private static String writingMode(String remoteType,String requested,double[]bbox){if("vertical_text".equals(remoteType))return"vertical-rl";if("vertical".equals(requested))return"vertical-rl";if("horizontal".equals(requested))return"horizontal-tb";return bbox[3]>bbox[2]*2?"vertical-rl":"horizontal-tb";}
    private static double[]coordinates(JsonNode layout)throws OcrException{JsonNode position=layout.get("position");if(position!=null&&position.isArray()&&position.size()>=4&&numbers(position,4))return new double[]{position.get(0).asDouble(),position.get(1).asDouble(),position.get(2).asDouble(),position.get(3).asDouble()};JsonNode polygon=layout.get("polygon");if(polygon!=null&&polygon.isArray()&&!polygon.isEmpty()){double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE;for(JsonNode point:polygon){if(!point.isArray()||point.size()<2||!point.get(0).isNumber()||!point.get(1).isNumber())throw new OcrException("PaddleOCR-VL polygon 坐标无效");double x=point.get(0).asDouble(),y=point.get(1).asDouble();minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);}return new double[]{minX,minY,maxX-minX,maxY-minY};}throw new OcrException("PaddleOCR-VL 内容块缺少必要坐标");}
    private static boolean numbers(JsonNode array,int count){for(int i=0;i<count;i++)if(!array.get(i).isNumber())return false;return true;}
    private static int dimension(JsonNode value,int fallback){return value.canConvertToInt()&&value.asInt()>0?value.asInt():fallback;}
    private static double[]normalize(double[]raw,int width,int height)throws OcrException{if(width<=0||height<=0||raw.length!=4||!Double.isFinite(raw[0])||!Double.isFinite(raw[1])||!Double.isFinite(raw[2])||!Double.isFinite(raw[3])||raw[0]<0||raw[1]<0||raw[2]<=0||raw[3]<=0||raw[0]+raw[2]>width+1||raw[1]+raw[3]>height+1)throw new OcrException("PaddleOCR-VL 内容块坐标越界");double right=Math.min(width,raw[0]+raw[2]),bottom=Math.min(height,raw[1]+raw[3]);double[]box={raw[0]/width,raw[1]/height,(right-raw[0])/width,(bottom-raw[1])/height};try{BlockValidator.validateBbox(box);}catch(Exception e){throw new OcrException("PaddleOCR-VL 内容块坐标无效",e);}return box;}
    private static String spanText(JsonNode spans){if(!spans.isArray())return"";List<String>text=new ArrayList<>();for(JsonNode span:spans){String value=span.path("text").asText("").strip();if(!value.isEmpty())text.add(value);}return String.join("",text);}
    private static String directoryText(JsonNode spans){if(!spans.isArray()||spans.size()<6)return null;List<String>lines=new ArrayList<>();int nonEmpty=0,covered=0;for(JsonNode span:spans)if(!span.path("text").asText("").strip().isEmpty())nonEmpty++;for(int i=0;i+1<spans.size();i+=2){JsonNode titleNode=spans.get(i),pageNode=spans.get(i+1);String title=titleNode.path("text").asText("").strip(),page=pageNode.path("text").asText("").strip();if(title.isEmpty()||isPageNumber(title)||!isPageNumber(page)||hasLocation(titleNode.path("location"))||!hasLocation(pageNode.path("location")))continue;lines.add(title+" "+page);covered+=2;}if(lines.size()<3||nonEmpty==0||covered<Math.ceil(nonEmpty*.7))return null;return String.join("\n",lines);}
    private static boolean hasLocation(JsonNode location){return location.isArray()&&!location.isEmpty();}
    private static boolean isPageNumber(String value){return value.matches("[0-9]{1,5}")||value.matches("[〇零一二三四五六七八九十百千兩两廿卅]{1,8}");}
    private static String plainTable(String html){if(html==null)return"";String without=html.replace("\\n","\n").replaceAll("(?i)</(?:td|th|tr|p)>","\n").replaceAll("(?s)<[^>]*>"," ");return HtmlUtils.htmlUnescape(without).replaceAll("[ \\t]+"," ").replaceAll(" *\n+ *","\n").strip();}
    private static String safeLabel(String value){if(value==null||value.isBlank())return"（空）";return value.replaceAll("[^A-Za-z0-9_-]","?").substring(0,Math.min(40,value.length()));}
}
