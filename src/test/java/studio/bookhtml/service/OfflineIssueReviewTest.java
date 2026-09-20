package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R02：离线校对记录的延迟恢复/跨页保留/旧格式升级/配额失败，
 * 在 Node 最小 DOM 桩中执行真实的 issue-review.js。
 */
class OfflineIssueReviewTest {
    @Test void offlineReviewStorageContractHolds() throws Exception {
        Path harness = Paths.get("src/test/js/offline-review-harness.cjs").toAbsolutePath();
        Process process = new ProcessBuilder("node", harness.toString())
                .redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, "离线校对回归必须通过：\n" + log);
        assertTrue(log.contains("ALL_OFFLINE_REVIEW_CASES_PASS"), "缺少通过标记：\n" + log);
    }
}
