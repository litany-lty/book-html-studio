// Task completion is independent of save state and text accuracy. No synthetic time interpolation.
export function progressView(snapshot, pageStatus) {
  if (!snapshot || snapshot.messageCode === 'IDLE_NO_TASK') {
    return pageStatus && pageStatus !== 'READY'
      ? { hidden: false, text: pageStatus === 'FAILED' ? '处理失败 · 0%' : '待处理 · 0%', percent: 0, state: 'idle' }
      : { hidden: true };
  }
  const value = Number(snapshot.percent);
  const percent = Number.isFinite(value) ? Math.max(0, Math.min(100, Math.floor(value))) : null;
  if (snapshot.lifecycle === 'SUCCEEDED') return { hidden: true };
  const names = { QUEUED: '排队中', RUNNING: '处理中', DRAINING: '收尾中', PARTIAL: '部分完成',
    FAILED: '处理失败', CANCELLED: '已停止', INTERRUPTED: '已中断' };
  const stages = {PREPARING:'准备中', OCR:'识别中', STRUCTURE:'整理中', REVIEW:'核对中', VALIDATING:'校验中', PUBLISHING:'保存结果'};
  const name = snapshot.lifecycle === 'RUNNING' ? stages[snapshot.stage] || '处理中' : names[snapshot.lifecycle] || '处理中';
  return { hidden: false, text: `${name}${percent === null ? '' : ` · ${percent}%`}`, percent,
    state: ['RUNNING', 'QUEUED', 'DRAINING'].includes(snapshot.lifecycle) ? 'running' : 'settled' };
}

export function createPageProgress({ api, state, element }) {
  let key = '', generation = 0, timer = null, controller = null, snapshot = null, failures = 0;
  const currentKey = () => state.book ? `${state.book.id}:${state.currentPage}` : '';
  function render() {
    if (!element) return;
    const status = state.page?.pageNumber === state.currentPage ? state.page.status : null;
    const view = progressView(snapshot, status);
    element.hidden = view.hidden;
    if (view.hidden) return;
    element.textContent = view.text;
    element.dataset.state = view.state;
    element.setAttribute('aria-label', '本页处理进度');
    element.setAttribute('aria-valuetext', view.text);
    if (view.percent === null) element.removeAttribute('aria-valuenow');
    else element.setAttribute('aria-valuenow', String(view.percent));
    element.title = '按已完成处理阶段与核对分组计算；不是预计耗时，也不是文字准确率。正文保存状态独立显示。';
  }
  function accept(next) {
    if (!next || next.bookId !== state.book?.id || Number(next.pageNumber) !== state.currentPage) return;
    if (snapshot?.attemptId && next.attemptId === snapshot.attemptId && next.snapshotVersion < snapshot.snapshotVersion) return;
    if (snapshot?.attemptId && next.attemptId !== snapshot.attemptId && Date.parse(next.startedAt) < Date.parse(snapshot.startedAt)) return;
    snapshot = next;
    render();
  }
  async function poll(expected) {
    clearTimeout(timer); timer = null;
    if (!key || expected !== generation || key !== currentKey()) return;
    if (document.hidden) { timer = setTimeout(() => poll(expected), 5000); return; }
    controller?.abort();
    const ownController = new AbortController(); controller = ownController;
    try {
      const next = await api.pageProcessing(state.book.id, state.currentPage, ownController.signal);
      if (expected !== generation || ownController.signal.aborted) return;
      failures = 0;
      accept(next);
      if (next && ['RUNNING', 'QUEUED', 'DRAINING'].includes(next.lifecycle)) timer = setTimeout(() => poll(expected), 900);
    } catch (_) {
      if (expected !== generation || ownController.signal.aborted) return;
      failures++;
      if (element && !element.hidden) element.title = '进度暂未刷新，当前百分比保留；不会自动重发识别请求。';
      if (failures <= 5) timer = setTimeout(() => poll(expected), Math.min(8000, 500 * 2 ** failures));
    } finally { if (controller === ownController) controller = null; }
  }
  function select() {
    const nextKey = currentKey();
    if (nextKey !== key) {
      key = nextKey; generation++; snapshot = null; failures = 0;
      clearTimeout(timer); controller?.abort(); controller = null;
      render();
      if (key) void poll(generation);
    } else render();
  }
  function refresh() { select(); if (!timer && !controller && key) void poll(generation); }
  return { select, refresh, accept };
}
