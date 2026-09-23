package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LedgerJournalTest {

    @TempDir
    Path tempDir;

    private Path dir;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws IOException {
        dir = tempDir.toRealPath();
    }

    @Test
    void writeAndReadValidFrames() throws Exception {
        DurableEventJournal journal = new DurableEventJournal();
        Path seg = dir.resolve("test.wal");
        UUID segId = UUID.randomUUID();
        journal.initSegment(seg, "book-1", segId, 1, "");

        byte[] payload1 = "{\"eventId\":\"e1\",\"type\":\"PREPARED\"}".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "{\"eventId\":\"e2\",\"type\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);

        journal.append(seg, 1, payload1);
        journal.append(seg, 2, payload2);

        DurableEventJournal.SegmentScanResult scan = journal.readSegment(seg, "book-1", false, null);
        assertNotNull(scan.header());
        assertEquals("book-1", scan.header().bookId());
        assertEquals(segId, scan.header().segmentId());
        assertEquals(1, scan.header().startSeq());
        assertEquals(2, scan.frames().size());

        assertEquals(1, scan.frames().get(0).seq());
        assertArrayEquals(payload1, scan.frames().get(0).payload());

        assertEquals(2, scan.frames().get(1).seq());
        assertArrayEquals(payload2, scan.frames().get(1).payload());
        assertFalse(scan.tailTruncated());
    }

    @Test
    void truncateAndRecoverIncompleteTailAtCrashBoundary() throws Exception {
        DurableEventJournal journal = new DurableEventJournal();
        Path seg = dir.resolve("crash.wal");
        UUID segId = UUID.randomUUID();
        journal.initSegment(seg, "book-1", segId, 1, "");

        byte[] p1 = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "{\"eventId\":\"e2\"}".getBytes(StandardCharsets.UTF_8);
        journal.append(seg, 1, p1);
        journal.append(seg, 2, p2);

        long validLength = Files.size(seg);

        // Simulate crash during 3rd append: partial frame written (only 8 bytes out of 40)
        try (FileChannel fc = FileChannel.open(seg, StandardOpenOption.WRITE)) {
            fc.position(fc.size());
            fc.write(ByteBuffer.wrap(new byte[]{0, 0, 0, 30, 0, 0, 0, 3})); // Length says 30, but file ends
        }

        List<String> warnings = new ArrayList<>();
        DurableEventJournal.SegmentScanResult scan = journal.readSegment(seg, "book-1", true, warnings);
        assertTrue(scan.tailTruncated());
        assertEquals(2, scan.frames().size());
        assertEquals(validLength, scan.validLength());
        assertEquals(validLength, Files.size(seg)); // Channel was truncated cleanly back to valid length
        assertFalse(warnings.isEmpty());

        // Now we can continue appending to the recovered segment!
        byte[] p3 = "{\"eventId\":\"e3\"}".getBytes(StandardCharsets.UTF_8);
        journal.append(seg, 3, p3);

        DurableEventJournal.SegmentScanResult scan2 = journal.readSegment(seg, "book-1", false, null);
        assertEquals(3, scan2.frames().size());
        assertEquals(3, scan2.frames().get(2).seq());
        assertArrayEquals(p3, scan2.frames().get(2).payload());
    }

    @Test
    void rejectMiddleCorruption() throws Exception {
        DurableEventJournal journal = new DurableEventJournal();
        Path seg = dir.resolve("corrupt-mid.wal");
        UUID segId = UUID.randomUUID();
        journal.initSegment(seg, "book-1", segId, 1, "");

        journal.append(seg, 1, "{\"e\":1}".getBytes(StandardCharsets.UTF_8));
        long midFrameOffset = Files.size(seg);
        journal.append(seg, 2, "{\"e\":2}".getBytes(StandardCharsets.UTF_8));
        journal.append(seg, 3, "{\"e\":3}".getBytes(StandardCharsets.UTF_8));

        // Corrupt frame 2 in the middle
        try (FileChannel fc = FileChannel.open(seg, StandardOpenOption.WRITE)) {
            fc.position(midFrameOffset + 6); // inside payload of frame 2
            fc.write(ByteBuffer.wrap(new byte[]{(byte) 0xFF, (byte) 0xFF}));
        }

        // Even with allowTruncatedTail=true, middle corruption must fail closed and throw IOException
        assertThrows(IOException.class, () -> journal.readSegment(seg, "book-1", true, null));
    }

    @Test
    void rejectOversizedPayload() throws Exception {
        DurableEventJournal journal = new DurableEventJournal();
        Path seg = dir.resolve("oversized.wal");
        journal.initSegment(seg, "book-1", UUID.randomUUID(), 1, "");

        byte[] huge = new byte[DurableEventJournal.MAX_FRAME_PAYLOAD_BYTES + 1];
        assertThrows(IOException.class, () -> journal.append(seg, 1, huge));
    }

    @Test
    void atomicManifestWriteReadAndValidation() throws Exception {
        AtomicManifestStore store = new AtomicManifestStore(json);
        Path base = dir.resolve("usage-v2");

        AtomicManifestStore.SegmentRef active = new AtomicManifestStore.SegmentRef(
                UUID.randomUUID().toString(), "active/seg-1.wal", 1, 10, 10, "sha256-hash");
        AtomicManifestStore.CheckpointRef cp = new AtomicManifestStore.CheckpointRef(
                1, 10, "checkpoints/1/checkpoint.json", "cp-sha256");

        AtomicManifestStore.Manifest manifest = new AtomicManifestStore.Manifest(
                1, "book-test", 1, 10, active, List.of(), cp, Instant.now(), "HEALTHY");

        store.write(base, manifest);

        assertTrue(store.exists(base));
        AtomicManifestStore.Manifest read = store.read(base, "book-test");
        assertNotNull(read);
        assertEquals(1, read.generation());
        assertEquals("book-test", read.bookId());
        assertEquals(10, read.appliedThroughSeq());
        assertEquals("HEALTHY", read.state());

        // BookId mismatch fails closed
        assertThrows(IOException.class, () -> store.read(base, "wrong-book"));
    }

    @Test
    void manifestRejectsDuplicateKeys() throws Exception {
        AtomicManifestStore store = new AtomicManifestStore(json);
        Path base = dir.resolve("bad-manifest");
        Files.createDirectories(base);
        Path manifestPath = AtomicManifestStore.manifestPath(base);

        String duplicateJson = """
                {
                  "schemaVersion": 1,
                  "schemaVersion": 2,
                  "bookId": "b1",
                  "generation": 1,
                  "appliedThroughSeq": 0,
                  "activeSegment": null,
                  "sealedSegments": [],
                  "checkpoint": null,
                  "updatedAt": "2026-09-23T00:00:00Z",
                  "state": "HEALTHY"
                }
                """;
        Files.writeString(manifestPath, duplicateJson);

        assertThrows(IOException.class, () -> store.read(base, "b1"));
    }
}
