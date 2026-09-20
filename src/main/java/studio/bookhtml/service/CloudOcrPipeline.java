package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;

@Service
public class CloudOcrPipeline {
    private static final long QWEN_MAX_PIXELS=8_388_608L;
    private final QwenOcrClient qwen;private final PdfService pdf;
    public CloudOcrPipeline(QwenOcrClient qwen,PdfService pdf){this.qwen=qwen;this.pdf=pdf;}
    public List<Block> recognize(BufferedImage full,String layout,boolean splitSpreads,BooleanSupplier cancelled)throws Exception{
        if(splitSpreads&&shouldSplit(full)){int mid=full.getWidth()/2;BufferedImage left=full.getSubimage(0,0,mid,full.getHeight()),right=full.getSubimage(mid,0,full.getWidth()-mid,full.getHeight());List<Block>all=new ArrayList<>();List<Half>halves="horizontal".equals(layout)?List.of(new Half("L",left,0,mid),new Half("R",right,mid,full.getWidth()-mid)):List.of(new Half("R",right,mid,full.getWidth()-mid),new Half("L",left,0,mid));for(Half half:halves){if(cancelled.getAsBoolean())throw new CancelledException();if(!hasContent(half.image))continue;Encoded encoded=encodeWithin(half.image);List<Block>local=qwen.recognize(encoded.bytes,encoded.width,encoded.height,layout,cancelled);for(Block b:local)all.add(remap(b,half.prefix,half.x,half.w,full.getWidth()));}if(!all.isEmpty())return resequence(all,layout);}
        BufferedImage bounded=boundPixels(full,QWEN_MAX_PIXELS);try{Encoded encoded=encodeWithin(bounded);return qwen.recognize(encoded.bytes,encoded.width,encoded.height,layout,cancelled);}finally{if(bounded!=full)bounded.flush();}
    }
    static boolean shouldSplit(BufferedImage image){if(image.getWidth()<image.getHeight()*1.12)return false;int center=image.getWidth()/2,halfBand=Math.max(3,image.getWidth()/100);double centerInk=inkRatio(image,center-halfBand,center+halfBand),leftInk=inkRatio(image,image.getWidth()/8,image.getWidth()*3/8),rightInk=inkRatio(image,image.getWidth()*5/8,image.getWidth()*7/8);return centerInk<.025&&centerInk<Math.max(.003,Math.min(leftInk,rightInk)*.45);}
    static Block remap(Block b,String prefix,int x,int halfWidth,int fullWidth){double[]v=b.bbox();double[]box={(x+v[0]*halfWidth)/fullWidth,v[1],v[2]*halfWidth/fullWidth,v[3]};String id=prefix+"-"+b.id();return new Block(id,b.type(),b.order(),box,b.writingMode(),b.original(),b.simplified(),b.confidence(),b.uncertain(),b.reviewed(),b.headingLevel(),"qwen:"+prefix,List.of(id),b.suggestion(),b.sourceRect());}
    private static List<Block> resequence(List<Block>blocks,String layout){List<Block>sorted=new ArrayList<>(blocks);boolean vertical="vertical".equals(layout)||("auto".equals(layout)&&sorted.stream().filter(b->"vertical-rl".equals(b.writingMode())).count()>sorted.size()/2);sorted.sort(vertical?Comparator.<Block>comparingDouble(b->-b.bbox()[0]).thenComparingDouble(b->b.bbox()[1]):Comparator.<Block>comparingDouble(b->b.bbox()[1]).thenComparingDouble(b->b.bbox()[0]));List<Block>out=new ArrayList<>();int order=0;for(Block b:sorted)out.add(new Block(b.id(),b.type(),order++,b.bbox(),b.writingMode(),b.original(),b.simplified(),b.confidence(),b.uncertain(),b.reviewed(),b.headingLevel(),b.source(),b.sourceIds(),b.suggestion(),b.sourceRect()));return out;}
    private static boolean hasContent(BufferedImage image){return inkRatio(image,0,image.getWidth())>.001;}
    private static double inkRatio(BufferedImage image,int fromX,int toX){long ink=0,total=0;int step=Math.max(1,Math.min(image.getWidth(),image.getHeight())/500);for(int y=0;y<image.getHeight();y+=step)for(int x=Math.max(0,fromX);x<Math.min(image.getWidth(),toX);x+=step){int rgb=image.getRGB(x,y);int gray=(((rgb>>16)&255)*30+((rgb>>8)&255)*59+(rgb&255)*11)/100;if(gray<210)ink++;total++;}return ink/(double)Math.max(1,total);}
    private static BufferedImage boundPixels(BufferedImage source,long max){long pixels=(long)source.getWidth()*source.getHeight();if(pixels<=max)return source;double scale=Math.sqrt(max/(double)pixels);int w=(int)Math.floor(source.getWidth()*scale),h=(int)Math.floor(source.getHeight()*scale);BufferedImage out=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=out.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,w,h,null);}finally{g.dispose();}return out;}
    Encoded encodeWithin(BufferedImage source)throws Exception{BufferedImage current=source;try{for(int i=0;i<6;i++){byte[] bytes=pdf.png(current);if(bytes.length<=9_500_000)return new Encoded(bytes,current.getWidth(),current.getHeight());int w=Math.max(196,(int)(current.getWidth()*.8)),h=Math.max(196,(int)(current.getHeight()*.8));BufferedImage next=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=next.createGraphics();try{g.drawImage(current,0,0,w,h,null);}finally{g.dispose();}if(current!=source)current.flush();current=next;}throw new OcrException("送识图片压缩后仍超过 10MB 限制");}finally{if(current!=source)current.flush();}}
    record Half(String prefix,BufferedImage image,int x,int w){}
    record Encoded(byte[]bytes,int width,int height){}
}
