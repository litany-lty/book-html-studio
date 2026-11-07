package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationPageRangesTest {
    @Test void overflowIsAReadableBadRequest() {
        for (String input : List.of("2147483648", "1-2147483648", "9".repeat(80))) {
            ApiException error = assertThrows(ApiException.class, () -> PageRanges.parse(input, 5000));
            assertEquals(HttpStatus.BAD_REQUEST, error.status());
        }
    }

    @Test void rejectsTrailingEmptySelections() {
        for (String input : List.of("1,", "1,,", "1-3,")) {
            assertThrows(ApiException.class, () -> PageRanges.parse(input, 10));
        }
    }

    @Test void invalidRangeIsRejectedBeforeExpansion() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThrows(ApiException.class, () -> PageRanges.parse("1-2147483647", 10)));
        assertThrows(ApiException.class, () -> PageRanges.parse("all", 0));
        assertEquals(List.of(1, 2, 3, 5), PageRanges.parse(" 1 - 3, 2, 5 ", 5));
    }
}
