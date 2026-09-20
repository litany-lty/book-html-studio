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
        byte[] before = Files.readAllBytes(store.pagePath(id, 1));
        try {
            service.update(id, 1, new PageUpdateRequest(List.of(textBlock("s", "新文字")), false, null));
            fail("缺少 revision 必须拒绝");
        } catch (ApiException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.status());
        }
        assertEquals(rev(store, id), BookStore.revisionOrZero(store.readPage(id, 1)));
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
                        rev + 99, CommitActor.MANUAL, null));
        assertEquals(rev, conflict.currentRevision());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(store.pagePath(id, 1))), "冲突写入不得改变文件");
    }

    @Test void jobResultYieldsToConcurrentManualWriteAndKeepsCandidate() throws Exception {
        BookStore store = new BookStore(TestConfigs.config(temp, "", ""), mapper());
        String id = "a4444444-4444-4444-4444-444444444444";
        BookService books = service(store, id);
        PageProcessor processor = mock(PageProcessor.class);
        when(processor.process(eq(id), eq(1), anyString(), anyString(), anyBoolean(), anyBoolean(), any())).thenAnswer(inv -> {
            // 模拟识别期间的并发版本推进（绕过任务活跃 guard 的带外写入，如旧客户端直接写）
            Page current = store.readPage(id, 1);
            store.commitPage(id, new Page(1, 600, 800, "READY", "manual",
                    List.of(textBlock("s", "人工校对结果文字")), List.of(), true, null, current.sourceRecords(), null),
                    BookStore.revisionOrZero(current), CommitActor.SYSTEM, null);
            Block ocr = textBlock("ocr-1", "识别结果文字内容");
            return new Page(1, 600, 800, "READY", "local", List.of(ocr), List.of(), false, null, List.of(ocr));
        });
        JobService jobs = new JobService(store, books, processor);
        try {
            jobs.submit(id, new JobRequest("1", "local", "auto", false, true, false));
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                String status = store.readJob(id).status();
                if (status.startsWith("COMPLETED") || "FAILED".equals(status)) break;
                Thread.sleep(50);
            }
            Page committed = store.readPage(id, 1);
            assertEquals("人工校对结果文字", committed.blocks().get(0).original(), "必须保留人工结果");
            Page candidate = store.readCandidate(id, 1);
            assertNotNull(candidate, "后台识别结果必须另存候选");
            assertEquals("识别结果文字内容", candidate.blocks().get(0).original());
            assertTrue(store.readJob(id).errors().stream().anyMatch(e -> e.contains("手工保存") || e.contains("手工版本")));
        } finally {
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
}
