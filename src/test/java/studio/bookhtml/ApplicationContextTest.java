package studio.bookhtml;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.IOException;
import java.nio.file.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class ApplicationContextTest {
    private static final Path DATA=createTemp();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("app.data-dir",DATA::toString);registry.add("app.dashscope-api-key",()->"");registry.add("app.minimax-api-key",()->"");}
    @Test void contextLoads(){}
    private static Path createTemp(){try{return Files.createTempDirectory("book-html-context-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
}
