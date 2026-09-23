package studio.bookhtml.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * B01/G10：全局统一资源与图片所有权管理器。
 *
 * <p>职责：
 * <ul>
 *   <li>渲染并发许可（Semaphore）控制与公平排队</li>
 *   <li>解码图像驻留堆内存（inFlightImageBytes）全程租约管理</li>
 *   <li>PNG/Base64/请求体编码数据（inFlightEncodedBytes）独立计量与上限控制</li>
 *   <li>严格防止跨线程重复释放、乘法溢出、负数与死锁</li>
 * </ul>
 */
@Service
public class ResourceBudgetManager {
    public static final int DEFAULT_MAX_CONCURRENT = 3;
    public static final long DEFAULT_MAX_IN_FLIGHT_BYTES = 384L * 1024 * 1024;
    public static final long DEFAULT_MAX_WAIT_MS = 30_000L;

    private final Semaphore renderPermits;
    private final int maxConcurrent;
    private final long maxInFlightBytes;
    private final long maxWaitMs;
    private final LongSupplier usedHeapSupplier;
    private final LongSupplier maxHeapSupplier;

    private final AtomicLong inFlightImageBytes = new AtomicLong();
    private final AtomicLong inFlightEncodedBytes = new AtomicLong();
    private final AtomicLong inFlightDiskBytes = new AtomicLong();

    public interface Ticket extends AutoCloseable {
        long reservedBytes();
        boolean isClosed();
        @Override
        void close();
    }

    public ResourceBudgetManager() {
        this(envInt("RENDER_MAX_CONCURRENT", DEFAULT_MAX_CONCURRENT),
             calculateMaxInFlightBytes(),
             envLong("RENDER_MAX_WAIT_MS", DEFAULT_MAX_WAIT_MS));
    }

    public ResourceBudgetManager(int maxConcurrent, long maxInFlightBytes, long maxWaitMs) {
        this(maxConcurrent, maxInFlightBytes, maxWaitMs, defaultUsedHeap(), defaultMaxHeap());
    }

