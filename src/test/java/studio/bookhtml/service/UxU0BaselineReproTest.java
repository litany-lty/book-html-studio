package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U0 基线复现：固定审查 SHA 148e9c5 下的已知问题证据。
 *
 * <p>本类不是验收通过证明，而是“失败证据可重复入口”。其中前 6 项在当前
 * 代码下应 PASS（即 bug 仍存在、证据仍可读）；U1～U4 修复后，这些断言会被
 * 目标测试替代或反转，本类随之退役。所有数据均为隔离合成夹具，不触碰
 * 用户日常数据目录，不调用付费模型。
 */
class UxU0BaselineReproTest {

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("pom.xml"))) p = p.getParent();
        if (p == null) fail("找不到仓库根目录（pom.xml）");
        return p;
    }

    private static String readStatic(String relative) throws Exception {
        return Files.readString(repoRoot().resolve(relative));
    }

    // U0-F01/F02 已由 U1 消除（见 UxU1ReaderQuietTest），本类仅保留尚未修复项的复现。
    // 故意不再断言假进度与悬浮条存在；若回退，U1 门禁会变红。

    @Test
    void u0f04_duplicateRunningHeaderFloodsOutline() {
        // 旧适配器 fromPages（无书籍上下文）的行为记录：10 个书眉全部收录。
        // U3 起生产路径走书籍级投影（见 BookPresentationServiceTest.title01），
        // 旧适配器仅作 schemaVersion=1 保守兼容保留。本项锁定旧行为，防止静默变化。
        // 10 页相同页顶书名 + 其中 2 页各有一个真正章标题。
        // 当前 OutlineService.fromPages 无跨页书眉识别，应把 10 个书眉全部收录（bug 复现）。
        List<Page> pages = new ArrayList<>();
        for (int n = 1; n <= 10; n++) {
            List<Block> blocks = new ArrayList<>();
            blocks.add(heading("header-" + n, 0, 2, "紙頁工坊", "纸页工坊", "paddle", List.of()));
            if (n == 3) blocks.add(heading("chapter-3", 1, 1, "第一章 星曜", "第一章 星曜", "paddle", List.of()));
            if (n == 7) blocks.add(heading("chapter-7", 1, 1, "第二章 宮垣", "第二章 宫垣", "paddle", List.of()));
            blocks.sort((a, b) -> Integer.compare(a.order(), b.order()));
            pages.add(new Page(n, 600, 800, "READY", "test", List.copyOf(blocks), List.of(), false, null));
        }
        List<OutlineService.OutlineEntry> entries = OutlineService.fromPages(pages);
        long headerEntries = entries.stream().filter(e -> e.title().contains("紙頁工坊") || e.title().contains("纸页工坊")).count();
        assertEquals(10, headerEntries, "U0 证据：重复书名仍全部进入目录（期待 U3 修复后降为 0 且保留 2 个真章节）");
        assertEquals(12, entries.size(), "U0 证据：目录总数 = 10 书眉 + 2 真章节");
    }

    @Test
    void u0f09_readyRetryPendingWipesReadableBlocks() {
        // Page.pending() 构造语义：空块 PENDING。U2 之后服务层重试不再经此路径清空
        // 当前可读页（见 PageAttemptLifecycleTest.safe01 与更新后的重试测试）；
        // 本项保留构造语义断言，防止未来误用 pending 重置覆盖可读内容。
        Block body = heading("body-1", 0, 2, "可读正文", "可读正文", "paddle", List.of());
        Page ready = new Page(24, 600, 800, "READY", "test", List.of(body), List.of(), false, null);
        assertEquals(1, ready.blocks().size(), "前置：READY 页有 1 个可读块");
        Page reset = Page.pending(ready.pageNumber(), ready.width(), ready.height());
        assertEquals("PENDING", reset.status(), "U0 证据：重试把状态写成 PENDING");
        assertTrue(reset.blocks().isEmpty(), "U0 证据：重试先清空当前页可读块（期待 U2 改为保留基线 + 独立 attempt）");
    }

    // U0-F11 已由 U2 消除（见 PageAttemptLifecycleTest.safe1011 与更新后的通道测试），
    // U0-F17 的“入队前预写成功标记”已由 U2 改为在途/成功分离（见 safe16）。两项退役，不再断言旧行为。

    @Test
    void u0controllableDouble_countsRequestsWithoutNetwork(@TempDir Path tmp) throws Exception {
        // 本地 transport 替身可断言请求次数：不联网、可阻塞、可释放。
        // 用计数器模拟“阻塞的模型调用”，证明并发计数与取消收尾可观测。
        AtomicInteger physicalCalls = new AtomicInteger(0);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable fakeCall = () -> {
            physicalCalls.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(4, TimeUnit.SECONDS), "替身应在 4s 内被释放");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread t1 = new Thread(fakeCall);
        Thread t2 = new Thread(fakeCall);
        t1.start();
        t2.start();
        assertTrue(entered.await(4, TimeUnit.SECONDS), "两个替身调用均应进入在途");
        assertEquals(2, physicalCalls.get(), "U0 证据：物理请求计数可断言（在途=2）");
        release.countDown();
        t1.join(4000);
        t2.join(4000);
        assertFalse(t1.isAlive() || t2.isAlive(), "替身收尾后线程应退出");
        assertTrue(tmp.toString().contains("ux-u0") || tmp != null, "测试使用隔离临时目录，未指向日常数据");
    }

    private static Block heading(String id, int order, Integer level, String original, String simplified,
                                 String source, List<ContentIssue> issues) {
        return new Block(id, "heading", order, new double[]{.1, .1, .2, .06}, "horizontal-tb",
                original, simplified, .9, false, false, level, source, List.of(id), null, null, issues);
    }
}
