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
import studio.bookhtml.config.SettingsService;
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
    private SettingsService settings;
    public ApiController(BookService books,JobService jobs,ExportService export,AppProperties config,QwenOcrClient qwen,MiniMaxVisionClient miniMax,PaddleOcrClient paddle,QwenLayoutClient qwenAssist,QwenAssistProperties qwenAssistConfig,IssueImageService issueImages,ObjectMapper json,PaddleAiStudioClient aiStudio,PaddleAiStudioProperties aiStudioConfig,BaiduPpOcrClient ppocr,PpOcrProperties ppocrConfig){this.books=books;this.jobs=jobs;this.export=export;this.config=config;this.qwen=qwen;this.miniMax=miniMax;this.paddle=paddle;this.qwenAssist=qwenAssist;this.qwenAssistConfig=qwenAssistConfig;this.issueImages=issueImages;this.json=json;this.aiStudio=aiStudio;this.aiStudioConfig=aiStudioConfig;this.ppocr=ppocr;this.ppocrConfig=ppocrConfig;}
    @org.springframework.beans.factory.annotation.Autowired public void setSettings(SettingsService settings){this.settings=settings;}
    private BookPresentationService presentation;
    /** U3：投影注入后页面载荷附带只读展示投影；未注入走旧适配器（保守兼容）。 */
    @org.springframework.beans.factory.annotation.Autowired(required=false) public void setPresentation(BookPresentationService presentation){this.presentation=presentation;}
    @GetMapping("/config") public Map<String,Object> config(){
        SettingsService.State s=settings==null?null:settings.state();
        boolean studio=s==null?aiStudio.configured():!s.paddleAccessToken().isBlank();
        boolean baidu=s==null?ppocr.configured():!s.ppocrApiKey().isBlank()&&!s.ppocrSecretKey().isBlank();
        String defaultProvider=s==null?"paddle-aistudio":s.defaultProvider();
        boolean fallback=s!=null&&s.fallbackEnabled();
        return Map.of("defaultProvider",defaultProvider,"fallbackEnabled",fallback,"providers",List.of(
                Map.of("id","paddle-aistudio","label","PaddleOCR-VL-1.6 · 飞桨 AI Studio","available",studio,"reason",studio?"已配置":"缺少 Access Token"),
                Map.of("id","ppocr","label","PP-OCRv6 · 百度智能云","available",baidu,"reason",baidu?"已配置":"缺少 API Key 或 Secret Key")),
                "ocrChannels",List.of(
                        Map.of("id","paddle-aistudio","configured",studio,"model","PaddleOCR-VL-1.6","endpoint","https://paddleocr.aistudio-app.com/api/v2/ocr/jobs","credentialType","Access Token"),
                        Map.of("id","ppocr","configured",baidu,"model","PP-OCRv6","endpoint","https://aip.baidubce.com/rest/2.0/ocr/v1/pp_ocrv5","credentialType","API Key + Secret Key")),
                "qwenAssist",Map.of("configured",qwenAssist.configured(),"model",qwenAssistConfig.getModel(),"assistEnabled",qwenAssistConfig.isEnabled()),
                "maxUploadMb",config.maxUploadMb(),
                "capabilities",Map.of("schemaVersion",2,"presentationV2",presentation!=null));
    }
    private static String safeEndpoint(String value){try{java.net.URI uri=java.net.URI.create(value);if(uri.getHost()==null||!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme())))return "";return new java.net.URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),uri.getPath(),null,null).toString();}catch(Exception ignored){return "";}}
    @GetMapping("/books") public List<Book> list(){return books.list();}
    @GetMapping(value="/books",params="reading=true") public List<Book> readingList(){return books.readingLibrary();}
    @GetMapping(value="/books/{id}",params="reading=true") public Book readingBook(@PathVariable String id){return books.readingMetadata(id);}
    @GetMapping(value="/books/{id}/pages/{n}",params="reading=true") public Map<String,Object> readingPage(@PathVariable String id,@PathVariable int n){return pagePayload(id,books.page(id,n),true);}
    @PostMapping(value="/books",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Book upload(@RequestPart("file")MultipartFile file){return books.upload(file);}
    @GetMapping("/books/{id}") public Book book(@PathVariable String id){return books.get(id);}
    @PatchMapping("/books/{id}/library") public Book updateLibrary(@PathVariable String id,@RequestBody LibraryUpdateRequest request){return jobs.updateLibrary(id,request.title(),request.archived());}
    @GetMapping("/books/{id}/pages") public List<PageSummary> pages(@PathVariable String id){return books.pages(id);}
    @GetMapping("/books/{id}/outline") public List<OutlineService.OutlineEntry> outline(@PathVariable String id){return books.outline(id);}
    /**
     * U3：目录 v2 对象（schemaVersion/bookId/outlineRevision/profileRevision/entries）。
     * 缺省与 schemaVersion=1 保留旧数组；不静默改变旧客户端预期。
     */
    @GetMapping(value="/books/{id}/outline", params="schemaVersion=2")
    public Map<String,Object> outlineV2(@PathVariable String id){
        List<OutlineService.OutlineEntry> entries=books.outline(id);
        long profileRevision=presentationProfileRevision(id);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schemaVersion",2);result.put("bookId",id);
        result.put("outlineRevision",profileRevision);result.put("profileRevision",profileRevision);
        result.put("entries",entries);return result;
    }
    private long presentationProfileRevision(String id){
        if(presentation==null)return 0;
        try{return presentation.buildProfile(id).profileRevision();}catch(RuntimeException e){return 0;}
    }
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
    @PostMapping("/books/{id}/pages/{n}/revert") public Map<String,Object> revert(@PathVariable String id,@PathVariable int n,@RequestBody Map<String,Object> body){int target=requiredRevision(body==null?null:body.get("revision"),"缺少 revision");int expected=requiredRevision(body==null?null:body.get("expectedRevision"),"缺少 expectedRevision，请刷新后重试");return pagePayload(id,books.revert(id,n,target,expected));}
    // A1-C09：只接受整数范围内的版本号；小数、溢出、负数一律可预测 400，不经 intValue 截断
    private static int requiredRevision(Object value,String missingMessage){
        if(value==null)throw new ApiException(HttpStatus.BAD_REQUEST,missingMessage);
        if(value instanceof Integer i)return i;
        if(value instanceof Long l&&l>=Integer.MIN_VALUE&&l<=Integer.MAX_VALUE)return l.intValue();
        throw new ApiException(HttpStatus.BAD_REQUEST,"revision 非法");
    }
    @GetMapping("/books/{id}/search") public List<Map<String,Object>> search(@PathVariable String id,@RequestParam String q){return books.search(id,q);}
    @GetMapping(value="/books/{id}/export",produces="application/zip") public void export(@PathVariable String id,@RequestParam(required=false)String pages,HttpServletResponse response)throws IOException{Book book=books.get(id);String ascii="book-"+book.id()+".zip";response.setHeader(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+ascii+"\"; filename*=UTF-8''"+java.net.URLEncoder.encode(book.title()+".zip",StandardCharsets.UTF_8).replace("+","%20"));export.writeZip(id,response.getOutputStream(),pages);}
    private Map<String,Object> pagePayload(String bookId,Page page){return pagePayload(bookId,page,false);}
    private Map<String,Object> pagePayload(String bookId,Page page,boolean readingFastPath){Page readingPage=ReadingStructureNormalizer.normalize(page);LinkedHashMap<String,Object>payload=json.convertValue(readingPage,new TypeReference<>(){});LinkedHashMap<String,Object>metadata=new LinkedHashMap<>();
        // U3：规范 Page 字段 + 只读展示投影（阅读器消费展示决策，编辑器处理真实保存对象）。
        if(presentation!=null){try{PagePresentation view=readingFastPath?presentation.project(bookId,page,presentation.profileForReading(bookId)):presentation.project(bookId,page);payload.put("presentation",json.convertValue(view,new TypeReference<LinkedHashMap<String,Object>>(){}));}catch(RuntimeException ignored){}}
        // 阶段2：正文读取只返回轻量疑点索引与证据状态，不触发高清渲染；点击疑字后按需请求精确证据
        issueImages.summarize(page).forEach((issueId,snippet)->{String src="/api/books/"+UriUtils.encodePathSegment(bookId,StandardCharsets.UTF_8)+"/pages/"+page.pageNumber()+"/issues/"+UriUtils.encodePathSegment(issueId,StandardCharsets.UTF_8)+"/image";metadata.put(issueId,Map.of("mode",snippet.mode(),"glyphCount",snippet.glyphCount(),"bbox",snippet.bbox(),"boxes",snippet.boxes(),"src",src,"contextBbox",snippet.contextBbox(),"contextSrc",src+"?context=true","pending",true));});payload.put("issueImages",metadata);return payload;}
}
