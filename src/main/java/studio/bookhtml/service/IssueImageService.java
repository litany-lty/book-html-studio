package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.List;
import java.util.*;

/** Produces evidence images for review issues without changing OCR text or review state. */
@Service
public class IssueImageService {
    private static final int CELL_WIDTH = 64;
    private static final int CELL_HEIGHT = 72;
    private static final int MAX_GLYPHS = 24;
    private static final int MAX_TEXT_CODEPOINTS = 2_000;
    private static final int MAX_CACHE_PAGES = 16;
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;

    private final BookService books;
    private final PdfService pdf;
    private final AppProperties config;
    private final ObjectMapper json;
    private final TraditionalConverter converter;
    private final LinkedHashMap<PageKey, CachedPage> cache = new LinkedHashMap<>(16, .75f, true);
    private final java.util.concurrent.ConcurrentHashMap<PageKey, Object> keyLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, FileLayouts> fileLayouts = new java.util.concurrent.ConcurrentHashMap<>();
    private long cachedBytes;

    public IssueImageService(BookService books, PdfService pdf, AppProperties config,
                             ObjectMapper json, TraditionalConverter converter) {
        this.books = books;
        this.pdf = pdf;
        this.config = config;
        this.json = json;
        this.converter = converter;
    }

    public Map<String, Snippet> locate(String bookId, Page page) {
        if (page == null || page.blocks() == null || page.blocks().stream().noneMatch(IssueImageService::hasIssues)) {
            return Map.of();
        }
        Path source = books.pdfPath(bookId);
        try {
            PageKey key = pageKey(bookId, page, source);
            CachedPage hit;
            synchronized (cache) { hit = cache.get(key); }
            if (hit != null) return copy(hit.snippets());
            // 阶段2：同页并发合并——同 PageKey 共用一次计算，避免重复高清渲染
            Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
            synchronized (lock) {
                try {
                    synchronized (cache) { hit = cache.get(key); }
                    if (hit != null) return copy(hit.snippets());
                    Map<String, Snippet> snippets = compute(source, page);
                    put(key, snippets);
                    return copy(snippets);
                } finally {
                    keyLocks.remove(key, lock);
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "疑点原图生成失败");
        }
    }

    /**
     * 阶段2：正文摘要——只返回文字与轻量疑点索引，不做高清渲染、不解析布局缓存。
     * 点击疑字后才经 {@link #locateOne} 按需生成精确证据。
     */
    public Map<String, Snippet> summarize(Page page) {
        if (page == null || page.blocks() == null || page.blocks().stream().noneMatch(IssueImageService::hasIssues)) {
            return Map.of();
        }
        LinkedHashMap<String, Snippet> result = new LinkedHashMap<>();
        for (Block block : page.blocks()) {
            if (!hasIssues(block) || block.issues() == null) continue;
            double[] bbox = block.bbox() == null ? new double[0] : block.bbox().clone();
            for (ContentIssue issue : block.issues()) {
                if (issue == null || issue.id() == null || issue.id().isBlank()) continue;
                if (result.containsKey(issue.id())) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "疑点标识重复，无法生成原图证据");
                }
                result.put(issue.id(), new Snippet("region", 0, bbox, List.of(), new byte[0], bbox, new byte[0]));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 阶段2：单疑点按需证据——同页并发只计算一次，失败只影响证据面板。 */
    public Snippet locateOne(String bookId, Page page, String issueId) {
        Map<String, Snippet> all = locate(bookId, page);
        return all.get(issueId);
    }

    private ImageArtifact renderForOcrArtifact(Path source, int pageNumber) throws IOException {
        ImageArtifact artifact = pdf.renderForOcrArtifact(source, pageNumber);
        if (artifact != null) return artifact;
        BufferedImage image = pdf.renderForOcr(source, pageNumber);
        if (image != null) return new ResourceBudgetManager().wrapImage(image);
        return null;
    }

    private Map<String, Snippet> compute(Path source, Page page) throws IOException {
        try (ImageArtifact artifact = renderForOcrArtifact(source, page.pageNumber())) {
            if (artifact == null || artifact.image() == null) {
                throw new IOException("PDF 渲染返回空图像");
            }
            BufferedImage image = artifact.image();
            try {
                // 阶段2：按本页来源直接定位布局，只解析相关缓存文件，不因他书缓存变化而失效
                Map<String, List<CacheLayout>> layouts = readLayoutsFor(neededLayoutIds(page));
                Map<String, Block> sourceRecords = new HashMap<>();
                if (page.sourceRecords() != null) for (Block block : page.sourceRecords()) sourceRecords.put(block.id(), block);
                LinkedHashMap<String, Snippet> result = new LinkedHashMap<>();
                for (Block block : page.blocks()) {
                    if (!hasIssues(block)) continue;
                    Block sourceBlock = sourceRecord(block, sourceRecords);
                    CacheLayout layout = findLayout(sourceBlock, layouts, image);
                    for (ContentIssue issue : block.issues()) {
                        if (issue == null || issue.id() == null || issue.id().isBlank()) continue;
                        if (result.containsKey(issue.id())) {
                            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "疑点标识重复，无法生成原图证据");
                        }
                        FocusRange focus = focusRange(block, issue);
                        Snippet snippet = precise(image, block, sourceBlock, issue, focus, layout);
                        if (snippet == null) snippet = region(image, block, sourceBlock, issue, focus, layout);
                        result.put(issue.id(), snippet);
                    }
                }
                return Collections.unmodifiableMap(result);
            } finally {
                image.flush();
            }
        }
    }

    private Snippet precise(BufferedImage image, Block block, Block sourceBlock, ContentIssue issue,
                            FocusRange focus, CacheLayout layout)
            throws IOException {
        if (layout == null || sourceBlock == null || sourceBlock.original() == null
                || !Objects.equals(block.original(), sourceBlock.original())
                || !validRange(focus, sourceBlock.original())) return null;
        String selected = sourceBlock.original().substring(focus.start(), focus.end());
        int glyphCount = selected.codePointCount(0, selected.length());
        if (glyphCount < 1 || glyphCount > MAX_GLYPHS || selected.codePoints().anyMatch(Character::isWhitespace)) return null;

        Snippet precise = preciseUsingSpans(image, block, sourceBlock, focus, layout, layout.spans(), glyphCount);
        if (precise != null) return precise;
        Span line = singleLineSpan(layout, block);
        return line == null ? null : preciseUsingSpans(image, block, sourceBlock, focus, layout, List.of(line), glyphCount);
    }

    private Snippet preciseUsingSpans(BufferedImage image, Block block, Block sourceBlock, FocusRange focus,
                                      CacheLayout layout, List<Span> spans, int glyphCount) throws IOException {
        if (spans.isEmpty()) return null;
        Alignment alignment = align(sourceBlock.original(), spans);
        if (alignment == null) return null;
        List<Unit> selectedUnits = tokens(sourceBlock.original()).stream()
                .filter(unit -> unit.start() >= focus.start() && unit.end() <= focus.end()).toList();
        if (selectedUnits.size() != glyphCount || selectedUnits.get(0).start() != focus.start()
                || selectedUnits.get(selectedUnits.size() - 1).end() != focus.end()) return null;

        List<SpanToken> targets = new ArrayList<>(glyphCount);
        int previous = -1;
        for (Unit unit : selectedUnits) {
            SpanToken target = alignment.bySourceStart().get(unit.start());
            if (target == null || (previous >= 0 && target.globalIndex() != previous + 1)) return null;
            targets.add(target);
            previous = target.globalIndex();
        }

        Map<Integer, Map<Integer, Rect>> split = new HashMap<>();
        Rect context = null;
        for (int spanIndex : targets.stream().map(SpanToken::spanIndex).distinct().toList()) {
            Span span = spans.get(spanIndex);
            if (span.text().codePoints().anyMatch(Character::isWhitespace)) return null;
            Rect full = toFull(span.rect(), layout, side(sourceBlock), image.getWidth(), image.getHeight());
            Set<Integer> indices = new TreeSet<>();
            targets.stream().filter(target -> target.spanIndex() == spanIndex).map(SpanToken::charIndex).forEach(indices::add);
            Map<Integer, Rect> cells = targetCells(image, full,
                    span.text().codePointCount(0, span.text().length()), vertical(layout, block), indices);
            if (cells == null) return null;
            split.put(spanIndex, cells);
            context = context == null ? full : context.union(full);
        }
        List<Rect> cells = new ArrayList<>(glyphCount);
        for (SpanToken target : targets) {
            Rect cell = split.getOrDefault(target.spanIndex(), Map.of()).get(target.charIndex());
            if (cell == null) return null;
            cells.add(cell);
        }
        byte[] png = atlas(image, cells);
        List<double[]> boxes = cells.stream().map(rect -> normalize(rect, image)).toList();
        Rect contextRect = pad(Objects.requireNonNull(context), Math.max(3, Math.round(Math.min(context.w(), context.h()) * .025)), image);
        return new Snippet("glyphs", glyphCount, union(boxes), boxes, png,
                normalize(contextRect, image), crop(image, contextRect));
    }

    private Snippet region(BufferedImage image, Block block, Block sourceBlock, ContentIssue issue,
                           FocusRange focus, CacheLayout layout)
            throws IOException {
        Rect rect = null;
        boolean unchangedSource = sourceBlock != null && Objects.equals(block.original(), sourceBlock.original());
        if (layout != null && unchangedSource) {
            Alignment alignment = sourceBlock != null && sourceBlock.original() != null
                    ? align(sourceBlock.original(), layout.spans()) : null;
            if (alignment != null && sourceBlock != null && validRange(focus, sourceBlock.original())) {
                for (Unit unit : tokens(sourceBlock.original())) {
                    if (unit.start() < focus.start() || unit.end() > focus.end()) continue;
                    SpanToken target = alignment.bySourceStart().get(unit.start());
                    if (target == null) { rect = null; break; }
                    Rect span = toFull(layout.spans().get(target.spanIndex()).rect(), layout, side(sourceBlock),
                            image.getWidth(), image.getHeight());
                    rect = rect == null ? span : rect.union(span);
                }
            }
            if (rect == null) rect = toFull(layout.rect(), layout, side(sourceBlock), image.getWidth(), image.getHeight());
        }
        if (rect == null) rect = fromNormalized(block.bbox(), image);
        rect = pad(rect, Math.max(3, Math.round(Math.min(rect.w(), rect.h()) * .025)), image);
        int count = validRange(focus, block.original())
                ? block.original().substring(focus.start(), focus.end()).codePointCount(0,
                block.original().substring(focus.start(), focus.end()).length()) : 0;
        byte[] region = crop(image, rect);
        return new Snippet("region", count, normalize(rect, image), List.of(), region,
                normalize(rect, image), region);
    }

    /**
     * 阶段2：按本页所需布局 ID 直接定位。缓存文件按路径+大小+修改时间逐文件缓存解析结果，
     * 未变化的文件只做 stat；他书缓存变化不再使本页失效。
     */
    private Map<String, List<CacheLayout>> readLayoutsFor(Set<String> neededIds) throws IOException {
        Map<String, List<CacheLayout>> result = new HashMap<>();
        if (neededIds.isEmpty()) return result;
        for (Path file : paddleCacheFiles()) {
            FileLayouts cached = cachedFileLayouts(file);
            for (String id : neededIds) {
                List<CacheLayout> layouts = cached.byId().get(id);
                if (layouts != null && !layouts.isEmpty()) {
                    result.computeIfAbsent(id, ignored -> new ArrayList<>()).addAll(layouts);
                }
            }
        }
        return result;
    }

    private static Set<String> neededLayoutIds(Page page) {
        Set<String> ids = new HashSet<>();
        if (page.sourceRecords() != null) for (Block source : page.sourceRecords()) {
            if (source == null || source.source() == null || !isPaddleFamily(source.source())) continue;
            String id = layoutId(source.id());
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private FileLayouts cachedFileLayouts(Path file) throws IOException {
        String key = file.toAbsolutePath().normalize().toString();
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        FileLayouts cached = fileLayouts.get(key);
        if (cached != null && cached.size() == size && cached.mtime() == mtime) return cached;
        Map<String, List<CacheLayout>> byId = parseCacheFile(file);
        FileLayouts fresh = new FileLayouts(size, mtime, byId);
        fileLayouts.put(key, fresh);
        return fresh;
    }

    private Map<String, List<CacheLayout>> parseCacheFile(Path file) throws IOException {
        Map<String, List<CacheLayout>> result = new HashMap<>();
        JsonNode document = json.readTree(file.toFile());
        JsonNode pages = document.path("result").path("pages");
        if (!pages.isArray()) return result;
        for (JsonNode page : pages) {
            double metaWidth = positive(page.path("meta").path("page_width").asDouble());
            double metaHeight = positive(page.path("meta").path("page_height").asDouble());
            if (metaWidth <= 0 || metaHeight <= 0) continue;
            JsonNode layouts = page.path("layouts");
            if (!layouts.isArray()) continue;
            for (JsonNode node : layouts) {
                String id = node.path("layout_id").asText("").strip();
                Rect rect = xywh(node.path("position"));
                if (id.isEmpty() || rect == null) continue;
                List<Span> spans = spans(node.path("span_boxes"));
                CacheLayout layout = new CacheLayout(id, node.path("type").asText(""),
                        node.path("text").asText(""), rect, spans, metaWidth, metaHeight);
                result.computeIfAbsent(id, ignored -> new ArrayList<>()).add(layout);
            }
        }
        Map<String, List<CacheLayout>> frozen = new HashMap<>();
        result.forEach((id, layouts) -> frozen.put(id, List.copyOf(layouts)));
        return frozen;
    }

    private CacheLayout findLayout(Block source, Map<String, List<CacheLayout>> layouts, BufferedImage image) {
        if (source == null || source.sourceRect() == null || source.sourceRect().length != 4
                || source.source() == null || !isPaddleFamily(source.source())) return null;
        String id = layoutId(source.id());
        List<CacheLayout> candidates = layouts.getOrDefault(id, List.of()).stream()
                .filter(layout -> close(layout.rect(), source.sourceRect()))
                .filter(layout -> canonical(layout.text()).equals(canonical(source.original())))
                .filter(layout -> dimensionCompatible(layout, source, image))
                .sorted(Comparator.comparingInt(layout -> rank(layout, source, image))).toList();
        if (candidates.isEmpty()) return null;
        int best = rank(candidates.get(0), source, image);
        if (candidates.size() > 1 && rank(candidates.get(1), source, image) == best
                && !sameLayout(candidates.get(0), candidates.get(1))) return null;
        return candidates.get(0);
    }

    private int rank(CacheLayout layout, Block source, BufferedImage image) {
        int text = Objects.equals(layout.text(), source.original()) ? 0
                : canonical(layout.text()).equals(canonical(source.original())) ? 10 : 100;
        double expectedWidth = side(source) == Side.FULL ? image.getWidth() : image.getWidth() / 2d;
        return text + (int)Math.min(50, Math.abs(layout.metaWidth() / layout.metaHeight()
                - expectedWidth / image.getHeight()) * 100);
    }

    private static boolean dimensionCompatible(CacheLayout layout, Block source, BufferedImage image) {
        double expectedWidth = side(source) == Side.FULL ? image.getWidth() : image.getWidth() / 2d;
        double expectedRatio = expectedWidth / image.getHeight();
        double actualRatio = layout.metaWidth() / layout.metaHeight();
        return Double.isFinite(actualRatio) && actualRatio > 0
                && Math.abs(Math.log(actualRatio / expectedRatio)) <= .08;
    }

    private Alignment align(String source, List<Span> spans) {
        if (source == null || spans.isEmpty()) return null;
        List<Unit> a = tokens(source);
        List<SpanToken> b = spanTokens(spans);
        if (a.isEmpty() || b.isEmpty() || a.size() > MAX_TEXT_CODEPOINTS || b.size() > MAX_TEXT_CODEPOINTS) return null;
        int[][] distance = new int[a.size() + 1][b.size() + 1];
        for (int i = 0; i <= a.size(); i++) distance[i][0] = i;
        for (int j = 0; j <= b.size(); j++) distance[0][j] = j;
        for (int i = 1; i <= a.size(); i++) for (int j = 1; j <= b.size(); j++) {
            int replace = distance[i - 1][j - 1] + (a.get(i - 1).canonical().equals(b.get(j - 1).canonical()) ? 0 : 2);
            distance[i][j] = Math.min(replace, Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1));
        }
        if (distance[a.size()][b.size()] > Math.ceil(Math.max(a.size(), b.size()) * .18)) return null;
        Map<Integer, SpanToken> mapping = new HashMap<>();
        int i = a.size(), j = b.size();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a.get(i - 1).canonical().equals(b.get(j - 1).canonical())
                    && distance[i][j] == distance[i - 1][j - 1]) {
                mapping.put(a.get(i - 1).start(), b.get(j - 1)); i--; j--;
            } else if (i > 0 && distance[i][j] == distance[i - 1][j] + 1) i--;
            else if (j > 0) j--;
            else return null;
        }
        return new Alignment(mapping);
    }

