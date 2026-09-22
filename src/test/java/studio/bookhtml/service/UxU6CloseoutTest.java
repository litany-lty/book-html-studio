package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U6 门禁：复杂版式保真降级、离线一致、性能收口的结构断言。
 * 行为断言见 LayoutFallbackAndCacheTest 与 ExportServiceTest 新增用例。
 */
class UxU6CloseoutTest {

    private static String read(String relative) throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("pom.xml"))) p = p.getParent();
        if (p == null) fail("找不到仓库根目录（pom.xml）");
        return Files.readString(p.resolve(relative));
    }

    @Test void u6_fallbackChainExists() throws Exception {
        String service = read("src/main/java/studio/bookhtml/service/BookPresentationService.java");
        assertTrue(service.contains("\"PAGE_IMAGE\""), "U6：几何缺失整页原稿降级");
        assertTrue(service.contains("\"REGION_IMAGE\""), "U6：视觉区区域原图降级");
        assertTrue(service.contains("\"NONE\""), "U6：可靠重排才重排");
        String reader = read("src/main/resources/static/reader.js");
        assertTrue(reader.contains("PAGE_IMAGE"), "U6：阅读器执行降级，不拼错误正文");
    }

    @Test void u6_projectionUsesNormalizedView() throws Exception {
        String service = read("src/main/java/studio/bookhtml/service/BookPresentationService.java");
        assertTrue(service.contains("ReadingStructureNormalizer.normalize"),
                "U6：已有图表/对开规则适配为展示角色，不丢弃");
        assertTrue(service.contains("normalizedType"), "U6：角色判定用归一化视角，内容用存储块");
    }

    @Test void u6_cachesInvalidatedByStoreNotifications() throws Exception {
        String store = read("src/main/java/studio/bookhtml/store/BookStore.java");
        assertTrue(store.contains("addChangeListener"), "U6：派生索引失效走存储通知");
        assertTrue(store.contains("notifyBookChanged"), "U6：页变更唯一出口通知");
        String books = read("src/main/java/studio/bookhtml/service/BookService.java");
        assertTrue(books.contains("PageStatisticsCache"), "U6：书架统计采用有界增量投影");
        String statistics = read("src/main/java/studio/bookhtml/service/PageStatisticsCache.java");
        assertTrue(statistics.contains("addPageChangeListener"), "统计由提交事件更新，不依赖页面轮询");
        assertTrue(statistics.contains("sourceEpoch"), "并发冷扫描不能回填旧统计");
    }

    @Test void u6_offlineUsesFrozenProfile() throws Exception {
        String export = read("src/main/java/studio/bookhtml/service/ExportService.java");
        assertTrue(export.contains("frozenProfile"), "U6：导出冻结一致快照");
    }

    @Test void u6_matrixDocumentsUnverifiedGaps() throws Exception {
        String matrix = read("docs/verification/ux-u6-layout-matrix.md");
        assertTrue(matrix.contains("未验"), "U6：未验证项明确标出，不填全部支持");
        assertTrue(matrix.contains("LAY") || matrix.contains("场景"), "U6：场景矩阵逐项有退路");
    }
}
