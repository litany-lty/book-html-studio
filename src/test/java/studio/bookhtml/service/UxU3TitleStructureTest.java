package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U3 门禁：目录与书名识别改为书籍级结构投影。行为断言见
 * {@link BookPresentationServiceTest}；本类防止回退到字符串黑名单、
 * 前端独立规则与旧单页反灌。
 */
class UxU3TitleStructureTest {

    private static String read(String relative) throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("pom.xml"))) p = p.getParent();
        if (p == null) fail("找不到仓库根目录（pom.xml）");
        return Files.readString(p.resolve(relative));
    }

    @Test
    void u3_noGlobalTitleBlacklistOrSourcePrefixSemantics() throws Exception {
        String service = read("src/main/java/studio/bookhtml/service/BookPresentationService.java");
        assertFalse(service.contains("\"paddle-span\""), "U3：供应商来源前缀不得作为语义结论");
        assertTrue(service.contains("只描述提取路径"), "U3：来源只描述提取路径，不作语义结论");
        assertFalse(service.contains("title.equals(book"), "U3：不得用标题相等全局删除");
        assertTrue(service.contains("FIRST_OCCURRENCE") || service.contains("firstOccurrence"),
                "U3：分类落到某页某块，首现页暂留候选");
        assertTrue(service.contains("matchesBookTitle"), "U3：封面书名只看第一页 + 书名弱提示");
    }

    @Test
    void u3_outlineSummaryAndSnapshotShareOneProjection() throws Exception {
        String outlines = read("src/main/java/studio/bookhtml/service/OutlineService.java");
        assertTrue(outlines.contains("setPresentation"), "U3：OutlineService 接入统一投影");
        String books = read("src/main/java/studio/bookhtml/service/BookService.java");
        assertTrue(books.contains("pageSummary"), "U3：摘要标题使用同一投影结果");
        String windows = read("src/main/java/studio/bookhtml/service/ReadingWindowService.java");
        assertTrue(windows.contains("snapshotOutline"), "U3：随读增量目录按画像版本取投影");
        assertTrue(windows.contains("profileRevision"), "U3：快照携带画像版本");
    }

    @Test
    void u3_pagePayloadAndOutlineVersionedWithCapabilities() throws Exception {
        String api = read("src/main/java/studio/bookhtml/api/ApiController.java");
        assertTrue(api.contains("\"presentation\""), "U3：页面载荷附带只读展示投影");
        assertTrue(api.contains("schemaVersion\",2") || api.contains("schemaVersion\", 2"),
                "U3：目录 v2 对象");
        assertTrue(api.contains("presentationV2"), "U3：能力声明");
        assertTrue(Files.exists(Path.of("").toAbsolutePath().resolve(
                "src/main/java/studio/bookhtml/api/PresentationController.java")), "U3：覆盖入口独立控制器");
    }

    @Test
    void u3_exportFreezesProfileBeforePageFiltering() throws Exception {
        String export = read("src/main/java/studio/bookhtml/service/ExportService.java");
        assertTrue(export.contains("frozenProfile") || export.contains("Frozen"),
                "U3：导出冻结同一画像再按页筛选");
    }

    @Test
    void u3_readerConsumesProjectionWithManualGuard() throws Exception {
        String reader = read("src/main/resources/static/reader.js");
        assertTrue(reader.contains("presentationMap") || reader.contains("presentation"),
                "U3：阅读器消费统一投影，不另写页眉规则");
        assertTrue(reader.contains("manual"), "U3：人工块不受阅读层隐藏");
        String app = read("src/main/resources/static/app.js");
        assertTrue(app.contains("readingMetadataProfiles"), "U3：旧响应不把书眉写回目录");
        assertTrue(app.contains("renderPageStructure"), "U3：校对模式页面结构覆盖入口");
        String html = read("src/main/resources/static/index.html");
        assertTrue(html.contains("page-structure"), "U3：页面结构按需进入");
    }
}
