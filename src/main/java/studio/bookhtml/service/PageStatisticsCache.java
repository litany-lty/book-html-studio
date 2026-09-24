package studio.bookhtml.service;

import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded incremental counters; cold scans remain explicit and never hold the store lock. */
final class PageStatisticsCache {
    private static final int CAPACITY = 64;
    record Counts(int processed, int reviewed) {}
    private record Entry(int totalPages, long epoch, Counts counts) {}
    private final BookStore store;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);

    PageStatisticsCache(BookStore store) {
        this.store = store;
        store.addPageChangeListener(this::changed);
    }

    Counts cached(Book book) {
        synchronized (entries) {
            Entry hit = entries.get(book.id());
            long epoch = store.pageEpoch(book.id());
            return hit != null && hit.totalPages() == book.totalPages() && hit.epoch() == epoch && (epoch & 1) == 0
                    ? hit.counts() : null;
        }
    }

    Counts counts(Book book) {
        long epoch = store.pageEpoch(book.id());
        synchronized (entries) {
            Entry hit = entries.get(book.id());
            if (hit != null && hit.totalPages() == book.totalPages() && hit.epoch() == epoch && (epoch & 1) == 0)
                return hit.counts();
        }
        var index=store.indexService()==null?null:store.indexService().manifest(store.bookDir(book.id()));
        if(index!=null && index.totalPages()==book.totalPages()) {
            Counts persisted=new Counts(index.processedPages(),index.reviewedPages());
            synchronized(entries) {
                if((epoch&1)==0 && store.pageEpoch(book.id())==epoch) {
                    entries.put(book.id(),new Entry(book.totalPages(),epoch,persisted));
                    while(entries.size()>CAPACITY) entries.remove(entries.keySet().iterator().next());
                    return persisted;
                }
            }
        }
        int processed = 0, reviewed = 0;
        for (int n = 1; n <= book.totalPages(); n++) {
            Page page = store.readPage(book.id(), n);
            processed += processed(page);
            reviewed += reviewed(page);
        }
        Counts counts = new Counts(processed, reviewed);
        synchronized (entries) {
            // A publication that overlapped the scan prevents stale reinsertion.
            // Never cache an odd epoch, even when the writer is paused after rename.
            if ((epoch & 1) == 0 && store.pageEpoch(book.id()) == epoch) {
                entries.put(book.id(), new Entry(book.totalPages(), epoch, counts));
                while (entries.size() > CAPACITY) entries.remove(entries.keySet().iterator().next());
            }
        }
        return counts;
    }

    private void changed(BookStore.PageChange change) {
        synchronized (entries) {
            Entry previous = entries.get(change.bookId());
            if (previous == null) return;
            if (previous.epoch() != change.sourceEpoch() - 2) {
                entries.remove(change.bookId());
                return;
            }
            int processed = previous.counts().processed() + processed(change.committed()) - processed(change.previous());
            int reviewed = previous.counts().reviewed() + reviewed(change.committed()) - reviewed(change.previous());
            if (processed < 0 || processed > previous.totalPages() || reviewed < 0 || reviewed > previous.totalPages()) {
                entries.remove(change.bookId());
                return;
            }
            entries.put(change.bookId(), new Entry(previous.totalPages(), change.sourceEpoch(), new Counts(processed, reviewed)));
        }
    }
    int size() { synchronized (entries) { return entries.size(); } }
    private static int processed(Page page) { return page != null && "READY".equals(page.status()) ? 1 : 0; }
    private static int reviewed(Page page) { return page != null && page.reviewed() ? 1 : 0; }
}
