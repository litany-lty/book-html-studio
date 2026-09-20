package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class SparsePageGuard {
    private static final Set<String> STRUCTURAL_TYPES=Set.of("table","figure","formula");

    GuardResult apply(BufferedImage image,List<Block>source){
        List<Block>original=source==null?List.of():List.copyOf(source);if(image==null||original.isEmpty()||!claimsDenseFullPageStructure(original)||!hasExtremelyLowVisualInformation(image))return new GuardResult(original,false,null);
        List<String>sourceIds=original.stream().filter(Objects::nonNull).map(Block::id).filter(Objects::nonNull).toList();Block fallback=new Block("sparse-page-original","figure",0,new double[]{0,0,1,1},"horizontal-tb","","",null,true,false,null,"sparse-page-guard",sourceIds.isEmpty()?List.of("sparse-page-original"):sourceIds,"原页图像信息极少，云端满页结构文字未作为正文采用；原始识别记录仍保留供核对",new double[]{0,0,image.getWidth(),image.getHeight()});
        return new GuardResult(List.of(fallback),true,"页面图像信息极少且与 PaddleOCR-VL 的满页结构结果不一致；未将该文字作为正文，已保留原页图和原始识别记录供核对");
    }

    static boolean claimsDenseFullPageStructure(List<Block>blocks){if(blocks==null||blocks.isEmpty()||blocks.size()>3)return false;Block dominant=blocks.stream().filter(Objects::nonNull).filter(b->STRUCTURAL_TYPES.contains(b.type())&&area(b.bbox())>=.82&&nonSpace(b.original())>0).max(java.util.Comparator.comparingDouble(b->area(b.bbox()))).orElse(null);if(dominant==null)return false;int all=blocks.stream().filter(Objects::nonNull).map(Block::original).mapToInt(SparsePageGuard::nonSpace).sum();return nonSpace(dominant.original())>=Math.max(1,(int)Math.ceil(all*.85));}

    static boolean hasExtremelyLowVisualInformation(BufferedImage image){double black=inkRatio(image,185),dark=inkRatio(image,210),light=inkRatio(image,235);return black<.0010&&dark<.0020&&light<.0100;}
    static double inkRatio(BufferedImage image,int threshold){int step=Math.max(1,Math.min(image.getWidth(),image.getHeight())/700);long ink=0,total=0;for(int y=0;y<image.getHeight();y+=step)for(int x=0;x<image.getWidth();x+=step){int rgb=image.getRGB(x,y),gray=(((rgb>>16)&255)*30+((rgb>>8)&255)*59+(rgb&255)*11)/100;if(gray<threshold)ink++;total++;}return ink/(double)Math.max(1,total);}
    private static double area(double[]bbox){return bbox==null||bbox.length!=4?0:Math.max(0,bbox[2])*Math.max(0,bbox[3]);}
    private static int nonSpace(String value){return value==null?0:(int)value.codePoints().filter(cp->!Character.isWhitespace(cp)).count();}

    record GuardResult(List<Block>blocks,boolean guarded,String warning){}
}