    private Map<Integer, Rect> targetCells(BufferedImage image, Rect source, int count, boolean vertical,
                                           Set<Integer> targets) {
        Rect rect = clamp(source, image);
        int length = (int)Math.round(vertical ? rect.h() : rect.w());
        int cross = (int)Math.round(vertical ? rect.w() : rect.h());
        if (count < 1 || targets.isEmpty() || targets.stream().anyMatch(index -> index < 0 || index >= count)
                || length / (double)count < 6 || cross < 3) return null;
        int[] ink = projection(image, rect, vertical, length);
        double average = Arrays.stream(ink).average().orElse(0);
        if (average <= 0) return null;
        double expected = length / (double)count;
        Map<Integer, Integer> boundaries = new HashMap<>();
        boundaries.put(0, 0); boundaries.put(count, length);
        for (int target : targets) {
            for (int boundary : new int[]{target, target + 1}) {
                if (boundaries.containsKey(boundary)) continue;
                Integer position = localBoundary(ink, boundary, count, expected, average);
                if (position == null) return null;
                boundaries.put(boundary, position);
            }
        }
        Map<Integer, Rect> result = new HashMap<>();
        for (int target : targets) {
            int start = boundaries.get(target), end = boundaries.get(target + 1), size = end - start;
            if (size < expected * .42 || size > expected * 1.7) return null;
            long cellInk = 0;
            for (int p = start; p < end; p++) cellInk += ink[p];
            if (cellInk < Math.max(2, cross * size * .0015)) return null;
            result.put(target, vertical
                    ? new Rect(rect.x(), rect.y() + start, rect.w(), size)
                    : new Rect(rect.x() + start, rect.y(), size, rect.h()));
        }
        return result;
    }

