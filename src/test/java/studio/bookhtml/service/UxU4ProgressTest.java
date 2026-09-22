package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U4 门禁：可读基线与真实阶段的结构断言。行为断言见
 * {@link ProcessingProgressTest}；本类防止回退到内存即完成、
 * 小任务直写整页与假进度。
 */
class UxU4ProgressTest {

    private static String read(String relative) throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("pom.xml"))) p = p.getParent();
        if (p == null) fail("找不到仓库根目录（pom.xml）");
        return Files.readString(p.resolve(relative));
    }

    @Test
    void u4_baselinePublishedOnlyAfterDurableCommit() throws Exception {
        String jobs = read("src/main/java/studio/bookhtml/service/JobService.java");
        assertTrue(jobs.contains("CommitOp.JOB_BASELINE"), "U4：基线提交走独立操作类别");
        assertTrue(jobs.contains("CommitOp.JOB_ENHANCEMENT"), "U4：增强提交走独立操作类别");
        assertTrue(jobs.contains("processBaseline"), "U4：基线与增强分离");
        assertTrue(jobs.contains("enrichBaseline"), "U4：增强只产候选");
        // 基线落盘（commitPage 成功）之后才宣布可读。
        int commitIdx = jobs.indexOf("CommitOp.JOB_BASELINE");
        int publishedIdx = jobs.indexOf("baselinePublished(running.bookId", commitIdx);
        assertTrue(commitIdx >= 0 && publishedIdx > commitIdx,
                "U4：内存收到结果不算完成，落盘才宣布可读");
    }

    @Test
    void u4_enrichmentNeverSavesPagesDirectly() throws Exception {
        String processor = read("src/main/java/studio/bookhtml/service/PageProcessor.java");
        assertTrue(processor.contains("EnrichResult"), "U4：增强返回候选结构");
        int enrichIdx = processor.indexOf("public EnrichResult enrichBaseline");
        assertTrue(enrichIdx >= 0);
        String enrichBody = processor.substring(enrichIdx, Math.min(processor.length(), enrichIdx + 9000));
        assertFalse(enrichBody.contains("commitPage"), "U4：子任务不得直接保存整页");
        assertFalse(enrichBody.contains("writePage"), "U4：子任务不得直接写页");
        String ops = read("src/main/java/studio/bookhtml/store/CommitOp.java");
        assertTrue(ops.contains("JOB_BASELINE") && ops.contains("JOB_ENHANCEMENT"),
                "U4：新提交操作逐一存在");
    }

    @Test
    void u4_progressContractAndIntentsExist() throws Exception {
        String progress = read("src/main/java/studio/bookhtml/service/ProcessingProgressService.java");
        assertTrue(progress.contains("lastProgressAt"), "U4：真实进展时间戳");
        assertTrue(progress.contains("MAX_TRACKED"), "U4：进度聚合有界");
        String jobs = read("src/main/java/studio/bookhtml/service/JobService.java");
        assertTrue(jobs.contains("reconcileAttemptIntents"), "U4：重启对照意图");
        assertTrue(jobs.contains("pageAttemptsPath"), "U4：意图持久化");
    }

    @Test
    void u4_frontendShowsRealStagesWithoutFakePercent() throws Exception {
        String app = read("src/main/resources/static/app.js");
        assertTrue(app.contains("stageLabels"), "U4：当前页显示真实阶段");
        assertTrue(app.contains("已核对"), "U4：固定计划显示任务单位数");
        assertTrue(app.contains("文字已可阅读，正在整理版面"), "U4：基线可读文案");
        assertTrue(app.contains("已结束"), "U4：终态显示已结束而非全成功");
        assertFalse(app.contains("getConvertingPagePct"), "U4：计时假进度不得回归");
    }

    @Test
    void u4_readableCountedSeparately() throws Exception {
        String response = read("src/main/java/studio/bookhtml/api/ReadingWindowResponse.java");
        assertTrue(response.contains("readablePages"), "U4：已可读页单独计数");
    }
}
