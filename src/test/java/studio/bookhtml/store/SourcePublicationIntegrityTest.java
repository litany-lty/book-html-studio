package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourcePublicationIntegrityTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString();
    BookStore store;
    @BeforeEach void setup() throws Exception {
        store=new BookStore(SourceChangeRecoveryTest.config(temp.toRealPath()),json);
        store.createBookDirectory(book);
        store.writeBook(new Book(book,"fixture","fixture.pdf",2,Instant.now(),Instant.now(),0,0));
        store.writePage(book,Page.pending(1,600,800),false);
    }
    @AfterEach void close() { store.close(); }
    Page page(String text) {
        var b=new Block("b1","text",0,new double[]{.1,.1,.8,.1},"horizontal-tb",text,text,.99,false,false,null,"test",List.of("b1"),null,null);
        return new Page(1,600,800,"READY","test",List.of(b),List.of(),false,null,List.of(b));
    }
    @Test void onePublicationHasOneSourceSequenceAcrossIntentCompletionAndHead() throws Exception {
        Page saved=store.commitPage(book,page("published"),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        var events=store.sourceJournal().readAll(store.bookDir(book),book);
        assertEquals(2,events.size());
        assertEquals(events.get(0).sourceSeq(),events.get(1).sourceSeq());
        assertEquals(events.get(0).sourceSeq(),store.headStore().readHead(store.bookDir(book),1).appliedSourceSeq());
        assertEquals(saved.lastCommitId(),events.get(1).commitId());
    }
    @Test void unreadableHistoryNeverResetsSourceSequenceToZero() throws Exception {
        Path dir=SourceChangeJournal.eventsDir(store.bookDir(book));Files.createDirectory(dir);
        Path wal=dir.resolve("segment-000001.wal");Files.writeString(wal,"damaged header");byte[] before=Files.readAllBytes(wal);
        assertThrows(Exception.class,()->store.sourceJournal().currentSourceSeq(store.bookDir(book),book));
        assertThrows(Exception.class,()->store.sourceJournal().nextSourceSeq(store.bookDir(book),book));
        assertArrayEquals(before,Files.readAllBytes(wal));
    }
    @Test void laterRevisionDoesNotProveAnUnrelatedOldIntentWasPublished() throws Exception {
        Page saved=store.commitPage(book,page("first"),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        var j=store.sourceJournal();
        var unknown=j.prepare(store.bookDir(book),book,"PAGE",1,UUID.randomUUID(),null,0,1,"0".repeat(64),"f".repeat(64),"TEST_UNKNOWN");
        store.commitPage(book,page("second"),1,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        j.reconcile(store.bookDir(book),book,store);
        var last=j.readAll(store.bookDir(book),book).stream().filter(e->e.sourceSeq()==unknown.sourceSeq()).reduce((a,b)->b).orElseThrow();
        assertEquals("UNKNOWN",last.state());
        assertEquals("second",store.readPage(book,1).blocks().get(0).original());
    }
    @Test void matchingRevisionAndHashWithDifferentCommitIdDoesNotProvePublication() throws Exception {
        Page saved=store.commitPage(book,page("first"),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        var j=store.sourceJournal();
        var unknown=j.prepare(store.bookDir(book),book,"PAGE",1,UUID.randomUUID(),null,0,1,"0".repeat(64),store.pageContentHash(saved),"TEST_UNKNOWN");
        j.reconcile(store.bookDir(book),book,store);
        var last=j.readAll(store.bookDir(book),book).stream().filter(e->e.sourceSeq()==unknown.sourceSeq()).reduce((a,b)->b).orElseThrow();
        assertEquals("UNKNOWN",last.state());
    }

    @Test void secondStoreHandleDoesNotReuseASequenceCachedBeforeAnotherCommit() throws Exception {
        var first=store.sourceJournal();assertEquals(0,first.currentSourceSeq(store.bookDir(book),book));
        var another=new BookStore(SourceChangeRecoveryTest.config(temp.toRealPath()),json);
        try {
            another.commitPage(book,page("one"),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
            store.commitPage(book,page("two"),1,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        } finally { another.close(); }
        var prepared=first.readAll(store.bookDir(book),book).stream().filter(e->"PREPARED".equals(e.state())).toList();
        assertEquals(2,prepared.size());assertTrue(prepared.get(1).sourceSeq()>prepared.get(0).sourceSeq());
    }
    @Test void wrongSettlementCannotCloseAnUnrelatedIntent() throws Exception {
        var j=store.sourceJournal();var p=j.prepare(store.bookDir(book),book,"PAGE",1,UUID.randomUUID(),null,0,1,"a","b","TEST");
        assertThrows(IOException.class,()->j.commit(store.bookDir(book),book,p.sourceSeq(),1,UUID.randomUUID(),1,"b"));
        assertTrue(j.hasUnresolved(store.bookDir(book),book));
    }
    @Test void completeCorruptTailIsPreservedRatherThanTruncatedAway() throws Exception {
        Path path=temp.toRealPath().resolve("tail.wal");var wal=new DurableEventJournal();
        wal.initSegment(path,book,UUID.randomUUID(),1,"0".repeat(64));wal.append(path,1,"valid-payload".getBytes());
        byte[] bytes=Files.readAllBytes(path);bytes[bytes.length-1]^=1;Files.write(path,bytes);
        assertThrows(IOException.class,()->wal.readSegment(path,book,true,new ArrayList<>()));
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }
    @Test void completeWarmJournalCorruptionIsNotHiddenByCachedWatermark() throws Exception {
        store.commitPage(book,page("one"),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        assertTrue(store.sourceJournal().currentSourceSeq(store.bookDir(book),book)>0);
        Path path=SourceChangeJournal.eventsDir(store.bookDir(book)).resolve("segment-000001.wal");
        byte[] bytes=Files.readAllBytes(path);bytes[bytes.length-1]^=1;Files.write(path,bytes);
        assertThrows(Exception.class,()->store.sourceJournal().currentSourceSeq(store.bookDir(book),book));
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }
}
