package studio.bookhtml.api;

import org.springframework.web.bind.annotation.*;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.ProcessingSnapshot;
import studio.bookhtml.service.BookService;
import studio.bookhtml.service.ProcessingProgressService;
import studio.bookhtml.store.BookStore;

/** Constant-page-count reads. These endpoints never start OCR or scan the book. */
@RestController
@RequestMapping("/api/books/{id}/reader")
public class ReaderController {
    private final BookStore store;
    private final BookService books;
    private final ProcessingProgressService progress;
    public ReaderController(BookStore store, BookService books, ProcessingProgressService progress) {
        this.store = store; this.books = books; this.progress = progress;
    }
    @GetMapping public Book manifest(@PathVariable String id) { return store.readBook(id); }
    public record PageProgress(int pageNumber, String status, Integer revision,
                               ProcessingSnapshot processing) {}
    @GetMapping("/pages/{n}/progress")
    public PageProgress progress(@PathVariable String id, @PathVariable int n) {
        Page page = books.page(id, n);
        return new PageProgress(n, page.status(), page.revision(), progress.latest(id, n));
    }
}
