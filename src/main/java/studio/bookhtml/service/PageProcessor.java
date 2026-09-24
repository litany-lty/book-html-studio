package studio.bookhtml.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

@Service
public class PageProcessor {
    private final BookStore store;private final PdfService pdf;private final NativeTextExtractor nativeText;private final TesseractService tesseract;private final CloudOcrPipeline qwenOcr;private final PaddleOcrPipeline paddle;private final MiniMaxVisionClient miniMax;private final QwenLayoutClient qwenLayout;private final QwenTocRecoveryService tocRecovery;private final SparsePageGuard sparsePageGuard;private final VerticalLayoutNormalizer verticalNormalizer;private final AssistedReviewService review;private final TraditionalConverter converter;
    private SettingsService settings;
    private ReadingPriority priority;
    @Autowired(required=false) public void setPriority(ReadingPriority priority) { this.priority = priority; }
    /** C：手写/影印稿转写通道（可选注入）。 */
    private HandwritingTranscribeService handwriting;
    @Autowired(required=false) public void setHandwriting(HandwritingTranscribeService handwriting) { this.handwriting = handwriting; }
    @org.springframework.beans.factory.annotation.Value("${app.ocr-region-recovery:true}")
    private boolean regionRecoveryEnabled = true;
    @Autowired public PageProcessor(BookStore store,PdfService pdf,NativeTextExtractor nativeText,TesseractService tesseract,CloudOcrPipeline qwenOcr,PaddleOcrPipeline paddle,MiniMaxVisionClient miniMax,QwenLayoutClient qwenLayout,QwenTocRecoveryService tocRecovery,SparsePageGuard sparsePageGuard,VerticalLayoutNormalizer verticalNormalizer,AssistedReviewService review,TraditionalConverter converter){this.store=store;this.pdf=pdf;this.nativeText=nativeText;this.tesseract=tesseract;this.qwenOcr=qwenOcr;this.paddle=paddle;this.miniMax=miniMax;this.qwenLayout=qwenLayout;this.tocRecovery=tocRecovery;this.sparsePageGuard=sparsePageGuard;this.verticalNormalizer=verticalNormalizer;this.review=review;this.converter=converter;}
    PageProcessor(BookStore store,PdfService pdf,NativeTextExtractor nativeText,TesseractService tesseract,CloudOcrPipeline qwenOcr,PaddleOcrPipeline paddle,MiniMaxVisionClient miniMax,QwenLayoutClient qwenLayout,QwenTocRecoveryService tocRecovery,VerticalLayoutNormalizer verticalNormalizer,AssistedReviewService review,TraditionalConverter converter){this(store,pdf,nativeText,tesseract,qwenOcr,paddle,miniMax,qwenLayout,tocRecovery,new SparsePageGuard(),verticalNormalizer,review,converter);}
    @Autowired public void setSettings(SettingsService settings){this.settings=settings;}
    private studio.bookhtml.config.QwenAssistProperties assistConfig;
    private QwenRequestGate gate;
    private QwenTaskPlanner planner;
    private QwenTextReviewClient reviewClient;
    private QwenAssistCoordinator coordinator;
    private ParagraphComprehensibilityService comprehensibilityService;
    /** U5：分组增强装配（缺省关闭，旧整页路径为可控回滚）。 */
    @Autowired(required=false) public void setAssistConfig(studio.bookhtml.config.QwenAssistProperties assistConfig){this.assistConfig=assistConfig;}
    @Autowired(required=false) public void setRequestGate(QwenRequestGate gate){this.gate=gate;}
    @Autowired(required=false) public void setTaskPlanner(QwenTaskPlanner planner){this.planner=planner;}
    @Autowired(required=false) public void setTextReviewClient(QwenTextReviewClient reviewClient){this.reviewClient=reviewClient;}
    @Autowired(required=false) public void setAssistCoordinator(QwenAssistCoordinator coordinator){this.coordinator=coordinator;}
    @Autowired(required=false) public void setComprehensibilityService(ParagraphComprehensibilityService comprehensibilityService){this.comprehensibilityService=comprehensibilityService;}
    /**
     * U4：可读基线。与完整 process() 同一管线、关闭可选增强：提取/OCR → 原始证据校验 →
     * 发布安全可读版。Qwen 未完成不阻止读取。
     */
    public ProcessingResult processBaseline(String bookId,int pageNumber,String provider,String layout,boolean split,BooleanSupplier cancelled)throws Exception{
        return process(bookId,pageNumber,provider,layout,split,false,cancelled);
    }
    private ImageArtifact renderForOcrArtifact(Path pdfPath, int pageNumber) throws IOException {
        ImageArtifact artifact = pdf.renderForOcrArtifact(pdfPath, pageNumber);
        if (artifact != null) return artifact;
        BufferedImage img = pdf.renderForOcr(pdfPath, pageNumber);
        if (img != null) return new ResourceBudgetManager().wrapImage(img);
        return null;
    }

    private ImageArtifact renderArtifact(Path pdfPath, int pageNumber, int targetWidth) throws IOException {
        ImageArtifact artifact = pdf.renderArtifact(pdfPath, pageNumber, targetWidth);
        if (artifact != null) return artifact;
        BufferedImage img = pdf.render(pdfPath, pageNumber, targetWidth);
        if (img != null) return new ResourceBudgetManager().wrapImage(img);
        return null;
    }

