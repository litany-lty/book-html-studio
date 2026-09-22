package studio.bookhtml.api;

import java.util.List;
import java.util.UUID;
import studio.bookhtml.domain.PageSummary;
import studio.bookhtml.domain.ProcessingSnapshot;
import studio.bookhtml.service.OutlineService;

public record ReadingWindowResponse(UUID sessionId, long sequence, boolean enabled, String status,
                                    Integer centerPage, Integer fromPage, Integer toPage,
                                    Integer processingPage, List<Integer> queuedPages,
                                    List<PageState> pages, String message,
                                    List<Integer> processingPages,
                                    Integer readablePages) {
    public ReadingWindowResponse(UUID sessionId, long sequence, boolean enabled, String status,
                                 Integer centerPage, Integer fromPage, Integer toPage,
                                 Integer processingPage, List<Integer> queuedPages,
                                 List<PageState> pages, String message,
                                 List<Integer> processingPages) {
        this(sessionId, sequence, enabled, status, centerPage, fromPage, toPage,
                processingPage, queuedPages, pages, message, processingPages,
                (int) pages.stream().filter(p -> p != null && "READY".equals(p.status())).count());
    }
    public ReadingWindowResponse(UUID sessionId, long sequence, boolean enabled, String status,
                                Integer centerPage, Integer fromPage, Integer toPage,
                                Integer processingPage, List<Integer> queuedPages,
                                List<PageState> pages, String message) {
        this(sessionId, sequence, enabled, status, centerPage, fromPage, toPage,
             processingPage, queuedPages, pages, message,
             processingPage == null ? List.of() : List.of(processingPage));
    }

    public record PageState(int pageNumber, String status, Integer revision, String error,
                            PageSummary summary, List<OutlineService.OutlineEntry> outline,
                            long profileRevision, ProcessingSnapshot processing) {
        public PageState(int pageNumber, String status, Integer revision, String error,
                         PageSummary summary, List<OutlineService.OutlineEntry> outline) {
            this(pageNumber, status, revision, error, summary, outline, 0, null);
        }
        public PageState(int pageNumber, String status, Integer revision, String error,
                         PageSummary summary, List<OutlineService.OutlineEntry> outline,
                         long profileRevision) {
            this(pageNumber, status, revision, error, summary, outline, profileRevision, null);
        }
    }
}
