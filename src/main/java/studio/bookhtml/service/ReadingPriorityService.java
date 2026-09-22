package studio.bookhtml.service;

import org.springframework.stereotype.Service;

/** Current reading target, not a persistent permission to process a book. */
@Service
public class ReadingPriorityService {
    private record Target(String bookId, int page) {}
    private volatile Target target;
    public void focus(String bookId, int page) { target = new Target(bookId, page); }
    public void clear(String bookId) {
        Target current = target;
        if(current != null && current.bookId().equals(bookId)) target = null;
    }
    public boolean foreground(boolean requested) {
        Target current = target;
        UsageContext.Value scope = UsageContext.current();
        return requested && (current == null || scope == null || scope.pageNumber() == null
                || (current.bookId().equals(scope.bookId()) && current.page() == scope.pageNumber()));
    }
}
