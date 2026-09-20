package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QwenTocRecoveryServiceTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void candidateRequiresDirectoryEvidenceAndDoesNotTreatOrdinaryBodyAsContents(){
        assertTrue(QwenTocRecoveryService.isCandidate(List.of(block("span",new double[]{.55,.1,.3,.3},"目錄", "paddle-span","text"))));
        assertTrue(QwenTocRecoveryService.isCandidate(List.of(block("toc",new double[]{.55,.1,.3,.3},directory("紫微斗數目錄條目"),"paddle","text"))));
        String body="第一章說明十二宮的基本概念。第二章討論三方四正的關係。本文列出二〇二六年的五個例子，但不是目錄。";
        assertFalse(QwenTocRecoveryService.isCandidate(List.of(block("body",new double[]{.55,.1,.3,.3},body,"paddle","text"))));
    }

    @Test void recoversNamedRegionPreservesNonContentsFigureAndMarksUnreadable()throws Exception{
        BufferedImage image=image();Block toc=block("toc",new double[]{.55,.08,.35,.30},directory("原條目"),"paddle","text");Block figure=block("chart",new double[]{.08,.58,.30,.30},"命盤圖", "paddle","figure");AtomicReference<HttpRequest>captured=new AtomicReference<>();
        QwenTocRecoveryService service=new QwenTocRecoveryService(config(),json,request->{captured.set(request);return response(200,envelope(regions("right-top",true,directory("恢復□條目"))));});

        QwenTocRecoveryService.RecoveryResult result=service.recover(image,List.of(toc,figure),()->false);

        assertTrue(result.candidate());assertTrue(result.recovered());assertEquals(2,result.blocks().size());Block recovered=result.blocks().get(0);assertEquals("qwen-toc-recovery",recovered.source());assertEquals("horizontal-tb",recovered.writingMode());assertEquals(4,recovered.issues().size());assertTrue(recovered.issues().stream().allMatch(issue->"unreadable".equals(issue.kind())));assertTrue(result.blocks().stream().anyMatch(b->b.id().equals("chart")&&b.type().equals("figure")));
        JsonNode body=json.readTree(requestBody(captured.get()));long imageCount=java.util.stream.StreamSupport.stream(body.at("/messages/0/content").spliterator(),false).filter(n->"image_url".equals(n.path("type").asText())).count();assertEquals(4,imageCount);
    }

    @Test void missingOrShortRegionResponseFallsBackWithoutDeletingSources()throws Exception{
        BufferedImage image=image();List<Block>source=List.of(block("toc",new double[]{.55,.08,.35,.30},directory("原條目"),"paddle","text"));
        String missing="{\"regions\":[{\"id\":\"right-top\",\"isContents\":true,\"text\":\"甲 一\\n乙 二\\n丙 三\"}]}";
        String shortText=regions("right-top",true,"甲 一\n乙 二");
        for(String content:List.of(missing,shortText)){QwenTocRecoveryService service=client(content);QwenTocRecoveryService.RecoveryResult result=service.recover(image,source,()->false);assertTrue(result.candidate());assertFalse(result.recovered());assertSame(source.get(0),result.blocks().get(0));assertTrue(result.warning().contains("保留 PaddleOCR-VL"));}
    }

    @Test void crossingContentsAndNonContentsBoundaryCausesWholePageFallback()throws Exception{
        BufferedImage image=image();Block crossing=block("wide",new double[]{.58,.20,.30,.62},directory("跨區條目"),"paddle","figure");QwenTocRecoveryService service=client(regions("right-top",true,directory("恢復條目")));
        QwenTocRecoveryService.RecoveryResult result=service.recover(image,List.of(crossing),()->false);
        assertFalse(result.recovered());assertEquals(List.of("wide"),result.blocks().stream().map(Block::id).toList());assertTrue(result.warning().contains("保留 PaddleOCR-VL"));
    }

    @Test void realDirectoryFixturesHaveIndependentDividersWhenAvailable()throws Exception{
        List<Path>images=List.of(Path.of("verification/first20-source/page-002.png"),Path.of("verification/first20-source/page-003.png"),Path.of("verification/first20-source/page-004.png"));Assumptions.assumeTrue(images.stream().allMatch(Files::isRegularFile));
        for(Path path:images){BufferedImage image=ImageIO.read(path.toFile());QwenTocRecoveryService.PageSplits splits=QwenTocRecoveryService.findHorizontalDividers(image).orElseThrow();System.out.printf("%s left=%.4f right=%.4f%n",path.getFileName(),splits.left(),splits.right());assertTrue(splits.left()>=.4&&splits.left()<=.65);assertTrue(splits.right()>=.4&&splits.right()<=.65);image.flush();}
    }

    @Test void candidateWithoutReliableDividerStillAllowsRegularLayoutAssist(){BufferedImage image=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,400,300);g.dispose();QwenTocRecoveryService service=new QwenTocRecoveryService(config(),json,request->{throw new AssertionError("无分隔线不得发目录恢复请求");});QwenTocRecoveryService.RecoveryResult result=service.recover(image,List.of(block("toc",new double[]{.55,.08,.35,.30},directory("紫微斗數目錄條目"),"paddle","text")),()->false);assertTrue(result.candidate());assertFalse(result.attempted());assertFalse(result.recovered());assertTrue(PageProcessor.shouldRunLayoutAssist(result));assertTrue(result.warning().contains("继续使用常规"));image.flush();}

    @Test void detectsRegularVerticalLeaderColumnsButRejectsOrdinaryLargeFigure(){BufferedImage toc=verticalLeaders(8);List<Block>emptyFigure=List.of(block("chart",new double[]{.05,.05,.9,.9},"","paddle","figure"));QwenTocRecoveryService.TocPreflight plan=QwenTocRecoveryService.preflight(toc,emptyFigure);assertEquals("single-page-vertical",plan.mode());assertEquals(8,plan.evidenceColumns());assertEquals(2,plan.boxes().size());BufferedImage ordinary=new BufferedImage(800,1000,BufferedImage.TYPE_INT_RGB);Graphics2D g=ordinary.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,800,1000);g.setColor(Color.BLACK);g.drawRect(100,120,600,700);g.drawLine(100,500,700,500);g.dispose();assertFalse(QwenTocRecoveryService.preflight(ordinary,emptyFigure).candidate());toc.flush();ordinary.flush();}

    @Test void singlePageRecoveryUsesOneRequestAndReplacesEmptyVisualWithoutLosingSourceEvidence()throws Exception{BufferedImage image=verticalLeaders(8);Block empty=block("chart",new double[]{.03,.05,.94,.9},"","paddle","figure");AtomicReference<HttpRequest>captured=new AtomicReference<>();String answer=verticalRegions(2,4);QwenTocRecoveryService service=new QwenTocRecoveryService(config(),json,request->{captured.set(request);return response(200,envelope(answer));});QwenTocRecoveryService.RecoveryResult result=service.recover(image,List.of(empty),()->false);assertTrue(result.recovered());assertEquals(2,result.blocks().size());assertTrue(result.blocks().stream().allMatch(b->"qwen-toc-recovery".equals(b.source())&&b.sourceIds().contains("chart")));JsonNode body=json.readTree(requestBody(captured.get()));long images=java.util.stream.StreamSupport.stream(body.at("/messages/0/content").spliterator(),false).filter(n->"image_url".equals(n.path("type").asText())).count();assertEquals(3,images);assertTrue(result.warning().contains("检测 8 条"));image.flush();}

    @Test void leaderCountMismatchFallsBackWithoutDeletingVisual()throws Exception{BufferedImage image=verticalLeaders(8);Block empty=block("chart",new double[]{.03,.05,.94,.9},"","paddle","figure");QwenTocRecoveryService service=client(verticalRegions(2,6));QwenTocRecoveryService.RecoveryResult result=service.recover(image,List.of(empty),()->false);assertTrue(result.attempted());assertFalse(result.recovered());assertEquals("chart",result.blocks().get(0).id());assertTrue(result.warning().contains("条目数与本地点引线证据不一致"));image.flush();}

    private QwenTocRecoveryService client(String content)throws Exception{return new QwenTocRecoveryService(config(),json,request->response(200,envelope(content)));}
    private QwenAssistProperties config(){QwenAssistProperties config=new QwenAssistProperties();config.setApiKey("test-key");return config;}
    private static BufferedImage image(){BufferedImage image=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,400,300);g.setColor(Color.BLACK);g.fillRect(0,150,400,2);g.dispose();return image;}
    private static BufferedImage verticalLeaders(int count){BufferedImage image=new BufferedImage(800,1000,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,800,1000);g.setColor(Color.DARK_GRAY);for(int i=0;i<count;i++){int x=210+i*50;for(int y=270;y<850;y+=12)g.fillRect(x,y,2,4);}g.dispose();return image;}
    private static String directory(String prefix){return prefix+"甲內容……一○四\n"+prefix+"乙內容……一三\n"+prefix+"丙內容……一四\n"+prefix+"丁內容……一五";}
    private static Block block(String id,double[]bbox,String text,String source,String type){return new Block(id,type,0,bbox,"vertical-rl",text,text,.8,false,false,null,source,List.of(id),null,new double[]{1,1,20,20});}
    private String regions(String trueId,boolean isContents,String text)throws Exception{return json.writeValueAsString(Map.of("regions",List.of(region("right-top",trueId.equals("right-top")&&isContents,trueId.equals("right-top")?text:""),region("right-bottom",trueId.equals("right-bottom")&&isContents,trueId.equals("right-bottom")?text:""),region("left-top",trueId.equals("left-top")&&isContents,trueId.equals("left-top")?text:""),region("left-bottom",trueId.equals("left-bottom")&&isContents,trueId.equals("left-bottom")?text:""))));}
    private String verticalRegions(int regionCount,int linesPerRegion)throws Exception{java.util.ArrayList<Map<String,Object>>regions=new java.util.ArrayList<>();for(int region=1;region<=regionCount;region++){StringBuilder text=new StringBuilder();for(int line=1;line<=linesPerRegion;line++){if(!text.isEmpty())text.append('\n');text.append("第").append(region).append('-').append(line).append("條……").append(line+10);}regions.add(region("vertical-band-"+region,true,text.toString()));}return json.writeValueAsString(Map.of("regions",regions));}
    private static Map<String,Object>region(String id,boolean contents,String text){return Map.of("id",id,"isContents",contents,"text",text);}
    private String envelope(String content)throws Exception{return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));}
    @SuppressWarnings("unchecked")private static HttpResponse<InputStream>response(int status,String body){HttpResponse<InputStream>response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));return response;}
    private static String requestBody(HttpRequest request){ByteArrayOutputStream output=new ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>(){@Override public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}@Override public void onNext(ByteBuffer item){byte[]bytes=new byte[item.remaining()];item.get(bytes);output.writeBytes(bytes);}@Override public void onError(Throwable throwable){throw new AssertionError(throwable);}@Override public void onComplete(){}});return output.toString(StandardCharsets.UTF_8);}
}
