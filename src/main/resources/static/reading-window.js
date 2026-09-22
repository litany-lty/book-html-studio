// The reading window is deliberately ephemeral: no consent or session is persisted.
export function nearbyPages(center, total) {
  const pages = [];
  // Keep the already displayed center out of the local cache queue; prepare
  // the same five pages on either side as the server-side reading window.
  for (let offset = 1; offset <= 5; offset++) {
    if (center - offset >= 1) pages.push(center - offset);
    if (center + offset <= total) pages.push(center + offset);
  }
  return pages;
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
      const queue = [center, ...nearbyPages(center, total)].filter(page => {
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
        if (!valid(item.bookId, item.sequence, item.epoch) || cacheIsNewer(state.pageCache.get(item.pageNumber), page)) return;
        onPageReady(item.pageNumber, page);
      }).catch(error => {
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
      const cached = state.pageCache.get(pageNumber);
      if (cached?.revision === info.revision && cached?.status === 'READY') { seenReady.set(pageNumber, key); continue; }
      seenReady.set(pageNumber, key);
      readyQueue.push({ bookId: session.bookId, sequence: sequenceAtStart, epoch: epochAtStart, pageNumber });
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
    const isBusy = Boolean(snapshot?.processingPages?.length) || Boolean(snapshot?.processingPage);
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
    session = { id: crypto.randomUUID(), bookId: state.book.id, confirmed: false };
    sequence = 0;
    fixedOptions = { ...options };
    seenReady.clear();
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
