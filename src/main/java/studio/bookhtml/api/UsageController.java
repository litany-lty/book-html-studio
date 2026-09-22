package studio.bookhtml.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import studio.bookhtml.service.BookService;
import studio.bookhtml.service.UsageLedger;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/books/{bookId}/usage")
public class UsageController {
    private final BookService books;
    private final UsageLedger ledger;
    public UsageController(BookService books, UsageLedger ledger) { this.books = books; this.ledger = ledger; }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(@PathVariable String bookId,
                                                    @RequestParam(defaultValue = "0") int offset,
                                                    @RequestParam(defaultValue = "50") int limit,
                                                    @RequestParam(required = false) String asOf,
                                                    @RequestParam(required = false) String cursor) {
        books.get(bookId);
        try {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(ledger.view(bookId, offset, limit, asOf, cursor));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "用量账本损坏或不可用，未按零费用处理");
        }
    }
}