    private static Integer localBoundary(int[] ink, int boundary, int count, double expected, double average) {
        int center = (int)Math.round(boundary * expected);
        int radius = Math.max(2, (int)Math.floor(expected * .28));
        int from = Math.max(2, center - radius), to = Math.min(ink.length - 3, center + radius);
        if (from > to) return null;
        int minimum = Integer.MAX_VALUE;
        for (int position = from; position <= to; position++) minimum = Math.min(minimum, smooth(ink, position));
        if (minimum > average * .48) return null;
        int tolerance = Math.max(1, (int)Math.floor(average * .06));
        int best = -1, bestDistance = Integer.MAX_VALUE;
        for (int position = from; position <= to; position++) {
            if (smooth(ink, position) > minimum + tolerance) continue;
            int distance = Math.abs(position - center);
            if (distance < bestDistance) { best = position; bestDistance = distance; }
        }
        return best < 0 || bestDistance > expected * .28 ? null : best;
    }

    private static int[] projection(BufferedImage image, Rect rect, boolean vertical, int length) {
        int x0 = (int)Math.floor(rect.x()), y0 = (int)Math.floor(rect.y());
        int w = Math.max(1, (int)Math.ceil(rect.w())), h = Math.max(1, (int)Math.ceil(rect.h()));
        int[] projection = new int[length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int rgb = image.getRGB(Math.min(image.getWidth() - 1, x0 + x), Math.min(image.getHeight() - 1, y0 + y));
            int gray = (((rgb >> 16) & 255) * 30 + ((rgb >> 8) & 255) * 59 + (rgb & 255) * 11) / 100;
            if (gray < 205) projection[Math.min(length - 1, vertical ? y : x)]++;
        }
        return projection;
    }

