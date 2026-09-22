package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.api.PageUpdateRequest;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Job;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LibraryManagementTest {
    @TempDir Path temp;
    private final String id = "c1111111-1111-1111-1111-111111111111";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private BookStore store() throws Exception {
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), json);
        store.createBookDirectory(id);
        store.writeBook(new Book(id, "原书名", "original.pdf", 1,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), 0, 0));
        store.writePage(id, Page.pending(1, 600, 800), false);
        return store;
    }

    private BookService service(BookStore store) {
        return new BookService(store, mock(PdfService.class), TestConfigs.config(temp, "", ""));
    }

    @Test void oldBookJsonDefaultsToVisibleAndArchiveSurvivesRestartWithoutDeletingData() throws Exception {
        BookStore first = store();
        try {
            Path metadata = first.bookDir(id).resolve("book.json");
            ObjectNode old = (ObjectNode) json.readTree(metadata.toFile());
            old.remove("archived");
            json.writeValue(metadata.toFile(), old);
            assertFalse(first.readBook(id).archived());

            JobService jobs = new JobService(first, service(first), mock(PageProcessor.class));
            Book archived = jobs.updateLibrary(id, null, true);
            assertTrue(archived.archived());
            assertEquals("original.pdf", archived.filename());
            assertEquals(id, first.listBooks().get(0).id());
            assertTrue(Files.isRegularFile(first.pagePath(id, 1)));
            jobs.close();
        } finally { first.close(); }

        BookStore reopened = new BookStore(TestConfigs.config(temp, "", ""), json);
        try {
            assertTrue(reopened.readBook(id).archived());
            assertEquals("PENDING", reopened.readPage(id, 1).status());
            JobService jobs = new JobService(reopened, service(reopened), mock(PageProcessor.class));
            assertFalse(jobs.updateLibrary(id, null, false).archived());
            jobs.close();
        } finally { reopened.close(); }
    }

    @Test void titleValidationAndPartialUpdatesPreserveOtherFields() throws Exception {
        BookStore store = store();
        try {
            JobService jobs = new JobService(store, service(store), mock(PageProcessor.class));
            assertEquals("新书名", jobs.updateLibrary(id, "  新书名  ", null).title());
            assertEquals("新书名", jobs.updateLibrary(id, null, true).title());
            assertTrue(jobs.updateLibrary(id, "另一个书名", null).archived());
            assertEquals("original.pdf", store.readBook(id).filename());
            for (String invalid : List.of("  ", "带\n换行", "\n开头", "x".repeat(121))) {
                ApiException error = assertThrows(ApiException.class, () -> jobs.updateLibrary(id, invalid, null));
                assertEquals(HttpStatus.BAD_REQUEST, error.status());
            }
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ApiException.class, () -> jobs.updateLibrary(id, null, null)).status());
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ApiException.class, () -> jobs.updateLibrary(id, "同时", false)).status());
            assertEquals("另一个书名", store.readBook(id).title());
            jobs.close();
        } finally { store.close(); }
    }

    @Test void activeOrPersistedJobAndReadingReservationBlockArchive() throws Exception {
        BookStore store = store();
        try {
            BookService books = service(store);
            JobService jobs = new JobService(store, books, mock(PageProcessor.class));
            UUID reservation = UUID.randomUUID();
            jobs.reserveReading(reservation, id);
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ApiException.class, () -> jobs.updateLibrary(id, null, true)).status());
            jobs.releaseReading(reservation);

            store.writeJob(id, new Job("active", "RUNNING", 0, 1, 1, null, List.of(), Instant.now()));
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ApiException.class, () -> jobs.updateLibrary(id, null, true)).status());
            assertEquals("可改名", jobs.updateLibrary(id, "可改名", null).title());
            store.writeJob(id, Job.idle());

            jobs.updateLibrary(id, null, true);
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ApiException.class, () -> jobs.reserveReading(UUID.randomUUID(), id)).status());
            assertEquals(HttpStatus.CONFLICT,
                    assertThrows(ApiException.class, () -> jobs.submit(id,
                            new JobRequest("1", "paddle-aistudio", "auto", false, false, false))).status());
            jobs.updateLibrary(id, null, false);
            UUID restoredReservation = UUID.randomUUID();
            jobs.reserveReading(restoredReservation, id);
            jobs.releaseReading(restoredReservation);
            jobs.close();
        } finally { store.close(); }
    }

    @Test void independentStatisticTouchCannotRevertLibraryChanges() throws Exception {
        BookStore store = store();
        try {
            BookService books = service(store);
            JobService jobs = new JobService(store, books, mock(PageProcessor.class));
            jobs.updateLibrary(id, "校定书名", null);
            jobs.updateLibrary(id, null, true);
            Block edited = new Block("manual-1", "text", 0, new double[]{0.1, 0.1, 0.8, 0.1},
                    "horizontal-tb", "人工校对内容", "人工校对内容", null, false, false,
                    null, "manual", List.of(), null, null);
            books.update(id, 1, new PageUpdateRequest(List.of(edited), false, 0));
            Book touched = store.readBook(id);
            assertEquals("校定书名", touched.title());
            assertTrue(touched.archived());
            assertEquals(1, touched.processedPages());
            jobs.close();
        } finally { store.close(); }
    }
}
