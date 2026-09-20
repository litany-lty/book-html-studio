package studio.bookhtml.service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.store.BookStore;

/**
 * 阶段2：受限解码工作进程（父进程侧）。
 *
 * <p>高风险渲染走独立 JVM：堆上限、运行时间上限、输出文件上限、临时磁盘上限；
 * 取消/超时终止子进程并回收临时文件；子进程环境清除云凭证。父子总预算通过
 * 主预算（在途字节）+ 同活 worker 数（默认 1）共同约束；JVM 堆上限不等于 RSS，
 * 极端原生内存仍如实报错，不 catch OOM 后继续当作健康。
 */
@Service
public class IsolatedPdfRender {
    static final long WORKER_HEAP_MB = 256;
    static final long TMP_MAX_BYTES = 512L * 1024 * 1024;
    static final long ISOLATE_FILE_BYTES = 30L * 1024 * 1024;
    static final long ISOLATE_PIXELS = 6_000_000L;

    private final Path tmpDir;
    private final RenderBudget budget;
    private final Semaphore isolatedPermits = new Semaphore(1, true);
    private final Set<String> riskyPages = ConcurrentHashMap.newKeySet();
    private final long timeoutSeconds;
    private final String javaBin;
    private final String classPath;
    private final long tmpMaxBytes;

    @org.springframework.beans.factory.annotation.Autowired
    public IsolatedPdfRender(BookStore store, RenderBudget budget) {
        this(store.renderTmpDir(), budget,
             envLong("RENDER_WORKER_TIMEOUT_S", 120),
             System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
             System.getProperty("java.class.path", ""), TMP_MAX_BYTES);
    }

    IsolatedPdfRender(Path tmpDir, RenderBudget budget, long timeoutSeconds, String javaBin, String classPath) {
        this(tmpDir, budget, timeoutSeconds, javaBin, classPath, TMP_MAX_BYTES);
    }

    IsolatedPdfRender(Path tmpDir, RenderBudget budget, long timeoutSeconds, String javaBin, String classPath, long tmpMaxBytes) {
        this.tmpDir = tmpDir;
        this.budget = budget;
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        this.javaBin = javaBin;
        this.classPath = classPath;
        this.tmpMaxBytes = tmpMaxBytes;
    }

