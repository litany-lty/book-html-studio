package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndexReadIntegrityTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString();
    BookStore store; BookIndexService index; BookService books;
    @BeforeEach void setup() throws Exception {
        var config=PersistentBookIndexTest.config(temp.toRealPath());store=new BookStore(config,json);
        store.createBookDirectory(book);store.writeBook(new Book(book,"fixture","f.pdf",2,Instant.now(),Instant.now(),0,0));
        store.writePage(book,page(1,"old keyword"),false);store.writePage(book,page(2,"other"),false);
        index=store.indexService();books=new BookService(store,mock(PdfService.class),config);
    }
    @AfterEach void close() { store.close(); }
    Page page(int number,String text) {
        var b=new Block("b"+number,"text",0,new double[]{.1,.1,.8,.1},"horizontal-tb",text,text,.99,false,false,null,"test",List.of("b"+number),null,null);
        return new Page(number,600,800,"READY","test",List.of(b),List.of(),false,null,List.of(b));
    }
    BookIndexManifest build() throws Exception { return index.buildOrRebuild(store.bookDir(book),book,"source-fixture",2,store.sourceJournal(),p->store.readPage(book,p)).get(10,TimeUnit.SECONDS); }
    @Test void savedPageCannotBeReplacedByStaleNonemptySearchHits() throws Exception {
        build();store.commitPage(book,page(1,"new keyword"),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        assertTrue(books.search(book,"old keyword").isEmpty());
        assertEquals("new keyword",books.search(book,"new keyword").get(0).get("text"));
    }
    @Test void keywordBeyondFiftiethCharacterIsStillIndexed() throws Exception {
        store.commitPage(book,page(1,"甲".repeat(90)+"独特终章"),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        build();assertFalse(index.search(store.bookDir(book),book,"独特终章",null,500).isEmpty());
    }
    @Test void manifestGenerationCannotEscapeItsDirectory() throws Exception {
        build();Path path=BookIndexService.manifestPath(store.bookDir(book));
        var object=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(path));
        object.put("generationId","../../outside");Files.write(path,json.writeValueAsBytes(object));
        assertNull(index.manifest(store.bookDir(book)));
    }
    @Test void malformedManifestAndCrossBookIdentityAreRejected() throws Exception {
        build();Path path=BookIndexService.manifestPath(store.bookDir(book));
        String original=Files.readString(path);
        Files.writeString(path,original.substring(0,original.length()-1)+","+(char)34+"schemaVersion"+(char)34+":2}");
        assertNull(index.manifest(store.bookDir(book)));
        var object=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original);object.put("bookId",UUID.randomUUID().toString());
        Files.write(path,json.writeValueAsBytes(object));assertNull(index.manifest(store.bookDir(book)));
    }
    @Test void missingPageAbortsRatherThanPublishingMisalignedShards() throws Exception {
        assertThrows(Exception.class,()->index.buildOrRebuild(store.bookDir(book),book,"source-fixture",130,
                store.sourceJournal(),p->p==2?null:page(p,"body")).get(10,TimeUnit.SECONDS));
        assertNull(index.manifest(store.bookDir(book)));
    }
    @Test void requestBoundsAreValidatedBeforeAllocation() {
        assertThrows(IllegalArgumentException.class,()->index.pageSummaries(store.bookDir(book),1,Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->index.pageSummaries(store.bookDir(book),0,1));
        assertThrows(IllegalArgumentException.class,()->index.search(store.bookDir(book),book,"x",null,-1));
    }
    @Test void realNegativeSearchDoesNotFallBackToWholeBookScan() throws Exception {
        build();BookStore spy=spy(store);
        BookService reader=new BookService(spy,mock(PdfService.class),PersistentBookIndexTest.config(temp.toAbsolutePath()));
        assertTrue(reader.search(book,"absent").isEmpty());
        verify(spy,never()).readPage(anyString(),anyInt());
    }

    @Test void traditionalAndSupplementaryCharactersSearchTheSameSourceBlock() throws Exception {
        Page base=page(1,"漢字𠮷");Block b=base.blocks().get(0);
        var changed=new Block(b.id(),b.type(),b.order(),b.bbox(),b.writingMode(),"漢字𠮷","汉字𠮷",.99,false,false,null,"test",b.sourceIds(),null,null);
        store.commitPage(book,new Page(1,600,800,"READY","test",List.of(changed),List.of(),false,null,List.of(b)),0,CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
        build();
        assertEquals(1,index.search(store.bookDir(book),book,"漢字",null,500).size());
        assertEquals(1,index.search(store.bookDir(book),book,"汉字",null,500).size());
        assertEquals(1,index.search(store.bookDir(book),book,"𠮷",null,500).size());
    }
    @Test void missingPartitionIsUnavailableNotAnEmptyAnswer() throws Exception {
        var m=build();String anchor="old";int part=(anchor.hashCode()&0x7fffffff)%256;
        Files.delete(BookIndexService.termPartPath(BookIndexService.generationDir(store.bookDir(book),m.generationId()),part));
        assertNull(index.search(store.bookDir(book),book,"old",null,500));
        assertEquals(1,books.search(book,"old").size());
    }
    @Test void coldStatisticsUseValidatedPersistedCountsWithoutOpeningPageJson() throws Exception {
        build();BookStore reader=spy(store);
        var service=new BookService(reader,mock(PdfService.class),PersistentBookIndexTest.config(temp));
        assertEquals(2,service.get(book).processedPages());
        verify(reader,never()).readPage(anyString(),anyInt());
    }
    @Test void preparedSourceChangePreventsReadyGeneration() throws Exception {
        var j=store.sourceJournal();Page old=store.readPage(book,1);
        j.prepare(store.bookDir(book),book,"PAGE",1,UUID.randomUUID(),null,0,1,store.pageContentHash(old),"f".repeat(64),"UNSETTLED");
        assertThrows(Exception.class,this::build);
        assertNull(index.manifest(store.bookDir(book)));
    }
    @Test void failedBuildDoesNotPoisonSingleFlightRegistration() throws Exception {
        assertThrows(Exception.class,()->index.buildOrRebuild(store.bookDir(book),book,"source-fixture",2,store.sourceJournal(),p->{throw new IllegalStateException("fixture");}).get(5,TimeUnit.SECONDS));
        assertNotNull(build());
    }
    @Test void overlappingGenerationPinsAreReferenceCounted() throws Exception {
        var m=build();index.pinGeneration(book,m.generationId());index.pinGeneration(book,m.generationId());
        index.unpinGeneration(book,m.generationId());assertTrue(index.isPinned(book,m.generationId()));
        build();assertTrue(Files.isDirectory(BookIndexService.generationDir(store.bookDir(book),m.generationId())));
        index.unpinGeneration(book,m.generationId());assertFalse(index.isPinned(book,m.generationId()));
    }

    @Test void syntacticallyValidRecordCorruptionIsDetectedBeforeReturningText() throws Exception {
        var m=build();Path path=BookIndexService.pageRecordPath(BookIndexService.generationDir(store.bookDir(book),m.generationId()),1);
        Files.writeString(path,Files.readString(path).replace("old keyword","old corrupted keyword"));
        assertNull(index.search(store.bookDir(book),book,"old",null,500));
        assertEquals("old keyword",books.search(book,"old").get(0).get("text"));
    }
    @Test void validJsonCounterTamperingDoesNotBecomePersistedStatistics() throws Exception {
        build();Path path=BookIndexService.manifestPath(store.bookDir(book));
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(path));
        value.put("processedPages",0);Files.write(path,json.writeValueAsBytes(value));
        assertNull(index.manifest(store.bookDir(book)));
        assertEquals(2,books.get(book).processedPages());
    }

    @Test void anotherIndexHandleCannotDeleteAStillPinnedGeneration() throws Exception {
        var first=build();index.pinGeneration(book,first.generationId());
        BookIndexService another=new BookIndexService(json);
        try {
            another.buildOrRebuild(store.bookDir(book),book,"source-fixture",2,store.sourceJournal(),p->store.readPage(book,p)).get(10,TimeUnit.SECONDS);
            assertTrue(Files.isDirectory(BookIndexService.generationDir(store.bookDir(book),first.generationId())));
            assertTrue(another.isPinned(book,first.generationId()));
        } finally { index.unpinGeneration(book,first.generationId());another.close(); }
    }
    @Test void olderBuildCleanupCannotDeleteTheNewerPublishedGeneration() throws Exception {
        var first=build();var latest=build();
        var cleanup=BookIndexService.class.getDeclaredMethod("cleanupOldGenerations",Path.class,String.class,String.class);
        cleanup.setAccessible(true);cleanup.invoke(index,store.bookDir(book),book,first.generationId());
        assertTrue(Files.isDirectory(BookIndexService.generationDir(store.bookDir(book),latest.generationId())));
        assertEquals(latest.generationId(),index.manifest(store.bookDir(book)).generationId());
    }
}
