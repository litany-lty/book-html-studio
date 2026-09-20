const API_ROOT = '/api';

async function request(path, options = {}, timeout = 30000) {
  const { signal: externalSignal, ...fetchOptions } = options || {};
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeout);
  const onExternalAbort = () => controller.abort();
  if (externalSignal) {
    if (externalSignal.aborted) controller.abort();
    else externalSignal.addEventListener('abort', onExternalAbort, { once: true });
  }
  try {
    const response = await fetch(`${API_ROOT}${path}`, { ...fetchOptions, signal: controller.signal });
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
    return response.status === 204 ? null : response.json();
  } catch (error) {
    // 阶段2：快速翻页丢弃过时请求——外部取消不提示超时，后端仍以自身预算为准
    if (error.name === 'AbortError' && externalSignal?.aborted) {
      const stale = new Error('已翻页，该请求已丢弃。');
      stale.name = 'StaleRequest';
      throw stale;
    }
    if (error.name === 'AbortError') throw new Error('请求超时，请检查服务状态后重试。');
    throw error;
  } finally {
    window.clearTimeout(timer);
    externalSignal?.removeEventListener?.('abort', onExternalAbort);
  }
}

export const api = {
  config: () => request('/config'),
  books: () => request('/books'),
  book: id => request(`/books/${encodeURIComponent(id)}`),
  upload(file) {
    const body = new FormData();
    body.append('file', file);
    return request('/books', { method: 'POST', body }, 120000);
  },
  pages: id => request(`/books/${encodeURIComponent(id)}/pages`),
  outline: id => request(`/books/${encodeURIComponent(id)}/outline`),
  page: (id, n, signal) => request(`/books/${encodeURIComponent(id)}/pages/${n}`, signal ? { signal } : {}),
  issueMetadata: (id, n, issueId, signal) => request(`/books/${encodeURIComponent(id)}/pages/${n}/issues/${encodeURIComponent(issueId)}`, signal ? { signal } : {}),
  pageImage: (id, n, width = 1800) => `${API_ROOT}/books/${encodeURIComponent(id)}/pages/${n}/image?width=${width}`,
  figureImage: (id, n, blockId) => `${API_ROOT}/books/${encodeURIComponent(id)}/pages/${n}/figures/${encodeURIComponent(blockId)}`,
  startJob: (id, body) => request(`/books/${encodeURIComponent(id)}/jobs`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  job: id => request(`/books/${encodeURIComponent(id)}/job`),
  cancelJob: id => request(`/books/${encodeURIComponent(id)}/job/cancel`, { method: 'POST' }),
  savePage: (id, n, body) => request(`/books/${encodeURIComponent(id)}/pages/${n}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  revisions: (id, n) => request(`/books/${encodeURIComponent(id)}/pages/${n}/revisions`),
  revertPage: (id, n, revision, expectedRevision) => request(`/books/${encodeURIComponent(id)}/pages/${n}/revert`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(expectedRevision == null ? { revision } : { revision, expectedRevision }) }),
  search: (id, query) => request(`/books/${encodeURIComponent(id)}/search?q=${encodeURIComponent(query)}`),
  exportUrl: id => `${API_ROOT}/books/${encodeURIComponent(id)}/export`
};
