package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReaderAuditRegressionTest {
    @Test void parallelCountersAreAtomicAndLatestMeansNewestAttempt() throws Exception {
        var progress=new ProcessingProgressService();var id=progress.begin("book",1,0);
        progress.plan("book",1,id,"REVIEW_CHUNK",1000);
        var pool=Executors.newFixedThreadPool(8);
        for(int n=0;n<1000;n++)pool.submit(()->progress.unitDone("book",1,id,true));
        pool.shutdown();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));
        assertEquals(1000,progress.latest("book",1).units().succeeded());
        var newer=progress.begin("book",1,2);
        assertEquals(newer,progress.latest("book",1).attemptId());
        progress.finish("book",1,newer,"FAILED","FAILED",true);
        progress.stage("book",1,newer,"PUBLISHING");
        progress.finish("book",1,newer,"SUCCEEDED","LATE",false);
        assertEquals("FAILED",progress.latest("book",1).lifecycle());
        assertNull(progress.latest("different-book",1));
    }

    @Test void percentIsMonotoneAndOneHundredRequiresCommittedSuccess() {
        var p=new ProcessingProgressService();var id=p.begin("b",1,4);
        assertEquals(0,p.latest("b",1).percent());p.stage("b",1,id,"PUBLISHING");
        assertEquals(25,p.latest("b",1).percent());p.baselinePublished("b",1,id,6,false);
        assertEquals(50,p.latest("b",1).percent());p.stage("b",1,id,"STRUCTURE");
        assertEquals(50,p.latest("b",1).percent());p.plan("b",1,id,"REVIEW_CHUNK",4);
        p.unitDone("b",1,id,true);assertEquals(56,p.latest("b",1).percent());
        p.stage("b",1,id,"VALIDATING");assertEquals(75,p.latest("b",1).percent());
        p.finish("b",1,id,"PARTIAL","NOT_ALL_REVIEWED",true);
        assertTrue(p.latest("b",1).percent()<100);assertEquals(6,p.latest("b",1).publishedRevision());
    }

    @Test void refreshDoesNotCreatePermitsWhileOldRequestsStillRun() throws Exception {
        var config=new QwenAssistProperties();var gate=new QwenRequestGate(config);
        var a=gate.acquire(true,Duration.ZERO);var b=gate.acquire(true,Duration.ZERO);var c=gate.acquire(true,Duration.ZERO);
        config.setMaxConcurrentRequests(1);gate.refresh();
        assertNull(gate.acquire(true,Duration.ZERO));a.close();b.close();
        assertNull(gate.acquire(true,Duration.ZERO));c.close();
        try(var permit=gate.acquire(true,Duration.ZERO)){assertNotNull(permit);assertEquals(1,gate.inFlight());}
        assertEquals(0,gate.inFlight());
    }

    @Test void concurrentCloseIsExactlyOnceAndBudgetRejectsNegativeValues() throws Exception {
        var gate=new QwenRequestGate(new QwenAssistProperties());var permit=gate.acquire(true,Duration.ZERO);
        var pool=Executors.newFixedThreadPool(4);for(int i=0;i<100;i++)pool.submit(permit::close);
        pool.shutdown();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));assertEquals(0,gate.inFlight());
        assertThrows(IllegalArgumentException.class,()->gate.newBudget().reserve(-1));
    }

    @Test void contextIsSameBookBoundedAndRevisionAware() throws Exception {
        BookStore store=mock(BookStore.class);String id="book-a";
        when(store.readBook(id)).thenReturn(new Book(id,"古籍研习","a.pdf",10000,Instant.now(),Instant.now(),0,0));
        Block block=new Block("b1","heading",0,new double[]{.1,.2,.8,.1},"horizontal-tb","章节甲","章节甲",.99,true,false,2,"paddle",List.of(),null,null);
        when(store.readPage(eq(id),anyInt())).thenAnswer(inv->new Page(inv.getArgument(1),600,800,"READY","paddle",List.of(block),List.of(),false,null,List.of(block),2));
        var service=new BookContextService(store,new ObjectMapper());
        String first=service.forPage(id,1000);var parsed=new ObjectMapper().readTree(first);
        assertEquals(id,parsed.path("bookId").asText());assertEquals("章节甲",parsed.path("chapterCandidate").asText());
        assertFalse(parsed.path("chapterVerified").asBoolean());assertTrue(first.length()<16000);
        verify(store,atMost(17)).readPage(eq(id),anyInt());
        service.forPage(id,1000);verify(store,atMost(17)).readPage(eq(id),anyInt());
        assertFalse(first.contains("book-b"));
    }

    @Test void priorityMarksNeighbourCallsBackground() {
        var priority=new ReadingPriorityService();priority.focus("b",8);
        try(var scope=UsageContext.open("b",7,"test")){assertFalse(priority.foreground(true));}
        try(var scope=UsageContext.open("b",8,"test")){assertTrue(priority.foreground(true));}
        priority.clear("b");assertTrue(priority.foreground(true));
    }
    @Test void firstPaintMetadataAndProjectionDoNotWaitForFullBookScan() throws Exception {
        BookStore store=mock(BookStore.class);
        Book book=new Book("large", "大书", "large.pdf", 10000, Instant.now(), Instant.now(), 0, 0);
        when(store.readBook("large")).thenReturn(book); when(store.listBooks()).thenReturn(List.of(book));
        var books=new BookService(store, mock(PdfService.class), null);
        assertEquals(book, books.readingMetadata("large")); assertEquals(List.of(book), books.readingLibrary());
        verify(store, never()).readPage(anyString(), anyInt());
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(store.readPage(eq("large"),anyInt())).thenAnswer(call -> {
            entered.countDown();
            try {release.await(2, TimeUnit.SECONDS);} catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();throw new CancelledException();
            }
            return null;
        });
        var presentation=new BookPresentationService(store);
        try {
            assertTimeoutPreemptively(Duration.ofMillis(700), () ->
                assertEquals(0, presentation.profileForReading("large").profileRevision()));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(700), () ->
                assertEquals(0, presentation.profileForReading("large").profileRevision()));
            verify(store, times(1)).readPage(eq("large"), anyInt());
        } finally { presentation.close();release.countDown(); }
    }

}
