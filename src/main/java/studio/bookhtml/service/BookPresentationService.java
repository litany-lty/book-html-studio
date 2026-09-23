package studio.bookhtml.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.BookLayoutProfile;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.PagePresentation;
import studio.bookhtml.domain.PageSummary;
import studio.bookhtml.domain.PresentationOverride;
import studio.bookhtml.store.BookStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * U3：书籍级结构投影。在存储页面之上产生只读展示投影，区分书名、书眉、
 * 页脚、章标题、节标题与目录页条目；不用“和书名一样就删除”的黑名单。
 *
 * <p>判定流水线：证据索引 → 版式分组 → 重复边缘簇 → 章节/书眉区分 → 目录发布。
 * 分类落到“某页的某个块”，不把字符串在全书全局隐藏（首现页暂留候选为例外）。
 */
@Service
public class BookPresentationService {
    public static final String POLICY_VERSION = "u3.1";

    // 首版可解释规则参数（待场景夹具与留出样本验证，不是准确率承诺）。
    static final int MIN_OBSERVED_PAGES = 5;
    static final int MIN_CLUSTER_PAGES = 3;
    static final double MIN_CLUSTER_COVERAGE = 0.6;
    static final double EDGE_BAND = 0.12;
    static final double EDGE_THICKNESS = 0.08;
    static final double POSITION_TOLERANCE = 0.03;

    private final BookStore store;
    private PresentationOverrideService overrides;
    // U6：画像内存缓存；失效唯一来源是存储变更通知（保守：任何页/书变更即失效）。
    private final Map<String, BookLayoutProfile> profileCache = new ConcurrentHashMap<>();
    private final Object cacheLock = new Object();
    private final AtomicLong changeGeneration = new AtomicLong();
    private final Set<String> warming = ConcurrentHashMap.newKeySet();
    private final Object[] buildLocks = new Object[32];
    private final Map<String, Long> lastWarm = new LinkedHashMap<>();
    private final ExecutorService warmExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "reader-layout-profile"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong cacheHits = new AtomicLong();

    public BookPresentationService(BookStore store) {
        this.store = store;
        Arrays.setAll(buildLocks, ignored -> new Object());
        store.addChangeListener(id -> {
            synchronized (cacheLock) { changeGeneration.incrementAndGet(); profileCache.remove(id); }
        });
    }

    @Autowired(required = false)
    public void setOverrides(PresentationOverrideService overrides) {
        this.overrides = overrides;
    }

    public PresentationOverrideService overrides() {
        return overrides;
    }

    // ---------- 规范化与几何 ----------

    /** 文本比较只用于索引；原文字形不改。统一常见空白与外层书名括号。 */
    public static String normalizeEdgeText(String text) {
        if (text == null) return "";
        String compact = text.strip().replaceAll("\\s+", "");
        while (compact.length() >= 2 && isOuterBracketPair(compact)) {
            compact = compact.substring(1, compact.length() - 1).strip();
        }
        return compact;
    }

    private static boolean isOuterBracketPair(String text) {
        char first = text.charAt(0), last = text.charAt(text.length() - 1);
        return (first == '【' && last == '】') || (first == '「' && last == '」')
                || (first == '『' && last == '』') || (first == '《' && last == '》')
                || (first == '〈' && last == '〉') || (first == '(' && last == ')')
                || (first == '（' && last == '）');
    }

    /** 原文（转录本身），不含任何模型推测。匹配用简体优先、无则原文。 */
    public static String displayOriginal(Block block) {
        if (block == null) return "";
        String simplified = block.simplified();
        if (simplified != null && !simplified.isBlank()) return simplified;
        return block.original() == null ? "" : block.original();
    }