    private static int smooth(int[] values, int at) {
        int total = 0, count = 0;
        for (int i = Math.max(0, at - 2); i <= Math.min(values.length - 1, at + 2); i++) { total += values[i]; count++; }
        return total / Math.max(1, count);
    }

    private byte[] atlas(BufferedImage source, List<Rect> cells) throws IOException {
        BufferedImage atlas = new BufferedImage(CELL_WIDTH * cells.size(), CELL_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = atlas.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, atlas.getWidth(), atlas.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for (int i = 0; i < cells.size(); i++) {
                Rect rect = pad(cells.get(i), 2, source);
                int x = (int)Math.floor(rect.x()), y = (int)Math.floor(rect.y());
                int w = Math.max(1, (int)Math.ceil(rect.w())), h = Math.max(1, (int)Math.ceil(rect.h()));
                double scale = Math.min((CELL_WIDTH - 8d) / w, (CELL_HEIGHT - 8d) / h);
                int dw = Math.max(1, (int)Math.round(w * scale)), dh = Math.max(1, (int)Math.round(h * scale));
                int dx = i * CELL_WIDTH + (CELL_WIDTH - dw) / 2, dy = (CELL_HEIGHT - dh) / 2;
                graphics.drawImage(source, dx, dy, dx + dw, dy + dh, x, y, x + w, y + h, null);
            }
        } finally { graphics.dispose(); }
        try { return encode(atlas); } finally { atlas.flush(); }
    }

