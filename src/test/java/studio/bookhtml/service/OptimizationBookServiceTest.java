package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OptimizationBookServiceTest {
    @TempDir Path temp;

    private Block block(int n, String original, String simplified) {
        return new Block("b" + n, "text", n, new double[]{.1, .1, .8, .1}, "horizontal-tb",
                original, simplified, .95, false, false, null, "test", List.of(), null, null);
    }

    @Test void fallbackSearchCannotOverflowItsLimitWithinOnePage() {
        BookStore store = mock(BookStore.class);
        String id = UUID.randomUUID().toString();
        when(store.readBook(id)).thenReturn(new Book(id, "书", "book.pdf", 2, Instant.EPOCH, Instant.EPOCH, 0, 0));
        List<Block> blocks = IntStream.range(0, 550).mapToObj(n -> block(n, "needle " + n, "needle " + n)).toList();
        when(store.readPage(id, 1)).thenReturn(new Page(1, 600, 800, "READY", "test", blocks, List.of(), false, null));
        var books = new BookService(store, mock(PdfService.class), TestConfigs.config(temp, "", ""));
        assertEquals(500, books.search(id, "NEEDLE").size());
        verify(store, never()).readPage(id, 2);
    }

    @Test void searchKeepsOriginalTextWhenSimplifiedTextIsBlank() {
        BookStore store = mock(BookStore.class);
        String id = UUID.randomUUID().toString();
        when(store.readBook(id)).thenReturn(new Book(id, "书", "book.pdf", 1, Instant.EPOCH, Instant.EPOCH, 0, 0));
        when(store.readPage(id, 1)).thenReturn(new Page(1, 600, 800, "READY", "test",
                List.of(block(0, "原稿詞條", " ")), List.of(), false, null));
        var books = new BookService(store, mock(PdfService.class), TestConfigs.config(temp, "", ""));
        assertEquals("原稿詞條", books.search(id, "詞條").get(0).get("text"));
    }

    @Test void uploadedWindowsFilenameNeverBecomesTheBookTitle() throws Exception {
        var config = TestConfigs.config(temp, "", "");
        BookStore store = new BookStore(config, new ObjectMapper().findAndRegisterModules());
        PdfService pdf = mock(PdfService.class);
        when(pdf.inspect(any(Path.class))).thenReturn(new PdfService.PdfInfo(1));
        when(pdf.allDimensions(any(Path.class))).thenReturn(List.of(new PdfService.Dimensions(600, 800)));
        try {
            Book book = new BookService(store, pdf, config).upload(new MockMultipartFile("file",
                    "C:\\fakepath\\古籍.pdf", "application/pdf", "%PDF-test".getBytes()));
            assertEquals("古籍.pdf", book.filename());
            assertEquals("古籍", book.title());
        } finally { store.close(); }
    }
}