    /** 边缘带：TOP（页眉）/ BOTTOM（页脚），厚度超过上限则不算边缘。 */
    public static String edgeZoneOf(Block block) {
        if (block == null || block.bbox() == null || block.bbox().length < 4) return null;
        double y = block.bbox()[1], h = block.bbox()[3];
        if (h <= 0 || h > EDGE_THICKNESS) return null;
        if (y <= EDGE_BAND) return "TOP";
        if (y + h >= 1.0 - EDGE_BAND) return "BOTTOM";
        return null;
    }

    static String positionBucket(Block block, String zone) {
        double[] bbox = block.bbox();
        double anchor = "TOP".equals(zone) ? bbox[1] : bbox[1] + bbox[3];
        long bucket = Math.round(anchor / POSITION_TOLERANCE);
        return zone.charAt(0) + String.valueOf(bucket);
    }

    /** 可比版式组：纵横 + 主导书写方向 + PDF 奇偶（仅分组，不假定印刷页码映射）。 */
    static String layoutGroup(Page page) {
        String aspect = (page.width() > page.height() * 1.15) ? "SPREAD" : "SINGLE";
        Map<String, Integer> votes = new HashMap<>();
        if (page.blocks() != null) {
            for (Block block : page.blocks()) {
                if (block == null) continue;
                String mode = block.writingMode() == null ? "horizontal-tb" : block.writingMode();
                votes.merge(mode, 1, Integer::sum);
            }
        }
        String writing = votes.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("horizontal-tb");
        String parity = page.pageNumber() % 2 == 0 ? "EVEN" : "ODD";
        return aspect + "|" + writing + "|" + parity;
    }

    /**
     * 章起始反证（强证据）：处于版面内部的标题一定是内容，不是边缘书眉。
     * 边缘位置的区分由簇内首次出现规则承担。
     */
    public static boolean hasChapterStartEvidence(Page page, Block block) {
        if (block == null || !"heading".equals(block.type())) return false;
        return edgeZoneOf(block) == null;
    }

    // ---------- 画像构建 ----------

    public BookLayoutProfile buildProfile(String bookId) {
        synchronized (buildLocks[Math.floorMod(bookId.hashCode(), buildLocks.length)]) {
            return buildProfileOnce(bookId);
        }
    }

