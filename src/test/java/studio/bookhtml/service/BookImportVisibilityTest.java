package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.Book;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookImportVisibilityTest {
    @TempDir Path temp;

    @Test void bookAppearsOnlyAfterEveryPageIsReady() throws Exception {
        var config = TestConfigs.config(temp, "", "");
        BookStore store = new BookStore(config, new ObjectMapper().findAndRegisterModules());
        PdfService pdf = mock(PdfService.class);
        when(pdf.inspect(any(Path.class))).thenReturn(new PdfService.PdfInfo(2));
        when(pdf.allDimensions(any(Path.class))).thenAnswer(invocation -> {
            assertTrue(store.listBooks().isEmpty(), "未建齐页面时不得在书籍列表发布");
            return List.of(new PdfService.Dimensions(600, 800), new PdfService.Dimensions(600, 800));
        });
        try {
            Book book = new BookService(store, pdf, config).upload(
                    new MockMultipartFile("file", "sample.pdf", "application/pdf", "%PDF-1.7".getBytes()));
            assertEquals(List.of(book), store.listBooks());
            assertEquals("PENDING", store.readPage(book.id(), 1).status());
            assertEquals("PENDING", store.readPage(book.id(), 2).status());
        } finally {
            store.close();
        }
    }

    @Test void changedPageCountNeverPublishesPartialBook() throws Exception {
        var config = TestConfigs.config(temp, "", "");
        BookStore store = new BookStore(config, new ObjectMapper().findAndRegisterModules());
        PdfService pdf = mock(PdfService.class);
        when(pdf.inspect(any(Path.class))).thenReturn(new PdfService.PdfInfo(2));
        when(pdf.allDimensions(any(Path.class))).thenReturn(List.of(new PdfService.Dimensions(600, 800)));
        try {
            ApiException error = assertThrows(ApiException.class, () -> new BookService(store, pdf, config).upload(
                    new MockMultipartFile("file", "sample.pdf", "application/pdf", "%PDF-1.7".getBytes())));
            assertEquals(HttpStatus.BAD_REQUEST, error.status());
            assertTrue(store.listBooks().isEmpty());
        } finally {
            store.close();
        }
    }
}
