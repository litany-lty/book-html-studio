package studio.bookhtml.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Append-only durable event journal for WAL segments.
 * Enforces segment size bounds (256 records / 4MiB), strict frame verification,
 * CRC32 checksums, crash-safe fsync on append, and bounded tail recovery.
 */
public final class DurableEventJournal {
    public static final int MAX_FRAME_PAYLOAD_BYTES = 32768; // 32KiB per frame
    public static final int MAX_SEGMENT_RECORDS = 256;
    public static final long MAX_SEGMENT_BYTES = 4 * 1024 * 1024L; // 4MiB
    private static final byte[] MAGIC = "BOOK_WAL_SEG_V1\0".getBytes(StandardCharsets.US_ASCII);
    private static final int SCHEMA_VERSION = 1;

    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    public record SegmentHeader(
            int schemaVersion,
            String bookId,
            UUID segmentId,
            long startSeq,
            String prevSegmentSha256
    ) {}

    public record JournalFrame(
            long seq,
            byte[] payload,
            long fileOffset
    ) {}

    public record SegmentScanResult(
            SegmentHeader header,
            List<JournalFrame> frames,
            long validLength,
            boolean tailTruncated
    ) {}

    public DurableEventJournal() {}

    /**
     * Initializes a new segment file with a validated header.
     */
    public void initSegment(Path segmentPath, String bookId, UUID segmentId, long startSeq, String prevSegmentSha256) throws IOException {
        Objects.requireNonNull(segmentPath, "segmentPath");
        Objects.requireNonNull(bookId, "bookId");
        Objects.requireNonNull(segmentId, "segmentId");
        DurableJson.rejectLinks(segmentPath);

        Files.createDirectories(segmentPath.getParent());
        if (Files.exists(segmentPath, LinkOption.NOFOLLOW_LINKS) && Files.size(segmentPath) > 0) {
            throw new IOException("segment file already exists and is non-empty: " + segmentPath);
        }

        byte[] bookIdBytes = bookId.getBytes(StandardCharsets.UTF_8);
        if (bookIdBytes.length > 128) {
            throw new IOException("bookId too long: " + bookIdBytes.length);
        }
        String safePrevSha = prevSegmentSha256 == null ? "" : prevSegmentSha256;
        byte[] prevShaBytes = safePrevSha.getBytes(StandardCharsets.US_ASCII);

        ByteBuffer buf = ByteBuffer.allocate(MAGIC.length + 4 + 4 + bookIdBytes.length + 16 + 8 + 4 + prevShaBytes.length + 8);
        buf.put(MAGIC);
        buf.putInt(SCHEMA_VERSION);
        buf.putInt(bookIdBytes.length);
        buf.put(bookIdBytes);
        buf.putLong(segmentId.getMostSignificantBits());
        buf.putLong(segmentId.getLeastSignificantBits());
        buf.putLong(startSeq);
        buf.putInt(prevShaBytes.length);
        buf.put(prevShaBytes);

        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, buf.position());
        buf.putLong(crc.getValue());
        buf.flip();

        try (FileChannel channel = FileChannel.open(segmentPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            while (buf.hasRemaining()) channel.write(buf);
            channel.force(true);
        }
        setPermissions(segmentPath, FILE_PERMS);
    }

