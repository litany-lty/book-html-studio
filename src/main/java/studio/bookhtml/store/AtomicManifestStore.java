package studio.bookhtml.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Atomic manifest store for book event journals, snapshots, and index roots.
 * Strictly bounded to <= 64KiB with crash-safe atomic publish and directory fsync.
 */
public final class AtomicManifestStore {
    public static final int MAX_MANIFEST_BYTES = 65536; // 64KiB bound

    private static final Set<PosixFilePermission> DIR_PERMS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    public record SegmentRef(String segmentId, String filename, long startSeq, long endSeq, int recordCount, String sha256) {}
    public record CheckpointRef(long generation, long appliedThroughSeq, String filename, String sha256) {}

    public record Manifest(
            int schemaVersion,
            String bookId,
            long generation,
            long appliedThroughSeq,
            SegmentRef activeSegment,
            List<SegmentRef> sealedSegments,
            CheckpointRef checkpoint,
            Instant updatedAt,
            String state
    ) {
        public Manifest {
            sealedSegments = sealedSegments == null ? List.of() : List.copyOf(sealedSegments);
        }
    }

    private final ObjectMapper json;

    public AtomicManifestStore(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public static Path manifestPath(Path baseDir) {
        return baseDir.resolve("manifest.json");
    }

    public static Path stagingDir(Path baseDir) {
        return baseDir.resolve("staging");
    }

    public boolean exists(Path baseDir) throws IOException {
        Path path = manifestPath(baseDir);
        DurableJson.rejectLinks(path);
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    public Manifest read(Path baseDir, String expectedBookId) throws IOException {
        Path path = manifestPath(baseDir);
        DurableJson.rejectLinks(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(baseDir, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("manifest file or directory unsafe");
        }
        long size = Files.size(path);
        if (size > MAX_MANIFEST_BYTES) {
            throw new IOException("manifest exceeds max allowed bytes (" + size + " > " + MAX_MANIFEST_BYTES + ")");
        }
        byte[] bytes = Files.readAllBytes(path);
        try (JsonParser parser = json.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree = json.readTree(parser);
            if (tree == null || !tree.isObject() || parser.nextToken() != null) {
                throw new IOException("malformed manifest JSON");
            }
            validateIntegralField(tree, "schemaVersion", 1, 1);
            validateIntegralField(tree, "generation", 1, Long.MAX_VALUE);
            validateIntegralField(tree, "appliedThroughSeq", 0, Long.MAX_VALUE);

            Manifest manifest = json.treeToValue(tree, Manifest.class);
            if (manifest == null) throw new IOException("manifest deserialization returned null");
            if (expectedBookId != null && !expectedBookId.equals(manifest.bookId())) {
                throw new IOException("manifest bookId mismatch: expected " + expectedBookId + ", got " + manifest.bookId());
            }
            if (manifest.activeSegment() == null && manifest.appliedThroughSeq() > 0) {
                throw new IOException("missing active segment in non-empty manifest");
            }
            return manifest;
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("failed to read manifest: " + e.getMessage(), e);
        }
    }

    public void write(Path baseDir, Manifest manifest) throws IOException {
        Objects.requireNonNull(baseDir, "baseDir");
        Objects.requireNonNull(manifest, "manifest");
        Path target = manifestPath(baseDir);
        DurableJson.rejectLinks(target);

        byte[] bytes = json.writeValueAsBytes(manifest);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("manifest exceeds max allowed bytes: " + bytes.length);
        }

        Path staging = stagingDir(baseDir);
        ensureDirectory(staging);
        Path temp = Files.createTempFile(staging, ".manifest-gen-" + manifest.generation() + "-", ".tmp");
        setPermissions(temp, FILE_PERMS);

        boolean interrupted = Thread.interrupted();
        boolean renamed = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (!MessageDigest.isEqual(bytes, Files.readAllBytes(temp))) {
                throw new IOException("temporary manifest verification failed");
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            renamed = true;
            try (FileChannel dirChannel = FileChannel.open(baseDir, StandardOpenOption.READ)) {
                dirChannel.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {}
        } finally {
            try { Files.deleteIfExists(temp); }
            catch (IOException failure) { if (!renamed) throw failure; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static void validateIntegralField(JsonNode tree, String field, long min, long max) throws IOException {
        JsonNode node = tree.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw new IOException("field " + field + " is missing or not integral");
        }
        long val = node.longValue();
        if (val < min || val > max) {
            throw new IOException("field " + field + " out of valid range: " + val);
        }
    }

    private static void ensureDirectory(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir);
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("directory unsafe: " + dir);
        }
        setPermissions(dir, DIR_PERMS);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (Exception ignored) {}
    }
}
