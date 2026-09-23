package studio.bookhtml.store;

import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * A1-05：数据目录单写者租约，生命周期明确、可释放。
 *
 * <p>规则：
 * <ul>
 *   <li>以 {@code toRealPath()} 后的规范路径为同一性，符号链接别名视为同一目录；</li>
 *   <li>JVM 内先查 registry，已有租约直接显式复用（引用计数，共享同一 channel/lock/monitor），
 *       绝不在复用路径上第二次打开锁文件；</li>
 *   <li>只有首个租约持有者打开 channel 并 {@code tryLock}；外部进程持有则拒绝启动，
 *       不删除锁文件、不触碰其他进程；</li>
 *   <li>引用计数归零时释放锁、关闭 channel、清理 registry；{@link #close()} 可重复调用；</li>
 *   <li>失败原因可诊断：被占用 / 不可写 / 锁机制不可用分别报错，不再统一说“另一进程”。</li>
 * </ul>
 */
public final class DataDirectoryLease implements AutoCloseable {
    private static final Map<String, Entry> REGISTRY = new HashMap<>();
    private static final String LOCK_NAME = ".write.lock";

    private static final class Entry {
        final Path realPath;
        final FileChannel channel;
        final FileLock lock;
        final Object monitor = new Object();
        final java.util.Set<java.util.UUID> revokedAttempts = new java.util.HashSet<>();
        int refCount;
        Entry(Path realPath, FileChannel channel, FileLock lock) {
            this.realPath = realPath;
            this.channel = channel;
            this.lock = lock;
            this.refCount = 1;
        }
    }

    private final String key;
    private final Entry entry;
    private boolean closed;

    private DataDirectoryLease(String key, Entry entry) {
        this.key = key;
        this.entry = entry;
    }

    /** 与该目录同一提交锁监视器（同一目录所有复用者共享）。 */
    public Object monitor() {
        return entry.monitor;
    }

    /** Guarded by the shared monitor, same lifetime as the directory lease. */
    java.util.Set<java.util.UUID> revokedAttempts() { return entry.revokedAttempts; }

    public Path realPath() {
        return entry.realPath;
    }

    public static synchronized DataDirectoryLease acquire(Path dataDir) throws IOException {
        Path absolute = dataDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "数据目录不可写：" + absolute + "（" + safeReason(e) + "）");
        }
        Path real;
        try {
            real = absolute.toRealPath();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "数据目录路径无法规范化：" + absolute + "（" + safeReason(e) + "）");
        }
        String key = real.toString();
        Entry existing = REGISTRY.get(key);
        if (existing != null) {
            existing.refCount++;
            return new DataDirectoryLease(key, existing);
        }
        Path lockFile = real.resolve(LOCK_NAME);
        FileChannel channel;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "锁文件不可写：" + lockFile + "（" + safeReason(e) + "），已拒绝启动以保护数据");
        }
        final FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (IOException e) {
            closeQuietly(channel);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件锁机制不可用：" + lockFile + "（" + safeReason(e) + "），已拒绝启动");
        }
        if (lock == null) {
            closeQuietly(channel);
            throw new ApiException(HttpStatus.CONFLICT,
                    "数据目录正被另一进程使用：" + real + "，已拒绝共享写入（锁文件本身存在不代表被持有）");
        }
        Entry entry = new Entry(real, channel, lock);
        REGISTRY.put(key, entry);
        return new DataDirectoryLease(key, entry);
    }

    /** 引用计数减一；最后一个持有者释放锁、关闭 channel 并清理 registry。 */
    @Override
    public void close() {
        synchronized (DataDirectoryLease.class) {
            if (closed) return;
            closed = true;
            Entry entry = REGISTRY.get(key);
            if (entry == null || entry != this.entry) return;
            entry.refCount--;
            if (entry.refCount <= 0) {
                REGISTRY.remove(key);
                try {
                    entry.lock.release();
                } catch (IOException ignored) {
                }
                closeQuietly(entry.channel);
            }
        }
    }

    static synchronized int registrationsForTest() {
        return REGISTRY.size();
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    private static String safeReason(Exception e) {
        String message = e == null ? null : e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
