package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A1-05：数据目录租约——显式复用、可释放、别名同一、崩溃可接管。 */
class DataDirectoryLeaseTest {
    @TempDir Path temp;

    private AppProperties config(Path data) {
        return new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, false);
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static String javaBin() {
        return System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
    }

    private static final class Probe {
        final Process process;
        final StringBuilder output = new StringBuilder();
        Probe(Process process) { this.process = process; }
    }

    private Probe startProbe(Path data, String... extra) throws Exception {
        String[] command = new String[extra.length + 5];
        command[0] = javaBin();
        command[1] = "-cp";
        command[2] = System.getProperty("java.class.path");
        command[3] = "studio.bookhtml.store.DataDirLockProbe";
        command[4] = data.toString();
        System.arraycopy(extra, 0, command, 5, extra.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return new Probe(process);
    }

    private static String readAvailable(Process process) throws Exception {
        StringBuilder out = new StringBuilder();
        long deadline = System.currentTimeMillis() + 15000;
        var input = process.getInputStream();
        byte[] buffer = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                int n = input.read(buffer, 0, Math.min(buffer.length, input.available()));
                if (n > 0) out.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                if (out.toString().contains("\n")) break;
            } else if (!process.isAlive()) {
                int n;
                while ((n = input.read(buffer)) > 0) out.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                break;
            } else {
                Thread.sleep(50);
            }
        }
        return out.toString().trim();
    }

    @Test void secondProcessRefusedWhileFirstHolds() throws Exception {
        Path data = temp.resolve("l01");
        Files.createDirectories(data);
        BookStore first = new BookStore(config(data), mapper());
        Probe probe = startProbe(data);
        try {
            String output = readAvailable(probe.process);
            assertTrue(probe.process.waitFor(30, TimeUnit.SECONDS), "探针必须退出");
            assertEquals(2, probe.process.exitValue(), "第二个进程必须拒绝：" + output);
            assertTrue(output.contains("另一进程"), "拒绝原因必须明确：" + output);
            // 原实例仍可读写
            String id = UUID.randomUUID().toString();
            first.createBookDirectory(id);
            first.writeBook(new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
            assertEquals("t", first.readBook(id).title());
        } finally {
            probe.process.destroyForcibly();
            first.close();
        }
    }

    @Test void sameJvmSharesLeaseAndThirdProcessStillBlocked() throws Exception {
        Path data = temp.resolve("l02");
        Files.createDirectories(data);
        BookStore first = new BookStore(config(data), mapper());
        BookStore second = new BookStore(config(data), mapper());
        try {
            // 显式复用：两边读写互通
            String id = UUID.randomUUID().toString();
            first.createBookDirectory(id);
            first.writeBook(new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
            first.writePage(id, Page.pending(1, 600, 800), false);
            assertEquals(1, second.readPage(id, 1).pageNumber());
            second.writePage(id, new Page(1, 600, 800, "READY", "local", List.of(), List.of(), false, null), false);
            assertEquals("READY", first.readPage(id, 1).status());
            // 只要任一有效实例存活，第三进程始终被阻止
            Probe probe = startProbe(data);
            try {
                String output = readAvailable(probe.process);
                assertTrue(probe.process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(2, probe.process.exitValue(), "第三进程必须拒绝：" + output);
            } finally {
                probe.process.destroyForcibly();
            }
        } finally {
            second.close();
            first.close();
        }
    }

    @Test void lastCloseReleasesLock() throws Exception {
        Path data = temp.resolve("l03");
        Files.createDirectories(data);
        int before = DataDirectoryLease.registrationsForTest();
        BookStore first = new BookStore(config(data), mapper());
        BookStore second = new BookStore(config(data), mapper());
        first.close();
        // 还有引用时锁仍在
        Probe blocked = startProbe(data);
        String refused;
        try {
            refused = readAvailable(blocked.process);
            assertTrue(blocked.process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(2, blocked.process.exitValue(), "引用未清零不得释放：" + refused);
        } finally {
            blocked.process.destroyForcibly();
        }
        second.close();
        assertEquals(before, DataDirectoryLease.registrationsForTest(), "registry 不得泄漏");
        Probe probe = startProbe(data);
        try {
            String output = readAvailable(probe.process);
            assertTrue(probe.process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, probe.process.exitValue(), "释放后新实例必须成功：" + output);
        } finally {
            probe.process.destroyForcibly();
        }
    }

    @Test void symlinkAliasSharesSameLease() throws Exception {
        Path data = temp.resolve("l04");
        Files.createDirectories(data);
        Path link = temp.resolve("l04-link");
        try {
            Files.createSymbolicLink(link, data);
        } catch (UnsupportedOperationException e) {
            System.out.println("SKIP symlink unsupported");
            return;
        }
        BookStore first = new BookStore(config(data), mapper());
        BookStore second = new BookStore(config(link), mapper());
        try {
            String id = UUID.randomUUID().toString();
            first.createBookDirectory(id);
            first.writeBook(new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
            assertEquals("t", second.readBook(id).title());
            Probe probe = startProbe(link);
            try {
                String output = readAvailable(probe.process);
                assertTrue(probe.process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(2, probe.process.exitValue(), "别名不得绕开互斥：" + output);
            } finally {
                probe.process.destroyForcibly();
            }
        } finally {
            second.close();
            first.close();
        }
    }

    @Test void killedHolderReleasesForRestart() throws Exception {
        Path data = temp.resolve("l05");
        Files.createDirectories(data);
        Probe holder = startProbe(data, "hold");
        try {
            String output = readAvailable(holder.process);
            assertTrue(output.contains("HOLDING"), "持有者必须先拿到锁：" + output);
            assertTrue(holder.process.isAlive());
            // 模拟崩溃：强杀，不给 close 机会
            holder.process.destroyForcibly();
            assertTrue(holder.process.waitFor(15, TimeUnit.SECONDS));
            BookStore restarted = new BookStore(config(data), mapper());
            try {
                String id = UUID.randomUUID().toString();
                restarted.createBookDirectory(id);
                restarted.writeBook(new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
                assertEquals("t", restarted.readBook(id).title());
            } finally {
                restarted.close();
            }
        } finally {
            holder.process.destroyForcibly();
        }
    }
}