    private static byte[] crop(BufferedImage source, Rect raw) throws IOException {
        Rect rect = clamp(raw, source);
        int x = (int)Math.floor(rect.x()), y = (int)Math.floor(rect.y());
        int w = Math.max(1, Math.min(source.getWidth() - x, (int)Math.ceil(rect.w())));
        int h = Math.max(1, Math.min(source.getHeight() - y, (int)Math.ceil(rect.h())));
        BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try { graphics.drawImage(source, 0, 0, w, h, x, y, x + w, y + h, null); }
        finally { graphics.dispose(); }
        try { return encode(copy); } finally { copy.flush(); }
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
        return output.toByteArray();
    }

    /**
     * 阶段2：页级缓存键只包含本 PDF 指纹、本页内容哈希与本页相关布局版本；
     * 其他书籍的缓存变化不再使本页失效。
     */
    private PageKey pageKey(String bookId, Page page, Path pdfPath) throws IOException {
        long pdfStamp = Files.size(pdfPath) * 31 + Files.getLastModifiedTime(pdfPath).toMillis();
        return new PageKey(bookId, page.pageNumber(), pdfStamp, pageHash(page), layoutVersion(neededLayoutIds(page)));
    }

    private long layoutVersion(Set<String> neededIds) throws IOException {
        if (neededIds.isEmpty()) return 0;
        long version = 1;
        for (Path file : paddleCacheFiles()) {
            FileLayouts cached;
            try {
                cached = cachedFileLayouts(file);
            } catch (IOException e) {
                version = version * 31 + file.toString().hashCode();
                continue;
            }
            boolean contributes = neededIds.stream().anyMatch(id -> cached.byId().containsKey(id));
            if (!contributes) continue;
            version = version * 31 + file.toString().hashCode();
            version = version * 31 + cached.size();
            version = version * 31 + cached.mtime();
        }
        return version;
    }

