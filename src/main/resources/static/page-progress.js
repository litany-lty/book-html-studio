// Work-plan progress, not elapsed time, ETA or OCR accuracy. Never interpolate with a timer.
const milestones = Object.freeze({ PREPARING: 0, OCR: 10, BASELINE_PUBLISHING: 50,
  STRUCTURE: 60, REVIEW: 65, VALIDATING: 92, PUBLISHING: 97 });
const terminal = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED']);
const count = value => Number.isFinite(Number(value)) ? Math.max(0, Math.floor(Number(value))) : 0;

export function progressView(snapshot, pageStatus) {
  if (!snapshot || snapshot.messageCode === 'IDLE_NO_TASK') {
    if (pageStatus === 'READY') return { hidden: true };
    return { hidden: false, percent: 0, label: pageStatus === 'FAILED' ? '未完成' : '等待处理', state: 'waiting' };
  }
  const life = snapshot.lifecycle;
  const units = snapshot.units || {};
  const total = count(units.total);
  const done = Math.min(total, count(units.succeeded) + count(units.failed) + count(units.skipped) + count(units.cancelled));
  let percent = milestones[snapshot.stage] ?? 0;
  if (snapshot.stage === 'REVIEW' && total > 0 && units.kind !== 'PAGE') percent += Math.floor(25 * done / total);
  if (snapshot.canRead) percent = Math.max(55, percent);
  if (life === 'SUCCEEDED') percent = 100;
  else percent = Math.min(99, percent);
  const labels = { SUCCEEDED: '处理完成', PARTIAL: '部分完成', FAILED: '处理失败', CANCELLED: '已停止', INTERRUPTED: '已中断' };
  return { hidden: false, percent, label: labels[life] || (snapshot.canRead ? '可读 · 整理' : '正在处理'),
    state: String(life || 'waiting').toLowerCase(), done, total, terminal: terminal.has(life) };
}

// Keep these related statuses together when the page header wraps on a narrow screen.
// Group once, preserving the live save-status node and both public DOM IDs.
function groupSaveAndProgress(node) {
  const document = node.ownerDocument;
  const saved = document?.getElementById('page-save-status');
  if (!saved || saved.nextElementSibling !== node || saved.parentElement?.classList.contains('page-save-progress-group')) return;
  const group = document.createElement('span');
  group.className = 'page-save-progress-group';
  group.style.cssText = 'display:inline-flex;align-items:center;white-space:nowrap;max-inline-size:100%';
  saved.before(group);
  group.append(saved, node);
}

export function createPageProgress({ api, state, element }) {
  let scope = null, snapshot = null, timer = null, controller = null, generation = 0;
  const retired = new Set();
  const key = () => state.book ? `${state.book.id}:${state.currentPage}` : null;
  function render() {
    const node = element();
    if (!node) return;
    if (!key() || !state.page) { node.hidden = true; return; }
    const view = progressView(snapshot, state.page.status);
    node.hidden = view.hidden;
    if (view.hidden) { node.textContent = ''; return; }
    groupSaveAndProgress(node);
    const text = `${view.label} ${view.percent}%`;
    if (node.textContent !== text) node.textContent = text;
    node.dataset.state = view.state;
    node.title = '当前页处理进度：按实际阶段及已结束的核对组加权；不是耗时完成率、剩余时间或文字正确率。' +
      (view.total && snapshot?.units?.kind !== 'PAGE' ? ` 核对组已结束 ${view.done}/${view.total}。` : '') +
      (view.state === 'partial' || view.state === 'failed' ? ' 未完成部分保留原文，请在详情中查看原因。' : '');
  }
  function reset() {
    ++generation;
    clearTimeout(timer); timer = null;
    controller?.abort(); controller = null;
    scope = null; snapshot = null; retired.clear();
    const node = element(); if (node) { node.hidden = true; node.textContent = ''; }
  }
  function accept(value) {
    if (!value || value.bookId !== state.book?.id || Number(value.pageNumber) !== state.currentPage) return;
    if (scope !== key()) { reset(); scope = key(); }
    if (value.attemptId && retired.has(value.attemptId)) return;
    if (snapshot?.attemptId === value.attemptId && count(value.snapshotVersion) < count(snapshot.snapshotVersion)) return;
    if (snapshot && snapshot.attemptId !== value.attemptId) {
      const incomingStart = Date.parse(value.startedAt || '');
      const currentStart = Date.parse(snapshot.startedAt || '');
      // ISO instants can omit fractional seconds; lexical ordering is not chronological.
      if (Number.isFinite(currentStart) && (!Number.isFinite(incomingStart) || incomingStart < currentStart)) return;
      retired.add(snapshot.attemptId);
      if (retired.size > 64) retired.delete(retired.values().next().value);
    }
    snapshot = value; render();
  }
  function watch(value) {
    if (!key()) { reset(); return; }
    if (scope !== key()) { reset(); scope = key(); }
    if (value) accept(value);
    render();
    if (timer || controller || globalThis.document?.hidden) return;
    const activeJob = ['QUEUED', 'RUNNING', 'CANCELLING'].includes(state.job?.status);
    if (snapshot && terminal.has(snapshot.lifecycle) && !activeJob) return;
    const started = generation, bookId = state.book.id, page = state.currentPage;
    const own = new AbortController(); controller = own;
    api.pageProgress(bookId, page, own.signal).then(result => {
      if (started !== generation || scope !== key() || own.signal.aborted) return;
      if (result?.processing) accept(result.processing);
      const jobRunning = ['QUEUED', 'RUNNING', 'CANCELLING'].includes(state.job?.status);
      if ((snapshot && !terminal.has(snapshot.lifecycle)) || jobRunning) {
        timer = setTimeout(() => { timer = null; watch(); }, 1200);
      }
    }).catch(() => {
      if (started !== generation || own.signal.aborted) return;
      // Only retry a read; never re-dispatch OCR because a status request failed.
      timer = setTimeout(() => { timer = null; watch(); }, 5000);
    }).finally(() => { if (controller === own) controller = null; });
  }
  globalThis.document?.addEventListener?.('visibilitychange', () => {
    if (document.hidden) { clearTimeout(timer); timer = null; }
    else watch();
  });
  return { accept, watch, reset };
}
