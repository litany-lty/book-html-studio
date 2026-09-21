package studio.bookhtml.service;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.ApiException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PdfServiceTest {
    @TempDir Path temp;

    @Test void swapsDimensionsForQuarterTurn(){PDPage page=new PDPage(new PDRectangle(600,800));page.setRotation(90);PdfService.Dimensions d=PdfService.effectiveDimensions(page);assertEquals(800,d.width());assertEquals(600,d.height());}

    private Path protectedPdf(String name, String userPassword) throws Exception {
        Path pdf = temp.resolve(name);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(600, 800)));
            org.apache.pdfbox.pdmodel.encryption.AccessPermission permissions =
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner", userPassword, permissions));
            document.save(pdf.toFile());
        }
        return pdf;
    }

    @Test void emptyPasswordPdfIsReadableNotRejected() throws Exception {
        // 误判回归：空口令加密字典自动解开，不报“暂不支持加密”
        Path pdf = protectedPdf("empty-pw.pdf", "");
        PdfService service = new PdfService(1600, codec -> true);
        assertEquals(1, service.inspect(pdf).pages());
        java.awt.image.BufferedImage rendered = service.renderForOcr(pdf, 1);
        try {
            assertTrue(rendered.getWidth() > 0 && rendered.getHeight() > 0);
        } finally {
            rendered.flush();
        }
    }

    @Test void realPasswordPdfStillRefused() throws Exception {
        Path pdf = protectedPdf("real-pw.pdf", "secret");
        PdfService service = new PdfService(1600, codec -> true);
        studio.bookhtml.api.ApiException refused = assertThrows(
                studio.bookhtml.api.ApiException.class, () -> service.inspect(pdf));
        assertTrue(refused.getMessage().contains("暂不支持加密"));
    }

    @Test void rendersAnActualJpeg2000Image() throws Exception {
        assertTrue(PdfService.decoderAvailable(PdfService.Codec.JPX));
        Path pdf = jpeg2000Pdf("jpx.pdf");
        BufferedImage rendered = new PdfService(1600, codec -> true).renderForOcr(pdf, 1);
        try {
            Color center = new Color(rendered.getRGB(rendered.getWidth()/2, rendered.getHeight()/2));
            assertTrue(center.getRed() > center.getGreen() + 40, "实际 JPX 图像应被解码为红色，而不是空白页");
        } finally {
            rendered.flush();
        }
    }

    @Test void hasJbig2ImageIoReader() {
        assertTrue(PdfService.decoderAvailable(PdfService.Codec.JBIG2));
    }

    @Test void missingDecoderStopsOcrRenderingWithSafeDetail() throws Exception {
        Path pdf = jpeg2000Pdf("missing-codec.pdf");
        ApiException error = assertThrows(ApiException.class,
                () -> new PdfService(1600, codec -> false).renderForOcr(pdf, 1));
        assertEquals("PDF图像解码器缺失：第1页需要JPEG 2000（JPXDecode）", error.getMessage());
    }

    @Test void missingJbig2MaskDecoderAlsoStopsOcrRendering() throws Exception {
        Path pdf = jpeg2000PdfWithJbig2Mask("missing-mask-codec.pdf");
        ApiException error = assertThrows(ApiException.class,
                () -> new PdfService(1600, codec -> codec != PdfService.Codec.JBIG2).renderForOcr(pdf, 1));
        assertEquals("PDF图像解码器缺失：第1页需要JBIG2（JBIG2Decode）", error.getMessage());
    }

    @Test void corruptJbig2MaskCannotSilentlyBecomeABlankOcrPage() throws Exception {
        Path pdf = jpeg2000PdfWithJbig2Mask("corrupt-mask.pdf");
        ApiException error = assertThrows(ApiException.class,
                () -> new PdfService(1600, codec -> true).renderForOcr(pdf, 1));
        assertTrue(error.getMessage().startsWith("PDF图像解码失败：第1页"));
    }

    @Test void corruptJpeg2000CannotSilentlyBecomeABlankOcrPage() throws Exception {
        Path pdf = imagePdf("corrupt-jpx.pdf", new byte[]{0,1,2,3}, false);
        ApiException error = assertThrows(ApiException.class,
                () -> new PdfService(1600, codec -> true).renderForOcr(pdf, 1));
        assertTrue(error.getMessage().startsWith("PDF图像解码失败：第1页"));
    }

    @Test void xObjectConstructionFailureCannotBeSwallowedIntoBlankPage() throws Exception {
        Path pdf = imagePdf("invalid-xobject.pdf", jpeg2000Bytes(), false, true);
        ApiException error = assertThrows(ApiException.class,
                () -> new PdfService(1600, codec -> true).renderForOcr(pdf, 1));
        assertEquals("PDF图像解码失败：第1页的内嵌图像数据无法读取", error.getMessage());
    }

    @Test void ordinaryJpegAndFlateImagesStillRender() throws Exception {
        Path pdf = ordinaryImagePdf();
        BufferedImage rendered = new PdfService(1600, codec -> false).renderForOcr(pdf, 1);
        try {
            Color left = new Color(rendered.getRGB(rendered.getWidth()/4, rendered.getHeight()/2));
            Color right = new Color(rendered.getRGB(rendered.getWidth()*3/4, rendered.getHeight()/2));
            assertTrue(left.getBlue() > left.getRed() + 40, "普通 JPEG 图像应正常渲染");
            assertTrue(right.getGreen() > right.getRed() + 40, "普通 Flate/PNG 图像应正常渲染");
        } finally {
            rendered.flush();
        }
    }

    @Test void legitimateBlankPageStillRenders() throws Exception {
        Path pdf = temp.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(72,72)));
            document.save(pdf.toFile());
        }
        BufferedImage rendered = new PdfService(1600, codec -> false).renderForOcr(pdf, 1);
        try {
            assertEquals(Color.WHITE.getRGB(), rendered.getRGB(rendered.getWidth()/2, rendered.getHeight()/2));
        } finally {
            rendered.flush();
        }
    }

    @Test void rejectsNonPositivePageDimensionsBeforeRendering() throws Exception {
        Path pdf = blankPdf("zero-width.pdf", new PDRectangle(0,72));
        ApiException error = assertThrows(ApiException.class,
                () -> new PdfService(1600, codec -> true).renderForOcr(pdf, 1));
        assertEquals("PDF页面尺寸无效：第1页宽高必须为有限正数", error.getMessage());
    }

    @Test void rejectsPageWhoseAspectRatioCannotFitThePixelBudget() throws Exception {
        Path pdf = blankPdf("extreme-aspect.pdf", new PDRectangle((float)(PdfService.MAX_OCR_PIXELS + 1), 1));
        ApiException error = assertThrows(ApiException.class,
                () -> new PdfService(1600, codec -> true).renderForOcr(pdf, 1));
        assertEquals("PDF页面尺寸异常：第1页纵横比过大，无法安全渲染", error.getMessage());
    }

    @Test void commonLandscapePageRendersAtTheOcrPixelBudget() throws Exception {
        Path pdf = blankPdf("landscape.pdf", new PDRectangle(840,595));
        BufferedImage rendered = new PdfService(1600, codec -> true).renderForOcr(pdf, 1);
        try {
            long pixels = (long)rendered.getWidth() * rendered.getHeight();
            assertTrue(rendered.getWidth() > 4_000, "常见横页不应因取整被误拒绝或过度缩小");
            assertTrue(pixels <= PdfService.MAX_OCR_PIXELS, "OCR 渲染必须在像素预算内分配");
        } finally {
            rendered.flush();
        }
    }

    private Path jpeg2000Pdf(String name) throws Exception {
        return jpeg2000Pdf(name, false);
    }

    private Path jpeg2000PdfWithJbig2Mask(String name) throws Exception {
        return jpeg2000Pdf(name, true);
    }

    private Path jpeg2000Pdf(String name, boolean withJbig2Mask) throws Exception {
        return imagePdf(name, jpeg2000Bytes(), withJbig2Mask);
    }

    private byte[] jpeg2000Bytes() throws Exception {
        BufferedImage source = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "JPEG2000", encoded), "测试环境必须加载 JPEG 2000 ImageIO writer");
        source.flush();
        return encoded.toByteArray();
    }

    private Path imagePdf(String name, byte[] encoded, boolean withJbig2Mask) throws Exception {
        return imagePdf(name, encoded, withJbig2Mask, false);
    }

    private Path imagePdf(String name, byte[] encoded, boolean withJbig2Mask, boolean invalidXObject) throws Exception {
        Path pdf = temp.resolve(name);
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(72,72));
            document.addPage(page);
            PDImageXObject image = new PDImageXObject(document,
                    new ByteArrayInputStream(encoded), COSName.JPX_DECODE,
                    24, 24, 8, PDDeviceRGB.INSTANCE);
            if (invalidXObject) image.getCOSObject().setItem(COSName.SUBTYPE, COSName.getPDFName("BrokenImage"));
            if (withJbig2Mask) {
                PDImageXObject mask = new PDImageXObject(document,
                        new ByteArrayInputStream(new byte[]{0,1,2,3}), COSName.JBIG2_DECODE,
                        24, 24, 1, PDDeviceRGB.INSTANCE);
                mask.setStencil(true);
                image.getCOSObject().setItem(COSName.MASK, mask);
            }
            PDFormXObject form = new PDFormXObject(document);
            form.setBBox(new PDRectangle(72,72));
            form.setResources(new PDResources());
            try (PDFormContentStream content = new PDFormContentStream(form)) {
                content.drawImage(image, 0, 0, 72, 72);
            }
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawForm(form);
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private Path ordinaryImagePdf() throws Exception {
        BufferedImage blue = solidImage(Color.BLUE);
        BufferedImage green = solidImage(Color.GREEN);
        Path pdf = temp.resolve("ordinary-images.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(144,72));
            document.addPage(page);
            PDImageXObject jpeg = JPEGFactory.createFromImage(document, blue, .9f);
            PDImageXObject flate = LosslessFactory.createFromImage(document, green);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(jpeg, 0, 0, 72, 72);
                content.drawImage(flate, 72, 0, 72, 72);
            }
            document.save(pdf.toFile());
        } finally {
            blue.flush();
            green.flush();
        }
        return pdf;
    }

    private Path blankPdf(String name, PDRectangle mediaBox) throws Exception {
        Path pdf = temp.resolve(name);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(mediaBox));
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private static BufferedImage solidImage(Color color) {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