    private List<Path> paddleCacheFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String channel : List.of("paddle", "paddle-aistudio", "ppocr")) {
            Path root = config.dataDir().toAbsolutePath().normalize().resolve("cache").resolve(channel);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var stream = Files.list(root)) {
                files.addAll(stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList());
            }
        }
        return files;
    }

    private static long pageHash(Page page) {
        long hash = 17;
        for (Block block : page.blocks()) {
            hash = hash * 31 + Objects.hash(block.id(), block.original(), block.simplified(), block.source());
            hash = hash * 31 + Arrays.hashCode(block.bbox());
            for (ContentIssue issue : block.issues()) hash = hash * 31 + Objects.hash(issue.id(), issue.start(), issue.end(),
                    issue.simplifiedStart(), issue.simplifiedEnd(), issue.resolved(), issue.replacement(), issue.inferredText());
        }
        if (page.sourceRecords() != null) for (Block block : page.sourceRecords()) {
            hash = hash * 31 + Objects.hash(block.id(), block.original(), block.source());
            hash = hash * 31 + Arrays.hashCode(block.sourceRect());
        }
        return hash;
    }

    private void put(PageKey key, Map<String, Snippet> value) {
        long bytes = value.values().stream().mapToLong(item -> (long)item.png().length + item.contextPng().length).sum();
        if (bytes > MAX_CACHE_BYTES) return;
        synchronized (cache) {
            CachedPage prior = cache.put(key, new CachedPage(value, bytes));
            if (prior != null) cachedBytes -= prior.bytes();
            cachedBytes += bytes;
            Iterator<Map.Entry<PageKey, CachedPage>> iterator = cache.entrySet().iterator();
            while ((cache.size() > MAX_CACHE_PAGES || cachedBytes > MAX_CACHE_BYTES) && iterator.hasNext()) {
                Map.Entry<PageKey, CachedPage> eldest = iterator.next();
                cachedBytes -= eldest.getValue().bytes(); iterator.remove();
            }
        }
    }

    private static Map<String, Snippet> copy(Map<String, Snippet> input) {
        LinkedHashMap<String, Snippet> output = new LinkedHashMap<>();
        input.forEach((id, snippet) -> output.put(id, new Snippet(snippet.mode(), snippet.glyphCount(),
                snippet.bbox(), snippet.boxes(), snippet.png(), snippet.contextBbox(), snippet.contextPng())));
        return Collections.unmodifiableMap(output);
    }

    private static Block sourceRecord(Block block, Map<String, Block> records) {
        Block direct = records.get(block.id());
        if (direct != null) return direct;
        if (block.sourceIds() != null && block.sourceIds().size() == 1) return records.get(block.sourceIds().get(0));
        return null;
    }

    private List<Unit> tokens(String text) {
        if (text == null) return List.of();
        List<Unit> result = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset), end = offset + Character.charCount(cp);
            result.add(new Unit(offset, end, canonical(new String(Character.toChars(cp))))); offset = end;
        }
        return result;
    }

    private List<SpanToken> spanTokens(List<Span> spans) {
        List<SpanToken> result = new ArrayList<>(); int global = 0;
        for (int spanIndex = 0; spanIndex < spans.size(); spanIndex++) {
            String text = spans.get(spanIndex).text(); int charIndex = 0;
            for (int offset = 0; offset < text.length();) {
                int cp = text.codePointAt(offset); offset += Character.charCount(cp);
                result.add(new SpanToken(spanIndex, charIndex++, global++, canonical(new String(Character.toChars(cp)))));
            }
        }
        return result;
    }

    private String canonical(String text) {
        return converter.toSimplified(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC));
    }

    private FocusRange focusRange(Block block, ContentIssue issue) {
        FocusRange original = new FocusRange(issue.start(), issue.end());
        if (block.original() == null || !validRange(original, block.original())
                || issue.inferredText() == null || issue.inferredText().isEmpty()) return original;
        String selected = block.original().substring(issue.start(), issue.end());
        List<Unit> units = tokens(selected);
        if (units.isEmpty()) return original;
        StringBuilder perCharacter = new StringBuilder();
        for (Unit unit : units) {
            if (unit.canonical().codePointCount(0, unit.canonical().length()) != 1) return original;
            perCharacter.append(unit.canonical());
        }
        String simplified = canonical(selected);
        if (!simplified.contentEquals(perCharacter)
                || simplified.codePointCount(0, simplified.length()) != units.size()) return original;
        String inferred = canonical(issue.inferredText());
        int[] sourcePoints = simplified.codePoints().toArray(), inferredPoints = inferred.codePoints().toArray();
        int prefix = 0;
        while (prefix < sourcePoints.length && prefix < inferredPoints.length
                && sourcePoints[prefix] == inferredPoints[prefix]) prefix++;
        int suffix = 0;
        while (suffix < sourcePoints.length - prefix && suffix < inferredPoints.length - prefix
                && sourcePoints[sourcePoints.length - 1 - suffix] == inferredPoints[inferredPoints.length - 1 - suffix]) suffix++;
        int sourceEnd = sourcePoints.length - suffix, inferredEnd = inferredPoints.length - suffix;
        if (prefix >= sourceEnd || prefix >= inferredEnd) return original;
        int start = issue.start() + selected.offsetByCodePoints(0, prefix);
        int end = issue.start() + selected.offsetByCodePoints(0, sourceEnd);
        return start < end ? new FocusRange(start, end) : original;
    }

    private static List<Span> spans(JsonNode nodes) {
        if (!nodes.isArray()) return List.of();
        List<Span> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            String text = node.path("text").asText(""); Rect rect = points(node.path("location"));
            if (!text.isEmpty() && rect != null) result.add(new Span(text, rect));
        }
        return List.copyOf(result);
    }

    private static Rect points(JsonNode node) {
        if (!node.isArray()) return null;
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        if (node.size() >= 4 && node.get(0).isArray()) {
            for (JsonNode point : node) if (point.isArray() && point.size() >= 2) { xs.add(point.get(0).asDouble()); ys.add(point.get(1).asDouble()); }
        } else if (node.size() >= 8) {
            for (int i = 0; i + 1 < node.size(); i += 2) { xs.add(node.get(i).asDouble()); ys.add(node.get(i + 1).asDouble()); }
        }
        if (xs.size() < 2) return null;
        double minX = Collections.min(xs), maxX = Collections.max(xs), minY = Collections.min(ys), maxY = Collections.max(ys);
        return maxX > minX && maxY > minY ? new Rect(minX, minY, maxX - minX, maxY - minY) : null;
    }

    private static Rect xywh(JsonNode node) {
        if (!node.isArray() || node.size() != 4) return null;
        double x = node.get(0).asDouble(), y = node.get(1).asDouble(), w = node.get(2).asDouble(), h = node.get(3).asDouble();
        return x >= 0 && y >= 0 && w > 0 && h > 0 ? new Rect(x, y, w, h) : null;
    }

    private static Side side(Block block) {
        if (block == null) return Side.FULL;
        if ((block.source() != null && block.source().endsWith(":L")) || block.id().startsWith("L-")) return Side.LEFT;
        if ((block.source() != null && block.source().endsWith(":R")) || block.id().startsWith("R-")) return Side.RIGHT;
        return Side.FULL;
    }

    private static Rect toFull(Rect local, CacheLayout layout, Side side, int width, int height) {
        double partX = side == Side.RIGHT ? width / 2d : 0;
        double partWidth = side == Side.FULL ? width : (side == Side.LEFT ? Math.floor(width / 2d) : width - Math.floor(width / 2d));
        return new Rect(partX + local.x() / layout.metaWidth() * partWidth,
                local.y() / layout.metaHeight() * height,
                local.w() / layout.metaWidth() * partWidth,
                local.h() / layout.metaHeight() * height);
    }

    private static Rect fromNormalized(double[] bbox, BufferedImage image) {
        if (bbox == null || bbox.length != 4 || bbox[0] < 0 || bbox[1] < 0 || bbox[2] <= 0 || bbox[3] <= 0
                || bbox[0] + bbox[2] > 1.000001 || bbox[1] + bbox[3] > 1.000001) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "疑点原图坐标无效");
        }
        return new Rect(bbox[0] * image.getWidth(), bbox[1] * image.getHeight(),
                bbox[2] * image.getWidth(), bbox[3] * image.getHeight());
    }

    private static Rect pad(Rect rect, double pixels, BufferedImage image) {
        return clamp(new Rect(rect.x() - pixels, rect.y() - pixels, rect.w() + pixels * 2, rect.h() + pixels * 2), image);
    }

    private static Rect clamp(Rect rect, BufferedImage image) {
        double x = Math.max(0, Math.min(image.getWidth() - 1, rect.x()));
        double y = Math.max(0, Math.min(image.getHeight() - 1, rect.y()));
        double right = Math.max(x + 1, Math.min(image.getWidth(), rect.x() + rect.w()));
        double bottom = Math.max(y + 1, Math.min(image.getHeight(), rect.y() + rect.h()));
        return new Rect(x, y, right - x, bottom - y);
    }

    private static double[] normalize(Rect rect, BufferedImage image) {
        Rect safe = clamp(rect, image);
        return new double[]{safe.x() / image.getWidth(), safe.y() / image.getHeight(),
                safe.w() / image.getWidth(), safe.h() / image.getHeight()};
    }

    private static double[] union(List<double[]> boxes) {
        double x1 = 1, y1 = 1, x2 = 0, y2 = 0;
        for (double[] box : boxes) { x1 = Math.min(x1, box[0]); y1 = Math.min(y1, box[1]); x2 = Math.max(x2, box[0] + box[2]); y2 = Math.max(y2, box[1] + box[3]); }
        return new double[]{x1, y1, x2 - x1, y2 - y1};
    }

    private static boolean vertical(CacheLayout layout, Block block) {
        return "vertical_text".equals(layout.type()) || (block.writingMode() != null && block.writingMode().startsWith("vertical"));
    }

    private static Span singleLineSpan(CacheLayout layout, Block block) {
        String text = layout.text();
        if (text == null || text.isEmpty() || text.codePoints().anyMatch(Character::isWhitespace)) return null;
        int count = text.codePointCount(0, text.length());
        if (count < 1 || count > MAX_TEXT_CODEPOINTS) return null;
        boolean vertical = vertical(layout, block);
        double pitch = (vertical ? layout.rect().h() : layout.rect().w()) / count;
        double cross = vertical ? layout.rect().w() : layout.rect().h();
        double ratio = cross / pitch;
        return pitch >= 6 && ratio >= .35 && ratio <= 2.2 ? new Span(text, layout.rect()) : null;
    }
    private static boolean validRange(ContentIssue issue, String text) { return text != null && issue.start() >= 0 && issue.end() > issue.start() && issue.end() <= text.length(); }
    private static boolean validRange(FocusRange range, String text) { return text != null && range.start() >= 0 && range.end() > range.start() && range.end() <= text.length(); }
    private static boolean hasIssues(Block block) { return block != null && block.issues() != null && !block.issues().isEmpty(); }
    private static double positive(double value) { return Double.isFinite(value) && value > 0 ? value : -1; }
    private static boolean close(Rect rect, double[] raw) { return Math.abs(rect.x() - raw[0]) < .51 && Math.abs(rect.y() - raw[1]) < .51 && Math.abs(rect.w() - raw[2]) < .51 && Math.abs(rect.h() - raw[3]) < .51; }
    private static boolean sameLayout(CacheLayout a, CacheLayout b) { return a.equals(b); }
    private static String layoutId(String id) { String value = id == null ? "" : id; if (value.startsWith("L-") || value.startsWith("R-")) value = value.substring(2); if (value.startsWith("paddle-")) value = value.substring(7); else if (value.startsWith("ppocr-")) value = value.substring(6); return value; }

    static boolean isPaddleFamily(String source) { return source != null && (source.startsWith("paddle") || source.startsWith("ppocr")); }

    public record Snippet(String mode, int glyphCount, double[] bbox, List<double[]> boxes, byte[] png,
                          double[] contextBbox, byte[] contextPng) {
        public Snippet(String mode, int glyphCount, double[] bbox, List<double[]> boxes, byte[] png) {
            this(mode, glyphCount, bbox, boxes, png, bbox, png);
        }
        public Snippet {
            if (!Set.of("glyphs", "region").contains(mode)) throw new IllegalArgumentException("Unsupported snippet mode");
            bbox = bbox == null ? new double[0] : bbox.clone();
            boxes = boxes == null ? List.of() : boxes.stream().map(double[]::clone).toList();
            png = png == null ? new byte[0] : png.clone();
            contextBbox = contextBbox == null ? bbox.clone() : contextBbox.clone();
            contextPng = contextPng == null ? png.clone() : contextPng.clone();
        }
        @Override public double[] bbox() { return bbox.clone(); }
        @Override public List<double[]> boxes() { return boxes.stream().map(double[]::clone).toList(); }
        @Override public byte[] png() { return png.clone(); }
        @Override public double[] contextBbox() { return contextBbox.clone(); }
        @Override public byte[] contextPng() { return contextPng.clone(); }
    }

    private enum Side { LEFT, RIGHT, FULL }
    private record PageKey(String bookId, int page, long pdfStamp, long pageHash, long layoutVersion) {}
    private record FileLayouts(long size, long mtime, Map<String, List<CacheLayout>> byId) {}
    private record CachedPage(Map<String, Snippet> snippets, long bytes) {}
    private record CacheLayout(String id, String type, String text, Rect rect, List<Span> spans, double metaWidth, double metaHeight) {}
    private record Span(String text, Rect rect) {}
    private record Unit(int start, int end, String canonical) {}
    private record SpanToken(int spanIndex, int charIndex, int globalIndex, String canonical) {}
    private record Alignment(Map<Integer, SpanToken> bySourceStart) {}
    private record FocusRange(int start, int end) {}
    private record Rect(double x, double y, double w, double h) {
        Rect union(Rect other) { double left = Math.min(x, other.x), top = Math.min(y, other.y); return new Rect(left, top, Math.max(x + w, other.x + other.w) - left, Math.max(y + h, other.y + other.h) - top); }
    }
}
