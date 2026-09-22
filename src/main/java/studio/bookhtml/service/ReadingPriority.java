package studio.bookhtml.service;

import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** A scheduling hint, never an authorization. Re-evaluated before each queued outbound call. */
@Component
public class ReadingPriority {
    private record Focus(UUID session, String bookId, int pageNumber) {}
    private final AtomicReference<Focus> focus = new AtomicReference<>();
    public void select(UUID session, String bookId, int pageNumber) { focus.set(new Focus(session, bookId, pageNumber)); }
    public void clear(UUID session) { focus.updateAndGet(value -> value != null && value.session().equals(session) ? null : value); }
    public boolean foreground(String bookId, int pageNumber, boolean fallback) {
        Focus current = focus.get();
        return current == null ? fallback : current.bookId().equals(bookId) && current.pageNumber() == pageNumber;
    }
}
