package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Forced, verified, atomic replacement. No non-atomic fallback for authority or pages. */
public final class DurableJson {
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private DurableJson() {}

    public static void write(Path target, Object value, ObjectMapper json, int maxBytes) throws IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length > maxBytes) throw new IOException("durable record exceeds limit");
        rejectLinks(target);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".publish-", ".tmp");
        boolean interrupted = Thread.interrupted();
        boolean renamed = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (!MessageDigest.isEqual(bytes, Files.readAllBytes(temp)))
                throw new IOException("durable temporary record verification failed");
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            renamed = true;
            try (FileChannel directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException unsupported) {
                if (WARNED.compareAndSet(false, true))
                    System.getLogger(DurableJson.class.getName()).log(System.Logger.Level.WARNING,
                            "Directory fsync unavailable; directory-entry power-loss durability is platform dependent");
            }
        } finally {
            try { Files.deleteIfExists(temp); }
            catch (IOException failure) { if (!renamed) throw failure; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    public static void rejectLinks(Path target) throws IOException {
        // The data root is canonicalized by DataDirectoryLease before paths reach here.
        for (Path p = target; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("unsafe durable path");
        }
    }
}
