package studio.bookhtml.api;

import java.util.UUID;

public record ReadingWindowRequest(UUID sessionId, Long sequence, Integer currentPage,
                                   String provider, String layout, Boolean splitSpreads,
                                   Boolean assist, Boolean allowCloud, Boolean start,
                                   Boolean autoProcessAll,
                                   Boolean retryCurrentPage) {
    public ReadingWindowRequest(UUID sessionId, Long sequence, Integer currentPage,
                                String provider, String layout, Boolean splitSpreads,
                                Boolean assist, Boolean allowCloud, Boolean start) {
        this(sessionId, sequence, currentPage, provider, layout, splitSpreads, assist, allowCloud, start, false, false);
    }

    public ReadingWindowRequest(UUID sessionId, Long sequence, Integer currentPage,
                                String provider, String layout, Boolean splitSpreads,
                                Boolean assist, Boolean allowCloud, Boolean start,
                                Boolean autoProcessAll) {
        this(sessionId, sequence, currentPage, provider, layout, splitSpreads, assist, allowCloud, start, autoProcessAll, false);
    }
}
