const API_ROOT = '/api';

async function request(path, options = {}, timeout = 30000) {
  const { signal: externalSignal, conditional, ...fetchOptions } = options || {};
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeout);
  const onExternalAbort = () => controller.abort();
  if (externalSignal) {
    if (externalSignal.aborted) controller.abort();
    else externalSignal.addEventListener('abort', onExternalAbort, { once: true });
  }
  try {
    const response = await fetch(`${API_ROOT}${path}`, { ...fetchOptions, signal: controller.signal });
    if (response.status === 304 && conditional?._etag) return conditional;
    if (!response.ok) {
      let message = `请求失败（${response.status}）`;
      let body = null;
      try { body = await response.json(); message = body.message || message; } catch (_) { /* non-JSON error */ }
      // R07：把状态码与响应体挂在错误上，调用方可区分 409 冲突等情况
      const failure = new Error(message);
      failure.status = response.status;
      failure.body = body;
      throw failure;
    }
    const body = response.status === 204 ? null : await response.json();
    return conditional && body ? { ...body, _etag: response.headers.get('ETag') } : body;
  } catch (error) {
    // 阶段2：快速翻页丢弃过时请求——外部取消不提示超时，后端仍以自身预算为准
    if (error.name === 'AbortError' && externalSignal?.aborted) {
      const stale = new Error('已翻页，该请求已丢弃。');
      stale.name = 'StaleRequest';
      throw stale;
    }
    if (error.name === 'AbortError') {
      // A1-S06：超时需要调用方核实服务端是否已保存，保留可识别的错误身份
      const timeout = new Error('请求超时，请检查服务状态后重试。');
      timeout.name = 'TimeoutError';
      throw timeout;
    }
    throw error;
  } finally {
    window.clearTimeout(timer);
    externalSignal?.removeEventListener?.('abort', onExternalAbort);
  }
}

export const api = {
  readingPolicy: () => request('/reading-policy'),
  config: () => request('/config'),
  settings: () => request('/settings'),
  saveSettings: (body, csrfToken) => request('/settings', { method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-Settings-Token': csrfToken }, body: JSON.stringify(body) }),
  books: () => request('/books'),
  book: id => request(`/books/${encodeURIComponent(id)}`),
  readerBook: id => request(`/books/${encodeURIComponent(id)}/reader`),
  pageProgress: (id, n, signal, previous = {}) => request(`/books/${encodeURIComponent(id)}/reader/pages/${n}/progress`,
    { signal, conditional: previous, ...(previous._etag ? { headers: { 'If-None-Match': previous._etag } } : {}) }),
  updateLibraryBook: (id, body) => request(`/books/${encodeURIComponent(id)}/library`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  bookUsage: (id, cursor = null, limit = 50, asOf = null, signal, offset = null) => request(`/books/${encodeURIComponent(id)}/usage?${new URLSearchParams({ limit: String(limit), ...(cursor ? { cursor } : offset != null ? { offset: String(offset) } : {}), ...(asOf ? { asOf } : {}) })}`, signal ? { signal } : {}),
  upload(file) {
    const body = new FormData();
    body.append('file', file);
    return request('/books', { method: 'POST', body }, 120000);
  },
  pages: id => request(`/books/${encodeURIComponent(id)}/pages`),
  outline: id => request(`/books/${encodeURIComponent(id)}/outline`),
  page: (id, n, signal) => request(`/books/${encodeURIComponent(id)}/reader/pages/${n}`, signal ? { signal } : {}),
  issueMetadata: (id, n, issueId, signal) => request(`/books/${encodeURIComponent(id)}/pages/${n}/issues/${encodeURIComponent(issueId)}`, signal ? { signal } : {}),
  pageImage: (id, n, width = 1800) => `${API_ROOT}/books/${encodeURIComponent(id)}/pages/${n}/image?width=${width}`,
  figureImage: (id, n, blockId) => `${API_ROOT}/books/${encodeURIComponent(id)}/pages/${n}/figures/${encodeURIComponent(blockId)}`,
  startJob: (id, body) => request(`/books/${encodeURIComponent(id)}/jobs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  job: id => request(`/books/${encodeURIComponent(id)}/job`),
  cancelJob: id => request(`/books/${encodeURIComponent(id)}/job/cancel`, { method: 'POST' }),
  readingWindow: (id, body) => request(`/books/${encodeURIComponent(id)}/reading-window`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  readingWindowStatus: (id, sessionId) => request(`/books/${encodeURIComponent(id)}/reading-window?sessionId=${encodeURIComponent(sessionId)}`),
  stopReadingWindow: (id, body) => request(`/books/${encodeURIComponent(id)}/reading-window/stop`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  savePage: (id, n, body, timeoutMs) => request(`/books/${encodeURIComponent(id)}/pages/${n}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, timeoutMs ?? 30000),
  // J07/J08：决策作业独立作用域，不复用 saveInFlight；取消与完整响应期限。
  decisionJobsCreate: (id, n, issueId, body, signal, timeoutMs) => request(`/books/${encodeURIComponent(id)}/pages/${n}/issues/${encodeURIComponent(issueId)}/decision-jobs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), ...(signal ? { signal } : {}) }, timeoutMs ?? 30000),
  decisionJob: (id, jobId, signal) => request(`/books/${encodeURIComponent(id)}/decision-jobs/${encodeURIComponent(jobId)}`, signal ? { signal } : {}),
  decisionJobCancel: (id, jobId, body) => request(`/books/${encodeURIComponent(id)}/decision-jobs/${encodeURIComponent(jobId)}/cancel`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  decisions: (id, n, issueId, signal) => request(`/books/${encodeURIComponent(id)}/pages/${n}/issues/${encodeURIComponent(issueId)}/decisions`, signal ? { signal } : {}),
  decisionAccept: (id, n, issueId, decisionId, body, timeoutMs) => request(`/books/${encodeURIComponent(id)}/pages/${n}/issues/${encodeURIComponent(issueId)}/decisions/${encodeURIComponent(decisionId)}/accept`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }, timeoutMs ?? 30000),
  revisions: (id, n) => request(`/books/${encodeURIComponent(id)}/pages/${n}/revisions`),
  revertPage: (id, n, revision, expectedRevision) => request(`/books/${encodeURIComponent(id)}/pages/${n}/revert`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(expectedRevision == null ? { revision } : { revision, expectedRevision }) }),
  // U3：展示层人工覆盖（只改目录/展示角色，不算全文人工校对）。
  presentationOverrides: (id, n) => request(`/books/${encodeURIComponent(id)}/pages/${n}/presentation-overrides`),
  applyPresentationOverride: (id, n, body) => request(`/books/${encodeURIComponent(id)}/pages/${n}/presentation-overrides`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  previewOverrideScope: (id, n, blockId) => request(`/books/${encodeURIComponent(id)}/pages/${n}/presentation-overrides/preview?blockId=${encodeURIComponent(blockId)}`),
  search: (id, query, signal) => request(`/books/${encodeURIComponent(id)}/search?q=${encodeURIComponent(query)}`, { signal }),
  exportUrl: id => `${API_ROOT}/books/${encodeURIComponent(id)}/export`
};
