import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import studio.bookhtml.BookHtmlStudioApplication;
import studio.bookhtml.config.WriteOriginFilter;
import java.nio.file.*;
import java.io.IOException;

/** Test-classpath only; loopback socket, simulated remote peer, real upload/store/restart. */
public final class LanLibraryQaServer {
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("temporary data directory and port required");
        Path dir=Path.of(args[0]).toAbsolutePath().normalize();Path parent=dir.getParent().toRealPath();
        if(!parent.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))throw new IllegalArgumentException("temporary directory only");
        int port=Integer.parseInt(args[1]);if(port<1024||port>65535||port==18765)throw new IllegalArgumentException("isolated port required");
        Path sample=parent.resolve("sample.pdf");
        if(!Files.exists(sample))try(var pdf=new PDDocument()){
            for(int n=1;n<=3;n++){
                var page=new PDPage(PDRectangle.A4);pdf.addPage(page);
                try(var text=new PDPageContentStream(pdf,page)){
                    text.beginText();text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),24);
                    text.newLineAtOffset(60,740);text.showText("LAN LIBRARY SAMPLE PAGE "+n);text.endText();
                }
            }
            pdf.save(sample.toFile());
        }
        new SpringApplication(BookHtmlStudioApplication.class,TestPeer.class).run(
            "--server.address=127.0.0.1","--server.port="+port,"--app.data-dir="+dir,
            "--app.paddle-aistudio.access-token=","--app.baidu-ocr-api-key=","--app.baidu-ocr-secret-key=",
            "--app.dashscope-api-key=","--app.minimax-api-key=","--app.qwen-assist.api-key=",
            "--app.qwen-assist.enabled=false","--book.decision.api-key=","--book.decision.mode=OFF");
    }
    @Configuration(proxyBeanMethods=false) static class TestPeer{
        @Bean static BeanPostProcessor simulatedPeer(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                if(!(bean instanceof WriteOriginFilter delegate))return bean;
                ReflectionTestUtils.setField(delegate,"bindAddress","0.0.0.0");
                return new WriteOriginFilter(){
                    @Override public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain)throws IOException,ServletException{
                        HttpServletRequest request=(HttpServletRequest)req;
                        if("lan-test".equals(request.getHeader("X-QA-Reader")))request=new HttpServletRequestWrapper(request){
                            @Override public String getRemoteAddr(){return "192.0.2.50";}
                        };
                        delegate.doFilter(request,res,chain);
                    }
                };
            }
        };}
    }
}
