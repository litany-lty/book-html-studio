package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.*;
import studio.bookhtml.config.*;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadingWindowServiceTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private BookStore store;
    private JobService jobs;
    private ReadingWindowService windows;
    private SettingsService settings;
    private BookService books;
    private PageProcessor processor;
    private Book book;

    @AfterEach void close() {
        if (windows != null) windows.close();
        if (jobs != null) jobs.close();
        if (store != null) store.close();
    }

    private void setup(int pages) throws Exception {
        AppProperties app = TestConfigs.config(data, "", "");
        store = spy(new BookStore(app, json));
        String id = UUID.randomUUID().toString();
        store.createBookDirectory(id);
        book = new Book(id, "test", "test.pdf", pages, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        for (int n = 1; n <= pages; n++) store.writePage(id, Page.pending(n, 600, 800), false);
        books = mock(BookService.class);
        when(books.get(id)).thenReturn(book);
        processor = mock(PageProcessor.class);
        settings = new SettingsService(app, new PaddleAiStudioProperties("test-token", null, null, 30, 60, 1),
                new QwenAssistProperties(), new DecisionProperties(), json);
        jobs = new JobService(store, books, processor);
        jobs.setSettings(settings);
        windows = new ReadingWindowService(store, jobs, settings, clock, Duration.ofSeconds(1));
    }

    private ReadingWindowRequest request(UUID id, long sequence, int page) {
        return new ReadingWindowRequest(id, sequence, page, "paddle-aistudio", "auto", false, false, true, true);
    }

    private ReadingWindowRequest heartbeat(UUID id, long sequence, int page) {
        return new ReadingWindowRequest(id, sequence, page, "paddle-aistudio", "auto", false, false, true, false);
    }

    private void advanceAndTick(Duration duration) {
        clock.advance(duration);
        windows.tick();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("condition did not become true");
    }

    private static ProcessingResult ready(int n) {
        Block text = new Block("b" + n, "text", 0, new double[]{.1,.1,.8,.8}, "horizontal-tb",
                "text " + n, "text " + n, null, false, false, null, "paddle", List.of("b" + n), null, null);
        return new ProcessingResult(new Page(n, 600, 800, "READY", "paddle-aistudio", List.of(text),
                List.of(), false, null, List.of(text)), ProcessingResult.Category.TEXT);
    }

    @Test void prioritizesCenterAlternatingNeighborsAndClampsEnds() throws Exception {
        setup(14);
        UUID session = UUID.randomUUID();
        ReadingWindowResponse first = windows.update(book.id(), request(session, 1, 1));
        assertEquals("SETTLING", first.status());
        assertEquals(List.of(1,2,3,4,5,6), first.queuedPages());
        assertEquals(6, first.pages().size());
        ReadingWindowResponse middle = windows.update(book.id(), request(session, 2, 8));
        assertEquals(List.of(8,9,10,11,12,13,7,6,5), middle.queuedPages());
        assertEquals(5, middle.fromPage());
        assertEquals(13, middle.toPage());
        ReadingWindowResponse last = windows.update(book.id(), request(session, 3, 14));
        assertEquals(List.of(14,13,12,11), last.queuedPages());
        assertEquals(11, last.fromPage());
        assertEquals(14, last.toPage());
        assertThrows(ApiException.class, () -> windows.update(book.id(),
                new ReadingWindowRequest(session, 4L, 14, "ppocr", "auto", false, false, true, false)));
    }

    @Test void quickJumpDropsPendingButLetsInFlightFinishThenStartsLatestCenter() throws Exception {
        setup(20);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Integer> calls = new CopyOnWriteArrayList<>();
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int n = inv.getArgument(1);
                    calls.add(n);
                    if (n == 8) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); }
                    return ready(n);
                });
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 8));
        windows.tick();
        verifyNoInteractions(processor); // server-side dwell gate
        advanceAndTick(Duration.ofSeconds(1));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        ReadingWindowResponse moved = windows.update(book.id(), request(session, 2, 17));
        assertEquals(8, moved.processingPage());
        assertEquals(17, moved.queuedPages().get(0));
        assertFalse(moved.queuedPages().contains(9));
        release.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 8).status()));
        windows.tick();
        assertEquals(List.of(8), calls); // completion did not dispatch the old queue
        advanceAndTick(Duration.ofSeconds(1));
        await(() -> calls.size() >= 2);
        assertEquals(List.of(8, 17), calls.subList(0, 2));
    }

    @Test void currentPageLoadsInParallelWithExistingInFlightPage() throws Exception {
        setup(20);
        CountDownLatch entered1 = new CountDownLatch(1), release1 = new CountDownLatch(1);
        CountDownLatch entered4 = new CountDownLatch(1), release4 = new CountDownLatch(1);
        List<Integer> calls = new CopyOnWriteArrayList<>();
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int n = inv.getArgument(1);
                    calls.add(n);
                    if (n == 1) { entered1.countDown(); assertTrue(release1.await(3, TimeUnit.SECONDS)); }
                    if (n == 4) { entered4.countDown(); assertTrue(release4.await(3, TimeUnit.SECONDS)); }
                    return ready(n);
                });
        UUID session = UUID.randomUUID();
        // 1. Start on Page 1
        windows.update(book.id(), request(session, 1, 1));
        advanceAndTick(Duration.ofSeconds(1));
        assertTrue(entered1.await(2, TimeUnit.SECONDS));

        // 2. User navigates to Page 4 while Page 1 is still in-flight
        ReadingWindowResponse moved = windows.update(book.id(), request(session, 2, 4));
        assertEquals(4, moved.centerPage());
        assertEquals(4, moved.queuedPages().get(0));

        // 3. Dwell gate for Page 4 passes: Page 4 must be dispatched in parallel with Page 1
        advanceAndTick(Duration.ofSeconds(1));
        assertTrue(entered4.await(2, TimeUnit.SECONDS));

        // 4. Verify snapshot: both pages are in processingPages, and current page (4) is primary
        ReadingWindowResponse parallel = windows.get(book.id(), session);
        assertEquals(4, parallel.processingPage());
        assertTrue(parallel.processingPages().containsAll(List.of(4, 1)));
        assertEquals(List.of(4, 1), parallel.processingPages());

        // 5. Release both to complete cleanly
        release1.countDown();
        release4.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 1).status()));
        await(() -> "READY".equals(store.readPage(book.id(), 4).status()));
        windows.tick();
        assertTrue(calls.containsAll(List.of(1, 4)));
    }

    @Test void fullConcurrencyWaitsForNaturalFinishInsteadOfPreempting() throws Exception {
        setup(20);
        // Page 1 is already ready, so tick dispatches background prefetch pages 2, 3, 4
        store.writePage(book.id(), new Page(1, 600, 800, "READY", "paddle", List.of(), List.of(), false, null), false);
        CountDownLatch entered2 = new CountDownLatch(1), entered3 = new CountDownLatch(1), entered4 = new CountDownLatch(1);
        CountDownLatch releaseAll = new CountDownLatch(1);
        CountDownLatch entered10 = new CountDownLatch(1);
        List<Integer> calls = new CopyOnWriteArrayList<>();
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int n = inv.getArgument(1);
                    calls.add(n);
                    if (n == 2) { entered2.countDown(); releaseAll.await(3, TimeUnit.SECONDS); }
                    if (n == 3) { entered3.countDown(); releaseAll.await(3, TimeUnit.SECONDS); }
                    if (n == 4) { entered4.countDown(); releaseAll.await(3, TimeUnit.SECONDS); }
                    if (n == 10) { entered10.countDown(); releaseAll.await(3, TimeUnit.SECONDS); }
                    return ready(n);
                });
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 1));
        advanceAndTick(Duration.ofSeconds(1));
        assertTrue(entered2.await(2, TimeUnit.SECONDS));
        assertTrue(entered3.await(2, TimeUnit.SECONDS));
        assertTrue(entered4.await(2, TimeUnit.SECONDS));

        // 3 background pages are now running (2, 3, 4). Concurrency is full (3/3).
        // User navigates to Page 10. U2: no preemptive kill of in-flight cloud requests;
        // page 10 waits queued-first while 2/3/4 finish naturally.
        windows.update(book.id(), request(session, 2, 10));
        advanceAndTick(Duration.ofSeconds(1));

        assertFalse(entered10.await(300, TimeUnit.MILLISECONDS), "center must wait, not preempt in-flight requests");
        ReadingWindowResponse res = windows.get(book.id(), session);
        assertTrue(res.processingPages().contains(2), "in-flight page 2 must not be killed");
        assertEquals(10, res.queuedPages().get(0), "center stays queued-first");

        releaseAll.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 2).status()));
        await(() -> "READY".equals(store.readPage(book.id(), 3).status()));
        await(() -> "READY".equals(store.readPage(book.id(), 4).status()));
        windows.tick();
        advanceAndTick(Duration.ofSeconds(1));
        assertTrue(entered10.await(2, TimeUnit.SECONDS), "center dispatches after natural finish");
        assertEquals(1, calls.stream().filter(n -> n == 2).count(), "no kill-and-resubmit loop for page 2");
    }

    @Test void protectsReadyReviewedManualAndFailedAndDoesNotRetryOnNewSequence() throws Exception {
        setup(11);
        store.writePage(book.id(), new Page(4,600,800,"READY","paddle",List.of(),List.of(),false,null), false);
        store.writePage(book.id(), new Page(5,600,800,"PENDING","manual",List.of(),List.of(),true,null), false);
        store.writePage(book.id(), new Page(6,600,800,"FAILED","paddle",List.of(),List.of(),false,"failed"), false);
        UUID session = UUID.randomUUID();
        ReadingWindowResponse response = windows.update(book.id(), request(session, 1, 6));
        assertEquals(List.of(7,8,9,10,11,3), response.queuedPages());
        assertEquals(9, response.pages().size());
        assertEquals(List.of(7,8,9,10,11,3),
                windows.update(book.id(), request(session, 2, 6)).queuedPages());
    }

    @Test void pollingReturnsOnlyWindowSummariesAndOutlineWithoutBookWideRefresh() throws Exception {
        setup(20);
        Block outside = new Block("outside", "heading", 0, new double[]{.1,.1,.8,.2}, "horizontal-tb",
                "Outside", "Outside", null, false, false, 1, "manual", List.of("outside"), null, null);
        Block inside = new Block("inside", "heading", 0, new double[]{.1,.1,.8,.2}, "horizontal-tb",
                "Inside", "Inside", null, false, false, 1, "manual", List.of("inside"), null, null);
        store.writePage(book.id(), new Page(1,600,800,"READY","manual",List.of(outside),List.of(),true,null), false);
        store.writePage(book.id(), new Page(8,600,800,"READY","manual",List.of(inside),List.of(),true,null), false);
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 8));
        ReadingWindowResponse response = windows.get(book.id(), session);
        ReadingWindowResponse.PageState center = response.pages().stream()
                .filter(page -> page.pageNumber() == 8).findFirst().orElseThrow();
        assertEquals(1, center.summary().blockCount());
        assertEquals("Inside", center.outline().get(0).title());
        assertTrue(response.pages().stream().flatMap(page -> page.outline().stream())
                .noneMatch(entry -> "Outside".equals(entry.title())));
        verifyNoInteractions(books);
        verify(store, never()).readPage(book.id(), 1);
    }

    @Test void corruptPageDuringFinishStillReleasesReservationAndSettingsLease() throws Exception {
        setup(2);
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 1));
        doThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "corrupt"))
                .when(store).readPage(book.id(), 1);
        ReadingWindowResponse stopped = windows.stop(book.id(), new ReadingWindowCommand(session, 2L));
        assertEquals("BLOCKED", stopped.status());
        assertFalse(settings.busy());
        assertEquals("BLOCKED", windows.get(book.id(), session).status());
    }

    @Test void unconfiguredProviderNeverAdmitsAWindow() throws Exception {
        setup(2);
        settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"paddleAiStudio\":{\"clearAccessToken\":true}}}"));
        UUID session = UUID.randomUUID();
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> windows.update(book.id(), request(session, 1, 1))).status());
        assertEquals("IDLE", windows.get(book.id(), session).status());
        assertFalse(settings.busy());
        verifyNoInteractions(processor);
    }

    @Test void heartbeatStaleStopAndExpiryRespectLeaseAndDoNotRevive() throws Exception {
        setup(3);
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 5, 2));
        assertTrue(settings.busy());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> settings.update(json.readTree("{\"revision\":0}"))).status());
        clock.advance(Duration.ofMillis(800));
        assertEquals("SETTLING", windows.update(book.id(), heartbeat(session, 5, 2)).status());
        advanceAndTick(Duration.ofMillis(300));
        // Heartbeat did not restart the one-second settle; dispatch may already start.
        assertNotEquals("SETTLING", windows.get(book.id(), session).status());
        ReadingWindowResponse stopped = windows.stop(book.id(), new ReadingWindowCommand(session, 7L));
        assertFalse(stopped.enabled());
        assertEquals("STOPPING", windows.update(book.id(), heartbeat(session, 6, 1)).status());
        await(() -> { windows.tick(); return !settings.busy(); });
        UUID expired = UUID.randomUUID();
        windows.update(book.id(), request(expired, 1, 1));
        advanceAndTick(Duration.ofSeconds(61));
        assertEquals("EXPIRED", windows.get(book.id(), expired).status());
        assertFalse(windows.update(book.id(), request(expired, 2, 1)).enabled());
        assertFalse(settings.busy());
    }

    @Test void manualTaskAndOtherTabConflictAndRestartHasNoAutomaticWindow() throws Exception {
        setup(3);
        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 2));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> jobs.submit(book.id(), new JobRequest("1", "paddle-aistudio", "auto", false, false, false))).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> windows.update(book.id(), request(UUID.randomUUID(), 1, 1))).status());
        windows.close();
        windows = new ReadingWindowService(store, jobs, settings, clock, Duration.ofSeconds(1));
        assertEquals("IDLE", windows.get(book.id(), session).status());
        ReadingWindowResponse oldHeartbeat = windows.update(book.id(), heartbeat(session, 2, 2));
        assertEquals(session, oldHeartbeat.sessionId());
        assertEquals(2, oldHeartbeat.sequence());
        assertEquals("EXPIRED", oldHeartbeat.status());
        assertFalse(oldHeartbeat.enabled());
        assertFalse(settings.busy());
        verifyNoInteractions(processor);
        ReadingWindowResponse restarted = windows.update(book.id(), request(session, 3, 2));
        assertEquals("SETTLING", restarted.status());
        assertTrue(restarted.enabled());
        assertTrue(settings.busy());
    }

    @Test void existingManualBatchIsNotCancelledOrPreempted() throws Exception {
        setup(3);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(processor.process(eq(book.id()), eq(1), anyString(), anyString(), anyBoolean(), anyBoolean(), any()))
                .thenAnswer(inv -> { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return ready(1); });
        jobs.submit(book.id(), new JobRequest("1", "paddle-aistudio", "auto", false, false, false));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                    () -> windows.update(book.id(), request(UUID.randomUUID(), 1, 2))).status());
            assertEquals("RUNNING", store.readJob(book.id()).status());
        } finally { release.countDown(); }
        await(() -> "READY".equals(store.readPage(book.id(), 1).status()));
    }

    @Test void bothConfiguredDefaultsToPrimaryChannelOnly() throws Exception {
        setup(20);
        // Configure ppocr so both paddle-aistudio and ppocr are configured.
        // U2: "configured" no longer implies "authorized for parallel dispatch".
        // Default session authorization is primary-only.
        settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"ppocr\":{\"apiKey\":\"pp-key\",\"secretKey\":\"pp-secret\"}}}"));

        // Page 8 is already ready, so background prefetch will trigger for window around 8 (5..13)
        store.writePage(book.id(), new Page(8, 600, 800, "READY", "paddle", List.of(), List.of(), false, null), false);

        CountDownLatch entered3 = new CountDownLatch(3);
        CountDownLatch releaseAll = new CountDownLatch(1);
        Map<Integer, String> invokedChannels = new ConcurrentHashMap<>();

        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int pageNum = inv.getArgument(1);
                    String prov = inv.getArgument(2);
                    invokedChannels.put(pageNum, prov);
                    entered3.countDown();
                    releaseAll.await(3, TimeUnit.SECONDS);
                    return ready(pageNum);
                });

        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 8));
        advanceAndTick(Duration.ofSeconds(1));

        assertTrue(entered3.await(2, TimeUnit.SECONDS), "3 concurrent tasks must be started on the primary channel");
        ReadingWindowResponse res = windows.get(book.id(), session);
        assertEquals(3, res.processingPages().size());

        long paddleCount = invokedChannels.values().stream().filter("paddle-aistudio"::equals).count();
        long ppocrCount = invokedChannels.values().stream().filter("ppocr"::equals).count();
        assertEquals(3, paddleCount, "primary channel carries the session load");
        assertEquals(0, ppocrCount, "secondary channel must see 0 requests without explicit parallel authorization");

        releaseAll.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 9).status()));
        await(() -> "READY".equals(store.readPage(book.id(), 7).status()));
    }

    @Test void fullSecondaryCapacityStillWaitsInsteadOfPreempting() throws Exception {
        setup(20);
        settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"ppocr\":{\"apiKey\":\"pp-key\",\"secretKey\":\"pp-secret\"}}}"));
        store.writePage(book.id(), new Page(8, 600, 800, "READY", "paddle", List.of(), List.of(), false, null), false);

        // U2 default is primary-only: 3 slots. Fill them with blocking background pages.
        CountDownLatch entered3 = new CountDownLatch(3);
        CountDownLatch entered18 = new CountDownLatch(1);
        CountDownLatch releaseAll = new CountDownLatch(1);

        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int pageNum = inv.getArgument(1);
                    if (pageNum == 18) {
                        entered18.countDown();
                    } else {
                        entered3.countDown();
                    }
                    releaseAll.await(3, TimeUnit.SECONDS);
                    return ready(pageNum);
                });

        UUID session = UUID.randomUUID();
        windows.update(book.id(), request(session, 1, 8));
        advanceAndTick(Duration.ofSeconds(1));
        assertTrue(entered3.await(2, TimeUnit.SECONDS));

        // 3 pages are running. Now navigate to page 18: it must wait queued-first,
        // in-flight pages are never killed to free a slot.
        windows.update(book.id(), request(session, 2, 18));
        advanceAndTick(Duration.ofSeconds(1));

        assertFalse(entered18.await(300, TimeUnit.MILLISECONDS));
        ReadingWindowResponse res = windows.get(book.id(), session);
        assertEquals(18, res.queuedPages().get(0));
        assertEquals(3, res.processingPages().size());

        releaseAll.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 18).status()));
    }

    @Test void onlyPpocrConfiguredRunsUpToThreeConcurrentOnPpocr() throws Exception {
        setup(20);
        // Clear paddle-aistudio token, configure ppocr
        settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"paddleAiStudio\":{\"clearAccessToken\":true},\"ppocr\":{\"apiKey\":\"pp-key\",\"secretKey\":\"pp-secret\"}}}"));
        store.writePage(book.id(), new Page(8, 600, 800, "READY", "ppocr", List.of(), List.of(), false, null), false);

        CountDownLatch entered3 = new CountDownLatch(3);
        CountDownLatch releaseAll = new CountDownLatch(1);
        Map<Integer, String> invokedChannels = new ConcurrentHashMap<>();

        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int pageNum = inv.getArgument(1);
                    String prov = inv.getArgument(2);
                    invokedChannels.put(pageNum, prov);
                    entered3.countDown();
                    releaseAll.await(3, TimeUnit.SECONDS);
                    return ready(pageNum);
                });

        UUID session = UUID.randomUUID();
        ReadingWindowRequest req = new ReadingWindowRequest(session, 1L, 8, "ppocr", "auto", false, false, true, true);
        windows.update(book.id(), req);
        advanceAndTick(Duration.ofSeconds(1));

        assertTrue(entered3.await(2, TimeUnit.SECONDS));
        ReadingWindowResponse res = windows.get(book.id(), session);
        assertEquals(3, res.processingPages().size());
        assertTrue(invokedChannels.values().stream().allMatch("ppocr"::equals));

        releaseAll.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 9).status()));
    }

    @Test void autoProcessAllContinuesBeyondWindowWhenEnabledAndStopsWhenDisabled() throws Exception {
        setup(10);
        // Case 1: autoProcessAll is false by default
        UUID session1 = UUID.randomUUID();
        ReadingWindowRequest req1 = new ReadingWindowRequest(session1, 1L, 1, "paddle-aistudio", "auto", false, false, true, true, false);
        windows.update(book.id(), req1);

        // Mark pages 1..6 as READY to simulate immediate window completion
        for (int p = 1; p <= 6; p++) {
            store.writePage(book.id(), new Page(p, 600, 800, "READY", "paddle", List.of(), List.of(), false, null), false);
        }
        advanceAndTick(Duration.ofSeconds(1));

        // When autoProcessAll is false, pages 7..10 must NOT be queued
        ReadingWindowResponse res1 = windows.get(book.id(), session1);
        assertEquals("READY", res1.status());
        assertTrue(res1.queuedPages().isEmpty());
        for (int p = 7; p <= 10; p++) {
            assertEquals("PENDING", store.readPage(book.id(), p).status());
        }
        windows.stop(book.id(), new ReadingWindowCommand(session1, 2L));

        // Case 2: autoProcessAll is true
        UUID session2 = UUID.randomUUID();
        ReadingWindowRequest req2 = new ReadingWindowRequest(session2, 1L, 1, "paddle-aistudio", "auto", false, false, true, true, true);
        windows.update(book.id(), req2);

        List<Integer> dispatched = new CopyOnWriteArrayList<>();
        when(processor.processBaseline(eq(book.id()), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int p = inv.getArgument(1);
                    dispatched.add(p);
                    return ready(p);
                });

        advanceAndTick(Duration.ofSeconds(1));

        // Pages 7..10 must now be dispatched because autoProcessAll is enabled!
        await(() -> dispatched.containsAll(List.of(7, 8, 9)));
        assertTrue(dispatched.contains(7));
        assertTrue(dispatched.contains(8));
        assertTrue(dispatched.contains(9));
    }

    @Test void retryCurrentPageKeepsFailedSnapshotAndRequeuesForReprocess() throws Exception {
        setup(5);
        // Block page-1 processing so no worker overwrites the snapshot under assertion.
        CountDownLatch release1 = new CountDownLatch(1);
        when(processor.processBaseline(eq(book.id()), eq(1), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> { assertTrue(release1.await(4, TimeUnit.SECONDS)); return ready(1); });
        UUID session = UUID.randomUUID();
        ReadingWindowRequest req = new ReadingWindowRequest(session, 1L, 1, "paddle-aistudio", "auto", false, false, true, true);
        windows.update(book.id(), req);

        // Simulate page 1 failing
        store.writePage(book.id(), new Page(1, 600, 800, "FAILED", "paddle-aistudio", List.of(), List.of(), false, "OCR failed"), false);
        assertEquals("FAILED", store.readPage(book.id(), 1).status());

        // Send retry request. U2: the readable snapshot (error + evidence) is kept,
        // never wiped to empty PENDING; the page is queued/dispatched for reprocess.
        ReadingWindowRequest retryReq = new ReadingWindowRequest(session, 2L, 1, "paddle-aistudio", "auto", false, false, true, false, false, true);
        ReadingWindowResponse res = windows.update(book.id(), retryReq);

        assertEquals("FAILED", store.readPage(book.id(), 1).status(), "retry must not wipe the current snapshot");
        assertEquals("OCR failed", store.readPage(book.id(), 1).error(), "failure evidence must be kept");
        assertTrue(res.processingPages().contains(1) || res.queuedPages().contains(1));
        release1.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 1).status()));
    }

    @Test void retryCurrentPageKeepsReadyContentAndReprocesses() throws Exception {
        setup(5);
        CountDownLatch release1 = new CountDownLatch(1);
        when(processor.processBaseline(eq(book.id()), eq(1), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> { assertTrue(release1.await(4, TimeUnit.SECONDS)); return ready(1); });
        UUID session = UUID.randomUUID();
        ReadingWindowRequest req = new ReadingWindowRequest(session, 1L, 1, "paddle-aistudio", "auto", false, false, true, true);
        windows.update(book.id(), req);

        // Page 1 was processed and is READY
        store.writePage(book.id(), new Page(1, 600, 800, "READY", "paddle-aistudio", List.of(), List.of(), false, null), false);
        assertEquals("READY", store.readPage(book.id(), 1).status());

        // Send retry request on READY page. U2: content stays until the new
        // attempt passes all gates and CAS-replaces it.
        ReadingWindowRequest retryReq = new ReadingWindowRequest(session, 2L, 1, "paddle-aistudio", "auto", false, false, true, false, false, true);
        ReadingWindowResponse res = windows.update(book.id(), retryReq);

        assertEquals("READY", store.readPage(book.id(), 1).status(), "retry must not wipe readable content");
        assertTrue(res.processingPages().contains(1) || res.queuedPages().contains(1));
        release1.countDown();
        await(() -> "READY".equals(store.readPage(book.id(), 1).status()));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return instant; }
        synchronized void advance(Duration duration) { instant = instant.plus(duration); }
    }
}
