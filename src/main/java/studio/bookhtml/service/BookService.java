package studio.bookhtml.service;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.PageUpdateRequest;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.CommitOp;
import studio.bookhtml.store.PageConflictException;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Service
public class BookService {
    private final BookStore store; private final PdfService pdf; private final AppProperties config; private final OutlineService outlines;
    private BookPresentationService presentation;
    private final PageStatisticsCache statistics;
    public BookService(BookStore store,PdfService pdf,AppProperties config){this(store,pdf,config,new OutlineService(store));}
    @Autowired public BookService(BookStore store,PdfService pdf,AppProperties config,OutlineService outlines){this.store=store;this.pdf=pdf;this.config=config;this.outlines=outlines;this.statistics=new PageStatisticsCache(store);}
    /** U3：投影注入后，摘要标题使用统一投影；未注入走旧适配器（保守兼容）。 */
    @Autowired(required=false) public void setPresentation(BookPresentationService presentation){this.presentation=presentation;}
    public Book upload(MultipartFile file) {
        if(file==null||file.isEmpty())throw new ApiException(HttpStatus.BAD_REQUEST,"请选择 PDF 文件");
        String original=Optional.ofNullable(file.getOriginalFilename()).orElse("book.pdf");
        if(!original.toLowerCase(Locale.ROOT).endsWith(".pdf"))throw new ApiException(HttpStatus.BAD_REQUEST,"只支持 PDF 文件");
        String id=UUID.randomUUID().toString(); Path dir=null;
        try {
            dir=store.createBookDirectory(id);Path target=store.pdf(id);long max=config.maxUploadMb()*1024L*1024L;
            try(InputStream in=file.getInputStream();OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){byte[] buffer=new byte[64*1024];long total=0;int n;while((n=in.read(buffer))>=0){total+=n;if(total>max)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"PDF 文件超过上传大小限制");out.write(buffer,0,n);}}
            PdfService.PdfInfo info=pdf.inspect(target);if(info.pages()>config.maxPages())throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"PDF 页数超过限制");
            Instant now=Instant.now();String safeName=safeFilename(original);String title=safeName.replaceFirst("(?i)\\.pdf$","");Book book=new Book(id,title,safeName,info.pages(),now,now,0,0);
            List<PdfService.Dimensions> dimensions=pdf.allDimensions(target);if(dimensions.size()!=info.pages())throw new ApiException(HttpStatus.BAD_REQUEST,"PDF 页数在导入期间发生变化");for(int i=0;i<dimensions.size();i++){var d=dimensions.get(i);store.writePage(id,Page.pending(i+1,d.width(),d.height()),false);}
            // 列表以 book.json 为发布标记：所有页准备好后再使新书可见。
            store.writeBook(book);return book;
        }catch(ApiException e){cleanup(dir);throw e;}catch(Exception e){cleanup(dir);throw new ApiException(HttpStatus.BAD_REQUEST,"PDF 文件无法读取或已损坏");}
    }
    public List<Book> list(){return store.listBooks().stream().map(this::refresh).toList();}
    public Book get(String id){return refresh(store.readBook(id));}
    /** Called under JobService's task lock when archiving, to serialize task admission with the metadata write. */
    Book updateLibrary(String id, String requestedTitle, Boolean archived) {
        if ((requestedTitle == null) == (archived == null))
            throw new ApiException(HttpStatus.BAD_REQUEST, "一次只能修改书名或归档状态");
        String title = null;
        if (requestedTitle != null) {
            if (requestedTitle.codePoints().anyMatch(Character::isISOControl))
                throw new ApiException(HttpStatus.BAD_REQUEST, "书名应为 1–120 字且不能包含控制字符");
            title = requestedTitle.strip();
            if (title.isEmpty() || title.codePointCount(0, title.length()) > 120)
                throw new ApiException(HttpStatus.BAD_REQUEST, "书名应为 1–120 字且不能包含控制字符");
        }
        String validTitle = title;
        try {
            Book changed = store.updateBook(id, current -> {
                if (Boolean.TRUE.equals(archived)) {
                    Job job = store.readJob(id);
                    if (job != null && List.of("QUEUED", "RUNNING", "CANCELLING").contains(job.status()))
                        throw new ApiException(HttpStatus.CONFLICT, "本书正在识别，请等待任务完成或取消后归档");
                }
                return new Book(current.id(), validTitle == null ? current.title() : validTitle,
                        current.filename(), current.totalPages(), current.createdAt(), Instant.now(),
                        current.processedPages(), current.reviewedPages(), archived == null ? current.archived() : archived);
            });
            return refresh(changed);
        } catch (ApiException e) { throw e; }
        catch (IOException e) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "更新书架失败"); }
    }
    public List<PageSummary> pages(String id){Book b=store.readBook(id);List<PageSummary> result=new ArrayList<>(b.totalPages());for(int n=1;n<=b.totalPages();n++){Page p=requirePage(id,n);result.add(pageSummary(id,p));}return result;}
    /** U3：同一投影结果选择页面代表标题；无标题回到“第 N 页”，不冒用书眉。 */
    public PageSummary pageSummary(String id,Page p){if(presentation==null)return summary(p);return presentation.pageSummary(id,p);}
    public List<OutlineService.OutlineEntry> outline(String id){return outlines.outline(id);}
    public Page page(String id,int number){Book b=store.readBook(id);validatePage(number,b.totalPages());return requirePage(id,number);}
    public Page update(String id,int n,PageUpdateRequest request){Page old=page(id,n);if("PROCESSING".equals(old.status()))throw new ApiException(HttpStatus.CONFLICT,"本页正在识别，请等待完成或先取消任务再校对");
        // 阶段1：活动任务包含本页时拒绝手工保存，避免与后台回写竞争
        try{Job job=store.readJob(id);if(job!=null&&List.of("QUEUED","RUNNING","CANCELLING").contains(job.status())&&job.pages()!=null&&job.pages().contains(n))throw new ApiException(HttpStatus.CONFLICT,"本页正在识别，请等待完成或先取消任务再校对");}catch(ApiException e){throw e;}catch(Exception ignored){}
        // R03/A1-04：人工保存必须携带版本；缺失或非法 400，冲突 409（含当前版本）
        if(request.revision()==null)throw new ApiException(HttpStatus.BAD_REQUEST,"缺少 revision，请刷新后重试");
        if(request.revision()<0)throw new ApiException(HttpStatus.BAD_REQUEST,"revision 非法");
        BlockValidator.validate(request.blocks());List<Block> reviewed=request.blocks().stream().map(b->new Block(b.id(),b.type(),b.order(),b.bbox(),b.writingMode(),b.original(),b.simplified(),b.confidence(),b.uncertain(),request.reviewed()||b.reviewed(),b.headingLevel(),"manual",b.sourceIds(),b.suggestion(),b.sourceRect(),b.issues())).toList();Page next=new Page(n,old.width(),old.height(),"READY","manual",reviewed,old.warnings(),request.reviewed(),null,old.sourceRecords(),null);try{Page committed=store.commitPage(id,next,request.revision(),CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);touchAfterCommit(id);return committed;}catch(PageConflictException e){throw e;}catch(ApiException e){throw e;}catch(IOException e){throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"保存校对结果失败");}}
    public List<Integer> revisions(String id,int n){page(id,n);return store.listRevisions(id,n);}
    public Page revert(String id,int n,int targetRevision,int expectedRevision){page(id,n);
        // A1-C09：版本号必须为非负整数，不经截断解释
        if(targetRevision<0||expectedRevision<0)throw new ApiException(HttpStatus.BAD_REQUEST,"revision 非法");
        try{Page next=store.revertPage(id,n,targetRevision,expectedRevision);touchAfterCommit(id);return next;}catch(ApiException e){throw e;}catch(IOException e){throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"回退版本失败");}}
    public List<Map<String,Object>> search(String id,String query){Book b=store.readBook(id);String q=query==null?"":query.strip();if(q.isEmpty())return List.of();if(q.length()>200)throw new ApiException(HttpStatus.BAD_REQUEST,"搜索词过长");List<Map<String,Object>> result=new ArrayList<>();for(int n=1;n<=b.totalPages()&&result.size()<500;n++){Page p=requirePage(id,n);for(Block block:p.blocks()){if("advertisement".equals(block.type()))continue;if(contains(block.original(),q)||contains(block.simplified(),q))result.add(Map.of("pageNumber",n,"blockId",block.id(),"text",Optional.ofNullable(block.simplified()).orElse(block.original())));}}return result;}
    public byte[] image(String id,int n,int width){page(id,n);java.awt.image.BufferedImage image=null;try{image=pdf.render(store.pdf(id),n,width);return pdf.png(image);}catch(IOException e){throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"页面图片生成失败");}finally{if(image!=null)image.flush();}}
    public byte[] figure(String id,int n,String blockId){Page p=page(id,n);Block b=p.blocks().stream().filter(x->x.id().equals(blockId)).findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"未找到该内容块"));try{return pdf.cropPng(store.pdf(id),n,config.maxImageWidth(),b.bbox());}catch(IOException e){throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"内容块图片生成失败");}}
    public Path pdfPath(String id){store.readBook(id);return store.pdf(id);}
    private Book refresh(Book b) {
        var counts = statistics.counts(b);
        return new Book(b.id(),b.title(),b.filename(),b.totalPages(),b.createdAt(),b.updatedAt(),
                counts.processed(),counts.reviewed(),b.archived());
    }
    private void touchAfterCommit(String id) {
        try {
            // A saved page is authoritative. Do not scan an entire book before acknowledging it.
            store.updateBook(id, current -> {
                var counts = statistics.cached(current);
                return new Book(current.id(),current.title(),current.filename(),current.totalPages(),
                        current.createdAt(),Instant.now(),counts == null ? current.processedPages() : counts.processed(),
                        counts == null ? current.reviewedPages() : counts.reviewed(),current.archived());
            });
        } catch (IOException | RuntimeException metadataFailure) {
            System.getLogger(BookService.class.getName()).log(System.Logger.Level.WARNING,
                    "PAGE_SAVED_METADATA_STALE: saved page is authoritative; counts refresh on read, book timestamp may lag");
        }
    }
    private Page requirePage(String id,int n){Page p=store.readPage(id,n);if(p==null)throw new ApiException(HttpStatus.NOT_FOUND,"页码不存在");return p;}
    static PageSummary summary(Page p){String title=HeadingText.pageTitle(p);List<Block>reading=p.blocks().stream().filter(b->!"advertisement".equals(b.type())).toList();int uncertain=(int)reading.stream().filter(Block::uncertain).count();return new PageSummary(p.pageNumber(),p.status(),reading.size(),uncertain,p.width(),p.height(),title,p.reviewed());}
    private static boolean contains(String value,String q){return value!=null&&value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));}
    private static void validatePage(int n,int total){if(n<1||n>total)throw new ApiException(HttpStatus.NOT_FOUND,"页码不存在");}
    private static String safeFilename(String s){String v=Paths.get(s).getFileName().toString().replaceAll("[\\p{Cntrl}]","_");return v.length()>200?v.substring(v.length()-200):v;}
    private static void cleanup(Path dir){if(dir==null)return;try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){}}
}