    /**
     * Appends an event frame to the segment and fsyncs.
     * Returns the total segment byte size after append.
     */
    public long append(Path segmentPath, long seq, byte[] payload) throws IOException {
        Objects.requireNonNull(segmentPath, "segmentPath");
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0 || payload.length > MAX_FRAME_PAYLOAD_BYTES) {
            throw new IOException("payload length exceeds bound (0 < " + payload.length + " <= " + MAX_FRAME_PAYLOAD_BYTES + ")");
        }
        DurableJson.rejectLinks(segmentPath);

        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + payload.length + 8);
        buf.putInt(payload.length);
        buf.putLong(seq);
        buf.put(payload);

        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, 4 + 8 + payload.length);
        buf.putLong(crc.getValue());
        buf.flip();

        try (FileChannel channel = FileChannel.open(segmentPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.position(channel.size());
            while (buf.hasRemaining()) channel.write(buf);
            channel.force(true);
            return channel.size();
        }
    }

    /**
     * Reads and verifies all frames in a segment file.
     * If allowTruncatedTail is true and an incomplete or corrupt frame exists strictly at the end,
     * it truncates the file back to the last valid frame.
     */
    public SegmentScanResult readSegment(Path segmentPath, String expectedBookId, boolean allowTruncatedTail, List<String> warnings) throws IOException {
        DurableJson.rejectLinks(segmentPath);
        if (!Files.exists(segmentPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("segment file does not exist: " + segmentPath);
        }
        if (Files.isSymbolicLink(segmentPath) || !Files.isRegularFile(segmentPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("segment file unsafe: " + segmentPath);
        }

        try (FileChannel channel = allowTruncatedTail
                ? FileChannel.open(segmentPath,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)
                : FileChannel.open(segmentPath,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            long fileSize = channel.size();
            if(fileSize>MAX_SEGMENT_BYTES+MAX_FRAME_PAYLOAD_BYTES+512) throw new IOException("WAL segment exceeds read bound");
            SegmentHeader header = readHeader(channel, expectedBookId);
            long validLength = channel.position();
            List<JournalFrame> frames = new ArrayList<>();
            boolean tailTruncated = false;

            while (channel.position() < fileSize) {
                long frameStart = channel.position();
                ByteBuffer lenBuf = ByteBuffer.allocate(4);
                int read = fill(channel,lenBuf);
                if (read < 4) {
                    if (allowTruncatedTail) {
                        tailTruncated = true;
                        if (warnings != null) warnings.add("Incomplete frame length at tail offset " + frameStart);
                        break;
                    } else {
                        throw new IOException("unexpected EOF reading frame length at offset " + frameStart);
                    }
                }
                lenBuf.flip();
                int payloadLen = lenBuf.getInt();
                if (payloadLen <= 0 || payloadLen > MAX_FRAME_PAYLOAD_BYTES) {
                    if (allowTruncatedTail && fileSize-frameStart < 20) {
                        tailTruncated = true;
                        if (warnings != null) warnings.add("Corrupted frame length at tail offset " + frameStart);
                        break;
                    } else {
                        throw new IOException("invalid frame payload length " + payloadLen + " at offset " + frameStart);
                    }
                }

                int restSize = 8 + payloadLen + 8; // seq(8) + payload + crc(8)
                ByteBuffer restBuf = ByteBuffer.allocate(restSize);
                read = fill(channel,restBuf);
                if (read < restSize) {
                    if (allowTruncatedTail) {
                        tailTruncated = true;
                        if (warnings != null) warnings.add("Incomplete frame body at tail offset " + frameStart);
                        break;
                    } else {
                        throw new IOException("unexpected EOF reading frame body at offset " + frameStart);
                    }
                }
                restBuf.flip();
                long seq = restBuf.getLong();
                byte[] payload = new byte[payloadLen];
                restBuf.get(payload);
                long expectedCrc = restBuf.getLong();

                CRC32 crc = new CRC32();
                ByteBuffer verifyBuf = ByteBuffer.allocate(4 + 8 + payloadLen);
                verifyBuf.putInt(payloadLen);
                verifyBuf.putLong(seq);
                verifyBuf.put(payload);
                crc.update(verifyBuf.array(), 0, verifyBuf.capacity());

                if (crc.getValue() != expectedCrc) {
                    // A complete bad checksum may be disk corruption, not an incomplete append.
                    // Preserve its bytes and reject, including the final frame.
                    throw new IOException("frame checksum mismatch at seq " + seq + ", offset " + frameStart);
                }

                frames.add(new JournalFrame(seq, payload, frameStart));
                validLength = channel.position();
            }

            if (tailTruncated) {
                channel.truncate(validLength);
                channel.force(true);
            }
            return new SegmentScanResult(header, Collections.unmodifiableList(frames), validLength, tailTruncated);
        }
    }

    private static int fill(FileChannel channel,ByteBuffer buffer) throws IOException {
        int total=0;
        while(buffer.hasRemaining()) { int count=channel.read(buffer);if(count<0) break;total+=count; }
        return total;
    }

    private SegmentHeader readHeader(FileChannel channel, String expectedBookId) throws IOException {
        channel.position(0);
        ByteBuffer magicBuf = ByteBuffer.allocate(MAGIC.length);
        if (fill(channel,magicBuf) < MAGIC.length) throw new IOException("segment file truncated at magic header");
        magicBuf.flip();
        byte[] actualMagic = new byte[MAGIC.length];
        magicBuf.get(actualMagic);
        if (!Arrays.equals(MAGIC, actualMagic)) {
            throw new IOException("invalid segment magic bytes");
        }

        ByteBuffer metaBuf = ByteBuffer.allocate(4 + 4); // schemaVersion + bookIdLen
        if (fill(channel,metaBuf) < metaBuf.capacity()) throw new IOException("truncated segment header");
        metaBuf.flip();
        int schemaVersion = metaBuf.getInt();
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IOException("unsupported segment schema version: " + schemaVersion);
        }
        int bookIdLen = metaBuf.getInt();
        if (bookIdLen <= 0 || bookIdLen > 128) throw new IOException("invalid bookId length in segment: " + bookIdLen);

        ByteBuffer bookIdBuf = ByteBuffer.allocate(bookIdLen);
        if (fill(channel,bookIdBuf) < bookIdLen) throw new IOException("truncated bookId in segment");
        String bookId = new String(bookIdBuf.array(), StandardCharsets.UTF_8);
        if (expectedBookId != null && !expectedBookId.equals(bookId)) {
            throw new IOException("bookId mismatch in segment: expected " + expectedBookId + ", got " + bookId);
        }

        ByteBuffer tailBuf = ByteBuffer.allocate(16 + 8 + 4); // segmentId(16) + startSeq(8) + prevShaLen(4)
        if (fill(channel,tailBuf) < tailBuf.capacity()) throw new IOException("truncated segment header tail");
        tailBuf.flip();
        long mostSig = tailBuf.getLong();
        long leastSig = tailBuf.getLong();
        UUID segmentId = new UUID(mostSig, leastSig);
        long startSeq = tailBuf.getLong();
        int prevShaLen = tailBuf.getInt();
        if (prevShaLen < 0 || prevShaLen > 128) throw new IOException("invalid prevShaLen in segment: " + prevShaLen);

        ByteBuffer prevShaBuf = ByteBuffer.allocate(prevShaLen);
        if (fill(channel,prevShaBuf) < prevShaLen) throw new IOException("truncated prevSha in segment");
        String prevSha = new String(prevShaBuf.array(), StandardCharsets.US_ASCII);

        ByteBuffer crcBuf = ByteBuffer.allocate(8);
        if (fill(channel,crcBuf) < 8) throw new IOException("truncated header crc");
        crcBuf.flip();
        long expectedCrc = crcBuf.getLong();

        // Verify header checksum
        int headerDataLen = (int) channel.position() - 8;
        channel.position(0);
        ByteBuffer checkBuf = ByteBuffer.allocate(headerDataLen);
        fill(channel,checkBuf);
        CRC32 crc = new CRC32();
        crc.update(checkBuf.array(), 0, headerDataLen);
        if (crc.getValue() != expectedCrc) {
            throw new IOException("segment header checksum mismatch");
        }
        channel.position(headerDataLen + 8);

        return new SegmentHeader(schemaVersion, bookId, segmentId, startSeq, prevSha);
    }

    public static String sha256Hex(Path path) throws IOException {
        DurableJson.rejectLinks(path);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            if (e instanceof IOException io) throw io;
            throw new IOException("failed to compute sha256: " + e.getMessage(), e);
        }
    }

    public void sealSegment(Path activePath, Path sealedPath) throws IOException {
        DurableJson.rejectLinks(activePath);
        DurableJson.rejectLinks(sealedPath);
        Files.createDirectories(sealedPath.getParent());
        Files.move(activePath, sealedPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel dirChannel = FileChannel.open(sealedPath.getParent(), StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {}
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } catch (Exception ignored) {}
    }
}
