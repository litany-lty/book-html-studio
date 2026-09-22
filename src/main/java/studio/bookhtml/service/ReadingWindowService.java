package studio.bookhtml.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.*;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.BookLayoutProfile;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.ProcessingSnapshot;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One process-local reading window. Nothing is restored after restart. */
@Service
public class ReadingWindowService {
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final int TOMBSTONE_LIMIT = 256;
    private final BookStore store;
    private final JobService jobs;
    private final SettingsService settings;
    private BookPresentationService presentation;
    private final Clock clock;
    private final Duration settle;
    private final ScheduledExecutorService dispatcher;
    private final LinkedHashMap<UUID, Tombstone> tombstones = new LinkedHashMap<>();
    private Session current;

    @Autowired public ReadingWindowService(BookStore store, JobService jobs, SettingsService settings) {
        // U2：停留阈值统一为 1000ms（见 9.7）；前端只读缓存窗口（前后 5）与云派发窗口
        // （前 3/后 5）命名区分，不互相冒充。构造注入的 settle seam 保留供测试。
        this(store, jobs, settings, Clock.systemUTC(), Duration.ofMillis(1000));
    }

    // Package-local clock/settle seam keeps race and expiry tests deterministic.
    ReadingWindowService(BookStore store, JobService jobs, SettingsService settings,
                         Clock clock, Duration settle) {
        this.store = store;
        this.jobs = jobs;
        this.settings = settings;
        this.clock = clock;
        this.settle = settle;
        this.dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "book-reading-window");
            thread.setDaemon(true);
            return thread;
        });
        dispatcher.scheduleWithFixedDelay(this::safeTick, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** U3：统一投影注入后，随读增量目录按已发布 profileRevision 取投影，不再孤立推断。 */
    @Autowired(required = false)
    public void setPresentation(BookPresentationService presentation) {
        this.presentation = presentation;
    }

    private ProcessingProgressService progress;

    /** U4：阶段事件聚合（测试可注入；缺省关闭，正式保存不受影响）。 */
    @Autowired(required = false)
    public void setProgress(ProcessingProgressService progress) {
        this.progress = progress;
    }

    public synchronized ReadingWindowResponse update(String bookId, ReadingWindowRequest request) {
        validate(request);
        Book book = store.readBook(bookId);
        if (request.currentPage() > book.totalPages()) bad("页码超出书籍范围");
        Tombstone ended = tombstones.get(request.sessionId());
        if (ended != null) {
            if (!ended.bookId.equals(bookId)) throw new ApiException(HttpStatus.CONFLICT, "会话编号已用于另一书籍");
            return ended.response;
        }
        Instant now = clock.instant();
        // A process restart loses the in-memory reservation. An old tab's heartbeat must not recreate it.
        if ((current == null || !current.sessionId.equals(request.sessionId()))
                && !Boolean.TRUE.equals(request.start()))
            return new ReadingWindowResponse(request.sessionId(), request.sequence(), false, "EXPIRED",
                    request.currentPage(), null, null, null, List.of(), List.of(),
                    "阅读会话已失效，请明确重新开启随读处理");
        if (current != null && !current.enabled && current.processingPages.isEmpty()) finish(current);
        if (current == null) {
            SettingsService.Lease lease = settings.beginWork();
            UUID reservation = UUID.randomUUID();
            try {
                requireConfigured(request.provider());
                jobs.reserveReading(reservation, bookId);
                current = new Session(bookId, request, reservation, lease, now);
                replaceWindow(current, book.totalPages(), now);
                return snapshot(current);
            } catch (RuntimeException error) {
                jobs.releaseReading(reservation);
                lease.close();
                throw error;
            }
        }
        Session session = current;
        if (!session.bookId.equals(bookId) || !session.sessionId.equals(request.sessionId()))
            throw new ApiException(HttpStatus.CONFLICT, "已有其他书籍或标签页的阅读窗口，请先停止原窗口");
        if (session.enabled && !now.isBefore(session.deadline)) {
            disable(session, "EXPIRED", "阅读窗口已过期，请重新开启");
            if (session.processingPages.isEmpty()) finish(session);
            return current == session ? snapshot(session) : tombstones.get(request.sessionId()).response;
        }
        if (!session.enabled) return snapshot(session);
        if (!sameOptions(session, request)) throw new ApiException(HttpStatus.CONFLICT, "阅读窗口参数已固定，请停止后新建会话");
        if (request.sequence() < session.sequence) return snapshot(session);
        if (request.sequence().equals(session.sequence)) {
            if (request.currentPage() != session.centerPage)
                throw new ApiException(HttpStatus.CONFLICT, "相同序号的阅读窗口请求内容不同");
            session.deadline = now.plus(LEASE);
            session.autoProcessAll = Boolean.TRUE.equals(request.autoProcessAll());
            if (Boolean.TRUE.equals(request.retryCurrentPage())) {
                retryPage(session, session.centerPage, now);
            }
            return snapshot(session);
        }
        session.sequence = request.sequence();
        session.centerPage = request.currentPage();
        session.deadline = now.plus(LEASE);
        session.autoProcessAll = Boolean.TRUE.equals(request.autoProcessAll());
        if (Boolean.TRUE.equals(request.retryCurrentPage())) {
            retryPage(session, session.centerPage, now);
        } else {
            replaceWindow(session, book.totalPages(), now);
        }
        return snapshot(session);
    }

    private void retryPage(Session session, int pageNumber, Instant now) {
        Page page = store.readPage(session.bookId, pageNumber);
        if (page == null) return;
        String operationId = "window:" + session.sessionId + ":" + session.sequence + ":" + pageNumber;
        // Freeze the original revision for retransmission of the same accepted operation.
        if (!operationId.equals(session.retryOperationId)) {
            session.retryOperationId = operationId;
            session.retryRequest = new PageReprocessRequest(BookStore.revisionOrZero(page), operationId,
                    false, session.provider, session.assist);
        }
        try {
            jobs.requestReprocess(session.reservation, session.bookId, pageNumber, session.retryRequest,
                    session.provider, session.layout, session.splitSpreads, session.assist);
            session.retryPages.remove(pageNumber);
            session.queued.remove(Integer.valueOf(pageNumber));
            session.dispatched.add(pageNumber);
            if (jobs.readingJobActive(session.reservation, pageNumber)) {
                session.processingPages.add(pageNumber);
                session.processingChannels.put(pageNumber, session.provider);
            }
        } catch (ApiException rejected) {
            session.message = rejected.getMessage();
            // A rejected manual-overwrite/revision check must NEVER become a force=true retry.
            return;
        }
        session.notBefore = now;
        tick();
    }

    public synchronized ReadingWindowResponse get(String bookId, UUID sessionId) {
        store.readBook(bookId);
        if (sessionId == null) bad("缺少 sessionId");
        if (current != null && current.bookId.equals(bookId) && current.sessionId.equals(sessionId))
            return snapshot(current);
        Tombstone ended = tombstones.get(sessionId);
        if (ended != null && ended.bookId.equals(bookId)) return ended.response;
        return new ReadingWindowResponse(sessionId, 0, false, "IDLE", null, null, null,
                null, List.of(), List.of(), "阅读窗口未启动");
    }

    public synchronized ReadingWindowResponse stop(String bookId, ReadingWindowCommand command) {
        store.readBook(bookId);
        if (command == null || command.sessionId() == null || command.sequence() == null || command.sequence() <= 0)
            bad("停止请求缺少有效会话或序号");
        Tombstone ended = tombstones.get(command.sessionId());
        if (ended != null) {
            if (!ended.bookId.equals(bookId)) throw new ApiException(HttpStatus.CONFLICT, "会话编号已用于另一书籍");
            if (command.sequence() > ended.response.sequence()) {
                ReadingWindowResponse r = ended.response;
                ended = new Tombstone(bookId, new ReadingWindowResponse(r.sessionId(), command.sequence(), false,
                        r.status(), r.centerPage(), r.fromPage(), r.toPage(), null, List.of(), r.pages(), r.message()));
                remember(command.sessionId(), ended);
            }
            return ended.response;
        }
        if (current == null || !current.sessionId.equals(command.sessionId()) || !current.bookId.equals(bookId)) {
            ReadingWindowResponse stopped = new ReadingWindowResponse(command.sessionId(), command.sequence(), false,
                    "STOPPING", null, null, null, null, List.of(), List.of(), "阅读窗口已停止");
            remember(command.sessionId(), new Tombstone(bookId, stopped));
            return stopped;
        }
        Session session = current;
        if (command.sequence() < session.sequence) return snapshot(session);
        session.sequence = command.sequence();
        disable(session, "STOPPING", "阅读窗口已停止；已开始的页面将自然完成");
        if (session.processingPages.isEmpty()) finish(session);
        return current == session ? snapshot(session) : tombstones.get(command.sessionId()).response;
    }

    private void safeTick() {
        try { tick(); }
        catch (RuntimeException error) {
            synchronized (this) {
                if (current != null) {
                    Session s = current;
                    disable(s, "BLOCKED", "随读处理暂停：" + safeMessage(error));
                    if (s.processingPages.isEmpty() || !jobs.readingJobActive(s.reservation)) finish(s);
                }
            }
        }
    }

    public static final int CHANNEL_CONCURRENCY = 3;

    private boolean isConfigured(String provider, SettingsService.State state) {
        if ("paddle-aistudio".equals(provider)) {
            return state.paddleAccessToken() != null && !state.paddleAccessToken().isBlank();
        }
        if ("ppocr".equals(provider)) {
            return state.ppocrApiKey() != null && !state.ppocrApiKey().isBlank()
                    && state.ppocrSecretKey() != null && !state.ppocrSecretKey().isBlank();
        }
        return false;
    }

    List<String> getEnabledChannels(Session s) {
        // U2：按本次任务授权派发。默认 allowedProviders=[primary]，仅主通道；
        // “已配置”不等于“本次允许并行外发”。回退/并行/全书范围分别控制（见 U4/U5）。
        SettingsService.State state = settings.state();
        List<String> allowed = s.allowedProviders == null || s.allowedProviders.isEmpty()
                ? List.of(s.provider) : s.allowedProviders;
        List<String> channels = new ArrayList<>();
        for (String provider : allowed) {
            if (isConfigured(provider, state) && !channels.contains(provider)) channels.add(provider);
        }
        if (channels.isEmpty()) {
            channels.add(s.provider);
        }
        return channels;
    }

    private long channelCount(Session s, String channel) {
        return s.processingChannels.values().stream().filter(channel::equals).count();
    }

    synchronized void tick() {
        Session s = current;
        if (s == null) return;
        Instant now = clock.instant();
        if (s.enabled && !now.isBefore(s.deadline)) disable(s, "EXPIRED", "阅读窗口已过期，请重新开启");
        s.processingPages.removeIf(p -> {
            boolean active = jobs.readingJobActive(s.reservation, p);
            if (!active) {
                s.processingChannels.remove(p);
            }
            return !active;
        });
        if (!s.enabled) {
            if (s.processingPages.isEmpty()) finish(s);
            return;
        }
        if (now.isBefore(s.notBefore)) {
            s.status = s.processingPages.isEmpty() ? "SETTLING" : "PROCESSING";
            return;
        }
        List<String> channels = getEnabledChannels(s);
        int totalCapacity = channels.size() * CHANNEL_CONCURRENCY;

        int center = s.centerPage;
        boolean centerEligible = eligible(store.readPage(s.bookId, center));

        // Explicit retries are admitted only by requestReprocess, never by an unchecked force queue.

        // 1. Guarantee centerPage is Priority #1.
        // U2：容量满时等待自然收尾，不强杀最远页的已发出云请求。当前页保持队首，
        // 有空闲槽即优先派发；已发出的请求允许完成并缓存到对应书页（DRAIN 语义）。
        if (centerEligible && !s.processingPages.contains(center)) {
            if (s.processingPages.size() >= totalCapacity) {
                s.queued.remove(Integer.valueOf(center));
                s.queued.addFirst(center);
                s.status = "PROCESSING";
                return;
            }
            String centerChannel = null;
            for (String ch : channels) {
                if (channelCount(s, ch) < CHANNEL_CONCURRENCY) {
                    centerChannel = ch;
                    break;
                }
            }
            if (centerChannel != null) {
                s.queued.remove(Integer.valueOf(center));
                if (s.dispatched.add(center)) {
                    try {
                        jobs.submitReserved(s.reservation, s.bookId, new JobRequest(String.valueOf(center), centerChannel,
                                s.layout, s.splitSpreads, false, s.assist));
                        s.processingPages.add(center);
                        s.processingChannels.put(center, centerChannel);
                    } catch (RuntimeException error) {
                        s.dispatched.remove(center);
                        s.processingChannels.remove(center);
                        disable(s, "BLOCKED", "随读处理无法提交单页任务：" + safeMessage(error));
                        finish(s);
                        return;
                    }
                }
            }
        }

        // 2. If centerPage is still not ready (either running or waiting), do not start background prefetch
        if (centerEligible || s.processingPages.contains(center)) {
            if (!s.processingPages.isEmpty()) s.status = "PROCESSING";
            return;
        }

        // 3. Center page is ready: check if any subsequent (next 5) pages are still pending dispatch
        boolean hasSubsequentPending = false;
        for (int p = center + 1; p <= s.toPage; p++) {
            if (eligible(store.readPage(s.bookId, p)) && !s.processingPages.contains(p) && !s.dispatched.contains(p)) {
                hasSubsequentPending = true;
                break;
            }
        }

        // 4. Fill available concurrency slots across enabled channels (up to 3 per channel)
        fillAvailableSlots(s, channels, center, hasSubsequentPending);

        // 5. If immediate window queue is exhausted and autoProcessAll is enabled, queue remaining book pages
        if (s.queued.isEmpty() && s.autoProcessAll) {
            Book book = store.readBook(s.bookId);
            int total = book == null ? 0 : book.totalPages();
            for (int p = s.toPage + 1; p <= total; p++) {
                if (eligible(store.readPage(s.bookId, p)) && !s.processingPages.contains(p)
                        && !s.dispatched.contains(p) && !s.queued.contains(p)) {
                    s.queued.addLast(p);
                }
            }
            for (int p = s.fromPage - 1; p >= 1; p--) {
                if (eligible(store.readPage(s.bookId, p)) && !s.processingPages.contains(p)
                        && !s.dispatched.contains(p) && !s.queued.contains(p)) {
                    s.queued.addLast(p);
                }
            }
            fillAvailableSlots(s, channels, center, false);
        }

        if (!s.processingPages.isEmpty()) {
            s.status = "PROCESSING";
        } else if (s.queued.isEmpty()) {
            s.status = "READY";
        }
    }

    private void fillAvailableSlots(Session s, List<String> channels, int center, boolean hasSubsequentPending) {
        while (!s.queued.isEmpty()) {
            int peek = s.queued.peekFirst();
            if (peek < center && hasSubsequentPending) {
                break;
            }

            String targetChannel = channels.stream()
                    .filter(ch -> channelCount(s, ch) < CHANNEL_CONCURRENCY)
                    .min(Comparator.comparingLong((String ch) -> channelCount(s, ch)).thenComparingInt(channels::indexOf))
                    .orElse(null);
            if (targetChannel == null) {
                break;
            }

            int pageNumber = s.queued.removeFirst();
            if (s.processingPages.contains(pageNumber)) continue;
            Page page = store.readPage(s.bookId, pageNumber);
            if (!eligible(page) || !s.dispatched.add(pageNumber)) continue;
            try {
                jobs.submitReserved(s.reservation, s.bookId, new JobRequest(String.valueOf(pageNumber), targetChannel,
                        s.layout, s.splitSpreads, false, s.assist));
                s.processingPages.add(pageNumber);
                s.processingChannels.put(pageNumber, targetChannel);
            } catch (RuntimeException error) {
                s.dispatched.remove(pageNumber);
                s.processingChannels.remove(pageNumber);
                disable(s, "BLOCKED", "随读处理无法提交单页任务：" + safeMessage(error));
                finish(s);
                return;
            }
        }
    }

    private static String safeMessage(RuntimeException error) {
        return error instanceof ApiException && error.getMessage() != null ? error.getMessage() : "请检查任务状态后重试";
    }

    private void replaceWindow(Session s, int total, Instant now) {
        s.fromPage = Math.max(1, s.centerPage - 3);
        s.toPage = Math.min(total, s.centerPage + 5);
        s.queued.clear();
        int center = s.centerPage;
        if (center <= total && eligible(store.readPage(s.bookId, center))
                && !s.processingPages.contains(center) && !s.dispatched.contains(center)) {
            s.queued.addLast(center);
        }
        for (int i = 1; i <= 5; i++) {
            int plus = center + i;
            if (plus <= s.toPage && eligible(store.readPage(s.bookId, plus))
                    && !s.processingPages.contains(plus) && !s.dispatched.contains(plus)) {
                s.queued.addLast(plus);
            }
        }
        for (int j = 1; j <= 3; j++) {
            int minus = center - j;
            if (minus >= s.fromPage && eligible(store.readPage(s.bookId, minus))
                    && !s.processingPages.contains(minus) && !s.dispatched.contains(minus)) {
                s.queued.addLast(minus);
            }
        }
        s.notBefore = now.plus(settle);
        s.status = "SETTLING";
    }

    private static boolean eligible(Page page) {
        return page != null && "PENDING".equals(page.status()) && !page.reviewed()
                && !"manual".equals(page.provider())
                && (page.blocks() == null || page.blocks().isEmpty())
                && (page.sourceRecords() == null || page.sourceRecords().isEmpty());
    }

    private void requireConfigured(String provider) {
        SettingsService.State state = settings.state();
        if ("paddle-aistudio".equals(provider) && state.paddleAccessToken().isBlank())
            throw new ApiException(HttpStatus.CONFLICT, "AI Studio 尚未配置 Access Token");
        if ("ppocr".equals(provider) && (state.ppocrApiKey().isBlank() || state.ppocrSecretKey().isBlank()))
            throw new ApiException(HttpStatus.CONFLICT, "PP-OCR 尚未配置 API Key 或 Secret Key");
    }

    private ReadingWindowResponse snapshot(Session s) {
        List<ReadingWindowResponse.PageState> pages = new ArrayList<>();
        // U3：一次快照共用同一画像构建，避免每页重复扫描；画像更新影响前页时由
        // profileRevision 触发目录更新，旧单页逻辑不反灌（见 app.js）。
        BookLayoutProfile profile = presentation == null ? null : presentation.buildProfile(s.bookId);
        for (int n = s.fromPage; n <= s.toPage; n++) {
            Page page = store.readPage(s.bookId, n);
            // U4：当前页处理快照来自真实阶段事件；无事件时为 null，前端不伪造进度。
            ProcessingSnapshot processing =
                    progress == null ? null : progress.latest(s.bookId, n);
            pages.add(new ReadingWindowResponse.PageState(n, page == null ? "MISSING" : page.status(),
                    page == null ? null : page.revision(), page == null ? "页面数据缺失" : page.error(),
                    page == null ? null : BookService.summary(page),
                    page == null ? List.of() : snapshotOutline(s.bookId, page, profile),
                    profile == null ? 0 : profile.profileRevision(), processing));
        }
        List<Integer> procList = new ArrayList<>(s.processingPages);
        procList.sort((a, b) -> {
            if (a.equals(s.centerPage)) return -1;
            if (b.equals(s.centerPage)) return 1;
            return Integer.compare(a, b);
        });
        Integer primaryProcessing = procList.isEmpty() ? null : procList.get(0);
        return new ReadingWindowResponse(s.sessionId, s.sequence, s.enabled, s.status, s.centerPage,
                s.fromPage, s.toPage, primaryProcessing, List.copyOf(s.queued), List.copyOf(pages),
                s.message, List.copyOf(procList));
    }

    /** U3：随读增量目录使用统一投影；未注入投影走旧适配器（保守兼容）。 */
    private List<OutlineService.OutlineEntry> snapshotOutline(String bookId, Page page,
                                                             BookLayoutProfile profile) {
        if (presentation == null || profile == null) {
            return OutlineService.fromPages(List.of(page));
        }
        return presentation.outlineForPages(bookId, List.of(page), profile);
    }

    private void disable(Session s, String status, String message) {
        s.enabled = false;
        s.status = status;
        s.message = message;
        s.queued.clear();
    }

    private void finish(Session s) {
        if (current != s) return;
        s.processingPages.clear();
        s.processingChannels.clear();
        try {
            ReadingWindowResponse last;
            try { last = snapshot(s); }
            catch (RuntimeException error) {
                last = new ReadingWindowResponse(s.sessionId, s.sequence, false, "BLOCKED", s.centerPage,
                        s.fromPage, s.toPage, null, List.of(), List.of(), "读取窗口页状态失败，请刷新书籍数据", List.of());
            }
            remember(s.sessionId, new Tombstone(s.bookId, last));
        } finally {
            current = null;
            try { jobs.releaseReading(s.reservation); }
            finally { s.lease.close(); }
        }
    }

    private void remember(UUID id, Tombstone tombstone) {
        tombstones.put(id, tombstone);
        while (tombstones.size() > TOMBSTONE_LIMIT) tombstones.remove(tombstones.keySet().iterator().next());
    }

    private static boolean sameOptions(Session s, ReadingWindowRequest r) {
        return s.provider.equals(r.provider()) && s.layout.equals(r.layout())
                && s.splitSpreads == r.splitSpreads() && s.assist == Boolean.TRUE.equals(r.assist());
    }

    private static void validate(ReadingWindowRequest r) {
        if (r == null || r.sessionId() == null || r.sequence() == null || r.sequence() <= 0
                || r.currentPage() == null || r.currentPage() <= 0) bad("阅读窗口缺少有效会话、序号或页码");
        if (r.provider() == null || r.layout() == null
                || !Set.of("paddle-aistudio", "ppocr").contains(r.provider())
                || !Set.of("auto", "vertical", "horizontal").contains(r.layout())
                || r.splitSpreads() == null || !Boolean.TRUE.equals(r.allowCloud()))
            bad("阅读窗口参数无效或未允许云端识别");
    }

    private static void bad(String message) { throw new ApiException(HttpStatus.BAD_REQUEST, message); }

    @PreDestroy public synchronized void close() {
        dispatcher.shutdownNow();
        if (current != null) {
            disable(current, "STOPPING", "应用正在关闭");
            jobs.releaseReading(current.reservation);
            current.lease.close();
            current = null;
        }
    }

    private record Tombstone(String bookId, ReadingWindowResponse response) {}
    private static final class Session {
        final String bookId;
        final UUID sessionId, reservation;
        final String provider, layout;
        final boolean splitSpreads, assist;
        // U2：本次任务授权快照。默认仅主通道；失败回退、并行分发、全书范围默认关闭，
        // 配置了密钥不代表已获授权（见 9.6）。新任务读取新快照，在途任务不受配置修改影响。
        final List<String> allowedProviders;
        final boolean fallbackAllowed;
        final boolean parallelProvidersAllowed;
        final SettingsService.Lease lease;
        final ArrayDeque<Integer> queued = new ArrayDeque<>();
        final Set<Integer> dispatched = new HashSet<>();
        // U2：用户显式重试页。绕过 PENDING 资格门（force=true），仍受容量/授权约束。
        final Set<Integer> retryPages = ConcurrentHashMap.newKeySet();
        final Set<Integer> processingPages = ConcurrentHashMap.newKeySet();
        final Map<Integer, String> processingChannels = new ConcurrentHashMap<>();
        String retryOperationId;
        PageReprocessRequest retryRequest;
        long sequence;
        int centerPage, fromPage, toPage;
        Instant deadline, notBefore;
        boolean enabled = true;
        boolean autoProcessAll;
        String status = "SETTLING", message = "";

        Session(String bookId, ReadingWindowRequest request, UUID reservation,
                SettingsService.Lease lease, Instant now) {
            this.bookId = bookId;
            this.sessionId = request.sessionId();
            this.reservation = reservation;
            this.provider = request.provider();
            this.layout = request.layout();
            this.splitSpreads = request.splitSpreads();
            this.assist = Boolean.TRUE.equals(request.assist());
            this.allowedProviders = List.of(request.provider());
            this.fallbackAllowed = false;
            this.parallelProvidersAllowed = false;
            this.autoProcessAll = Boolean.TRUE.equals(request.autoProcessAll());
            this.lease = lease;
            this.sequence = request.sequence();
            this.centerPage = request.currentPage();
            this.deadline = now.plus(LEASE);
        }
    }
}
