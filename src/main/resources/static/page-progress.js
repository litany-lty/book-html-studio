// A task milestone percentage is not an OCR accuracy score or a time estimate.
export function progressView(snapshot, page) {
  if (!snapshot) {
    if (!page || page.status === 'READY') return { hidden: true };
    return { hidden: false, percent: 0, label: page.status === 'FAILED' ? '未完成 · 0%' : '待处理 · 0%', state: 'waiting' };
  }
  const value = Number(snapshot.percent);
  const percent = Number.isFinite(value) ? Math.max(0, Math.min(100, Math.floor(value))) : 0;
  const phase = { PREPARING: '准备', OCR: '识别', BASELINE_PUBLISHING: '保存正文', STRUCTURE: '整理', REVIEW: '核对', VALIDATING: '校验', PUBLISHING: '保存' };
  const terminal = { SUCCEEDED: '已处理', PARTIAL: '部分完成', FAILED: '处理失败', CANCELLED: '已停止', INTERRUPTED: '已中断' };
  const name = terminal[snapshot.lifecycle] || phase[snapshot.stage] || '处理中';
  return { hidden: snapshot.lifecycle === 'SUCCEEDED', percent, label: `${name} · ${percent}%`, state: terminal[snapshot.lifecycle] ? 'settled' : 'processing' };
}

export function createPageProgress({ api, onPublished }) {
  const element = document.querySelector('#page-processing-progress');
  let current = null, snapshot = null, timer = null, controller = null, epoch = 0;
  let lastAttempt = null, lastVersion = -1;
  function render() {
    if (!element) return;
    const view = progressView(snapshot, current?.page);
    element.hidden = view.hidden;
    if (view.hidden) return;
    element.textContent = view.label;
    element.dataset.state = view.state;
    element.setAttribute('aria-valuenow', String(view.percent));
    element.setAttribute('aria-valuetext', view.label);
    element.style.setProperty('--page-progress', `${view.percent}%`);
    element.title = '当前页处理阶段完成比例，不是文字准确率或剩余时间。疑点仍须对照原稿核对。';
  }
  function update(next) {
    if (!current || !next || next.bookId !== current.id || Number(next.pageNumber) !== current.n) return;
    // Same-attempt event order matters; a different attempt is compared by its start time.
    if (snapshot?.attemptId && next.attemptId !== snapshot.attemptId &&
        Date.parse(next.startedAt) < Date.parse(snapshot.startedAt)) return;
    if (next.attemptId === lastAttempt && next.snapshotVersion < lastVersion) return;
    lastAttempt = next.attemptId; lastVersion = next.snapshotVersion;
    snapshot = next; render();
  }
  async function poll(token) {
    if (!current || token !== epoch) return;
    if (!document.hidden) {
      controller = new AbortController();
      const { id, n } = current;
      try {
        const status = await api.pageProgress(id, n, controller.signal);
        if (token !== epoch) return;
        update(status.processing);
        if (status.status === 'READY' && (status.revision !== current.page?.revision || current.page?.status !== 'READY')) {
          const page = await api.page(id, n, controller.signal);
          if (token !== epoch) return;
          current.page = page; render(); onPublished(n, page);
        }
      } catch (_) { /* Read-only refresh retries; never resubmit a paid request. */ }
    }
    if (token === epoch) timer = setTimeout(() => poll(token), snapshot?.lifecycle === 'RUNNING' ? 1000 : 5000);
  }
  function clear() {
    ++epoch; clearTimeout(timer); controller?.abort(); current = snapshot = null;
    lastAttempt = null; lastVersion = -1;
    if (element) element.hidden = true;
  }
  function setPage(id, n, page) {
    if (current?.id === id && current.n === n) { current.page = page; render(); return; }
    clear(); current = { id, n, page }; render(); void poll(epoch);
  }
  return { setPage, update, clear };
}
