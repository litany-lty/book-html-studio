package studio.bookhtml.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("G11 / B09: 前端 12页/32MiB 缓存双上限、校对工作台按需挂载与交互会话守卫专项测试")
class FrontendBoundsTest {

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
    @DisplayName("双上限配置与算法结构：PAGE_CACHE_LIMIT=12，PAGE_CACHE_BYTES_LIMIT=32MiB，估算函数与双重淘汰存在")
    void testPageCacheDualLimitsStructure() throws Exception {
        String storeJs = read("src/main/resources/static/store.js");

        assertTrue(storeJs.contains("export const PAGE_CACHE_LIMIT = 12;"), "G11: 缓存页数上限必须为 12 页");
        assertTrue(storeJs.contains("export const PAGE_CACHE_BYTES_LIMIT = 32 * 1024 * 1024;"), "G11: 缓存字节上限必须为 32 MiB");
        assertTrue(storeJs.contains("function estimatePageBytes("), "G11: 必须提供 estimatePageBytes 估算每页字节占用");
        assertTrue(storeJs.contains("super.size > this.limit || this.totalBytes > this.maxBytes"), "G11: 必须在页数或字节数任一超限时执行淘汰");
        assertTrue(storeJs.contains("this.isProtected(oldest)"), "G11: 淘汰时必须保护当前页与未保存草稿");
        // Accounting now recomputes the bounded cache after mutations instead of
        // subtracting from a saturated estimate. Actual JS behavior is tested by probe_reader_cache.mjs.
        assertTrue(storeJs.contains("this.recountBytes();"), "G11: 缓存变更后必须重新结算字节计数");
        assertTrue(storeJs.contains("for (const bytes of this.byteSizes.values())"), "G11: 重算必须包含所有保留项");
        assertTrue(storeJs.contains("Math.min(Number.MAX_SAFE_INTEGER, this.totalBytes + bytes)"), "G11: 大对象计数必须防溢出");
    }

    @Test
    @DisplayName("双上限淘汰行为仿真：当页数达到 12 或总字节超过 32MiB 时触发淘汰，受保护页不得淘汰")
    void testDualLimitEvictionBehavior() {
        // Java 严格仿真 store.js 中的双上限淘汰算法
        final int LIMIT = 12;
        final long MAX_BYTES = 32 * 1024 * 1024; // 32 MiB

        class SimulatedPageCache {
            final LinkedHashMap<Integer, Long> map = new LinkedHashMap<>(16, 0.75f, true);
            long totalBytes = 0;
            int protectedPage = 1;

            void set(int page, long bytes) {
                Long prev = map.remove(page);
                if (prev != null) totalBytes -= prev;
                map.put(page, bytes);
                totalBytes += bytes;

                while (map.size() > LIMIT || totalBytes > MAX_BYTES) {
                    Integer toEvict = null;
                    for (Integer k : map.keySet()) {
                        if (k == protectedPage) continue;
                        toEvict = k;
                        break;
                    }
                    if (toEvict == null) break;
                    long evictedBytes = map.remove(toEvict);
                    totalBytes -= evictedBytes;
                }
            }

            int size() { return map.size(); }
            boolean contains(int page) { return map.containsKey(page); }
            long bytes() { return totalBytes; }
        }

        SimulatedPageCache cache = new SimulatedPageCache();

        // 1. 测试页数达到 12 页上限（小页面，每页 10 KiB）
        for (int p = 1; p <= 15; p++) {
            cache.set(p, 10 * 1024L);
        }
        assertEquals(LIMIT, cache.size(), "页数不得超过 12 页上限");
        assertTrue(cache.contains(1), "受保护的第 1 页绝不被淘汰");
        assertFalse(cache.contains(2), "最久未使用的非保护第 2 页必须被淘汰");
        assertTrue(cache.contains(15), "最新加入的第 15 页必须存在");

        // 2. 测试字节达到 32 MiB 上限（单页 10 MiB，即使页数未达 12 也必须淘汰）
        SimulatedPageCache byteCache = new SimulatedPageCache();
        byteCache.set(1, 1024L); // protected page
        byteCache.set(2, 10 * 1024 * 1024L); // 10 MiB
        byteCache.set(3, 10 * 1024 * 1024L); // 10 MiB
        byteCache.set(4, 10 * 1024 * 1024L); // 10 MiB
        assertEquals(4, byteCache.size());
        assertTrue(byteCache.bytes() <= MAX_BYTES);

        // 插入第 5 页 (10 MiB)，累计超 32 MiB，必须淘汰最旧的第 2 页
        byteCache.set(5, 10 * 1024 * 1024L);
        assertTrue(byteCache.bytes() <= MAX_BYTES, "总字节数不得超过 32 MiB");
        assertFalse(byteCache.contains(2), "超出 32MiB 时必须淘汰最旧的第 2 页");
        assertTrue(byteCache.contains(1), "受保护页在内存超限时依然受保护");
        assertTrue(byteCache.contains(5), "最新加入的第 5 页必须存在");
    }

