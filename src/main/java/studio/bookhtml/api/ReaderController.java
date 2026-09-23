package studio.bookhtml.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.ProcessingSnapshot;
import studio.bookhtml.service.BookService;
import studio.bookhtml.service.ProcessingProgressService;
import studio.bookhtml.store.BookStore;

/** Read-only projections. No endpoint starts a model request. */
@RestController
@RequestMapping("/api/books/{id}/reader")
public class ReaderController {
    private final BookStore store;
    private final BookService books;
    private final ProcessingProgressService progress;
    public ReaderController(BookStore store, BookService books, ProcessingProgressService progress) {
        this.store=store; this.books=books; this.progress=progress;
    }
    @GetMapping public Book manifest(@PathVariable String id) { return store.readBook(id); }
    public record PageProgress(int pageNumber,String status,Integer revision,ProcessingSnapshot processing) {}
    public PageProgress progress(String id,int n) {
        Page page=books.page(id,n);
        return new PageProgress(n,page.status(),page.revision(),progress.latest(id,n));
    }
    @GetMapping("/pages/{n}/progress")
    public ResponseEntity<PageProgress> conditionalProgress(@PathVariable String id,@PathVariable int n,
            @RequestHeader(value="If-None-Match",required=false) String validator) {
        PageProgress payload=progress(id,n);
        String identity=id+":"+n+":"+payload.status()+":"+payload.revision()+":"+payload.processing()
                +":"+(payload.processing()==null?"":payload.processing().serverInstanceId());
        String etag="\""+studio.bookhtml.decision.DecisionHash.sha256Hex(identity)+"\"";
        boolean matches=validator!=null && java.util.Arrays.stream(validator.split(","))
                .map(String::strip).anyMatch(v->v.equals(etag)||v.equals("W/"+etag)||v.equals("*"));
        if(matches) return ResponseEntity.status(304).eTag(etag).build();
        return ResponseEntity.ok().eTag(etag).body(payload);
    }
}
