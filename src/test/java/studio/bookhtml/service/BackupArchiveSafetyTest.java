package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G12 / B10-03 & B10-04: 归档与备份解压安全防护测试。
 * 验证路径穿越阻断、ZIP 炸弹（压缩比过高/巨量字节）、条目上限与流式检测。
 */
class BackupArchiveSafetyTest {
    @TempDir
    Path tempDir;

    private byte[] createZipWithEntry(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    void pathTraversalAttemptIsRejectedBySafeArchiveExtractor() {
        SafeArchiveExtractor extractor = new SafeArchiveExtractor();
        Path outDir = tempDir.resolve("extract-out");

        assertThrows(IllegalArgumentException.class, () -> {
            byte[] zip = createZipWithEntry("../escape.sh", "echo evil".getBytes());
            extractor.extract(new ByteArrayInputStream(zip), outDir);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            byte[] zip = createZipWithEntry("/etc/shadow", "root:x:0:0:".getBytes());
            extractor.extract(new ByteArrayInputStream(zip), outDir);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            byte[] zip = createZipWithEntry("sub/../../root.txt", "escaped".getBytes());
            extractor.extract(new ByteArrayInputStream(zip), outDir);
        });
    }

    @Test
    void zipBombWithHighCompressionRatioIsDetectedAndAborted() throws IOException {
        // 创建一个高压缩比的条目（2MB 全零，压缩后仅几十字节，压缩比超过 1000:1）
        byte[] largeZeros = new byte[2 * 1024 * 1024];
        Arrays.fill(largeZeros, (byte) 0);
        byte[] zip = createZipWithEntry("zeros.bin", largeZeros);

        SafeArchiveExtractor extractor = new SafeArchiveExtractor(50 * 1024 * 1024, 100, 50.0); // 阈值 50:1
        Path outDir = tempDir.resolve("bomb-out");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                extractor.extract(new ByteArrayInputStream(zip), outDir)
        );
        assertTrue(ex.getMessage().contains("ZIP 炸弹防护") || ex.getMessage().contains("高压缩比"));
    }

    @Test
    void totalDecompressedBytesLimitIsEnforcedStreamingly() throws IOException {
        byte[] data = new byte[20 * 1024]; // 20 KiB
        Arrays.fill(data, (byte) 'A');
        byte[] zip = createZipWithEntry("data.txt", data);

        // 限制最多 10 KiB
        SafeArchiveExtractor extractor = new SafeArchiveExtractor(10 * 1024, 10, 1000.0);
        Path outDir = tempDir.resolve("limit-out");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                extractor.extract(new ByteArrayInputStream(zip), outDir)
        );
        assertTrue(ex.getMessage().contains("解压总字节数超出限制"));
    }

    @Test
    void maxEntriesLimitIsEnforced() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 10; i++) {
                ZipEntry entry = new ZipEntry("entry-" + i + ".txt");
                zos.putNextEntry(entry);
                zos.write("ok".getBytes());
                zos.closeEntry();
            }
        }
        byte[] zip = baos.toByteArray();

        // 限制最多 5 个条目
        SafeArchiveExtractor extractor = new SafeArchiveExtractor(1024 * 1024, 5, 1000.0);
        Path outDir = tempDir.resolve("entries-out");

        SecurityException ex = assertThrows(SecurityException.class, () ->
                extractor.extract(new ByteArrayInputStream(zip), outDir)
        );
        assertTrue(ex.getMessage().contains("条目总数超出限制"));
    }

    @Test
    void validateMethodPerformsStreamingCheckWithoutWritingToDisk() throws IOException {
        byte[] zip = createZipWithEntry("valid.txt", "hello safe extractor".getBytes());
        SafeArchiveExtractor extractor = new SafeArchiveExtractor();

        assertDoesNotThrow(() -> extractor.validate(new ByteArrayInputStream(zip)));

        // 畸形路径校验也会被 validate 拦截
        byte[] evilZip = createZipWithEntry("../evil.txt", "bad".getBytes());
        assertThrows(IllegalArgumentException.class, () ->
                extractor.validate(new ByteArrayInputStream(evilZip))
        );
    }

    @Test
    void safeExtractionSuccessfullyUnpacksLegitimateArchive() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            ZipEntry e1 = new ZipEntry("folder/a.txt");
            zos.putNextEntry(e1);
            zos.write("content A".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ZipEntry e2 = new ZipEntry("folder/b.txt");
            zos.putNextEntry(e2);
            zos.write("content B".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        byte[] zip = baos.toByteArray();

        SafeArchiveExtractor extractor = new SafeArchiveExtractor();
        Path outDir = tempDir.resolve("legit-out");
        extractor.extract(new ByteArrayInputStream(zip), outDir);

        assertTrue(Files.isRegularFile(outDir.resolve("folder/a.txt")));
        assertTrue(Files.isRegularFile(outDir.resolve("folder/b.txt")));
        assertEquals("content A", Files.readString(outDir.resolve("folder/a.txt")));
        assertEquals("content B", Files.readString(outDir.resolve("folder/b.txt")));
    }
}