    /**
     * R05：启动时回收上次崩溃遗留的渲染输出（uuid 输出文件永不跨重启复用）。
     * 只处理本目录的 render-*.png，不碰其他用途文件。
     */
    @jakarta.annotation.PostConstruct
    public void reclaimOrphanedOutputs() {
        if (tmpDir == null || !Files.isDirectory(tmpDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir, "render-*.png")) {
            for (Path p : stream) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    /** 风险分级只是启发式：大文件、高像素、历史超时页走隔离；不能识别全部恶意 PDF。 */
    public boolean shouldIsolate(Path pdf, long estimatedPixels, int pageNumber) {
        try {
            if (Files.size(pdf) > ISOLATE_FILE_BYTES) return true;
        } catch (IOException ignored) { }
        if (estimatedPixels > ISOLATE_PIXELS) return true;
        return riskyPages.contains(riskKey(pdf, pageNumber));
    }

    public BufferedImage renderIsolated(Path pdf, int pageNumber, boolean ocr, int width,
                                        long estimatedBytes, BooleanSupplier cancelled) throws Exception {
        Path source = pdf.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new ApiException(HttpStatus.NOT_FOUND, "页码不存在");
        Files.createDirectories(tmpDir);
        enforceTmpCap(null);
        if (usableBytes(tmpDir) < PdfRenderWorker.MAX_OUTPUT_BYTES + (100L * 1024 * 1024)) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "临时磁盘空间不足，无法渲染本页");
        }
        // 父子总预算：主预算在途字节 + 同活 worker 数上限；租约覆盖子进程存活期
        try (RenderBudget.Lease ignored = budget.acquire(estimatedBytes, cancelled)) {
            if (!isolatedPermits.tryAcquire(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
            }
            try {
                return runWorker(source, pageNumber, ocr, width, cancelled);
            } finally {
                isolatedPermits.release();
            }
        }
    }

    private BufferedImage runWorker(Path pdf, int pageNumber, boolean ocr, int width,
                                    BooleanSupplier cancelled) throws Exception {
        Path output = tmpDir.resolve("render-" + java.util.UUID.randomUUID() + ".png");
        List<String> command = workerCommand(javaBin, classPath, pdf, pageNumber, ocr ? "ocr" : "preview", width, output);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        sanitizeEnv(builder.environment());
        Process process = builder.start();
        try {
            // 可取消等待：分片轮询，取消即终止子进程
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    throw new CancelledException();
                }
                if (Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new CancelledException();
                }
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() >= deadline) {
                    riskyPages.add(riskKey(pdf, pageNumber));
                    process.destroyForcibly();
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "页面渲染超时，已终止解码进程，主服务仍可用");
                }
            }
            if (process.exitValue() != 0) {
                String detail = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "页面解码失败" + (detail.contains("解码") ? "：独立进程报告图像无法读取" : "，主服务仍可用"));
            }
            if (!Files.isRegularFile(output) || Files.size(output) > PdfRenderWorker.MAX_OUTPUT_BYTES) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "页面解码输出异常，主服务仍可用");
            }
            BufferedImage image = ImageIO.read(output.toFile());
            if (image == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "页面解码输出异常，主服务仍可用");
            return image;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } finally {
            try { process.getInputStream().close(); } catch (Exception ignored) { }
            try { process.getErrorStream().close(); } catch (Exception ignored) { }
            try { process.getOutputStream().close(); } catch (Exception ignored) { }
            Files.deleteIfExists(output);
            enforceTmpCap(null);
        }
    }

    /**
     * 子进程启动命令：exploded classpath 沿用 -cp；fat-jar（java -jar 启动，
     * java.class.path 为单个 jar）改用 -jar，由主入口识别 worker 参数分流。
     */
    static List<String> workerCommand(String javaBin, String classPath, Path pdf, int pageNumber,
                                      String mode, int width, Path output) {
        List<String> command = new ArrayList<>(List.of(javaBin, "-Xmx" + WORKER_HEAP_MB + "m",
                "-Djava.awt.headless=true"));
        if (isSingleJar(classPath)) {
            command.add("-jar");
            command.add(Path.of(classPath.strip()).toAbsolutePath().normalize().toString());
        } else {
            command.add("-cp");
            command.add(classPath);
            command.add(PdfRenderWorker.class.getName());
        }
        command.addAll(List.of(pdf.toString(), String.valueOf(pageNumber), mode,
                String.valueOf(width), output.toString()));
        return List.copyOf(command);
    }

    static boolean isSingleJar(String classPath) {
        if (classPath == null || classPath.isBlank() || classPath.contains(File.pathSeparator)) return false;
        String entry = classPath.strip();
        if (!entry.toLowerCase(Locale.ROOT).endsWith(".jar")) return false;
        try {
            return Files.isRegularFile(Path.of(entry));
        } catch (Exception e) {
            return false;
        }
    }

    private static String riskKey(Path pdf, int pageNumber) {
        return pdf.toString() + "#" + pageNumber;
    }

    /** 子进程不得携带云凭证。 */
    static void sanitizeEnv(Map<String, String> env) {
        List<String> drop = new ArrayList<>();
        for (String key : env.keySet()) {
            String upper = key.toUpperCase(Locale.ROOT);
            if (upper.contains("API_KEY") || upper.contains("APIKEY") || upper.contains("TOKEN")
                    || upper.contains("SECRET") || upper.contains("DASHSCOPE") || upper.contains("BAIDU")
                    || upper.contains("MINIMAX") || upper.contains("PADDLE")) {
                drop.add(key);
            }
        }
        drop.forEach(env::remove);
    }

    private long usableBytes(Path dir) {
        try { return Files.getFileStore(dir).getUsableSpace(); }
        catch (IOException e) { return Long.MAX_VALUE; }
    }

    private void enforceTmpCap(Path keep) throws IOException {
        enforceTmpCap(tmpDir, tmpMaxBytes, keep);
    }

    /** R05：配额清理只作用于传入的自有目录；全局磁盘不足只拒绝新任务，不删活跃文件。 */
    static void enforceTmpCap(Path dir, long maxBytes, Path keep) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (keep != null && p.equals(keep)) continue;
                if (Files.isRegularFile(p)) files.add(p);
            }
        }
        files.sort(Comparator.comparingLong(p -> {
            try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
        }));
        long total = 0;
        for (Path p : files) { try { total += Files.size(p); } catch (IOException ignored) { } }
        for (Path p : files) {
            if (total <= maxBytes) break;
            try {
                long size = Files.size(p);
                Files.deleteIfExists(p);
                total -= size;
            } catch (IOException ignored) { }
        }
    }

    private static long envLong(String name, long fallback) {
        try { String v = System.getenv(name); return v == null ? fallback : Long.parseLong(v.strip()); }
        catch (Exception ignored) { return fallback; }
    }
}
