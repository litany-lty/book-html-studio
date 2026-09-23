// A task milestone percentage is not an OCR accuracy score or a time estimate.
export function progressView(snapshot, page) {
  if (!snapshot) {
    if (!page || page.status === 'READY') return { hidden: true };
    return { hidden: false, percent: 0, label: page.status === 'FAILED' ? '未完成 · 0%' : '待处理 · 0%', state: 'waiting' };
  }
  const value = Number(snapshot.percent);
  const percent = Number.isFinite(value) ? Math.max(0, Math.min(100, Math.floor(value))) : 0;
  const phase = { PREPARING: '准备', OCR: '识别', BASELINE_PUBLISHING: '保存正文', STRUCTURE: '整理', REVIEW: '核对', VALIDATING: '校验', PUBLISHING: '保存' };
  const terminal = { SUCCEEDED: '已处理', PARTIAL: '部分完成', FAILED: '处理失败', CANCELLED: '已停止', INTERRUPTED: '已中断', UNKNOWN: '结果待确认' };
  const name = terminal[snapshot.lifecycle] || phase[snapshot.stage] || '处理中';
  if (snapshot.stage === 'RECOVERED') return { hidden: snapshot.lifecycle === 'SUCCEEDED', percent: null, label: `${name} · 状态已恢复`, state: terminal[snapshot.lifecycle] ? 'settled' : 'processing' };
  return { hidden: snapshot.lifecycle === 'SUCCEEDED', percent, label: `${name} · ${percent}%`, state: terminal[snapshot.lifecycle] ? 'settled' : 'processing' };
}

export function createPageProgress({ api, onPublished }) {
  const element = document.querySelector('#page-processing-progress');
  let current = null, snapshot = null, timer = null, controller = null, epoch = 0;
  let lastAttempt = null, lastVersion = -1, lastResponse = {}, inFlightEpoch = null;
  let serverInstanceId = null;
  const retiredServers = new Set();
  function render() {
    if (!element) return;
    const view = progressView(snapshot, current?.page);
    element.hidden = view.hidden;
    if (view.hidden) return;
    element.textContent = view.label;
    element.dataset.state = view.state;
    if (view.percent == null) element.removeAttribute('aria-valuenow');
    else element.setAttribute('aria-valuenow', String(view.percent));
    element.setAttribute('aria-valuetext', view.label);
    element.style.setProperty('--page-progress', `${view.percent ?? 0}%`);
    element.title = '当前页处理阶段完成比例，不是文字准确率或剩余时间。疑点仍须对照原稿核对。';
  }
  function update(next) {
    if (!current || !next || next.bookId !== current.id || Number(next.pageNumber) !== current.n) return;
    if (typeof next.serverInstanceId === 'string' && next.serverInstanceId.length > 0 && next.serverInstanceId.length <= 80) {
      if (retiredServers.has(next.serverInstanceId)) return;
      if (serverInstanceId && serverInstanceId !== next.serverInstanceId) {
        retiredServers.add(serverInstanceId);
        if (retiredServers.size > 16) retiredServers.delete(retiredServers.values().next().value);
        snapshot = null; lastVersion = -1; lastAttempt = null; lastResponse = {};
      }
      serverInstanceId = next.serverInstanceId;
    }
    if (snapshot?.attemptId) {
      if (next.attemptId === snapshot.attemptId) {
        // A compatibility response may fill the persistent identity without changing the event.
        if (next.snapshotVersion === lastVersion && !(Number(snapshot.attemptSeq) > 0)
            && Number.isSafeInteger(Number(next.attemptSeq)) && Number(next.attemptSeq) > 0) {
          snapshot = { ...snapshot, attemptSeq: Number(next.attemptSeq) }; return;
        }
        if (next.snapshotVersion <= lastVersion) return;
        if (['SUCCEEDED','PARTIAL','FAILED','CANCELLED','INTERRUPTED','UNKNOWN'].includes(snapshot.lifecycle)
            && next.lifecycle !== snapshot.lifecycle) return;
      } else {
        const before = Number(snapshot.attemptSeq), after = Number(next.attemptSeq);
        if (Number.isSafeInteger(before) && before > 0) {
          if (!Number.isSafeInteger(after) || after <= before) return;
        } else {
          const time = Date.parse(next.startedAt), previousTime = Date.parse(snapshot.startedAt);
          if (!Number.isFinite(time) || (Number.isFinite(previousTime) && time <= previousTime)) return;
        }
      }
    }
    lastAttempt = next.attemptId; lastVersion = next.snapshotVersion;
    snapshot = next; render();
  }
  async function poll(token) {
    if (!current || token !== epoch || inFlightEpoch === token || document.hidden) return;
    clearTimeout(timer); inFlightEpoch = token;
    const own = new AbortController(); controller = own;
    const { id, n } = current;
    try {
      const status = await api.pageProgress(id, n, own.signal, lastResponse);
      if (token !== epoch) return;
      lastResponse = status; update(status.processing);
      if (status.status === 'READY' && (status.revision !== current.page?.revision || current.page?.status !== 'READY')) {
        const page = await api.page(id, n, own.signal);
        if (token !== epoch) return;
        current.page = page; render(); onPublished(n, page);
      }
    } catch (_) { /* Status failures never retry a model request. */ }
    finally {
      if (controller === own) controller = null;
      if (inFlightEpoch === token) inFlightEpoch = null;
      if (token === epoch && !document.hidden) timer = setTimeout(() => poll(token),
        ['QUEUED','RUNNING','DRAINING','BASELINE_PUBLISHED'].includes(snapshot?.lifecycle) ? 1000 : 30000);
    }
  }
  function wake() {
    clearTimeout(timer);
    if (current && !document.hidden) void poll(epoch);
  }
  function clear() {
    ++epoch; clearTimeout(timer); controller?.abort(); current = snapshot = null;
    lastAttempt = null; lastVersion = -1; lastResponse = {}; inFlightEpoch = null;
    if (element) element.hidden = true;
  }
  function setPage(id, n, page) {
    if (current?.id === id && current.n === n) { current.page = page; render(); return; }
    clear(); current = { id, n, page }; render(); void poll(epoch);
  }
  document.addEventListener?.('visibilitychange', () => { if (document.hidden) clearTimeout(timer); else wake(); });
  return { setPage, update, clear, wake };
}
