package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.BooleanSupplier;

@Service
public class PaddleOcrPipeline {
    private static final List<String> CHANNEL_ORDER = List.of("paddle-aistudio", "ppocr");
    private final PaddleOcrClient paddle;private final PaddleAiStudioClient aiStudio;private final BaiduPpOcrClient ppocr;private final CloudOcrPipeline encoder;
    public PaddleOcrPipeline(PaddleOcrClient paddle,PaddleAiStudioClient aiStudio,BaiduPpOcrClient ppocr,CloudOcrPipeline encoder){this.paddle=paddle;this.aiStudio=aiStudio;this.ppocr=ppocr;this.encoder=encoder;}
    public List<Block>recognize(BufferedImage full,String layout,boolean splitSpreads,BooleanSupplier cancelled)throws Exception{
        return recognize(full,layout,splitSpreads,"paddle-aistudio",cancelled);
    }
    public List<Block>recognize(BufferedImage full,String layout,boolean splitSpreads,String provider,BooleanSupplier cancelled)throws Exception{
        if(!isPaddle(provider))throw new OcrException("未知 PaddleOCR 通道");
        if(splitSpreads&&CloudOcrPipeline.shouldSplit(full)){int mid=full.getWidth()/2;BufferedImage left=full.getSubimage(0,0,mid,full.getHeight()),right=full.getSubimage(mid,0,full.getWidth()-mid,full.getHeight());List<Half>halves="horizontal".equals(layout)?List.of(new Half("L",left,0,mid),new Half("R",right,mid,full.getWidth()-mid)):List.of(new Half("R",right,mid,full.getWidth()-mid),new Half("L",left,0,mid));List<Block>all=new ArrayList<>();for(Half half:halves){if(cancelled.getAsBoolean())throw new CancelledException();if(!hasContent(half.image()))continue;CloudOcrPipeline.Encoded encoded=encoder.encodeWithin(half.image());List<Block>local;try{local=recognizeEncoded(encoded,layout,provider,cancelled);}catch(OcrNoTextException empty){all.add(OcrTextRecovery.missingHalf(half.prefix(),half.x(),half.width(),full.getWidth(),full.getHeight(),all.size()));continue;}for(Block block:local)all.add(remap(block,half.prefix(),half.x(),half.width(),full.getWidth(),all.size()));}return List.copyOf(all);}
        return recognizeEncoded(encoder.encodeWithin(full),layout,provider,cancelled);
    }
    public static boolean isPaddle(String provider){return "paddle".equals(provider)||"paddle-aistudio".equals(provider)||"ppocr".equals(provider);}
    /** 该通道是否已配置凭据，可参与识别与降级。 */
    public boolean configured(String provider){
        return switch(provider==null?"":provider){
            case "paddle-aistudio" -> aiStudio.configured();
            case "ppocr" -> ppocr.configured();
            case "paddle" -> paddle.configured();
            default -> false;
        };
    }
    /** Only the other publicly configured OCR channel can be an explicit fallback. */
    public static List<String> fallbackOrder(String provider){
        List<String> order = new ArrayList<>(CHANNEL_ORDER);
        order.remove(provider);
        return List.copyOf(order);
    }
    public static String channelLabel(String provider){
        return switch(provider==null?"":provider){
            case "paddle-aistudio" -> "PaddleOCR-VL · AI Studio";
            case "ppocr" -> "PP-OCRv6 · 百度智能云";
            case "paddle" -> "PaddleOCR-VL · 百度智能云";
            default -> provider;
        };
    }
    static String ocrShortLabel(String provider){return "ppocr".equals(provider)?"PP-OCRv6":"PaddleOCR-VL";}
    private List<Block>recognizeEncoded(CloudOcrPipeline.Encoded encoded,String layout,String provider,BooleanSupplier cancelled)throws OcrException{
        return switch(provider){
            case "paddle-aistudio" -> aiStudio.recognize(encoded.bytes(),encoded.width(),encoded.height(),layout,cancelled);
            case "ppocr" -> ppocr.recognize(encoded.bytes(),encoded.width(),encoded.height(),layout,cancelled);
            default -> paddle.recognize(encoded.bytes(),encoded.width(),encoded.height(),layout,cancelled);
        };
    }
    static Block remap(Block b,String prefix,int x,int halfWidth,int fullWidth,int order){double[]v=b.bbox();double[]box={(x+v[0]*halfWidth)/fullWidth,v[1],v[2]*halfWidth/fullWidth,v[3]};String id=prefix+"-"+b.id();String source=b.source()==null||b.source().isBlank()?"paddle":b.source();return new Block(id,b.type(),order,box,b.writingMode(),b.original(),b.simplified(),b.confidence(),b.uncertain(),b.reviewed(),b.headingLevel(),source+":"+prefix,List.of(id),b.suggestion(),b.sourceRect());}
    private static boolean hasContent(BufferedImage image){return !QualityGate.isTrueBlank(image);}
    record Half(String prefix,BufferedImage image,int x,int width){}
}
