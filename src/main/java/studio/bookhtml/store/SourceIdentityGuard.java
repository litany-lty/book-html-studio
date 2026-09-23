package studio.bookhtml.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Content hash plus a bounded freshness cache. Without a file key and change-time,
 * bytes are rehashed. Size/mtime are never used as the content identity. */
final class SourceIdentityGuard {
    record Stamp(boolean present, long size, String modified, String changed, String fileKey) {}
    record Snapshot(String sha256, Stamp stamp) {}
    private final Map<Path, Snapshot> cache = new LinkedHashMap<>(32, .75f, true);

    synchronized Snapshot capture(Path path) throws IOException {
        Stamp before = stamp(path);
        if (!before.present()) { cache.remove(path); return new Snapshot("ABSENT", before); }
        Snapshot known = cache.get(path);
        if (known != null && before.changed() != null && before.fileKey() != null
                && before.equals(known.stamp())) return known;
        String hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            hash = HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (!before.equals(stamp(path))) throw new IOException("source changed during hashing");
        Snapshot result = new Snapshot(hash, before);
        cache.put(path, result);
        while (cache.size() > 32) cache.remove(cache.keySet().iterator().next());
        return result;
    }

    void verifyStable(Path path, Snapshot snapshot) throws IOException {
        if (snapshot == null || !snapshot.stamp().equals(stamp(path)))
            throw new IOException("source changed before publication");
    }

    private static Stamp stamp(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent()))
            throw new IOException("unsafe source path");
        BasicFileAttributes a;
        try { a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return new Stamp(false, 0, null, null, null); }
        if (!a.isRegularFile()) throw new IOException("source is not a regular file");
        String changed = null;
        try { changed = String.valueOf(Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS)); }
        catch (UnsupportedOperationException | IllegalArgumentException unsupported) { /* Always rehash. */ }
        return new Stamp(true, a.size(), a.lastModifiedTime().toString(), changed,
                a.fileKey() == null ? null : a.fileKey().toString());
    }
}
