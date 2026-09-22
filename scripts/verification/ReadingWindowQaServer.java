import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;
import studio.bookhtml.BookHtmlStudioApplication;
import studio.bookhtml.domain.*;
import studio.bookhtml.service.PageProcessor;
import studio.bookhtml.service.ProcessingResult;
import studio.bookhtml.service.JobService;
import studio.bookhtml.api.JobRequest;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Browser fixture only: real HTTP/queue/CAS, gated mock processor, no cloud calls.
 * Compiled separately with the existing test classpath, never packaged into the application. */
public class ReadingWindowQaServer {
    public static final String BOOK = "bbbbbbbb-2222-2222-2222-222222222222";
    private static final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();
    // Admission order is the scheduling contract. Independent worker arrival times
    // are not a dispatch trace: OS scheduling and mock setup can reverse them.
    private static final List<Map<String, Object>> admissions = new CopyOnWriteArrayList<>();
    private static final Object callLock = new Object();
    private static final java.util.Deque<CountDownLatch> gates = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private static Map<String, Object> recordCall(int page, CountDownLatch gate) {
        synchronized (callLock) {
            Map<String, Object> call = Collections.synchronizedMap(new LinkedHashMap<>());
            call.put("pageNumber", page); call.put("startedAt", System.currentTimeMillis());
            call.put("completed", false);
            // Keep the release FIFO paired with the observable physical call order.
            gates.add(gate); calls.add(call);
            return call;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("需要全新临时数据目录与独立端口");
        int port = Integer.parseInt(args[1]);
        if (port < 1024 || port > 65535 || port == 18765) throw new IllegalArgumentException("不能使用日常服务端口");
        Path data = Path.of(args[0]).toAbsolutePath().normalize();
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path parent = data.getParent().toRealPath();
        if ((!parent.startsWith(temp) && !parent.startsWith(Path.of("/private/tmp")))
                || Files.exists(data)) throw new IllegalArgumentException("只接受尚不存在的临时数据目录");
        seed(data);
        SeedBook.main(new String[]{data.toString()});
        // CLI properties override every inherited environment value. Mockito replaces the
        // processor before injection; loopback URLs and dummy keys are a second safety net.
        SpringApplication app = new SpringApplication(BookHtmlStudioApplication.class, MockConfiguration.class);
        app.run("--server.address=127.0.0.1", "--server.port=" + port, "--app.data-dir=" + data,
                "--app.paddle-aistudio.access-token=qa-mock-not-a-credential",
                "--app.paddle-aistudio.job-url=http://127.0.0.1:9/disabled",
                "--app.baidu-ocr-api-key=", "--app.baidu-ocr-secret-key=",
                "--app.paddle-job-url=http://127.0.0.1:9/disabled", "--app.ppocr.url=http://127.0.0.1:9/disabled",
                "--app.dashscope-api-key=", "--app.minimax-api-key=", "--app.qwen-assist.api-key=",
                "--app.qwen-assist.enabled=false", "--book.decision.api-key=", "--book.decision.mode=OFF");
    }

    static void seed(Path data) throws Exception {
        Path book = data.resolve("books").resolve(BOOK);
        Files.createDirectories(book.resolve("pages"));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Instant now = Instant.now();
        json.writeValue(book.resolve("book.json").toFile(),
                new Book(BOOK, "随读处理合成书", "reading-window-fixture.pdf", 60, now, now, 0, 0));
        try (PDDocument pdf = new PDDocument()) {
            for (int n = 1; n <= 60; n++) {
                PDPage page = new PDPage(new PDRectangle(600, 800)); pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                    content.newLineAtOffset(60, 720); content.showText("SOURCE PAGE " + n); content.endText();
                }
                json.writeValue(book.resolve("pages/" + n + ".json").toFile(), Page.pending(n, 600, 800));
            }
            pdf.save(book.resolve("source.pdf").toFile());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MockConfiguration {
        @Bean static BeanPostProcessor mockProcessor() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof JobService jobs) {
                        JobService observed = spy(jobs);
                        doAnswer(invocation -> {
                            Job accepted = (Job) invocation.callRealMethod();
                            admissions.add(Map.of("pageNumber", accepted.currentPage(), "jobId", accepted.id()));
                            return accepted;
                        }).when(observed).submitReserved(any(UUID.class), anyString(), any(JobRequest.class));
                        return observed;
                    }
                    if (!(bean instanceof PageProcessor)) return bean;
                    PageProcessor mock = mock(PageProcessor.class);
                    try {
                        // U4：随读 worker 走两阶段（基线阻塞可门控，增强直通基线块）。
                        when(mock.processBaseline(anyString(), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                                .thenAnswer(invocation -> {
                                    String id = invocation.getArgument(0); int n = invocation.getArgument(1);
                                    if (!BOOK.equals(id)) throw new IllegalArgumentException("禁止处理非随读合成书");
                                    CountDownLatch latch = new CountDownLatch(1);
                                    Map<String, Object> call = recordCall(n, latch);
                                    try {
                                        if (!latch.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放模拟识别");
                                        String text = "这是第 " + n + " 页的模拟识别内容，仅用于随读导航与安全保存验证。";
                                        Block block = new Block("mock-" + n, "text", 0, new double[]{.1,.1,.8,.1},
                                                "horizontal-tb", text, text, .95, false, false, null, "local",
                                                List.of("mock-" + n), null, null, List.of());
                                        call.put("completed", true); call.put("finishedAt", System.currentTimeMillis());
                                        return new ProcessingResult(new Page(n,600,800,"READY","mock",
                                                List.of(block), List.of("模拟识别，无云调用"), false, null, List.of(block)),
                                                ProcessingResult.Category.TEXT);
                                    } finally { gates.remove(latch); }
                                });
                        when(mock.enrichBaseline(anyString(), anyInt(), any(), anyString(), anyString(), any()))
                                .thenAnswer(invocation -> {
                                    studio.bookhtml.domain.Page baseline = invocation.getArgument(2);
                                    List<Block> blocks = baseline.blocks() == null ? List.of() : baseline.blocks();
                                    return new PageProcessor.EnrichResult(List.copyOf(blocks),
                                            baseline.provider() == null ? "mock" : baseline.provider(),
                                            List.of());
                                });
                        when(mock.process(anyString(), anyInt(), anyString(), anyString(), anyBoolean(), anyBoolean(), any()))
                                .thenAnswer(invocation -> {
                                    String id = invocation.getArgument(0); int n = invocation.getArgument(1);
                                    if (!BOOK.equals(id)) throw new IllegalArgumentException("禁止处理非随读合成书");
                                    CountDownLatch latch = new CountDownLatch(1);
                                    Map<String, Object> call = recordCall(n, latch);
                                    try {
                                        if (!latch.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放模拟识别");
                                        String text = "这是第 " + n + " 页的模拟识别内容，仅用于随读导航与安全保存验证。";
                                        Block block = new Block("mock-" + n, "text", 0, new double[]{.1,.1,.8,.1},
                                                "horizontal-tb", text, text, .95, false, false, null, "local",
                                                List.of("mock-" + n), null, null, List.of());
                                        call.put("completed", true); call.put("finishedAt", System.currentTimeMillis());
                                        return new ProcessingResult(new Page(n,600,800,"READY","mock",
                                                List.of(block), List.of("模拟识别，无云调用"), false, null, List.of(block)),
                                                ProcessingResult.Category.TEXT);
                                    } finally { gates.remove(latch); }
                                });
                    } catch (Exception error) { throw new IllegalStateException(error); }
                    return mock;
                }
            };
        }
        @Bean QaController qaController() { return new QaController(); }
    }

    @RestController
    static class QaController {
        @GetMapping("/__qa/reading-calls") public Map<String, Object> calls() {
            List<Map<String, Object>> snapshot = calls.stream().map(call -> {
                synchronized (call) { return Map.copyOf(call); }
            }).toList();
            return Map.of("fixture", BOOK, "calls", snapshot, "admissions", List.copyOf(admissions), "waiting", !gates.isEmpty());
        }
        @PostMapping("/__qa/release") public Map<String, Object> release() {
            synchronized (callLock) {
                CountDownLatch oldest = gates.pollFirst();
                if (oldest != null) oldest.countDown();
                return Map.of("released", oldest != null);
            }
        }
    }
}
