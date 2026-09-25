package studio.bookhtml.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import studio.bookhtml.api.ApiController;
import studio.bookhtml.domain.Book;
import studio.bookhtml.store.BookStore;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReaderLibraryIsolationTest {
  @Test void readerLibraryIsMetadataOnlyAndMarksCountsAsSnapshots() throws Exception {
    BookStore store=mock(BookStore.class);
    var book=new Book("00000000-0000-4000-8000-000000000001","大书","fixture.pdf",5000,Instant.now(),Instant.now(),30,5);
    when(store.listBooks()).thenReturn(List.of(book));
    var service=new BookService(store,mock(PdfService.class),null);
    var json=new ObjectMapper().findAndRegisterModules();
    var controller=new ApiController(service,null,null,null,null,null,null,null,null,null,json,null,null,null,null);
    var mvc=MockMvcBuilders.standaloneSetup(controller).setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    mvc.perform(get("/api/books").param("view","reader")).andExpect(status().isOk())
      .andExpect(jsonPath("$[0].id").value(book.id()))
      .andExpect(jsonPath("$[0].processedPages").value(30))
      .andExpect(jsonPath("$[0].countsStatus").value("SNAPSHOT"));
    verify(store,never()).readPage(anyString(),anyInt());verify(store,never()).indexService();
  }
}
