package studio.bookhtml.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class PdfService {
    static final long MAX_RENDER_PIXELS = 8_388_608L;
    static final long MAX_OCR_PIXELS = 12_000_000L;
    static final int MAX_RENDER_EDGE = 32_768;
    private final int maxImageWidth;
    private final DecoderProbe decoderProbe;
    private final RenderBudget budget;
    private final IsolatedPdfRender isolated;
    @Autowired
    public PdfService(AppProperties properties, RenderBudget budget, IsolatedPdfRender isolated) { this(properties.maxImageWidth(), PdfService::decoderAvailable, budget, isolated); }
    public PdfService(AppProperties properties) { this(properties.maxImageWidth(), PdfService::decoderAvailable, null, null); }
    PdfService(int maxImageWidth, DecoderProbe decoderProbe) {
        this(maxImageWidth, decoderProbe, null, null);
    }
    PdfService(int maxImageWidth, DecoderProbe decoderProbe, RenderBudget budget, IsolatedPdfRender isolated) {
        this.maxImageWidth = maxImageWidth;
        this.decoderProbe = decoderProbe;
        this.budget = budget;
        this.isolated = isolated;
    }

    public PdfInfo inspect(Path pdf) throws IOException {
        try (PDDocument document = loadPdf(pdf)) {
            return new PdfInfo(document.getNumberOfPages());
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "暂不支持加密 PDF");
        }
    }

    /**
     * 误判修复：空口令加密字典（如老 Adobe 文件）自动以空口令解开；
     * 只有真密码文件才抛 InvalidPasswordException（调用方按“暂不支持加密”处理）。
     * 能成功加载即视为可读，不再以 isEncrypted() 误拒已解密文档。
     */
    static PDDocument loadPdf(Path pdf) throws IOException {
        try {
            return Loader.loadPDF(pdf.toFile());
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException first) {
            try {
                return Loader.loadPDF(pdf.toFile(), "");
            } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
                first.addSuppressed(e);
                throw first;
            }
        }
    }
    public Dimensions dimensions(Path pdf, int pageNumber) throws IOException {
        try (PDDocument document = loadPdf(pdf)) {
            validatePage(document, pageNumber); PDPage page = document.getPage(pageNumber - 1);
            Dimensions dimensions = effectiveDimensions(page);
            validateDimensions(dimensions, pageNumber);
            return dimensions;
        }
    }
    public List<Dimensions> allDimensions(Path pdf) throws IOException {
        try (PDDocument document = loadPdf(pdf)) {
            List<Dimensions> result = new ArrayList<>(document.getNumberOfPages());
            int pageNumber = 0;
            for (PDPage page : document.getPages()) {
                Dimensions dimensions = effectiveDimensions(page);
                validateDimensions(dimensions, ++pageNumber);
                result.add(dimensions);
            }
            return result;
        }
    }
    public PdfService(RenderBudget budget, IsolatedPdfRender isolated) {
        this(1800, PdfService::decoderAvailable, budget, isolated);
    }

    public ImageArtifact renderArtifact(Path pdf, int pageNumber, int requestedWidth) throws IOException {
        int width = Math.max(196, Math.min(requestedWidth, maxImageWidth));
        Dimensions dimensions = dimensions(pdf, pageNumber);
        float dpi = boundedDpi(dimensions, pageNumber, width, 36, 300, MAX_RENDER_PIXELS);
        long pixels = renderedPixels(dimensions.width(), dpi) * renderedPixels(dimensions.height(), dpi);
        long estimatedBytes = ResourceBudgetManager.calculatePixelBytes(
                (int) Math.max(1, renderedPixels(dimensions.width(), dpi)),
                (int) Math.max(1, renderedPixels(dimensions.height(), dpi)));
        // 阶段2：高风险页走独立进程，避免坏页拖垮主服务；普通页走共享预算
        if (isolated != null && isolated.shouldIsolate(pdf, pixels, pageNumber)) {
            try {
                return isolated.renderIsolatedArtifact(pdf, pageNumber, false, width, estimatedBytes, PdfService::interrupted);
            } catch (CancelledException e) {
                throw new IOException("页面渲染已取消", e);
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("页面渲染失败", e);
            }
        }
        RenderBudget.Lease lease;
        try {
            lease = acquire(estimatedBytes);
        } catch (CancelledException e) {
            throw new IOException("页面渲染已取消", e);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("页面渲染预算获取失败", e);
        }
        try {
            BufferedImage scaled;
            try (PDDocument document = loadPdf(pdf)) {
                validatePage(document, pageNumber);
                PDPage page = document.getPage(pageNumber - 1);
                BufferedImage source = renderPage(document, pageNumber, dpi);
                double scale = Math.min(1d, Math.min(width / (double)source.getWidth(), Math.sqrt(MAX_RENDER_PIXELS / (double)((long)source.getWidth()*source.getHeight()))));
                if (scale >= .999) {
                    scaled = source;
                } else {
                    width = Math.max(1, (int)Math.floor(source.getWidth()*scale));
                    int height = Math.max(1, (int)Math.floor(source.getHeight()*scale));
                    scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = scaled.createGraphics();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        g.drawImage(source, 0, 0, width, height, null);
                    } finally {
                        g.dispose();
                        source.flush();
                    }
                }
            }
            ResourceBudgetManager.Ticket byteTicket = lease.detachImageByteTicket();
            RenderBudget effectiveBudget = budget != null ? budget : UNLIMITED;
            return effectiveBudget.manager().wrapImage(scaled, byteTicket);
        } catch (Exception e) {
            lease.close();
            if (e instanceof ApiException api) throw api;
            if (e instanceof IOException io) throw io;
            throw new IOException("页面渲染失败", e);
        }
    }

    public BufferedImage render(Path pdf, int pageNumber, int requestedWidth) throws IOException {
        try (ImageArtifact artifact = renderArtifact(pdf, pageNumber, requestedWidth)) {
            return artifact.image();
        }
    }

    public ImageArtifact renderForOcrArtifact(Path pdf, int pageNumber) throws IOException {
        Dimensions dimensions = dimensions(pdf, pageNumber);
        float dpi = boundedDpi(dimensions, pageNumber, 4200, 72, 400, MAX_OCR_PIXELS);
        long pixels = renderedPixels(dimensions.width(), dpi) * renderedPixels(dimensions.height(), dpi);
        long estimatedBytes = ResourceBudgetManager.calculatePixelBytes(
                (int) Math.max(1, renderedPixels(dimensions.width(), dpi)),
                (int) Math.max(1, renderedPixels(dimensions.height(), dpi)));
        if (isolated != null && isolated.shouldIsolate(pdf, pixels, pageNumber)) {
            try {
                return isolated.renderIsolatedArtifact(pdf, pageNumber, true, 1800, estimatedBytes, PdfService::interrupted);
            } catch (CancelledException e) {
                throw new IOException("页面渲染已取消", e);
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("页面渲染失败", e);
            }
        }
        RenderBudget.Lease lease;
        try {
            lease = acquire(estimatedBytes);
        } catch (CancelledException e) {
            throw new IOException("页面渲染已取消", e);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("页面渲染预算获取失败", e);
        }
        try {
            BufferedImage scaled;
            try (PDDocument document = loadPdf(pdf)) {
                validatePage(document, pageNumber);
                PDPage page = document.getPage(pageNumber - 1);
                BufferedImage source = renderPage(document, pageNumber, dpi);
                double scale = Math.min(1d, Math.sqrt(MAX_OCR_PIXELS / (double)((long)source.getWidth() * source.getHeight())));
                if (scale >= .999) {
                    scaled = source;
                } else {
                    int w = Math.max(1, (int)Math.floor(source.getWidth() * scale));
                    int h = Math.max(1, (int)Math.floor(source.getHeight() * scale));
                    scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = scaled.createGraphics();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        g.drawImage(source, 0, 0, w, h, null);
                    } finally {
                        g.dispose();
                        source.flush();
                    }
                }
            }
            ResourceBudgetManager.Ticket byteTicket = lease.detachImageByteTicket();
            RenderBudget effectiveBudget = budget != null ? budget : UNLIMITED;
            return effectiveBudget.manager().wrapImage(scaled, byteTicket);
        } catch (Exception e) {
            lease.close();
            if (e instanceof ApiException api) throw api;
            if (e instanceof IOException io) throw io;
            throw new IOException("页面渲染失败", e);
        }
    }

    public BufferedImage renderForOcr(Path pdf, int pageNumber) throws IOException {
        try (ImageArtifact artifact = renderForOcrArtifact(pdf, pageNumber)) {
            return artifact.image();
        }
    }

    private static final RenderBudget UNLIMITED =
            new RenderBudget(new ResourceBudgetManager(1024, Long.MAX_VALUE, 1, () -> 0, () -> 0));

    private RenderBudget.Lease acquire(long estimatedBytes) throws Exception {
        // 测试构造（budget==null）走无限制预算，保持原有纯解码行为可测
        if (budget == null) return UNLIMITED.acquire(0, () -> false);
        return budget.acquire(estimatedBytes, PdfService::interrupted);
    }

    private static boolean interrupted() { return Thread.currentThread().isInterrupted(); }

    public byte[] png(BufferedImage image) throws IOException {
        BoundedByteOutputStream out = new BoundedByteOutputStream(10 * 1024 * 1024L);
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("PNG 图像编码失败");
        }
        return out.toByteArray();
    }

    public byte[] pngArtifact(ImageArtifact artifact) throws IOException {
        return png(artifact.image());
    }

    public ImageArtifact cropArtifact(ImageArtifact source, double[] bbox) {
        BlockValidator.validateBbox(bbox);
        BufferedImage image = source.image();
        int x = clamp((int)Math.floor(bbox[0] * image.getWidth()), 0, image.getWidth()-1);
        int y = clamp((int)Math.floor(bbox[1] * image.getHeight()), 0, image.getHeight()-1);
        int w = clamp((int)Math.ceil(bbox[2] * image.getWidth()), 1, image.getWidth()-x);
        int h = clamp((int)Math.ceil(bbox[3] * image.getHeight()), 1, image.getHeight()-y);
        return source.subImage(x, y, w, h);
    }

    public byte[] cropPng(Path pdf, int page, int width, double[] bbox) throws IOException {
        BlockValidator.validateBbox(bbox);
        try (ImageArtifact artifact = renderArtifact(pdf, page, width);
             ImageArtifact cropped = cropArtifact(artifact, bbox)) {
            return png(cropped.image());
        }
    }
    private static int clamp(int n, int low, int high) { return Math.max(low, Math.min(n, high)); }
    private static float boundedDpi(Dimensions dimensions, int pageNumber, int targetWidth,
                                    float minimumDpi, float maximumDpi, long pixelBudget) {
        validateDimensions(dimensions, pageNumber);
        double width = dimensions.width(), height = dimensions.height();
        double longest = Math.max(width, height), shortest = Math.min(width, height);
        if (longest / shortest > pixelBudget) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PDF页面尺寸异常：第" + pageNumber + "页纵横比过大，无法安全渲染");
        }
        double desiredDpi = Math.max(minimumDpi, Math.min(maximumDpi, targetWidth * 72d / width));
        double pixelsPerPointByArea = Math.sqrt(pixelBudget / width / height);
        double pixelsPerPointByEdge = MAX_RENDER_EDGE / longest;
        double bounded = Math.min(desiredDpi, 72d * Math.min(pixelsPerPointByArea, pixelsPerPointByEdge));
        float dpi = (float)bounded;
        long pixelWidth = renderedPixels(width, dpi), pixelHeight = renderedPixels(height, dpi);
        if (pixelWidth > MAX_RENDER_EDGE || pixelHeight > MAX_RENDER_EDGE || pixelWidth > pixelBudget / pixelHeight) {
            // A double-to-float round-up can put an exact area cap just over budget.
            dpi = Math.nextDown(dpi);
            pixelWidth = renderedPixels(width, dpi);
            pixelHeight = renderedPixels(height, dpi);
        }
        if (!(dpi > 0) || pixelWidth < 1 || pixelHeight < 1 || pixelWidth > MAX_RENDER_EDGE
                || pixelHeight > MAX_RENDER_EDGE || pixelWidth > pixelBudget / pixelHeight) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PDF页面尺寸异常：第" + pageNumber + "页无法在安全像素预算内渲染");
        }
        return dpi;
    }

    private static long renderedPixels(double points, float dpi) {
        // Keep this calculation aligned with PDFRenderer's float scale and truncating cast.
        float scale = dpi / 72f;
        return Math.max(1L, (long)Math.floor((float)points * scale));
    }

    private static void validateDimensions(Dimensions dimensions, int pageNumber) {
        if (!Double.isFinite(dimensions.width()) || !Double.isFinite(dimensions.height())
                || dimensions.width() <= 0 || dimensions.height() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PDF页面尺寸无效：第" + pageNumber + "页宽高必须为有限正数");
        }
    }

    private BufferedImage renderPage(PDDocument document, int pageNumber, float dpi) throws IOException {
        return new StrictPdfRenderer(document, pageNumber, decoderProbe)
                .renderImageWithDPI(pageNumber - 1, dpi, ImageType.RGB);
    }

    static boolean decoderAvailable(Codec codec) {
        for (String format : codec.formats) {
            if (ImageIO.getImageReadersByFormatName(format).hasNext()) return true;
        }
        return false;
    }

    private static EnumSet<Codec> requiredCodecs(COSDictionary image) {
        EnumSet<Codec> codecs = EnumSet.noneOf(Codec.class);
        Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectCodecs(image, codecs, visited);
        return codecs;
    }

    private static void collectCodecs(COSBase base, EnumSet<Codec> codecs, Set<COSBase> visited) {
        if (!(base instanceof COSDictionary dictionary) || !visited.add(base)) return;
        collectFilter(dictionary.getDictionaryObject(COSName.FILTER), codecs);
        collectCodecs(dictionary.getDictionaryObject(COSName.MASK), codecs, visited);
        collectCodecs(dictionary.getDictionaryObject(COSName.SMASK), codecs, visited);
    }

    private static void collectFilter(COSBase filter, EnumSet<Codec> codecs) {
        if (filter instanceof COSName name) {
            Codec.fromFilter(name).ifPresent(codecs::add);
        } else if (filter instanceof COSArray array) {
            for (COSBase item : array) collectFilter(item, codecs);
        }
    }

    @FunctionalInterface
    interface DecoderProbe { boolean available(Codec codec); }

    enum Codec {
        JPX("JPXDecode", "JPEG 2000", List.of("JPEG2000", "JPEG 2000", "JP2")),
        JBIG2("JBIG2Decode", "JBIG2", List.of("JBIG2", "jbig2"));

        private final String filter;
        private final String label;
        private final List<String> formats;
        Codec(String filter, String label, List<String> formats) { this.filter=filter; this.label=label; this.formats=formats; }
        private static java.util.Optional<Codec> fromFilter(COSName name) {
            for (Codec codec : values()) if (codec.filter.equals(name.getName())) return java.util.Optional.of(codec);
            return java.util.Optional.empty();
        }
    }

    private static final class StrictPdfRenderer extends PDFRenderer {
        private final int pageNumber;
        private final DecoderProbe decoderProbe;
        private StrictPdfRenderer(PDDocument document, int pageNumber, DecoderProbe decoderProbe) {
            super(document);
            this.pageNumber = pageNumber;
            this.decoderProbe = decoderProbe;
        }
        @Override protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new StrictPageDrawer(parameters, pageNumber, decoderProbe);
        }
    }

    private static final class StrictPageDrawer extends PageDrawer {
        private final int pageNumber;
        private final DecoderProbe decoderProbe;
        private StrictPageDrawer(PageDrawerParameters parameters, int pageNumber, DecoderProbe decoderProbe) throws IOException {
            super(parameters);
            this.pageNumber = pageNumber;
            this.decoderProbe = decoderProbe;
        }
        @Override public void drawImage(PDImage image) throws IOException {
            EnumSet<Codec> codecs = requiredCodecs(image.getCOSObject());
            for (Codec codec : codecs) {
                if (!decoderProbe.available(codec)) {
                    throw decodeException("PDF图像解码器缺失：第" + pageNumber + "页需要" + codec.label + "（" + codec.filter + "）");
                }
            }
            try {
                super.drawImage(image);
            } catch (ApiException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                String labels = codecs.isEmpty() ? "内嵌图像" : codecs.stream().map(codec -> codec.label).reduce((a,b) -> a + "/" + b).orElse("内嵌图像");
                throw decodeException("PDF图像解码失败：第" + pageNumber + "页的" + labels + "数据无法读取");
            }
        }
        @Override protected void operatorException(Operator operator, List<COSBase> operands, IOException cause) throws IOException {
            // PDFStreamEngine normally logs and skips a failing Do operator. A skipped image can turn a
            // scanned page into a plausible white bitmap, which must never be sent on to OCR.
            if (operator != null && "Do".equals(operator.getName())) {
                throw decodeException("PDF图像解码失败：第" + pageNumber + "页的内嵌图像数据无法读取");
            }
            super.operatorException(operator, operands, cause);
        }
        private static ApiException decodeException(String message) {
            return new ApiException(HttpStatus.BAD_REQUEST, message);
        }
    }

    static Dimensions effectiveDimensions(PDPage page) { int rotation=Math.floorMod(page.getRotation(),360); return rotation==90||rotation==270?new Dimensions(page.getCropBox().getHeight(),page.getCropBox().getWidth()):new Dimensions(page.getCropBox().getWidth(),page.getCropBox().getHeight()); }
    private static void validatePage(PDDocument d, int n) { if (n < 1 || n > d.getNumberOfPages()) throw new ApiException(HttpStatus.NOT_FOUND, "页码不存在"); }
    public record PdfInfo(int pages) {}
    public record Dimensions(double width, double height) {}
}