    public ResourceBudgetManager(int maxConcurrent, long maxInFlightBytes, long maxWaitMs,
                                 LongSupplier usedHeapSupplier, LongSupplier maxHeapSupplier) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent 必须大于 0");
        }
        if (maxInFlightBytes <= 0) {
            throw new IllegalArgumentException("maxInFlightBytes 必须大于 0");
        }
        if (maxWaitMs <= 0) {
            throw new IllegalArgumentException("maxWaitMs 必须大于 0");
        }
        this.maxConcurrent = maxConcurrent;
        this.renderPermits = new Semaphore(maxConcurrent, true);
        this.maxInFlightBytes = maxInFlightBytes;
        this.maxWaitMs = maxWaitMs;
        this.usedHeapSupplier = Objects.requireNonNull(usedHeapSupplier);
        this.maxHeapSupplier = Objects.requireNonNull(maxHeapSupplier);
    }

    public Ticket acquireRenderPermit(BooleanSupplier cancelled) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        while (true) {
            checkCancelled(cancelled);
            if (renderPermits.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                return new PermitTicket();
            }
            if (System.nanoTime() >= deadline) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "系统渲染资源繁忙，请稍后重试");
            }
        }
    }

    public Ticket acquireImageBytes(long bytes, BooleanSupplier cancelled) throws Exception {
        validateBytes(bytes);
        if (bytes == 0) {
            return new ImageTicket(0);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        while (true) {
            checkCancelled(cancelled);
            if (fits(bytes, inFlightImageBytes.get() + inFlightEncodedBytes.get())) {
                long after = inFlightImageBytes.addAndGet(bytes);
                if (after + inFlightEncodedBytes.get() <= maxInFlightBytes) {
                    return new ImageTicket(bytes);
                }
                // 竞争超限回退
                inFlightImageBytes.getAndUpdate(v -> Math.max(0, v - bytes));
            }
            Thread.sleep(50);
            if (System.nanoTime() >= deadline) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "系统图像内存预算繁忙，请稍后重试");
            }
        }
    }

    public Ticket acquireEncodedBytes(long bytes, BooleanSupplier cancelled) throws Exception {
        validateBytes(bytes);
        if (bytes == 0) {
            return new EncodedTicket(0);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        while (true) {
            checkCancelled(cancelled);
            if (fits(bytes, inFlightImageBytes.get() + inFlightEncodedBytes.get())) {
                long after = inFlightEncodedBytes.addAndGet(bytes);
                if (after + inFlightImageBytes.get() <= maxInFlightBytes) {
                    return new EncodedTicket(bytes);
                }
                inFlightEncodedBytes.getAndUpdate(v -> Math.max(0, v - bytes));
            }
            Thread.sleep(50);
            if (System.nanoTime() >= deadline) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "系统编码数据内存预算繁忙，请稍后重试");
            }
        }
    }

    public boolean tryGrowEncoded(Ticket ticket, long additionalBytes) {
        if (!(ticket instanceof EncodedTicket encTicket) || encTicket.isClosed()) {
            return false;
        }
        if (additionalBytes <= 0) return true;
        if (inFlightEncodedBytes.get() + inFlightImageBytes.get() + additionalBytes > maxInFlightBytes) {
            return false;
        }
        inFlightEncodedBytes.addAndGet(additionalBytes);
        encTicket.grow(additionalBytes);
        return true;
    }

    public ImageArtifact wrapImage(BufferedImage image) {
        Objects.requireNonNull(image, "image 不能为 null");
        long bytes = calculatePixelBytes(image.getWidth(), image.getHeight());
        Ticket ticket;
        try {
            ticket = acquireImageBytes(bytes, () -> false);
        } catch (Exception e) {
            throw new RuntimeException("无法分配图像内存租约", e);
        }
        return wrapImage(image, ticket);
    }

    public ImageArtifact wrapImage(BufferedImage image, Ticket ticket) {
        Objects.requireNonNull(image, "image 不能为 null");
        Ticket effectiveTicket = ticket != null ? ticket : new ImageTicket(0);
        RasterHolder holder = new RasterHolder(image, effectiveTicket);
        return new DefaultImageArtifact(holder, image);
    }

    public EncodedImageArtifact wrapEncoded(byte[] data, String mimeType) {
        Objects.requireNonNull(data, "data 不能为 null");
        Ticket ticket;
        try {
            ticket = acquireEncodedBytes(data.length, () -> false);
        } catch (Exception e) {
            throw new RuntimeException("无法分配编码数据内存租约", e);
        }
        String hash = sha256Hex(data);
        return new DefaultEncodedImageArtifact(data, hash, ticket);
    }

    public static long calculatePixelBytes(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("图像尺寸必须大于0: " + width + "x" + height);
        }
        try {
            long pixels = Math.multiplyExact((long) width, (long) height);
            return Math.multiplyExact(pixels, 4L); // 4 bytes per pixel (ARGB/RGB INT)
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("图像像素乘法溢出: " + width + "x" + height, e);
        }
    }

    public long inFlightImageBytes() { return inFlightImageBytes.get(); }
    public long inFlightEncodedBytes() { return inFlightEncodedBytes.get(); }
    public long inFlightDiskBytes() { return inFlightDiskBytes.get(); }
    public int availableRenderPermits() { return renderPermits.availablePermits(); }
    public int maxConcurrent() { return maxConcurrent; }
    public long maxInFlightBytes() { return maxInFlightBytes; }

    private void validateBytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("申请字节数不能为负数: " + bytes);
        }
    }

    private boolean fits(long need, long currentTotal) {
        if (need > maxInFlightBytes || currentTotal > maxInFlightBytes - need) return false;
        try {
            long maxHeap = maxHeapSupplier.getAsLong();
            if (maxHeap > 0) {
                long heapThreshold = (long) (maxHeap * 0.85);
                if (need > heapThreshold || usedHeapSupplier.getAsLong() > heapThreshold - need) {
                    return false;
                }
            }
        } catch (Exception ignored) { }
        return true;
    }

    private void checkCancelled(BooleanSupplier cancelled) throws CancelledException {
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancelledException();
        if (Thread.currentThread().isInterrupted()) throw new CancelledException();
    }

    private final class PermitTicket implements Ticket {
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override public long reservedBytes() { return 0; }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                renderPermits.release();
            }
        }
    }

    private final class ImageTicket implements Ticket {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final long bytes;
        ImageTicket(long bytes) { this.bytes = bytes; }
        @Override public long reservedBytes() { return bytes; }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                inFlightImageBytes.getAndUpdate(v -> Math.max(0, v - bytes));
            }
        }
    }

    private final class EncodedTicket implements Ticket {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong bytes;
        EncodedTicket(long initialBytes) { this.bytes = new AtomicLong(initialBytes); }
        void grow(long additional) { bytes.addAndGet(additional); }
        @Override public long reservedBytes() { return bytes.get(); }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                long current = bytes.get();
                inFlightEncodedBytes.getAndUpdate(v -> Math.max(0, v - current));
            }
        }
    }

    /** 底层光栅持有者，维护原子引用计数。 */
    private static final class RasterHolder {
        private final BufferedImage rootImage;
        private final Ticket ticket;
        private final AtomicInteger refCount = new AtomicInteger(1);

        RasterHolder(BufferedImage rootImage, Ticket ticket) {
            this.rootImage = rootImage;
            this.ticket = ticket;
        }

        void retain() {
            refCount.incrementAndGet();
        }

        void release() {
            if (refCount.decrementAndGet() == 0) {
                try {
                    rootImage.flush();
                } catch (Exception ignored) { }
                ticket.close();
            }
        }
    }

    private static final class DefaultImageArtifact implements ImageArtifact {
        private final RasterHolder holder;
        private final BufferedImage image;
        private final AtomicBoolean closed = new AtomicBoolean();

        DefaultImageArtifact(RasterHolder holder, BufferedImage image) {
            this.holder = holder;
            this.image = image;
        }

        @Override
        public BufferedImage image() {
            if (closed.get()) {
                throw new IllegalStateException("ImageArtifact 已被关闭，无法访问底图");
            }
            return image;
        }

        @Override
        public long reservedBytes() {
            return holder.ticket.reservedBytes();
        }

        @Override
        public ImageArtifact retain() {
            if (closed.get()) {
                throw new IllegalStateException("ImageArtifact 已关闭，不能 retain");
            }
            holder.retain();
            return new DefaultImageArtifact(holder, image);
        }

        @Override
        public ImageArtifact subImage(int x, int y, int w, int h) {
            if (closed.get()) {
                throw new IllegalStateException("ImageArtifact 已关闭，不能创建子图");
            }
            BufferedImage sub = image.getSubimage(x, y, w, h);
            holder.retain();
            return new DefaultImageArtifact(holder, sub);
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                holder.release();
            }
        }
    }

    private static final class DefaultEncodedImageArtifact implements EncodedImageArtifact {
        private final byte[] data;
        private final String sha256;
        private final Ticket ticket;
        private final AtomicBoolean closed = new AtomicBoolean();

        DefaultEncodedImageArtifact(byte[] data, String sha256, Ticket ticket) {
            this.data = data;
            this.sha256 = sha256;
            this.ticket = ticket;
        }

        @Override
        public InputStream openStream() {
            if (closed.get()) {
                throw new IllegalStateException("EncodedImageArtifact 已关闭");
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public long byteLength() {
            return data.length;
        }

        @Override
        public String sha256() {
            return sha256;
        }

        @Override
        public byte[] toByteArray() {
            if (closed.get()) {
                throw new IllegalStateException("EncodedImageArtifact 已关闭");
            }
            return data.clone();
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                ticket.close();
            }
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static long calculateMaxInFlightBytes() {
        long configuredMb = envLong("RENDER_MAX_IN_FLIGHT_MB", 384);
        long configuredBytes = configuredMb * 1024L * 1024L;
        long maxHeap = defaultMaxHeap().getAsLong();
        if (maxHeap > 0) {
            long heapLimit = (long) Math.floor(maxHeap * 0.35);
            return Math.min(configuredBytes, Math.max(64L * 1024 * 1024, heapLimit));
        }
        return configuredBytes;
    }

    private static LongSupplier defaultUsedHeap() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        return () -> bean.getHeapMemoryUsage().getUsed();
    }

    private static LongSupplier defaultMaxHeap() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        return () -> bean.getHeapMemoryUsage().getMax();
    }

    private static int envInt(String name, int fallback) {
        try { String v = System.getenv(name); return v == null ? fallback : Integer.parseInt(v.strip()); }
        catch (Exception ignored) { return fallback; }
    }

    private static long envLong(String name, long fallback) {
        try { String v = System.getenv(name); return v == null ? fallback : Long.parseLong(v.strip()); }
        catch (Exception ignored) { return fallback; }
    }
}
