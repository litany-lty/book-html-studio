package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U1 门禁：阅读区安静化。断言假进度/悬浮浮层/脉冲/误导文案已移除，
 * 且保留停止/重试/刷新、保存/冲突与证据能力。
 *
 * <p>与 U0 的“复现存在”相反，本类断言“问题已消除”。若后续批次回退这些行为，
 * 本类应变红，而不是静默删除。
 */
class UxU1ReaderQuietTest {

    private static Path repoRoot() throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("pom.xml"))) p = p.getParent();
        if (p == null) fail("找不到仓库根目录（pom.xml）");
        return p;
    }

    private static String read(String relative) throws Exception {
        return Files.readString(repoRoot().resolve(relative));
    }

    @Test
    void u1_noFakeTimedProgress() throws Exception {
        String appJs = read("src/main/resources/static/app.js");
        assertFalse(appJs.contains("getConvertingPagePct"), "U1：计时假进度函数必须删除");
        assertFalse(appJs.contains("Math.min(92"), "U1：92% 模拟公式必须删除");
        assertFalse(appJs.contains("正在转化第"), "U1：标题不得再用“正在转化第 X 页·N%”");
        assertTrue(appJs.contains("正在识别第") || appJs.contains("正在识别本页"),
                "U1：未知进度只显示真实阶段");
    }

    @Test
    void u1_noFloatingPulseBar() throws Exception {
        String css = read("src/main/resources/static/styles.css");
        assertFalse(css.contains("pulseDot"), "U1：任务条脉冲动画必须删除");
        assertFalse(css.contains("pulse-glow"), "U1：徽标脉冲动画必须删除");
        assertFalse(css.contains("pulse-dot"), "U1：脉冲点样式必须删除");
        assertFalse(css.contains("reading-window-visible #reader-shell"), "U1：浮层补偿 padding 必须删除");
        assertTrue(css.contains("body[data-reader-mode=\"reading\"]"), "U1：阅读模式默认关闭校对栏");
        assertTrue(css.contains("prefers-reduced-motion"), "U1：保留 reduced-motion 约束");
    }

    @Test
    void u1_taskEntryQuietAndCapabilitiesKept() throws Exception {
        String appJs = read("src/main/resources/static/app.js");
        assertTrue(appJs.contains("任务 · ${activeCount}") || appJs.contains("任务 · "),
                "U1：唯一任务入口只显示活动任务数");
        // 能力保留：停止/刷新/重试入口仍在
        assertTrue(appJs.contains("readingWindow.stop"), "U1：保留停止能力");
        assertTrue(appJs.contains("refreshStatus"), "U1：保留刷新状态能力");
        assertTrue(appJs.contains("retryCurrentPage") || appJs.contains("reloadCurrentPage"), "U1：保留重试能力");
        String html = read("src/main/resources/static/index.html");
        assertTrue(html.contains("id=\"reading-window-stop\""), "U1：停止按钮保留");
        assertTrue(html.contains("id=\"reading-window-refresh\""), "U1：刷新按钮保留");
        assertTrue(html.contains("id=\"proof-toggle\""), "U1：校对显式入口存在");
    }

    @Test
    void u1_readingModeDefaultsAndAria() throws Exception {
        String html = read("src/main/resources/static/index.html");
        assertTrue(html.contains("data-reader-mode=\"reading\""), "U1：默认进入阅读模式");
        assertFalse(html.contains("<article id=\"paper\" class=\"paper\" aria-live"),
                "U1：正文整篇常驻 aria-live 必须移除");
        assertTrue(html.contains("原版排布"), "U1：视图“原貌 HTML”改名“原版排布”");
        assertTrue(html.contains(">阅读</button>"), "U1：视图“横排阅读”改名“阅读”");
        assertTrue(html.contains("quality-diagnostics"), "U1：技术信息进入诊断区");
        assertTrue(html.contains("翻页与阅读位置"), "U1：页码与阅读位置合并为同一导航");
    }

    @Test
    void u1_honestCopyAndInfoLayers() throws Exception {
        String readerJs = read("src/main/resources/static/reader.js");
        assertFalse(readerJs.contains("本页为空白页或仅含插图"), "空识别结果不得被断言为空白或纯图");
        assertTrue(readerJs.contains("emptyReadingMessage"), "空态必须区分图像证据与漏识");
        assertFalse(readerJs.contains("请在校对栏手工框选"), "U1：不得诱导纯图页手工框选");
        assertTrue(readerJs.contains("有待核对文字"), "U1：默认阅读只用轻量疑点标记");
        assertFalse(readerJs.contains("模型提示"), "U1：默认标签不再叫“模型提示”");
        assertTrue(readerJs.contains("qualityDiagnostics"), "U1：诊断详情保留完整技术信息");
        assertTrue(readerJs.contains("原版排布按识别坐标近似呈现"), "U1：原版排布说明改名");
        assertFalse(appJsContains(readerJs, "横排阅读稿"), "U1：授权文案不再称横排阅读稿");
    }

    @Test
    void u1_newModulesPresentWithoutCloudCalls() throws Exception {
        String mode = read("src/main/resources/static/reader-mode.js");
        String center = read("src/main/resources/static/task-center.js");
        String anchor = read("src/main/resources/static/reading-anchor.js");
        assertTrue(mode.contains("readerMode"), "U1：reader-mode 管理阅读/校对模式");
        assertTrue(center.contains("任务 · "), "U1：task-center 唯一入口文案");
        assertTrue(anchor.contains("fallbackId"), "U1：reading-anchor 邻近节点回退");
        for (String content : new String[]{mode, center, anchor}) {
            assertFalse(content.contains("fetch("), "U1：新模块不得直接调用云识别");
            assertFalse(content.contains("api.page"), "U1：新模块不得直接调用页面 API");
        }
        String appJs = read("src/main/resources/static/app.js");
        assertTrue(appJs.contains("initReaderMode"), "U1：app 接入阅读模式");
        assertTrue(appJs.contains("restoreAnchor") || appJs.contains("recordAnchor"), "U1：app 使用阅读锚点");
    }

    @Test
    void u1_existingVerificationEntersProofMode() throws Exception {
        String script = read("scripts/verification/cdp_reader_ux.py");
        assertTrue(script.contains("proof-toggle"), "U1：既有 UX 脚本显式进入校对模式");
        assertTrue(script.contains("默认阅读模式"), "U1：脚本先断言默认阅读模式");
    }

    private static boolean appJsContains(String ignored, String token) throws Exception {
        return read("src/main/resources/static/app.js").contains(token)
                || read("src/main/resources/static/reader.js").contains(token);
    }
}
