package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ComprehensibilityResumeStoreTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString(),input="a".repeat(64),group="b".repeat(64);
    BookStore store;ComprehensibilityResumeStore cache;
    final Instant instant=Instant.parse("2026-09-26T01:02:03.123456789Z");
    @BeforeEach void setup() throws Exception {
        store=new BookStore(TestConfigs.config(temp,"",""),json);store.createBookDirectory(book);
        store.writeBook(new Book(book,"fixture","fixture.pdf",40,instant,instant,0,0));
        for(int page=1;page<=40;page++)store.writePage(book,Page.pending(page,600,800),false);
        cache=at(instant);
    }
    @AfterEach void close(){store.close();}
    ComprehensibilityResumeStore at(Instant now){return new ComprehensibilityResumeStore(store,json,Clock.fixed(now,ZoneOffset.UTC));}
    String body(){return "{\"findings\":[]}";}
    @Test void nanosecondTimestampsAndEmptyFindingsSurviveFreshReader() throws Exception {
        var session=cache.open(book,1,input,2);assertTrue(session.available());session.put(0,group,body());
        var disk=cache.read(cache.path(book,1),book,1);assertEquals(instant,disk.startedAt());assertEquals(instant,disk.updatedAt());
        assertEquals(body(),at(instant).open(book,1,input,2).get(0,group));
    }
    @Test void oldWriterCannotReplaceNewerReviewOwnership() throws Exception {
        var first=cache.open(book,1,input,2);var second=at(instant).open(book,1,input,2);
        second.put(1,group,body());first.put(0,group,body());assertFalse(first.available());
        var next=cache.open(book,1,input,2);assertNull(next.get(0,group));assertEquals(body(),next.get(1,group));
    }
    @Test void expiryIsFixedNotExtendedByCacheAccess() throws Exception {
        cache.open(book,1,input,1).put(0,group,body());
        var almost=at(instant.plus(Duration.ofDays(6))).open(book,1,input,1);assertEquals(body(),almost.get(0,group));
        assertEquals(instant.plus(Duration.ofDays(7)),almost.expiresAt());
        assertNull(at(instant.plus(Duration.ofDays(7))).open(book,1,input,1).get(0,group));
    }
    @Test void fullPlanAndGroupIdentityMustBothMatch() throws Exception {
        cache.open(book,1,input,2).put(0,group,body());
        assertNull(cache.open(book,1,input,2).get(0,"c".repeat(64)));
        assertNull(cache.open(book,1,"d".repeat(64),2).get(0,group));
    }
    @Test void malformedRecordsRemainUntouchedAndUnavailable() throws Exception {
        cache.open(book,1,input,1).put(0,group,body());Path path=cache.path(book,1);String valid=Files.readString(path);
        List<String> invalid=new ArrayList<>();invalid.add("broken");invalid.add(valid+"{}");
        invalid.add(valid.substring(0,valid.length()-1)+",\"schemaVersion\":1}");
        for(String field:List.of("schemaVersion","pageNumber","planned")){
            var object=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(valid);object.put(field,1.5);invalid.add(json.writeValueAsString(object));
        }
        var foreign=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(valid);foreign.put("bookId",UUID.randomUUID().toString());invalid.add(json.writeValueAsString(foreign));
        invalid.add(valid.replace("findings","changed"));
        for(String value:invalid){Files.writeString(path,value);assertFalse(cache.open(book,1,input,1).available());assertEquals(value,Files.readString(path));}
    }
    @Test void symlinkCannotReadOrOverwriteAnotherFile() throws Exception {
        Path outside=temp.toRealPath().resolve("outside");Files.writeString(outside,"private fixture");Path path=cache.path(book,1);
        Files.createDirectories(path.getParent());Files.createSymbolicLink(path,outside);
        assertFalse(cache.open(book,1,input,1).available());assertTrue(Files.isSymbolicLink(path));assertEquals("private fixture",Files.readString(outside));
    }
    @Test void oversizeFileIsRejectedWithoutAllocationOrReplacement() throws Exception {
        Path path=cache.path(book,1);Files.createDirectories(path.getParent());
        try(var file=new java.io.RandomAccessFile(path.toFile(),"rw")){file.setLength(ComprehensibilityResumeStore.MAX_BYTES+1L);}
        assertFalse(cache.open(book,1,input,1).available());assertEquals(ComprehensibilityResumeStore.MAX_BYTES+1L,Files.size(path));
    }
    @Test void successfulResultsRemainReadableAfterWriteFailure() throws Exception {
        var session=cache.open(book,1,input,2);session.put(0,group,body());Path path=cache.path(book,1);Files.delete(path);Files.createDirectory(path);
        session.put(1,group,body());assertFalse(session.available());assertEquals(body(),session.get(0,group));assertNull(session.get(1,group));
        assertTrue(Files.isDirectory(path));
    }
    @Test void recordCapacityDoesNotEvictRecentUnfinishedEvidence() throws Exception {
        for(int page=1;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,2).put(0,group,body());
        assertFalse(cache.open(book,33,input,2).available());assertEquals(body(),cache.open(book,1,input,2).get(0,group));
        assertFalse(Files.exists(cache.path(book,33)));
    }
    @Test void capacityMaintenanceCanReclaimExpiredEvidence() throws Exception {
        for(int page=1;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,2).put(0,group,body());
        assertTrue(at(instant.plus(Duration.ofDays(8))).open(book,33,input,2).available());
        try(var files=Files.list(cache.path(book,1).getParent())){assertEquals(ComprehensibilityResumeStore.MAX_PAGES,files.count());}
    }
    @Test void invalidGroupBoundsDoNotMutateTheSavedRecord() throws Exception {
        var session=cache.open(book,1,input,1);byte[] before=Files.readAllBytes(cache.path(book,1));
        assertThrows(IllegalArgumentException.class,()->session.put(1,group,body()));
        assertThrows(IllegalArgumentException.class,()->session.put(0,group,"x".repeat(ComprehensibilityResumeStore.MAX_RESULT_CHARS+1)));
        assertArrayEquals(before,Files.readAllBytes(cache.path(book,1)));
    }
    @Test void freshJvmContinuesAForcedReviewAfterWriterHalts() throws Exception {
        assertEquals(23,child("write","SELF_CHECK_FORCED"));
        assertEquals(0,child("read","REUSED:1:SENT:1"));
    }
    private int child(String mode,String marker)throws Exception{
        Path output=temp.toRealPath().resolve("child-"+mode+".log");
        // The parent releases its directory lease before the probe acquires it.
        store.close();
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("java.class.path"),RestartProbe.class.getName(),mode,temp.toRealPath().toString(),book)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertTrue(Files.readString(output).contains(marker),Files.readString(output));return process.exitValue();}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
    public static final class RestartProbe {
        public static void main(String[] args)throws Exception{
            var mapper=new ObjectMapper().findAndRegisterModules();var store=new BookStore(TestConfigs.config(Path.of(args[1]),"",""),mapper);
            var fixture=new AutomaticComprehensibilityTest();var calls=new java.util.concurrent.atomic.AtomicInteger();
            var config=new studio.bookhtml.config.QwenAssistProperties();config.setApiKey("test-key");
            var service=new ParagraphComprehensibilityService(config,mapper,new TraditionalConverter(),request->{
                int count=calls.incrementAndGet();
                if(args[0].equals("write")&&count==2)throw new OcrException("fixture stop");
                return fixture.response(fixture.envelope("{\"findings\":[]}"));
            });
            service.setResumeStore(new ComprehensibilityResumeStore(store,mapper));
            service.setBookContext(new BookContextService(store,mapper));
            var result=service.check(args[2],1,List.of(fixture.block("甲".repeat(3500))),()->false);
            if(args[0].equals("write")){System.out.println("SELF_CHECK_FORCED");System.out.flush();Runtime.getRuntime().halt(23);}
            if(!result.complete()||result.reused()!=1||calls.get()!=1)throw new AssertionError("wrong cross-JVM replay");
            System.out.println("REUSED:"+result.reused()+":SENT:"+calls.get());store.close();
        }
    }

    @Test void capacityCanReclaimFullyRecordedPlansWithoutEvictingPartialWork() throws Exception {
        cache.open(book,1,input,2).put(0,group,body());
        for(int page=2;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,1).put(0,group,body());
        assertTrue(cache.open(book,33,input,1).available(),"completed plans must not permanently block every later page");
        assertEquals(body(),cache.open(book,1,input,2).get(0,group));
    }
    @Test void boundedCapacityInspectionDoesNotStayStuckOnTheSamePartialPrefix() throws Exception {
        for(int page=1;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,2).put(0,group,body());
        cache.open(book,32,input,2).put(1,group,body());
        boolean admitted=false;
        for(int tries=0;tries<8&&!admitted;tries++)admitted=cache.open(book,33,input,1).available();
        assertTrue(admitted,"bounded successive maintenance must eventually visit every candidate");
        assertTrue(Files.exists(cache.path(book,1)),"uncompleted source evidence is retained");
    }

    @Test void successiveStoreHandlesShareBoundedMaintenanceProgress() throws Exception {
        for(int page=1;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,2).put(0,group,body());
        cache.open(book,32,input,2).put(1,group,body());
        boolean admitted=false;
        for(int tries=0;tries<8&&!admitted;tries++)admitted=at(instant).open(book,33,input,1).available();
        assertTrue(admitted,"changing service handles must not reset a full-directory scan");
        for(int page=1;page<32;page++)assertNotNull(cache.read(cache.path(book,page),book,page));
    }
    @Test void oneMaintenancePassReadsAtMostFourCandidateRecords() throws Exception {
        for(int page=1;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,2).put(0,group,body());
        var counting=org.mockito.Mockito.spy(cache);
        assertFalse(counting.open(book,33,input,1).available());
        org.mockito.Mockito.verify(counting,org.mockito.Mockito.times(5)).read(
                org.mockito.ArgumentMatchers.any(Path.class),org.mockito.ArgumentMatchers.eq(book),org.mockito.ArgumentMatchers.anyInt());
        assertFalse(Files.exists(cache.path(book,33)));
    }
    @Test void corruptCompletedRecordIsNotDeletedAsReclaimableEvidence() throws Exception {
        for(int page=1;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,2).put(0,group,body());
        Path bad=cache.path(book,1);String damaged=Files.readString(bad).replace("findings","changed");Files.writeString(bad,damaged);
        cache.open(book,32,input,2).put(1,group,body());
        boolean admitted=false;
        for(int tries=0;tries<8&&!admitted;tries++)admitted=cache.open(book,33,input,1).available();
        assertTrue(admitted);assertEquals(damaged,Files.readString(bad));
        assertFalse(cache.open(book,1,input,2).available());
    }
    @Test void clockRollbackDoesNotEvictFutureCompletedEvidence() throws Exception {
        for(int page=1;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,1).put(0,group,body());
        for(int tries=0;tries<8;tries++)assertFalse(at(instant.minusSeconds(1)).open(book,33,input,1).available());
        try(var files=Files.list(cache.path(book,1).getParent())){assertEquals(ComprehensibilityResumeStore.MAX_PAGES,files.count());}
    }
    @Test void evictedOldWriterCannotReplaceNewEvidenceForTheSamePage() throws Exception {
        var old=cache.open(book,1,input,1);old.put(0,group,body());
        for(int page=2;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,2).put(0,group,body());
        assertTrue(cache.open(book,33,input,1).available());assertFalse(Files.exists(cache.path(book,1)));
        cache.open(book,33,input,1).put(0,group,body());
        ComprehensibilityResumeStore.Session replacement=null;
        for(int tries=0;tries<8;tries++){replacement=cache.open(book,1,"c".repeat(64),1);if(replacement.available())break;}
        assertTrue(replacement.available());replacement.put(0,"d".repeat(64),body());
        old.put(0,group,body());assertFalse(old.available());
        var saved=cache.read(cache.path(book,1),book,1);
        assertEquals("c".repeat(64),saved.inputHash());assertEquals("d".repeat(64),saved.groups().get(0).inputHash());
    }
    @Test void fullDirectoryConcurrentAdmissionDoesNotExceedTheBound() throws Exception {
        for(int page=1;page<=ComprehensibilityResumeStore.MAX_PAGES;page++)cache.open(book,page,input,1).put(0,group,body());
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=executor.submit(()->{start.await();return at(instant).open(book,33,input,1).available();});
            var b=executor.submit(()->{start.await();return at(instant).open(book,34,input,1).available();});
            start.countDown();assertTrue(a.get(10,TimeUnit.SECONDS));assertTrue(b.get(10,TimeUnit.SECONDS));
            try(var files=Files.list(cache.path(book,1).getParent())){assertEquals(ComprehensibilityResumeStore.MAX_PAGES,files.count());}
        } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
