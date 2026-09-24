// The reading window is deliberately ephemeral: no consent or session is persisted.
// U2：只读缓存窗口（前后各 5 页）与云派发窗口（服务端前 3/后 5）是两个不同的窗口，
// 不互相冒充。缓存窗口只做只读 GET 预取，从不触发付费 POST。
export function nearbyPages(center, total) {
  const pages = [];
  // cacheWindow：本地只读缓存范围；processingWindow（云派发）由服务端快照决定。
  for (let offset = 1; offset <= 5; offset++) {
    if (center - offset >= 1) pages.push(center - offset);
    if (center + offset <= total) pages.push(center + offset);
  }
  return pages;
}

function generateUuid() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function createReadingWindow({ api, state, onStatus, onPageReady, onError }) {
  let session = null;
  let sequence = 0;
  let fixedOptions = null;
  let pollTimer = null;
  let heartbeatTimer = null;
  let prefetchTimer = null;
  let epoch = 0;
  let prefetchGeneration = 0;
  let prefetchControllers = [];
  let readyControllers = [];
  let readyQueue = [];
  const seenReady = new Map();
  // U2：在途集合与成功标记分离。seenReady 只在 GET 成功且版本/会话有效、
  // 成功进入缓存或待展示队列后写入；失败/取消清理在途标记，同 revision 可重 GET。
  // 重试的是只读 GET，从不因此重新提交付费 POST。
  const readyInFlight = new Map();

  const active = () => Boolean(session && state.book?.id === session.bookId);
  const clearTimers = () => {
    clearTimeout(pollTimer); clearTimeout(heartbeatTimer); clearTimeout(prefetchTimer);
    pollTimer = heartbeatTimer = prefetchTimer = null;
  };
  const abortPrefetch = () => {
    ++prefetchGeneration;
    clearTimeout(prefetchTimer);
    prefetchControllers.forEach(controller => controller.abort());
    prefetchControllers = [];
  };
  const cancelReads = () => {
    ++epoch;
    abortPrefetch();
    readyControllers.forEach(controller => controller.abort());
    readyControllers = [];
    readyQueue = [];
    readyInFlight.clear();
  };
  const valid = (bookId, sequenceAtStart, epochAtStart) =>
    active() && session.bookId === bookId && sequence === sequenceAtStart && epoch === epochAtStart;
  const cacheIsNewer = (oldPage, newPage) => oldPage?.revision != null && newPage?.revision != null &&
    Number(oldPage.revision) > Number(newPage.revision);

  function schedulePrefetch(reset = true) {
    if (reset) cancelReads();
    else abortPrefetch();
    const bookId = state.book?.id;
    const center = state.currentPage;
    const total = state.book?.totalPages || 0;
    const currentEpoch = epoch;
    const generation = prefetchGeneration;
    prefetchTimer = setTimeout(async () => {
      if (state.book?.id !== bookId || state.currentPage !== center || epoch !== currentEpoch || generation !== prefetchGeneration || readyControllers.length || readyQueue.length) return;
      const queue = nearbyPages(center, total).sort((a, b) => (a > center ? a - center : 10 + center - a) - (b > center ? b - center : 10 + center - b)).filter(page => {
        const cached = state.pageCache.get(page);
        return !cached || cached.status !== 'READY';
      });
      async function worker() {
        while (queue.length && state.book?.id === bookId && state.currentPage === center && epoch === currentEpoch && generation === prefetchGeneration && !readyQueue.length) {
          const pageNumber = queue.shift();
          const controller = new AbortController();
          prefetchControllers.push(controller);
          try {
            const page = await api.page(bookId, pageNumber, controller.signal);
            if (!controller.signal.aborted && state.book?.id === bookId && state.currentPage === center && epoch === currentEpoch && generation === prefetchGeneration &&
                !cacheIsNewer(state.pageCache.get(pageNumber), page))
              state.pageCache.set(pageNumber, page);
          } catch (_) { /* Prefetch is optional; current page remains usable. */ }
          finally { prefetchControllers = prefetchControllers.filter(item => item !== controller); }
        }
      }
      await Promise.all([worker(), worker(), worker()]);
    }, 1000);
  }

  function runReadyWorkers() {
    if (readyQueue.length) abortPrefetch();
    while (readyControllers.length < 3 && readyQueue.length) {
      const item = readyQueue.shift();
      const controller = new AbortController();
      readyControllers.push(controller);
      api.page(item.bookId, item.pageNumber, controller.signal).then(page => {
        if (!valid(item.bookId, item.sequence, item.epoch) || cacheIsNewer(state.pageCache.get(item.pageNumber), page)) {
          if (readyInFlight.get(item.pageNumber) === item) readyInFlight.delete(item.pageNumber);
          return;
        }
        // U2：GET 成功且会话有效才记成功标记；缓存与展示仍由 onPageReady 按既有保护处理。
        seenReady.set(item.pageNumber, `${item.pageNumber}:${page.revision}`);
        if (readyInFlight.get(item.pageNumber) === item) readyInFlight.delete(item.pageNumber);
        onPageReady(item.pageNumber, page);
      }).catch(error => {
        // U2：失败/取消清理在途标记，同 revision 仍可重 GET；不重发付费 POST。
        if (readyInFlight.get(item.pageNumber) === item) readyInFlight.delete(item.pageNumber);
        if (valid(item.bookId, item.sequence, item.epoch) && error?.name !== 'StaleRequest') {
          onError(error);
        }
      }).finally(() => {
        readyControllers = readyControllers.filter(current => current !== controller);
        runReadyWorkers();
        if (!readyQueue.length && !readyControllers.length && valid(item.bookId, item.sequence, item.epoch))
          schedulePrefetch(false);
      });
    }
  }

  function inspectReady(snapshot, sequenceAtStart, epochAtStart) {
    const center = state.currentPage;
    function pagePriority(p) {
      const pageNum = Number(p.pageNumber);
      if (pageNum === center) return 0;
      if (pageNum > center && pageNum <= center + 5) return pageNum - center;
      if (pageNum < center && pageNum >= center - 5) return 10 + (center - pageNum);
      return 100 + Math.abs(pageNum - center);
    }
    const sorted = [...(snapshot.pages || [])].sort((left, right) => pagePriority(left) - pagePriority(right));
    for (const info of sorted) {
      if (info.status !== 'READY' || info.revision == null) continue;
      const pageNumber = Number(info.pageNumber);
      const key = `${pageNumber}:${info.revision}`;
      if (seenReady.get(pageNumber) === key) continue;
      // U2：在途页不重复入队；成功标记只在 GET 成功后写，失败清理在途后可重 GET。
      if (readyInFlight.get(pageNumber)?.key === key) continue;
      const cached = state.pageCache.get(pageNumber);
      if (cached?.revision === info.revision && cached?.status === 'READY') { seenReady.set(pageNumber, key); continue; }
      const item = { bookId: session.bookId, sequence: sequenceAtStart, epoch: epochAtStart, pageNumber, key };
      readyInFlight.set(pageNumber, item);
      readyQueue.push(item);
    }
    runReadyWorkers();
  }

  function accept(snapshot, expectedSequence, expectedEpoch) {
    if (!session || snapshot?.sessionId !== session.id || snapshot.sequence !== expectedSequence ||
        sequence !== expectedSequence || epoch !== expectedEpoch || state.book?.id !== session.bookId) return;
    if (snapshot.enabled === false || snapshot.status === 'BLOCKED' || snapshot.status === 'EXPIRED' || snapshot.status === 'STOPPING') {
      session = null;
      clearTimers(); cancelReads();
      onStatus(snapshot);
      return;
    }
    if (snapshot.enabled === true) session.confirmed = true;
    onStatus(snapshot);
    inspectReady(snapshot, expectedSequence, expectedEpoch);
    schedulePoll(snapshot);
  }

  function schedulePoll(snapshot) {
    clearTimeout(pollTimer);
    if (!active()) return;
    const centerNum = Number(snapshot?.centerPage ?? state.currentPage);
    const centerPage = snapshot?.pages?.find(p => Number(p.pageNumber) === centerNum);
    const centerPending = centerPage && centerPage.status !== 'READY';
    const isBusy = Boolean(snapshot?.processingPages?.length) || Boolean(snapshot?.processingPage)
      || snapshot?.status === 'SETTLING' || snapshot?.status === 'PROCESSING' || centerPending;
    const delay = isBusy ? 400 : 1500;
    pollTimer = setTimeout(async () => {
      if (!active()) return;
      const bookId = session.bookId, id = session.id, seq = sequence, requestEpoch = epoch;
      try { accept(await api.readingWindowStatus(bookId, id), seq, requestEpoch); }
      catch (error) {
        if (valid(bookId, seq, requestEpoch)) {
          onError(error);
          // A failed status read is not authority to repeat a cloud-processing POST.
          clearTimeout(pollTimer);
        }
      }
    }, delay);
  }

  function scheduleHeartbeat() {
    clearTimeout(heartbeatTimer);
    if (!active()) return;
    heartbeatTimer = setTimeout(async () => {
      if (!active()) return;
      const bookId = session.bookId, seq = sequence, requestEpoch = epoch;
      try { accept(await api.readingWindow(bookId, payload(seq)), seq, requestEpoch); }
      catch (error) { if (valid(bookId, seq, requestEpoch)) onError(error); }
      if (valid(bookId, seq, requestEpoch)) scheduleHeartbeat();
    }, 20000);
  }

  const payload = (seq, start = false) => ({ sessionId: session.id, sequence: seq, currentPage: state.currentPage,
    ...fixedOptions, allowCloud: true, start });

  async function postNavigation() {
    if (!active()) return;
    const bookId = session.bookId, seq = ++sequence, requestEpoch = epoch;
    onStatus({ sessionId: session.id, sequence: seq, enabled: true, status: 'SETTLING', centerPage: state.currentPage,
      fromPage: Math.max(1, state.currentPage - 3), toPage: Math.min(state.book.totalPages, state.currentPage + 5), pages: [] });
    try { accept(await api.readingWindow(bookId, payload(seq, !session.confirmed)), seq, requestEpoch); }
    catch (error) {
      if (!valid(bookId, seq, requestEpoch)) return;
      if (error?.status === 409) {
        clearTimers();
        session = null;
        onStatus({ enabled: false, status: 'BLOCKED', message: error.message || '另一处理任务正在占用本书。' });
      }
      onError(error);
    }
  }

  function navigated() {
    seenReady.clear();
    readyInFlight.clear();
    schedulePrefetch();
    if (active()) void postNavigation();
  }

  async function refreshStatus() {
    if (!active()) return;
    // Explicit user action may retry a failed local JSON refresh; polling never loops it.
    seenReady.clear();
    const bookId = session.bookId, id = session.id, seq = sequence, requestEpoch = epoch;
    try { accept(await api.readingWindowStatus(bookId, id), seq, requestEpoch); }
    catch (error) { if (valid(bookId, seq, requestEpoch)) onError(error); }
  }

  async function enable(options) {
    if (!state.book || active()) return;
    session = { id: generateUuid(), bookId: state.book.id, confirmed: false };
    sequence = 0;
    fixedOptions = { ...options };
    seenReady.clear();
    readyInFlight.clear();
    schedulePrefetch();
    await postNavigation();
    if (active()) scheduleHeartbeat();
  }

  async function stop({ beacon = false, silent = false } = {}) {
    if (!session) { cancelReads(); clearTimers(); onStatus(null); return; }
    const previous = session;
    const stopSequence = ++sequence;
    session = null;
    fixedOptions = null;
    seenReady.clear();
    readyInFlight.clear();
    cancelReads(); clearTimers();
    const stopEpoch = epoch;
    const currentStop = () => !silent && !session && epoch === stopEpoch && state.book?.id === previous.bookId;
    if (!silent) onStatus({ enabled: false, status: 'STOPPING', message: '正在请求停止；已发出的当前页可能仍会完成。', pages: [] });
    const body = { sessionId: previous.id, sequence: stopSequence };
    if (beacon && typeof navigator !== 'undefined' && navigator.sendBeacon) {
      const url = `/api/books/${encodeURIComponent(previous.bookId)}/reading-window/stop`;
      navigator.sendBeacon(url, new Blob([JSON.stringify(body)], { type: 'application/json' }));
      if (currentStop()) onStatus({ enabled: false, status: 'STOP_REQUESTED', message: '已发停止请求，送达尚未确认；若未送达，后台会在租期结束后停止。', pages: [] });
      return;
    }
    try {
      await api.stopReadingWindow(previous.bookId, body);
      if (currentStop()) onStatus({ enabled: false, status: 'STOPPED', message: '已停止后续排队；已发出的当前页仍可能完成。', pages: [] });
    } catch (error) {
      if (currentStop()) {
        onStatus({ enabled: false, status: 'STOP_UNKNOWN', message: '停止尚未确认；最后一次心跳后 60 秒租期到期会停止新派发。请勿重复开启，已发出请求仍可能完成。', pages: [] });
        onError(error);
      }
    }
  }

  async function retryCurrentPage() {
    if (!active()) return;
    seenReady.delete(state.currentPage);
    readyInFlight.delete(state.currentPage);
    const bookId = session.bookId, seq = ++sequence, requestEpoch = epoch;
    onStatus({ sessionId: session.id, sequence: seq, enabled: true, status: 'PROCESSING', centerPage: state.currentPage,
      fromPage: Math.max(1, state.currentPage - 3), toPage: Math.min(state.book.totalPages, state.currentPage + 5), pages: [] });
    try {
      accept(await api.readingWindow(bookId, { ...payload(seq, false), retryCurrentPage: true }), seq, requestEpoch);
    } catch (error) {
      if (valid(bookId, seq, requestEpoch)) onError(error);
    }
  }

  return { enable, stop, navigated, active, refreshStatus, retryCurrentPage, prefetch: schedulePrefetch };
}
