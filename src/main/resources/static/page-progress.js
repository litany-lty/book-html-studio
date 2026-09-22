const stages = { PREPARING: '准备', OCR: '识别', STRUCTURE: '整理', REVIEW: '核对', VALIDATING: '校验', PUBLISHING: '保存结果' };
const running = new Set(['QUEUED', 'RUNNING', 'DRAINING']);
const count = value => Number.isFinite(Number(value)) ? Math.max(0, Math.floor(Number(value))) : 0;

/** Percentage describes completed units in the named stage, never elapsed time or OCR accuracy. */
export function progressView(status, processing) {
  if (!processing) return ['PENDING', 'PROCESSING'].includes(status)
    ? { text: '等待处理 · 0%', percent: 0, active: true, title: '尚无已完成的处理阶段；阅读原稿不受影响' }
    : { text: '', percent: null, active: false, title: '' };
  if (!running.has(processing.lifecycle)) {
    const text = { PARTIAL: '部分完成 · 待核对', FAILED: '处理失败 · 可重试', CANCELLED: '已停止', INTERRUPTED: '处理已中断' }[processing.lifecycle] || '';
    return { text, percent: null, active: false, title: '处理状态不代表文字已全部核对正确' };
  }
  const units = processing.units || {};
  const total = count(units.total);
  const done = Math.min(total, count(units.succeeded) + count(units.failed) + count(units.skipped) + count(units.cancelled));
  const percent = total > 0 ? Math.floor(done * 100 / total) : null;
  const label = stages[processing.stage] || '处理中';
  return { text: `${label}${percent == null ? '中' : ` · ${percent}%`}`, percent, active: true,
    title: `${label}阶段已结束 ${done}/${total || '待确定'} 个任务单位；这是本阶段完成比例，不是耗时比例、整本书进度或识别正确率${count(units.failed) ? `；${count(units.failed)} 个单位失败，原文保留` : ''}` };
}

/** One in-flight read, page/attempt/version fences, no paid POST and no body re-render on ticks. */
export function createPageProgress({ element, api, state, onPublished = () => {} }) {
  let generation = 0, timer = null, controller = null, observed = null;
  let key = '', attempt = null, version = -1, lastPublished = null;
  const currentKey = () => state.book ? `${state.book.id}:${state.currentPage}` : '';
  function render(status = state.page?.status, snapshot = observed) {
    if (!element) return;
    const view = progressView(status, snapshot);
    element.textContent = view.text; element.hidden = !view.text;
    element.title = view.title; element.dataset.state = view.active ? 'processing' : 'settled';
    element.setAttribute('aria-label', `${view.text}。${view.title}`);
    if (view.active) {
      element.setAttribute('role', 'progressbar'); element.setAttribute('aria-valuemin', '0'); element.setAttribute('aria-valuemax', '100');
      if (view.percent == null) element.removeAttribute('aria-valuenow');
      else element.setAttribute('aria-valuenow', String(view.percent));
    } else { element.removeAttribute('role'); element.removeAttribute('aria-valuenow'); }
  }
  function accept(snapshot, status = state.page?.status) {
    if (!snapshot || snapshot.bookId !== state.book?.id || Number(snapshot.pageNumber) !== state.currentPage) return;
    if (attempt === snapshot.attemptId && Number(snapshot.snapshotVersion) < version) return;
    if (observed && attempt !== snapshot.attemptId && Date.parse(snapshot.startedAt) < Date.parse(observed.startedAt)) return;
    attempt = snapshot.attemptId; version = Number(snapshot.snapshotVersion) || 0; observed = snapshot;
    render(status, snapshot);
  }
  function stop() { ++generation; clearTimeout(timer); controller?.abort(); controller = null; }
  async function poll(ownGeneration) {
    if (ownGeneration !== generation || key !== currentKey()) return;
    if (document.hidden) { timer = setTimeout(() => poll(ownGeneration), 2000); return; }
    const bookId = state.book.id, number = state.currentPage;
    controller = new AbortController();
    let delay = 2000;
    try {
      const status = await api.pageProgress(bookId, number, controller.signal);
      if (ownGeneration !== generation || key !== currentKey() || status.bookId !== bookId || Number(status.pageNumber) !== number) return;
      if (status.processing) accept(status.processing, status.status); else { observed = null; render(status.status, null); }
      delay = running.has(status.processing?.lifecycle) || status.status === 'PROCESSING' ? 750 : 3000;
      const revision = Number(status.revision);
      if (status.status === 'READY' && Number.isFinite(revision) && state.page?.revision !== status.revision && lastPublished !== revision) {
        await onPublished(bookId, number, status.revision, controller.signal);
        if (ownGeneration === generation) lastPublished = revision;
      }
    } catch (_) { delay = 5000; /* Local status failure is not permission to retry OCR. */ }
    finally {
      if (ownGeneration === generation && key === currentKey()) timer = setTimeout(() => poll(ownGeneration), delay);
    }
  }
  function watch() {
    stop(); key = currentKey(); observed = null; attempt = null; version = -1; lastPublished = null;
    if (!key) { if (element) element.hidden = true; return; }
    render(); timer = setTimeout(() => poll(generation), 0);
  }
  return { watch, stop, accept, render };
}
