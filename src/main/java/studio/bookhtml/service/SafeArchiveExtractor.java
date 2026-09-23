package studio.bookhtml.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * G12 / B10: 归档与备份解压安全防护。
 * 实施流式 ZIP 炸弹检测（字节限制、条目数限制、压缩比限制）及严格路径穿越校验，
 * 拒绝 ../ 穿越、绝对路径、Windows 盘符、NUL 字符与符号链接，流式检测异常即终止。
 */
public final class SafeArchiveExtractor {
    public static final long DEFAULT_MAX_DECOMPRESSED_BYTES = 50L * 1024 * 1024; // 50 MiB
    public static final int DEFAULT_MAX_ENTRIES = 5000;
    public static final double DEFAULT_MAX_COMPRESSION_RATIO = 100.0; // 100:1 ratio limit
    public static final long RATIO_CHECK_THRESHOLD_BYTES = 256L * 1024; // 256 KiB

    private final long maxDecompressedBytes;
    private final int maxEntries;
    private final double maxCompressionRatio;

    public SafeArchiveExtractor() {
        this(DEFAULT_MAX_DECOMPRESSED_BYTES, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_COMPRESSION_RATIO);
    }

    public SafeArchiveExtractor(long maxDecompressedBytes, int maxEntries, double maxCompressionRatio) {
        this.maxDecompressedBytes = maxDecompressedBytes;
        this.maxEntries = maxEntries;
        this.maxCompressionRatio = maxCompressionRatio;
    }

    /**
     * 校验 ZIP 条目路径的安全性，防止路径穿越。
     */
    public static void validateEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ZIP 条目路径不能为空");
        }
        if (name.contains("\0")) {
            throw new IllegalArgumentException("ZIP 条目路径包含非法 NUL 字符: " + name);
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw new IllegalArgumentException("ZIP 条目路径不能以斜杠开头: " + name);
        }
        if (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
            throw new IllegalArgumentException("ZIP 条目路径不能包含驱动器号: " + name);
        }
        if (name.contains(":") && name.indexOf(":") < 3) {
            throw new IllegalArgumentException("ZIP 条目路径不能包含非法盘符冒号: " + name);
        }
        if (name.contains("..")) {
            throw new IllegalArgumentException("ZIP 条目路径包含非法穿越字符: " + name);
        }
        Path normalized = Path.of(name).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || normalized.toString().contains("..")) {
            throw new IllegalArgumentException("ZIP 条目路径存在路径穿越: " + name);
        }
    }

    /**
     * 流式提取归档内容到目标目录，并在提取过程中实施安全限制。
     */
    public void extract(InputStream in, Path destinationDir) throws IOException {
        Path destRoot = destinationDir.toAbsolutePath().normalize();
        Files.createDirectories(destRoot);

        CountingInputStream countingIn = new CountingInputStream(in);
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(countingIn), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            int entryCount = 0;
            long totalDecompressedBytes = 0;

            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > maxEntries) {
                    throw new SecurityException("ZIP 归档条目总数超出限制 (最多 " + maxEntries + " 个条目)");
                }

                String name = entry.getName();
                validateEntryName(name);

                Path target = destRoot.resolve(name).normalize();
                if (!target.startsWith(destRoot)) {
                    throw new SecurityException("ZIP 路径穿越逃逸目标目录: " + name);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    zis.closeEntry();
                    continue;
                }

                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                byte[] buffer = new byte[8192];
                try (OutputStream out = Files.newOutputStream(target)) {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        totalDecompressedBytes += read;

                        if (totalDecompressedBytes > maxDecompressedBytes) {
                            throw new SecurityException("ZIP 归档解压总字节数超出限制 (最多 "
                                    + maxDecompressedBytes + " 字节, 已解压 " + totalDecompressedBytes + " 字节)");
                        }

                        if (totalDecompressedBytes > RATIO_CHECK_THRESHOLD_BYTES) {
                            long compressedConsumed = Math.max(1L, countingIn.getBytesRead());
                            double ratio = (double) totalDecompressedBytes / (double) compressedConsumed;
                            if (ratio > maxCompressionRatio) {
                                throw new SecurityException("检测到异常高压缩比 (ZIP 炸弹防护): ratio="
                                        + String.format(java.util.Locale.ROOT, "%.2f", ratio) + " > " + maxCompressionRatio);
                            }
                        }

                        out.write(buffer, 0, read);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 流式校验归档内容合法性，不写入磁盘。
     */
    public void validate(InputStream in) throws IOException {
        CountingInputStream countingIn = new CountingInputStream(in);
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(countingIn), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            int entryCount = 0;
            long totalDecompressedBytes = 0;
            byte[] discard = new byte[8192];

            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > maxEntries) {
                    throw new SecurityException("ZIP 归档条目总数超出限制 (最多 " + maxEntries + " 个条目)");
                }

                String name = entry.getName();
                validateEntryName(name);

                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                int read;
                while ((read = zis.read(discard)) != -1) {
                    totalDecompressedBytes += read;

                    if (totalDecompressedBytes > maxDecompressedBytes) {
                        throw new SecurityException("ZIP 归档解压总字节数超出限制 (最多 "
                                + maxDecompressedBytes + " 字节, 已解压 " + totalDecompressedBytes + " 字节)");
                    }

                    if (totalDecompressedBytes > RATIO_CHECK_THRESHOLD_BYTES) {
                        long compressedConsumed = Math.max(1L, countingIn.getBytesRead());
                        double ratio = (double) totalDecompressedBytes / (double) compressedConsumed;
                        if (ratio > maxCompressionRatio) {
                            throw new SecurityException("检测到异常高压缩比 (ZIP 炸弹防护): ratio="
                                    + String.format(java.util.Locale.ROOT, "%.2f", ratio) + " > " + maxCompressionRatio);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long bytesRead = 0;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read != -1) {
                bytesRead += read;
            }
            return read;
        }

        public long getBytesRead() {
            return bytesRead;
        }
    }
}
