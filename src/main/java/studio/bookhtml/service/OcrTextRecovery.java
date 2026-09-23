package studio.bookhtml.service;

import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One bounded regional pass through the already selected provider. No background
 * queue, cross-provider fallback, watermark erasure, guessed text or recursive retry.
 */
public final class OcrTextRecovery {
    public static final String PARTIAL = "[OCR_RECOVERY_PARTIAL]";
    public static final String RECOVERED = "[OCR_REGION_RECOVERY]";
    private static final String MISSING = "原图存在笔画或纹理但未获得可靠转录，不能据此判为空白或纯插图";
    @FunctionalInterface public interface Recognizer {
        List<Block> recognize(BufferedImage crop, String layout, BooleanSupplier cancelled) throws Exception;
    }
    public record Result(List<Block> blocks, String warning) {}
    private record Region(int x,int y,int w,int h,double[] core) {}
    private OcrTextRecovery() {}

    public static int bodyChars(List<Block> blocks) {
        return blocks==null ? 0 : blocks.stream().filter(Objects::nonNull)
                .filter(b->!Set.of("advertisement","page-number").contains(b.type()))
                .mapToInt(b->QualityGate.nonSpace(b.original())).sum();
    }
    public static Result recover(BufferedImage image,List<Block> source,String layout,
                                 BooleanSupplier cancelled,Recognizer recognizer) throws Exception {
        return recover(image, source, layout, cancelled, 4, recognizer);
    }

