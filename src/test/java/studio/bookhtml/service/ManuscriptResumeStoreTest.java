package studio.bookhtml.service;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManuscriptResumeStoreTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString(),source="a".repeat(64),contract="b".repeat(64),input="c".repeat(64);
    final String result="{\"text\":\"既有转录，未经人工确认\",\"findings\":[]}";
    final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    BookStore store;ManuscriptResumeStore cache;
    @BeforeEach void setup()throws Exception {
        store=new BookStore(TestConfigs.config(data,"",""),json);store.createBookDirectory(book);
        store.writeBook(new Book(book,"fixture","fixture.pdf",65,now,now,0,0));
        store.writePage(book,Page.pending(1,600,800),false);
        cache=new ManuscriptResumeStore(store,json,Clock.fixed(now,ZoneOffset.UTC));
    }
    @AfterEach void close(){store.close();}
    ManuscriptResumeStore.Session start(){return cache.open(book,1,source,contract);}
    Path file(){return cache.path(book,1);}
    @Test void staleWriterCannotOverwriteANewerAttemptRecord()throws Exception {
        var first=start();first.put(0,input,result);var second=start();second.put(1,"d".repeat(64),result);
        byte[] before=Files.readAllBytes(file());first.put(2,"e".repeat(64),result);
        assertFalse(first.available());assertArrayEquals(before,Files.readAllBytes(file()));
        assertNotNull(start().get(1,"d".repeat(64)));assertNull(start().get(2,"e".repeat(64)));
    }
    @Test void changedContractInvalidatesPriorRegionsWithoutCrossReading()throws Exception {
        start().put(0,input,result);var different=cache.open(book,1,source,"f".repeat(64));
        assertNull(different.get(0,input));assertTrue(different.available());
    }
    @Test void hardAgeLimitAndClockRollbackDisableReuse()throws Exception {
        start().put(0,input,result);
        var future=new ManuscriptResumeStore(store,json,Clock.fixed(now.plus(Duration.ofDays(8)),ZoneOffset.UTC));
        assertNull(future.open(book,1,source,contract).get(0,input));
        future.open(book,1,source,contract).put(0,input,result);
        assertNull(start().get(0,input),"future-dated evidence cannot be adopted after clock rollback");
    }
    @Test void wellFormedJsonTamperingIsDetectedByIntegrityHash()throws Exception {
        start().put(0,input,result);String changed=Files.readString(file()).replace("既有转录","擅自替换");Files.writeString(file(),changed);
        var next=start();assertFalse(next.available());assertNull(next.get(0,input));assertEquals(changed,Files.readString(file()));
    }
    @Test void duplicatesWrongTypesAndTrailingJsonAreLeftUntouched()throws Exception {
        start().put(0,input,result);String original=Files.readString(file());
        List<String> invalid=List.of(original+"{}",original.replace("\"schemaVersion\":1","\"schemaVersion\":1.5"),
                original.substring(0,original.length()-1)+",\"schemaVersion\":1}",original.replace("\"pageNumber\":1","\"pageNumber\":\"1\""));
        for(String text:invalid){Files.writeString(file(),text);assertFalse(start().available());assertEquals(text,Files.readString(file()));}
    }
    @Test void overlongOptionalFileIsNotReadAsAValidCheckpoint()throws Exception {
        start();byte[] bytes=new byte[ManuscriptResumeStore.MAX_BYTES+1];Arrays.fill(bytes,(byte)' ');Files.write(file(),bytes);
        assertFalse(start().available());assertArrayEquals(bytes,Files.readAllBytes(file()));
    }
    @Test void cachedResponseAndRegionCountHaveIndependentBounds()throws Exception {
        var session=start();assertThrows(IllegalArgumentException.class,()->session.put(6,input,result));
        assertThrows(IllegalArgumentException.class,()->session.put(0,input,"x".repeat(ManuscriptResumeStore.MAX_RESPONSE_CHARS+1)));
        assertThrows(IllegalArgumentException.class,()->session.put(0,"not-a-hash",result));
        assertTrue(cache.read(file(),book,1).regions().isEmpty());
    }
    @Test void optionalFileSymlinkCannotReadOrModifyItsTarget()throws Exception {
        Path target=data.toRealPath().resolve("private-target");Files.writeString(target,"private synthetic data");
        Files.createDirectories(file().getParent());Files.createSymbolicLink(file(),target);
        assertFalse(start().available());assertEquals("private synthetic data",Files.readString(target));assertTrue(Files.isSymbolicLink(file()));
    }
    @Test void copiedSnapshotCannotBecomeEvidenceForAnotherBook()throws Exception {
        start().put(0,input,result);String another=UUID.randomUUID().toString();store.createBookDirectory(another);store.writePage(another,Page.pending(1,600,800),false);
        Path other=cache.path(another,1);Files.createDirectories(other.getParent());Files.copy(file(),other);
        var session=cache.open(another,1,source,contract);assertFalse(session.available());assertNull(session.get(0,input));
    }
    @Test void boundedDiskAdmissionDoesNotEvictFreshUnfinishedEvidence()throws Exception {
        for(int page=1;page<=65;page++) {
            if(page>1)store.writePage(book,Page.pending(page,600,800),false);
            var session=cache.open(book,page,source,contract);
            if(page<=64){assertTrue(session.available());session.put(0,input,result);}else assertFalse(session.available());
        }
        try(var files=Files.list(file().getParent())){assertEquals(64,files.count());}
        assertEquals(result,start().get(0,input));
    }
    @Test void onlyVerifiedExpiredRecordsMakeRoomAtCapacity()throws Exception {
        for(int page=1;page<=65;page++) {
            if(page>1)store.writePage(book,Page.pending(page,600,800),false);
            if(page<=64)cache.open(book,page,source,contract).put(0,input,result);
        }
        var future=new ManuscriptResumeStore(store,json,Clock.fixed(now.plus(Duration.ofDays(8)),ZoneOffset.UTC));
        assertTrue(future.open(book,65,source,contract).available());
        try(var files=Files.list(file().getParent())){assertEquals(64,files.count());}
    }

    @Test void newJvmCanResumeForcedRegionEvidenceAfterWriterHalts()throws Exception {
        store.close();
        try {
            assertEquals(23,child("write-halt","REGION_DURABLE"));
            assertEquals(0,child("read","REGION_RECOVERED"));
        } finally {store=new BookStore(TestConfigs.config(data,"",""),json);}
        assertEquals(result,start().get(0,input));
    }
    int child(String mode,String expected)throws Exception {
        Path output=data.resolve("child-"+mode+".log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),
                RestartProbe.class.getName(),mode,data.toString(),book,result).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().keySet().removeIf(k->!Set.of("PATH","HOME","TMPDIR","LANG","SystemRoot","WINDIR").contains(k));
        Process process=builder.start();
        try {assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));assertTrue(Files.readString(output).contains(expected));return process.exitValue();}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
    public static final class RestartProbe {
        public static void main(String[] args)throws Exception {
            var json=new ObjectMapper().findAndRegisterModules();var store=new BookStore(TestConfigs.config(Path.of(args[1]),"",""),json);
            var cache=new ManuscriptResumeStore(store,json,Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.UTC));
            var session=cache.open(args[2],1,"a".repeat(64),"b".repeat(64));
            if(args[0].equals("write-halt")) {
                session.put(0,"c".repeat(64),args[3]);
                if(!session.available())throw new IllegalStateException("cache unavailable");
                System.out.println("REGION_DURABLE");System.out.flush();Runtime.getRuntime().halt(23);
            }
            if(!args[3].equals(session.get(0,"c".repeat(64))))throw new IllegalStateException("region missing");
            System.out.println("REGION_RECOVERED");store.close();
        }
    }
}
