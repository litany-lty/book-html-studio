package studio.bookhtml.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import studio.bookhtml.domain.ProcessingSnapshot;
import studio.bookhtml.service.ProcessingProgressService;
import studio.bookhtml.store.BookStore;

/** Read-only, cheap attempt metadata; this endpoint never starts or retries model work. */
@RestController
@RequestMapping("/api/books/{id}/pages/{page}/processing")
public class PageProcessingController {
    private final BookStore store;
    private final ProcessingProgressService progress;
    public PageProcessingController(BookStore store, ProcessingProgressService progress) {
        this.store = store; this.progress = progress;
    }
    @GetMapping public ResponseEntity<ProcessingSnapshot> get(@PathVariable String id, @PathVariable int page) {
        var book = store.readBook(id);
        if (page < 1 || page > book.totalPages()) throw new ApiException(HttpStatus.BAD_REQUEST, "页码超出书籍范围");
        ProcessingSnapshot snapshot = progress.latest(id, page);
        if(snapshot == null) return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(snapshot);
    }
}