    public static Result recover(BufferedImage image,List<Block> source,String layout,
                                 BooleanSupplier cancelled,int maxPhysicalStarts,Recognizer recognizer) throws Exception {
        List<Block> initial=source==null?List.of():List.copyOf(source);
        if(bodyChars(initial)>0)return new Result(initial,
                initial.stream().anyMatch(b->"ocr-region-unresolved".equals(b.source()))
                    ? PARTIAL+" 跨页扫描有区域未获得可用文字，已保留原图区域供核对" : null);
        check(cancelled);
        ScanTextEvidence.Evidence evidence=ScanTextEvidence.inspect(image);
        check(cancelled);
        if(!evidence.possibleText())return new Result(initial,null);
        List<Block> recovered=new ArrayList<>();
        // Preserve every original region/folio as evidence, not as inferred text.
        for(Block b:initial)recovered.add(copy(b,b.id(),recovered.size(),b.bbox(),b.sourceRect(),b.source(),b.sourceIds()));
        int unresolved=0, found=0;
        int startsRemaining = Math.max(0, maxPhysicalStarts);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        BooleanSupplier stopped=()->cancelled.getAsBoolean()||System.nanoTime()>=deadline;
        List<Region> regions=regions(image,layout);
        for(int index=0;index<regions.size();index++) {
            check(cancelled);
            if(System.nanoTime()>=deadline) {
                if(found==0)throw new OcrException("局部分区重识别已达总时限，保留原稿供核对");
                unresolved += retainMissing(recovered, regions, index);
                break;
            }
            if(startsRemaining<=0) {
                if(found==0)throw new OcrException("[OCR_EMPTY_UNRESOLVED] 局部分区重识别预算不足，保留原稿供核对");
                unresolved += retainMissing(recovered, regions, index);
                break;
            }
            Region r=regions.get(index);
            BufferedImage crop=image.getSubimage(r.x,r.y,r.w,r.h);
            try {
                if(ScanTextEvidence.inspect(crop).nearBlank())continue;
                startsRemaining--;
                List<Block> result;
                try { result=recognizer.recognize(crop,layout,stopped); }
                catch(OcrNoTextException empty) { result=List.of(); }
                catch(CancelledException stoppedCall) {
                    check(cancelled);
                    if(System.nanoTime()>=deadline)throw new OcrException("局部分区重识别已达总时限，保留原稿供核对");
                    throw stoppedCall;
                }
                check(cancelled);
                if(System.nanoTime()>=deadline)throw new OcrException("局部分区重识别已达总时限，保留原稿供核对");
                if(result==null)throw new OcrException("局部分区识别未返回有效结果");
                BlockValidator.validate(result);
                int accepted=0;
                for(Block b:result) {
                    if(bodyChars(List.of(b))==0)continue;
                    double[] bb=b.bbox();
                    double[] box={(r.x+bb[0]*r.w)/image.getWidth(),(r.y+bb[1]*r.h)/image.getHeight(),
                            bb[2]*r.w/image.getWidth(),bb[3]*r.h/image.getHeight()};
                    double cx=box[0]+box[2]/2,cy=box[1]+box[3]/2;
                    // Non-overlapping ownership cores suppress duplicated overlap text
                    // without deleting identical words that occur elsewhere on the page.
                    if(cx<r.core[0]||cx>=r.core[0]+r.core[2]||cy<r.core[1]||cy>=r.core[1]+r.core[3])continue;
                    String id="region-"+(index+1)+"-"+b.id();
                    if(id.length()>120)id="region-"+UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    double[] raw={box[0]*image.getWidth(),box[1]*image.getHeight(),box[2]*image.getWidth(),box[3]*image.getHeight()};
                    recovered.add(copy(b,id,recovered.size(),box,raw,(b.source()==null?"ocr":b.source())+":region-recovery",List.of(id)));
                    found++;accepted++;
                }
                if(accepted==0) {
                    unresolved++;
                    String id="region-unresolved-"+(index+1);
                    recovered.add(new Block(id,"figure",recovered.size(),r.core.clone(),"horizontal-tb","","",null,true,false,null,
                            "ocr-region-unresolved",List.of(id),MISSING,null));
                }
            } catch (CancelledException cancelledCall) {
                throw cancelledCall;
            } catch (Exception failedRegion) {
                check(cancelled);
                if(found==0)throw failedRegion;
                // Stop on transport, quota, deadline or malformed output. Do not discard
                // validated earlier regions and do not send more paid requests.
                unresolved += retainMissing(recovered, regions, index);
                break;
            } finally { crop.flush(); }
        }
        check(cancelled);
        if(found==0)throw new OcrException("[OCR_EMPTY_UNRESOLVED] "+MISSING+"；局部分区重识别仍无可用文字，请对照原稿校对或主动更换识别通道");
        BlockValidator.validate(recovered);
        // A region producing a few words does not prove complete recovery under a dark
        // overprint. Always retain partial status until a human has checked the source.
        return new Result(List.copyOf(recovered),PARTIAL+" "+RECOVERED+" 已恢复部分局部转录，原图和原始区域保留；"+
                (unresolved>0?unresolved+" 个区域未获得可用文字；":"")+"防盗印、水印、手写或网格干扰可能仍有漏字，需对照原稿核对");
    }
    private static int retainMissing(List<Block> out, List<Region> regions, int from) {
        for(int i=from;i<regions.size();i++) {
            String id="region-unresolved-"+(i+1);
            out.add(new Block(id,"figure",out.size(),regions.get(i).core.clone(),"horizontal-tb","","",null,true,false,null,
                    "ocr-region-unresolved",List.of(id),MISSING+"；该区域因请求失败或时限未完成",null));
        }
        return regions.size()-from;
    }
    static Block missingHalf(String prefix,int x,int width,int fullWidth,int height,int order) {
        String id="unresolved-half-"+prefix;
        return new Block(id,"figure",order,new double[]{x/(double)fullWidth,0,width/(double)fullWidth,1},
                "horizontal-tb","","",null,true,false,null,"ocr-region-unresolved",List.of(id),
                MISSING,new double[]{x,0,width,height});
    }
    private static Block copy(Block b,String id,int order,double[] box,double[] raw,String source,List<String> sources) {
        List<ContentIssue> issues=new ArrayList<>();
        for(ContentIssue i:b.issues())issues.add(new ContentIssue("ri-"+UUID.nameUUIDFromBytes((id+"/"+issues.size()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),i.kind(),i.start(),i.end(),
                i.simplifiedStart(),i.simplifiedEnd(),i.reason(),i.resolved(),i.replacement(),i.inferredText(),i.resolution()));
        return new Block(id,b.type(),order,box.clone(),b.writingMode(),b.original(),b.simplified(),b.confidence(),true,false,
                b.headingLevel(),source,sources,b.suggestion(),raw,issues);
    }
    private static List<Region> regions(BufferedImage image,String layout) {
        List<double[]> cores=new ArrayList<>();
        if("vertical".equals(layout))for(int i=3;i>=0;i--)cores.add(new double[]{i/4.0,0,.25,1});
        else if("horizontal".equals(layout))for(int i=0;i<4;i++)cores.add(new double[]{0,i/4.0,1,.25});
        else { // No guessed writing direction: quadrant order is retained as uncertain.
            cores.add(new double[]{.5,0,.5,.5});cores.add(new double[]{.5,.5,.5,.5});
            cores.add(new double[]{0,0,.5,.5});cores.add(new double[]{0,.5,.5,.5});
        }
        List<Region> out=new ArrayList<>();
        for(double[] c:cores) {
            int x=Math.max(0,(int)Math.floor((c[0]-.025)*image.getWidth()));
            int y=Math.max(0,(int)Math.floor((c[1]-.025)*image.getHeight()));
            int right=Math.min(image.getWidth(),(int)Math.ceil((c[0]+c[2]+.025)*image.getWidth()));
            int bottom=Math.min(image.getHeight(),(int)Math.ceil((c[1]+c[3]+.025)*image.getHeight()));
            out.add(new Region(x,y,right-x,bottom-y,c));
        }
        return out;
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancelledException();
    }
}
