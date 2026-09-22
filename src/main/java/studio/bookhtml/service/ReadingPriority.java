package studio.bookhtml.service;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicReference;

/** Scheduling hint only; never authorizes a provider, a page write, or a paid request. */
@Component
public class ReadingPriority {
    private record Target(String bookId, int pageNumber) {}
    private final AtomicReference<Target> current = new AtomicReference<>();
    public void focus(String bookId, int pageNumber) { current.set(new Target(bookId, pageNumber)); }
    public void clear(String bookId) { current.updateAndGet(t -> t != null && t.bookId().equals(bookId) ? null : t); }
    public boolean foreground(String bookId, int pageNumber) {
        Target t = current.get();
        return t == null || (t.bookId().equals(bookId) && t.pageNumber() == pageNumber);
    }
}
