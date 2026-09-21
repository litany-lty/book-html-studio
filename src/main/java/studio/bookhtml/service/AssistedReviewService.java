package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;

@Service
public class AssistedReviewService {
    static final int MAX_REGIONS = 3;
    private static final String REVIEW_MARKER = "qwen-review";
    private final QwenOcrClient qwen;
    public AssistedReviewService(QwenOcrClient qwen){this.qwen=qwen;}

    public List<Block> review(BufferedImage page,List<Block> blocks,BooleanSupplier cancelled){
        return review(page, blocks, selectCandidates(blocks, MAX_REGIONS), cancelled);
    }

    /** F01：接受受控目标列表；人工已确认（reviewed）的块默认跳过，不触发复识别。 */
    public List<Block> review(BufferedImage page,List<Block> blocks,List<Block> targets,BooleanSupplier cancelled){
        List<Block> result=new ArrayList<>(blocks);
        for(Block target:targets){
            if(cancelled.getAsBoolean())throw new CancelledException();
            if(target==null||target.reviewed())continue;
            int index=result.indexOf(target);if(index<0)continue;
            Crop crop=null;
            try{
                crop=crop(page,target.bbox());
                String candidate=reread(crop.image,
                        "vertical-rl".equals(target.writingMode())?"vertical":"horizontal",cancelled);
                String note=candidate.equals(target.original())?"Qwen 局部复识别与原文一致":"Qwen 局部复识别候选（未自动覆盖）："+candidate;
                result.set(index,withReviewNote(target,note));
            }
            catch(CancelledException e){throw e;}
            catch(Exception e){result.set(index,withReviewNote(target,"Qwen 局部复识别失败，保留原 OCR 文本"));}
            finally{if(crop!=null)crop.close();}
        }
        return List.copyOf(result);
    }

    /** F01：只追加复核记录；issues（含确认信息）与原 source 全部保留。 */
    static Block withReviewNote(Block target,String note){
        String suggestion=target.suggestion()==null||target.suggestion().isBlank()?note:target.suggestion()+"；"+note;
        String source=target.source()==null?"":target.source();
        if(!source.contains(REVIEW_MARKER))source=source.isEmpty()?REVIEW_MARKER:source+"+"+REVIEW_MARKER;
        return new Block(target.id(),target.type(),target.order(),target.bbox(),target.writingMode(),
                target.original(),target.simplified(),target.confidence(),true,target.reviewed(),
                target.headingLevel(),source,target.sourceIds(),suggestion,target.sourceRect(),target.issues());
    }

    /** 受控的单区域复识别；调用者拥有的整页图不由此关闭。 */
    String reread(BufferedImage region,String layout,BooleanSupplier cancelled)throws Exception{
        List<Block> reread=qwen.recognize(png(region),region.getWidth(),region.getHeight(),layout,cancelled);
        return reread.stream().map(Block::original).reduce("",String::concat).strip();
    }

    static List<Block> selectCandidates(List<Block> blocks,int limit){List<Block> selected=new ArrayList<>();for(Block b:blocks.stream().sorted(Comparator.comparingInt(Block::order)).toList()){if(b==null||b.reviewed())continue;if(b.suggestion()==null||b.suggestion().isBlank())continue;if(b.bbox()==null)continue;if(selected.stream().anyMatch(x->iou(x.bbox(),b.bbox())>.45))continue;selected.add(b);if(selected.size()>=limit)break;}return selected;}
    static double iou(double[]a,double[]b){double x=Math.max(a[0],b[0]),y=Math.max(a[1],b[1]),r=Math.min(a[0]+a[2],b[0]+b[2]),bottom=Math.min(a[1]+a[3],b[1]+b[3]);double intersection=Math.max(0,r-x)*Math.max(0,bottom-y);double union=a[2]*a[3]+b[2]*b[3]-intersection;return union<=0?0:intersection/union;}
    private static Crop crop(BufferedImage image,double[]b){double pad=.015;int x=Math.max(0,(int)Math.floor((b[0]-pad)*image.getWidth())),y=Math.max(0,(int)Math.floor((b[1]-pad)*image.getHeight()));int r=Math.min(image.getWidth(),(int)Math.ceil((b[0]+b[2]+pad)*image.getWidth())),bottom=Math.min(image.getHeight(),(int)Math.ceil((b[1]+b[3]+pad)*image.getHeight()));BufferedImage sub=image.getSubimage(x,y,Math.max(1,r-x),Math.max(1,bottom-y));if(sub.getWidth()>=196&&sub.getHeight()>=196)return new Crop(sub,false);double scale=Math.max(196d/sub.getWidth(),196d/sub.getHeight());int w=(int)Math.ceil(sub.getWidth()*scale),h=(int)Math.ceil(sub.getHeight()*scale);BufferedImage enlarged=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=enlarged.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(sub,0,0,w,h,null);}finally{g.dispose();}return new Crop(enlarged,true);}
    private static byte[] png(BufferedImage image)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    /** F01：裁图生命周期进 finally；owned=false 表示引用调用者的子图，只 flush 自建放大图。 */
    record Crop(BufferedImage image,boolean owned) implements AutoCloseable{
        public void close(){if(owned&&image!=null)image.flush();}
    }
}
