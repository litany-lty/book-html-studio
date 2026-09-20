package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

@Service
public class QwenOcrClient {
    private static final int MAX_IMAGE_BYTES=10*1024*1024;
    private final AppProperties config; private final ObjectMapper json; private final Transport transport;
    @Autowired public QwenOcrClient(AppProperties config,ObjectMapper json){this(config,json,request->HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(request,HttpResponse.BodyHandlers.ofString()));}
    QwenOcrClient(AppProperties config,ObjectMapper json,Transport transport){this.config=config;this.json=json;this.transport=transport;}
    public boolean configured(){return !config.dashscopeApiKey().isBlank()&&!config.qwenModel().isBlank();}
    public List<Block> recognize(byte[] png,int imageWidth,int imageHeight,String layout,BooleanSupplier cancelled)throws OcrException{
        if(!configured())throw new ApiException(HttpStatus.BAD_REQUEST,"Qwen OCR 尚未配置 DASHSCOPE_API_KEY");
        if(png.length>MAX_IMAGE_BYTES)throw new ApiException(HttpStatus.BAD_REQUEST,"送识图片超过 Qwen 10MB 限制");
        if((long)imageWidth*imageHeight>8_388_608L)throw new ApiException(HttpStatus.BAD_REQUEST,"送识图片超过 Qwen 800 万像素限制");
        try{
            Map<String,Object> image=Map.of("image","data:image/png;base64,"+Base64.getEncoder().encodeToString(png),"min_pixels",3072,"max_pixels",8388608,"enable_rotate",false);
            Map<String,Object> body=Map.of("model",config.qwenModel(),"input",Map.of("messages",List.of(Map.of("role","user","content",List.of(image)))),"parameters",Map.of("ocr_options",Map.of("task","advanced_recognition")));
            HttpRequest request=HttpRequest.newBuilder(URI.create(config.qwenBaseUrl().replaceAll("/+$","")+"/services/aigc/multimodal-generation/generation")).timeout(Duration.ofSeconds(config.qwenTimeoutSeconds())).header("Authorization","Bearer "+config.dashscopeApiKey()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> response=null;for(int attempt=0;attempt<3;attempt++){if(cancelled.getAsBoolean())throw new CancelledException();response=transport.send(request);if(response.statusCode()!=429)break;backoff(attempt,cancelled);}
            if(response==null||response.statusCode()==429)throw new OcrException("Qwen OCR 请求频率受限，请稍后重试");if(response.statusCode()<200||response.statusCode()>=300)throw new OcrException("Qwen OCR 失败（HTTP "+response.statusCode()+"）");return parse(response.body(),imageWidth,imageHeight,layout);
        }catch(ApiException|CancelledException|OcrException e){throw e;}catch(Exception e){throw new OcrException("Qwen OCR 请求失败",e);}
    }
    List<Block> parse(String body,int width,int height,String requestedLayout)throws OcrException{
        try{JsonNode root=json.readTree(body);JsonNode choice=root.at("/output/choices/0");if("length".equalsIgnoreCase(choice.path("finish_reason").asText()))throw new OcrException("Qwen OCR 输出被截断");List<JsonNode>wordGroups=new ArrayList<>();findFields(choice,"words_info",wordGroups);if(wordGroups.isEmpty())throw new OcrException("Qwen OCR 返回缺少 words_info");List<RawLine>lines=new ArrayList<>();int seq=0;
            for(JsonNode words:wordGroups){if(!words.isArray())continue;for(JsonNode word:words){String text=word.path("text").asText("").strip();if(text.isEmpty())continue;double[] raw=flatten(word.has("location")?word.get("location"):word.get("rotate_rect"));double[] box=normalize(raw,width,height);lines.add(new RawLine("qwen-line-"+(++seq),text,box,raw));}}
            if(lines.isEmpty())throw new OcrException("Qwen OCR 未返回可用文字坐标");String mode=chooseMode(lines,requestedLayout);Comparator<RawLine>order="vertical-rl".equals(mode)?Comparator.<RawLine>comparingDouble(x->-x.bbox[0]).thenComparingDouble(x->x.bbox[1]):Comparator.<RawLine>comparingDouble(x->x.bbox[1]).thenComparingDouble(x->x.bbox[0]);lines.sort(order);List<Block>result=new ArrayList<>();int index=0;for(RawLine l:lines){String id="qwen-line-"+(index+1);result.add(new Block(id,"text",index++,l.bbox,mode,l.text,l.text,null,true,false,null,"qwen",List.of(id),null,l.raw));}BlockValidator.validate(result);return result;
        }catch(OcrException e){throw e;}catch(Exception e){throw new OcrException("Qwen OCR 返回 JSON 或坐标无效",e);}
    }
    private static void findFields(JsonNode node,String field,List<JsonNode>found){if(node==null)return;if(node.has(field))found.add(node.get(field));if(node.isContainerNode())for(JsonNode child:node)findFields(child,field,found);}
    private static double[] flatten(JsonNode node){if(node==null)return new double[0];List<Double>values=new ArrayList<>();flattenInto(node,values);return values.stream().mapToDouble(Double::doubleValue).toArray();}
    private static void flattenInto(JsonNode node,List<Double>out){if(node.isNumber())out.add(node.asDouble());else if(node.isArray())for(JsonNode child:node)flattenInto(child,out);}
    static double[] normalize(double[] raw,int width,int height)throws OcrException{double minX,minY,maxX,maxY;if(raw.length==5){double cx=raw[0],cy=raw[1],w=raw[2],h=raw[3],a=Math.toRadians(raw[4]);double ex=Math.abs(Math.cos(a))*w/2+Math.abs(Math.sin(a))*h/2,ey=Math.abs(Math.sin(a))*w/2+Math.abs(Math.cos(a))*h/2;minX=cx-ex;maxX=cx+ex;minY=cy-ey;maxY=cy+ey;}else if(raw.length>=8&&raw.length%2==0){minX=Double.MAX_VALUE;minY=Double.MAX_VALUE;maxX=0;maxY=0;for(int i=0;i<raw.length;i+=2){minX=Math.min(minX,raw[i]);maxX=Math.max(maxX,raw[i]);minY=Math.min(minY,raw[i+1]);maxY=Math.max(maxY,raw[i+1]);}}else throw new OcrException("Qwen OCR 文字坐标格式无效");minX=Math.max(0,minX);minY=Math.max(0,minY);maxX=Math.min(width,maxX);maxY=Math.min(height,maxY);double[]box={minX/width,minY/height,(maxX-minX)/width,(maxY-minY)/height};try{BlockValidator.validateBbox(box);}catch(ApiException e){throw new OcrException("Qwen OCR 文字坐标越界",e);}return box;}
    private static String chooseMode(List<RawLine>lines,String requested){if("vertical".equals(requested))return"vertical-rl";if("horizontal".equals(requested))return"horizontal-tb";return lines.stream().filter(x->x.bbox[3]>x.bbox[2]*1.5).count()>lines.size()/2?"vertical-rl":"horizontal-tb";}
    private static void backoff(int attempt,BooleanSupplier cancelled){long until=System.nanoTime()+(250L*(1L<<attempt))*1_000_000L;while(System.nanoTime()<until){if(cancelled.getAsBoolean())throw new CancelledException();try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancelledException();}}}
    record RawLine(String id,String text,double[]bbox,double[]raw){}
    @FunctionalInterface interface Transport{HttpResponse<String>send(HttpRequest request)throws Exception;}
}
