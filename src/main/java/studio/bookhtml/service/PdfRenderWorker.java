package studio.bookhtml.service;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 阶段2：独立解码工作进程入口。
 *
 * <p>复用本 JAR 的 PdfService 渲染逻辑，不起第二套 Web 服务、不依赖外部 PDF 工具。
 * 只接收本地书页与尺寸参数，不携带云 API 密钥，不拼 shell 指令（父进程用 argv 直接启动）。
 * 用法：PdfRenderWorker &lt;pdfPath&gt; &lt;page&gt; &lt;preview|ocr&gt; &lt;width&gt; &lt;outputPng&gt;
 */
public final class PdfRenderWorker {
    static final long MAX_OUTPUT_BYTES = 50L * 1024 * 1024;

    private PdfRenderWorker() {}

    /** fat-jar 下子进程走 -jar 入口，需先识别 worker 参数，避免启动 Spring。 */
    public static boolean isWorkerInvocation(String[] args) {
        if (args == null || args.length != 5) return false;
        try {
            if (!Files.isRegularFile(Path.of(args[0]))) return false;
            if (Integer.parseInt(args[1]) < 1) return false;
            if (!"preview".equals(args[2]) && !"ocr".equals(args[2])) return false;
            int width = Integer.parseInt(args[3]);
            if (width < 196 || width > 2400) return false;
            return Path.of(args[4]).getFileName() != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: PdfRenderWorker <pdf> <page> <preview|ocr> <width> <output>");
            System.exit(2);
            return;
        }
        Path pdf = Path.of(args[0]).toAbsolutePath().normalize();
        int page = Integer.parseInt(args[1]);
        String mode = args[2];
        int width = Integer.parseInt(args[3]);
        Path output = Path.of(args[4]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(pdf) || page < 1
                || (!"preview".equals(mode) && !"ocr".equals(mode))
                || width < 196 || width > 2400) {
            System.err.println("ERROR 非法的渲染参数");
            System.exit(2);
            return;
        }
        PdfService service = new PdfService(width, PdfService::decoderAvailable);
        BufferedImage image = "ocr".equals(mode)
                ? service.renderForOcr(pdf, page)
                : service.render(pdf, page, width);
        try {
            Files.createDirectories(output.getParent());
            if (!ImageIO.write(image, "png", output.toFile())) throw new java.io.IOException("PNG encoder unavailable");
            long size = Files.size(output);
            if (size > MAX_OUTPUT_BYTES) {
                Files.deleteIfExists(output);
                System.err.println("ERROR 渲染输出超过上限");
                System.exit(3);
                return;
            }
            System.out.println("OK " + image.getWidth() + " " + image.getHeight() + " " + size);
        } finally {
            image.flush();
        }
    }
}
