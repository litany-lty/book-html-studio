package studio.bookhtml;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BookHtmlStudioApplication {
    public static void main(String[] args) throws Exception {
        // fat-jar 下隔离解码子进程经 -jar 附带 worker 参数启动，直接进入 worker，不启动 Spring。
        if (studio.bookhtml.service.PdfRenderWorker.isWorkerInvocation(args)) {
            studio.bookhtml.service.PdfRenderWorker.main(args);
            return;
        }
        SpringApplication.run(BookHtmlStudioApplication.class, args);
    }
}
