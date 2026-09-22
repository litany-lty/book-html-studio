package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.api.PageUpdateRequest;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.CommitOp;
import studio.bookhtml.store.PageConflictException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** R03：同版本并发提交仅一个成功；缺失版本拒绝；任务与人工竞争保留人工结果。 */
class ConcurrentPageCommitTest {
    @TempDir Path temp;
    private ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }

    private static Block textBlock(String id, String text) {
        return new Block(id, "text", 0, new double[]{.1, .1, .1, .2}, "vertical-rl",
                text, text, null, false, false, null, "paddle:R", List.of(id), null, null);
    }

    private BookService service(BookStore store, String id) throws Exception {
        store.createBookDirectory(id);
        store.writeBook(new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
        store.writePage(id, new Page(1, 600, 800, "READY", "paddle",
                List.of(textBlock("s", "原文内容文字")), List.of(), false, null,
                List.of(textBlock("s", "原文内容文字"))), false);
        return new BookService(store, mock(PdfService.class), TestConfigs.config(temp, "", ""),
                new OutlineService(store));
    }

    @Test void sameRevisionConcurrentSavesYieldOneSuccessOne409() throws Exception {
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "a1111111-1111-1111-1111-111111111111";
        BookService service = service(store, id);
        int rev = BookStore.revisionOrZero(store.readPage(id, 1));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Page> first = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", "甲甲甲甲")), false, rev));
            });
            Future<Page> second = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", "乙乙乙乙")), false, rev));
            });
            int successes = 0, conflicts = 0;
            for (Future<Page> f : List.of(first, second)) {
                try {
                    f.get(15, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof ApiException);
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) e.getCause()).status());
                    conflicts++;
                }
            }
            assertEquals(1, successes, "同版本并发保存必须恰好一个成功");
            assertEquals(1, conflicts, "同版本并发保存必须恰好一个 409");
            Page committed = store.readPage(id, 1);
            assertEquals(rev + 1, BookStore.revisionOrZero(committed));
            String text = committed.blocks().get(0).original();
            assertTrue(text.equals("甲甲甲甲") || text.equals("乙乙乙乙"), "结果不得是混合文本");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void missingRevisionIsRejectedWithoutWriting() throws Exception {
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "a2222222-2222-2222-2222-222222222222";
        BookService service = service(store, id);
        int beforeRevision = BookStore.revisionOrZero(store.readPage(id, 1));
        byte[] before = Files.readAllBytes(store.pagePath(id, 1));
        try {
            service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", "新文字")), false, null));
            fail("缺少 revision 必须拒绝");
        } catch (ApiException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.status());
        }
        assertEquals(beforeRevision, BookStore.revisionOrZero(store.readPage(id, 1)), "拒绝前后版本必须一致");
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(store.pagePath(id, 1))), "拒绝写入不得改变文件");
    }

    private static int rev(BookStore store, String id) {
        return BookStore.revisionOrZero(store.readPage(id, 1));
    }

    @Test void staleCommitFailsWithoutTouchingFile() throws Exception {
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "a3333333-3333-3333-3333-333333333333";
        service(store, id);
        int rev = rev(store, id);
        byte[] before = Files.readAllBytes(store.pagePath(id, 1));
        PageConflictException conflict = assertThrows(PageConflictException.class, () ->
                store.commitPage(id, new Page(1, 600, 800, "READY", "manual",
                        List.of(textBlock("s", "过期写入")), List.of(), false, null, null, null),
                        rev + 99, CommitActor.MANUAL, null, CommitOp.MANUAL_SAVE));
        assertEquals(rev, conflict.currentRevision());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(store.pagePath(id, 1))), "冲突写入不得改变文件");
    }

    @Test void manualCommitRefusedAfterJobRegisteredAndJobCompletes() throws Exception {
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "a4444444-4444-4444-4444-444444444444";
        BookService books = service(store, id);
        java.util.concurrent.CountDownLatch inProcessor = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseProcessor = new java.util.concurrent.CountDownLatch(1);
        PageProcessor processor = mock(PageProcessor.class);
        when(processor.process(eq(id), eq(1), anyString(), anyString(), anyBoolean(), anyBoolean(), any())).thenAnswer(inv -> {
            inProcessor.countDown();
            assertTrue(releaseProcessor.await(10, TimeUnit.SECONDS));
            Block ocr = textBlock("ocr-1", "识别结果文字内容");
            return new ProcessingResult(new Page(1, 600, 800, "READY", "local", List.of(ocr), List.of(), false, null, List.of(ocr)),ProcessingResult.Category.TEXT);
        });
        JobService jobs = new JobService(store, books, processor);
        try {
            jobs.submit(id, new JobRequest("1", "local", "auto", false, true, false));
            assertTrue(inProcessor.await(10, TimeUnit.SECONDS), "任务应进入识别");
            // A1-C01：预检查通过后、任务登记后的人工提交，在锁内被拒绝且无副作用
            Page current = store.readPage(id, 1);
            int processingRev = BookStore.revisionOrZero(current);
            byte[] beforeBytes = Files.readAllBytes(store.pagePath(id, 1));
            PageConflictException refused = assertThrows(PageConflictException.class, () ->
                    store.commitPage(id, new Page(1, 600, 800, "READY", "manual",
                            List.of(textBlock("s", "人工校对结果文字")), List.of(), true, null, current.sourceRecords(), null),
                            processingRev, CommitActor.MANUAL, null, CommitOp.MANUAL_SAVE));
            assertTrue(refused.getMessage().contains("识别"), "拒绝原因必须明确：" + refused.getMessage());
            assertEquals(processingRev, BookStore.revisionOrZero(store.readPage(id, 1)));
            assertTrue(java.util.Arrays.equals(beforeBytes, Files.readAllBytes(store.pagePath(id, 1))), "拒绝提交不得有副作用");
            releaseProcessor.countDown();
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                String status = store.readJob(id).status();
                if (status.startsWith("COMPLETED") || "FAILED".equals(status)) break;
                Thread.sleep(50);
            }
            Page committed = store.readPage(id, 1);
            assertEquals("识别结果文字内容", committed.blocks().get(0).original(), "任务结果应正常提交");
            assertTrue(store.readJob(id).errors().isEmpty(), "不应有冲突错误");
        } finally {
            releaseProcessor.countDown();
            jobs.close();
        }
    }

    @Test void revertAndSaveRaceLeavesSingleWinner() throws Exception {
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "a5555555-5555-5555-5555-555555555555";
        BookService service = service(store, id);
        // 先保存一次，产生 rev1 并留下 rev0 历史
        service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", "第一次保存文字")), false, 0));
        int rev = rev(store, id);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> save = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", "并发保存文字内容")), false, rev));
            });
            Future<?> revert = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.revert(id, 1, 0, rev);
            });
            int successes = 0, conflicts = 0;
            for (Future<?> f : List.of(save, revert)) {
                try {
                    f.get(15, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof ApiException);
                    assertEquals(HttpStatus.CONFLICT, ((ApiException) e.getCause()).status());
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
            assertEquals(rev + 1, rev(store, id));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void secondProcessWithSameDataDirRefusesSharedWrite() throws Exception {
        Path data = temp.resolve("lockdata");
        Files.createDirectories(data);
        BookStore first = new BookStore(TestConfigs.config(data, "", ""), mapper());
        assertNotNull(first);
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        Process process = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                "studio.bookhtml.store.DataDirLockProbe", data.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        assertTrue(exited, "锁探针进程必须退出");
        assertEquals(2, process.exitValue(), "第二个进程必须拒绝共享写入：" + output);
        assertTrue(output.contains("另一进程"), "拒绝原因必须明确：" + output);
    }

    private static studio.bookhtml.domain.Job queuedJob(String jobId, int page) {
        return new studio.bookhtml.domain.Job(jobId, "QUEUED", 0, 1, null, null, List.of(), Instant.now(),
                List.of(page), "local", "auto", false, false, false, "fp");
    }

    private Page manualPage(String text) {
        return new Page(1, 600, 800, "READY", "manual",
                List.of(textBlock("s", text)), List.of(), false, null, null, null);
    }

    @Test void revertRefusedWhenPageCoveredByQueuedJob() throws Exception {
        // A1-C02：QUEUED 任务已登记但页尚未 PROCESSING，回退同样按统一占用策略拒绝
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "b1111111-1111-1111-1111-111111111111";
        BookService service = service(store, id);
        service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", "第一次保存文字")), false, 0));
        int rev = rev(store, id);
        byte[] before = Files.readAllBytes(store.pagePath(id, 1));
        store.writeJob(id, queuedJob("job-1", 1));
        PageConflictException refused = assertThrows(PageConflictException.class,
                () -> service.revert(id, 1, 0, rev));
        assertTrue(refused.getMessage().contains("识别"));
        assertEquals(rev, rev(store, id));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(store.pagePath(id, 1))), "拒绝回退不得有副作用");
    }

    @Test void jobStartRefusedWhenTerminalOrForeign() throws Exception {
        // A1-C03：同 jobId 但已终态，或页不属于任务，JOB_START/成功结果都不允许写入
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "b2222222-2222-2222-2222-222222222222";
        service(store, id);
        int rev = rev(store, id);
        Page processing = new Page(1, 600, 800, "PROCESSING", "local", List.of(), List.of(), false, null, null, null);
        // 终态任务：COMPLETED 同 jobId
        store.writeJob(id, new studio.bookhtml.domain.Job("job-1", "COMPLETED", 1, 1, null, null, List.of(), Instant.now(),
                List.of(1), "local", "auto", false, false, false, "fp"));
        assertThrows(PageConflictException.class, () ->
                store.commitPage(id, processing, rev, CommitActor.JOB, "job-1", CommitOp.JOB_START));
        // 页不属于任务
        store.writeJob(id, new studio.bookhtml.domain.Job("job-2", "RUNNING", 0, 1, null, null, List.of(), Instant.now(),
                List.of(2), "local", "auto", false, false, false, "fp"));
        PageConflictException foreign = assertThrows(PageConflictException.class, () ->
                store.commitPage(id, processing, rev, CommitActor.JOB, "job-2", CommitOp.JOB_START));
        assertTrue(foreign.getMessage().contains("不属于"));
        assertEquals(rev, rev(store, id));
    }

    @Test void jobCompleteRefusedWhenCancelled() throws Exception {
        // A1-C03：任务已取消，迟到成功结果不得写入
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "b2233333-3333-3333-3333-333333333333";
        service(store, id);
        int rev = rev(store, id);
        store.writeJob(id, queuedJob("job-1", 1));
        Page processing = new Page(1, 600, 800, "PROCESSING", "local", List.of(), List.of(), false, null, null, null);
        Page started = store.commitPage(id, processing, rev, CommitActor.JOB, "job-1", CommitOp.JOB_START);
        assertEquals("PROCESSING", started.status());
        store.writeJob(id, new studio.bookhtml.domain.Job("job-1", "CANCELLED", 0, 1, 1, null, List.of(), Instant.now(),
                List.of(1), "local", "auto", false, false, false, "fp"));
        Page late = manualPage("迟到结果");
        assertThrows(PageConflictException.class, () ->
                store.commitPage(id, late, rev + 1, CommitActor.JOB, "job-1", CommitOp.JOB_COMPLETE));
        assertEquals(rev + 1, rev(store, id));
    }

    @Test void cancellingRestoreSucceedsAndStaleRestoreRefused() throws Exception {
        // A1-C04：CANCELLING 对自己的 processing 版本恢复合法；过期恢复保留较新版本
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "b3333333-3333-3333-3333-333333333333";
        service(store, id);
        int rev = rev(store, id);
        store.writeJob(id, new studio.bookhtml.domain.Job("job-1", "RUNNING", 0, 1, null, null, List.of(), Instant.now(),
                List.of(1), "local", "auto", false, false, false, "fp"));
        Page processing = new Page(1, 600, 800, "PROCESSING", "local", List.of(), List.of(), false, null, null, null);
        store.commitPage(id, processing, rev, CommitActor.JOB, "job-1", CommitOp.JOB_START);
        store.writeJob(id, new studio.bookhtml.domain.Job("job-1", "CANCELLING", 0, 1, 1, null, List.of(), Instant.now(),
                List.of(1), "local", "auto", false, false, false, "fp"));
        Page restored = store.commitPage(id, manualPage("恢复旧可读状态"), rev + 1, CommitActor.JOB, "job-1", CommitOp.JOB_RESTORE);
        assertEquals(rev + 2, BookStore.revisionOrZero(restored));
        assertThrows(PageConflictException.class, () ->
                store.commitPage(id, manualPage("过期恢复"), rev + 1, CommitActor.JOB, "job-1", CommitOp.JOB_RESTORE));
        assertEquals("恢复旧可读状态", store.readPage(id, 1).blocks().get(0).original());
    }

    @Test void ioFailureAtEachStageLeavesNoPseudoSuccess() throws Exception {
        // A1-C06：各落盘阶段注入失败——不伪成功；旧成果不被清空
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "b4444444-4444-4444-4444-444444444444";
        BookService service = service(store, id);
        // history 阶段：READY 页归档失败
        byte[] before = Files.readAllBytes(store.pagePath(id, 1));
        long historyBefore = Files.exists(store.historyDir(id, 1))
                ? Files.list(store.historyDir(id, 1)).count() : 0;
        BookStore.failNextIoAt("history");
        try {
            service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", "新文字")), false, 0));
            fail("归档失败必须抛错");
        } catch (ApiException e) {
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.status());
        } finally {
            BookStore.clearIoFailure();
        }
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(store.pagePath(id, 1))), "归档失败不得改动页面文件");
        long historyAfter = Files.exists(store.historyDir(id, 1))
                ? Files.list(store.historyDir(id, 1)).count() : 0;
        assertEquals(historyBefore, historyAfter);
        // page/atomic 阶段：PENDING 页无归档，直接写页失败
        Path data2 = temp.resolve("c06b");
        Files.createDirectories(data2);
        BookStore store2 = new BookStore(TestConfigs.config(data2, "", ""), mapper());
        String id2 = "b4444444-4444-4444-4444-444444444445";
        store2.createBookDirectory(id2);
        store2.writeBook(new Book(id2, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
        store2.writePage(id2, Page.pending(1, 600, 800), false);
        byte[] before2 = Files.readAllBytes(store2.pagePath(id2, 1));
        BookService service2 = new BookService(store2, mock(PdfService.class), TestConfigs.config(data2, "", ""),
                new OutlineService(store2));
        BookStore.failNextIoAt("atomic");
        try {
            service2.update(id2, 1, new PageUpdateRequest(List.of(textBlock("s", "新文字")), false, 0));
            fail("写页失败必须抛错");
        } catch (ApiException e) {
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.status());
        } finally {
            BookStore.clearIoFailure();
        }
        assertTrue(java.util.Arrays.equals(before2, Files.readAllBytes(store2.pagePath(id2, 1))), "写页失败不得改动页面文件");
        // original 阶段：快照失败不建文件
        BookStore.failNextIoAt("original");
        try {
            store2.preserveOriginal(id2, store2.readPage(id2, 1));
            fail("快照失败必须抛错");
        } catch (java.io.IOException e) {
            assertTrue(e.getMessage().contains("original"));
        } finally {
            BookStore.clearIoFailure();
        }
        assertTrue(Files.notExists(store2.originalPagePath(id2, 1)));
        // FINAL §5.5: an ancillary metadata failure cannot turn a durable page commit into HTTP 500.
        BookStore.failNextIoAt("book");
        try {
            Page saved = service2.update(id2, 1, new PageUpdateRequest(List.of(textBlock("s", "新文字")), false, 0));
            assertEquals(1, saved.revision());
            assertEquals("新文字", saved.blocks().get(0).original());
        } finally {
            BookStore.clearIoFailure();
        }
        assertEquals(1, service2.get(id2).processedPages(), "read repairs the derived count without resubmitting the page");
        Page committed = store2.readPage(id2, 1);
        assertEquals(1, BookStore.revisionOrZero(committed), "统计失败时页面本身必须已提交");
        assertEquals("新文字", committed.blocks().get(0).original());
    }

    @Test void hundredRoundsSaveAndRevertSingleWinnerEach() throws Exception {
        // A1-C07：100 轮受控重复——每轮单一赢家、revision 正确、无混合文字
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "b5555555-5555-5555-5555-555555555555";
        BookService service = service(store, id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 100; round++) {
                int rev = rev(store, id);
                String saveText = "S" + round + "保存文字";
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> first;
                Future<?> second;
                final String expectOther;
                if (round % 2 == 0) {
                    String otherText = "T" + round + "保存文字";
                    first = pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", saveText)), false, rev));
                    });
                    second = pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", otherText)), false, rev));
                    });
                    expectOther = otherText;
                } else {
                    int target = store.listRevisions(id, 1).stream().filter(r -> r != rev).max(Integer::compareTo).orElseThrow();
                    first = pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", saveText)), false, rev));
                    });
                    second = pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return service.revert(id, 1, target, rev);
                    });
                    expectOther = null;
                }
                int successes = 0, conflicts = 0;
                for (Future<?> f : List.of(first, second)) {
                    try {
                        f.get(15, TimeUnit.SECONDS);
                        successes++;
                    } catch (ExecutionException e) {
                        assertTrue(e.getCause() instanceof ApiException, "第 " + round + " 轮异常类型：" + e.getCause());
                        assertEquals(HttpStatus.CONFLICT, ((ApiException) e.getCause()).status());
                        conflicts++;
                    }
                }
                assertEquals(1, successes, "第 " + round + " 轮必须恰好一个成功");
                assertEquals(1, conflicts, "第 " + round + " 轮必须恰好一个 409");
                assertEquals(rev + 1, rev(store, id), "第 " + round + " 轮版本必须 +1");
                if (expectOther != null) {
                    String text = store.readPage(id, 1).blocks().get(0).original();
                    assertTrue(text.equals(saveText) || text.equals(expectOther), "第 " + round + " 轮不得混合：" + text);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void negativeRevisionRejected400() throws Exception {
        // A1-C09：负数版本号可预测 400
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "b6666666-6666-6666-6666-666666666666";
        BookService service = service(store, id);
        int rev = rev(store, id);
        try {
            service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", "负数")), false, -1));
            fail("负数 revision 必须拒绝");
        } catch (ApiException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.status());
        }
        try {
            service.revert(id, 1, -1, rev);
            fail("负数 target 必须拒绝");
        } catch (ApiException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.status());
        }
        try {
            service.revert(id, 1, 0, -1);
            fail("负数 expected 必须拒绝");
        } catch (ApiException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.status());
        }
        assertEquals(rev, rev(store, id));
    }
}
