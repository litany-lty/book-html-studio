package studio.bookhtml.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

@Service
public class PageProcessor {
    private final BookStore store;private final PdfService pdf;private final NativeTextExtractor nativeText;private final TesseractService tesseract;private final CloudOcrPipeline qwenOcr;private final PaddleOcrPipeline paddle;private final MiniMaxVisionClient miniMax;private final QwenLayoutClient qwenLayout;private final QwenTocRecoveryService tocRecovery;private final SparsePageGuard sparsePageGuard;private final VerticalLayoutNormalizer verticalNormalizer;private final AssistedReviewService review;private final TraditionalConverter converter;
    private SettingsService settings;
    @Autowired public PageProcessor(BookStore store,PdfService pdf,NativeTextExtractor nativeText,TesseractService tesseract,CloudOcrPipeline qwenOcr,PaddleOcrPipeline paddle,MiniMaxVisionClient miniMax,QwenLayoutClient qwenLayout,QwenTocRecoveryService tocRecovery,SparsePageGuard sparsePageGuard,VerticalLayoutNormalizer verticalNormalizer,AssistedReviewService review,TraditionalConverter converter){this.store=store;this.pdf=pdf;this.nativeText=nativeText;this.tesseract=tesseract;this.qwenOcr=qwenOcr;this.paddle=paddle;this.miniMax=miniMax;this.qwenLayout=qwenLayout;this.tocRecovery=tocRecovery;this.sparsePageGuard=sparsePageGuard;this.verticalNormalizer=verticalNormalizer;this.review=review;this.converter=converter;}
    PageProcessor(BookStore store,PdfService pdf,NativeTextExtractor nativeText,TesseractService tesseract,CloudOcrPipeline qwenOcr,PaddleOcrPipeline paddle,MiniMaxVisionClient miniMax,QwenLayoutClient qwenLayout,QwenTocRecoveryService tocRecovery,VerticalLayoutNormalizer verticalNormalizer,AssistedReviewService review,TraditionalConverter converter){this(store,pdf,nativeText,tesseract,qwenOcr,paddle,miniMax,qwenLayout,tocRecovery,new SparsePageGuard(),verticalNormalizer,review,converter);}
    @Autowired public void setSettings(SettingsService settings){this.settings=settings;}
    public ProcessingResult process(String bookId,int pageNumber,String provider,String layout,boolean split,boolean assist,BooleanSupplier cancelled)throws Exception{
        try(UsageContext.Scope ignored=UsageContext.open(bookId,pageNumber,"OCR_PAGE")){
        if(cancelled.getAsBoolean())throw new CancelledException();Page previous=store.readPage(bookId,pageNumber);String nativeLayout="vertical".equals(layout)?"vertical":"horizontal".equals(layout)?"horizontal":"auto";
        Optional<List<Block>>nativeBlocks=nativeText.extract(store.pdf(bookId),pageNumber,nativeLayout);List<Block>blocks;List<Block>sourceRecords;String actualProvider;List<String>warnings=new ArrayList<>();
        if(nativeBlocks.isPresent()&&!nativeBlocks.get().isEmpty()&&!preferOcrOverNative(store.pdf(bookId),pageNumber,nativeBlocks.get(),warnings,cancelled)){
            blocks=nativeBlocks.get();sourceRecords=List.copyOf(blocks);actualProvider="native";
            if(assist&&PaddleOcrPipeline.isPaddle(provider)){AssistResult result=assistWithQwen(store.pdf(bookId),pageNumber,blocks,layout,cancelled);blocks=guardedAssist(blocks,result.blocks(),warnings,"Qwen3.8-Max 结构辅助来源校验失败，已保留原生文字层",QualityGate.GateOp.REORDER_OR_RECLASSIFY);blocks=simplify(blocks);if(result.assisted())actualProvider="native+qwen-assist";else warnings.add(result.warning());}
            else if(assist&&"qwen".equals(provider)){if(miniMax.configured()){BufferedImage image=pdf.renderForOcr(store.pdf(bookId),pageNumber);try{List<Block> assisted=miniMax.assist(qwenOcr.encodeWithin(image).bytes(),blocks,layout,cancelled);blocks=simplify(guardedAssist(blocks,assisted,warnings,"MiniMax 结构辅助来源校验失败，已保留原生文字层",QualityGate.GateOp.REORDER_OR_RECLASSIFY));actualProvider="native+minimax";}catch(CancelledException e){throw e;}catch(Exception e){blocks=sourceRecords;warnings.add("MiniMax 结构辅助失败，本页保留原生文字层结果");}finally{image.flush();}}else warnings.add("MiniMax 辅助未配置，本页仅使用原生文字层");}
            warnings.add("原生文字层已提取；自动顺序、图框与坐标仍需人工抽查");
        }else{
            BufferedImage image=("qwen".equals(provider)||PaddleOcrPipeline.isPaddle(provider))?pdf.renderForOcr(store.pdf(bookId),pageNumber):pdf.render(store.pdf(bookId),pageNumber,1800);
            try{
                if(PaddleOcrPipeline.isPaddle(provider)){
                    PaddleResult paddleResult=recognizePaddleWithFallback(image,layout,split,provider,cancelled);
                    if(paddleResult.fallbackNote()!=null)warnings.add(paddleResult.fallbackNote());
                    blocks=paddleResult.blocks();sourceRecords=List.copyOf(blocks);actualProvider=paddleResult.usedProvider();
                    String ocrLabel=PaddleOcrPipeline.ocrShortLabel(actualProvider);
                    SparsePageGuard.GuardResult sparse=sparsePageGuard.apply(image,blocks);if(sparse.guarded()){blocks=sparse.blocks();warnings.add(sparse.warning());actualProvider=provider+"+sparse-page-guard";}
                    else if(assist){QwenTocRecoveryService.RecoveryResult recovery=tocRecovery.recover(image,blocks,cancelled);if(recovery.warning()!=null)warnings.add(recovery.warning());if(recovery.recovered()){blocks=guardedAssist(sourceRecords,recovery.blocks(),warnings,"目录恢复来源校验失败，已保留"+ocrLabel+"原始结果",QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION);actualProvider=paddleResult.usedProvider()+"+qwen-toc-recovery";}else if(recovery.attempted())blocks=guardedAssist(sourceRecords,recovery.blocks(),warnings,"目录恢复来源校验失败，已保留"+ocrLabel+"原始结果",QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION);else if(shouldRunLayoutAssist(recovery)&&qwenLayout.configured()){try{List<Block> assisted=qwenLayout.assist(qwenOcr.encodeWithin(image).bytes(),blocks,layout,cancelled);blocks=guardedAssist(sourceRecords,assisted,warnings,"Qwen3.8-Max 结构辅助来源校验失败，已保留"+ocrLabel+"原始结果",QualityGate.GateOp.REORDER_OR_RECLASSIFY);actualProvider=paddleResult.usedProvider()+"+qwen-assist";}catch(CancelledException e){throw e;}catch(Exception e){String detail=JobService.safeDetail(e);warnings.add("Qwen3.8-Max 结构辅助失败，本页已保留"+ocrLabel+"原始结果"+(detail==null?"":"："+detail));blocks=sourceRecords;}}else warnings.add("Qwen3.8-Max 结构辅助未配置，本页仅保留"+ocrLabel+"结果");}
                    // F03/R08：空与失败分类——纯视觉页成功保留；真空白成功空页；其余空结果失败。
                    // 稀疏保护与目录恢复先行：空 OCR 也可达恢复分支，不在恢复前判失败。
                    if(QualityGate.totalChars(blocks)==0&&blocks.stream().allMatch(b->QualityGate.nonSpace(b.original())==0)){
                        if(QualityGate.isFigureOnly(blocks)){
                            warnings.add("本页以插图/表格为主，未提取到正文文字，已保留原图与图框");
                            blocks=simplify(blocks);warnings.add(ocrLabel+"结果尚未人工校对，不保证无错字、漏字或顺序错误");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,actualProvider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider,blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.VISUAL_ONLY);
                        }
                        if(QualityGate.isTrueBlank(image)){
                            warnings.add("本页图像信息极少，判定为近空白页，已保留原页图供对照");
                            blocks=List.of();sourceRecords=List.of();
                            blocks=simplifyForBlank();warnings.add(ocrLabel+"结果尚未人工校对，不保证无错字、漏字或顺序错误");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,actualProvider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider+"+blank",blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.BLANK_CONFIRMED);
                        }
                        throw new OcrException("OCR未返回可用文字，图像另有墨量，已标记失败供重试");
                    }
                    blocks=simplify(blocks);warnings.add(ocrLabel+"结果尚未人工校对，不保证无错字、漏字或顺序错误");
                }else if("qwen".equals(provider)){
                    blocks=qwenOcr.recognize(image,layout,split,cancelled);sourceRecords=List.copyOf(blocks);actualProvider="qwen";
                    if(QualityGate.totalChars(blocks)==0){
                        if(QualityGate.isFigureOnly(blocks)){
                            warnings.add("本页以插图/表格为主，未提取到正文文字，已保留原图与图框");
                            blocks=simplify(blocks);warnings.add("云端 OCR 与结构识别尚未人工校对，不保证无错字、漏字或顺序错误");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,actualProvider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider,blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.VISUAL_ONLY);
                        }
                        if(QualityGate.isTrueBlank(image)){
                            warnings.add("本页图像信息极少，判定为近空白页，已保留原页图供对照");
                            blocks=List.of();sourceRecords=List.of();
                            blocks=simplifyForBlank();warnings.add("云端 OCR 与结构识别尚未人工校对，不保证无错字、漏字或顺序错误");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,provider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider+"+blank",blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.BLANK_CONFIRMED);
                        }
                        throw new OcrException("OCR未返回可用文字，图像另有墨量，已标记失败供重试");
                    }
                    if(assist){if(miniMax.configured()){try{byte[]png=qwenOcr.encodeWithin(image).bytes();List<Block> assisted=miniMax.assist(png,blocks,layout,cancelled);blocks=guardedAssist(sourceRecords,assisted,warnings,"MiniMax 结构辅助来源校验失败，已保留 Qwen OCR 原始结果",QualityGate.GateOp.MERGE_TEXT_STRUCTURE);if("vertical".equals(layout))blocks=verticalNormalizer.normalize(blocks,sourceRecords);actualProvider="qwen+minimax";List<Block> reviewed=review.review(image,blocks,cancelled);blocks=guardedAssist(blocks,reviewed,warnings,"局部复核来源校验失败，已保留辅助前结果",QualityGate.GateOp.REORDER_OR_RECLASSIFY);if(blocks.stream().anyMatch(b->b.source()!=null&&b.source().contains("qwen-review")))actualProvider="qwen+minimax+qwen-review";}catch(CancelledException e){throw e;}catch(Exception e){String detail=JobService.safeDetail(e);warnings.add("MiniMax 结构辅助失败，本页已保留 Qwen OCR 原始结果"+(detail==null?"":"："+detail));blocks=sourceRecords;}}else warnings.add("MiniMax 辅助未配置，本页仅使用 Qwen OCR，未完成结构辅助");}
                    blocks=simplify(blocks);warnings.add("云端 OCR 与结构识别尚未人工校对，不保证无错字、漏字或顺序错误");
                }else{blocks=tesseract.recognize(image,layout,split,cancelled);sourceRecords=List.copyOf(blocks);actualProvider="local";
                    if(QualityGate.totalChars(blocks)==0){
                        if(QualityGate.isFigureOnly(blocks)){
                            warnings.add("本页以插图/表格为主，未提取到正文文字，已保留原图与图框");
                            blocks=simplify(blocks);warnings.add("本地 OCR 仅为初稿，复杂图表、竖排和手写内容可能存在明显错字");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,actualProvider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider,blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.VISUAL_ONLY);
                        }
                        if(QualityGate.isTrueBlank(image)){
                            warnings.add("本页图像信息极少，判定为近空白页，已保留原页图供对照");
                            blocks=List.of();sourceRecords=List.of();
                            blocks=simplifyForBlank();warnings.add("本地 OCR 仅为初稿，复杂图表、竖排和手写内容可能存在明显错字");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,provider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider+"+blank",blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.BLANK_CONFIRMED);
                        }
                        throw new OcrException("本地 OCR 未返回可用文字，图像另有墨量，已标记失败供重试");
                    }
                    warnings.add("本地 OCR 仅为初稿，复杂图表、竖排和手写内容可能存在明显错字");}
            }finally{image.flush();}
        }
        // 保真门和局部复核结束后才分类：只改派生块类型，原始 OCR sourceRecords 保持原样。
        AdvertisementFilter.Result advertisements=AdvertisementFilter.classify(blocks,previous.reviewed(),converter);
        blocks=advertisements.blocks();
        if(advertisements.marked()>0)warnings.add("已标记 "+advertisements.marked()+" 个独立页边广告块；原稿和原始识别记录保留可查看");
        if(advertisements.heldForReview()>0)warnings.add("有 "+advertisements.heldForReview()+" 个疑似广告块含已确认疑点，未自动隐藏，请人工复核");
        int bodyChars=blocks.stream().filter(b->!"advertisement".equals(b.type())).mapToInt(b->QualityGate.nonSpace(b.original())).sum();
        if(advertisements.marked()>0&&bodyChars==0)warnings.add("本页仅识别到页边广告，未确认为正文；请对照原图复核");
        BlockValidator.validate(blocks);warnings.add(traceWarning(store.pdf(bookId),pageNumber,actualProvider,layout));
        ProcessingResult.Category category=bodyChars>0?ProcessingResult.Category.TEXT
            :(advertisements.marked()>0||QualityGate.isFigureOnly(blocks))?ProcessingResult.Category.VISUAL_ONLY:ProcessingResult.Category.TEXT;
        return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider,blocks,List.copyOf(warnings),false,null,sourceRecords),category);
        }
    }
    /** 阶段3：混合页检查——原生字符少但图像墨多时改走图像识别；预览图失败也保守改走图像识别。 */
    private boolean preferOcrOverNative(Path pdfPath,int pageNumber,List<Block> nativeBlocks,List<String> warnings,BooleanSupplier cancelled){
        int chars=QualityGate.totalChars(nativeBlocks);
        double area=nativeBlocks.stream().filter(Objects::nonNull).map(Block::bbox).filter(b->b!=null&&b.length==4).mapToDouble(b->Math.max(0,b[2])*Math.max(0,b[3])).sum();
        if(chars>=200&&area>=0.05) return false;
        if(cancelled.getAsBoolean()) throw new CancelledException();
        BufferedImage preview=null;
        try{preview=this.pdf.render(pdfPath,pageNumber,900);}
        catch(CancelledException e){throw e;}
        catch(Exception e){if(cancelled.getAsBoolean())throw new CancelledException();warnings.add("原生文字层覆盖不足且原图预览失败，已保守改走图像识别");return true;}
        if(preview==null){warnings.add("原生文字层覆盖不足且原图预览为空，已保守改走图像识别");return true;}
        try{
            boolean prefer=QualityGate.shouldPreferOcr(nativeBlocks,preview);
            if(prefer) warnings.add("检测到原生文字层覆盖不足（字符少、图像墨量大），已改用图像识别以防漏识扫描内容");
            return prefer;
        }finally{if(preview!=null) preview.flush();}
    }
    /** 阶段3/F02：来源门禁——调用方显式声明操作类别；校验失败回退原始并警告，不悄悄交付。 */
    private List<Block> guardedAssist(List<Block> source,List<Block> assisted,List<String> warnings,String message,QualityGate.GateOp op){
        if(assisted==null||!QualityGate.check(source,assisted,op).accepted()){warnings.add(message);return source;}
        return assisted;
    }
    /** The user must explicitly enable cross-channel fallback; never try a hidden third channel. */
    private PaddleResult recognizePaddleWithFallback(BufferedImage image,String layout,boolean split,String provider,BooleanSupplier cancelled)throws Exception{
        try{return new PaddleResult(paddle.recognize(image,layout,split,provider,cancelled),provider,null);}
        catch(QuotaExceededException first){
            if(settings==null||!settings.state().fallbackEnabled())throw first;
            for(String alt:PaddleOcrPipeline.fallbackOrder(provider)){
                if(cancelled.getAsBoolean())throw new CancelledException();
                if(!paddle.configured(alt))continue;
                try{
                    List<Block> blocks=paddle.recognize(image,layout,split,alt,cancelled);
                    String note="主通道"+PaddleOcrPipeline.channelLabel(provider)+"额度不足或无权限，已自动改用"+PaddleOcrPipeline.channelLabel(alt)+"；后续页面仍优先使用原通道";
                    return new PaddleResult(blocks,alt,note);
                }catch(QuotaExceededException ignored){}
            }
            throw first;
        }
    }
    private record PaddleResult(List<Block>blocks,String usedProvider,String fallbackNote){}
    private List<Block> simplifyForBlank(){return List.of();}
    private static String traceWarning(Path pdfPath,int pageNumber,String provider,String layout){
        try{long size=java.nio.file.Files.size(pdfPath);long mtime=java.nio.file.Files.getLastModifiedTime(pdfPath).toMillis();return "处理追溯：页"+pageNumber+" 通道"+provider+" 版式"+layout+" PDF指纹"+Long.toHexString(size*31+mtime);}
        catch(Exception ignored){return "处理追溯：页"+pageNumber+" 通道"+provider+" 版式"+layout;}
    }
    private AssistResult assistWithQwen(Path pdfPath,int pageNumber,List<Block>source,String layout,BooleanSupplier cancelled)throws Exception{if(!qwenLayout.configured())return new AssistResult(source,false,"Qwen3.8-Max 结构辅助未配置，本页仅保留原生文字层");BufferedImage image=pdf.renderForOcr(pdfPath,pageNumber);try{return new AssistResult(qwenLayout.assist(qwenOcr.encodeWithin(image).bytes(),source,layout,cancelled),true,null);}catch(CancelledException e){throw e;}catch(Exception e){String detail=JobService.safeDetail(e);return new AssistResult(source,false,"Qwen3.8-Max 结构辅助失败，本页已保留原生文字层"+(detail==null?"":"："+detail));}finally{image.flush();}}
    private List<Block>simplify(List<Block>blocks)throws OcrException{List<Block>result=new ArrayList<>();for(Block b:blocks){String original=b.original()==null?"":b.original();String simplified=converter.toSimplified(original);List<ContentIssue>issues=mapIssues(original,b.issues(),converter);result.add(new Block(b.id(),b.type(),b.order(),b.bbox(),b.writingMode(),b.original(),simplified,b.confidence(),true,false,b.headingLevel(),b.source(),b.sourceIds(),b.suggestion(),b.sourceRect(),issues));}BlockValidator.validate(result);return List.copyOf(result);}
    /** J09/T58：问题区间映射（simplified 偏移）；确认元数据原样保留，不重建丢失。 */
    public static List<ContentIssue>mapIssues(String original,List<ContentIssue>input,TraditionalConverter converter)throws OcrException{List<ContentIssue>issues=new ArrayList<>();for(ContentIssue issue:input==null?List.<ContentIssue>of():input){if(issue.start()<0||issue.end()<=issue.start()||issue.end()>original.length())throw new OcrException("内容问题区间超出 OCR 原文范围");int simpleStart=converter.toSimplified(original.substring(0,issue.start())).length();int simpleEnd=converter.toSimplified(original.substring(0,issue.end())).length();String inferred=issue.inferredText()==null?null:converter.toSimplified(issue.inferredText());issues.add(new ContentIssue(issue.id(),issue.kind(),issue.start(),issue.end(),simpleStart,simpleEnd,issue.reason(),issue.resolved(),issue.replacement(),inferred,issue.resolution()));}return List.copyOf(issues);}
    static boolean shouldRunLayoutAssist(QwenTocRecoveryService.RecoveryResult recovery){return recovery!=null&&!recovery.recovered()&&!recovery.attempted();}
    private record AssistResult(List<Block>blocks,boolean assisted,String warning){}
}
