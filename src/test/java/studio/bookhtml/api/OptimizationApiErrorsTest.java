package studio.bookhtml.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import studio.bookhtml.service.BookService;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OptimizationApiErrorsTest {
    private MockMvc mvc(BookService books) {
        ApiController controller = new ApiController(books, null, null, null, null, null,
                null, null, null, null, new ObjectMapper(), null, null, null, null);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test void invalidImageWidthIsBadRequestWithoutRendering() throws Exception {
        BookService books = mock(BookService.class);
        mvc(books).perform(get("/api/books/book/pages/1/image").param("width", "1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(books);
    }

    @Test void missingUploadPartIsBadRequest() throws Exception {
        mvc(mock(BookService.class)).perform(multipart("/api/books"))
                .andExpect(status().isBadRequest());
    }

    @Test void unsupportedMethodKeepsItsHttpStatus() throws Exception {
        mvc(mock(BookService.class)).perform(delete("/api/books"))
                .andExpect(status().isMethodNotAllowed());
    }
}
