package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenderIsolationTest {
    @TempDir Path temp;

    private RenderBudget quietBudget() {
        return new RenderBudget(8, Long.MAX_VALUE, 5000, () -> 0, () -> 8L * 1024 * 1024 * 1024);
    }

    private Path blankPdf(String name) throws Exception {
        Path pdf = temp.resolve(name);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(600, 800)));
            document.save(pdf.toFile());
        }
        return pdf;
    }

    @Test void sanitizeEnvDropsCloudCredentials() {
        java.util.Map<String, String> env = new java.util.HashMap<>(Map.of(
                "BAIDU_OCR_API_KEY", "x", "PADDLEOCR_ACCESS_TOKEN", "y",
                "DASHSCOPE_API_KEY", "z", "MY_SECRET", "s", "PATH", "/bin", "PORT", "18765"));
        IsolatedPdfRender.sanitizeEnv(env);
        assertFalse(env.containsKey("BAIDU_OCR_API_KEY"));
        assertFalse(env.containsKey("PADDLEOCR_ACCESS_TOKEN"));
        assertFalse(env.containsKey("DASHSCOPE_API_KEY"));
        assertFalse(env.containsKey("MY_SECRET"));
        assertEquals("/bin", env.get("PATH"));
        assertEquals("18765", env.get("PORT"));
    }

    @Test void shouldIsolateFlagsLargeInput() throws Exception {
        Path pdf = blankPdf("small.pdf");
        IsolatedPdfRender render = new IsolatedPdfRender(temp.resolve("tmp"), quietBudget(), 30,
                "/bin/false", "");
        assertFalse(render.shouldIsolate(pdf, 1000, 1));
        assertTrue(render.shouldIsolate(pdf, IsolatedPdfRender.ISOLATE_PIXELS + 1, 1));
    }

    @Test void workerRendersBlankPageAndCleansTmp() throws Exception {
        Path pdf = blankPdf("worker.pdf");
        Path tmp = temp.resolve("wtmp");
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin"
                + java.io.File.separator + "java";
        IsolatedPdfRender render = new IsolatedPdfRender(tmp, quietBudget(), 120, javaBin,
                System.getProperty("java.class.path", ""));
        BufferedImage image = render.renderIsolated(pdf, 1, false, 600, 8L * 1024 * 1024, () -> false);
        try {
            assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
        } finally {
            image.flush();
        }
        try (var stream = Files.list(tmp)) {
            assertTrue(stream.filter(p -> Files.isRegularFile(p)).findAny().isEmpty(), "临时渲染文件必须回收");
        }
    }

    @Test void workerTimeoutKeepsMainServiceUsable() throws Exception {
        Path pdf = blankPdf("hang.pdf");
        Path script = temp.resolve("hang-java");
        Files.writeString(script, "#!/bin/sh\nexec /bin/sleep 30\n");
        assertTrue(script.toFile().setExecutable(true));
        IsolatedPdfRender render = new IsolatedPdfRender(temp.resolve("htmp"), quietBudget(), 5,
                script.toString(), "");
        long start = System.nanoTime();
        ApiException timeout = assertThrows(ApiException.class,
                () -> render.renderIsolated(pdf, 1, false, 600, 8L * 1024 * 1024, () -> false));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, timeout.status());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 25, "超时必须终止子进程而非等满 sleep");
        // 主服务仍可读书：普通进程内渲染不受影响
        PdfService direct = new PdfService(600, codec -> true);
        BufferedImage image = direct.render(pdf, 1, 600);
        try {
            assertTrue(image.getWidth() > 0);
        } finally {
            image.flush();
        }
    }

    @Test void workerInvocationRecognition() throws Exception {
        Path pdf = blankPdf("detect.pdf");
        assertTrue(PdfRenderWorker.isWorkerInvocation(new String[]{pdf.toString(), "1", "ocr", "900", temp.resolve("o.png").toString()}));
        assertFalse(PdfRenderWorker.isWorkerInvocation(new String[]{}));
        assertFalse(PdfRenderWorker.isWorkerInvocation(new String[]{pdf.toString(), "0", "ocr", "900", "o.png"}));
        assertFalse(PdfRenderWorker.isWorkerInvocation(new String[]{pdf.toString(), "1", "bad", "900", "o.png"}));
        assertFalse(PdfRenderWorker.isWorkerInvocation(new String[]{temp.resolve("missing.pdf").toString(), "1", "ocr", "900", "o.png"}));
        assertFalse(PdfRenderWorker.isWorkerInvocation(new String[]{"--server.port=18765"}));
    }

    @Test void workerCommandUsesJarModeForFatJar() throws Exception {
        Path pdf = blankPdf("cmd.pdf");
        Path out = temp.resolve("o.png");
        Path fakeJar = temp.resolve("app.jar");
        Files.write(fakeJar, new byte[]{1, 2, 3});
        java.util.List<String> jar = IsolatedPdfRender.workerCommand("/bin/java", fakeJar.toString(), pdf, 2, "ocr", 900, out);
        assertTrue(jar.contains("-jar"));
        assertFalse(jar.contains(PdfRenderWorker.class.getName()));
        assertEquals(List.of("/bin/java", "-Xmx256m", "-Djava.awt.headless=true", "-jar",
                fakeJar.toAbsolutePath().normalize().toString(),
                pdf.toString(), "2", "ocr", "900", out.toString()), jar);
        java.util.List<String> cp = IsolatedPdfRender.workerCommand("/bin/java", "a.jar" + java.io.File.pathSeparator + "b.jar", pdf, 2, "ocr", 900, out);
        assertTrue(cp.contains("-cp"));
        assertTrue(cp.contains(PdfRenderWorker.class.getName()));
        assertFalse(IsolatedPdfRender.isSingleJar("a.jar" + java.io.File.pathSeparator + "b.jar"));
        assertFalse(IsolatedPdfRender.isSingleJar("classes"));
    }

    @Test void samePageEvidenceComputedOnce() throws Exception {
        Path pdfPath = temp.resolve("source.pdf");
        Files.writeString(pdfPath, "pdf");
        Path cacheDir = temp.resolve("cache/paddle");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("f.json"),
                "{\"result\":{\"pages\":[{\"meta\":{\"page_width\":400,\"page_height\":400},\"layouts\":[]}]}}");
        BookService books = mock(BookService.class);
        PdfService pdf = mock(PdfService.class);
        when(books.pdfPath("book")).thenReturn(pdfPath);
        CountDownLatch entered = new CountDownLatch(1);
        when(pdf.renderForOcr(eq(pdfPath), eq(1))).thenAnswer(inv -> {
            entered.countDown();
            Thread.sleep(300);
            BufferedImage image = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 400, 400);
            g.dispose();
            return image;
        });
        IssueImageService service = new IssueImageService(books, pdf,
                TestConfigs.config(temp, "", ""), new ObjectMapper(), new TraditionalConverter());
        ContentIssue issue = new ContentIssue("i", "suspected", 0, 1, 0, 1, "图像依据", false, null, null);
        Block block = new Block("b", "text", 0, new double[]{.2, .125, .15, .6}, "vertical-rl",
                "甲乙", "甲乙", .8, true, false, null, "paddle", List.of("b"), null, null, List.of(issue));
        Block source = new Block("b", "text", 0, block.bbox(), block.writingMode(), "甲乙", "甲乙",
                .8, true, false, null, "paddle", List.of("b"), null, null);
        Page page = new Page(1, 400, 400, "READY", "paddle", List.of(block), List.of(), false, null, List.of(source));

        // 摘要零渲染
        service.summarize(page);
        verify(pdf, never()).renderForOcr(any(), anyInt());

        // 同页并发只计算一次
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<IssueImageService.Snippet>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) futures.add(pool.submit(() -> service.locateOne("book", page, "i")));
            for (Future<IssueImageService.Snippet> f : futures) assertNotNull(f.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        verify(pdf, times(1)).renderForOcr(eq(pdfPath), eq(1));
    }
}