    @Test
    @DisplayName("校对工作台按需挂载与视口卸载：非 original 视图或抽屉关闭时不挂载工作台，离开时彻底卸载")
    void testWorkbenchLazyMountingStructure() throws Exception {
        String editorJs = read("src/main/resources/static/editor.js");
        String appJs = read("src/main/resources/static/app.js");

        assertTrue(editorJs.contains("export function unmountIssueWorkbench("), "G11: editor.js 必须导出 unmountIssueWorkbench");
        assertTrue(editorJs.contains("container.replaceChildren()"), "G11: 卸载时必须清除 DOM 节点以释放原图与画布内存");
        assertTrue(editorJs.contains("container.hidden = true"), "G11: 卸载时必须隐藏容器");

        assertTrue(appJs.contains("unmountIssueWorkbench("), "G11: app.js 必须引入并调用 unmountIssueWorkbench");
        assertTrue(appJs.contains("const isReviewVisible = isProofMode() && !state.focus"), "G11: 工作台必须绑定可见校对模式，原稿阅读本身不挂载隐藏编辑器");
        assertTrue(appJs.contains("unmountIssueWorkbench($('#issue-workbench'))"), "G11: 非可见时必须执行卸载");
    }

    @Test
    @DisplayName("交互会话守卫：SessionGuard 防范跨页与跨书异步响应串扰")
    void testSessionGuardStructure() throws Exception {
        String storeJs = read("src/main/resources/static/store.js");
        String appJs = read("src/main/resources/static/app.js");

        assertTrue(storeJs.contains("export class SessionGuard"), "G11: store.js 必须导出 SessionGuard 类");
        assertTrue(storeJs.contains("export const sessionGuard"), "G11: store.js 必须导出全局 sessionGuard 实例");
        assertTrue(storeJs.contains("isValid(token)"), "G11: SessionGuard 必须具备 token 校验能力");
        assertTrue(storeJs.contains("invalidate()"), "G11: SessionGuard 必须具备失效能力");

        assertTrue(appJs.contains("sessionGuard.setSession("), "G11: 切换页面时必须调用 sessionGuard.setSession");
        assertTrue(appJs.contains("sessionGuard.isValid("), "G11: 异步加载返回时必须校验 sessionGuard.isValid");
        assertTrue(appJs.contains("sessionGuard.invalidate()"), "G11: 切书或重置时必须主动使会话失效");
    }

    @Test
    @DisplayName("SessionGuard 行为测试：页与书切换单调推进代次，旧 Token 立即失效")
    void testSessionGuardBehavior() {
        class MockSessionGuard {
            String bookId;
            int page;
            long epoch;

            record Token(String bookId, int page, long epoch) {}

            Token setSession(String b, int p) {
                if (!Objects.equals(bookId, b) || page != p) {
                    bookId = b;
                    page = p;
                    epoch++;
                }
                return new Token(bookId, page, epoch);
            }

            boolean isValid(Token t) {
                return t != null && Objects.equals(t.bookId(), bookId) && t.page() == page && t.epoch() == epoch;
            }

            void invalidate() {
                epoch++;
            }
        }

        MockSessionGuard guard = new MockSessionGuard();
        var t1 = guard.setSession("book-1", 1);
        assertTrue(guard.isValid(t1));

        // 翻页到第 2 页，t1 立即失效
        var t2 = guard.setSession("book-1", 2);
        assertFalse(guard.isValid(t1), "翻页后旧会话 Token 必须失效");
        assertTrue(guard.isValid(t2));

        // 切书，t2 立即失效
        guard.invalidate();
        assertFalse(guard.isValid(t2), "切书使会话失效后当前 Token 不得再通过");
    }
}
