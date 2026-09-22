package studio.bookhtml.service;
import org.junit.jupiter.api.Test;
import studio.bookhtml.api.ReaderController;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ReaderFirstPaintTest {
    @Test void manifestAndColdProjectionNeverScanWholeBook() {
        BookStore store = mock(BookStore.class);
        BookService books = mock(BookService.class);
        Book book = new Book("book", "large", "book.pdf", 5000, Instant.now(), Instant.now(), 0, 0);
        when(store.readBook("book")).thenReturn(book);
        ReaderController api = new ReaderController(store, books, new ProcessingProgressService());
        assertSame(book, api.manifest("book"));
        verifyNoInteractions(books);
        BookPresentationService presentation = new BookPresentationService(store);
        try {
            assertNotNull(presentation.projectCached("book", Page.pending(2500, 600, 800)));
            verify(store, never()).readPage(anyString(), anyInt());
        } finally { presentation.close(); }
    }
}
