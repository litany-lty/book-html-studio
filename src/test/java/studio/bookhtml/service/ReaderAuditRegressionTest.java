package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReaderAuditRegressionTest {
    @Test void newAttemptWinsEvenAfterOldAttemptEmitsManyEvents() {
        var service = new ProcessingProgressService();
        UUID old = service.begin("book", 1, 0);
        service.plan("book", 1, old, "REVIEW_CHUNK", 100);
        for (int i=0;i<100;i++) service.unitDone("book",1,old,true);
        service.finish("book",1,old,"SUCCEEDED","DONE",false);
        UUID fresh=service.begin("book",1,1);
        assertEquals(fresh,service.latest("book",1).attemptId());
        service.stage("book",1,old,"OCR");
        assertEquals("SUCCEEDED",service.snapshot("book",1,old).lifecycle());
        assertEquals(fresh,service.latest("book",1).attemptId());
    }
    @Test void unfinishedWorkCannotBeReportedAsComplete() {
        var service = new ProcessingProgressService();
        UUID attempt = service.begin("book", 1, 0);
        service.plan("book", 1, attempt, "REVIEW_CHUNK", 4);
        service.unitDone("book", 1, attempt, true);
        service.baselinePublished("book", 1, attempt, 2, false);
        service.finish("book", 1, attempt, "SUCCEEDED", "ENHANCED", false);
        var result = service.latest("book", 1);
        assertEquals("PARTIAL", result.lifecycle());
        assertEquals("WORK_PLAN_INCOMPLETE", result.messageCode());
        assertTrue(result.canRead());
        assertTrue(result.canRetry());
    }
    @Test void concurrentUnitsAreAtomicClampedAndScopeSafe() throws Exception {
        var service = new ProcessingProgressService();
        UUID attempt=service.begin("book",1,0);
        service.plan("book",1,attempt,"REVIEW_CHUNK",50);
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<100;i++) futures.add(pool.submit(()->service.unitDone("book",1,attempt,true)));
            for(Future<?> f:futures) f.get(3,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(50,service.latest("book",1).units().succeeded());
        service.stage("other-book",1,attempt,"PUBLISHING");
        assertNotEquals("PUBLISHING",service.latest("book",1).stage());
        assertThrows(IllegalArgumentException.class,()->service.plan("book",1,attempt,"REVIEW_CHUNK",-1));
        service.finish("book",1,attempt,"FAILED","FAILED",true);
        service.finish("book",1,attempt,"SUCCEEDED","LATE",false);
        assertEquals("FAILED",service.latest("book",1).lifecycle());
    }
    @Test void refreshCannotMintSlotsWhileCallsAreInFlight() throws Exception {
        QwenAssistProperties config=new QwenAssistProperties();config.setMaxConcurrentRequests(3);
        QwenRequestGate gate=new QwenRequestGate(config);
        var a=gate.acquire(true,Duration.ofMillis(20));
        var b=gate.acquire(true,Duration.ofMillis(20));
        var c=gate.acquire(true,Duration.ofMillis(20));
        gate.refresh();
        assertNull(gate.acquire(true,Duration.ofMillis(20)));
        config.setMaxConcurrentRequests(1);gate.refresh();
        a.close();b.close();
        assertNull(gate.acquire(true,Duration.ofMillis(20)),"downsizing waits for physical drain");
        c.close();c.close();
        var only=gate.acquire(true,Duration.ofMillis(20));assertNotNull(only);
        assertNull(gate.acquire(true,Duration.ofMillis(20)));
        only.close();assertEquals(0,gate.inFlight());
    }
    @Test void coldReadingProfileDoesNotScanAWholeBook() {
        BookStore store=mock(BookStore.class);
        BookPresentationService presentation=new BookPresentationService(store);
        assertEquals(0,presentation.readingProfile("book").observedPages());
        verify(store,never()).readPage(anyString(),anyInt());
        verify(store,never()).readBook(anyString());
    }
    @Test void bookContextIsBoundedIsolatedOriginalOnlyAndInvalidated() throws Exception {
        BookStore store=mock(BookStore.class);
        AtomicReference<Consumer<String>> changed=new AtomicReference<>();
        doAnswer(inv->{changed.set(inv.getArgument(0));return null;}).when(store).addChangeListener(any());
        Book book=new Book("book","星曜研习","book.pdf",1000,Instant.now(),Instant.now(),0,0);
        when(store.readBook("book")).thenReturn(book);
        Block heading=new Block("h","heading",0,new double[]{.1,.4,.8,.1},"horizontal-tb","第二章 星曜","wrong simplified",.95,false,false,1,"ocr",List.of("h"),"DO_NOT_USE_CANDIDATE",null);
        Block manual=new Block("m","text",1,new double[]{.1,.6,.8,.1},"horizontal-tb","已核原文","wrong simplified",null,false,true,null,"manual",List.of("m"),"DO_NOT_USE_CANDIDATE",null);
        Block unknown=new Block("u","text",2,new double[]{.1,.7,.8,.1},"horizontal-tb","UNKNOWN_NULL_CONFIDENCE",null,null,false,false,null,"ocr",List.of("u"),null,null);
        when(store.readPage("book",499)).thenReturn(new Page(499,600,800,"READY","ocr",List.of(heading,manual,unknown),List.of(),false,null));
        BookContextService service=new BookContextService(store,new ObjectMapper());
        String first=service.forPage("book",500);
        assertTrue(first.contains("第二章 星曜"));assertTrue(first.contains("已核原文"));
        assertFalse(first.contains("DO_NOT_USE_CANDIDATE"));assertFalse(first.contains("UNKNOWN_NULL_CONFIDENCE"));assertFalse(first.contains("wrong simplified"));
        assertTrue(first.contains("semanticPriorOnly"));
        assertEquals(first,service.forPage("book",500));verify(store,times(1)).readBook("book");
        verify(store,atMost(32)).readPage(eq("book"),anyInt());
        verify(store,never()).readPage(argThat(id->!"book".equals(id)),anyInt());
        changed.get().accept("book");service.forPage("book",500);verify(store,times(2)).readBook("book");
    }
}
