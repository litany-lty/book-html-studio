package studio.bookhtml.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.ProcessingSnapshot;
import studio.bookhtml.service.BookService;
import studio.bookhtml.service.ProcessingProgressService;

/** A read-only lightweight endpoint: never renders images, builds a book profile or submits OCR. */
@RestController
public class PageProgressController {
    private final BookService books;
    private final ProcessingProgressService progress;
    public PageProgressController(BookService books, ProcessingProgressService progress) {
        this.books = books;
        this.progress = progress;
    }
    @GetMapping("/api/books/{id}/metadata")
    public ResponseEntity<studio.bookhtml.domain.Book> metadata(@PathVariable String id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(books.metadata(id));
    }
    public record Status(String bookId, int pageNumber, String status, Integer revision,
                         ProcessingSnapshot processing) {}
    @GetMapping("/api/books/{id}/pages/{n}/progress")
    public ResponseEntity<Status> get(@PathVariable String id, @PathVariable int n) {
        Page page = books.page(id, n);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new Status(id, n, page.status(), page.revision(), progress.latest(id, n)));
    }
}
