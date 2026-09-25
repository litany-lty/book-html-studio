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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@Service
public class QwenOcrClient {
    private static final int MAX_IMAGE_BYTES=10*1024*1024,MAX_RESPONSE_BYTES=8*1024*1024;
    private final AppProperties config;
    private final ObjectMapper json;
    private final ManagedTransport transport;
    private PhysicalCallService calls;
    private final boolean managed;
    @Autowired public QwenOcrClient(AppProperties config,ObjectMapper json,PhysicalCallService calls) {
        this.config=config;this.json=json;this.calls=Objects.requireNonNull(calls);this.transport=calls.synchronousTransport();managed=true;
    }
    /** Test-only injection: never constructs an unbounded live HTTP client. */
    QwenOcrClient(AppProperties config,ObjectMapper json,Transport fixture) {
        this.config=config;this.json=json;managed=false;
        calls=new PhysicalCallService(new ProviderResourceRegistry(),new AttemptCallBudgetStore(),new DelayedCallQueue(),json);
        transport=(request,remaining,max,stop)->{
            try {
                var response=fixture.send(request);if(response==null)throw new java.io.IOException("fixture response absent");
                var headers=response.headers();var retry=headers==null?List.<String>of():headers.allValues("Retry-After");
                return new BoundedHttp.Response(response.statusCode(),response.body()==null?new byte[0]:response.body().getBytes(StandardCharsets.UTF_8),
                        retry.size()==1?retry.get(0):retry.isEmpty()?null:"AMBIGUOUS_RETRY_AFTER");
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancelledException();}
            catch(CancelledException e){throw e;}catch(Exception e){throw new java.io.IOException("fixture transport failed");}
        };
    }
    /** Compatibility for isolated pool fixtures; production constructor already owns the shared pool. */
    public void setResourceRegistry(ProviderResourceRegistry resources) {
        if(managed){if(calls.resources()!=resources)throw new IllegalStateException("registry mismatch");return;}
        calls.close();calls=new PhysicalCallService(resources,new AttemptCallBudgetStore(),new DelayedCallQueue(),json);
    }
    public boolean configured(){return !config.dashscopeApiKey().isBlank()&&!config.qwenModel().isBlank();}
    private void validateImage(byte[] png,int width,int height) {
        if(!configured())throw new ApiException(HttpStatus.BAD_REQUEST,"Qwen OCR 尚未配置 DASHSCOPE_API_KEY");
        if(png==null || png.length==0 || png.length>MAX_IMAGE_BYTES)throw new ApiException(HttpStatus.BAD_REQUEST,"Qwen送识图片为空或超过10MB限制");
        if(width<1 || height<1 || (long)width*height>8_388_608L)throw new ApiException(HttpStatus.BAD_REQUEST,"Qwen送识尺寸无效或超过800万像素限制");
    }
    private HttpRequest request(byte[] png) throws Exception {
        Map<String,Object> image=Map.of("image","data:image/png;base64,"+Base64.getEncoder().encodeToString(png),"min_pixels",3072,"max_pixels",8388608,"enable_rotate",false);
        Map<String,Object> body=Map.of("model",config.qwenModel(),"input",Map.of("messages",List.of(Map.of("role","user","content",List.of(image)))),"parameters",Map.of("ocr_options",Map.of("task","advanced_recognition")));
        return HttpRequest.newBuilder(URI.create(config.qwenBaseUrl().replaceAll("/+$","")+"/services/aigc/multimodal-generation/generation"))
                .header("Authorization","Bearer "+config.dashscopeApiKey()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
    }
    /** Relative budget includes admission, request construction and the complete body. No hidden retry. */
    public List<Block> recognizeBounded(byte[] png,int width,int height,String layout,long timeoutNanos,int maxBytes,
                                      BooleanSupplier cancelled,studio.bookhtml.decision.DecisionTransport injected) throws OcrException {
        validateImage(png,width,height);Objects.requireNonNull(injected);
        return calls.invokeSynchronous(PhysicalCallService.SynchronousPurpose.QWEN_CROP_OCR,config.qwenModel(),timeoutNanos,
                Math.min(maxBytes,MAX_RESPONSE_BYTES),()->request(png),injected::send,cancelled,root->parse(root,width,height,layout));
    }
    public List<Block> recognize(byte[] png,int width,int height,String layout,BooleanSupplier cancelled) throws OcrException {
        validateImage(png,width,height);
        return calls.invokeSynchronous(PhysicalCallService.SynchronousPurpose.QWEN_OCR,config.qwenModel(),
                TimeUnit.SECONDS.toNanos(Math.max(1,Math.min(600,config.qwenTimeoutSeconds()))),MAX_RESPONSE_BYTES,
                ()->request(png),transport,cancelled,root->parse(root,width,height,layout));
    }
    List<Block> parse(String body,int width,int height,String layout)throws OcrException {return parse(ModelCompletion.read(json,body),width,height,layout);}
    private List<Block> parse(JsonNode root,int width,int height,String requestedLayout)throws OcrException {
        try {
            JsonNode choice=ModelCompletion.singleChoice(root,true);
            if(root.hasNonNull("code") && !"200".equals(root.path("code").asText()))throw new OcrException("Qwen OCR 返回业务错误");
            JsonNode content=choice.at("/message/content");if(!content.isArray())throw new OcrException("Qwen OCR 返回内容无效");
            List<JsonNode> groups=new ArrayList<>();findFields(content,"words_info",groups);
            if(groups.isEmpty())throw new OcrException("Qwen OCR 返回缺少 words_info");
            List<RawLine> lines=new ArrayList<>();int seq=0;
            for(JsonNode words:groups) {
                if(!words.isArray())throw new OcrException("Qwen OCR 字段类型无效");
                for(JsonNode word:words) {
                    if(!word.isObject() || !word.path("text").isTextual())throw new OcrException("Qwen OCR 文字类型无效");
                    String text=word.path("text").textValue().strip();if(text.isEmpty())continue;
                    double[] raw=flatten(word.has("location")?word.get("location"):word.get("rotate_rect"));
                    lines.add(new RawLine("qwen-line-"+(++seq),text,normalize(raw,width,height),raw));
                    if(lines.size()>10000)throw new OcrException("Qwen OCR 文字行数超过上限");
                }
            }
            if(lines.isEmpty()) {
                if(groups.stream().allMatch(JsonNode::isEmpty))throw new OcrNoTextException("Qwen OCR 未检出文字，不代表图像为空白");
                throw new OcrException("Qwen OCR 未返回可用文字坐标");
            }
            String mode=chooseMode(lines,requestedLayout);
            lines.sort("vertical-rl".equals(mode)?Comparator.<RawLine>comparingDouble(x->-x.bbox[0]).thenComparingDouble(x->x.bbox[1]):Comparator.<RawLine>comparingDouble(x->x.bbox[1]).thenComparingDouble(x->x.bbox[0]));
            List<Block> result=new ArrayList<>();int index=0;
            for(RawLine line:lines){String id="qwen-line-"+(index+1);result.add(new Block(id,"text",index++,line.bbox,mode,line.text,line.text,null,true,false,null,"qwen",List.of(id),null,line.raw));}
            BlockValidator.validate(result);return result;
        }catch(OcrException safe){throw safe;}catch(Exception invalid){throw new OcrException("Qwen OCR 返回JSON或坐标无效");}
    }
    private static void findFields(JsonNode node,String field,List<JsonNode>found){if(node==null)return;if(node.has(field))found.add(node.get(field));if(node.isContainerNode())for(JsonNode child:node)findFields(child,field,found);}
    private static double[] flatten(JsonNode node)throws OcrException{if(node==null)return new double[0];List<Double>values=new ArrayList<>();flattenInto(node,values);return values.stream().mapToDouble(Double::doubleValue).toArray();}
    private static void flattenInto(JsonNode node,List<Double>out)throws OcrException{
        if(node==null)throw new OcrException("OCR坐标缺失");
        if(out.size()>64)throw new OcrException("OCR坐标数量过多");
        if(node.isNumber() && Double.isFinite(node.asDouble()))out.add(node.asDouble());
        else if(node.isArray())for(JsonNode child:node)flattenInto(child,out);
        else throw new OcrException("OCR坐标包含无效值");
    }
    static double[] normalize(double[] raw,int width,int height)throws OcrException{if(width<1||height<1||raw==null||raw.length>64||Arrays.stream(raw).anyMatch(v->!Double.isFinite(v)))throw new OcrException("OCR尺寸或坐标无效");double minX,minY,maxX,maxY;if(raw.length==5){if(raw[2]<=0||raw[3]<=0)throw new OcrException("OCR旋转框尺寸无效");double cx=raw[0],cy=raw[1],w=raw[2],h=raw[3],a=Math.toRadians(raw[4]);double ex=Math.abs(Math.cos(a))*w/2+Math.abs(Math.sin(a))*h/2,ey=Math.abs(Math.sin(a))*w/2+Math.abs(Math.cos(a))*h/2;minX=cx-ex;maxX=cx+ex;minY=cy-ey;maxY=cy+ey;}else if(raw.length>=8&&raw.length%2==0){minX=Double.MAX_VALUE;minY=Double.MAX_VALUE;maxX=0;maxY=0;for(int i=0;i<raw.length;i+=2){minX=Math.min(minX,raw[i]);maxX=Math.max(maxX,raw[i]);minY=Math.min(minY,raw[i+1]);maxY=Math.max(maxY,raw[i+1]);}}else throw new OcrException("Qwen OCR 文字坐标格式无效");minX=Math.max(0,minX);minY=Math.max(0,minY);maxX=Math.min(width,maxX);maxY=Math.min(height,maxY);double[]box={minX/width,minY/height,(maxX-minX)/width,(maxY-minY)/height};try{BlockValidator.validateBbox(box);}catch(ApiException e){throw new OcrException("Qwen OCR 文字坐标越界",e);}return box;}
    private static String chooseMode(List<RawLine>lines,String requested){if("vertical".equals(requested))return"vertical-rl";if("horizontal".equals(requested))return"horizontal-tb";return lines.stream().filter(x->x.bbox[3]>x.bbox[2]*1.5).count()>lines.size()/2?"vertical-rl":"horizontal-tb";}

    record RawLine(String id,String text,double[] bbox,double[] raw){}
    @FunctionalInterface interface Transport{HttpResponse<String>send(HttpRequest request)throws Exception;}
}
