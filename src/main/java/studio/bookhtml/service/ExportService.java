package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportService {
    private static final int EXPORT_IMAGE_WIDTH = 1800;
    private static final Set<String> IMAGE_BLOCK_TYPES = Set.of("figure", "table", "formula");
    private static final Set<String> FACSIMILE_TEXT_TYPES = Set.of("text", "heading", "advertisement", "caption", "page-number");

    private final BookService books;
    private final BookStore store;
    private final PdfService pdf;
    private final ObjectMapper json;
    private final IssueImageService issueImages;
    private final studio.bookhtml.decision.DecisionCoordinator decisionCoordinator;

    public ExportService(BookService books, BookStore store, PdfService pdf, ObjectMapper json) {
        this(books, store, pdf, json, null, null);
    }

    public ExportService(BookService books, BookStore store, PdfService pdf, ObjectMapper json,
                         IssueImageService issueImages) {
        this(books, store, pdf, json, issueImages, null);
    }

    @Autowired
    public ExportService(BookService books, BookStore store, PdfService pdf, ObjectMapper json,
                         IssueImageService issueImages,
                         studio.bookhtml.decision.DecisionCoordinator decisionCoordinator) {
        this.books = books;
        this.store = store;
        this.pdf = pdf;
        this.json = json;
        this.issueImages = issueImages;
        this.decisionCoordinator = decisionCoordinator;
    }

    /**
     * J10：有界脱敏的推荐摘要。只含展示必需字段（候选、来源类别、推荐状态、基线引用、
     * 规则/模型版本摘要）；密钥、原始 HTTP 头、供应商调试数据、完整远端请求 body、
     * 无关整书上下文一律不导出。仅收录导出时刻仍适用的建议。
     */
    String decisionsScript(String bookId, List<Integer> selected,
                           Map<Integer, Integer> exportedNumbers) {
        Map<String, Object> summaries = new java.util.LinkedHashMap<>();
        if (decisionCoordinator != null) for (int sourcePage : selected) {
            Integer exportPage = exportedNumbers == null ? sourcePage
                    : exportedNumbers.getOrDefault(sourcePage, sourcePage);
            Page page;
            try {
                page = store.readPage(bookId, sourcePage);
            } catch (Exception e) {
                continue;
            }
            if (page.blocks() == null) continue;
            for (Block block : page.blocks()) {
                if (block == null || block.original() == null || block.issues() == null) continue;
                for (ContentIssue issue : block.issues()) {
                    if (issue == null || issue.resolved()) continue;
                    Map<String, Object> current;
                    try {
                        current = decisionCoordinator.currentDecisionView(bookId, sourcePage,
                                issue.id());
                    } catch (Exception e) {
                        continue;
                    }
                    if (current == null) continue;
                    // JR-08-T06：仅正式推荐（admitted）可导出；模型偏好绝不当推荐导出
                    Object candidateId = current.get("admittedRecommendationId");
                    if (candidateId == null) continue;
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> candidates =
                            (List<Map<String, Object>>) current.get("candidates");
                    if (candidates == null) continue;
                    Map<String, Object> recommended = null;
                    for (Map<String, Object> candidate : candidates)
                        if (candidateId.equals(candidate.get("candidateId")))
                            recommended = candidate;
                    if (recommended == null) continue;
                    String quote = null;
                    if (issue.start() >= 0 && issue.end() <= block.original().length()
                            && issue.end() > issue.start())
                        quote = block.original().substring(issue.start(), issue.end());
                    if (quote == null) continue;
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("sourcePage", sourcePage);
                    entry.put("blockId", block.id());
                    entry.put("issueId", issue.id());
                    entry.put("start", issue.start());
                    entry.put("end", issue.end());
                    entry.put("originalQuote", quote);
                    entry.put("candidateId", recommended.get("candidateId"));
                    entry.put("displayText", recommended.get("displayText"));
                    entry.put("originalText", recommended.get("originalText"));
                    entry.put("sourceKind", recommended.get("sourceKind"));
                    entry.put("verdict", current.get("verdict"));
                    entry.put("reasonCodes", current.get("reasonCodes"));
                    entry.put("candidateSetHash", current.get("candidateSetHash"));
                    entry.put("decisionId", current.get("decisionId"));
                    entry.put("templateVersion", current.get("templateVersion"));
                    entry.put("policyVersion", current.get("policyVersion"));
                    summaries.put(exportPage + ":" + block.id() + ":" + issue.id(), entry);
                }
            }
        }
        try {
            return "globalThis.BOOK_DECISIONS=" + json.writeValueAsString(summaries) + ";";
        } catch (Exception e) {
            return "globalThis.BOOK_DECISIONS={};";
        }
    }

    /**
     * R05：启动时回收上次崩溃遗留的导出暂存（单写锁保证无活跃导出）。
     * 只处理自有目录的 export-*.zip.part，不碰其他用途文件。
     */
    @jakarta.annotation.PostConstruct
    public void reclaimOrphanedStaging() {
        Path dir;
        try {
            dir = store.exportTmpDir();
        } catch (Exception e) {
            return;
        }
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.newDirectoryStream(dir, "export-*.zip.part")) {
            for (Path p : stream) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    public void writeZip(String bookId, OutputStream output) throws IOException {
        writeZip(bookId, output, null);
    }

    public void writeZip(String bookId, OutputStream output, String pageRange) throws IOException {
        Book book = books.get(bookId);
        Path sourcePdf = store.pdf(bookId);
        List<Integer> selected = PageRanges.parse(pageRange == null ? "all" : pageRange, book.totalPages());
        // 阶段2：先写临时文件，失败不向客户端发送残缺 ZIP；同时做磁盘空间预检
        // R05：导出暂存使用自有子目录，不与渲染清理器共享
        Path tmpDir = store.exportTmpDir();
        if (tmpDir == null) tmpDir = store.tmpDir();
        if (tmpDir == null) tmpDir = Path.of(System.getProperty("java.io.tmpdir", "."));
        Files.createDirectories(tmpDir);
        long need = 10L * 1024 * 1024;
        try { need += Files.size(sourcePdf); } catch (IOException ignored) { }
        long usable;
        try { usable = Files.getFileStore(tmpDir).getUsableSpace(); }
        catch (IOException e) { usable = Long.MAX_VALUE; }
        if (usable < need) {
            throw new studio.bookhtml.api.ApiException(org.springframework.http.HttpStatus.INSUFFICIENT_STORAGE, "磁盘空间不足，无法导出");
        }
        Path staging = Files.createTempFile(tmpDir, "export-", ".zip.part");
        try {
            try (java.io.OutputStream fileOut = Files.newOutputStream(staging);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOut), StandardCharsets.UTF_8)) {
                put(zip, "index.html", html(book));
                put(zip, "assets/style.css", css());
                put(zip, "assets/app.js", javascript());
                put(zip, "assets/reading-layout.js", new ClassPathResource("static/reading-layout.js").getContentAsString(StandardCharsets.UTF_8));
                put(zip, "assets/reader-navigation.js", new ClassPathResource("static/reader-navigation.js").getContentAsString(StandardCharsets.UTF_8));
                put(zip, "assets/issue-review.js", new ClassPathResource("export/issue-review.js").getContentAsString(StandardCharsets.UTF_8));
                put(zip, "assets/offline-edit-store.js", new ClassPathResource("export/offline-edit-store.js").getContentAsString(StandardCharsets.UTF_8));
                put(zip, "assets/issue-review.css", new ClassPathResource("export/issue-review.css").getContentAsString(StandardCharsets.UTF_8));
                copy(zip, "source.pdf", sourcePdf);
                // 阶段4：逐页流式导出——同一时刻只保留一页 Page+一位图，内存有界；失败不发残缺 ZIP
                writePagedPayload(zip, bookId, book, sourcePdf, selected);
            } catch (IOException e) {
                if (e instanceof java.nio.file.FileSystemException || (e.getMessage() != null && e.getMessage().contains("No space"))) {
                    throw new studio.bookhtml.api.ApiException(org.springframework.http.HttpStatus.INSUFFICIENT_STORAGE, "磁盘空间不足，导出失败");
                }
                throw e;
            }
            try (java.io.InputStream in = Files.newInputStream(staging)) {
                in.transferTo(output);
            }
            output.flush();
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    /** 阶段4：分页渐进数据——book.js 只含元数据/目录/轻量搜索索引，正文按页懒加载（file:// 用 script 标签，无 fetch）。 */
    static final int SEARCH_INDEX_LIMIT = 20000;
    private void writePagedPayload(ZipOutputStream zip, String bookId, Book book, Path sourcePdf, List<Integer> selected) throws IOException {
        int processed = 0, reviewed = 0;
        List<Map<String, Object>> searchIndex = new ArrayList<>();
        // R01：索引收集与正文导出完全分离——触顶只停止收录，不退出页面循环。
        boolean searchIndexComplete = true;
        int searchIndexTotal = 0;
        List<OutlineService.OutlineEntry> outlineSource = new ArrayList<>();
        Map<Integer, Integer> exportedNumbers = new LinkedHashMap<>();
        List<Integer> writtenPages = new ArrayList<>();
        int exportIndex = 0;
        for (int sourceNumber : selected) {
            exportIndex++;
            exportedNumbers.put(sourceNumber, exportIndex);
            Page raw = store.readPage(bookId, sourceNumber);
            Page page = raw != null ? raw : Page.pending(sourceNumber, 1, 1);
            if (ready(page)) processed++;
            if (page.reviewed()) reviewed++;
            Page reading = ReadingStructureNormalizer.normalize(page);
            Map<String, Object> exported = exportPage(reading);
            exported.put("sourcePageNumber", page.pageNumber());
            exported.put("pageNumber", exportIndex);
            if (ready(page)) {
                // 同一时刻只保留一页位图，写完立即释放
                writeSinglePageImages(zip, sourcePdf, page, reading);
                writeSingleIssueImages(zip, bookId, page, exported);
            } else {
                exported.put("issueImages", Map.of());
            }
            // 目录：单页复用 fromPages，保证与在线一致；重映射为连续页码
            for (OutlineService.OutlineEntry e : OutlineService.fromPages(List.of(page))) {
                outlineSource.add(new OutlineService.OutlineEntry(exportedNumbers.get(e.pageNumber()), e.blockId(), e.title(), e.level()));
            }
            // 轻量搜索索引：只留页码与截断文本，全文仍在分页文件中；触顶后仅停止收录
            if (page.blocks() != null) {
                for (Block b : page.blocks()) {
                    if ("advertisement".equals(b.type())) continue;
                    String text = b.simplified() != null && !b.simplified().isBlank() ? b.simplified() : b.original();
                    if (text == null || text.isBlank()) continue;
                    searchIndexTotal++;
                    if (searchIndex.size() >= SEARCH_INDEX_LIMIT) {
                        searchIndexComplete = false;
                        continue;
                    }
                    String snippet = text.length() > 120 ? text.substring(0, 120) : text;
                    searchIndex.add(Map.of("page", exportIndex, "sourcePage", sourceNumber,
                            "blockId", b.id(), "text", snippet));
                }
            }
            writePageJs(zip, exportIndex, exported);
            writtenPages.add(exportIndex);
            // 显式释放本页引用，下一轮覆盖
        }
        // R01：成品校验——所有选中页的正文文件必须存在且唯一，映射必须一致；否则失败整个导出
        List<Integer> expectedPages = new ArrayList<>();
        for (int i = 1; i <= selected.size(); i++) expectedPages.add(i);
        if (!writtenPages.equals(expectedPages) || exportedNumbers.size() != selected.size()) {
            throw new IOException("导出校验失败：分页正文缺失或页号映射不一致");
        }
        boolean partial = selected.size() != book.totalPages();
        Map<String, Object> bookMeta = new LinkedHashMap<>();
        bookMeta.put("id", partial ? book.id() + ":selection:" + selected : book.id());
        bookMeta.put("title", book.title());
        bookMeta.put("filename", book.filename());
        bookMeta.put("totalPages", selected.size());
        bookMeta.put("sourceTotalPages", book.totalPages());
        bookMeta.put("partial", partial);
        bookMeta.put("processedPages", processed);
        bookMeta.put("reviewedPages", reviewed);
        // A1-02：原稿内容摘要 + 导出页号→源页号映射，供离线修订做可信身份绑定（流式一次，不预加载正文）
        bookMeta.put("sourcePdfSha256", sha256Hex(sourcePdf));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 2);
        payload.put("book", bookMeta);
        payload.put("outline", outlineSource.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pageNumber", e.pageNumber());
            m.put("blockId", e.blockId());
            m.put("title", e.title());
            m.put("level", e.level());
            return m;
        }).toList());
        payload.put("searchIndex", List.copyOf(searchIndex));
        // R01：索引完整性声明——截断时明确不是全文搜索
        payload.put("searchIndexComplete", searchIndexComplete);
        payload.put("searchIndexCount", searchIndex.size());
        payload.put("searchIndexTotal", searchIndexTotal);
        payload.put("searchIndexLimit", SEARCH_INDEX_LIMIT);
        // A1-02：导出页号→源页号的可信映射（键为字符串，避免 JSON 数字键歧义）
        Map<String, Integer> pageMap = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : exportedNumbers.entrySet()) pageMap.put(String.valueOf(e.getValue()), e.getKey());
        payload.put("pageMap", pageMap);
        payload.put("pageCount", selected.size());
        payload.put("pages", List.of());
        writeBookJs(zip, payload);
        // J10：只读建议摘要 sidecar（有界脱敏）；缺失时为空映射，离线照常工作
        put(zip, "assets/decisions.js", decisionsScript(bookId, selected, exportedNumbers));
    }

    private static String sha256Hex(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void writePageJs(ZipOutputStream zip, int exportIndex, Map<String, Object> exportedPage) throws IOException {
        zip.putNextEntry(entry("assets/pages-data/" + exportIndex + ".js"));
        String prefix = "globalThis.__BOOK_PAGES__=globalThis.__BOOK_PAGES__||{};globalThis.__BOOK_PAGES__[" + exportIndex + "]=";
        zip.write(prefix.getBytes(StandardCharsets.UTF_8));
        byte[] data = safeJavascriptJson(Map.of("page", exportedPage)).getBytes(StandardCharsets.UTF_8);
        // 去掉外层 {"page":...} 包装，只写页对象
        String wrapped = new String(data, StandardCharsets.UTF_8);
        String inner = wrapped.substring("{\"page\":".length(), wrapped.length() - 1);
        byte[] innerBytes = inner.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < innerBytes.length) {
            int chunk = Math.min(64 * 1024, innerBytes.length - offset);
            zip.write(innerBytes, offset, chunk);
            offset += chunk;
        }
        zip.write(";\n".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void writeSinglePageImages(ZipOutputStream zip, Path sourcePdf, Page page, Page readingPage) throws IOException {
        BufferedImage image = pdf.render(sourcePdf, page.pageNumber(), EXPORT_IMAGE_WIDTH);
        try {
            putPng(zip, "assets/pages/" + page.pageNumber() + ".png", image);
            List<Block> blocks = readingPage.blocks() == null ? List.of() : readingPage.blocks();
            for (int index = 0; index < blocks.size(); index++) {
                Block block = blocks.get(index);
                if (!imageBlock(block) || !validBbox(block.bbox())) continue;
                BufferedImage crop = crop(image, block.bbox());
                try {
                    putPng(zip, figureName(page.pageNumber(), index), crop);
                } finally {
                    crop.flush();
                }
            }
        } finally {
            image.flush();
        }
    }

    @SuppressWarnings("unchecked")
    private void writeSingleIssueImages(ZipOutputStream zip, String bookId, Page page, Map<String, Object> exported) throws IOException {
        if (issueImages == null) {
            exported.put("issueImages", Map.of());
            return;
        }
        Map<String, IssueImageService.Snippet> snippets = issueImages.locate(bookId, page);
        Map<String, Object> manifest = new LinkedHashMap<>();
        int imageIndex = 0;
        for (var e : snippets.entrySet()) {
            IssueImageService.Snippet snippet = e.getValue();
            String asset = "assets/issues/" + page.pageNumber() + "-" + (++imageIndex) + ".png";
            String contextAsset = "assets/issues/" + page.pageNumber() + "-" + imageIndex + "-context.png";
            manifest.put(e.getKey(), Map.of("mode", snippet.mode(), "glyphCount", snippet.glyphCount(),
                    "bbox", snippet.bbox(), "boxes", snippet.boxes(), "src", asset,
                    "contextBbox", snippet.contextBbox(), "contextSrc", contextAsset));
            putBytes(zip, asset, snippet.png());
            putBytes(zip, contextAsset, snippet.contextPng());
        }
        exported.put("issueImages", manifest);
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(entry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private void writeBookJs(ZipOutputStream zip, Map<String, Object> payload) throws IOException {
        zip.putNextEntry(entry("assets/book.js"));
        byte[] prefix = "globalThis.__BOOK__=".getBytes(StandardCharsets.UTF_8);
        zip.write(prefix);
        // 结构数据仍为全书 payload（分页加载在阶段4）；此处分块写入避免单次巨型 write
        byte[] json = safeJavascriptJson(payload).getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < json.length) {
            int chunk = Math.min(64 * 1024, json.length - offset);
            zip.write(json, offset, chunk);
            offset += chunk;
        }
        zip.write(";\n".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @SuppressWarnings("unchecked")
    private void writeIssueImages(ZipOutputStream zip, String bookId, List<Page> pages,
                                  Map<String, Object> payload) throws IOException {
        if (issueImages == null) return;
        List<Map<String, Object>> exported = (List<Map<String, Object>>) payload.get("pages");
        for (int index = 0; index < pages.size(); index++) {
            Page page = pages.get(index);
            if (!ready(page)) continue;
            Map<String, IssueImageService.Snippet> snippets = issueImages.locate(bookId, page);
            Map<String, Object> manifest = new LinkedHashMap<>();
            int imageIndex = 0;
            for (var entry : snippets.entrySet()) {
                IssueImageService.Snippet snippet = entry.getValue();
                String asset = "assets/issues/" + page.pageNumber() + "-" + (++imageIndex) + ".png";
                String contextAsset = "assets/issues/" + page.pageNumber() + "-" + imageIndex + "-context.png";
                manifest.put(entry.getKey(), Map.of("mode", snippet.mode(), "glyphCount", snippet.glyphCount(),
                        "bbox", snippet.bbox(), "boxes", snippet.boxes(), "src", asset,
                        "contextBbox", snippet.contextBbox(), "contextSrc", contextAsset));
                ZipEntry zipEntry = new ZipEntry(asset);
                zipEntry.setTime(0);
                zip.putNextEntry(zipEntry);
                zip.write(snippet.png());
                zip.closeEntry();
                ZipEntry contextEntry = new ZipEntry(contextAsset);
                contextEntry.setTime(0);
                zip.putNextEntry(contextEntry);
                zip.write(snippet.contextPng());
                zip.closeEntry();
            }
            exported.get(index).put("issueImages", manifest);
        }
    }

    private List<Page> snapshotPages(String bookId, List<Integer> selected) {
        List<Page> pages = new ArrayList<>(selected.size());
        for (int number : selected) {
            Page page = store.readPage(bookId, number);
            pages.add(page != null ? page : Page.pending(number, 1, 1));
        }
        return List.copyOf(pages);
    }

    private Map<String, Object> exportPayload(Book source, List<Page> pages) {
        int processed = (int) pages.stream().filter(ExportService::ready).count();
        int reviewed = (int) pages.stream().filter(Page::reviewed).count();
        Map<String, Object> book = new LinkedHashMap<>();
        boolean partial = pages.size() != source.totalPages();
        book.put("id", partial ? source.id() + ":selection:" + pages.stream().map(Page::pageNumber).toList() : source.id());
        book.put("title", source.title());
        book.put("filename", source.filename());
        book.put("totalPages", pages.size());
        book.put("sourceTotalPages", source.totalPages());
        book.put("partial", partial);
        book.put("processedPages", processed);
        book.put("reviewedPages", reviewed);

        List<Map<String, Object>> pageData = new ArrayList<>(pages.size());
        for (Page page : pages) {
            Map<String, Object> exported = exportPage(ReadingStructureNormalizer.normalize(page));
            exported.put("sourcePageNumber", page.pageNumber());
            // Navigation is contiguous within a selection; source links retain original PDF numbering.
            exported.put("pageNumber", pageData.size() + 1);
            pageData.add(exported);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("book", book);
        payload.put("pages", pageData);
        Map<Integer, Integer> exportedPageNumbers = new LinkedHashMap<>();
        for (int index = 0; index < pages.size(); index++) {
            exportedPageNumbers.put(pages.get(index).pageNumber(), index + 1);
        }
        payload.put("outline", OutlineService.fromPages(pages).stream()
                .map(entry -> new OutlineService.OutlineEntry(exportedPageNumbers.get(entry.pageNumber()),
                        entry.blockId(), entry.title(), entry.level()))
                .toList());
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportPage(Page page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageNumber", page.pageNumber());
        result.put("width", positive(page.width()) ? page.width() : 1);
        result.put("height", positive(page.height()) ? page.height() : 1);
        result.put("status", page.status());
        result.put("provider", page.provider());
        result.put("reviewed", page.reviewed());
        result.put("warnings", page.warnings() == null ? List.of() : page.warnings());
        result.put("error", page.error());
        if (ready(page)) result.put("image", "assets/pages/" + page.pageNumber() + ".png");

        List<Map<String, Object>> blocks = new ArrayList<>();
        List<Block> sourceBlocks = page.blocks() == null ? List.of() : page.blocks();
        for (int index = 0; index < sourceBlocks.size(); index++) {
            Block block = sourceBlocks.get(index);
            Map<String, Object> data = json.convertValue(block, LinkedHashMap.class);
            if (ready(page) && imageBlock(block) && validBbox(block.bbox())) data.put("asset", figureName(page.pageNumber(), index));
            data.put("facsimileText", FACSIMILE_TEXT_TYPES.contains(block.type()));
            blocks.add(data);
        }
        result.put("blocks", blocks);
        return result;
    }

    private void writeReadyPageImages(ZipOutputStream zip, Path sourcePdf, List<Page> pages) throws IOException {
        for (Page page : pages) {
            if (!ready(page)) continue;
            BufferedImage image = pdf.render(sourcePdf, page.pageNumber(), EXPORT_IMAGE_WIDTH);
            try {
                putPng(zip, "assets/pages/" + page.pageNumber() + ".png", image);
                Page readingPage = ReadingStructureNormalizer.normalize(page);
                List<Block> blocks = readingPage.blocks() == null ? List.of() : readingPage.blocks();
                for (int index = 0; index < blocks.size(); index++) {
                    Block block = blocks.get(index);
                    if (!imageBlock(block) || !validBbox(block.bbox())) continue;
                    BufferedImage crop = crop(image, block.bbox());
                    try {
                        putPng(zip, figureName(page.pageNumber(), index), crop);
                    } finally {
                        crop.flush();
                    }
                }
            } finally {
                image.flush();
            }
        }
    }

    private String safeJavascriptJson(Map<String, Object> value) throws IOException {
        return json.writeValueAsString(value)
            .replace("&", "\\u0026")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace(Character.toString(0x2028), "\\u2028")
            .replace(Character.toString(0x2029), "\\u2029");
    }

    private static boolean ready(Page page) {
        return page != null && "READY".equals(page.status());
    }

    private static boolean imageBlock(Block block) {
        return block != null && IMAGE_BLOCK_TYPES.contains(block.type());
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static boolean validBbox(double[] bbox) {
        if (bbox == null || bbox.length != 4) return false;
        for (double value : bbox) if (!Double.isFinite(value)) return false;
        return bbox[0] >= 0 && bbox[1] >= 0 && bbox[2] > 0 && bbox[3] > 0
            && bbox[0] + bbox[2] <= 1.000001 && bbox[1] + bbox[3] <= 1.000001;
    }

    private static BufferedImage crop(BufferedImage source, double[] bbox) {
        int x = clamp((int) Math.floor(bbox[0] * source.getWidth()), 0, source.getWidth() - 1);
        int y = clamp((int) Math.floor(bbox[1] * source.getHeight()), 0, source.getHeight() - 1);
        int width = clamp((int) Math.ceil(bbox[2] * source.getWidth()), 1, source.getWidth() - x);
        int height = clamp((int) Math.ceil(bbox[3] * source.getHeight()), 1, source.getHeight() - y);
        BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, width, height, x, y, x + width, y + height, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static String figureName(int pageNumber, int blockIndex) {
        return "assets/figures/" + pageNumber + "-" + (blockIndex + 1) + ".png";
    }

    private static void putPng(ZipOutputStream zip, String name, BufferedImage image) throws IOException {
        zip.putNextEntry(entry(name));
        if (!ImageIO.write(image, "png", zip)) throw new IOException("PNG 编码器不可用");
        zip.closeEntry();
    }

    private static void copy(ZipOutputStream zip, String name, Path source) throws IOException {
        zip.putNextEntry(entry(name));
        try (InputStream input = Files.newInputStream(source)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        return entry;
    }

    private static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(entry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String html(Book book) {
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <meta name="color-scheme" content="light">
              <title>%s</title>
              <link rel="stylesheet" href="assets/style.css">
              <link rel="stylesheet" href="assets/issue-review.css">
            </head>
            <body>
              <a class="skip-link" href="#paper">跳到正文</a>
              <header class="topbar">
                <button class="button drawer-button" data-drawer type="button" aria-expanded="false">目录</button>
                <div class="identity"><strong data-book-title>%s</strong><span data-stats></span></div>
                <div class="toolbar" aria-label="阅读工具">
                  <div class="view-switch" role="group" aria-label="显示方式"><button class="view-button active" data-view="original" type="button">原稿</button><button class="view-button" data-view="facsimile" type="button">原貌 HTML</button><button class="view-button" data-view="reading" type="button">舒适阅读</button></div>
                  <button class="button" data-focus type="button" aria-pressed="false">专注阅读</button>
                  <button class="button" data-script type="button">显示：简体</button>
                  <label>字号 <input data-size type="range" min="15" max="30" value="20"><output data-size-output>20</output></label>
                  <label>行距 <input data-leading type="range" min="1.4" max="2.2" step="0.1" value="1.8"><output data-leading-output>1.8</output></label>
                </div>
              </header>
              <aside class="sidebar" data-sidebar aria-label="目录和搜索">
                <div class="sidebar-head"><strong>目录</strong><button class="close-drawer" data-close-drawer type="button" aria-label="关闭目录">×</button></div>
                <form class="search" data-search-form><label for="book-search">搜索繁体与简体</label><div><input id="book-search" data-search type="search"><button class="button" type="submit">搜索</button></div></form>
                <p class="search-status" data-search-status></p><ol class="search-results" data-search-results></ol>
                <nav class="toc" data-toc aria-label="篇目与页码"></nav>
                <section class="bookmarks"><h2>书签</h2><div data-bookmarks></div></section>
              </aside>
              <div class="scrim" data-scrim hidden></div>
              <main>
                <section class="page-meta"><div><span class="status" data-status></span><span data-quality></span></div><div><a class="source-link" data-source href="source.pdf">在原 PDF 中对照</a><button class="button" data-bookmark type="button">加入书签</button></div></section>
                <details class="notice" data-notice hidden><summary data-notice-summary>本页识别说明</summary><div data-notice-text></div></details>
                <article id="paper" class="paper" data-paper tabindex="-1"></article>
                <nav class="pagination" aria-label="翻页"><button class="button" data-prev type="button">上一页</button><div class="page-progress"><input data-progress type="range" min="1" step="1" value="1" aria-label="阅读进度"><output data-progress-label></output></div><form data-jump-form><label for="page-jump">第</label><input id="page-jump" data-jump type="number" min="1"><span data-total></span></form><button class="button" data-next type="button">下一页</button></nav>
              </main>
              <script src="assets/book.js"></script><script src="assets/decisions.js"></script><script src="assets/reading-layout.js"></script><script src="assets/offline-edit-store.js"></script><script src="assets/issue-review.js"></script><script src="assets/reader-navigation.js"></script><script src="assets/app.js"></script>
            </body>
            </html>
            """.formatted(escape(book.title()), escape(book.title()));
    }

    private static String css() {
        return """
            :root{color-scheme:light;--paper:#fff;--ink:#252d36;--muted:#687684;--work:#eef2f6;--line:#ccd6df;--blue:#315f90;--blue-pale:#e4edf6;--amber:#a56912;--amber-pale:#fff4d8;--serif:"Songti SC","STSong","SimSun",serif;--sans:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;--size:20px;--leading:1.8}
            *{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;min-width:320px;background:var(--work);color:var(--ink);font:14px var(--sans)}button,input{font:inherit;color:inherit}button{cursor:pointer}button:disabled{cursor:not-allowed;opacity:.5}button:focus-visible,input:focus-visible,[tabindex]:focus-visible,a:focus-visible{outline:3px solid rgb(49 95 144/.3);outline-offset:2px}[hidden]{display:none!important}.skip-link{position:fixed;z-index:50;top:-50px;left:15px;background:var(--blue);color:#fff;padding:9px 12px}.skip-link:focus{top:10px}
            .topbar{position:sticky;z-index:10;top:0;min-height:66px;display:flex;align-items:center;gap:20px;border-bottom:1px solid var(--line);background:rgb(255 255 255/.97);padding:9px 18px}.identity{min-width:180px;display:grid;gap:2px}.identity strong{font:600 17px var(--serif)}.identity span{color:var(--muted);font-size:11px}.toolbar{display:flex;align-items:center;justify-content:flex-end;gap:9px;flex:1}.toolbar label{display:flex;align-items:center;gap:5px;color:var(--muted);font-size:11px}.toolbar input[type=range]{width:68px;accent-color:var(--blue)}.toolbar output{width:24px;color:var(--ink);font-variant-numeric:tabular-nums}.button{min-height:34px;border:1px solid #aebcc9;border-radius:3px;background:#fff;padding:6px 10px;font-weight:620}.button:hover{border-color:var(--blue);color:var(--blue)}.view-switch{display:flex}.view-button{min-height:34px;border:1px solid #aebcc9;border-right:0;background:#fff;padding:6px 10px}.view-button:first-child{border-radius:3px 0 0 3px}.view-button:last-child{border-right:1px solid #aebcc9;border-radius:0 3px 3px 0}.view-button.active{border-color:var(--blue);background:var(--blue);color:#fff}.drawer-button,.close-drawer{display:none}
            .sidebar{position:fixed;z-index:8;top:66px;bottom:0;left:0;width:245px;overflow:auto;border-right:1px solid var(--line);background:#f8fafc;padding:17px 13px}.sidebar-head{display:flex;justify-content:space-between;align-items:center}.search{display:grid;gap:6px;margin:15px 0 0}.search label{color:var(--muted);font-size:11px}.search>div{display:grid;grid-template-columns:1fr auto;gap:5px}.search input{min-width:0;height:34px;border:1px solid #aebcc9;border-radius:3px;padding:6px 8px}.search-status{min-height:18px;margin:7px 0;color:var(--muted);font-size:11px}.search-results{list-style:none;margin:0;padding:0}.search-result,.toc-entry,.bookmark-entry{width:100%;display:flex;justify-content:space-between;gap:8px;border:0;border-left:2px solid transparent;background:transparent;padding:7px 6px;text-align:left}.search-result{display:grid;border-bottom:1px solid var(--line)}.search-result strong,.search-result small{color:var(--blue);font-size:11px}.search-result span{overflow:hidden;font:13px/1.45 var(--serif);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}.toc{display:grid;margin-top:10px}.toc-entry:hover,.bookmark-entry:hover{background:var(--blue-pale)}.toc-entry.active{border-left-color:var(--blue);background:var(--blue-pale);color:#21496f;font-weight:650}.toc-entry span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.toc-entry small{color:var(--muted)}.bookmarks{margin-top:22px;border-top:1px solid var(--line);padding-top:13px}.bookmarks h2{font-size:12px}.empty-note{color:var(--muted);font-size:11px;line-height:1.5}
            main{min-height:calc(100vh - 66px);margin-left:245px;padding:18px}.page-meta{width:min(960px,100%);display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 auto 10px}.page-meta>div{display:flex;align-items:center;gap:8px}.status{border:1px solid #aebcc9;border-radius:2px;padding:4px 8px;color:var(--muted);font-size:11px;font-weight:700}.status.warning{border-color:#dabd7c;background:var(--amber-pale);color:#7f4e08}.status.reviewed{border-color:#96b9a8;background:#eaf5ef;color:#356d53}[data-quality]{color:var(--muted);font-size:11px}.source-link{color:var(--blue);font-size:12px}.notice{width:min(960px,100%);margin:0 auto 10px;border-left:3px solid var(--amber);background:var(--amber-pale);padding:9px 12px;color:#754b10;font-size:12px;line-height:1.5}.paper{width:min(960px,100%);min-height:62vh;margin:auto}.original-frame,.facsimile-stage{position:relative;width:min(900px,100%);margin:auto;overflow:hidden;border:1px solid #c5ced6;background:#fff;box-shadow:0 2px 7px rgb(29 45 58/.1),0 18px 50px rgb(29 45 58/.08)}.page-image,.facsimile-image{display:block;width:100%;height:100%;object-fit:fill}.facsimile-image{position:absolute;inset:0}.coordinate-block{position:absolute;overflow:hidden;background:rgb(255 255 255/.96);white-space:pre-wrap;font-family:var(--serif);line-height:1.35}.coordinate-block.uncertain{outline:2px solid rgb(165 105 18/.75);background:rgb(255 244 216/.96)}.layout-note{color:var(--muted);font-size:11px;text-align:center}.reading-flow{width:min(740px,100%);min-height:62vh;margin:auto;background:#fff;box-shadow:0 2px 7px rgb(29 45 58/.1),0 18px 50px rgb(29 45 58/.08);padding:clamp(32px,7vw,86px) clamp(24px,8vw,90px);font:var(--size)/var(--leading) var(--serif)}.reading-flow{writing-mode:horizontal-tb;text-orientation:mixed}.reading-flow p{margin:0 0 1.05em;text-align:justify;overflow-wrap:anywhere}.reading-flow h2,.reading-flow h3,.reading-flow h4{margin:1.8em 0 .7em;line-height:1.45}.reading-flow>:first-child{margin-top:0}.flow-caption{border-left:2px solid #aebcc9;padding-left:12px;color:var(--muted);font-size:.82em}.uncertain-text{text-decoration:underline dotted var(--amber) 1.5px;text-underline-offset:.2em}.reading-figure{margin:1.7em auto}.reading-figure img{display:block;max-width:100%;max-height:78vh;margin:auto;border:1px solid #d8dfe5}.reading-figure figcaption{margin-top:.6em;color:var(--muted);font-size:.72em;line-height:1.55;text-align:center}.placeholder{min-height:62vh;display:grid;place-items:center;border:1px solid #c5ced6;background:#fff;padding:40px;text-align:center;box-shadow:0 2px 7px rgb(29 45 58/.1)}.placeholder strong{display:block;font:600 23px var(--serif)}.placeholder p{max-width:480px;color:var(--muted);line-height:1.7}.placeholder a{color:var(--blue)}.pagination{width:min(900px,100%);display:flex;align-items:center;justify-content:space-between;gap:15px;margin:22px auto 30px}.pagination form{display:flex;align-items:center;gap:7px;color:var(--muted);flex:0 0 auto}.pagination input[type=number]{width:70px;height:34px;border:1px solid #aebcc9;border-radius:3px;text-align:center}.page-progress{min-width:160px;display:grid;grid-template-columns:minmax(100px,1fr) auto;align-items:center;gap:10px;flex:1}.page-progress input[type=range]{width:100%;accent-color:var(--blue)}.page-progress output{min-width:135px;color:var(--muted);font-size:12px;font-variant-numeric:tabular-nums;text-align:right}.scrim{position:fixed;z-index:19;inset:0;background:rgb(28 39 48/.35)}.sidebar.open{display:block;z-index:21}.sidebar.open .close-drawer{display:block;border:0;background:transparent;font-size:26px}body.focus-reading .drawer-button{display:inline-flex}body.focus-reading .sidebar:not(.open){display:none}body.focus-reading main{margin-left:0}body.focus-reading .paper,body.focus-reading .page-meta,body.focus-reading .notice{width:min(1180px,100%)}body.focus-reading .original-frame,body.focus-reading .facsimile-stage{width:min(1100px,100%)}
            @media(max-width:900px){.topbar{flex-wrap:wrap;gap:8px}.drawer-button{display:inline-flex}.identity{min-width:0;flex:1}.toolbar{flex-basis:100%;justify-content:flex-start;overflow-x:auto}.toolbar>*{flex:0 0 auto;white-space:nowrap}.view-switch{flex:0 0 auto}.view-button{flex:0 0 auto;white-space:nowrap}.toolbar label{flex:none}.sidebar{z-index:20;top:0;width:min(88vw,360px);transform:translateX(-105%);transition:transform .16s ease-out;box-shadow:0 0 30px rgb(24 38 50/.18)}.sidebar.open{transform:translateX(0)}.close-drawer{display:block;border:0;background:transparent;font-size:26px}main{margin-left:0;padding:10px}.page-meta{align-items:flex-start}.page-meta>div:last-child{flex-direction:column;align-items:flex-end}.paper{width:100%}}
            @media(max-width:560px){.identity span{display:none}.toolbar{gap:4px}.view-button,.button{padding-inline:7px;font-size:12px}.toolbar>[data-script]{margin-left:auto}.page-meta [data-quality]{display:none}.reading-flow{padding:34px 23px}.placeholder{padding:24px}.source-link{font-size:11px}.pagination{gap:7px;flex-wrap:wrap}.page-progress{order:-1;flex-basis:100%}.page-progress output{min-width:126px;font-size:11px}}
            .filtered-ads{margin-top:2.5em;border-top:1px solid var(--line);padding-top:.7em;color:var(--muted);font:12px/1.6 var(--sans)}.filtered-ads summary{width:fit-content;cursor:pointer;color:var(--blue)}.filtered-ads-note{margin:.7em 0!important;text-align:left!important}.filtered-ads ol{max-height:260px;overflow:auto;margin:.5em 0 0;padding-left:1.6em}.filtered-ads li{border-bottom:1px solid var(--line);padding:.5em 0}.filtered-ads li p{margin:0 0 .4em;white-space:pre-wrap;text-align:left;font:15px/1.6 var(--serif)}.filtered-ads .button{min-height:32px;padding:4px 8px;font-size:12px}.ad-source-highlight{position:absolute;border:2px dashed #a56912;background:rgb(165 105 18/.12);pointer-events:none}
            @media(prefers-reduced-motion:reduce){*{scroll-behavior:auto!important;transition-duration:.01ms!important}}
            """;
    }

    private static String javascript() {
        return """
            (() => {
              'use strict';
              const payload=globalThis.__BOOK__||{book:{title:'离线书籍',totalPages:0,processedPages:0,reviewedPages:0},pages:[],outline:[]};const book=payload.book;const outline=Array.isArray(payload.outline)?payload.outline:[];const searchIndex=Array.isArray(payload.searchIndex)?payload.searchIndex:[];const pagedMode=!(Array.isArray(payload.pages)&&payload.pages.length>0)&&Number(payload.pageCount||0)>0;const totalCount=Number(book.totalPages||payload.pageCount||0)||0;const pages=(Array.isArray(payload.pages)&&payload.pages.length>0)?payload.pages:Array.from({length:Number(payload.pageCount||totalCount||0)},(_,i)=>({pageNumber:i+1,status:'LOADING',blocks:[],warnings:['正在加载本页…']}));const pageCache=new Map();const pendingLoads=new Map();const PAGE_LRU=10;function touchPageCache(n,pg){pageCache.delete(n);pageCache.set(n,pg);while(pageCache.size>PAGE_LRU){const oldest=pageCache.keys().next().value;pageCache.delete(oldest);if(pageCache.size<=PAGE_LRU)break;break;}}function loadPaged(n){n=Number(n);if(!(n>=1&&n<=pages.length))return Promise.resolve(null);const ready=(globalThis.__BOOK_PAGES__||{})[n]||pageCache.get(n);if(ready&&ready.status&&ready.status!=='LOADING'){touchPageCache(n,ready);pages[n-1]=ready;return Promise.resolve(ready);}if(pendingLoads.has(n))return pendingLoads.get(n);const task=new Promise(resolve=>{const done=pg=>{pendingLoads.delete(n);const finish=pg2=>{if(pg2){touchPageCache(n,pg2);pages[n-1]=pg2;}resolve(pg2||null);try{if(typeof state!=='undefined'&&state&&state.page===n&&typeof renderPage==='function')renderPage({keepScroll:true});}catch(_){}};if(pg){try{if(typeof issueReview!=='undefined'&&issueReview&&typeof issueReview.preparePage==='function'){Promise.resolve(issueReview.preparePage(pg)).then(()=>finish(pg),()=>finish(pg));}else{if(typeof issueReview!=='undefined'&&issueReview&&typeof issueReview.hydratePage==='function')issueReview.hydratePage(pg);finish(pg);}}catch(_){finish(pg);}}else finish(null);};try{const hit=(globalThis.__BOOK_PAGES__||{})[n];if(hit){done(hit);return;}const s=document.createElement('script');s.src=`assets/pages-data/${n}.js`;s.async=true;s.onload=()=>done((globalThis.__BOOK_PAGES__||{})[n]);s.onerror=()=>done(null);document.head.append(s);}catch(_){done(null);}});pendingLoads.set(n,task);return task;}const navigation=globalThis.BookReaderNavigation||{progress:(page,total)=>{const safeTotal=Math.max(1,Number(total)||1),safePage=Math.max(1,Math.min(Number(page)||1,safeTotal));return{page:safePage,total:safeTotal,percent:safeTotal===1?100:Math.round(safePage/safeTotal*100)}},activeIndex:(items,page,blockId)=>{if(blockId){const exact=items.findIndex(item=>item.pageNumber===page&&item.blockId===blockId);if(exact>=0)return exact}let active=-1;for(let i=0;i<items.length&&items[i].pageNumber<=page;i++)active=i;return active}};const $=s=>document.querySelector(s);const $$=s=>[...document.querySelectorAll(s)];const storageKey=s=>`book-html:${book.id||book.title}:${s}`;const readStorage=(s,f)=>{try{return JSON.parse(localStorage.getItem(storageKey(s)))??f}catch(_){return f}};const writeStorage=(s,v)=>{try{localStorage.setItem(storageKey(s),JSON.stringify(v))}catch(_){}};const preferences=readStorage('reading',{});const clamp=(v,l,h)=>Math.max(l,Math.min(v,h));const state={page:clamp(Number(preferences.page||1),1,Math.max(1,Number(book.totalPages||pages.length||1))),blockId:null,view:['original','facsimile','reading'].includes(preferences.view)?preferences.view:'reading',script:preferences.script==='original'?'original':'simplified',size:clamp(Number(preferences.size||20),15,30),leading:clamp(Number(preferences.leading||1.8),1.4,2.2),focus:Boolean(preferences.focus)};let bookmarks=readStorage('bookmarks',[]).filter(Number.isInteger);const imageTypes=new Set(['figure','table','formula']);const textFor=b=>state.script==='original'?(b.original||b.simplified||''):(b.simplified||b.original||'');const pageAt=n=>{n=Number(n);const i=n-1;if(!(i>=0&&i<pages.length))return{pageNumber:n,status:'PENDING',blocks:[],warnings:[]};const cur=pages[i];if(cur&&cur.status&&cur.status!=='LOADING'&&(cur.status==='READY'||cur.status==='FAILED'||cur.status==='PENDING'||(cur.blocks||[]).length>0))return cur;const hit=pageCache.get(n)||(globalThis.__BOOK_PAGES__||{})[n];if(hit){pages[i]=hit;touchPageCache(n,hit);return hit;}if(pagedMode)loadPaged(n);return{pageNumber:n,status:'LOADING',blocks:[],warnings:['正在加载本页…'],sourcePageNumber:n};};const node=(tag,c,t)=>{const e=document.createElement(tag);if(c)e.className=c;if(t!=null)e.textContent=t;return e};const savePosition=()=>writeStorage('reading',{...state,blockId:undefined,scrollY:window.scrollY});const issueReview=globalThis.BookIssueReview.create({book,pages,pageMap:payload.pageMap,getPage:()=>pageAt(state.page),getScript:()=>state.script,render:()=>renderPage({keepScroll:true})});try{globalThis.__reviewForTest=issueReview;}catch(_){}try{if(!pagedMode&&issueReview&&typeof issueReview.preparePage==='function'){for(const p of pages){if(p&&p.blocks&&p.blocks.length)Promise.resolve(issueReview.preparePage(p)).catch(()=>{});}}}catch(_){}
              function sourceLink(n,text='打开 source.pdf 对照本页'){const a=node('a','',text);try{const pg=pageAt(n);a.href=`source.pdf#page=${pg.sourcePageNumber||n}`;}catch(_){a.href=`source.pdf#page=${n}`;}return a}function placeholder(p){const box=node('section','placeholder'),inner=node('div');if(p.status==='LOADING'){inner.append(node('strong','',`第 ${p.pageNumber} 页正在加载…`),node('p','','分页数据按需加载，file:// 下通过 script 标签读取，无需网络。'));box.append(inner);try{loadPaged(p.pageNumber);}catch(_){}return box;}inner.append(node('strong','',`第 ${p.pageNumber} 页尚未转换`),node('p','',p.status==='FAILED'?(p.error||'本页转换失败，离线包没有伪造 HTML 或栅格图。'):'离线包未栅格化未处理页面，以控制体积并忠实标明进度。'),sourceLink(p.pageNumber));box.append(inner);return box}function pageImage(p,c){const i=node('img',c);i.src=p.image;i.alt=`第 ${p.pageNumber} 页原稿`;i.draggable=false;return i}function renderOriginal(p){if(!p.image)return placeholder(p);const f=node('div','original-frame');f.style.aspectRatio=`${p.width||1}/${p.height||1}`;f.append(pageImage(p,'page-image'));const selected=adLocation?.page===p.pageNumber&&(p.blocks||[]).find(b=>b.id===adLocation.id);if(selected&&Array.isArray(selected.bbox)&&selected.bbox.length===4){const [x,y,w,h]=selected.bbox;if([x,y,w,h].every(Number.isFinite)){const mark=node('div','ad-source-highlight');Object.assign(mark.style,{left:`${x*100}%`,top:`${y*100}%`,width:`${w*100}%`,height:`${h*100}%`});mark.tabIndex=-1;mark.setAttribute('aria-label','广告原稿位置');f.append(mark)}}return f}
              function fitBlock(e){let s=Math.min(26,Math.max(8,e.getBoundingClientRect().height*.28));e.style.fontSize=`${s}px`;while(s>7&&(e.scrollHeight>e.clientHeight+1||e.scrollWidth>e.clientWidth+1)){s-=.5;e.style.fontSize=`${s}px`}}function renderFacsimile(p){if(!p.image)return placeholder(p);const wrap=node('div'),stage=node('div','facsimile-stage');stage.style.aspectRatio=`${p.width||1}/${p.height||1}`;stage.append(pageImage(p,'facsimile-image'));for(const b of p.blocks||[]){if(!b.facsimileText)continue;const x=b.bbox||[0,0,.1,.1],item=node(b.type==='heading'?'h2':'div',`coordinate-block${b.uncertain?' uncertain':''}`,textFor(b));Object.assign(item.style,{left:`${x[0]*100}%`,top:`${x[1]*100}%`,width:`${x[2]*100}%`,height:`${x[3]*100}%`,writingMode:b.writingMode||'horizontal-tb'});stage.append(item)}wrap.append(stage,node('p','layout-note','原貌 HTML 按识别坐标近似排版；原稿保留在底层，插图与复杂底纹不会被重绘。'));requestAnimationFrame(()=>stage.querySelectorAll('.coordinate-block').forEach(fitBlock));return wrap}
              let adLocation=null;
              function renderReading(p){
                if(p.status!=='READY')return placeholder(p);
                const flow=node('section','reading-flow'),blocks=[...(p.blocks||[])].sort((a,b)=>(a.order??0)-(b.order??0));
                let previous=null,previousText='',paragraph=null;const ads=[];
                for(const b of blocks){
                  if(b.type==='advertisement'){ads.push(b);previous=null;paragraph=null;continue}
                  if(b.type==='page-number')continue;
                  const text=textFor(b);
                  if(imageTypes.has(b.type)){
                    previous=null;paragraph=null;
                    const f=node('figure',`reading-figure type-${b.type}`);
                    if(b.asset){const i=node('img');i.src=b.asset;i.alt=b.type==='table'?'原稿中的表格裁图':b.type==='formula'?'原稿中的公式裁图':'原稿中的插图';f.append(i)}
                    if(text){const details=node('details','figure-transcript'),caption=node('div','figure-transcript-text');details.append(node('summary','','图内识别文字（可展开校对）'));issueReview.appendText(caption,b,text);details.append(caption);f.append(details)}
                    if(f.childNodes.length)flow.append(f);continue;
                  }
                  if(!text)continue;
                  if(paragraph&&globalThis.BookReadingLayout.canJoin(previous,b,previousText,text))issueReview.appendText(paragraph,b,text);
                  else{const tag=b.type==='heading'?`h${clamp(Number(b.headingLevel||2),2,4)}`:b.type==='caption'?'aside':'p';paragraph=node(tag,b.type==='caption'?'flow-caption':'');if(b.type==='heading'&&b.id){paragraph.dataset.blockId=b.id;paragraph.tabIndex=-1}issueReview.appendText(paragraph,b,text);flow.append(paragraph)}
                  previous=b;previousText=text;
                }
                if(!flow.childNodes.length)flow.append(node('p','empty-note','本页没有可进入阅读流的内容，请对照原稿。'));
                if(ads.length){const details=node('details','filtered-ads');details.append(node('summary','',`已过滤 ${ads.length} 处广告 · 查看`),node('p','filtered-ads-note','仅从舒适阅读正文中收起，原识别字与原稿位置仍保留。'));const list=node('ol');for(const b of ads){const item=node('li'),text=node('p','',b.original||b.simplified||'（未识别到文字）'),locate=node('button','button quiet','在原稿定位');locate.type='button';locate.addEventListener('click',()=>{adLocation={page:p.pageNumber,id:b.id};state.view='original';renderPage({keepScroll:true});requestAnimationFrame(()=>{const mark=$('.ad-source-highlight');(mark||$('[data-paper]')).scrollIntoView({block:'center',behavior:matchMedia('(prefers-reduced-motion:reduce)').matches?'auto':'smooth'});mark?.focus({preventScroll:true})})});item.append(text,locate);list.append(item)}details.append(list);flow.append(details)}return flow;
              }
              function quality(p){const blocks=p.blocks||[],u=blocks.filter(x=>x.uncertain).length;if(p.status==='LOADING')return['正在加载','warning','分页数据按需加载中'];if(p.status!=='READY')return['尚未转换','warning',p.status==='FAILED'?'转换失败':'仅保留 PDF 原稿'];if(p.reviewed)return['已人工校对','reviewed',`${blocks.length} 个块${u?`，${u} 个疑点`:''}`];return[u?'建议校对':'待人工确认',u?'warning':'',`${blocks.length} 个块${u?`，${u} 个疑点`:''}`]}
              function renderPage(o={}){
                const p=pageAt(state.page),paper=$('[data-paper]');
                paper.replaceChildren(state.view==='original'?renderOriginal(p):state.view==='facsimile'?renderFacsimile(p):renderReading(p));
                document.documentElement.style.setProperty('--size',`${state.size}px`);document.documentElement.style.setProperty('--leading',state.leading);
                document.body.classList.toggle('focus-reading',state.focus);
                $('[data-focus]').textContent=state.focus?'返回工作台':'专注阅读';$('[data-focus]').setAttribute('aria-pressed',String(state.focus));
                $$('[data-view]').forEach(b=>{const a=b.dataset.view===state.view;b.classList.toggle('active',a);b.setAttribute('aria-pressed',String(a))});
                $('[data-script]').textContent=`显示：${state.script==='simplified'?'简体':'原文'}`;
                $('[data-size]').value=state.size;$('[data-size-output]').value=state.size;$('[data-leading]').value=state.leading;$('[data-leading-output]').value=state.leading;
                $('[data-jump]').value=state.page;$('[data-jump]').max=Math.max(1,pages.length);$('[data-total]').textContent=`/ ${book.totalPages} 页`;$('[data-prev]').disabled=state.page<=1;$('[data-next]').disabled=state.page>=book.totalPages;renderProgress(state.page);
                const [label,tone,detail]=quality(p);$('[data-status]').textContent=label;$('[data-status]').className=`status ${tone}`;
                $('[data-quality]').textContent=detail+(book.partial?` · 原 PDF 第 ${p.sourcePageNumber||state.page} 页`:'');
                const notice=[...(p.warnings||[])];if(p.status==='READY'&&!p.reviewed)notice.unshift('自动识别结果；虚线文字为待核对候选，点击可查看原字。');
                if(p.error)notice.unshift(p.error);
                const noticeBox=$('[data-notice]');noticeBox.hidden=!notice.length;
                if(noticeBox.dataset.page!==String(state.page)){noticeBox.open=p.status==='FAILED';noticeBox.dataset.page=String(state.page)}
                $('[data-notice-summary]').textContent=p.status==='FAILED'?'本页识别失败':`本页识别说明（${notice.length}）`;
                $('[data-notice-text]').textContent=notice.join('；');
                $('[data-source]').href=`source.pdf#page=${p.sourcePageNumber||state.page}`;
                renderBookmarkButton();renderToc();issueReview.refresh();savePosition();
                if(o.blockId)requestAnimationFrame(()=>focusHeading(o.blockId));else if(!o.keepScroll)window.scrollTo({top:0,behavior:matchMedia('(prefers-reduced-motion:reduce)').matches?'auto':'smooth'});
              }
              function headingBlock(entry){return(pageAt(entry.pageNumber).blocks||[]).find(block=>block.id===entry.blockId)}function headingLabel(b){const label=node('span');issueReview.appendText(label,b,textFor(b));return label.textContent}function renderToc(){const toc=$('[data-toc]');toc.replaceChildren();const active=navigation.activeIndex(outline,state.page,state.blockId);if(!outline.length){toc.append(node('p','empty-note','尚未识别到正文标题。'));return}outline.forEach((entry,index)=>{const block=headingBlock(entry),label=block?headingLabel(block):String(entry.title||'').trim();if(!label)return;const b=node('button',`toc-entry level-${clamp(Number(entry.level||2),1,6)}${index===active?' active':''}`);b.type='button';if(index===active)b.setAttribute('aria-current','location');b.style.paddingLeft=`${6+(clamp(Number(entry.level||2),1,6)-1)*10}px`;b.title=label+(block&&(block.issues||[]).some(issue=>!issue.resolved)?'（含待核对文字）':'');b.append(node('span','',label),node('small','',String(entry.pageNumber)));b.addEventListener('click',()=>go(entry.pageNumber,entry.blockId));toc.append(b)})}function focusHeading(blockId){const target=[...document.querySelectorAll('[data-block-id]')].find(element=>element.dataset.blockId===blockId);if(!target)return;target.scrollIntoView({block:'start',behavior:matchMedia('(prefers-reduced-motion:reduce)').matches?'auto':'smooth'});target.focus({preventScroll:true})}function progressText(value){return`第 ${value.page} / ${value.total} 页 · ${Math.round(Number(value.percent)||0)}%`}function renderProgress(page){const value=navigation.progress(page,Math.max(1,pages.length)),input=$('[data-progress]'),label=progressText(value);input.min=1;input.max=value.total;input.value=value.page;input.disabled=value.total<=1;input.setAttribute('aria-valuetext',label);$('[data-progress-label]').value=label}function go(n,blockId=null){const next=clamp(Number(n)||1,1,Math.max(1,pages.length));state.blockId=blockId||null;if(blockId)state.view='reading';if(next===state.page){renderPage({keepScroll:!blockId,blockId});closeDrawer();return}state.page=next;renderPage({blockId});closeDrawer()}
              function renderBookmarkButton(){const a=bookmarks.includes(state.page);$('[data-bookmark]').textContent=a?'已加入书签':'加入书签';$('[data-bookmark]').setAttribute('aria-pressed',String(a))}function renderBookmarks(){const box=$('[data-bookmarks]');box.replaceChildren();if(!bookmarks.length){box.append(node('p','empty-note','还没有书签。'));return}for(const p of [...bookmarks].sort((a,b)=>a-b)){const button=node('button','bookmark-entry',`第 ${p} 页`);button.type='button';button.addEventListener('click',()=>go(p));box.append(button)}}
              function search(query){const q=query.trim().toLocaleLowerCase(),results=$('[data-search-results]');results.replaceChildren();if(!q){$('[data-search-status]').textContent='';return}const hits=[];if(pagedMode&&searchIndex.length){for(const entry of searchIndex){const text=String(entry.text||'');if(text.toLocaleLowerCase().includes(q))hits.push({page:entry.page,text});if(hits.length>=500)break;}}else{for(const p of pages){const full=pageCache.get(p.pageNumber)||(globalThis.__BOOK_PAGES__||{})[p.pageNumber]||p;for(const b of full.blocks||[]){if(b.type==='advertisement')continue;const original=String(b.original||''),simple=String(b.simplified||'');if(original.toLocaleLowerCase().includes(q)||simple.toLocaleLowerCase().includes(q))hits.push({page:full.pageNumber,text:simple||original});if(hits.length>=500)break}if(hits.length>=500)break}}$('[data-search-status]').textContent=hits.length?`${hits.length} 处命中${hits.length===500?'（最多显示 500 处）':''}`:'没有找到';for(const hit of hits){const li=node('li'),button=node('button','search-result');button.type='button';button.append(node('strong','',`第 ${hit.page} 页`),node('span','',hit.text));button.addEventListener('click',()=>go(hit.page));li.append(button);results.append(li)}}
              function openDrawer(){$('[data-sidebar]').classList.add('open');$('[data-scrim]').hidden=false;$('[data-drawer]').setAttribute('aria-expanded','true')}function closeDrawer(){$('[data-sidebar]').classList.remove('open');$('[data-scrim]').hidden=true;$('[data-drawer]').setAttribute('aria-expanded','false')}
              $('[data-book-title]').textContent=book.title;document.title=book.title;$('[data-stats]').textContent=`${book.partial?'节选 ':''}${book.totalPages} 页${book.partial?`（原书 ${book.sourceTotalPages} 页）`:''} · 已处理 ${book.processedPages} 页 · 已校对 ${book.reviewedPages} 页${payload.searchIndexComplete===false?` · 搜索索引不完整（仅 ${payload.searchIndexCount}/${payload.searchIndexTotal} 条），请以分页正文核对`:''}`;$$('[data-view]').forEach(b=>b.addEventListener('click',()=>{state.view=b.dataset.view;renderPage({keepScroll:true})}));$('[data-focus]').addEventListener('click',()=>{state.focus=!state.focus;closeDrawer();renderPage({keepScroll:true})});$('[data-script]').addEventListener('click',()=>{state.script=state.script==='simplified'?'original':'simplified';renderPage({keepScroll:true})});$('[data-size]').addEventListener('input',e=>{state.size=Number(e.target.value);renderPage({keepScroll:true})});$('[data-leading]').addEventListener('input',e=>{state.leading=Number(e.target.value);renderPage({keepScroll:true})});$('[data-prev]').addEventListener('click',()=>go(state.page-1));$('[data-next]').addEventListener('click',()=>go(state.page+1));$('[data-jump-form]').addEventListener('submit',e=>{e.preventDefault();go($('[data-jump]').value)});$('[data-jump]').addEventListener('change',e=>go(e.target.value));$('[data-progress]').addEventListener('input',e=>{const value=navigation.progress(e.target.value,Math.max(1,pages.length)),label=progressText(value);e.target.setAttribute('aria-valuetext',label);$('[data-progress-label]').value=label});$('[data-progress]').addEventListener('change',e=>go(e.target.value));$('[data-bookmark]').addEventListener('click',()=>{bookmarks=bookmarks.includes(state.page)?bookmarks.filter(p=>p!==state.page):[...bookmarks,state.page];writeStorage('bookmarks',bookmarks);renderBookmarkButton();renderBookmarks()});$('[data-search-form]').addEventListener('submit',e=>{e.preventDefault();search($('[data-search]').value)});$('[data-drawer]').addEventListener('click',openDrawer);$('[data-close-drawer]').addEventListener('click',closeDrawer);$('[data-scrim]').addEventListener('click',closeDrawer);window.addEventListener('scroll',savePosition,{passive:true});window.addEventListener('beforeunload',savePosition);document.addEventListener('keydown',e=>{if(e.key==='Escape')closeDrawer();if(!e.target.matches('input')&&e.key==='ArrowLeft')go(state.page-1);if(!e.target.matches('input')&&e.key==='ArrowRight')go(state.page+1)});renderBookmarks();renderPage({keepScroll:true});requestAnimationFrame(()=>window.scrollTo(0,Number(preferences.scrollY||0)));
            })();
            """;
    }
}
