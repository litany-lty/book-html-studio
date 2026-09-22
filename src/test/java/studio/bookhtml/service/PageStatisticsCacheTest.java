package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.PageUpdateRequest;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageStatisticsCacheTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String id=UUID.randomUUID().toString();
    BookStore store;
    Book book;
    @AfterEach void close() { BookStore.clearIoFailure(); if(store!=null)store.close(); }
    BookService setup(int pages) throws Exception {
        var config=TestConfigs.config(data,"","");
        store=spy(new BookStore(config,json));
        store.createBookDirectory(id);
        book=new Book(id,"合成计数书","fixture.pdf",pages,Instant.now(),Instant.now(),0,0);
        store.writeBook(book);
        for(int n=1;n<=pages;n++)store.writePage(id,Page.pending(n,600,800),false);
        return new BookService(store,mock(PdfService.class),config);
    }
    Block block(String text) {
        return new Block("b1","text",0,new double[]{.1,.1,.8,.1},"horizontal-tb",text,text,.99,false,false,
                null,"paddle",List.of("b1"),null,null);
    }
    Page ready(int n,String text,boolean reviewed) {
        return new Page(n,600,800,"READY","paddle-aistudio",List.of(block(text)),List.of(),reviewed,null,List.of(block(text)));
    }

    @Test void savingAndRefreshingWarmFiveThousandPageBookDoesNotRescan() throws Exception {
        BookService service=setup(5000);
        long start=System.nanoTime();
        assertEquals(0,service.get(id).processedPages());
        long coldNanos=System.nanoTime()-start;
        clearInvocations(store);
        start=System.nanoTime();
        Page saved=service.update(id,2500,new PageUpdateRequest(List.of(block("人工校对正文")),true,0));
        Book refreshed=service.get(id);
        long saveNanos=System.nanoTime()-start;
        assertEquals(1,saved.revision());
        assertEquals(1,refreshed.processedPages());
        assertEquals(1,refreshed.reviewedPages());
        verify(store,times(1)).readPage(anyString(),anyInt());
        Path out=Path.of("test-results","statistics-scale.json");
        Files.createDirectories(out.getParent());
        json.writeValue(out.toFile(),Map.of("fixture","5000 synthetic page JSON files; not PDF/OCR load testing",
                "pages",5000,"coldScanMillis",coldNanos/1_000_000.0,"saveAndRefreshMillis",saveNanos/1_000_000.0,
                "postWarmPageReads",1));
    }

    @Test void coldSaveAcknowledgementDoesNotRequireWholeBookStatistics() throws Exception {
        BookService service=setup(100);
        clearInvocations(store);
        assertEquals(1,service.update(id,20,new PageUpdateRequest(List.of(block("正文")),false,0)).revision());
        verify(store,times(1)).readPage(anyString(),anyInt());
        assertEquals(1,service.get(id).processedPages());
    }

    @Test void metadataFailureAfterSaveReturnsActualSavedRevision() throws Exception {
        BookService service=setup(2);
        BookStore.failNextIoAt("book");
        Page saved=service.update(id,1,new PageUpdateRequest(List.of(block("已持久化正文")),true,0));
        assertEquals(1,saved.revision());
        assertEquals(json.valueToTree(saved),json.valueToTree(store.readPage(id,1)));
        BookStore.clearIoFailure();
        assertEquals(1,service.get(id).reviewedPages());
    }

    @Test void metadataFailureAfterRevertDoesNotLieAboutRevert() throws Exception {
        BookService service=setup(1);
        store.writePage(id,ready(1,"旧正文",false),false);
        int oldRevision=store.readPage(id,1).revision();
        Page edited=service.update(id,1,new PageUpdateRequest(List.of(block("新正文")),true,oldRevision));
        assertEquals(1,service.get(id).reviewedPages());
        BookStore.failNextIoAt("book");
        Page restored=service.revert(id,1,oldRevision,edited.revision());
        assertEquals(edited.revision()+1,restored.revision());
        assertEquals("旧正文",restored.blocks().get(0).original());
        assertEquals(json.valueToTree(restored),json.valueToTree(store.readPage(id,1)));
        BookStore.clearIoFailure();
        assertEquals(0,service.get(id).reviewedPages());
    }

    @Test void staleColdScanCannotReinsertCountsAfterPublication() throws Exception {
        setup(2);
        var cache=new PageStatisticsCache(store);
        var readOld=new CountDownLatch(1); var release=new CountDownLatch(1);
        var first=new AtomicBoolean(true);
        doAnswer(inv->{
            Page old=(Page)inv.callRealMethod();
            if(first.compareAndSet(true,false)) { readOld.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); }
            return old;
        }).when(store).readPage(id,1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var scan=pool.submit(()->cache.counts(book));
            assertTrue(readOld.await(5,TimeUnit.SECONDS));
            store.writePage(id,ready(1,"新正文",true),false);
            release.countDown(); scan.get(5,TimeUnit.SECONDS);
            assertNull(cache.cached(book),"overlapping build is not cacheable");
            assertEquals(new PageStatisticsCache.Counts(1,1),cache.counts(book));
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void scanDuringPublicationCannotCacheAnOddEpoch() throws Exception {
        setup(1);
        var renamed=new CountDownLatch(1);var release=new CountDownLatch(1);
        store.addPageChangeListener(event->{
            renamed.countDown();
            try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        });
        var cache=new PageStatisticsCache(store);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var writer=pool.submit(()->{store.writePage(id,ready(1,"正文",false),false);return null;});
            assertTrue(renamed.await(5,TimeUnit.SECONDS));
            assertEquals(1,store.pageEpoch(id)&1);
            assertEquals(1,cache.counts(book).processed());
            assertNull(cache.cached(book));
            release.countDown();writer.get(5,TimeUnit.SECONDS);
            assertEquals(0,store.pageEpoch(id)&1);
            assertEquals(1,cache.counts(book).processed());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void cacheIsBoundedAndSeparateBooksDoNotInvalidateEachOther() throws Exception {
        BookService service=setup(1);
        assertEquals(0,service.get(id).processedPages());
        String other=UUID.randomUUID().toString();
        store.createBookDirectory(other);
        store.writeBook(new Book(other,"other","f.pdf",1,Instant.now(),Instant.now(),0,0));
        store.writePage(other,Page.pending(1,600,800),false);
        clearInvocations(store);
        assertEquals(0,service.get(id).processedPages());
        verify(store,never()).readPage(id,1);
        BookStore fake=mock(BookStore.class);
        when(fake.readPage(anyString(),eq(1))).thenReturn(Page.pending(1,600,800));
        var cache=new PageStatisticsCache(fake);
        for(int n=0;n<100;n++) cache.counts(new Book(UUID.randomUUID().toString(),"t","f.pdf",1,Instant.now(),Instant.now(),0,0));
        assertEquals(64,cache.size());
    }
}