    private BookLayoutProfile buildProfileOnce(String bookId) {
        final long generation = changeGeneration.get();
        // U6：无变更直接返回缓存（失效唯一来源是存储变更通知），避免每页请求全书扫描。
        BookLayoutProfile cached = profileCache.get(bookId);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        Book book = store.readBook(bookId);
        List<Page> observed = new ArrayList<>();
        for (int n = 1; n <= book.totalPages(); n++) {
            Page page = store.readPage(bookId, n);
            if (page != null && "READY".equals(page.status())) observed.add(page);
        }
        Map<String, Integer> groupObserved = new HashMap<>();
        for (Page page : observed) {
            groupObserved.merge(layoutGroup(page), 1, Integer::sum);
        }
        Map<String, Set<Integer>> clusterPages = new HashMap<>();
        Map<String, String[]> clusterMeta = new HashMap<>();
        for (Page page : observed) {
            String group = layoutGroup(page);
            if (page.blocks() == null) continue;
            for (Block block : page.blocks()) {
                if (block == null) continue;
                if (!"heading".equals(block.type()) && !"text".equals(block.type())) continue;
                String normalized = normalizeEdgeText(displayOriginal(block));
                if (normalized.isEmpty() || normalized.length() > 48) continue;
                String zone = edgeZoneOf(block);
                if (zone == null) continue;
                String key = group + "|" + zone + "|" + positionBucket(block, zone) + "|" + normalized;
                clusterPages.computeIfAbsent(key, k -> new HashSet<>()).add(page.pageNumber());
                clusterMeta.putIfAbsent(key, new String[]{normalized, zone, group});
            }
        }
        List<BookLayoutProfile.EdgeCluster> clusters = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> entry : clusterPages.entrySet()) {
            String[] meta = clusterMeta.get(entry.getKey());
            int groupCount = groupObserved.getOrDefault(meta[2], 0);
            if (groupCount < MIN_OBSERVED_PAGES) continue;
            Set<Integer> pages = entry.getValue();
            if (pages.size() < MIN_CLUSTER_PAGES) continue;
            double coverage = groupCount == 0 ? 0 : (double) pages.size() / groupCount;
            if (coverage < MIN_CLUSTER_COVERAGE) continue;
            List<Integer> sorted = new ArrayList<>(pages);
            Collections.sort(sorted);
            clusters.add(new BookLayoutProfile.EdgeCluster(meta[0], meta[1], meta[2],
                    pages.size(), coverage, sorted));
        }
        clusters.sort(Comparator.comparing(BookLayoutProfile.EdgeCluster::layoutGroup)
                .thenComparing(BookLayoutProfile.EdgeCluster::edgeZone)
                .thenComparing(BookLayoutProfile.EdgeCluster::normalizedText));
        // U6：缓存未命中时读 sidecar 比对（崩溃重启后恢复连续性）。
        BookLayoutProfile previous =
                store.readSidecar(store.layoutProfilePath(bookId), BookLayoutProfile.class);
        BookLayoutProfile candidate = new BookLayoutProfile(bookId,
                previous == null ? 1 : previous.profileRevision(),
                POLICY_VERSION, observed.size(), clusters, java.time.Instant.now());
        if (previous != null && previous.clusterSignature().equals(candidate.clusterSignature())) {
            cacheIfUnchanged(bookId, previous, generation);
            return previous;
        }
        BookLayoutProfile published = new BookLayoutProfile(bookId,
                previous == null ? 1 : previous.profileRevision() + 1,
                POLICY_VERSION, observed.size(), clusters, java.time.Instant.now());
        try {
            store.writeSidecar(store.layoutProfilePath(bookId), published);
        } catch (Exception ignored) {
            // sidecar 写失败不阻塞原稿打开；调用方继续使用内存画像。
        }
        cacheIfUnchanged(bookId, published, generation);
        return published;
    }

    private void cacheIfUnchanged(String id, BookLayoutProfile profile, long generation) {
        synchronized (cacheLock) {
            if (generation != changeGeneration.get()) return;
            if (profileCache.size() >= 128) profileCache.remove(profileCache.keySet().iterator().next());
            profileCache.put(id, profile);
        }
    }

    /** A polling/reader request never waits for a whole-book scan. Warm work is coalesced and bounded. */
    public BookLayoutProfile profileForReader(String bookId) {
        BookLayoutProfile cached = profileCache.get(bookId);
        if (cached != null) return cached;
        synchronized (lastWarm) {
            long now = System.nanoTime();
            Long last = lastWarm.get(bookId);
            if ((last == null || now - last >= TimeUnit.SECONDS.toNanos(2)) && warming.add(bookId)) {
                if (lastWarm.size() >= 128) lastWarm.remove(lastWarm.keySet().iterator().next());
                lastWarm.put(bookId, now);
                try { warmExecutor.execute(() -> {
                    try { buildProfile(bookId); } catch (RuntimeException ignored) { /* Source remains readable. */ }
                    finally { warming.remove(bookId); }
                }); } catch (RejectedExecutionException full) { warming.remove(bookId); }
            }
        }
        return new BookLayoutProfile(bookId, 0, POLICY_VERSION, 0, List.of(), java.time.Instant.EPOCH);
    }

    @jakarta.annotation.PreDestroy
    public void close() { warmExecutor.shutdownNow(); }

    /** U6：测试可见的缓存命中计数。 */
    long cacheHits() {
        return cacheHits.get();
    }

    private Optional<BookLayoutProfile.EdgeCluster> matchingCluster(BookLayoutProfile profile,
                                                                   Page page, Block block) {
        String normalized = normalizeEdgeText(displayOriginal(block));
        if (normalized.isEmpty()) return Optional.empty();
        String zone = edgeZoneOf(block);
        if (zone == null) return Optional.empty();
        String group = layoutGroup(page);
        return profile.edgeClusters().stream()
                .filter(c -> c.normalizedText().equals(normalized)
                        && c.edgeZone().equals(zone)
                        && c.layoutGroup().equals(group))
                .findFirst();
    }

    // ---------- 投影 ----------

    /** First-paint path: never scans all pages or guesses a full-book profile. */
    public PagePresentation projectCached(String bookId, Page page) {
        BookLayoutProfile profile = profileCache.get(bookId);
        if (profile == null) profile = new BookLayoutProfile(bookId, 0, POLICY_VERSION, 0,
                List.of(), java.time.Instant.EPOCH);
        return project(bookId, page, profile);
    }

    public PagePresentation project(String bookId, Page page) {
        return project(bookId, page, buildProfile(bookId));
    }

    public PagePresentation project(String bookId, Page page, BookLayoutProfile profile) {
        Book book = store.readBook(bookId);
        // U6：角色判定基于归一化视角（关系图/不可靠矩阵已转 figure、页码已归位），
        // 内容与身份仍用存储块；复杂版式按证据降级，不拼错误正文。
        Page normalizedView = ReadingStructureNormalizer.normalize(page);
        Map<String, String> normalizedTypes = new HashMap<>();
        if (normalizedView.blocks() != null) {
            for (Block block : normalizedView.blocks()) {
                if (block != null && block.id() != null) {
                    normalizedTypes.putIfAbsent(block.id(), block.type());
                }
            }
        }
        List<PagePresentation.BlockPresentation> out = new ArrayList<>();
        List<Block> blocks = page.blocks() == null ? List.of() : page.blocks();
        int order = 0;
        for (Block block : blocks) {
            if (block == null) continue;
            String normalizedType = normalizedTypes.getOrDefault(block.id(), block.type());
            out.add(projectBlock(book, page, block, normalizedType, profile, order++));
        }
        return new PagePresentation(page.pageNumber(), BookStore.revisionOrZero(page),
                profile.profileRevision(), profile.policyVersion(), layoutKind(page),
                fallbackMode(page, normalizedTypes), out);
    }

    /**
     * U6：复杂版面保真降级。NONE=可靠重排；REGION_IMAGE=归一化转出的视觉区看原图裁片；
     * PAGE_IMAGE=正文几何缺失、顺序不可验证，整页看原稿。
     */
    static String fallbackMode(Page page, Map<String, String> normalizedTypes) {
        boolean regionVisual = false;
        boolean hasTextual = false;
        boolean textualWithGeometry = false;
        if (page.blocks() != null) {
            for (Block block : page.blocks()) {
                if (block == null) continue;
                String stored = block.type();
                String normalized = normalizedTypes.getOrDefault(block.id(), stored);
                if ("figure".equals(normalized) && !"figure".equals(stored)
                        && !"table".equals(stored) && !"formula".equals(stored)) {
                    regionVisual = true;
                }
                if ("text".equals(stored) || "heading".equals(stored) || "caption".equals(stored)) {
                    hasTextual = true;
                    if (validBbox(block.bbox())) textualWithGeometry = true;
                }
            }
        }
        if (hasTextual && !textualWithGeometry) return "PAGE_IMAGE";
        if (regionVisual) return "REGION_IMAGE";
        return "NONE";
    }

    private static boolean validBbox(double[] bbox) {
        if (bbox == null || bbox.length != 4) return false;
        for (double value : bbox) if (!Double.isFinite(value)) return false;
        return bbox[0] >= 0 && bbox[1] >= 0 && bbox[2] > 0 && bbox[3] > 0
                && bbox[0] + bbox[2] <= 1.000001 && bbox[1] + bbox[3] <= 1.000001;
    }

    private PagePresentation.BlockPresentation projectBlock(Book book, Page page, Block block,
                                                            String normalizedType,
                                                            BookLayoutProfile profile, int readingOrder) {
        String sourceHash = PresentationOverrideService.sourceHash(block);
        String renderAs = "heading".equals(block.type()) ? "heading" : block.type();
        boolean manual = block.reviewed() || "manual".equals(block.source());

        // 1. 人工覆盖最高优先级，不得被自动判断覆盖。
        if (overrides != null) {
            Optional<PresentationOverride> override = overrides.activeFor(book.id(), page, block);
            if (override.isPresent()) {
                String role = PresentationOverrideService.roleForAction(override.get().action());
                boolean include = "INCLUDE_IN_OUTLINE".equals(override.get().action());
                boolean show = !"MARK_RUNNING_HEADER".equals(override.get().action())
                        && !"MARK_RUNNING_FOOTER".equals(override.get().action());
                return presentation(block, sourceHash, role, renderAs, readingOrder, show, include,
                        PagePresentation.EVIDENCE_MANUAL, List.of("MANUAL_OVERRIDE"));
            }
        }

        // 2. 明确的页码类型（归一化视角：页边符号已归位）。
        if ("page-number".equals(normalizedType)) {
            return presentation(block, sourceHash, PagePresentation.ROLE_PAGE_NUMBER, renderAs,
                    readingOrder, !manual, false, PagePresentation.EVIDENCE_SUFFICIENT,
                    List.of("PAGE_NUMBER_TYPE"));
        }

        // 3. 原书目录条目：保留目录页内容，不直接变成正文章节。
        if (isPrintedTocEntry(block)) {
            return presentation(block, sourceHash, PagePresentation.ROLE_PRINTED_TOC_ENTRY, renderAs,
                    readingOrder, true, false, PagePresentation.EVIDENCE_SUFFICIENT,
                    List.of("PRINTED_TOC_PATTERN"));
        }

        // 4. 视觉原子区域：图、表、公式不拆（含归一化识别出的关系图/不可靠矩阵）。
        if ("figure".equals(normalizedType) || "table".equals(normalizedType) || "formula".equals(normalizedType)) {
            return presentation(block, sourceHash, PagePresentation.ROLE_VISUAL, renderAs,
                    readingOrder, true, false, PagePresentation.EVIDENCE_SUFFICIENT,
                    List.of("ATOMIC_VISUAL"));
        }
        if ("advertisement".equals(block.type())) {
            return presentation(block, sourceHash, PagePresentation.ROLE_DECORATION, renderAs,
                    readingOrder, true, false, PagePresentation.EVIDENCE_SUFFICIENT,
                    List.of("ADVERTISEMENT"));
        }
        if ("caption".equals(block.type()) && "caption".equals(normalizedType)) {
            return presentation(block, sourceHash, PagePresentation.ROLE_CAPTION, renderAs,
                    readingOrder, true, false, PagePresentation.EVIDENCE_SUFFICIENT,
                    List.of("CAPTION"));
        }

        if ("heading".equals(block.type())) {
            // 5. 封面/扉页书名：默认排除，不当第一章（只看第一页 + 书名弱提示，不全局黑名单）。
            if (page.pageNumber() == 1 && matchesBookTitle(book, block)) {
                return presentation(block, sourceHash, PagePresentation.ROLE_BOOK_TITLE, renderAs,
                        readingOrder, true, false, PagePresentation.EVIDENCE_SUFFICIENT,
                        List.of("COVER_TITLE"));
            }
            // 6. 重复边缘簇：簇内首次出现暂留为结构候选（内容保留、暂不收录），
            // 后续重复页收起。证据不足时不删字；原稿/校对始终可见，可撤销。
            Optional<BookLayoutProfile.EdgeCluster> cluster = matchingCluster(profile, page, block);
            if (cluster.isPresent() && !manual) {
                List<Integer> members = cluster.get().pages();
                boolean firstOccurrence = !members.isEmpty() && members.get(0) == page.pageNumber();
                String role = "TOP".equals(cluster.get().edgeZone())
                        ? PagePresentation.ROLE_RUNNING_HEADER : PagePresentation.ROLE_RUNNING_FOOTER;
                if (firstOccurrence || hasChapterStartEvidence(page, block)) {
                    return presentation(block, sourceHash, role,
                            renderAs, readingOrder, true, false,
                            PagePresentation.EVIDENCE_INSUFFICIENT,
                            List.of("CLUSTER_FIRST_OCCURRENCE_CANDIDATE"));
                }
                return presentation(block, sourceHash, role, renderAs, readingOrder, false, false,
                        PagePresentation.EVIDENCE_SUFFICIENT, List.of("REPEATED_EDGE_CLUSTER"));
            }
            // 7. 一般正文内 heading：无边缘反证时保留，避免清空目录。
            int level = block.headingLevel() == null ? 2 : Math.max(1, Math.min(block.headingLevel(), 6));
            String role = level <= 1 ? PagePresentation.ROLE_CHAPTER_HEADING
                    : PagePresentation.ROLE_SECTION_HEADING;
            return presentation(block, sourceHash, role, renderAs, readingOrder, true, true,
                    PagePresentation.EVIDENCE_INSUFFICIENT, List.of("NO_COUNTER_EVIDENCE"));
        }

        return presentation(block, sourceHash, PagePresentation.ROLE_BODY, renderAs,
                readingOrder, true, false, PagePresentation.EVIDENCE_SUFFICIENT, List.of("BODY"));
    }

    private PagePresentation.BlockPresentation presentation(Block block, String sourceHash, String role,
                                                            String renderAs, int readingOrder,
                                                            boolean show, boolean include,
                                                            String evidence, List<String> reasons) {
        boolean joinable = "text".equals(block.type());
        return new PagePresentation.BlockPresentation(block.id(), sourceHash, role, renderAs,
                readingOrder, "PROSE", null, joinable,
                show, include, evidence, reasons);
    }

    private static String layoutKind(Page page) {
        String aspect = (page.width() > page.height() * 1.15) ? "SPREAD" : "SINGLE";
        return aspect + "_" + layoutGroup(page);
    }

    private static boolean matchesBookTitle(Book book, Block block) {
        String text = normalizeEdgeText(displayOriginal(block));
        if (text.isEmpty()) return false;
        if (book.title() != null && text.equals(normalizeEdgeText(book.title()))) return true;
        String file = book.filename();
        if (file != null) {
            String base = file.replaceAll("\\.[^.]*$", "");
            if (!base.isEmpty() && text.equals(normalizeEdgeText(base))) return true;
        }
        return false;
    }

    // ---------- 目录/标题统一入口 ----------

    /** 仅发布有章节结构证据、无明确排除理由的标题。 */
    public List<OutlineService.OutlineEntry> outline(String bookId) {
        Book book = store.readBook(bookId);
        BookLayoutProfile profile = buildProfile(bookId);
        List<OutlineService.OutlineEntry> entries = new ArrayList<>();
        for (int n = 1; n <= book.totalPages(); n++) {
            Page page = store.readPage(bookId, n);
            if (page == null || !"READY".equals(page.status())) continue;
            entries.addAll(outlineForPage(book, page, profile));
        }
        return List.copyOf(entries);
    }

    /** 导出冻结画像后，按导出页范围筛选（不因只导出两页使书眉证据失效）。 */
    public List<OutlineService.OutlineEntry> outlineForPages(String bookId, List<Page> pages,
                                                            BookLayoutProfile frozen) {
        Book book = store.readBook(bookId);
        List<OutlineService.OutlineEntry> entries = new ArrayList<>();
        for (Page page : pages) {
            if (page == null || !"READY".equals(page.status())) continue;
            entries.addAll(outlineForPage(book, page, frozen));
        }
        return List.copyOf(entries);
    }

    private List<OutlineService.OutlineEntry> outlineForPage(Book book, Page page, BookLayoutProfile profile) {
        PagePresentation presentation = project(book.id(), page, profile);
        Map<String, PagePresentation.BlockPresentation> byId = presentation.byBlockId();
        List<OutlineService.OutlineEntry> entries = new ArrayList<>();
        if (page.blocks() == null) return entries;
        List<Block> sorted = new ArrayList<>(page.blocks());
        sorted.sort(Comparator.comparingInt(Block::order));
        for (Block block : sorted) {
            if (block == null || !"heading".equals(block.type())) continue;
            PagePresentation.BlockPresentation view = byId.get(block.id());
            if (view == null || !view.includeInOutline()) continue;
            String title = HeadingText.display(block, true).strip();
            if (title.isEmpty()) continue;
            int level = block.headingLevel() == null ? 2 : Math.max(1, Math.min(block.headingLevel(), 6));
            entries.add(new OutlineService.OutlineEntry(page.pageNumber(), block.id(), title, level));
        }
        return entries;
    }

    /** 同一结果选择页面代表标题；无标题回到“第 N 页”，不冒用书眉。 */
    public String pageTitle(String bookId, Page page) {
        PagePresentation presentation = project(bookId, page);
        Map<String, PagePresentation.BlockPresentation> byId = presentation.byBlockId();
        Block best = null;
        if (page.blocks() != null) {
            for (Block block : page.blocks()) {
                if (block == null || !"heading".equals(block.type())) continue;
                PagePresentation.BlockPresentation view = byId.get(block.id());
                if (view != null && view.includeInOutline()) {
                    if (best == null || block.order() < best.order()) best = block;
                }
            }
        }
        if (best != null) {
            String title = HeadingText.display(best, true).strip();
            if (!title.isEmpty()) return title;
        }
        return "第 " + page.pageNumber() + " 页";
    }

    public PageSummary pageSummary(String bookId, Page page) {
        List<Block> reading = page.blocks() == null ? List.of() : page.blocks().stream()
                .filter(b -> b != null && !"advertisement".equals(b.type())).toList();
        int uncertain = (int) reading.stream().filter(Block::uncertain).count();
        return new PageSummary(page.pageNumber(), page.status(), reading.size(), uncertain,
                page.width(), page.height(), pageTitle(bookId, page), page.reviewed());
    }

    // ---------- 原书目录条目（保留内容，不生成章节） ----------

    private static final String PAGE_TOKEN = "(?:[0-9]{1,5}|[〇○零一二三四五六七八九十百千兩两廿卅]{1,8})";

    static boolean isPrintedTocEntry(Block block) {
        if (block == null) return false;
        String id = block.id() == null ? "" : block.id().toLowerCase(Locale.ROOT);
        if (id.startsWith("qwen-toc-")) return true;
        String title = displayOriginal(block);
        if (title.isEmpty()) return false;
        String compact = title.replaceAll("[\\s【】「」『』《》〈〉()（）:：]", "");
        if (compact.matches("(?i)(?:总|總)?目[录錄](?:索引)?|目次|contents|tableofcontents")) return true;
        List<String> lines = title.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.size() >= 3) {
            long directoryLines = lines.stream().filter(BookPresentationService::isDirectoryEntryLine).count();
            if (directoryLines >= 3 && directoryLines * 10 >= lines.size() * 7L) return true;
        }
        // 注意：供应商来源字符串（paddle-span 等）不作为语义结论，只描述提取路径。
        return title.matches("(?s).*?(?:\\.{2,}|…+|·{2,}|-{3,})\\s*" + PAGE_TOKEN + "\\s*$");
    }

    private static boolean isDirectoryEntryLine(String line) {
        return line.matches("(?s).+?(?:\\s|\\.{2,}|…+|·{2,}|-{3,})" + PAGE_TOKEN + "\\s*$");
    }
}
