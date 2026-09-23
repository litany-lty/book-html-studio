package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U5 门禁：Qwen 有界小任务并发的结构断言。行为断言见 QwenRequestGateTest、
 * QwenTaskPlannerTest、QwenTextReviewClientTest、QwenAssistCoordinatorTest、
 * PageProcessorChunkedEnrichTest；本类防止回退到无界并发与整页直写。
 */
class UxU5QwenBoundedTest {

    private static String read(String relative) throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("pom.xml"))) p = p.getParent();
        if (p == null) fail("找不到仓库根目录（pom.xml）");
        return Files.readString(p.resolve(relative));
    }

    @Test void u5_singleGlobalCapNoPerPagePools() throws Exception {
        String gate = read("src/main/java/studio/bookhtml/service/QwenRequestGate.java");
        assertTrue(gate.contains("maxConcurrent"), "U5：全局上限单一来源");
        String coordinator = read("src/main/java/studio/bookhtml/service/QwenAssistCoordinator.java");
        assertTrue(coordinator.contains("private PriorityTaskScheduler pool"), "U5：独立共享的有界调度器，不与页编排同池");
        assertTrue(coordinator.contains("if (pool == null) pool = new PriorityTaskScheduler"), "U5：协调器只建立一个共享调度器");
        String scheduler=read("src/main/java/studio/bookhtml/service/PriorityTaskScheduler.java");
        assertTrue(scheduler.contains("queue.size()>="), "U5：排队数量必须有硬上限");
        assertTrue(scheduler.contains("backgroundLimit"), "U5：保留前台执行容量");
        assertTrue(coordinator.contains("scheduler.submit("), "U5：不用无界 common pool 承载付费请求");
        String processor = read("src/main/java/studio/bookhtml/service/PageProcessor.java");
        assertFalse(processor.contains("newFixedThreadPool"), "U5：不为每页新建线程池");
        assertFalse(processor.contains("supplyAsync"), "U5：不无界派发模型请求");
    }

    @Test void u5_chunksNeverSavePagesDirectly() throws Exception {
        for (String file : new String[]{
                "src/main/java/studio/bookhtml/service/QwenTextReviewClient.java",
                "src/main/java/studio/bookhtml/service/QwenAssistCoordinator.java",
                "src/main/java/studio/bookhtml/service/QwenTaskPlanner.java"}) {
            String source = read(file);
            assertFalse(source.contains("commitPage"), "U5：子任务不得直接保存整页：" + file);
            assertFalse(source.contains("writePage"), "U5：子任务不得直接写页：" + file);
        }
        String coordinator = read("src/main/java/studio/bookhtml/service/QwenAssistCoordinator.java");
        assertTrue(coordinator.contains("plannedOrder"), "U5：按计划顺序合并，不按返回先后");
    }

    @Test void u5_conservativeDefaultsAndRollback() throws Exception {
        String config = read("src/main/java/studio/bookhtml/config/QwenAssistProperties.java");
        assertTrue(config.contains("private boolean chunkedAssist = true"), "分组默认开启，显式关闭时可用旧整页路径");
        assertTrue(config.contains("maxConcurrentRequests = 3"), "U5：全局 3 为工程起点");
        assertTrue(config.contains("maxPhysicalCallsPerPageAttempt = 8"), "U5：单页尝试预算 8");
        String processor = read("src/main/java/studio/bookhtml/service/PageProcessor.java");
        assertTrue(processor.contains("tryChunkedAssist"), "分组优先，发出后失败不得重复整页计费");
        assertTrue(processor.contains("qwenLayout.assist"), "U5：旧整页路径保留");
    }

    @Test void u5_meteringWithoutForgedTokens() throws Exception {
        String review = read("src/main/java/studio/bookhtml/service/QwenTextReviewClient.java");
        String boundary = read("src/main/java/studio/bookhtml/service/QwenPhysicalCall.java");
        assertTrue(review.contains("QwenPhysicalCall.open"), "核对使用统一物理发送入口");
        assertTrue(boundary.contains("ledger.prepare"), "U5：发送前审计意图落盘");
        assertTrue(boundary.indexOf("ledger.prepare") < boundary.indexOf("transport.send"), "准备先于物理发送");
        assertTrue(boundary.indexOf("ledger.sending") < boundary.indexOf("transport.send"), "进入可能发送区间先于物理调用");
        assertTrue(review.contains("cacheReused"), "U5：缓存命中只计命中，不伪造 token");
        assertTrue(review.contains("UNKNOWN"), "U5：结果未知不盲目重发");
    }

    @Test void u5_noSilentProviderSwitching() throws Exception {
        String coordinator = read("src/main/java/studio/bookhtml/service/QwenAssistCoordinator.java");
        assertFalse(coordinator.contains("fallbackOrder"), "U5：失败不自动切更多供应商");
        String review = read("src/main/java/studio/bookhtml/service/QwenTextReviewClient.java");
        assertFalse(review.contains("fallbackOrder"), "U5：核对失败保留原文，不切通道");
    }
}
