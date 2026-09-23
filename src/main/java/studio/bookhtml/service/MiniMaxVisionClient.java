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
public class MiniMaxVisionClient {
    private static final int MAX_IMAGE_BYTES=10*1024*1024;
    private final AppProperties config;private final ObjectMapper json;private final Transport transport;
    private ProviderResourceRegistry resources;
    private UsageLedger usage;
    private CloudConsentService consentService;

    @Autowired public MiniMaxVisionClient(AppProperties config,ObjectMapper json){this(config,json,request->HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().send(request,HttpResponse.BodyHandlers.ofString()));}
    MiniMaxVisionClient(AppProperties config,ObjectMapper json,Transport transport){this.config=config;this.json=json;this.transport=transport;}
    @Autowired(required=false) public void setResourceRegistry(ProviderResourceRegistry resources){this.resources=resources;}
    @Autowired(required=false) public void setUsageLedger(UsageLedger usage){this.usage=usage;}
    @Autowired(required=false) public void setConsentService(CloudConsentService consentService){this.consentService=consentService;}
    public boolean configured(){return config.minimaxAssistEnabled()&&!config.minimaxApiKey().isBlank()&&"MiniMax-M3".equals(config.minimaxModel());}
    public List<Block> assist(byte[] png,List<Block> qwenLines,String layout,BooleanSupplier cancelled)throws OcrException{
        if(!configured())throw new ApiException(HttpStatus.BAD_REQUEST,"MiniMax 辅助尚未配置");if(png.length>MAX_IMAGE_BYTES)throw new ApiException(HttpStatus.BAD_REQUEST,"送识图片超过 MiniMax 10MB 限制");
        UsageContext.Value ctx = UsageContext.current();
        if(consentService!=null && ctx!=null && ctx.bookId()!=null && !consentService.isCloudAuthorized(ctx.bookId(),"minimax")){
            throw new ApiException(HttpStatus.FORBIDDEN,"未获 MiniMax 服务端持久云端授权");
        }
        ProviderResourceRegistry.Permit permit = null;
        if(resources!=null){
            try{permit = resources.acquire(ProviderResourceRegistry.POOL_MINIMAX, true, Duration.ofSeconds(30), cancelled);}
            catch(InterruptedException e){Thread.currentThread().interrupt(); throw new CancelledException();}
        }
        String usageId = null;
        if(usage!=null) try{usageId = usage.start("minimax", config.minimaxModel());} catch(Exception ignored){}
        try{List<Map<String,Object>> sources=new ArrayList<>();for(int i=0;i<qwenLines.size();i++){Block b=qwenLines.get(i);sources.add(Map.of("id",b.id(),"readingOrder",i,"text",b.original(),"bbox",b.bbox()));}String prompt="你只做版面结构分析，不转录、不改写、不删除 Qwen 文字。依据图片和 sourceLines 返回严格 JSON：{\"blocks\":[{\"id\":\"b1\",\"type\":\"text|heading|figure|table|caption|page-number|formula\",\"order\":0,\"bbox\":{\"x\":0.1,\"y\":0.1,\"width\":0.2,\"height\":0.2},\"writingMode\":\"vertical-rl|horizontal-tb\",\"sourceIds\":[\"qwen-line-1\"],\"suggestion\":\"仅可选校对建议\",\"uncertain\":true}]}。figure/table/formula 的 bbox 必须是相对完整图片的 0..1 对象，width/height 是长度，绝不是右下角坐标；文字块 bbox 由服务端按 sourceIds 计算，你不得改写来源文字框。文字块必须只引用给定 ID，并让 sourceIds 严格按真实阅读顺序排列；严格依据 readingOrder 和图片判断版面，不得按 ID 数字排列。不可在 suggestion 外输出修正文。竖排双页先右页再左页，同页先右栏再左栏；不得跨插图合并段落。图表块可无 sourceIds。不得重复引用 ID。只有存在真实疑点才设置 uncertain=true 并给出 suggestion，否则 uncertain=false 且不输出 suggestion。版式偏好="+layout+"。sourceLines="+json.writeValueAsString(sources);
            Map<String,Object> body=Map.of("model",config.minimaxModel(),"max_completion_tokens",8192,"thinking",Map.of("type","disabled"),"reasoning_split",true,"messages",List.of(Map.of("role","user","content",List.of(Map.of("type","text","text",prompt),Map.of("type","image_url","image_url",Map.of("url","data:image/png;base64,"+Base64.getEncoder().encodeToString(png),"detail","high"))))));
            HttpRequest request=HttpRequest.newBuilder(URI.create(config.minimaxBaseUrl().replaceAll("/+$","")+"/chat/completions")).timeout(Duration.ofSeconds(config.minimaxTimeoutSeconds())).header("Authorization","Bearer "+config.minimaxApiKey()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            if(cancelled.getAsBoolean())throw new CancelledException();
            if(usage!=null && usageId!=null) try{usage.sending(usageId);} catch(Exception ignored){}
            HttpResponse<String>response=transport.send(request);
            if(response==null||response.statusCode()==429){
                if(usage!=null && usageId!=null) try{usage.failed(usageId);} catch(Exception ignored){}
                throw new OcrException("MiniMax 请求频率受限，请稍后重试");
            }
            if(response.statusCode()<200||response.statusCode()>=300){
                if(usage!=null && usageId!=null) try{usage.failed(usageId);} catch(Exception ignored){}
                throw new OcrException("MiniMax 辅助失败（HTTP "+response.statusCode()+"）");
            }
            JsonNode root=json.readTree(response.body());
            if(usage!=null && usageId!=null) try{usage.captureUsage(usageId, root); usage.succeeded(usageId);} catch(Exception ignored){}
            if(root.has("error")||root.path("base_resp").path("status_code").asInt(0)!=0)throw new OcrException("MiniMax 返回业务错误");
            JsonNode choice=root.at("/choices/0");
            if("length".equalsIgnoreCase(choice.path("finish_reason").asText()))throw new OcrException("MiniMax 结构输出被截断");
            JsonNode content=choice.at("/message/content");
            if(!content.isTextual())throw new OcrException("MiniMax 返回结构无效");
            return merge(stripFence(content.asText()),qwenLines);
        }catch(ApiException|CancelledException|OcrException e){
            if(usage!=null && usageId!=null) try{usage.failed(usageId);} catch(Exception ignored){}
            throw e;
        }catch(Exception e){
            if(usage!=null && usageId!=null) try{usage.unknown(usageId);} catch(Exception ignored){}
            throw new OcrException("MiniMax 辅助请求失败",e);
        }finally{
            if(permit!=null) permit.close();
        }
    }
    List<Block> merge(String value,List<Block> lines)throws OcrException{
        try{Map<String,Block>source=new LinkedHashMap<>();for(Block b:lines)source.put(b.id(),b);JsonNode nodes=json.readTree(value).get("blocks");if(nodes==null||!nodes.isArray())throw new OcrException("MiniMax 返回 JSON 缺少 blocks");List<JsonNode>ordered=new ArrayList<>();nodes.forEach(ordered::add);ordered.sort(Comparator.comparingInt(n->n.path("order").asInt(Integer.MAX_VALUE)));Set<String>used=new HashSet<>();List<Block>result=new ArrayList<>();int seq=0;
            for(JsonNode n:ordered){String type=text(n,"type",null);if(type==null||!Set.of("text","heading","figure","table","caption","page-number","formula").contains(type))continue;String mode=text(n,"writingMode",null);List<String>ids=new ArrayList<>();boolean unknownSource=false;if(n.has("sourceIds")&&n.get("sourceIds").isArray())for(JsonNode v:n.get("sourceIds")){String sid=v.asText();if(!source.containsKey(sid)){unknownSource=true;continue;}if(!ids.contains(sid))ids.add(sid);}boolean figure=Set.of("figure","table","formula").contains(type);if(ids.isEmpty()&&!figure)continue;boolean duplicate=ids.stream().anyMatch(used::contains);ids.removeIf(used::contains);if(ids.isEmpty()&&!figure)continue;if(mode==null||!Set.of("vertical-rl","horizontal-tb").contains(mode))mode=ids.isEmpty()?"horizontal-tb":source.get(ids.get(0)).writingMode();String suggestion=text(n,"suggestion",null);boolean bboxFallback=false;double[]bbox;if(!figure){bbox=union(ids,source,0);}else{bbox=parseBbox(n.get("bbox"));if(bbox==null){if(ids.isEmpty())continue;bbox=union(ids,source,.008);bboxFallback=true;}}used.addAll(ids);String original=ids.stream().map(x->source.get(x).original()).reduce("",(a,b)->a+b);boolean explicitUncertain=n.has("uncertain")&&n.get("uncertain").asBoolean();boolean uncertain=duplicate||unknownSource||bboxFallback||explicitUncertain;if(bboxFallback)suggestion=append(suggestion,"MiniMax 图框无效，已使用 OCR 来源框小幅扩边");if(unknownSource)suggestion=append(suggestion,"MiniMax 引用了未知来源行，已忽略该引用");if(uncertain&&(suggestion==null||suggestion.isBlank()))suggestion="MiniMax 标记该区域需复核";result.add(new Block("assist-"+seq,type,seq++,bbox,mode,original,original,null,uncertain,false,null,"qwen+minimax",List.copyOf(ids),suggestion,null));}
            for(Block line:lines)if(!used.contains(line.id()))result.add(new Block("unplaced-"+line.id(),"text",seq++,line.bbox(),line.writingMode(),line.original(),line.original(),line.confidence(),true,false,null,"qwen+minimax",List.of(line.id()),"辅助模型未放置此行，已自动补回",line.sourceRect()));BlockValidator.validate(result);return result;
        }catch(OcrException e){throw e;}catch(Exception e){throw new OcrException("MiniMax 返回 JSON 或引用关系无效",e);}
    }
    private static double[]union(List<String>ids,Map<String,Block>source,double pad){double x=1,y=1,r=0,b=0;for(String id:ids){double[]v=source.get(id).bbox();x=Math.min(x,v[0]);y=Math.min(y,v[1]);r=Math.max(r,v[0]+v[2]);b=Math.max(b,v[1]+v[3]);}x=Math.max(0,x-pad);y=Math.max(0,y-pad);r=Math.min(1,r+pad);b=Math.min(1,b+pad);return new double[]{x,y,r-x,b-y};}
    private double[]parseBbox(JsonNode node){if(node==null||!node.isObject())return null;try{double[]box=new double[]{node.path("x").asDouble(Double.NaN),node.path("y").asDouble(Double.NaN),node.path("width").asDouble(Double.NaN),node.path("height").asDouble(Double.NaN)};BlockValidator.validateBbox(box);return box;}catch(Exception e){return null;}}
    private static String append(String current,String value){return current==null||current.isBlank()?value:current+"；"+value;}
    static String stripFence(String s){String v=s.strip();if(v.startsWith("```")){int first=v.indexOf('\n'),last=v.lastIndexOf("```");if(first>=0&&last>first)v=v.substring(first+1,last).strip();}return v;}
    private static String text(JsonNode n,String key,String fallback){JsonNode v=n.get(key);return v!=null&&v.isTextual()?v.asText():fallback;}
    @FunctionalInterface interface Transport{HttpResponse<String>send(HttpRequest request)throws Exception;}
}
