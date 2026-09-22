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
        assertTrue(coordinator.contains("独立有界出站池"), "U5：独立有界出站池，不与页编排同池");
        assertTrue(coordinator.contains("pool())"), "U5：不用无界 common pool 承载付费请求");
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
        assertTrue(config.contains("private boolean chunkedAssist = true"), "分组默认开启，仍需既有云外发授权");
        assertTrue(config.contains("setChunkedAssist"), "操作员仍可明确关闭分组以回滚");
        assertTrue(read("src/main/resources/application.properties").contains("QWEN_CHUNKED_ASSIST:true"),
                "部署参数可覆盖默认值，不硬编码移除回滚入口");
        assertTrue(config.contains("maxConcurrentRequests = 3"), "U5：全局 3 为工程起点");
        assertTrue(config.contains("maxPhysicalCallsPerPageAttempt = 8"), "U5：单页尝试预算 8");
        String processor = read("src/main/java/studio/bookhtml/service/PageProcessor.java");
        assertTrue(processor.contains("tryChunkedAssist"), "分组优先；实际请求失败保留基线，不重复计费调用整页");
        assertTrue(processor.contains("qwenLayout.assist"), "U5：旧整页路径保留");
    }

    @Test void u5_meteringWithoutForgedTokens() throws Exception {
        String review = read("src/main/java/studio/bookhtml/service/QwenTextReviewClient.java");
        assertTrue(review.contains("usage.start"), "U5：每次物理请求记账");
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
