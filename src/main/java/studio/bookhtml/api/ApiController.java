package studio.bookhtml.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.PpOcrProperties;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.service.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final BookService books;private final JobService jobs;private final ExportService export;private final AppProperties config;private final QwenOcrClient qwen;private final MiniMaxVisionClient miniMax;private final PaddleOcrClient paddle;private final QwenLayoutClient qwenAssist;private final QwenAssistProperties qwenAssistConfig;private final IssueImageService issueImages;private final ObjectMapper json;
    private final PaddleAiStudioClient aiStudio;private final PaddleAiStudioProperties aiStudioConfig;
    private final BaiduPpOcrClient ppocr;private final PpOcrProperties ppocrConfig;
    public ApiController(BookService books,JobService jobs,ExportService export,AppProperties config,QwenOcrClient qwen,MiniMaxVisionClient miniMax,PaddleOcrClient paddle,QwenLayoutClient qwenAssist,QwenAssistProperties qwenAssistConfig,IssueImageService issueImages,ObjectMapper json,PaddleAiStudioClient aiStudio,PaddleAiStudioProperties aiStudioConfig,BaiduPpOcrClient ppocr,PpOcrProperties ppocrConfig){this.books=books;this.jobs=jobs;this.export=export;this.config=config;this.qwen=qwen;this.miniMax=miniMax;this.paddle=paddle;this.qwenAssist=qwenAssist;this.qwenAssistConfig=qwenAssistConfig;this.issueImages=issueImages;this.json=json;this.aiStudio=aiStudio;this.aiStudioConfig=aiStudioConfig;this.ppocr=ppocr;this.ppocrConfig=ppocrConfig;}
    @GetMapping("/config") public Map<String,Object> config(){
        String baiduLabel="PaddleOCR-VL · 百度智能云（AK/SK）",studioLabel="PaddleOCR-VL · AI Studio（Token）",ppocrLabel="PP-OCRv6 · 百度智能云（AK/SK）";
        String baiduQuota="百度智能云 AK/SK 所属账户的 OCR 额度",studioQuota="AI Studio Access Token 所属账户的服务额度",ppocrQuota="百度智能云 AK/SK 所属账户的 PP-OCRv6 额度（与 PaddleOCR-VL 独立结算）";
        return Map.of("defaultProvider","paddle","providers",List.of(
                Map.of("id","paddle","label",baiduLabel,"available",paddle.configured(),"reason",paddle.configured()?"已配置百度智能云通道":"缺少 BAIDU_OCR_API_KEY 或 BAIDU_OCR_SECRET_KEY","quotaSource",baiduQuota),
                Map.of("id","paddle-aistudio","label",studioLabel,"available",aiStudio.configured(),"reason",aiStudio.configured()?"已配置 AI Studio 通道":"缺少 PADDLEOCR_ACCESS_TOKEN","quotaSource",studioQuota),
                Map.of("id","ppocr","label",ppocrLabel,"available",ppocr.configured(),"reason",ppocr.configured()?"已配置百度智能云 PP-OCRv6 通道":"缺少 BAIDU_OCR_API_KEY 或 BAIDU_OCR_SECRET_KEY","quotaSource",ppocrQuota),
                Map.of("id","qwen","label","旧 Qwen OCR 手动兼容通道","available",qwen.configured(),"reason",qwen.configured()?"已配置":"缺少 DASHSCOPE_API_KEY","quotaSource","阿里云百炼账户额度"),
                Map.of("id","local","label","本地 Tesseract（离线初稿）","available",true,"reason","复杂图表和手写需人工校对","quotaSource","本地处理，不消耗云 OCR 额度")),
                "ocrChannels",List.of(
                        Map.of("id","paddle","label",baiduLabel,"configured",paddle.configured(),"model",config.paddleModel(),"endpoint",safeEndpoint(config.paddleJobUrl()),"credentialType","API Key + Secret Key","quotaSource",baiduQuota),
                        Map.of("id","paddle-aistudio","label",studioLabel,"configured",aiStudio.configured(),"model",aiStudioConfig.model(),"endpoint",safeEndpoint(aiStudioConfig.jobUrl()),"credentialType","Access Token","quotaSource",studioQuota),
                        Map.of("id","ppocr","label",ppocrLabel,"configured",ppocr.configured(),"model","PP-OCRv6","endpoint",safeEndpoint(ppocrConfig.url()),"credentialType","API Key + Secret Key","quotaSource",ppocrQuota)),
                "paddle",Map.of("configured",paddle.configured(),"model",config.paddleModel(),"jobUrl",safeEndpoint(config.paddleJobUrl())),
                "qwenAssist",Map.of("configured",qwenAssist.configured(),"model",qwenAssistConfig.getModel(),"assistEnabled",qwenAssistConfig.isEnabled()),
                "qwen",Map.of("configured",qwen.configured(),"model",config.qwenModel(),"baseUrl",safeEndpoint(config.qwenBaseUrl())),
                "minimax",Map.of("configured",miniMax.configured(),"model",config.minimaxModel(),"baseUrl",safeEndpoint(config.minimaxBaseUrl()),"assistEnabled",config.minimaxAssistEnabled()),"maxUploadMb",config.maxUploadMb());
    }
    private static String safeEndpoint(String value){try{java.net.URI uri=java.net.URI.create(value);if(uri.getHost()==null||!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())))return "";return new java.net.URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),uri.getPath(),null,null).toString();}catch(Exception ignored){return "";}}
    @GetMapping("/books") public List<Book> list(){return books.list();}
    @PostMapping(value="/books",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Book upload(@RequestPart("file")MultipartFile file){return books.upload(file);}
    @GetMapping("/books/{id}") public Book book(@PathVariable String id){return books.get(id);}
    @GetMapping("/books/{id}/pages") public List<PageSummary> pages(@PathVariable String id){return books.pages(id);}
    @GetMapping("/books/{id}/outline") public List<OutlineService.OutlineEntry> outline(@PathVariable String id){return books.outline(id);}
    @GetMapping("/books/{id}/pages/{n}") public Map<String,Object> page(@PathVariable String id,@PathVariable int n){return pagePayload(id,books.page(id,n));}
    @GetMapping(value="/books/{id}/pages/{n}/image",produces=MediaType.IMAGE_PNG_VALUE) public byte[] image(@PathVariable String id,@PathVariable int n,@RequestParam(defaultValue="1800")@Min(196) int width){return books.image(id,n,width);}
    @GetMapping(value="/books/{id}/pages/{n}/figures/{blockId}",produces=MediaType.IMAGE_PNG_VALUE) public byte[] figure(@PathVariable String id,@PathVariable int n,@PathVariable String blockId){return books.figure(id,n,blockId);}
    @GetMapping(value="/books/{id}/pages/{n}/issues/{issueId}/image",produces=MediaType.IMAGE_PNG_VALUE) public byte[] issueImage(@PathVariable String id,@PathVariable int n,@PathVariable String issueId,@RequestParam(defaultValue="false")boolean context){Page page=books.page(id,n);IssueImageService.Snippet snippet=issueImages.locateOne(id,page,issueId);if(snippet==null)throw new ApiException(HttpStatus.NOT_FOUND,"未找到该疑点原图");return context?snippet.contextPng():snippet.png();}
    @GetMapping("/books/{id}/pages/{n}/issues/{issueId}") public Map<String,Object> issueMetadata(@PathVariable String id,@PathVariable int n,@PathVariable String issueId){Page page=books.page(id,n);IssueImageService.Snippet snippet=issueImages.locateOne(id,page,issueId);if(snippet==null)throw new ApiException(HttpStatus.NOT_FOUND,"未找到该疑点原图");String src="/api/books/"+UriUtils.encodePathSegment(id,StandardCharsets.UTF_8)+"/pages/"+page.pageNumber()+"/issues/"+UriUtils.encodePathSegment(issueId,StandardCharsets.UTF_8)+"/image";LinkedHashMap<String,Object> result=new LinkedHashMap<>();result.put("mode",snippet.mode());result.put("glyphCount",snippet.glyphCount());result.put("bbox",snippet.bbox());result.put("boxes",snippet.boxes());result.put("src",src);result.put("contextBbox",snippet.contextBbox());result.put("contextSrc",src+"?context=true");result.put("pending",false);return result;}
    @PostMapping("/books/{id}/jobs") public Job job(@PathVariable String id,@Valid@RequestBody JobRequest request){return jobs.submit(id,request);}
    @GetMapping("/books/{id}/job") public Job job(@PathVariable String id){return jobs.get(id);}
    @PostMapping("/books/{id}/job/cancel") public Job cancel(@PathVariable String id){return jobs.cancel(id);}
    @PutMapping("/books/{id}/pages/{n}") public Map<String,Object> update(@PathVariable String id,@PathVariable int n,@Valid@RequestBody PageUpdateRequest request){return pagePayload(id,books.update(id,n,request));}
    @GetMapping("/books/{id}/pages/{n}/revisions") public Map<String,Object> revisions(@PathVariable String id,@PathVariable int n){return Map.of("revisions",books.revisions(id,n));}
    @PostMapping("/books/{id}/pages/{n}/revert") public Map<String,Object> revert(@PathVariable String id,@PathVariable int n,@RequestBody Map<String,Object> body){Object rev=body==null?null:body.get("revision");if(!(rev instanceof Number))throw new ApiException(HttpStatus.BAD_REQUEST,"缺少 revision");Object expected=body==null?null:body.get("expectedRevision");if(!(expected instanceof Number))throw new ApiException(HttpStatus.BAD_REQUEST,"缺少 expectedRevision，请刷新后重试");return pagePayload(id,books.revert(id,n,((Number)rev).intValue(),((Number)expected).intValue()));}
    @GetMapping("/books/{id}/search") public List<Map<String,Object>> search(@PathVariable String id,@RequestParam String q){return books.search(id,q);}
    @GetMapping(value="/books/{id}/export",produces="application/zip") public void export(@PathVariable String id,@RequestParam(required=false)String pages,HttpServletResponse response)throws IOException{Book book=books.get(id);String ascii="book-"+book.id()+".zip";response.setHeader(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+ascii+"\"; filename*=UTF-8''"+java.net.URLEncoder.encode(book.title()+".zip",StandardCharsets.UTF_8).replace("+","%20"));export.writeZip(id,response.getOutputStream(),pages);}
    private Map<String,Object> pagePayload(String bookId,Page page){Page readingPage=ReadingStructureNormalizer.normalize(page);LinkedHashMap<String,Object>payload=json.convertValue(readingPage,new TypeReference<>(){});LinkedHashMap<String,Object>metadata=new LinkedHashMap<>();
        // 阶段2：正文读取只返回轻量疑点索引与证据状态，不触发高清渲染；点击疑字后按需请求精确证据
        issueImages.summarize(page).forEach((issueId,snippet)->{String src="/api/books/"+UriUtils.encodePathSegment(bookId,StandardCharsets.UTF_8)+"/pages/"+page.pageNumber()+"/issues/"+UriUtils.encodePathSegment(issueId,StandardCharsets.UTF_8)+"/image";metadata.put(issueId,Map.of("mode",snippet.mode(),"glyphCount",snippet.glyphCount(),"bbox",snippet.bbox(),"boxes",snippet.boxes(),"src",src,"contextBbox",snippet.contextBbox(),"contextSrc",src+"?context=true","pending",true));});payload.put("issueImages",metadata);return payload;}
}
