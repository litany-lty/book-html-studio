package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U2 门禁：任务生命周期、重试与授权的结构断言。行为断言见
 * {@link PageAttemptLifecycleTest} 与更新后的 {@code ReadingWindowServiceTest}；
 * 本类防止安全路径被回退（重引入清空重试、提前释放、强杀、越权并行）。
 */
class UxU2TaskSafetyTest {

    private static String read(String relative) throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("pom.xml"))) p = p.getParent();
        if (p == null) fail("找不到仓库根目录（pom.xml）");
        return Files.readString(p.resolve(relative));
    }

    @Test
    void u2_retryNeverWipesReadableSnapshot() throws Exception {
        String windows = read("src/main/java/studio/bookhtml/service/ReadingWindowService.java");
        assertFalse(windows.contains("Page.pending("), "U2：重试路径不得再写空 PENDING 清空快照");
        assertTrue(windows.contains("requestReprocess"), "U2：重试经统一准入创建独立 attempt");
        assertTrue(windows.contains("retryPages"), "U2：显式重试页进入重试集，force 派发但仍受容量/授权约束");
    }

    @Test
    void u2_cancelDoesNotReleaseRegistrationEarlyAndRestoreSurvivesCancel() throws Exception {
        String jobs = read("src/main/java/studio/bookhtml/service/JobService.java");
        assertTrue(jobs.contains("mayRestore"), "U2：取消后仍可恢复旧可读版本");
        assertTrue(jobs.contains("mayPublish"), "U2：新内容发布与取消恢复使用不同资格");
        assertTrue(jobs.contains("ownsAttempt"), "U2：归属与取消拆开判断");
        assertFalse(jobs.contains("stillCurrentReserved"), "U2：混用判断必须删除");
        // cancelReadingPage 方法体内不得再出现提前删除登记。
        int cancelIdx = jobs.indexOf("public synchronized void cancelReadingPage");
        assertTrue(cancelIdx >= 0);
        String cancelBody = jobs.substring(cancelIdx, Math.min(jobs.length(), cancelIdx + 1200));
        assertFalse(cancelBody.contains("activeReserved.remove"), "U2：取消不得提前释放登记");
    }

    @Test
    void u2_noPreemptiveKillOfInFlightRequests() throws Exception {
        String windows = read("src/main/java/studio/bookhtml/service/ReadingWindowService.java");
        assertFalse(windows.contains("cancelReadingPage(s.reservation, furthest)"),
                "U2：默认不得强杀最远在途云请求腾位置");
        assertTrue(windows.contains("DRAIN"), "U2：已发出请求允许完成并缓存（DRAIN 语义）");
    }

    @Test
    void u2_channelsFilteredBySessionAuthorization() throws Exception {
        String windows = read("src/main/java/studio/bookhtml/service/ReadingWindowService.java");
        assertTrue(windows.contains("allowedProviders"), "U2：本次任务授权快照");
        assertFalse(windows.contains("channels.add(secondary)"), "U2：已配置次通道不得自动加入派发");
        assertTrue(windows.contains("parallelProvidersAllowed"), "U2：并行分发需显式授权（默认关闭）");
    }

    @Test
    void u2_attemptRegistryAndReprocessCommandExist() throws Exception {
        String attempt = read("src/main/java/studio/bookhtml/domain/PageAttempt.java");
        assertTrue(attempt.contains("expectedRevision"), "U2：attempt 绑定期望版本");
        assertTrue(attempt.contains("allowedCommitOps"), "U2：attempt 绑定允许的提交操作");
        assertTrue(attempt.contains("lifecycle"), "U2：attempt 有生命周期");
        String request = read("src/main/java/studio/bookhtml/api/PageReprocessRequest.java");
        assertTrue(request.contains("clientOperationId"), "U2：重处理命令幂等键");
        assertTrue(request.contains("explicitOverwriteAuthorization"), "U2：覆盖需显式授权");
        String jobs = read("src/main/java/studio/bookhtml/service/JobService.java");
        assertTrue(jobs.contains("attemptSnapshot"), "U2：attempt 登记测试可见");
    }

    @Test
    void u2_settleUnifiedAndWindowsNamed() throws Exception {
        String windows = read("src/main/java/studio/bookhtml/service/ReadingWindowService.java");
        assertTrue(windows.contains("Duration.ofMillis(1000)"), "U2：停留阈值统一 1000ms");
        String js = read("src/main/resources/static/reading-window.js");
        assertTrue(js.contains("readyInFlight"), "U2：在途/成功标记分离");
        assertTrue(js.contains("cacheWindow"), "U2：只读缓存窗口与云派发窗口命名区分");
        assertTrue(Files.exists(Path.of("").toAbsolutePath().resolve("scripts/verification/probe_ready_fetch_retry.js")),
                "U2：ready GET 重试探针存在");
    }
}