    public ProcessingResult process(String bookId,int pageNumber,String provider,String layout,boolean split,boolean assist,BooleanSupplier cancelled)throws Exception{
        try(UsageContext.Scope ignored=UsageContext.open(bookId,pageNumber,"OCR_PAGE");
            QwenExecutionScope execution=QwenExecutionScope.open(bookId,pageNumber,gate,
                    priority != null && priority.foreground(bookId,pageNumber))){
        if(cancelled.getAsBoolean())throw new CancelledException();Page previous=store.readPage(bookId,pageNumber);if(HandwritingTranscribeService.PROVIDER_ID.equals(provider))return processHandwriting(bookId,pageNumber,layout,cancelled,previous);String nativeLayout="vertical".equals(layout)?"vertical":"horizontal".equals(layout)?"horizontal":"auto";
        Optional<List<Block>>nativeBlocks=nativeText.extract(store.pdf(bookId),pageNumber,nativeLayout);List<Block>blocks;List<Block>sourceRecords;String actualProvider;List<String>warnings=new ArrayList<>();
        if(nativeBlocks.isPresent()&&!nativeBlocks.get().isEmpty()&&!preferOcrOverNative(store.pdf(bookId),pageNumber,nativeBlocks.get(),warnings,cancelled)){
            blocks=nativeBlocks.get();sourceRecords=List.copyOf(blocks);actualProvider="native";
            if(assist&&PaddleOcrPipeline.isPaddle(provider)){AssistResult result=assistWithQwen(store.pdf(bookId),pageNumber,blocks,layout,cancelled);blocks=guardedAssist(blocks,result.blocks(),warnings,"Qwen3.8-Max 结构辅助来源校验失败，已保留原生文字层",QualityGate.GateOp.REORDER_OR_RECLASSIFY);blocks=simplify(blocks);if(result.assisted())actualProvider="native+qwen-assist";else warnings.add(result.warning());}
            else if(assist&&"qwen".equals(provider)){if(miniMax.configured()){try(ImageArtifact artifact=renderForOcrArtifact(store.pdf(bookId),pageNumber)){if(artifact==null||artifact.image()==null)throw new OcrException("渲染页面图像为空");BufferedImage image=artifact.image();try{List<Block> assisted=miniMax.assist(qwenOcr.encodeWithin(image).bytes(),blocks,layout,cancelled);blocks=simplify(guardedAssist(blocks,assisted,warnings,"MiniMax 结构辅助来源校验失败，已保留原生文字层",QualityGate.GateOp.REORDER_OR_RECLASSIFY));actualProvider="native+minimax";}catch(CancelledException e){throw e;}catch(Exception e){blocks=sourceRecords;warnings.add("MiniMax 结构辅助失败，本页保留原生文字层结果");}}}else warnings.add("MiniMax 辅助未配置，本页仅使用原生文字层");}
            warnings.add("原生文字层已提取；自动顺序、图框与坐标仍需人工抽查");
        }else{
            try(ImageArtifact artifact=("qwen".equals(provider)||PaddleOcrPipeline.isPaddle(provider))?renderForOcrArtifact(store.pdf(bookId),pageNumber):renderArtifact(store.pdf(bookId),pageNumber,1800)){
                if(artifact==null||artifact.image()==null)throw new OcrException("渲染页面图像为空");
                BufferedImage image=artifact.image();
                try{
                if(PaddleOcrPipeline.isPaddle(provider)){
                    PaddleResult paddleResult=recognizePaddleWithFallback(image,layout,split,provider,cancelled);
                    if(paddleResult.fallbackNote()!=null)warnings.add(paddleResult.fallbackNote());
                    actualProvider=paddleResult.usedProvider();blocks=recoverMissingText(image,paddleResult.blocks(),layout,actualProvider,warnings,cancelled);sourceRecords=List.copyOf(blocks);
                    String ocrLabel=PaddleOcrPipeline.ocrShortLabel(actualProvider);
                    SparsePageGuard.GuardResult sparse=sparsePageGuard.apply(image,blocks);if(sparse.guarded()){blocks=sparse.blocks();warnings.add(sparse.warning());actualProvider=provider+"+sparse-page-guard";}
                    else if(assist&&warnings.stream().noneMatch(w->w.startsWith(OcrTextRecovery.PARTIAL))){QwenTocRecoveryService.RecoveryResult recovery=tocRecovery.recover(image,blocks,cancelled);if(recovery.warning()!=null)warnings.add(recovery.warning());if(recovery.recovered()){blocks=guardedAssist(sourceRecords,recovery.blocks(),warnings,"目录恢复来源校验失败，已保留"+ocrLabel+"原始结果",QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION);actualProvider=paddleResult.usedProvider()+"+qwen-toc-recovery";}else if(recovery.attempted())blocks=guardedAssist(sourceRecords,recovery.blocks(),warnings,"目录恢复来源校验失败，已保留"+ocrLabel+"原始结果",QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION);else if(shouldRunLayoutAssist(recovery)&&qwenLayout.configured()){try{List<Block> assisted=qwenLayout.assist(qwenOcr.encodeWithin(image).bytes(),blocks,layout,cancelled);blocks=guardedAssist(sourceRecords,assisted,warnings,"Qwen3.8-Max 结构辅助来源校验失败，已保留"+ocrLabel+"原始结果",QualityGate.GateOp.REORDER_OR_RECLASSIFY);actualProvider=paddleResult.usedProvider()+"+qwen-assist";}catch(CancelledException e){throw e;}catch(Exception e){String detail=JobService.safeDetail(e);warnings.add("Qwen3.8-Max 结构辅助失败，本页已保留"+ocrLabel+"原始结果"+(detail==null?"":"："+detail));blocks=sourceRecords;}}else warnings.add("Qwen3.8-Max 结构辅助未配置，本页仅保留"+ocrLabel+"结果");}
                    // F03/R08：空与失败分类——纯视觉页成功保留；真空白成功空页；其余空结果失败。
                    // 稀疏保护与目录恢复先行：空 OCR 也可达恢复分支，不在恢复前判失败。
                    if(QualityGate.totalChars(blocks)==0&&blocks.stream().allMatch(b->QualityGate.nonSpace(b.original())==0)){
                        if(QualityGate.isFigureOnly(blocks)&&!ScanTextEvidence.inspect(image).possibleText()){
                            warnings.add("仅获得图像区域，未取得正文转录；不能仅凭版面类型排除漏识文字，已保留原稿与图框");
                            blocks=simplify(blocks);warnings.add(ocrLabel+"结果尚未人工校对，不保证无错字、漏字或顺序错误");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,actualProvider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider,blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.VISUAL_ONLY);
                        }
                        if(QualityGate.isTrueBlank(image)){
                            warnings.add("[BLANK_EVIDENCE_V2] 本页图像信息极少，判定为近空白页，已保留原页图供对照");
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
                    blocks=recognizeOrEmpty(()->qwenOcr.recognize(image,layout,split,cancelled));blocks=recoverMissingText(image,blocks,layout,"qwen",warnings,cancelled);sourceRecords=List.copyOf(blocks);actualProvider="qwen";
                    if(QualityGate.totalChars(blocks)==0){
                        if(QualityGate.isFigureOnly(blocks)&&!ScanTextEvidence.inspect(image).possibleText()){
                            warnings.add("仅获得图像区域，未取得正文转录；不能仅凭版面类型排除漏识文字，已保留原稿与图框");
                            blocks=simplify(blocks);warnings.add("云端 OCR 与结构识别尚未人工校对，不保证无错字、漏字或顺序错误");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,actualProvider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider,blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.VISUAL_ONLY);
                        }
                        if(QualityGate.isTrueBlank(image)){
                            warnings.add("[BLANK_EVIDENCE_V2] 本页图像信息极少，判定为近空白页，已保留原页图供对照");
                            blocks=List.of();sourceRecords=List.of();
                            blocks=simplifyForBlank();warnings.add("云端 OCR 与结构识别尚未人工校对，不保证无错字、漏字或顺序错误");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,provider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider+"+blank",blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.BLANK_CONFIRMED);
                        }
                        throw new OcrException("OCR未返回可用文字，图像另有墨量，已标记失败供重试");
                    }
                    if(assist&&warnings.stream().noneMatch(w->w.startsWith(OcrTextRecovery.PARTIAL))){if(miniMax.configured()){try{byte[]png=qwenOcr.encodeWithin(image).bytes();List<Block> assisted=miniMax.assist(png,blocks,layout,cancelled);blocks=guardedAssist(sourceRecords,assisted,warnings,"MiniMax 结构辅助来源校验失败，已保留 Qwen OCR 原始结果",QualityGate.GateOp.MERGE_TEXT_STRUCTURE);if("vertical".equals(layout))blocks=verticalNormalizer.normalize(blocks,sourceRecords);actualProvider="qwen+minimax";List<Block> reviewed=review.review(image,blocks,cancelled);blocks=guardedAssist(blocks,reviewed,warnings,"局部复核来源校验失败，已保留辅助前结果",QualityGate.GateOp.REORDER_OR_RECLASSIFY);if(blocks.stream().anyMatch(b->b.source()!=null&&b.source().contains("qwen-review")))actualProvider="qwen+minimax+qwen-review";}catch(CancelledException e){throw e;}catch(Exception e){String detail=JobService.safeDetail(e);warnings.add("MiniMax 结构辅助失败，本页已保留 Qwen OCR 原始结果"+(detail==null?"":"："+detail));blocks=sourceRecords;}}else warnings.add("MiniMax 辅助未配置，本页仅使用 Qwen OCR，未完成结构辅助");}
                    blocks=simplify(blocks);warnings.add("云端 OCR 与结构识别尚未人工校对，不保证无错字、漏字或顺序错误");
                }else{blocks=recognizeOrEmpty(()->tesseract.recognize(image,layout,split,cancelled));blocks=recoverMissingText(image,blocks,layout,"local",warnings,cancelled);sourceRecords=List.copyOf(blocks);actualProvider="local";
                    if(QualityGate.totalChars(blocks)==0){
                        if(QualityGate.isFigureOnly(blocks)&&!ScanTextEvidence.inspect(image).possibleText()){
                            warnings.add("仅获得图像区域，未取得正文转录；不能仅凭版面类型排除漏识文字，已保留原稿与图框");
                            blocks=simplify(blocks);warnings.add("本地 OCR 仅为初稿，复杂图表、竖排和手写内容可能存在明显错字");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,actualProvider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider,blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.VISUAL_ONLY);
                        }
                        if(QualityGate.isTrueBlank(image)){
                            warnings.add("[BLANK_EVIDENCE_V2] 本页图像信息极少，判定为近空白页，已保留原页图供对照");
                            blocks=List.of();sourceRecords=List.of();
                            blocks=simplifyForBlank();warnings.add("本地 OCR 仅为初稿，复杂图表、竖排和手写内容可能存在明显错字");
                            BlockValidator.validate(blocks);
                            warnings.add(traceWarning(store.pdf(bookId),pageNumber,provider,layout));
                            return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider+"+blank",blocks,List.copyOf(warnings),false,null,sourceRecords),ProcessingResult.Category.BLANK_CONFIRMED);
                        }
                        throw new OcrException("本地 OCR 未返回可用文字，图像另有墨量，已标记失败供重试");
                    }
                    warnings.add("本地 OCR 仅为初稿，复杂图表、竖排和手写内容可能存在明显错字");}
            }finally{image.flush();}}
        }
        // 保真门和局部复核结束后才分类：只改派生块类型，原始 OCR sourceRecords 保持原样。
        AdvertisementFilter.Result advertisements=AdvertisementFilter.classify(blocks,previous.reviewed(),converter);
        blocks=advertisements.blocks();
        if(advertisements.marked()>0)warnings.add("已标记 "+advertisements.marked()+" 个独立页边广告块；原稿和原始识别记录保留可查看");
        if(advertisements.heldForReview()>0)warnings.add("有 "+advertisements.heldForReview()+" 个疑似广告块含已确认疑点，未自动隐藏，请人工复核");
        int bodyChars=blocks.stream().filter(b->!"advertisement".equals(b.type())).mapToInt(b->QualityGate.nonSpace(b.original())).sum();
        if(advertisements.marked()>0&&bodyChars==0)warnings.add("本页仅识别到页边广告，未确认为正文；请对照原图复核");
        // Always-on deterministic checks must not delay the first readable publication with cloud calls.
        if (comprehensibilityService != null) blocks=comprehensibilityService.checkLocal(blocks);
        BlockValidator.validate(blocks);warnings.add(traceWarning(store.pdf(bookId),pageNumber,actualProvider,layout));
        ProcessingResult.Category category=warnings.stream().anyMatch(w->w.startsWith(OcrTextRecovery.PARTIAL))?ProcessingResult.Category.TEXT_PARTIAL:bodyChars>0?ProcessingResult.Category.TEXT
            :(advertisements.marked()>0||QualityGate.isFigureOnly(blocks))?ProcessingResult.Category.VISUAL_ONLY:ProcessingResult.Category.TEXT;
        return new ProcessingResult(new Page(pageNumber,previous.width(),previous.height(),"READY",actualProvider,blocks,List.copyOf(warnings),false,null,sourceRecords),category);
        }
    }
    /** 阶段3：混合页检查——原生字符少但图像墨多时改走图像识别；预览图失败也保守改走图像识别。 */
    /**
     * C：手写/影印稿转写。分栏裁切放大后交视觉模型按提示词转写，产出块一律标"模型推断·待核对"，
     * 不并入印刷体 OCR 的质量结论，也不自动标记已人工校对。
     */
    private ProcessingResult processHandwriting(String bookId,int pageNumber,String layout,BooleanSupplier cancelled,Page previous)throws Exception{
        if(handwriting==null||!handwriting.configured())throw new OcrException("手写/影印稿转写未配置 Qwen 视觉凭据，请先在工具配置中填写 Qwen API Key");
        if(cancelled.getAsBoolean())throw new CancelledException();
        try(ImageArtifact artifact=renderForOcrArtifact(store.pdf(bookId),pageNumber)){
            if(artifact==null||artifact.image()==null)throw new OcrException("渲染页面图像为空");
            BufferedImage image=artifact.image();
            List<Block> transcribed=handwriting.transcribe(image,layout,cancelled);
            List<Block> blocks=simplify(transcribed);
            if(comprehensibilityService!=null)blocks=comprehensibilityService.checkLocal(blocks);
            List<String> warnings=new ArrayList<>();
            warnings.add(HandwritingTranscribeService.WARNING);
            warnings.add(traceWarning(store.pdf(bookId),pageNumber,HandwritingTranscribeService.SOURCE,layout));
            double width=previous==null||previous.width()<=0?image.getWidth():previous.width();
            double height=previous==null||previous.height()<=0?image.getHeight():previous.height();
            return new ProcessingResult(new Page(pageNumber,width,height,"READY",HandwritingTranscribeService.SOURCE,
                    blocks,List.copyOf(warnings),false,null,List.copyOf(transcribed)),
                    ProcessingResult.Category.TEXT_PARTIAL);
        }
    }

    private boolean preferOcrOverNative(Path pdfPath,int pageNumber,List<Block> nativeBlocks,List<String> warnings,BooleanSupplier cancelled){
        int chars=QualityGate.totalChars(nativeBlocks);
        double area=nativeBlocks.stream().filter(Objects::nonNull).map(Block::bbox).filter(b->b!=null&&b.length==4).mapToDouble(b->Math.max(0,b[2])*Math.max(0,b[3])).sum();
        if(chars>=200&&area>=0.05) return false;
        if(cancelled.getAsBoolean()) throw new CancelledException();
        final ImageArtifact previewArtifact;
        try{previewArtifact=renderArtifact(pdfPath,pageNumber,900);}
        catch(CancelledException e){throw e;}
        catch(Exception e){if(cancelled.getAsBoolean())throw new CancelledException();warnings.add("原生文字层覆盖不足且原图预览失败，已保守改走图像识别");return true;}
        if(previewArtifact==null || previewArtifact.image()==null){if(previewArtifact!=null) previewArtifact.close();warnings.add("原生文字层覆盖不足且原图预览为空，已保守改走图像识别");return true;}
        try(ImageArtifact artifactToClose=previewArtifact){
            BufferedImage preview = artifactToClose.image();
            try{
                boolean prefer=QualityGate.shouldPreferOcr(nativeBlocks,preview);
                if(prefer) warnings.add("检测到原生文字层覆盖不足（字符少、图像墨量大），已改用图像识别以防漏识扫描内容");
                return prefer;
            }finally{preview.flush();}
        }
    }
    /** 阶段3/F02：来源门禁——调用方显式声明操作类别；校验失败回退原始并警告，不悄悄交付。 */
    private List<Block> guardedAssist(List<Block> source,List<Block> assisted,List<String> warnings,String message,QualityGate.GateOp op){
        if(assisted==null||!QualityGate.check(source,assisted,op).accepted()){warnings.add(message);return source;}
        return assisted;
    }
    /** The user must explicitly enable cross-channel fallback; never try a hidden third channel. */
    private PaddleResult recognizePaddleWithFallback(BufferedImage image,String layout,boolean split,String provider,BooleanSupplier cancelled)throws Exception{
        try{return new PaddleResult(recognizeOrEmpty(()->paddle.recognize(image,layout,split,provider,cancelled)),provider,null);}
        catch(QuotaExceededException first){
            if(settings==null||!settings.state().fallbackEnabled())throw first;
            for(String alt:PaddleOcrPipeline.fallbackOrder(provider)){
                if(cancelled.getAsBoolean())throw new CancelledException();
                if(!paddle.configured(alt))continue;
                try{
                    List<Block> blocks=recognizeOrEmpty(()->paddle.recognize(image,layout,split,alt,cancelled));
                    String note="主通道"+PaddleOcrPipeline.channelLabel(provider)+"额度不足或无权限，已自动改用"+PaddleOcrPipeline.channelLabel(alt)+"；后续页面仍优先使用原通道";
                    return new PaddleResult(blocks,alt,note);
                }catch(QuotaExceededException ignored){}
            }
            throw first;
        }
    }
    @FunctionalInterface private interface OcrRead { List<Block> run() throws Exception; }
    private static List<Block> recognizeOrEmpty(OcrRead read) throws Exception {
        try { return read.run(); } catch (OcrNoTextException noText) { return List.of(); }
    }
    private List<Block> recoverMissingText(BufferedImage image,List<Block> initial,String layout,String provider,
                                          List<String> warnings,BooleanSupplier cancelled) throws Exception {
        if (!regionRecoveryEnabled) {
            if(initial!=null && initial.stream().anyMatch(b->"ocr-region-unresolved".equals(b.source())))
                warnings.add(OcrTextRecovery.PARTIAL+" 跨页扫描有区域未获得可用文字，已保留原图区域供核对");
            if (OcrTextRecovery.bodyChars(initial)==0 && ScanTextEvidence.inspect(image).possibleText())
                throw new OcrException("[OCR_EMPTY_UNRESOLVED] 原图疑似含文字但未取得正文；局部分区重识别未开启，保留原稿供核对");
            return initial;
        }
        OcrTextRecovery.Result result=OcrTextRecovery.recover(image,initial,layout,cancelled,(crop,direction,stop)-> {
            if(PaddleOcrPipeline.isPaddle(provider))return paddle.recognize(crop,direction,false,provider,stop);
            if("qwen".equals(provider))return qwenOcr.recognize(crop,direction,false,stop);
            return tesseract.recognize(crop,direction,false,stop);
        });
        if(result.warning()!=null)warnings.add(result.warning());
        return result.blocks();
    }
    private record PaddleResult(List<Block>blocks,String usedProvider,String fallbackNote){}
    private List<Block> simplifyForBlank(){return List.of();}
    private static String traceWarning(Path pdfPath,int pageNumber,String provider,String layout){
        try{long size=java.nio.file.Files.size(pdfPath);long mtime=java.nio.file.Files.getLastModifiedTime(pdfPath).toMillis();return "处理追溯：页"+pageNumber+" 通道"+provider+" 版式"+layout+" PDF指纹"+Long.toHexString(size*31+mtime);}
        catch(Exception ignored){return "处理追溯：页"+pageNumber+" 通道"+provider+" 版式"+layout;}
    }
    private record ChunkedOut(List<Block> blocks, String provider, List<String> warnings, boolean complete) {}

    /**
     * U5：分组增强尝试。条件：开关开启 + 协调器装配 + 结构服务可用 + 存在可核对文本组。
     * 任一条件不满足或执行失败返回 null，调用方走旧整页路径（可控回滚）。
     * 取消直接抛出（不回退，避免重复计费）。
     */
    private ChunkedOut tryChunkedAssist(String bookId, int pageNumber, List<Block> blocks,
                                        BufferedImage image, String layout, String baseProvider,
                                        BooleanSupplier cancelled) throws Exception {
        if (assistConfig == null || !assistConfig.isChunkedAssist()) return null;
        if (coordinator == null || planner == null || reviewClient == null) return null;
        if (!qwenLayout.configured()) return null;
        if (cancelled.getAsBoolean()) throw new CancelledException();
        QwenTaskPlanner.PlannedReview plan = planner.planReview(blocks,
                QwenTextReviewClient.PROMPT_VERSION, BookPresentationService.POLICY_VERSION);
        if (plan.chunks().isEmpty()) return null;
        Map<String, String> parentTexts = new HashMap<>();
        for (Block block : blocks) {
            if (block != null && block.id() != null && block.original() != null) {
                parentTexts.putIfAbsent(block.id(), block.original());
            }
        }
        // Queue references only source image plus crop descriptors, never all encoded regions.
        Map<String,QwenTaskPlanner.ChunkTask> descriptors=new HashMap<>();
        for (QwenTaskPlanner.ChunkTask chunk:plan.chunks()) descriptors.put(chunk.chunkId(),chunk);
        byte[] overview;
        try {
            overview = qwenOcr.encodeWithin(image).bytes();
        } catch (Exception e) {
            return null;
        }
        java.util.function.Function<String,byte[]> regionImages = key -> {
            if(cancelled.getAsBoolean()) throw new CancelledException();
            if("__overview__".equals(key)) return overview;
            QwenTaskPlanner.ChunkTask chunk=descriptors.get(key);
            return chunk==null?null:cropChunkRegion(image,blocks,chunk);
        };
        QwenAssistCoordinator.CoordinateResult result;
        try {
            result = coordinator.coordinateLazy(bookId, pageNumber,
                    blocks, parentTexts, plan, regionImages, null, layout, priority == null || priority.foreground(bookId, pageNumber), cancelled);
        } catch (CancelledException e) {
            throw e;
        } catch (Exception e) {
            // Once dispatched, do not silently repeat paid work through the full-page fallback.
            return new ChunkedOut(blocks, baseProvider,
                    List.of("分组增强未完成，保留基线；未重复发起整页增强"), false);
        }
        List<String> warnings = new ArrayList<>(result.warnings());
        if (result.failedChunks() > 0) {
            warnings.add(result.failedChunks() + " 组核对失败，已保留原文");
        }
        return new ChunkedOut(result.blocks(), baseProvider + result.actualProvider(), warnings, result.failedChunks() == 0 && result.deferredChunks() == 0);
    }

    /**
     * U5：组区域裁图。 owned 块 bbox 取全页归一化坐标并集（fullX = x0 + u*w 恒等，
     * 无局部坐标改写）；过小区域扩展到可辨尺寸；失败返回 null 走回滚。
     */
    private static byte[] cropChunkRegion(BufferedImage image, List<Block> blocks,
                                          QwenTaskPlanner.ChunkTask chunk) {
        try {
            double x0 = 1, y0 = 1, x1 = 0, y1 = 0;
            boolean found = false;
            java.util.Set<String> ownedIds = new java.util.HashSet<>();
            for (QwenTaskPlanner.OwnedRange owned : chunk.ownedRanges()) {
                ownedIds.add(owned.sourceId());
            }
            for (Block block : blocks) {
                if (block == null || !ownedIds.contains(block.id())) continue;
                double[] bbox = block.bbox();
                if (bbox == null || bbox.length < 4) continue;
                x0 = Math.min(x0, bbox[0]);
                y0 = Math.min(y0, bbox[1]);
                x1 = Math.max(x1, bbox[0] + bbox[2]);
                y1 = Math.max(y1, bbox[1] + bbox[3]);
                found = true;
            }
            if (!found) return null;
            int width = image.getWidth(), height = image.getHeight();
            // 过小区域扩展到页面 8%，保证可辨字（不为满足大小无限压缩原图）。
            double minSpan = 0.08;
            if (x1 - x0 < minSpan) {
                double center = (x0 + x1) / 2;
                x0 = Math.max(0, center - minSpan / 2);
                x1 = Math.min(1, center + minSpan / 2);
            }
            if (y1 - y0 < minSpan) {
                double center = (y0 + y1) / 2;
                y0 = Math.max(0, center - minSpan / 2);
                y1 = Math.min(1, center + minSpan / 2);
            }
            int px = Math.max(0, (int) (x0 * width));
            int py = Math.max(0, (int) (y0 * height));
            int pw = Math.min(width - px, (int) Math.ceil((x1 - x0) * width));
            int ph = Math.min(height - py, (int) Math.ceil((y1 - y0) * height));
            if (pw < 8 || ph < 8) return null;
            BufferedImage crop = image.getSubimage(px, py, pw, ph);
            ByteArrayOutputStream output = new ByteArrayOutputStream() {
                private static final int LIMIT=10*1024*1024;
                @Override public synchronized void write(int value) {
                    if(count>=LIMIT) throw new IllegalStateException("encoded region exceeds limit");
                    super.write(value);
                }
                @Override public synchronized void write(byte[] value,int offset,int length) {
                    if(length>LIMIT-count) throw new IllegalStateException("encoded region exceeds limit");
                    super.write(value,offset,length);
                }
            };
            if (!javax.imageio.ImageIO.write(crop, "png", output)) return null;
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) return null;
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    private AssistResult assistWithQwen(Path pdfPath,int pageNumber,List<Block>source,String layout,BooleanSupplier cancelled)throws Exception{if(!qwenLayout.configured())return new AssistResult(source,false,"Qwen3.8-Max 结构辅助未配置，本页仅保留原生文字层");try(ImageArtifact artifact=renderForOcrArtifact(pdfPath,pageNumber)){if(artifact==null||artifact.image()==null)return new AssistResult(source,false,"Qwen3.8-Max 结构辅助失败，渲染页面图像为空");BufferedImage image=artifact.image();try{return new AssistResult(qwenLayout.assist(qwenOcr.encodeWithin(image).bytes(),source,layout,cancelled),true,null);}catch(CancelledException e){throw e;}catch(Exception e){String detail=JobService.safeDetail(e);return new AssistResult(source,false,"Qwen3.8-Max 结构辅助失败，本页已保留原生文字层"+(detail==null?"":"："+detail));}}}
    /**
     * U4：可选增强阶段。只产生候选/派生结构，从不直接保存整页；调用方（JobService）
     * 经质量门与版本校验后最多发布一次合并结果。编排镜像 process() 内同名分支
     * （native/paddle/qwen 辅助段），基线已 simplify 的块再次 simplify 安全
     * （由原文重新转简体，幂等）。无原始转录的空白基线直接跳过。
     */
    public record EnrichResult(List<Block> blocks, String actualProvider, List<String> warnings, boolean complete) {
        public EnrichResult(List<Block> blocks, String actualProvider, List<String> warnings) {
            this(blocks, actualProvider, warnings, true);
        }
    }
    public EnrichResult enrichBaseline(String bookId,int pageNumber,Page baseline,String provider,String layout,BooleanSupplier cancelled)throws Exception{
        try(UsageContext.Scope ignored=UsageContext.open(bookId,pageNumber,"ENRICH_PAGE");
            QwenExecutionScope execution=QwenExecutionScope.open(bookId,pageNumber,gate,
                    priority != null && priority.foreground(bookId,pageNumber))){
        if(cancelled.getAsBoolean())throw new CancelledException();
        List<Block> sourceRecords=baseline.sourceRecords()==null?List.of():baseline.sourceRecords();
        List<String> warnings=new ArrayList<>();
        boolean complete = true;
        List<Block> blocks=new ArrayList<>(baseline.blocks()==null?List.of():baseline.blocks());
        String actualProvider=baseline.provider()==null?"":baseline.provider();
        if(sourceRecords.isEmpty()){
            warnings.add("基线无原始转录可供核对，跳过增强，保留基线可读版本");
            return new EnrichResult(List.copyOf(blocks),actualProvider,List.copyOf(warnings),complete);
        }
        boolean baseNative=actualProvider.startsWith("native");
        boolean wantPaddle=PaddleOcrPipeline.isPaddle(provider);
        boolean wantQwen="qwen".equals(provider);
        try(ImageArtifact artifact=renderForOcrArtifact(store.pdf(bookId),pageNumber)){
        if(artifact==null||artifact.image()==null)throw new OcrException("渲染页面图像为空");
        BufferedImage image=artifact.image();
        try{
            if(baseNative&&wantPaddle){
                ChunkedOut chunked=tryChunkedAssist(bookId,pageNumber,blocks,image,layout,actualProvider,cancelled);
                if(chunked!=null){blocks=chunked.blocks();actualProvider=chunked.provider();warnings.addAll(chunked.warnings());complete &= chunked.complete();}
                else{
                AssistResult result=assistWithQwen(store.pdf(bookId),pageNumber,blocks,layout,cancelled);
                complete &= result.assisted() && result.blocks()!=null && QualityGate.check(blocks,result.blocks(),QualityGate.GateOp.REORDER_OR_RECLASSIFY).accepted();
                blocks=guardedAssist(blocks,result.blocks(),warnings,"Qwen3.8-Max 结构辅助来源校验失败，已保留原生文字层",QualityGate.GateOp.REORDER_OR_RECLASSIFY);
                if(result.assisted())actualProvider="native+qwen-assist";else {complete=false; warnings.add(result.warning());}
                }
            }else if(baseNative&&wantQwen){
                if(miniMax.configured()){
                    try{List<Block> assisted=miniMax.assist(qwenOcr.encodeWithin(image).bytes(),blocks,layout,cancelled);blocks=simplify(guardedAssist(blocks,assisted,warnings,"MiniMax 结构辅助来源校验失败，已保留原生文字层结果",QualityGate.GateOp.REORDER_OR_RECLASSIFY));actualProvider="native+minimax";}
                    catch(CancelledException e){throw e;}
                    catch(Exception e){complete=false;blocks=new ArrayList<>(sourceRecords);warnings.add("MiniMax 结构辅助失败，本页保留原生文字层结果");}
                }else {complete=false; warnings.add("MiniMax 辅助未配置，本页仅使用原生文字层，未完成结构辅助");}
            }else if(PaddleOcrPipeline.isPaddle(actualProvider)||(!baseNative&&wantPaddle)){
                String ocrLabel=PaddleOcrPipeline.ocrShortLabel(PaddleOcrPipeline.isPaddle(actualProvider)?actualProvider:provider);
                QwenTocRecoveryService.RecoveryResult recovery=tocRecovery.recover(image,blocks,cancelled);
                if(recovery.warning()!=null)warnings.add(recovery.warning());
                if(recovery.recovered()){complete &= recovery.blocks()!=null && QualityGate.check(sourceRecords,recovery.blocks(),QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION).accepted();blocks=guardedAssist(sourceRecords,recovery.blocks(),warnings,"目录恢复来源校验失败，已保留"+ocrLabel+"原始结果",QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION);actualProvider=actualProvider+"+qwen-toc-recovery";}
                else if(recovery.attempted()){complete=false;blocks=guardedAssist(sourceRecords,recovery.blocks(),warnings,"目录恢复来源校验失败，已保留"+ocrLabel+"原始结果",QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION);}
                else{ChunkedOut chunked=tryChunkedAssist(bookId,pageNumber,blocks,image,layout,actualProvider,cancelled);
                if(chunked!=null){blocks=chunked.blocks();actualProvider=chunked.provider();warnings.addAll(chunked.warnings());complete &= chunked.complete();}
                else if(shouldRunLayoutAssist(recovery)&&qwenLayout.configured()){try{List<Block> assisted=qwenLayout.assist(qwenOcr.encodeWithin(image).bytes(),blocks,layout,cancelled);complete &= assisted!=null && QualityGate.check(sourceRecords,assisted,QualityGate.GateOp.REORDER_OR_RECLASSIFY).accepted();blocks=guardedAssist(sourceRecords,assisted,warnings,"Qwen3.8-Max 结构辅助来源校验失败，已保留"+ocrLabel+"原始结果",QualityGate.GateOp.REORDER_OR_RECLASSIFY);actualProvider=actualProvider+"+qwen-assist";}catch(CancelledException e){throw e;}catch(Exception e){complete=false;String detail=JobService.safeDetail(e);warnings.add("Qwen3.8-Max 结构辅助失败，本页已保留"+ocrLabel+"原始结果"+(detail==null?"":"："+detail));blocks=new ArrayList<>(sourceRecords);}}
                else {complete=false; warnings.add("Qwen3.8-Max 结构辅助未配置，本页仅保留"+ocrLabel+"结果");}}
            }else if("qwen".equals(actualProvider)||(!baseNative&&wantQwen)){
                if(miniMax.configured()){
                    try{byte[]png=qwenOcr.encodeWithin(image).bytes();List<Block> assisted=miniMax.assist(png,blocks,layout,cancelled);blocks=guardedAssist(sourceRecords,assisted,warnings,"MiniMax 结构辅助来源校验失败，已保留 Qwen OCR 原始结果",QualityGate.GateOp.MERGE_TEXT_STRUCTURE);if("vertical".equals(layout))blocks=verticalNormalizer.normalize(blocks,sourceRecords);actualProvider="qwen+minimax";List<Block> reviewed=review.review(image,blocks,cancelled);blocks=guardedAssist(blocks,reviewed,warnings,"局部复核来源校验失败，已保留辅助前结果",QualityGate.GateOp.REORDER_OR_RECLASSIFY);if(blocks.stream().anyMatch(b->b.source()!=null&&b.source().contains("qwen-review")))actualProvider="qwen+minimax+qwen-review";}
                    catch(CancelledException e){throw e;}
                    catch(Exception e){complete=false;String detail=JobService.safeDetail(e);warnings.add("MiniMax 结构辅助失败，本页已保留 Qwen OCR 原始结果"+(detail==null?"":"："+detail));blocks=new ArrayList<>(sourceRecords);}
                }else {complete=false; warnings.add("MiniMax 辅助未配置，本页仅使用 Qwen OCR，未完成结构辅助");}
            }else{
                warnings.add("本地基线无可选增强通道，保留基线可读版本");
                return new EnrichResult(List.copyOf(blocks),actualProvider,List.copyOf(warnings),complete);
            }
            blocks=simplify(blocks);
            AdvertisementFilter.Result advertisements=AdvertisementFilter.classify(blocks,baseline.reviewed(),converter);
            blocks=new ArrayList<>(advertisements.blocks());
            if(advertisements.marked()>0)warnings.add("已标记 "+advertisements.marked()+" 个独立页边广告块；原稿和原始识别记录保留可查看");
            if(advertisements.heldForReview()>0)warnings.add("有 "+advertisements.heldForReview()+" 个疑似广告块含已确认疑点，未自动隐藏，请人工复核");

            return new EnrichResult(List.copyOf(blocks),actualProvider,List.copyOf(warnings),complete);
        }finally{image.flush();}}
        }
    }
    /** Automatic review is policy-driven, not another reader-facing checkbox. */
    public boolean automaticCheckAvailable(String bookId,int pageNumber) {
        return comprehensibilityService!=null && comprehensibilityService.automaticAvailable(bookId);
    }
    public EnrichResult checkReadableBaseline(String bookId,int pageNumber,Page page,BooleanSupplier cancelled) throws Exception {
        if(comprehensibilityService==null) return new EnrichResult(page.blocks(),page.provider(),List.of(),true);
        if(cancelled.getAsBoolean())throw new CancelledException();
        ParagraphComprehensibilityService.Result checked=comprehensibilityService.check(bookId,pageNumber,page.blocks(),cancelled);
        String status=checked.complete()?ParagraphComprehensibilityService.COMPLETE:ParagraphComprehensibilityService.DEFERRED;
        String detail=checked.complete()?" 自动语义自检已结束，疑点仅为未确认建议；未发现疑点不代表文字完全正确"
                :" 自动语义自检部分未完成，保留原文；已结束 "+checked.completed()+"/"+checked.planned()+" 组";
        return new EnrichResult(checked.blocks(),page.provider(),List.of(status+detail),checked.complete());
    }

    private List<Block>simplify(List<Block>blocks)throws OcrException{List<Block>result=new ArrayList<>();for(Block b:blocks){String original=b.original()==null?"":b.original();String simplified=converter.toSimplified(original);List<ContentIssue>issues=mapIssues(original,b.issues(),converter);result.add(new Block(b.id(),b.type(),b.order(),b.bbox(),b.writingMode(),b.original(),simplified,b.confidence(),true,false,b.headingLevel(),b.source(),b.sourceIds(),b.suggestion(),b.sourceRect(),issues));}BlockValidator.validate(result);return List.copyOf(result);}
    /** J09/T58：问题区间映射（simplified 偏移）；确认元数据原样保留，不重建丢失。 */
    public static List<ContentIssue>mapIssues(String original,List<ContentIssue>input,TraditionalConverter converter)throws OcrException{List<ContentIssue>issues=new ArrayList<>();for(ContentIssue issue:input==null?List.<ContentIssue>of():input){if(issue.start()<0||issue.end()<=issue.start()||issue.end()>original.length())throw new OcrException("内容问题区间超出 OCR 原文范围");int simpleStart=converter.toSimplified(original.substring(0,issue.start())).length();int simpleEnd=converter.toSimplified(original.substring(0,issue.end())).length();String inferred=issue.inferredText()==null?null:converter.toSimplified(issue.inferredText());issues.add(new ContentIssue(issue.id(),issue.kind(),issue.start(),issue.end(),simpleStart,simpleEnd,issue.reason(),issue.resolved(),issue.replacement(),inferred,issue.resolution()));}return List.copyOf(issues);}
    static boolean shouldRunLayoutAssist(QwenTocRecoveryService.RecoveryResult recovery){return recovery!=null&&!recovery.recovered()&&!recovery.attempted();}
    private record AssistResult(List<Block>blocks,boolean assisted,String warning){}
}
