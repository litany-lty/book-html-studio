package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.SourceChange;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceCheckpointTest {
    @TempDir Path temp;
    final String book=UUID.randomUUID().toString();
    final ObjectMapper json=spy(new ObjectMapper().findAndRegisterModules());
    Path dir;
    SourceChangeJournal journal;
    @BeforeEach void setup() throws Exception {
        dir=Files.createDirectory(temp.toRealPath().resolve(book));
        journal=new SourceChangeJournal(json);
    }
    static SourceChange event(String book,long seq,String state) {
        return new SourceChange(seq,"PAGE",book,(int)seq,new UUID(0,seq),null,0,1,
                "a".repeat(64),"b".repeat(64),"MANUAL_SAVE",state,Instant.parse("2026-01-01T00:00:00Z"));
    }
    void seed(int operations) throws Exception {
        var wal=new DurableEventJournal();Path events=Files.createDirectory(SourceChangeJournal.eventsDir(dir));
        Path active=null;int frames=0,segment=0;
        for(int seq=1;seq<=operations;seq++) for(String state:List.of("PREPARED","COMMITTED")) {
            if(active==null || frames==DurableEventJournal.MAX_SEGMENT_RECORDS) {
                String previous=active==null?"0".repeat(64):DurableEventJournal.sha256Hex(active);
                active=events.resolve(String.format("segment-%06d.wal",++segment));
                wal.initSegment(active,book,UUID.randomUUID(),seq,previous);frames=0;
            }
            wal.append(active,seq,json.writeValueAsBytes(event(book,seq,state)));frames++;
        }
    }
    long decodes() {
        return mockingDetails(json).getInvocations().stream().filter(i->i.getMethod().getName().equals("readTree")).count();
    }
    @Test void repeatedIndexFreshnessReadDoesNotDecodeUnchangedHistoryAgain() throws Exception {
        seed(140);assertFalse(journal.hasUnresolved(dir,book));clearInvocations(json);
        for(int i=0;i<5;i++) { assertEquals(140,journal.currentSourceSeq(dir,book));assertFalse(journal.hasUnresolved(dir,book)); }
        assertEquals(0,decodes(),"unchanged WAL must not be re-decoded on every index freshness read");
    }
    @Test void preparedAndSettledAppendReusesTheVerifiedProjection() throws Exception {
        seed(140);assertFalse(journal.hasUnresolved(dir,book));clearInvocations(json);
        var intent=journal.prepare(dir,book,"PAGE",1,UUID.randomUUID(),null,0,1,"a","b","TEST");
        journal.commit(dir,book,intent.sourceSeq(),1,intent.commitId(),1,"b");
        assertFalse(journal.hasUnresolved(dir,book));
        assertTrue(decodes()<20,"a new operation must not decode the entire settled history; decoded="+decodes());
    }
    @Test void verifiedSealedPrefixProducesADurableDerivedCheckpoint() throws Exception {
        seed(140);assertEquals(140,journal.currentSourceSeq(dir,book));
        assertTrue(Files.isRegularFile(dir.resolve("source-checkpoint.json")),"sealed history must produce a resumable checkpoint");
    }
    @Test void pendingEarlierOperationSurvivesNewerCompletedOperations() throws Exception {
        seed(130);
        var pending=journal.prepare(dir,book,"PAGE",1,UUID.randomUUID(),null,0,1,"a","b","TEST");
        var newer=journal.prepare(dir,book,"PAGE",2,UUID.randomUUID(),null,0,1,"c","d","TEST");
        journal.commit(dir,book,newer.sourceSeq(),2,newer.commitId(),1,"d");
        assertTrue(journal.hasUnresolved(dir,book));
        journal.commit(dir,book,pending.sourceSeq(),1,pending.commitId(),1,"b");
        assertFalse(journal.hasUnresolved(dir,book));
        assertEquals(newer.sourceSeq(),journal.currentSourceSeq(dir,book));
    }

    void evict() throws Exception {
        for(String name:List.of("SNAPSHOTS","WATERMARKS")) {
            var field=SourceChangeJournal.class.getDeclaredField(name);field.setAccessible(true);
            Map<?,?> values=(Map<?,?>)field.get(null);synchronized(values) { values.clear(); }
        }
    }
    Path checkpoint() { return dir.resolve("source-checkpoint.json"); }
    @Test void coldCheckpointDecodesOnlyTheActiveTailAfterVerifyingSealedHashes() throws Exception {
        seed(270);assertEquals(270,journal.currentSourceSeq(dir,book));evict();clearInvocations(json);
        var reader=new SourceChangeJournal(json);
        assertFalse(reader.hasUnresolved(dir,book));assertEquals(270,reader.currentSourceSeq(dir,book));
        assertEquals(28,decodes(),"512 sealed frames are restored from checkpoint; 28 active frames still verified");
    }
    @Test void pendingOperationsInCheckpointAreNotLostAcrossRestartAndOutOfOrderSettlement() throws Exception {
        seed(127);
        var first=journal.prepare(dir,book,"PAGE",1,UUID.randomUUID(),null,0,1,"a","b","TEST");
        var second=journal.prepare(dir,book,"PAGE",2,UUID.randomUUID(),null,0,1,"c","d","TEST");
        var third=journal.prepare(dir,book,"PAGE",3,UUID.randomUUID(),null,0,1,"e","f","TEST");
        journal.commit(dir,book,third.sourceSeq(),3,third.commitId(),1,"f");
        assertTrue(Files.isRegularFile(checkpoint()));evict();
        var reader=new SourceChangeJournal(json);assertTrue(reader.hasUnresolved(dir,book));
        reader.commit(dir,book,second.sourceSeq(),2,second.commitId(),1,"d");assertTrue(reader.hasUnresolved(dir,book));
        reader.commit(dir,book,first.sourceSeq(),1,first.commitId(),1,"b");assertFalse(reader.hasUnresolved(dir,book));
        assertEquals(third.sourceSeq(),reader.currentSourceSeq(dir,book));
    }
    @Test void discardedCheckpointIsRebuiltFromOriginalWalWithoutLosingAnyEvents() throws Exception {
        seed(140);assertFalse(journal.hasUnresolved(dir,book));var before=journal.readAll(dir,book);
        Files.delete(checkpoint());evict();
        assertEquals(140,new SourceChangeJournal(json).currentSourceSeq(dir,book));
        assertEquals(before,journal.readAll(dir,book));assertTrue(Files.isRegularFile(checkpoint()));
    }
    @Test void corruptOrWrongBookCheckpointFallsBackWithoutResettingSourceWatermark() throws Exception {
        seed(140);assertEquals(140,journal.currentSourceSeq(dir,book));byte[] original=Files.readAllBytes(checkpoint());
        List<byte[]> invalid=new ArrayList<>();invalid.add(Arrays.copyOf(original,original.length/2));
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original);
        tree.put("bookId",UUID.randomUUID().toString());invalid.add(json.writeValueAsBytes(tree));
        tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original);
        tree.put("sourceSeq",987654);invalid.add(json.writeValueAsBytes(tree));
        for(byte[] content:invalid) {
            Files.write(checkpoint(),content);evict();assertEquals(140,new SourceChangeJournal(json).currentSourceSeq(dir,book));
            assertFalse(journal.hasUnresolved(dir,book));assertEquals(280,journal.readAll(dir,book).size());
        }
    }
    @Test void checkpointParseRejectsCoercedDuplicateAndTraversalFields() throws Exception {
        seed(140);assertEquals(140,journal.currentSourceSeq(dir,book));byte[] original=Files.readAllBytes(checkpoint());
        List<String> invalid=new ArrayList<>();String text=new String(original,java.nio.charset.StandardCharsets.UTF_8);
        invalid.add(text.substring(0,text.length()-1)+",\"schemaVersion\":1}");
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original);
        tree.put("sourceSeq","128");invalid.add(tree.toString());
        tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original);
        tree.put("sourceSeq",128.5);invalid.add(tree.toString());
        tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("sealedSegments").get(0)).put("name","../../outside.wal");invalid.add(tree.toString());
        for(String value:invalid) {
            Files.writeString(checkpoint(),value);evict();clearInvocations(json);
            assertEquals(140,journal.currentSourceSeq(dir,book));
            assertEquals(280,decodes(),"invalid checkpoint must trigger actual authoritative replay");
        }
    }
    @Test void completeSealedCorruptionCannotBeHiddenByCheckpoint() throws Exception {
        seed(140);assertEquals(140,journal.currentSourceSeq(dir,book));evict();
        Path path=SourceChangeJournal.eventsDir(dir).resolve("segment-000001.wal");
        byte[] corrupt=Files.readAllBytes(path);corrupt[corrupt.length-1]^=1;Files.write(path,corrupt);
        assertThrows(Exception.class,()->journal.hasUnresolved(dir,book));
        assertThrows(Exception.class,()->journal.nextSourceSeq(dir,book));assertArrayEquals(corrupt,Files.readAllBytes(path));
    }
    @Test void warmSealedFingerprintDetectsSameSizeCorruptionEvenWithRestoredMtime() throws Exception {
        seed(140);assertFalse(journal.hasUnresolved(dir,book));
        Path path=SourceChangeJournal.eventsDir(dir).resolve("segment-000001.wal");var modified=Files.getLastModifiedTime(path);
        byte[] corrupt=Files.readAllBytes(path);corrupt[corrupt.length-1]^=1;Files.write(path,corrupt);Files.setLastModifiedTime(path,modified);
        assertThrows(Exception.class,()->journal.hasUnresolved(dir,book));assertArrayEquals(corrupt,Files.readAllBytes(path));
    }
    @Test void activeIncompleteTailIsRepairedWithoutChangingSealedSource() throws Exception {
        seed(140);assertFalse(journal.hasUnresolved(dir,book));
        Path events=SourceChangeJournal.eventsDir(dir),active=events.resolve("segment-000002.wal");
        String sealHash=DurableEventJournal.sha256Hex(events.resolve("segment-000001.wal"));long valid=Files.size(active);
        Files.write(active,new byte[]{0,0,0},StandardOpenOption.APPEND);
        assertEquals(140,journal.currentSourceSeq(dir,book));assertEquals(valid,Files.size(active));
        assertEquals(sealHash,DurableEventJournal.sha256Hex(events.resolve("segment-000001.wal")));
        var next=journal.prepare(dir,book,"PAGE",1,UUID.randomUUID(),null,0,1,"a","b","TEST");assertEquals(141,next.sourceSeq());
    }
    @Test void missingSealedSegmentFailsClosedRatherThanReusingCheckpointState() throws Exception {
        seed(270);assertEquals(270,journal.currentSourceSeq(dir,book));
        Files.delete(SourceChangeJournal.eventsDir(dir).resolve("segment-000002.wal"));
        assertThrows(Exception.class,()->journal.hasUnresolved(dir,book));assertThrows(Exception.class,()->journal.nextSourceSeq(dir,book));
    }
    @Test void checkpointWriteFailureDoesNotUndoSuccessfulAuthoritativeAppend() throws Exception {
        seed(140);Files.createDirectory(checkpoint());assertEquals(140,journal.currentSourceSeq(dir,book));
        var p=journal.prepare(dir,book,"PAGE",1,UUID.randomUUID(),null,0,1,"a","b","TEST");
        journal.commit(dir,book,p.sourceSeq(),1,p.commitId(),1,"b");assertFalse(journal.hasUnresolved(dir,book));
        evict();assertEquals(p.sourceSeq(),journal.currentSourceSeq(dir,book));assertTrue(Files.isDirectory(checkpoint()));
    }
    @Test void checkpointSymlinkIsNeitherFollowedNorOverwritten() throws Exception {
        seed(140);Path outside=temp.toRealPath().resolve("not-checkpoint.txt");Files.writeString(outside,"keep-me");
        Files.createSymbolicLink(checkpoint(),outside);
        assertEquals(140,journal.currentSourceSeq(dir,book));assertEquals("keep-me",Files.readString(outside));
        assertTrue(Files.isSymbolicLink(checkpoint()));
    }
    @Test void oldSettlementBeyondBoundedRecentCacheRemainsIdempotent() throws Exception {
        seed(270);assertFalse(journal.hasUnresolved(dir,book));long before=journal.readAll(dir,book).size();
        evict();journal.commit(dir,book,1,1,new UUID(0,1),1,"b".repeat(64));
        assertEquals(before,journal.readAll(dir,book).size());assertFalse(journal.hasUnresolved(dir,book));
    }
    @Test void secondHandleSeesPendingAndCompletedWritesWithoutReplayingSourcePayloads() throws Exception {
        seed(140);assertFalse(journal.hasUnresolved(dir,book));var second=new SourceChangeJournal(json);clearInvocations(json);
        var p=second.prepare(dir,book,"PAGE",1,UUID.randomUUID(),null,0,1,"a","b","TEST");
        assertTrue(journal.hasUnresolved(dir,book));journal.commit(dir,book,p.sourceSeq(),1,p.commitId(),1,"b");
        assertFalse(second.hasUnresolved(dir,book));assertEquals(0,decodes());
    }
    @Test void changedActiveTailIsVerifiedEvenWhenSealedCheckpointIsStillValid() throws Exception {
        seed(140);assertFalse(journal.hasUnresolved(dir,book));
        Path active=SourceChangeJournal.eventsDir(dir).resolve("segment-000002.wal");
        new DurableEventJournal().append(active,141,json.writeValueAsBytes(event(book,141,"PREPARED")));
        clearInvocations(json);assertTrue(journal.hasUnresolved(dir,book));assertEquals(141,journal.currentSourceSeq(dir,book));
        assertEquals(25,decodes(),"24 old active frames and one new frame, not the sealed prefix");
    }

    @Test void missingWholeHistoryCannotBeReinitializedFromZero() throws Exception {
        seed(140);assertEquals(140,journal.currentSourceSeq(dir,book));
        try(var files=Files.list(SourceChangeJournal.eventsDir(dir))) { for(Path file:files.toList()) Files.delete(file); }
        evict();assertThrows(Exception.class,()->journal.currentSourceSeq(dir,book));
        assertThrows(Exception.class,()->journal.prepare(dir,book,"PAGE",1,UUID.randomUUID(),null,0,1,"a","b","TEST"));
        assertTrue(Files.isRegularFile(checkpoint()));
    }
    @Test void missingActiveSegmentIsNotMistakenForACompleteShorterJournal() throws Exception {
        seed(140);assertEquals(140,journal.currentSourceSeq(dir,book));
        Files.delete(SourceChangeJournal.eventsDir(dir).resolve("segment-000002.wal"));
        evict();assertThrows(Exception.class,()->journal.currentSourceSeq(dir,book));
    }
    @Test void recoveryProjectionNeverEvictsPendingOperationsToMakeRoom() throws Exception {
        var projection=new SourceReplayState();
        for(int i=1;i<=SourceReplayState.MAX_PENDING;i++) projection.apply(event(book,i,"PREPARED"));
        var candidate=projection.copy();
        assertThrows(java.io.IOException.class,()->candidate.apply(event(book,SourceReplayState.MAX_PENDING+1L,"PREPARED")));
        assertEquals(SourceReplayState.MAX_PENDING,projection.pending.size());
        assertNotNull(projection.operation(1));assertTrue(projection.recent.isEmpty());
    }
    @Test void freshJvmRecoversCheckpointAndTailAfterWriterProcessHalts() throws Exception {
        seed(127);
        journal.prepare(dir,book,"PAGE",1,UUID.randomUUID(),null,0,1,"a","b","TEST");
        journal.prepare(dir,book,"PAGE",2,UUID.randomUUID(),null,0,1,"c","d","TEST");
        assertEquals(23,child("append-halt", "SOURCE_APPEND_FORCED"));
        assertTrue(Files.isRegularFile(checkpoint()));
        assertEquals(0,child("read", "SOURCE:130:true"));
        evict();assertEquals(130,journal.currentSourceSeq(dir,book));assertTrue(journal.hasUnresolved(dir,book));
        assertEquals(257,journal.readAll(dir,book).size());
    }
    int child(String mode,String expected) throws Exception {
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Path output=dir.resolve("child-"+mode+".log");
        Process process=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("java.class.path"),RestartProbe.class.getName(),
                mode,dir.toString(),book).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS),"isolated source probe deadline");
            assertTrue(Files.readString(output).contains(expected));return process.exitValue();
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    public static final class RestartProbe {
        public static void main(String[] args) throws Exception {
            Path dir=Path.of(args[1]);String book=args[2];var journal=new SourceChangeJournal(new ObjectMapper().findAndRegisterModules());
            if(args[0].equals("append-halt")) {
                journal.prepare(dir,book,"PAGE",3,UUID.randomUUID(),null,0,1,"e","f","TEST");
                System.out.println("SOURCE_APPEND_FORCED");System.out.flush();Runtime.getRuntime().halt(23);
            }
            System.out.println("SOURCE:"+journal.currentSourceSeq(dir,book)+":"+journal.hasUnresolved(dir,book));
        }
    }

    @Test void mutationDuringReplayCannotBeCachedAsIfTheChangedBytesWereVerified() throws Exception {
        seed(140);assertEquals(140,journal.currentSourceSeq(dir,book));evict();
        Path active=SourceChangeJournal.eventsDir(dir).resolve("segment-000002.wal");
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv->{
            Object node=inv.callRealMethod();
            if(changed.compareAndSet(false,true)) Files.setLastModifiedTime(active,
                    java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(active).toMillis()+5000));
            return node;
        }).when(json).readTree(any(com.fasterxml.jackson.core.JsonParser.class));
        assertThrows(java.io.IOException.class,()->journal.hasUnresolved(dir,book));
        assertTrue(changed.get());
        assertEquals(140,journal.currentSourceSeq(dir,book),"a subsequent stable verification may succeed");
    }
}
