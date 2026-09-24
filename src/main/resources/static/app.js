import { api } from './api.js';
import { state, loadPreferences, savePreferences, getBookmarks, setBookmarks, cloneBlocks, isSameSession, canonicalJson, sessionGuard } from './store.js';
import { renderPaper, qualityOf, statusMessage } from './reader.js';
import { renderEditor, renderIssueWorkbench, unmountIssueWorkbench, issueReferences, createOverlay, enableDrawing } from './editor.js';
import { createDecisionPanel } from './decision.js';
import './settings.js';
import { openBookUsage } from './usage.js';
import { createReadingWindow } from './reading-window.js';
import { createLibrary } from './library.js';
import { initReaderMode } from './reader-mode.js';
import { recordAnchor, restoreAnchor } from './reading-anchor.js';
import { createPageProgress } from './page-progress.js';

const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const pageProgress = createPageProgress({
  api,
  onPublished: (n, page) => acceptReadyPage(n, page),
  onRetry: () => retryCurrentPage()
});
const readerNavigation = globalThis.BookReaderNavigation;
let cancelDrawing = null;
let scrollTimer = null;
let bookRequest = 0;
let pageRequest = 0;
// 阶段2：快速翻页取消过时正文请求
let pageFetchController = null;
let editVersion = 0;
let outlineRequest = 0;
let pendingPageTarget = null;
let searchGeneration = 0;
let searchController = null;
let structureGeneration = 0;
let pageInputIntent = 0;
let assistPreference;
let splitSpreadsPreference = true;
let jobSyncError = false;
let readingSnapshot = null;
let deferredReady = null;
const readingMetadataSignatures = new Map();
const readingMetadataVersions = new Map();
// U3：随读增量目录按画像版本取投影；旧响应不得把已排除书眉写回目录。
const readingMetadataProfiles = new Map();

function currentPageProtected() {
  return Boolean(state.dirty || state.conflict || currentSaveInFlight());
}

function olderRevision(incoming, current) {
  const next = Number(incoming?.revision), existing = Number(current?.revision);
  return incoming?.revision != null && current?.revision != null && Number.isFinite(next) && Number.isFinite(existing) && next < existing;
}

function renderReadingWindowStatus(snapshot = readingSnapshot) {
  readingSnapshot = snapshot;
  const currentProgress = snapshot?.pages?.find(p => p.pageNumber === state.currentPage)?.processing;
  if (currentProgress) pageProgress.update(currentProgress);
  const active = readingWindow?.active() || false;
  const bar = $('#reading-window-bar');
  bar.hidden = !active && !snapshot && !deferredReady;
  document.body.classList.toggle('reading-window-visible', !bar.hidden);
  // U1：阅读模式唯一安静任务入口。只显示活动任务数，不罗列页码/通道/并发量，
  // 不带呼吸灯/流光/全宽进度条；预留宽度内更新，不改变工具栏高度与布局列数。
  const entry = $('#reading-window-open');
  const activeCount = snapshot?.processingPages?.length || (snapshot?.processingPage ? 1 : 0) || 0;
  const failedCount = (snapshot?.pages || []).filter(page => page.status === 'FAILED').length;
  entry.dataset.active = String(active);
  entry.setAttribute('aria-pressed', String(active));
  entry.textContent = activeCount > 0 ? `任务 · ${activeCount}` : '任务';
  entry.dataset.attention = String(failedCount > 0);
  entry.title = failedCount > 0 ? `${failedCount} 页需重试，展开任务详情查看` : '查看任务详情';
  $('#job-form button[type="submit"]').disabled = active || Boolean(state.pollTimer) || !state.book || jobSyncError;
  $('#reading-window-stop').hidden = !active;
  $('#reading-window-apply').hidden = !deferredReady || deferredReady.bookId !== state.book?.id || deferredReady.page !== state.currentPage;
  $('#reading-window-manual').hidden = !(snapshot?.pages || []).some(page => page.status === 'FAILED');
  $('#reading-window-refresh').hidden = !active;
  const labels = { SETTLING: '停留 1 秒后开始', PROCESSING: '正在识别', READY: '窗口处理结束', IDLE: '等待阅读', STOPPING: '正在停止', STOPPED: '已停止', STOP_REQUESTED: '已发停止请求', STOP_UNKNOWN: '停止尚未确认', EXPIRED: '会话已过期', BLOCKED: '处理被占用' };
  const failed = (snapshot?.pages || []).filter(page => page.status === 'FAILED').map(page => page.pageNumber);
  // U4：当前页真实阶段来自后端事件（非计时推测）；未知剩余工作量不显示百分比。
  const centerInfo = (snapshot?.pages || []).find(p => Number(p.pageNumber) === state.currentPage);
  const proc = centerInfo?.processing || null;
  const stageLabels = { PREPARING: '正在准备', OCR: '正在识别文字', STRUCTURE: '正在整理版面', REVIEW: '正在核对疑字', VALIDATING: '正在校验', PUBLISHING: '正在发布' };
  const stageText = proc && proc.lifecycle === 'RUNNING' && stageLabels[proc.stage] ? stageLabels[proc.stage] : null;
  $('#reading-window-status').textContent = deferredReady && state.book && deferredReady.bookId === state.book.id && deferredReady.page === state.currentPage
    ? (deferredReady.revision == null
      ? (currentPageProtected() ? '页面状态已更新，保存后可读取' : '页面状态已更新，可读取本页')
      : (currentPageProtected() ? '识别结果已就绪，保存后更新' : '识别结果已就绪，可更新本页')) : snapshot?.status === 'READY' && failed.length
      ? '本轮结束，部分页需手动重试' : (stageText || labels[snapshot?.status] || (active ? '随读识别' : '随读识别已停止'));
  const processingCount = snapshot?.processingPages?.length || 0;
  let processingText = '';
  if (processingCount > 2) {
    processingText = ` · ${processingCount} 页并发处理中`;
  } else if (processingCount > 0) {
    processingText = ` · 正在处理第 ${snapshot.processingPages.join('、')} 页`;
  } else if (snapshot?.processingPage) {
    processingText = ` · 正在处理第 ${snapshot.processingPage} 页`;
  }

  const failedText = failed.length ? ` · ${failed.length} 页失败` : '';
  // U4：固定局部核对计划显示任务单位数（非耗时完成率）；基线可读后增强仍工作显示可读文案。
  let unitsText = '';
  const units = proc?.units;
  if (proc && proc.lifecycle === 'RUNNING' && units && units.total > 0 && units.kind !== 'PAGE') {
    const done = (units.succeeded || 0) + (units.failed || 0) + (units.skipped || 0);
    unitsText = ` · 已核对 ${done} / ${units.total} 组`;
  }
  let readableText = '';
  if (proc && proc.lifecycle === 'RUNNING' && proc.contentAvailability === 'OCR_READABLE'
      && (proc.stage === 'STRUCTURE' || proc.stage === 'REVIEW')) {
    readableText = ' · 文字已可阅读，正在整理版面';
  }

  $('#reading-window-pages').textContent = snapshot?.message || (snapshot?.centerPage
    ? `第 ${snapshot.centerPage} 页优先 · 准备 ${snapshot.fromPage}–${snapshot.toPage} 页${processingText}${failedText}${unitsText}${readableText}`
    : '随读识别就绪');
  bar.title = '随读识别：未识别页仍显示原稿，缓存页不重复消耗额度。';
  renderJobHeading();
}

function sameOutline(left, right) {
  return left.length === right.length && left.every((entry, index) =>
    Number(entry.pageNumber) === Number(right[index].pageNumber) && entry.blockId === right[index].blockId &&
    entry.title === right[index].title && Number(entry.level) === Number(right[index].level));
}

function mergeReadingMetadata(snapshot) {
  if (!state.book || !Array.isArray(snapshot?.pages)) return;
  let processedDelta = 0, reviewedDelta = 0;
  const outlineUpdates = new Map();
  for (const info of snapshot.pages) {
    const number = Number(info.pageNumber);
    if (!Number.isInteger(number) || number < 1 || number > state.book.totalPages || !info.summary) continue;
    const revision = info.revision == null ? null : Number(info.revision);
    const knownRevision = readingMetadataVersions.get(number);
    if (revision != null && Number.isFinite(revision) && knownRevision != null && revision < knownRevision) continue;
    if (number === state.currentPage && olderRevision(info, state.page)) continue;
    if (olderRevision(info, state.pageCache.get(number))) continue;
    const signature = JSON.stringify([info.revision, info.summary, info.outline]);
    if (readingMetadataSignatures.get(number) === signature) continue;
    readingMetadataSignatures.set(number, signature);
    if (revision != null && Number.isFinite(revision)) readingMetadataVersions.set(number, revision);
    const summary = info.summary;
    const index = state.summaries[number - 1]?.pageNumber === number ? number - 1
      : state.summaries.findIndex(item => item.pageNumber === number);
    const previous = index >= 0 ? state.summaries[index] : null;
    const changed = !previous || ['pageNumber', 'status', 'blockCount', 'uncertainCount', 'width', 'height', 'title', 'reviewed']
      .some(key => previous[key] !== summary[key]);
    if (changed) {
      processedDelta += Number(summary.status === 'READY') - Number(previous?.status === 'READY');
      reviewedDelta += Number(Boolean(summary.reviewed)) - Number(Boolean(previous?.reviewed));
      if (index >= 0) state.summaries[index] = summary;
      else state.summaries.splice(number - 1, 0, summary);
    }
    if (Array.isArray(info.outline)) {
      // U3：画像更新影响前页时，以 profileRevision 触发轻量目录更新；旧响应不反灌。
      const incomingProfile = Number(info.profileRevision || 0);
      const knownProfile = readingMetadataProfiles.get(number) || 0;
      if (incomingProfile < knownProfile) continue;
      readingMetadataProfiles.set(number, incomingProfile);
      const current = state.outline.filter(entry => Number(entry.pageNumber) === number);
      if (!sameOutline(current, info.outline)) outlineUpdates.set(number, info.outline);
    }
  }
  if (processedDelta || reviewedDelta) {
    state.book.processedPages = Math.max(0, Math.min(state.book.totalPages, Number(state.book.processedPages || 0) + processedDelta));
    state.book.reviewedPages = Math.max(0, Math.min(state.book.totalPages, Number(state.book.reviewedPages || 0) + reviewedDelta));
    state.books = state.books.map(book => book.id === state.book.id ? state.book : book);
    renderBookMeta();
  }
  if (outlineUpdates.size) {
    state.outline = [...state.outline.filter(entry => !outlineUpdates.has(Number(entry.pageNumber))),
      ...[...outlineUpdates.values()].flat()].sort((left, right) => Number(left.pageNumber) - Number(right.pageNumber));
    renderToc();
  }
}

function acceptReadyPage(pageNumber, page) {
  if (!state.book || pageNumber !== Number(page.pageNumber)) return;
  if (olderRevision(page, state.pageCache.get(pageNumber)) ||
      (pageNumber === state.currentPage && olderRevision(page, state.page))) return;
  const bookId = state.book.id;
  if (pageNumber === state.currentPage) {
    if (currentPageProtected()) {
      deferredReady = { bookId, page: pageNumber, revision: page.revision };
      renderReadingWindowStatus();
      return;
    }
    const scrollTop = $('#reader').scrollTop;
    const anchor = recordAnchor($('#reader'));
    if (!state.page) { pageFetchController?.abort(); ++pageRequest; }
    state.pageCache.set(pageNumber, page);
    state.page = page; state.blocks = cloneBlocks(page.blocks); state.reviewedDraft = Boolean(page.reviewed);
    state.selectedBlockId = null; state.selectedIssueId = null; state.editorEpoch++;
    convertingState.completedAt = Date.now();
    convertingState.completedPage = pageNumber;
    convertingState.wasReprocessing = Boolean(convertingState.isReprocessing || state.reprocessingPage === pageNumber);
    convertingState.isReprocessing = false;
    state.reprocessingPage = null;
    renderCurrent();
    renderJobHeading();
    setTimeout(() => {
      if (convertingState.completedPage === pageNumber) {
        convertingState.pageNumber = null;
        convertingState.completedAt = 0;
        convertingState.completedPage = null;
        renderJobHeading();
      }
    }, 1200);
    // U1：用阅读锚点恢复位置（同源块/邻近稳定文本），节点消失时回退 scrollTop。
    requestAnimationFrame(() => {
      if (state.book?.id !== bookId || state.currentPage !== pageNumber) return;
      const root = $('#reader');
      if (!restoreAnchor(root, anchor)) root.scrollTop = scrollTop;
    });
  } else {
    state.pageCache.set(pageNumber, page);
    if (state.outline.some(entry => Number(entry.pageNumber) === pageNumber)) renderToc();
  }
  renderReadingWindowStatus();
}

const readingWindow = createReadingWindow({ api, state,
  onStatus: snapshot => { mergeReadingMetadata(snapshot); renderReadingWindowStatus(snapshot); },
  onPageReady: acceptReadyPage,
  onError: error => {
    const note = error?.status === 409 ? '本书已有手动处理任务或其他标签的随读识别；没有取消对方。请等其结束后手动刷新。' : error?.message || '随读状态读取失败，请手动刷新状态。';
    toast(note, 'error');
    $('#reading-window-refresh').hidden = !readingWindow.active();
  }
});

function toast(message, tone = 'info') {
  const item = document.createElement('div');
  item.className = `toast ${tone}`;
  item.textContent = message;
  $('#toast-region').append(item);
  window.setTimeout(() => item.remove(), 4200);
}

function showError(error) {
  toast(error?.message || '操作没有完成，请重试。', 'error');
}

function setBusy(button, busy, label = '处理中…') {
  if (!button) return;
  if (busy) { button.dataset.label = button.textContent; button.textContent = label; button.disabled = true; }
  else { button.textContent = button.dataset.label || button.textContent; button.disabled = false; }
}

function hasDirtyChanges() {
  if (!state.dirty) return false;
  return !window.confirm('本页有未保存的校对修改。离开后会丢失，仍要离开吗？');
}

function clearPolling() {
  if (state.pollTimer) window.clearTimeout(state.pollTimer);
  state.pollTimer = null;
}

function jobSessionMatches(bookId, requestId) {
  return state.book?.id === bookId && bookRequest === requestId;
}

function imageStage() {
  return $('#paper .original-frame');
}

function selectedProvider() {
  const checked = $('#provider-options input[name="provider"]:checked')?.value;
  if (checked) return checked;
  const defaultProvider = state.config?.defaultProvider || 'paddle-aistudio';
  const available = state.config?.providers?.find(p => p.id === defaultProvider && p.available)
    || state.config?.providers?.find(p => p.available);
  return available?.id || defaultProvider;
}

const storedOption = (key, fallback) => { try { const value = localStorage.getItem(key); return value == null ? fallback : value === 'true'; } catch (_) { return fallback; } };
const isAutoProcessAll = () => storedOption('book_html_auto_process_all_v2', true);
const isAutoReadEnabled = () => storedOption('book_html_auto_read', true);
try {
  if (localStorage.getItem('book_html_auto_read_default_v2') !== 'true') {
    localStorage.setItem('book_html_auto_read', 'true');
    localStorage.setItem('book_html_auto_process_all_v2', 'true');
    localStorage.setItem('book_html_auto_read_default_v2', 'true');
  }
} catch (_) {}

async function ensureReadingWindowActive() {
  if (!state.book || readingWindow.active()) return;
  if (!isAutoReadEnabled() || jobSyncError || activeJobs.has(state.job?.status)) return;
  const provider = selectedProvider();
  const providerConfig = state.config?.providers?.find(p => p.id === provider && p.available)
    || state.config?.providers?.find(p => p.available);
  if (!providerConfig) return;
  const form = $('#job-form') ? new FormData($('#job-form')) : null;
  const options = {
    provider: providerConfig.id,
    layout: form?.get('layout') || 'auto',
    splitSpreads: form?.get('splitSpreads') === 'on',
    assist: $('#qwen-assist')?.checked && !$('#qwen-assist')?.disabled,
    autoProcessAll: isAutoProcessAll()
  };
  await readingWindow.enable(options);
}

function providerChannel(id) {
  return state.config?.ocrChannels?.find(channel => channel.id === id);
}

function providerQuotaSource(provider) {
  return provider?.quotaSource || providerChannel(provider?.id)?.quotaSource || '';
}

function unavailableProviderReason(provider) {
  const reason = provider?.reason || '当前不可用，请检查服务端配置。';
  return `${reason} 可在顶栏“工具配置”中检查凭据；保存配置不等于验证云端连通或额度。`;
}

function providerUsage(provider) {
  const quotaSource = providerQuotaSource(provider);
  return quotaSource
    ? `额度来源：${quotaSource}。这里只标明认证账户通道，不代表可用余额或免费额度。`
    : '实际用量由该云服务账户结算。';
}

function renderProviders() {
  const wrap = $('#provider-options');
  const previous = selectedProvider();
  wrap.replaceChildren();
  const providers = (state.config?.providers || []).filter(provider => ['paddle-aistudio', 'ppocr'].includes(provider.id));
  providers.forEach(provider => {
    const label = document.createElement('label');
    label.className = `radio${provider.available ? '' : ' disabled'}`;
    const input = document.createElement('input');
    input.type = 'radio'; input.name = 'provider'; input.value = provider.id; input.disabled = !provider.available;
    const text = document.createElement('span'); text.textContent = provider.label;
    const channel = providerChannel(provider.id);
    const detail = document.createElement('small');
    if (!provider.available) detail.textContent = unavailableProviderReason(provider);
    else if (channel || providerQuotaSource(provider)) {
      const model = channel?.model ? `${channel.model}；` : '';
      detail.textContent = `${model}${providerUsage(provider)}`;
    }
    if (detail.textContent) text.append(detail);
    label.append(input, text); wrap.append(label);
  });
  const defaultProvider = state.config?.defaultProvider || 'paddle-aistudio';
  const available = [...wrap.querySelectorAll('input:not(:disabled)')];
  const preferred = available.find(input => input.value === previous) || available.find(input => input.value === defaultProvider);
  if (preferred) preferred.checked = true;
  updateProviderNote();
}

function updateProviderNote() {
  const id = selectedProvider();
  const provider = state.config?.providers?.find(item => item.id === id);
  if (!provider) {
    const expected = state.config?.defaultProvider || 'paddle-aistudio';
    $('#provider-note').textContent = `默认识别方式 ${expected} 当前不可用，请检查服务端配置或主动选择其他方式；不会静默改用旧提供商。`;
    updateAssistAvailability();
    updateSplitSpreadsAvailability();
    return;
  }
  $('#provider-note').textContent = `${provider.label}会把所选页面图片发送给对应云服务。${providerUsage(provider)} 凭据只由服务端保存；是否尝试另一通道由“工具配置”的回退开关决定。已有 READY 页不会自动重跑；重跑需勾选覆盖并再次确认。`;
  updateAssistAvailability();
  updateSplitSpreadsAvailability();
}

function updateAssistAvailability() {
  const input = $('#qwen-assist');
  const assist = state.config?.qwenAssist;
  const cloudPrimary = Boolean(selectedProvider());
  const available = Boolean(assist?.configured && cloudPrimary);
  if (assistPreference === undefined) assistPreference = Boolean(assist?.assistEnabled);
  input.disabled = !available;
  input.checked = available && Boolean(assistPreference);
  $('#qwen-assist-label').classList.toggle('disabled', !available);
  const model = assist?.model || 'Qwen3.8-Max';
  if (!assist?.configured) $('#qwen-assist-note').textContent = '结构辅助未配置；请从顶栏“工具配置”填写百炼凭据并开启 Qwen。';
  else if (!cloudPrimary) $('#qwen-assist-note').textContent = '本地识别模式下不调用云端结构辅助。';
  else $('#qwen-assist-note').textContent = `${model} 会额外读取完整页面和 OCR 来源块，只给出顺序、分类及疑点建议，不自动覆盖原文，并产生账户用量。`;
}

function updateSplitSpreadsAvailability() {
  const input = $('#split-spreads');
  const provider = state.config?.providers?.find(item => item.id === selectedProvider());
  const paddle = ['paddle', 'paddle-aistudio', 'ppocr'].includes(provider?.id);
  input.disabled = false;
  input.checked = splitSpreadsPreference;
  $('#split-spreads-label').classList.remove('disabled');
  $('#split-spreads-note').textContent = paddle
    ? `${provider.label}开启后会先拆分跨页扫描再分别识别；关闭则保留整页原生布局。`
    : '仅用于支持分区识别的提供商。';
}

function renderBooks() {
  const select = $('#book-select');
  const previous = state.book?.id || '';
  select.replaceChildren(new Option('选择书籍', ''));
  state.books.filter(book => !book.archived).forEach(book => select.append(new Option(`${book.title}（${book.totalPages} 页）`, book.id)));
  select.value = previous;
  $('#empty-state p').textContent = state.books.some(book => !book.archived)
    ? '从上方书架选择已有书籍，或导入新的 PDF。先浏览原稿，再在“处理设置”中选择少量页面识别。'
    : '导入后先查看原稿，再挑选少量页面识别。没有识别结果的页面仍会诚实地显示原图。';
}

function importStatus(message, canRefresh = false) {
  $('#import-status').hidden = !message;
  $('#import-message').textContent = message;
  $('#refresh-library').hidden = !canRefresh;
}

function summaryFor(n) {
  return state.summaries.find(item => item.pageNumber === n);
}

function headingText(block) {
  if (!block) return '';
  const useSimplified = typeof block.simplified === 'string' && block.simplified.length > 0;
  const text = useSimplified ? block.simplified : (block.original || '');
  const issues = (block.issues || []).map(issue => ({
    issue,
    start: Number(useSimplified ? (issue.simplifiedStart ?? issue.start) : issue.start),
    end: Number(useSimplified ? (issue.simplifiedEnd ?? issue.end) : issue.end)
  })).filter(({ start, end }) => Number.isInteger(start) && Number.isInteger(end) && start >= 0 && end > start && end <= text.length)
    .sort((a, b) => a.start - b.start || a.end - b.end);
  let cursor = 0;
  const parts = [];
  for (const { issue, start, end } of issues) {
    if (start < cursor) continue;
    parts.push(text.slice(cursor, start));
    const source = text.slice(start, end);
    parts.push(globalThis.BookReadingLayout.displayIssueText(issue, source, 'simplified'));
    cursor = end;
  }
  parts.push(text.slice(cursor));
  return parts.join('');
}

function pageTitle(page) {
  const heading = [...(page?.blocks || [])].filter(block => block.type === 'heading')
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))[0];
  return headingText(heading).trim() || `第 ${page?.pageNumber || state.currentPage} 页`;
}

// U1：真实处理状态。不再用计时函数模拟百分比；未知剩余工作量时只显示阶段。
const convertingState = {
  pageNumber: null,
  completedAt: 0,
  completedPage: null,
  isReprocessing: false,
  wasReprocessing: false
};

function resetConvertingState(newPage = null) {
  convertingState.pageNumber = newPage;
  convertingState.completedAt = 0;
  convertingState.completedPage = null;
  convertingState.isReprocessing = false;
  convertingState.wasReprocessing = false;
}

function isCurrentPageConverting() {
  if (!state.book || !state.currentPage) return false;
  const pageNum = state.currentPage;
  if (state.job && activeJobs.has(state.job.status) && state.job.currentPage === pageNum) {
    return true;
  }
  if (readingWindow?.active()) {
    if (readingSnapshot?.processingPages?.includes(pageNum)) return true;
    if (readingSnapshot?.processingPage === pageNum) return true;
    if ((readingSnapshot?.status === 'PROCESSING' || readingSnapshot?.status === 'SETTLING') &&
        readingSnapshot?.centerPage === pageNum && (!state.page || state.page.status !== 'READY')) {
      return true;
    }
  }
  if (state.page && state.page.status === 'PROCESSING' && Number(state.page.pageNumber) === pageNum) {
    return true;
  }
  return false;
}

function renderJobHeading() {
  const headingEl = $('#job-heading');
  const titleContainer = $('#job-panel .job-title');
  const retryHeaderBtn = $('#retry-page-header');
  const reloadHeaderBtn = $('#reload-page-header');
  const readerReloadBtn = $('#reader-reload-page');
  if (!headingEl) return;
  if (!state.book) {
    headingEl.textContent = '开始转换';
    titleContainer?.classList.remove('is-converting');
    retryHeaderBtn?.setAttribute('hidden', '');
    reloadHeaderBtn?.setAttribute('hidden', '');
    readerReloadBtn?.setAttribute('hidden', '');
    return;
  }
  const pageNum = state.currentPage;
  const converting = isCurrentPageConverting();
  const isFailed = state.page?.status === 'FAILED' && !converting;
  const isReady = state.page?.status === 'READY' && !converting;
  const isReprocessing = Boolean(state.reprocessingPage === pageNum || convertingState.isReprocessing);

  if (retryHeaderBtn) {
    if (isFailed) retryHeaderBtn.removeAttribute('hidden');
    else retryHeaderBtn.setAttribute('hidden', '');
  }
  if (reloadHeaderBtn) {
    if (converting && isReprocessing) {
      reloadHeaderBtn.removeAttribute('hidden');
      reloadHeaderBtn.disabled = true;
      reloadHeaderBtn.innerHTML = '<span class="reprocessing-inline-spinner"></span> 正在二次处理…';
    } else if (isReady) {
      reloadHeaderBtn.removeAttribute('hidden');
      reloadHeaderBtn.disabled = false;
      reloadHeaderBtn.textContent = '重新处理本页';
    } else {
      reloadHeaderBtn.setAttribute('hidden', '');
      reloadHeaderBtn.disabled = false;
      reloadHeaderBtn.textContent = '重新处理本页';
    }
  }
  if (readerReloadBtn) {
    if (converting && isReprocessing) {
      readerReloadBtn.removeAttribute('hidden');
      readerReloadBtn.disabled = true;
      readerReloadBtn.innerHTML = '<span class="reprocessing-inline-spinner"></span> 正在二次处理…';
    } else if (isReady) {
      readerReloadBtn.removeAttribute('hidden');
      readerReloadBtn.disabled = false;
      readerReloadBtn.textContent = '重新处理本页';
    } else {
      readerReloadBtn.setAttribute('hidden', '');
      readerReloadBtn.disabled = false;
      readerReloadBtn.textContent = '重新处理本页';
    }
  }

  if (convertingState.completedAt > 0 && convertingState.completedPage === pageNum &&
      Date.now() - convertingState.completedAt < 1200) {
    headingEl.textContent = convertingState.wasReprocessing
      ? `第 ${pageNum} 页二次处理完成`
      : `第 ${pageNum} 页转化完成`;
    titleContainer?.classList.add('is-converting');
    return;
  }

  if (converting) {
    if (convertingState.pageNumber !== pageNum) {
      convertingState.pageNumber = pageNum;
      convertingState.completedAt = 0;
      convertingState.completedPage = null;
    }
    // U1：未知剩余工作量时只显示真实阶段，不显示百分比。后端阶段信息由 U4 接入。
    headingEl.textContent = isReprocessing ? `正在二次处理第 ${pageNum} 页` : `正在识别第 ${pageNum} 页`;
    titleContainer?.classList.add('is-converting');
    return;
  }

  titleContainer?.classList.remove('is-converting');

  if (state.job && state.job.status !== 'IDLE') {
    const labels = { QUEUED: '等待处理', RUNNING: '正在转换', CANCELLING: '正在取消', COMPLETED: '处理完成', COMPLETED_WITH_ERRORS: '完成，部分页面失败', CANCELLED: '任务已取消', INTERRUPTED: '任务因服务重启而中断', FAILED: '任务失败' };
    const pct = state.job.total ? Math.round(((state.job.completed || 0) / state.job.total) * 100) : 0;
    if (activeJobs.has(state.job.status)) {
      headingEl.textContent = `正在转换 · ${pct}%${state.job.currentPage ? ` (当前第 ${state.job.currentPage} 页)` : ''}`;
    } else {
      headingEl.textContent = labels[state.job.status] || '本书处理';
    }
    return;
  }

  if (readingWindow?.active()) {
    if (state.page?.status === 'READY') {
      headingEl.textContent = `第 ${pageNum} 页已就绪 · 随读中`;
    } else if (state.page?.status === 'FAILED') {
      headingEl.textContent = `第 ${pageNum} 页转化失败`;
    } else {
      headingEl.textContent = '随读识别中';
    }
    return;
  }

  if (isFailed) {
    headingEl.textContent = `第 ${pageNum} 页转化失败`;
    return;
  }

  headingEl.textContent = '本书处理';
}

async function retryCurrentPage() {
  if (!state.book || !state.currentPage) return;
  const pageNum = state.currentPage;
  state.page = { ...state.page, status: 'PROCESSING', error: null };
  state.pageCache.delete(pageNum);
  renderCurrent();
  renderJobHeading();
  if (readingWindow?.active()) {
    await readingWindow.retryCurrentPage();
  } else {
    const provider = selectedProvider();
    const providerConfig = state.config?.providers?.find(item => item.id === provider);
    if (!providerConfig?.available) { toast('请在处理设置中选择可用的云识别通道。', 'error'); return; }
    try {
      await api.startJob(state.book.id, {
        pages: String(pageNum),
        provider,
        layout: 'auto',
        splitSpreads: false,
        force: true,
        assist: $('#qwen-assist')?.checked && !$('#qwen-assist').disabled
      });
      schedulePoll();
    } catch (err) {
      showError(err);
    }
  }
}

async function reloadCurrentPage() {
  if (!state.book || !state.currentPage) return;
  const pageNum = state.currentPage;
  state.reprocessingPage = pageNum;
  convertingState.isReprocessing = true;
  convertingState.pageNumber = pageNum;
  convertingState.completedAt = 0;
  convertingState.completedPage = null;

  // 保留已有 blocks 供视觉平滑过渡，不闪烁、不退回原图
  state.page = {
    ...state.page,
    status: 'PROCESSING',
    isReprocessing: true,
    error: null
  };
  if (state.pageCache.has(pageNum)) {
    const cached = state.pageCache.get(pageNum);
    state.pageCache.set(pageNum, { ...cached, status: 'PROCESSING', isReprocessing: true });
  }

  renderCurrent();
  renderJobHeading();

  if (readingWindow?.active()) {
    await readingWindow.retryCurrentPage();
  } else {
    const provider = selectedProvider();
    const providerConfig = state.config?.providers?.find(item => item.id === provider);
    if (!providerConfig?.available) { toast('请在处理设置中选择可用的云识别通道。', 'error'); return; }
    try {
      await api.startJob(state.book.id, {
        pages: String(pageNum),
        provider,
        layout: 'auto',
        splitSpreads: false,
        force: true,
        assist: $('#qwen-assist')?.checked && !$('#qwen-assist').disabled
      });
      schedulePoll();
    } catch (err) {
      showError(err);
    }
  }
}

function renderBookMeta() {
  if (!state.book) {
    renderJobHeading();
    $('#book-summary').textContent = '先导入一本 PDF，原稿会始终保留。';
    $('#export-button').disabled = true;
    $('#usage-open').disabled = true;
    $('#job-form button[type="submit"]').disabled = true;
    renderReadingProgress();
    return;
  }
  const b = state.book;
  const pct = b.totalPages ? Math.round(((b.processedPages || 0) / b.totalPages) * 100) : 0;
  $('#book-summary').textContent = `已处理 ${b.processedPages || 0} / ${b.totalPages} 页 (${pct}%) · 已校对 ${b.reviewedPages || 0} 页`;
  $('#book-select').title = b.title;
  $('#export-button').disabled = false;
  $('#usage-open').disabled = false;
  $('#job-form button[type="submit"]').disabled = readingWindow.active();
  $('#page-jump').max = b.totalPages;
  $('#total-pages').textContent = `/ ${b.totalPages} 页`;
  renderReadingProgress();
  renderJobHeading();
}

function renderToc() {
  const list = $('#toc-list'); list.replaceChildren();
  const rawEntries = Array.isArray(state.outline) ? state.outline : [];
  const entries = rawEntries.flatMap(entry => {
    const cachedBlocks = Number(entry.pageNumber) === state.currentPage && state.page ? state.blocks : state.pageCache.get(Number(entry.pageNumber))?.blocks;
    if (!Array.isArray(cachedBlocks)) return [{ ...entry, displayTitle: String(entry.title || '').trim() || '未命名标题', cachedHeading: null }];
    const cachedHeading = cachedBlocks.find(block => block.id === entry.blockId);
    if (!cachedHeading || cachedHeading.type !== 'heading') return [];
    const displayTitle = headingText(cachedHeading).trim();
    return displayTitle ? [{ ...entry, displayTitle, cachedHeading }] : [];
  });
  if (state.outlineStatus === 'loading' && !entries.length) {
    const message = document.createElement('p'); message.className = 'side-empty'; message.textContent = '正在读取正文标题…'; list.append(message);
  } else if (state.outlineStatus === 'error') {
    const message = document.createElement('p'); message.className = 'side-empty';
    message.textContent = entries.length ? '正文标题刷新失败，以下内容可能不是最新版本。' : '正文标题读取失败，仍可使用页码和底部进度条翻页。';
    list.append(message);
  } else if (state.outlineStatus === 'ready' && !entries.length) {
    const message = document.createElement('p'); message.className = 'side-empty'; message.textContent = '已处理页面中还没有识别到正文标题。'; list.append(message);
  }
  const active = readerNavigation.activeIndex(entries, state.currentPage, state.activeOutlineBlockId);
  entries.forEach((entry, index) => {
    const button = document.createElement('button'); button.type = 'button';
    button.className = `toc-entry heading-entry${index === active ? ' active' : ''}`;
    button.style.setProperty('--level', Math.max(0, Number(entry.level || 1) - 1));
    if (index === active) button.setAttribute('aria-current', 'location');
    const text = document.createElement('span'); text.textContent = entry.displayTitle;
    const page = document.createElement('small'); page.textContent = entry.pageNumber;
    if ((entry.cachedHeading?.issues || []).some(issue => !issue.resolved)) button.title = '标题含未确认候选，点击可在正文查看原稿依据';
    button.append(text, page); button.addEventListener('click', () => goToOutlineEntry(entry)); list.append(button);
  });
  $('#toc-count').textContent = state.outlineStatus === 'loading' ? '读取中' : state.outlineStatus === 'error' ? '读取失败' : `${entries.length} 个标题`;
}

function renderReadingProgress(previewPage) {
  const info = readerNavigation.progress(previewPage ?? state.currentPage, state.book?.totalPages);
  const range = $('#reading-progress-range');
  range.min = '1'; range.max = String(Math.max(1, info.total)); range.value = String(info.page || 1);
  range.disabled = info.total <= 1;
  const text = info.total ? `${info.page} / ${info.total} 页 · ${Math.round(info.percent)}%` : '0 / 0 页';
  $('#reading-progress-output').textContent = text;
  range.setAttribute('aria-valuetext', info.total ? `第 ${info.page} 页，共 ${info.total} 页，${Math.round(info.percent)}%` : '未选择书籍');
}

async function refreshOutline(bookId = state.book?.id) {
  if (!bookId) return false;
  const requestId = ++outlineRequest;
  state.outlineStatus = 'loading'; renderToc();
  try {
    const outline = await api.outline(bookId);
    if (requestId !== outlineRequest || state.book?.id !== bookId) return false;
    state.outline = Array.isArray(outline) ? outline : [];
    state.outlineStatus = 'ready'; renderToc();
    return true;
  } catch (_) {
    if (requestId !== outlineRequest || state.book?.id !== bookId) return false;
    state.outlineStatus = 'error'; renderToc();
    return false;
  }
}

async function goToOutlineEntry(entry) {
  const pageNumber = Number(entry?.pageNumber);
  const navigated = await goToPage(pageNumber, { outlineBlockId: entry?.blockId });
  if (!navigated || state.currentPage !== pageNumber || !state.page) return;
  state.activeOutlineBlockId = entry?.blockId || null;
  if (state.view !== 'reading') state.view = 'reading';
  renderCurrent(false); saveReadingPosition(); closeDrawers();
  requestAnimationFrame(() => requestAnimationFrame(() => {
    const target = [...$('#paper').querySelectorAll('[data-block-id]')].find(node => node.dataset.blockId === entry?.blockId);
    if (target) {
      target.tabIndex = -1;
      target.scrollIntoView({ block: 'center', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
      target.focus({ preventScroll: true });
    }
    else $('#reader').scrollTop = 0;
  }));
}

async function commitPageInput() {
  const target = Number($('#page-jump').value);
  if (pendingPageTarget === target) return;
  const intent = ++pageInputIntent;
  pendingPageTarget = target;
  try { await goToPage(target); }
  finally { if (intent === pageInputIntent) pendingPageTarget = null; }
}

function renderBookmarks() {
  const list = $('#bookmark-list'); list.replaceChildren();
  if (!state.book) return;
  const pages = getBookmarks(state.book.id);
  if (!pages.length) { const p = document.createElement('p'); p.className = 'side-empty'; p.textContent = '还没有书签。阅读时可在页首加入。'; list.append(p); return; }
  pages.forEach(n => {
    const button = document.createElement('button'); button.type = 'button'; button.className = 'toc-entry'; button.textContent = `第 ${n} 页`; button.addEventListener('click', () => goToPage(n)); list.append(button);
  });
}

function renderBookmarkButton() {
  const active = state.book && getBookmarks(state.book.id).includes(state.currentPage);
  $('#bookmark-button').setAttribute('aria-pressed', String(Boolean(active)));
  $('#bookmark-button').textContent = active ? '已加入书签' : '加入书签';
}

function renderQuality() {
  const job = state.job;
  const isJobRunning = job && activeJobs.has(job.status);
  const isCurrentProcessing = (state.page?.status === 'PROCESSING') || (isJobRunning && job.currentPage === state.currentPage);

  const badge = $('#quality-badge');
  if (isCurrentProcessing) {
    // U1：当前页未知剩余工作量，只显示真实阶段，不显示百分比，不用脉冲点。
    badge.className = 'quality-badge processing';
    badge.textContent = state.page?.isReprocessing ? '正在二次处理' : '正在识别本页';
    $('#quality-detail').textContent = '';
    const diag = $('#quality-diagnostics');
    if (diag) {
      diag.textContent = job?.total
        ? `后台任务：已结束 ${job.completed || 0} / ${job.total} 页。当前页阶段未知，不显示百分比。`
        : '后台任务进行中，当前页阶段未知，不显示百分比。';
      const toggle = $('#quality-diagnostics-toggle');
      const open = toggle?.dataset.open === 'true';
      diag.hidden = !open;
      if (toggle) {
        toggle.hidden = false;
        toggle.setAttribute('aria-expanded', String(open));
      }
    }
    return;
  }

  const quality = qualityOf(state.page);
  badge.className = `quality-badge ${quality.tone}`;
  badge.textContent = quality.label;

  // U1/U7-Style：默认阅读不拼接长技术细节；诊断区默认收起，按“详情”按需查看。
  $('#quality-detail').textContent = '';
  const diag = $('#quality-diagnostics');
  const diagToggle = $('#quality-diagnostics-toggle');
  if (diag) {
    let text = quality.detail || '';
    if (isJobRunning && job?.currentPage && job.currentPage !== state.currentPage && job?.total) {
      text += `${text ? '；' : ''}后台任务：已结束 ${job.completed || 0} / ${job.total} 页，当前处理第 ${job.currentPage} 页`;
    }
    diag.textContent = text;
    const open = diagToggle?.dataset.open === 'true';
    diag.hidden = !text || !open;
    if (diagToggle) {
      diagToggle.hidden = !text;
      diagToggle.setAttribute('aria-expanded', String(open && Boolean(text)));
    }
  }
}

function renderPageMessage(rawMessage) {
  const node = $('#page-message');
  if (!node) return;
  const job = state.job;
  const isJobRunning = job && activeJobs.has(job.status);
  const isCurrentProcessing = (state.page?.status === 'PROCESSING') || (isJobRunning && job.currentPage === state.currentPage);

  if (isCurrentProcessing) {
    node.hidden = false;
    node.className = 'page-message is-processing';
    node.replaceChildren();

    const card = document.createElement('div');
    card.className = 'page-progress-card';

    const head = document.createElement('div');
    head.className = 'page-progress-head';
    const title = document.createElement('div');
    title.className = 'page-progress-title';
    title.textContent = state.page?.isReprocessing
      ? `第 ${state.currentPage} 页正在二次处理，保留当前内容…`
      : `第 ${state.currentPage} 页正在识别，原稿可以继续阅读`;
    head.append(title);

    const track = document.createElement('div');
    track.className = 'page-progress-track is-indeterminate';
    track.setAttribute('role', 'progressbar');
    track.setAttribute('aria-label', '正在识别本页，剩余工作量未知');
    const fill = document.createElement('div');
    fill.className = 'page-progress-fill is-indeterminate';
    track.append(fill);

    const meta = document.createElement('div');
    meta.className = 'page-progress-meta';
    const countSpan = document.createElement('span');
    countSpan.textContent = job?.total
      ? `全书任务已结束 ${job.completed || 0} / ${job.total} 页 · 当前页仍在识别`
      : '正在进行文字与版面识别…';
    const hintSpan = document.createElement('span');
    hintSpan.className = 'page-progress-hint';
    hintSpan.textContent = '识别完成后呈现排版正文；未知进度不显示百分比。';
    meta.append(countSpan, hintSpan);

    card.append(head, track, meta);
    node.append(card);
    return;
  }

  node.className = 'page-message';
  const msg = rawMessage !== undefined ? rawMessage : (state.page ? statusMessage(state.page) : '');
  node.hidden = !msg;
  node.textContent = msg || '';
}

function renderReview() {
  const available = Boolean(state.page);
  $('#review-empty').hidden = available;
  $('#review-content').hidden = !available;
  updateSaveStatus();
  if (!available) {
    unmountIssueWorkbench($('#issue-workbench'));
    return;
  }
  const isReviewVisible = state.view === 'original' || $('#review-panel')?.classList.contains('open');
  if (!isReviewVisible) {
    unmountIssueWorkbench($('#issue-workbench'));
    return;
  }
  renderConflictBar();
  const reviewImage = $('#review-original');
  const imageUrl = api.pageImage(state.book.id, state.currentPage, 900);
  if (reviewImage.getAttribute('src') !== imageUrl) reviewImage.src = imageUrl;
  const refs = issueReferences(state.blocks);
  if (!refs.some(ref => ref.issue.id === state.selectedIssueId)) {
    state.selectedIssueId = (refs.find(ref => !ref.synthetic && !ref.issue.resolved) || refs[0])?.issue.id || null;
  }
  renderIssueWorkbench($('#issue-workbench'), state.blocks, {
    selectedIssueId: state.selectedIssueId,
    page: state.page,
    loadIssueEvidence: loadIssueEvidence,
    imageUrl,
    pageWidth: state.page.width,
    pageHeight: state.page.height,
    onSelect(blockId, issueId) {
      const panel = $('#review-panel');
      const scrollTop = panel.scrollTop;
      const action = document.activeElement?.textContent;
      state.selectedBlockId = blockId; state.selectedIssueId = issueId; renderReview(); syncOverlays();
      // 疑点导航留在疑点工作台，不跳到下方整块编辑表单。
      panel.scrollTop = scrollTop;
      const nextFocus = [...$('#issue-workbench').querySelectorAll('header button')]
        .find(button => !button.disabled && button.textContent === action);
      nextFocus?.focus({ preventScroll: true });
    },
    onUpdate(block, issue, patch) {
      Object.assign(issue, patch); state.selectedBlockId = block.id; state.selectedIssueId = issue.id; markDirty(); renderCurrent();
    },
    onDirty() { markDirty(); }
  });
  $('#mark-reviewed').checked = Boolean(state.reviewedDraft);
  $('#save-page').disabled = !state.dirty || Boolean(state.conflict) || Boolean(currentSaveInFlight());
  renderDecisionSection();
  renderPageStructure();
  renderEditor($('#review-list'), state.blocks, {
    selectedId: state.selectedBlockId,
    uncertainOnly: $('#uncertain-only').checked,
    onSelect(id) { state.selectedBlockId = id; renderReview(); syncOverlays(); scrollToSelectedBlock(id); },
    onChange() { markDirty(); state.view === 'original' ? syncOverlays() : renderCurrent(false); },
    onDelete(block) {
      if (!window.confirm(`确定删除第 ${block.order ?? '—'} 个内容块吗？保存整页后生效。`)) return;
      state.blocks = state.blocks.filter(item => item.id !== block.id);
      if (state.selectedBlockId === block.id) { state.selectedBlockId = null; state.selectedIssueId = null; }
      markDirty(); renderCurrent();
    }
  });
}

// U3：校对模式“页面结构”。自动判断收起的块可查看并撤销；修改只影响展示与
// 目录，不改原文，不把整页标为人工校对。默认作用范围为本块。
function structureRoleLabel(role) {
  return { RUNNING_HEADER: '疑似书眉', RUNNING_FOOTER: '疑似页脚', BOOK_TITLE: '书名',
    PRINTED_TOC_ENTRY: '目录页条目', PAGE_NUMBER: '页码', CHAPTER_HEADING: '章节',
    SECTION_HEADING: '小节', VISUAL: '图/表/公式', CAPTION: '图注', DECORATION: '装饰',
    BODY: '正文', FOOTNOTE: '脚注', UNKNOWN: '未确定' }[role] || role || '未确定';
}

function renderPageStructure() {
  const host = $('#page-structure-list');
  const section = $('#page-structure');
  if (!host || !section) return;
  host.replaceChildren();
  const views = state.page?.presentation?.blocks || [];
  const blocks = new Map((state.blocks || []).map(b => [b.id, b]));
  const interesting = views.filter(view => {
    if (!view || !view.blockId) return false;
    if (view.showInReading === false) return true;
    const block = blocks.get(view.blockId);
    return block?.type === 'heading' && view.includeInOutline === false;
  });
  $('#page-structure-count').textContent = interesting.length ? `${interesting.length} 项` : '';
  section.hidden = interesting.length === 0 && !state.page;
  if (!interesting.length) {
    const empty = document.createElement('p');
    empty.className = 'side-empty';
    empty.textContent = '本页没有被收起的结构，可直接阅读。';
    host.append(empty);
    return;
  }
  for (const view of interesting) {
    const block = blocks.get(view.blockId);
    const text = (block?.simplified || block?.original || '').slice(0, 60);
    const item = document.createElement('div');
    item.className = 'structure-item';
    const head = document.createElement('div');
    head.className = 'structure-item-head';
    const label = document.createElement('strong');
    label.textContent = text || view.blockId;
    const role = document.createElement('span');
    role.className = 'structure-role';
    role.textContent = `${structureRoleLabel(view.role)}${view.evidenceLevel === 'MANUAL' ? ' · 人工' : ''}`;
    head.append(label, role);
    const reasons = document.createElement('p');
    reasons.className = 'structure-reasons';
    reasons.textContent = `依据：${(view.reasonCodes || []).join('、') || '自动判断'}`;
    const actions = document.createElement('div');
    actions.className = 'structure-actions';
    const addButton = (caption, action, confirmText) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'button quiet';
      button.textContent = caption;
      button.addEventListener('click', async () => {
        if (confirmText && !window.confirm(confirmText)) return;
        await applyStructureOverride(view, action);
      });
      actions.append(button);
    };
    addButton('加入目录', 'INCLUDE_IN_OUTLINE');
    addButton('不作为目录', 'EXCLUDE_FROM_OUTLINE');
    addButton('标为书眉/页脚', view.role === 'RUNNING_FOOTER' ? 'MARK_RUNNING_FOOTER' : 'MARK_RUNNING_HEADER');
    addButton('恢复自动判断', 'CLEAR_OVERRIDE');
    item.append(head, reasons, actions);
    host.append(item);
  }
}

async function applyStructureOverride(view, action) {
  if (!state.book || !state.page) return;
  const scope = { id: state.book.id, n: state.currentPage, epoch: state.editorEpoch,
    revision: state.page.revision, bookGeneration: bookRequest, editVersion };
  const generation = ++structureGeneration;
  const sameBook = () => state.book?.id === scope.id && bookRequest === scope.bookGeneration;
  const samePage = () => sameBook() && state.currentPage === scope.n && state.editorEpoch === scope.epoch;
  try {
    await api.applyPresentationOverride(scope.id, scope.n, {
      expectedRevision: scope.revision, blockId: view.blockId,
      sourceHash: view.sourceHash, action, scope: 'BLOCK'
    });
    if (!sameBook()) return;
    state.pageCache.delete(scope.n);
    if (samePage() && generation === structureGeneration) {
      if (currentPageProtected() || editVersion !== scope.editVersion || window.getSelection()?.toString()) {
        deferredReady = { bookId: scope.id, page: scope.n, revision: null };
        renderReadingWindowStatus();
      } else await goToPage(scope.n, { force: true, preserveScroll: true });
    }
    if (sameBook()) await refreshOutline(scope.id);
  } catch (error) {
    if (samePage() && generation === structureGeneration) showError(error);
  }
}

// J08：候选比较面板（独立作用域，不复用 saveInFlight；建议不改 page，不写 issues）。
const decisionPanel = createDecisionPanel({
  getSession: () => state.book
    ? { bookId: state.book.id, page: state.currentPage, epoch: state.editorEpoch } : null,
  // 面板只查询草稿状态；不可复用会弹出“离开页面”确认框的导航函数。
  hasDirty: () => Boolean(state.dirty),
  onAccepted: (result) => {
    // 接受已推进服务端版本：失效本页缓存；若有未保存草稿则保留草稿并同步版本，否则重载页面
    state.pageCache.delete(state.currentPage);
    if (state.dirty) {
      if (state.page) state.page.revision = result?.pageRevision || (state.page.revision + 1);
      const targetIssue = (state.blocks || []).flatMap(b => b.issues || []).find(i => i && i.id === state.selectedIssueId);
      if (targetIssue) targetIssue.resolved = true;
      renderReview();
      renderDecisionSection();
    } else {
      goToPage(state.currentPage, { force: true });
    }
  },
  onRecommendation: (issueId, info) => {
    state.assistMap[issueId] = info;
    if (state.evidenceMode === 'assisted') renderCurrent(false);
  },
  showError,
});

function renderDecisionSection() {
  const workbench = $('#issue-workbench');
  if (!workbench) return;
  let host = workbench.querySelector('[data-decision-panel]');
  if (!host) {
    host = document.createElement('section');
    host.className = 'decision-section';
    workbench.querySelector('.issue-edit')?.before(host);
  }
  const block = (state.blocks || []).find(b => b && b.id === state.selectedBlockId);
  const issue = block?.issues?.find(i => i && i.id === state.selectedIssueId);
  if (!block || !issue || issue.resolved) {
    // JR-02-T05：关闭面板仅停止读取，不取消服务器后台任务
    try { decisionPanel.dispose(); } catch (_) {}
    host.replaceChildren();
    host.hidden = true;
    return;
  }
  host.hidden = false;
  decisionPanel.render(host, block, issue);
}

function scrollToSelectedBlock(id) {
  requestAnimationFrame(() => {
    const item = [...$('#review-list').querySelectorAll('[data-block-id]')].find(node => node.dataset.blockId === id);
    item?.scrollIntoView({ block: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  });
}

function markDirty() {
  editVersion++;
  state.dirty = true;
  $('#save-page').disabled = Boolean(state.conflict) || Boolean(currentSaveInFlight());
  updateSaveStatus();
  // J08：草稿变脏即同步决策门禁（轻量，不重绘面板）
  try { decisionPanel.syncDraftGuard(true); } catch (_) { /* 面板未挂载时忽略 */ }
}

function currentSaveInFlight() {
  return state.book && state.saveInFlight?.bookId === state.book.id
    && state.saveInFlight.page === state.currentPage && state.saveInFlight.epoch === state.editorEpoch;
}

function updateSaveStatus() {
  const hasPage = Boolean(state.book && state.page);
  const saving = hasPage && currentSaveInFlight();
  const tone = !hasPage ? '' : state.conflict ? 'conflict' : saving ? 'saving' : state.dirty ? 'dirty' : 'saved';
  const label = { conflict: '冲突', saving: '保存中', dirty: '未保存', saved: '已保存' }[tone] || '';
  const pageStatus = $('#page-save-status');
  pageStatus.hidden = !hasPage;
  pageStatus.textContent = label;
  pageStatus.dataset.state = tone;
  const reviewStatus = $('#review-save-status');
  reviewStatus.textContent = tone === 'dirty' ? '未保存 · 标记解决后仍需保存' : label;
  reviewStatus.dataset.state = tone;
}

async function loadIssueEvidence(issueId) {
  const token = sessionGuard.token;
  const bookId = state.book?.id, pageNumber = state.currentPage, page = state.page, epoch = state.editorEpoch;
  if (!bookId || !page || !issueId) return null;
  const precise = await api.issueMetadata(bookId, pageNumber, issueId);
  if (!sessionGuard.isValid(token) || state.book?.id !== bookId || state.currentPage !== pageNumber || state.page !== page || state.editorEpoch !== epoch) return null;
  if (!(state.blocks || []).some(block => (block.issues || []).some(issue => issue.id === issueId))) return null;
  page.issueImages ||= {};
  page.issueImages[issueId] = precise;
  return precise;
}

// A1-01：编辑会话快照与身份判断。相同页号不代表相同会话。
let saveRequestSeq = 0;
function editSessionMatches(snap) {
  return isSameSession(
    state.book ? { bookId: state.book.id, page: state.currentPage, epoch: state.editorEpoch } : null, snap);
}
// A1-S06：不确定是否落盘的保存，向服务端读回核实。
// saved=读回与提交快照一致；mismatch=不一致；unknown=读回失败。
async function verifyUncertainSave(snapshot) {
  try {
    const remote = await api.page(snapshot.bookId, snapshot.page);
    const same = canonicalJson(remote.blocks) === canonicalJson(snapshot.blocks)
      && Boolean(remote.reviewed) === Boolean(snapshot.reviewed);
    return same ? { outcome: 'saved', remote } : { outcome: 'mismatch', remote };
  } catch (_) {
    return { outcome: 'unknown', remote: null };
  }
}
function downloadJson(filename, payload) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
// A1-01：真冲突常驻比较栏（4 秒 toast 会消失，不能作为冲突载体）。
function renderConflictBar() {
  let bar = $('#conflict-bar');
  const c = state.conflict;
  const show = c && state.book?.id === c.bookId && state.currentPage === c.page;
  if (!show) { bar?.remove(); return; }
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'conflict-bar'; bar.className = 'conflict-bar'; bar.setAttribute('role', 'alert');
    $('#review-content').prepend(bar);
  }
  bar.replaceChildren();
  const title = document.createElement('strong');
  title.textContent = `保存冲突：远端已到版本 ${c.remoteRevision ?? '未知'}，本地基于版本 ${c.baseRevision ?? '未知'}。本地草稿已保留，未写入服务端。`;
  const detail = document.createElement('div'); detail.className = 'conflict-detail';
  detail.textContent = c.remote
    ? `远端：${c.remote.blocks?.length ?? '?'} 个块${c.remote.reviewed ? '（已校对）' : ''}。如需合并，先下载本地草稿，再加载远端并人工重填。`
    : `${c.note || ''} 如需合并，先下载本地草稿，再加载远端并人工重填。`;
  const actions = document.createElement('div'); actions.className = 'conflict-actions';
  const mkButton = (text, onClick) => { const b = document.createElement('button'); b.type = 'button'; b.className = 'button'; b.textContent = text; b.addEventListener('click', onClick); return b; };
  actions.append(
    mkButton('查看远端版本', async () => {
      try {
        const remote = await api.page(c.bookId, c.page);
        if (state.conflict === c) { c.remote = remote; c.remoteRevision = remote?.revision ?? c.remoteRevision; renderConflictBar(); }
      } catch (error) { showError(error); }
    }),
    mkButton('下载本地草稿', () => {
      downloadJson(`草稿-${c.bookId.slice(0, 8)}-第${c.page}页.json`,
        { bookId: c.bookId, page: c.page, baseRevision: c.baseRevision, blocks: state.blocks, reviewed: $('#mark-reviewed').checked });
    }),
    mkButton('放弃本地并加载远端', async () => {
      if (!window.confirm('确定放弃本地草稿并加载远端版本吗？本地未保存的修改将丢失。')) return;
      try {
        const remote = await api.page(c.bookId, c.page);
        if (state.book?.id !== c.bookId || state.currentPage !== c.page) { toast('已不在冲突页面，未切换。'); return; }
        state.page = remote; state.blocks = cloneBlocks(remote.blocks); state.reviewedDraft = Boolean(remote.reviewed);
        state.dirty = false; state.conflict = null; state.pageCache.set(c.page, remote);
        renderCurrent(); toast('已加载远端版本。', 'success');
      } catch (error) { showError(error); }
    })
  );
  bar.append(title, detail, actions);
}

function syncOverlays() {
  const stage = imageStage();
  if (!stage || !state.page) return;
  createOverlay(stage, state.blocks, state.selectedBlockId, {
    canMove: () => !state.drawType,
    onSelect(id) { state.selectedBlockId = id; renderReview(); syncOverlays(); scrollToSelectedBlock(id); },
    onMove(block, finished) { markDirty(); if (finished) renderReview(); }
  });
}

function renderCurrent(full = true) {
  if (!state.page || !state.book) return;
  pageProgress.setPage(state.book.id, state.currentPage, state.page);
  const paper = $('#paper');
  const message = renderPaper(paper, {
    book: state.book,
    page: { ...state.page, blocks: state.blocks, isReprocessing: Boolean(state.reprocessingPage === state.currentPage || state.page?.isReprocessing) },
    blocks: state.blocks,
    isReprocessing: Boolean(state.reprocessingPage === state.currentPage || state.page?.isReprocessing),
    view: state.view,
    script: state.script,
    evidence: { mode: state.evidenceMode, assistMap: state.assistMap },
    fontSize: state.fontSize,
    lineHeight: state.lineHeight,
    onRetry: retryCurrentPage,
    onReload: reloadCurrentPage,
    onIssueSelect(blockId, issueId) {
      state.selectedBlockId = blockId; state.selectedIssueId = issueId; renderReview(); syncOverlays(); openDrawer('review');
      requestAnimationFrame(() => {
        const workbench = $('#issue-workbench');
        const target = workbench.querySelector('.issue-edit textarea') || workbench;
        if (target === workbench) target.tabIndex = -1;
        target.focus({ preventScroll: true });
        target.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
      });
    },
    onAdvertisementLocate(blockId) {
      state.selectedBlockId = blockId;
      state.view = 'original'; renderCurrent(); saveReadingPosition();
      requestAnimationFrame(() => requestAnimationFrame(() => {
        const target = [...$('#paper').querySelectorAll('.edit-overlay')].find(node => node.dataset.blockId === blockId);
        target?.scrollIntoView({ block: 'center', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
        target?.focus({ preventScroll: true });
      }));
    }
  });
  renderPageMessage(message);
  $('#page-jump').value = state.currentPage;
  renderReadingProgress();
  $('#prev-page').disabled = state.currentPage <= 1;
  $('#next-page').disabled = state.currentPage >= state.book.totalPages;
  $('#mobile-page-label').textContent = `${state.book.title} · ${state.currentPage}/${state.book.totalPages}`;
  document.body.classList.toggle('focus-reading', state.focus);
  $('#focus-toggle').textContent = state.focus ? '返回工作台' : '专注阅读';
  $('#focus-toggle').setAttribute('aria-pressed', String(state.focus));
  $$('.view-switch button').forEach(button => button.classList.toggle('active', button.dataset.view === state.view));
  $('#script-toggle').textContent = `显示：${state.script === 'simplified' ? '简体' : '原文'}`;
  syncEvidenceToggle();
  renderQuality(); renderBookmarkButton(); renderToc();
  if (full) renderReview();
  if (state.view === 'original') requestAnimationFrame(syncOverlays);
}

function saveReadingPosition() {
  if (!state.book) return;
  savePreferences(state.book.id, { page: state.currentPage, view: state.view, script: state.script, evidenceMode: state.evidenceMode, fontSize: state.fontSize, lineHeight: state.lineHeight, focus: state.focus, scrollTop: $('#reader').scrollTop });
}

// J08：阅读依据切换（语言脚本与证据状态分离）。默认保真阅读；辅助阅读显式开启并带标记。
function syncEvidenceToggle() {
  const button = $('#evidence-toggle');
  if (!button) return;
  const assisted = state.evidenceMode === 'assisted';
  button.textContent = `阅读依据：${assisted ? '辅助阅读' : '已确认'}`;
  button.setAttribute('aria-pressed', String(assisted));
}

async function goToPage(n, options = {}) {
  if (!state.book || !Number.isInteger(n) || n < 1 || n > state.book.totalPages) { renderReadingProgress(); if (state.book) $('#page-jump').value = state.currentPage; return false; }
  if (options.force && n === state.currentPage && currentPageProtected()) {
    deferredReady = { bookId: state.book.id, page: n, revision: null };
    renderReadingWindowStatus();
    return false;
  }
  if (!options.force && n === state.currentPage && state.page) {
    const bookId = state.book.id;
    const requestId = pageRequest, editorEpoch = state.editorEpoch;
    void api.page(bookId, n).then(fresh => {
      if (state.book?.id === bookId && state.currentPage === n && requestId === pageRequest && editorEpoch === state.editorEpoch &&
          !olderRevision(fresh, state.page) &&
          (fresh.revision !== state.page?.revision || fresh.status !== state.page?.status) && fresh.status === 'READY')
        acceptReadyPage(n, fresh);
    }).catch(error => { if (state.book?.id === bookId && state.currentPage === n) showError(error); });
    state.activeOutlineBlockId = options.outlineBlockId || null; renderToc(); renderReadingProgress(); $('#page-jump').value = state.currentPage; return true;
  }
  if (!options.force && hasDirtyChanges()) { renderReadingProgress(); $('#page-jump').value = state.currentPage; return false; }
  const previousScrollTop = $('#reader').scrollTop;
  const pageChanged = n !== state.currentPage;
  const previous = { currentPage: state.currentPage, page: state.page, blocks: state.blocks, selectedBlockId: state.selectedBlockId,
    selectedIssueId: state.selectedIssueId, reviewedDraft: state.reviewedDraft, dirty: state.dirty,
    activeOutlineBlockId: state.activeOutlineBlockId, editorEpoch: state.editorEpoch, conflict: state.conflict };
  cancelDrawing?.(); cancelDrawing = null; state.drawType = null; $('#draw-hint').hidden = true;
  if (!options.skipSavePosition) saveReadingPosition();
  pageProgress.clear();
  state.currentPage = n; state.page = null; state.blocks = []; state.selectedBlockId = null; state.selectedIssueId = null; state.dirty = false; state.activeOutlineBlockId = options.outlineBlockId || null;
  updateSaveStatus();
  // A1-01：进入新页面即开启新编辑会话，旧保存响应不得回写
  state.editorEpoch++; state.conflict = null;
  // J08：切页换作用域，辅助推荐映射清空，迟到决策响应只能丢弃（关闭面板不等同取消任务）
  state.assistMap = {};
  if (pageChanged) {
    deferredReady = null;
    resetConvertingState(n);
    renderJobHeading();
    if (!readingWindow.active()) {
      renderReadingWindowStatus(null);
      void ensureReadingWindowActive().then(() => {
        if (readingWindow.active()) readingWindow.navigated();
      });
    } else {
      readingWindow.navigated();
    }
  } else if (!readingWindow.active()) {
    void ensureReadingWindowActive().then(() => {
      if (!readingWindow.active()) readingWindow.prefetch();
    });
  }
  const requestId = ++pageRequest, bookId = state.book.id;
  const sessionToken = sessionGuard.setSession(bookId, n);
  // 阶段2：取消上一次未完成的正文请求，后端仍以自身预算为准继续或终止解码
  pageFetchController?.abort();
  pageFetchController = new AbortController();
  const fetchSignal = pageFetchController.signal;
  renderReview(); renderToc(); renderReadingProgress(n); $('#page-jump').value = n;
  $('#paper').replaceChildren();
  const loading = document.createElement('p'); loading.className = 'paper-loading'; loading.textContent = `正在读取第 ${n} 页…`; $('#paper').append(loading);
  try {
    const cached = state.pageCache.get(n);
    const page = cached || await api.page(bookId, n, fetchSignal);
    if (requestId !== pageRequest || state.book?.id !== bookId || !sessionGuard.isValid(sessionToken)) return;
    state.pageCache.set(n, page); state.page = page; state.blocks = cloneBlocks(page.blocks); state.reviewedDraft = Boolean(page.reviewed); renderCurrent();
    renderJobHeading();
    const position = options.restoreScroll ? Number(options.scrollTop || 0) : options.preserveScroll ? previousScrollTop : 0;
    requestAnimationFrame(() => { $('#reader').scrollTop = position; });
    closeDrawers();
    // 缓存先供即时阅读，但每次实际进入页面都以本地 JSON 重新核对；
    // 旧窗口单页完成后即使不在新窗口状态中，也不会永久停留在 PENDING 原稿。
    if (cached) void api.page(bookId, n, fetchSignal).then(fresh => {
      if (requestId !== pageRequest || state.book?.id !== bookId || state.currentPage !== n || !sessionGuard.isValid(sessionToken)) return;
      if (olderRevision(fresh, state.page) || olderRevision(fresh, state.pageCache.get(n)) ||
          (fresh.revision === cached.revision && fresh.status === cached.status)) return;
      if (fresh.status === 'READY') acceptReadyPage(n, fresh);
      else if (!currentPageProtected()) state.pageCache.set(n, fresh);
    }).catch(error => {
      if (error?.name !== 'StaleRequest' && requestId === pageRequest && state.book?.id === bookId && sessionGuard.isValid(sessionToken)) showError(error);
    });
    return true;
  } catch (error) {
    // 阶段2：被更快翻页取代的请求静默丢弃，不恢复旧页、不报错
    if (error?.name === 'StaleRequest' || requestId !== pageRequest || !sessionGuard.isValid(sessionToken)) return false;
    Object.assign(state, previous);
    if (pageChanged) readingWindow.navigated();
    if (state.page) { renderCurrent(); requestAnimationFrame(() => { $('#reader').scrollTop = previousScrollTop; }); }
    else {
      renderReadingProgress(); $('#page-jump').value = state.currentPage; $('#paper').replaceChildren();
      const failed = document.createElement('p'); failed.className = 'paper-loading'; failed.textContent = '页面读取失败，请用页码或底部进度条重试。'; $('#paper').append(failed);
    }
    showError(error);
    return false;
  }
}

async function selectBook(id) {
  if (hasDirtyChanges()) { $('#book-select').value = state.book?.id || ''; return; }
  ++searchGeneration; searchController?.abort(); searchController = null;
  ++structureGeneration;
  $('#search-status').textContent = ''; $('#search-results').replaceChildren();
  const previousWindowStopped = readingWindow.stop({ silent: true });
  pageProgress.clear();
  readingMetadataSignatures.clear(); readingMetadataVersions.clear(); readingMetadataProfiles.clear();
  deferredReady = null;
  renderReadingWindowStatus(null);
  const requestId = ++bookRequest;
  $('#usage-open').disabled = true;
  const submit = $('#job-form button[type="submit"]');
  const refresh = $('#refresh-job');
  setBusy(submit, false); submit.textContent = '开始处理'; delete submit.dataset.label; submit.disabled = true;
  setBusy(refresh, false); refresh.textContent = '刷新任务状态'; delete refresh.dataset.label;
  ++pageRequest;
  pageFetchController?.abort();
  clearPolling(); ++outlineRequest; state.pageCache.clear(); state.page = null; state.blocks = []; state.selectedIssueId = null; state.dirty = false; state.outline = []; state.outlineStatus = 'idle'; state.activeOutlineBlockId = null; state.job = null;
  updateSaveStatus();
  jobSyncError = false; $('#job-progress').hidden = true; $('#job-recovery').hidden = true;
  // A1-01：切书开启新编辑会话并清空冲突栏
  state.editorEpoch++; state.conflict = null; state.saveInFlight = null; sessionGuard.invalidate();
  // J08：切书换作用域，辅助推荐映射清空
  state.assistMap = {};
  if (!id) {
    state.book = null; state.summaries = []; state.focus = false; document.body.classList.remove('focus-reading'); $('#workspace').classList.add('is-empty'); $('#empty-state').hidden = false; $('#reader-shell').hidden = true; renderBookMeta(); renderToc(); return;
  }
  try {
    const book = await api.readerBook(id);
    if (requestId !== bookRequest) return;
    state.book = book; state.summaries = []; $('#book-select').value = id;
    $('#workspace').classList.remove('is-empty'); $('#empty-state').hidden = true; $('#reader-shell').hidden = false;
    renderBookMeta(); renderBookmarks();
    const finiteDefault = Math.min(20, book.totalPages);
    if (!$('#all-pages').checked) $('#page-range').value = finiteDefault > 1 ? `1-${finiteDefault}` : '1';
    const prefs = loadPreferences(id);
    state.view = ['original', 'facsimile', 'reading'].includes(prefs.view) ? prefs.view : 'reading';
    state.script = prefs.script === 'original' ? 'original' : 'simplified'; state.fontSize = Math.max(14, Math.min(36, Number(prefs.fontSize) || 20)); state.lineHeight = Math.max(1.2, Math.min(2.6, Number(prefs.lineHeight) || 1.8)); state.focus = Boolean(prefs.focus);
    // J08：阅读依据默认保真（已确认）；旧偏好无此项，不迁移、不把旧显示当自动确认授权
    state.evidenceMode = prefs.evidenceMode === 'assisted' ? 'assisted' : 'confirmed'; state.assistMap = {};
    $('#font-size').value = state.fontSize; $('#font-output').value = state.fontSize; $('#line-height').value = state.lineHeight; $('#line-output').value = state.lineHeight;
    const page = Math.min(book.totalPages, Math.max(1, (Number.isSafeInteger(Number(prefs.page)) ? Number(prefs.page) : 1)));
    state.currentPage = page;
    await goToPage(page, { force: true, restoreScroll: true, scrollTop: prefs.scrollTop, skipSavePosition: true });
    if (requestId !== bookRequest) return;
    // First paint must not wait for an O(book-size) outline/statistics pass.
    void api.pages(id).then(summaries => {
      if (requestId !== bookRequest) return;
      state.summaries = Array.isArray(summaries) ? summaries : [];
      renderToc(); renderBookMeta();
    }).catch(() => { /* Metadata can be refreshed; never replace readable content. */ });
    void refreshOutline(id);
    const jobSynced = refreshJob();
    await previousWindowStopped;
    await jobSynced;
    if (requestId !== bookRequest) return;
    await ensureReadingWindowActive();
  } catch (error) { if (requestId === bookRequest) showError(error); }
}

function validateRange(value, total) {
  if (!value.trim()) return false;
  return value.split(',').every(part => {
    const match = part.trim().match(/^(\d+)(?:-(\d+))?$/);
    if (!match) return false;
    const start = Number(match[1]), end = Number(match[2] || match[1]);
    return start >= 1 && end >= start && end <= total;
  });
}

function renderJob(job) {
  state.job = job;
  if (activeJobs.has(job?.status)) pageProgress.wake();
  const active = activeJobs.has(job.status);
  $('#job-progress').hidden = job.status === 'IDLE';
  $('#cancel-job').hidden = !active;
  $('#job-form button[type="submit"]').disabled = active || readingWindow.active() || !state.book;
  const labels = { IDLE: '未开始', QUEUED: '等待处理', RUNNING: '正在转换', CANCELLING: '正在取消', COMPLETED: '处理完成', COMPLETED_WITH_ERRORS: '完成，部分页面失败', CANCELLED: '任务已取消', INTERRUPTED: '任务因服务重启而中断', FAILED: '任务失败' };
  const pct = job.total ? Math.round(((job.completed || 0) / job.total) * 100) : 0;
  renderJobHeading();
  $('#progress-label').textContent = active ? `正在转换 · ${pct}%` : (labels[job.status] || job.status);
  const errors = [...(job.errors || []), ...(job.error ? [job.error] : [])];
  // U4：100% 只代表固定集合全部结束；有失败必须显示部分完成，不能绿色“全部成功”。
  $('#progress-count').textContent = job.total
    ? (active
      ? `${pct}% (${job.completed || 0} / ${job.total} 页)${job.currentPage ? ` · 当前第 ${job.currentPage} 页` : ''}`
      : `已结束 ${job.completed || 0} / ${job.total} 页${errors.length ? ` · ${errors.length} 条需处理` : ' · 全部成功'}`)
    : '';
  $('#progress-bar').max = Math.max(1, job.total || 1); $('#progress-bar').value = job.completed || 0;
  $('#job-panel').classList.toggle('job-settled', job.status === 'COMPLETED' && !errors.length && !jobSyncError);
  $('#job-errors').textContent = errors.slice(-3).join('；');
  $('#job-error-details').hidden = errors.length <= 3;
  $('#job-error-summary').textContent = `查看全部错误（${errors.length} 条）`;
  const list = $('#job-all-errors'); list.replaceChildren();
  errors.forEach(error => { const item = document.createElement('li'); item.textContent = error; list.append(item); });
  const guidance = {
    COMPLETED_WITH_ERRORS: '部分页面未完成。按上方错误页码重新选择范围；默认会跳过已完成页，不会覆盖手工校对。',
    CANCELLED: '已完成的页面保留。可按需重新选择未完成的页码继续处理。',
    INTERRUPTED: '服务中断前完成的页面保留。核对页码后可继续处理；不要勾选覆盖已有结果。',
    FAILED: '任务未完成。检查上方错误和服务状态后，重新选择未完成页码。'
  };
  $('#job-recovery').hidden = !(jobSyncError || guidance[job.status]);
  $('#job-recovery-message').textContent = jobSyncError
    ? '暂时无法确认任务状态，处理可能仍在后台运行。请先刷新状态，不要重复创建任务。'
    : (guidance[job.status] || '');
  $('#refresh-job').hidden = !jobSyncError;
  $('#edit-job').hidden = Boolean(jobSyncError || !guidance[job.status]);
  renderQuality();
  renderPageMessage();
  if (active) schedulePoll(); else clearPolling();
}

function schedulePoll(delay = 1500) {
  clearPolling();
  const bookId = state.book?.id;
  const requestId = bookRequest;
  state.pollTimer = window.setTimeout(async () => {
    if (!jobSessionMatches(bookId, requestId)) return;
    try {
      const job = await api.job(bookId);
      if (!jobSessionMatches(bookId, requestId)) return;
      jobSyncError = false; renderJob(job);
      if (!activeJobs.has(job.status)) {
        try { await refreshBookData(bookId, requestId); }
        catch (error) { if (jobSessionMatches(bookId, requestId)) showError(error); }
      } else if (state.page && state.page.status !== 'READY' && (!job.currentPage || job.currentPage > state.currentPage)) {
        api.page(bookId, state.currentPage).then(fresh => {
          if (fresh && fresh.status === 'READY' && state.currentPage === fresh.pageNumber && jobSessionMatches(bookId, requestId)) {
            acceptReadyPage(fresh.pageNumber, fresh);
          }
        }).catch(() => {});
      }
    } catch (_) {
      if (!jobSessionMatches(bookId, requestId)) return;
      showJobSyncError();
    }
  }, delay);
}

function showJobSyncError() {
  jobSyncError = true;
  $('#job-panel').classList.remove('job-settled');
  $('#job-progress').hidden = false;
  $('#job-recovery').hidden = false;
  $('#job-recovery-message').textContent = '暂时无法确认任务状态，处理可能仍在后台运行。请先刷新状态，不要重复创建任务。';
  $('#refresh-job').hidden = false; $('#edit-job').hidden = true;
  $('#job-form button[type="submit"]').disabled = true;
  schedulePoll(10000);
}

async function refreshJob() {
  if (!state.book) return;
  const bookId = state.book.id;
  const requestId = bookRequest;
  try {
    const job = await api.job(bookId);
    if (!jobSessionMatches(bookId, requestId)) return;
    const recovered = jobSyncError;
    jobSyncError = false; renderJob(job);
    if (recovered && !activeJobs.has(job.status)) {
      try { await refreshBookData(bookId, requestId); }
      catch (error) { if (jobSessionMatches(bookId, requestId)) showError(error); }
    }
  } catch (_) {
    if (!jobSessionMatches(bookId, requestId)) return;
    showJobSyncError();
  }
}

async function refreshBookData(id = state.book?.id, requestId = bookRequest) {
  if (!id || !jobSessionMatches(id, requestId)) return;
  const [book, summaries] = await Promise.all([api.book(id), api.pages(id)]);
  if (!jobSessionMatches(id, requestId)) return;
  const protectedPage = currentPageProtected() && state.page ? state.page : null;
  state.book = book; state.books = state.books.map(item => item.id === id ? book : item); state.summaries = summaries; state.pageCache.clear();
  if (protectedPage) state.pageCache.set(state.currentPage, protectedPage);
  renderBooks(); renderBookMeta(); renderToc();
  await refreshOutline(id);
  if (!jobSessionMatches(id, requestId)) return;
  if (currentPageProtected()) { deferredReady = { bookId: id, page: state.currentPage, revision: null }; renderReadingWindowStatus(); toast('任务状态已更新，保留当前校对内容；保存后可更新本页。'); return; }
  await goToPage(state.currentPage, { force: true, preserveScroll: true });
}

let lastDrawerOpener = null;
function openDrawer(type) {
  const opener = document.activeElement;
  if (opener && (opener.id === 'toc-toggle' || opener.id === 'review-toggle')) lastDrawerOpener = opener;
  const panel = type === 'toc' ? $('#toc-panel') : $('#review-panel');
  const other = type === 'toc' ? $('#review-panel') : $('#toc-panel');
  other.classList.remove('open'); panel.classList.add('open'); $('#drawer-scrim').hidden = false;
  $('#toc-toggle').setAttribute('aria-expanded', String(type === 'toc')); $('#review-toggle').setAttribute('aria-expanded', String(type === 'review'));
  if (type === 'review') renderReview();
}

function closeDrawers(restoreFocus = false) {
  $('#toc-panel').classList.remove('open'); $('#review-panel').classList.remove('open'); $('#drawer-scrim').hidden = true;
  $('#toc-toggle').setAttribute('aria-expanded', 'false'); $('#review-toggle').setAttribute('aria-expanded', 'false');
  if (state.view !== 'original') unmountIssueWorkbench($('#issue-workbench'));
  // A1-06：显式关闭抽屉时焦点回到触发按钮；程序化关闭（翻页/切书）不抢焦点
  if (restoreFocus && lastDrawerOpener && document.contains(lastDrawerOpener)) {
    try { lastDrawerOpener.focus({ preventScroll: true }); } catch (_) { /* 忽略 */ }
  }
  lastDrawerOpener = null;
}

function startDrawing(type) {
  if (!state.page) return;
  if (state.view !== 'original') { state.view = 'original'; renderCurrent(); }
  requestAnimationFrame(() => {
    const stage = imageStage(); if (!stage) return;
    cancelDrawing?.(); state.drawType = type; $('#draw-hint').hidden = false;
    cancelDrawing = enableDrawing(stage, type, (blockType, bbox) => {
      const order = state.blocks.reduce((max, block) => Math.max(max, Number(block.order || 0)), 0) + 1;
      const id = `manual-${Date.now()}-${Math.random().toString(16).slice(2)}`;
      state.blocks.push({ id, type: blockType, order, bbox, writingMode: 'horizontal-tb', original: '', simplified: '', confidence: null, uncertain: false, reviewed: false, source: 'manual', issues: [] });
      state.selectedBlockId = id; state.drawType = null; cancelDrawing = null; $('#draw-hint').hidden = true; markDirty(); renderCurrent();
    });
  });
}

async function init() {
  // 阶段2：点击疑字后按需加载精确证据（正文读取不再附带高清渲染）
  globalThis.BookReadingLayout?.setEvidenceFetcher?.(({ page, issueId }) => {
    if (!page || page !== state.page || !issueId) return Promise.resolve(null);
    return loadIssueEvidence(issueId);
  });
  try {
    const [config, books] = await Promise.all([api.config(), api.books()]);
    state.config = config; state.books = books; renderProviders(); renderBooks(); renderBookMeta();
  } catch (error) { showError(error); $('#provider-note').textContent = '无法读取服务端配置，请确认 Java 服务已启动。'; }
}

window.refreshProcessingConfig = async () => {
  const config = await api.config();
  state.config = config;
  renderProviders();
  state.assistMap = {};
  if (state.page) renderCurrent(false);
  await decisionPanel.refresh();
};

const library = createLibrary({
  books: () => state.books,
  currentBookId: () => state.book?.id,
  canArchiveCurrent: () => !currentPageProtected(),
  openUsage: book => openBookUsage(book),
  openBook: async id => {
    if (state.book?.id === id) return true;
    await selectBook(id);
    return state.book?.id === id;
  },
  processBook: async book => {
    if (readingWindow?.active()) {
      await readingWindow.stop({ silent: true });
    }
    const provider = selectedProvider() || 'paddle-aistudio';
    const form = new FormData($('#job-form'));
    const assist = $('#qwen-assist')?.checked && !$('#qwen-assist').disabled;
    await api.startJob(book.id, {
      pages: `1-${book.totalPages}`,
      provider,
      layout: form.get('layout') || 'auto',
      splitSpreads: form.get('splitSpreads') === 'on',
      force: false,
      assist: assist ?? true
    });
    if (state.book?.id === book.id) {
      schedulePoll();
    }
  },
  changed: async (updated, patch, latest) => {
    if (latest) state.books = latest;
    else if (updated) state.books = state.books.map(book => book.id === updated.id ? updated : book);
    if (updated?.id === state.book?.id) {
      if (patch?.archived) await selectBook('');
      else { state.book = updated; renderBookMeta(); }
    }
    renderBooks();
  }
});
$('#book-select').addEventListener('change', event => selectBook(event.target.value));
const moreToggle = $('#more-toggle');
const moreTools = $('#more-tools');
function closeMore(restoreFocus = false) {
  if (!moreTools.classList.contains('open')) return;
  moreTools.classList.remove('open');
  moreToggle.setAttribute('aria-expanded', 'false');
  if (restoreFocus) moreToggle.focus({ preventScroll: true });
}
moreToggle.addEventListener('click', () => {
  const open = moreTools.classList.toggle('open');
  moreToggle.setAttribute('aria-expanded', String(open));
  if (open) moreTools.querySelector('label, button:not(:disabled)')?.focus?.();
});
moreTools.querySelector('.upload-button').addEventListener('keydown', event => {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    $('#pdf-upload').click();
  }
});
document.addEventListener('pointerdown', event => {
  if (!moreTools.contains(event.target) && !moreToggle.contains(event.target)) closeMore();
});
moreTools.addEventListener('click', event => {
  const button = event.target.closest('button');
  if (button && button.id !== 'usage-open' && button.id !== 'settings-open') closeMore();
});
$('#pdf-upload').addEventListener('change', async event => {
  closeMore(true);
  const file = event.target.files[0]; if (!file) return;
  if (file.type && file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) { toast('请选择 PDF 文件。', 'error'); event.target.value = ''; return; }
  const max = Number(state.config?.maxUploadMb || 0); if (max && file.size > max * 1024 * 1024) { toast(`文件超过 ${max} MB 上传上限。`, 'error'); event.target.value = ''; return; }
  const labels = $$('[for="pdf-upload"]');
  labels.forEach(label => label.classList.add('busy'));
  event.target.disabled = true;
  importStatus(`正在导入《${file.name}》；大文件可能需要稍等，请勿重复选择。`);
  try {
    const book = await api.upload(file);
    state.books = [book, ...state.books.filter(item => item.id !== book.id)]; renderBooks();
    library.render();
    importStatus('导入成功，正在打开原稿…');
    await selectBook(book.id);
    importStatus(''); toast('PDF 已导入，可以先浏览原稿。', 'success');
  } catch (error) {
    const uncertain = error?.name === 'TimeoutError' || error?.name === 'TypeError';
    importStatus(uncertain
      ? '连接中断，导入结果尚不确定。先刷新书架检查是否已导入，确认没有后再试，避免重复。'
      : (error?.message || '导入失败，请检查文件后重试。'), uncertain);
  } finally { labels.forEach(label => label.classList.remove('busy')); event.target.disabled = false; event.target.value = ''; }
});

const emptyState = $('#empty-state');
emptyState.addEventListener('dragover', event => {
  if (![...(event.dataTransfer?.items || [])].some(item => item.kind === 'file')) return;
  event.preventDefault();
  event.dataTransfer.dropEffect = 'copy';
  emptyState.classList.add('drop-target');
});
emptyState.addEventListener('dragleave', event => {
  if (!emptyState.contains(event.relatedTarget)) emptyState.classList.remove('drop-target');
});
emptyState.addEventListener('drop', event => {
  event.preventDefault();
  emptyState.classList.remove('drop-target');
  const files = event.dataTransfer?.files;
  if (!files?.length) return;
  if (files.length !== 1) { toast('请一次只导入一份 PDF。', 'error'); return; }
  try {
    const transfer = new DataTransfer();
    transfer.items.add(files[0]);
    const input = $('#pdf-upload');
    input.files = transfer.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  } catch (_) { toast('拖放导入不可用，请使用“选择 PDF”。', 'error'); }
});

$('#refresh-library').addEventListener('click', async () => {
  const button = $('#refresh-library'); setBusy(button, true, '刷新中…');
  try { state.books = await api.books(); renderBooks(); library.render(); importStatus('书架已刷新。若找到刚才导入的书，请从书架选择；没有时再重新导入。'); }
  catch (error) { importStatus(`刷新书架失败：${error?.message || '请检查服务状态后重试。'}`, true); }
  finally { setBusy(button, false); }
});

$('#job-toggle').addEventListener('click', () => { const form = $('#job-form'); form.hidden = !form.hidden; $('#job-toggle').setAttribute('aria-expanded', String(!form.hidden)); });
$('#refresh-job').addEventListener('click', async () => {
  const button = $('#refresh-job'); setBusy(button, true, '刷新中…');
  const bookId = state.book?.id, requestId = bookRequest;
  try { await refreshJob(); }
  finally { if (jobSessionMatches(bookId, requestId)) setBusy(button, false); }
});
$('#edit-job').addEventListener('click', () => {
  $('#force-processing').checked = false;
  $('#job-form').hidden = false;
  $('#job-toggle').setAttribute('aria-expanded', 'true');
  $('#page-range').focus();
});
$('#all-pages').addEventListener('change', event => { $('#page-range').disabled = event.target.checked; if (event.target.checked) $('#page-range').value = ''; else { const end = Math.min(20, state.book?.totalPages || 20); $('#page-range').value = end > 1 ? `1-${end}` : '1'; } });
$('#provider-options').addEventListener('change', updateProviderNote);
$('#qwen-assist').addEventListener('change', event => { assistPreference = event.target.checked; });
$('#split-spreads').addEventListener('change', event => { splitSpreadsPreference = event.target.checked; });
$('#job-form').addEventListener('submit', async event => {
  event.preventDefault(); if (!state.book) return;
  if (readingWindow.active()) { toast('请先停止随读识别，再启动手动处理任务。', 'error'); return; }
  if (jobSyncError) { toast('先刷新任务状态，确认后台没有仍在运行的任务。', 'error'); return; }
  const form = new FormData(event.currentTarget); const all = $('#all-pages').checked; const pages = all ? 'all' : String(form.get('pages') || '');
  if (!all && !validateRange(pages, state.book.totalPages)) { toast(`请输入 1 到 ${state.book.totalPages} 页内的页码，例如 1-20 或 1,3,25。`, 'error'); $('#page-range').focus(); return; }
  const provider = selectedProvider(); if (!provider) { toast('请选择可用的识别方式。', 'error'); return; }
  const assist = $('#qwen-assist').checked && !$('#qwen-assist').disabled;
  const providerConfig = state.config?.providers?.find(item => item.id === provider);
  const providerLabel = providerConfig?.label || provider;
  const assistLabel = state.config?.qwenAssist?.model || 'Qwen3.8-Max';
  if (provider !== 'local' && !window.confirm(`将使用【${providerLabel}】识别第【${all ? `全书共 ${state.book.totalPages} 页` : pages}】页，页面图片会发送到该服务，可能产生费用。原稿和已保存内容会保留。${assist ? `另启用 ${assistLabel} 做结构整理与疑点建议（另计用量，建议不自动覆盖原文）。` : ''}`)) return;
  if (form.get('force') && !window.confirm('这会替换所选页已有的识别和手工校对结果，确定重新识别吗？')) return;
  const button = event.currentTarget.querySelector('button[type="submit"]'); setBusy(button, true, '正在创建任务…');
  const bookId = state.book.id, requestId = bookRequest;
  try {
    const job = await api.startJob(bookId, { pages, provider, assist, layout: form.get('layout'), splitSpreads: form.get('splitSpreads') === 'on', force: form.get('force') === 'on' });
    if (!jobSessionMatches(bookId, requestId)) return;
    renderJob(job); $('#force-processing').checked = false; toast('处理任务已开始。', 'success');
  } catch (error) {
    if (!jobSessionMatches(bookId, requestId)) return;
    if (error?.name === 'TimeoutError' || error?.name === 'TypeError') showJobSyncError();
    else showError(error);
  } finally {
    if (jobSessionMatches(bookId, requestId)) {
      setBusy(button, false);
      button.disabled = jobSyncError || Boolean(state.pollTimer) || !state.book;
    }
  }
});
$('#cancel-job').addEventListener('click', async () => {
  if (!state.book || !window.confirm('取消会停止本地处理或等待，但不能保证撤销已提交的远端任务或计费；已经完成的页面会保留。确定继续吗？')) return;
  const bookId = state.book.id;
  const requestId = bookRequest;
  try {
    const job = await api.cancelJob(bookId);
    if (state.book?.id !== bookId || requestId !== bookRequest) return;
    renderJob(job); toast('已请求取消任务。');
  } catch (error) { if (state.book?.id === bookId && requestId === bookRequest) showError(error); }
});

$$('[data-view]').forEach(button => button.addEventListener('click', () => { state.view = button.dataset.view; renderCurrent(); saveReadingPosition(); }));
$('#focus-toggle').addEventListener('click', () => { state.focus = !state.focus; closeDrawers(); renderCurrent(false); saveReadingPosition(); });
$('#script-toggle').addEventListener('click', () => { state.script = state.script === 'simplified' ? 'original' : 'simplified'; renderCurrent(); saveReadingPosition(); });
$('#evidence-toggle').addEventListener('click', () => { state.evidenceMode = state.evidenceMode === 'assisted' ? 'confirmed' : 'assisted'; renderCurrent(); saveReadingPosition(); });
// J08/12.3：默认复制/检索不冒充推荐为原文；辅助推荐复制时带未确认提示
$('#paper').addEventListener('copy', event => {
  const selection = window.getSelection();
  if (!selection || selection.rangeCount === 0 || !event.clipboardData) return;
  const range = selection.getRangeAt(0);
  const host = $('#paper');
  if (!host.contains(range.commonAncestorContainer)) return;
  // 选区可能只含标记内部文字（clone 不含标记本身），沿祖先链判定
  let marked = false;
  for (let node = range.commonAncestorContainer; node && node !== host; node = node.parentNode) {
    if (node.hasAttribute?.('data-unconfirmed')) { marked = true; break; }
  }
  if (!marked) {
    const probe = range.cloneContents();
    const inner = document.createElement('div');
    inner.append(probe);
    marked = Boolean(inner.querySelector('[data-unconfirmed]'));
  }
  if (!marked) return;
  event.preventDefault();
  event.clipboardData.setData('text/plain',
    `${selection.toString()}\n［注：含未确认的辅助推荐，以原文与已确认文字为准］`);
});
// 字号/行距：拖动只改 CSS 变量即时反馈（不整页重渲），松手才统一重排与保存。
function applyReadingMetrics() {
  const flow = document.querySelector('#paper .reading-flow');
  if (flow) {
    flow.style.setProperty('--reading-size', `${state.fontSize}px`);
    flow.style.setProperty('--reading-leading', String(state.lineHeight));
  }
}
$('#font-size').addEventListener('input', event => { state.fontSize = Number(event.target.value); $('#font-output').value = state.fontSize; applyReadingMetrics(); });
$('#font-size').addEventListener('change', () => { renderCurrent(false); saveReadingPosition(); });
$('#line-height').addEventListener('input', event => { state.lineHeight = Number(event.target.value); $('#line-output').value = state.lineHeight; applyReadingMetrics(); });
$('#line-height').addEventListener('change', () => { renderCurrent(false); saveReadingPosition(); });
$('#prev-page').addEventListener('click', () => goToPage(state.currentPage - 1)); $('#next-page').addEventListener('click', () => goToPage(state.currentPage + 1));
$('#jump-form').addEventListener('submit', event => { event.preventDefault(); commitPageInput(); }); $('#page-jump').addEventListener('change', commitPageInput);
$('#reading-progress-range').addEventListener('input', event => renderReadingProgress(Number(event.target.value)));
$('#reading-progress-range').addEventListener('change', event => goToPage(Number(event.target.value)));
// U7-Style：诊断详情默认收起，按需展开；关闭面板/切书时收起。
document.querySelector('#quality-diagnostics-toggle')?.addEventListener('click', event => {
  const toggle = event.currentTarget;
  const open = toggle.dataset.open !== 'true';
  toggle.dataset.open = String(open);
  toggle.setAttribute('aria-expanded', String(open));
  const diag = document.querySelector('#quality-diagnostics');
  if (diag && diag.textContent) diag.hidden = !open;
});
// U1：阅读模式默认进入，校对按需进入；后台任务不得自动打开面板。
initReaderMode();
$('#reading-window-open').addEventListener('click', () => {
  if (!state.book) return;
  const dialog = $('#reading-window-dialog');
  const provider = selectedProvider();
  const providerConfig = state.config?.providers?.find(item => item.id === provider);
  const assist = $('#qwen-assist').checked && !$('#qwen-assist').disabled;
  const qwenModel = state.config?.qwenAssist?.model || 'Qwen';
  $('#reading-window-choice').textContent = readingWindow.active()
    ? '随读识别已开启。本次通道和辅助选项固定；停止后可重新选择。'
    : `本次使用：${providerConfig?.label || provider || '未选择可用通道'}。${providerConfig ? providerUsage(providerConfig) : ''} ${assist ? `另启用 ${qwenModel} 结构整理，可能额外计费。` : '不启用 Qwen；只有在处理设置中主动勾选后才会使用。'}`;
  $('#reading-window-enable').hidden = readingWindow.active();
  $('#reading-window-enable').disabled = !provider || !providerConfig?.available;
  dialog.showModal();
});
$('#reading-window-enable').addEventListener('click', async () => {
  if (!state.book || readingWindow.active()) return;
  const provider = selectedProvider();
  const providerConfig = state.config?.providers?.find(item => item.id === provider);
  if (!providerConfig?.available) { toast('请选择可用的云识别通道。', 'error'); return; }
  const form = new FormData($('#job-form'));
  const options = { provider, layout: form.get('layout') || 'auto', splitSpreads: form.get('splitSpreads') === 'on',
    assist: $('#qwen-assist').checked && !$('#qwen-assist').disabled, autoProcessAll: isAutoProcessAll() };
  $('#reading-window-dialog').close();
  await readingWindow.enable(options);
});
$('#reading-window-stop').addEventListener('click', () => { void readingWindow.stop(); });
$('#reading-window-refresh').addEventListener('click', () => { void readingWindow.refreshStatus(); });
$('#retry-page-header')?.addEventListener('click', retryCurrentPage);
$('#reload-page-header')?.addEventListener('click', reloadCurrentPage);
$('#reader-reload-page')?.addEventListener('click', reloadCurrentPage);
const autoReadCheckbox = $('#reading-window-auto-start');
if (autoReadCheckbox) {
  autoReadCheckbox.checked = isAutoReadEnabled();
  autoReadCheckbox.addEventListener('change', () => {
    try { localStorage.setItem('book_html_auto_read', String(autoReadCheckbox.checked)); } catch (_) {}
  });
}
function setAutoProcessAll(val, persist = false) {
  if (persist) {
    try { localStorage.setItem('book_html_auto_process_all_v2', String(val)); } catch (_) {}
  }
  const cb1 = $('#auto-process-all');
  if (cb1) cb1.checked = val;
  const cb2 = $('#reading-window-auto-all');
  if (cb2) cb2.checked = val;
}
$('#auto-process-all')?.addEventListener('change', e => setAutoProcessAll(e.target.checked, true));
$('#reading-window-auto-all')?.addEventListener('change', e => setAutoProcessAll(e.target.checked, true));
setAutoProcessAll(isAutoProcessAll(), false);
$('#reading-window-apply').addEventListener('click', async () => {
  if (!deferredReady || !state.book || deferredReady.bookId !== state.book.id || deferredReady.page !== state.currentPage) return;
  if (currentPageProtected()) { toast('请先保存或处理冲突，再更新本页。'); return; }
  const bookId = state.book.id, pageNumber = state.currentPage;
  try {
    const page = await api.page(bookId, pageNumber);
    if (state.book?.id === bookId && state.currentPage === pageNumber) acceptReadyPage(pageNumber, page);
  } catch (error) { if (state.book?.id === bookId) showError(error); }
});
$('#reading-window-manual').addEventListener('click', async () => {
  const failed = (readingSnapshot?.pages || []).filter(page => page.status === 'FAILED').map(page => page.pageNumber);
  if (!failed.length) return;
  await readingWindow.stop();
  $('#page-range').value = failed.join(',');
  $('#all-pages').checked = false; $('#page-range').disabled = false; $('#force-processing').checked = false;
  $('#job-form').hidden = false; $('#job-toggle').setAttribute('aria-expanded', 'true');
  toast('已填入失败页。请确认处理设置后手动开始；已发出的单页任务结束前可能提示占用。');
  $('#page-range').focus();
});
$('#bookmark-button').addEventListener('click', () => { if (!state.book) return; const pages = getBookmarks(state.book.id); const found = pages.indexOf(state.currentPage); found >= 0 ? pages.splice(found, 1) : pages.push(state.currentPage); setBookmarks(state.book.id, pages); renderBookmarkButton(); renderBookmarks(); });

$$('[data-left-tab]').forEach(button => button.addEventListener('click', () => { $$('[data-left-tab]').forEach(tab => { const active = tab === button; tab.classList.toggle('active', active); tab.setAttribute('aria-selected', String(active)); }); $$('[data-left-panel]').forEach(panel => { panel.hidden = panel.dataset.leftPanel !== button.dataset.leftTab; }); }));
$('#search-form').addEventListener('submit', async event => {
  event.preventDefault(); if (!state.book) return;
  const query = $('#search-input').value.trim();
  const generation = ++searchGeneration, bookId = state.book.id, bookGeneration = bookRequest;
  searchController?.abort(); searchController = new AbortController();
  const own = searchController;
  const current = () => !own.signal.aborted && generation === searchGeneration
    && state.book?.id === bookId && bookGeneration === bookRequest;
  $('#search-results').replaceChildren();
  $('#search-status').textContent = query ? '正在搜索…' : '';
  if (!query) return;
  try {
    const results = await api.search(bookId, query, own.signal);
    if (!current()) return;
    $('#search-status').textContent = results.length ? `${results.length} 处命中` : '没有找到。可切换繁简字词再试。';
    results.forEach(result => {
      const li = document.createElement('li'), button = document.createElement('button');
      button.type = 'button'; button.className = 'search-result';
      const page = document.createElement('strong'), text = document.createElement('span');
      page.textContent = `第 ${result.pageNumber} 页`; text.textContent = result.text;
      button.append(page, text);
      button.addEventListener('click', async () => {
        if (!current()) return;
        const pending = goToPage(result.pageNumber), epoch = state.editorEpoch;
        const navigated = await pending;
        if (!current() || !navigated || state.editorEpoch !== epoch
            || state.currentPage !== result.pageNumber || !state.page) return;
        if (!(state.blocks || []).some(block => block.id === result.blockId)) return;
        state.selectedBlockId = result.blockId; renderReview(); syncOverlays();
      });
      li.append(button); $('#search-results').append(li);
    });
  } catch (error) {
    if (current()) { $('#search-status').textContent = ''; showError(error); }
  }
});

$('#uncertain-only').addEventListener('change', renderReview); $('#add-text').addEventListener('click', () => startDrawing('text')); $('#add-figure').addEventListener('click', () => startDrawing('figure'));
$('#mark-reviewed').addEventListener('change', event => { state.reviewedDraft = event.target.checked; markDirty(); });
$('#save-page').addEventListener('click', async () => {
  if (!state.book || !state.page) return;
  if (state.conflict) { toast('保存冲突尚未处理。请先在校对栏查看远端、下载草稿或加载远端。', 'error'); return; }
  // A1-01：单会话最多一个在途保存；Ctrl+S 走同一入口，同样受 guard 约束
  if (state.saveInFlight && state.saveInFlight.bookId === state.book.id
      && state.saveInFlight.page === state.currentPage && state.saveInFlight.epoch === state.editorEpoch) {
    toast('已有保存正在进行，请稍候。');
    return;
  }
  const button = $('#save-page'); setBusy(button, true, '保存中…');
  // 旧会话的请求可能尚未结束；新请求恢复时仍应回到按钮的固定文案。
  button.dataset.label = '保存整页';
  // A1-01：提交快照——等待期间的对象变化不得改变本次含义
  const snapshot = {
    requestId: ++saveRequestSeq,
    bookId: state.book.id, page: state.currentPage, epoch: state.editorEpoch,
    baseRevision: state.page?.revision ?? null,
    draftVersion: editVersion,
    blocks: cloneBlocks(state.blocks),
    reviewed: $('#mark-reviewed').checked,
  };
  state.saveInFlight = { requestId: snapshot.requestId, bookId: snapshot.bookId, page: snapshot.page, epoch: snapshot.epoch };
  updateSaveStatus();
  const clearFlight = () => { if (state.saveInFlight?.requestId === snapshot.requestId) state.saveInFlight = null; };
  try {
    const wasReviewed = Boolean(state.page.reviewed);
    // A1-S06 测试钩子：默认 30s；浏览器测试可经 window.__saveTimeoutMs 缩短以验证超时核实路径
    const timeoutMs = typeof window.__saveTimeoutMs === 'number' ? window.__saveTimeoutMs : 30000;
    const page = await api.savePage(snapshot.bookId, snapshot.page,
      { blocks: snapshot.blocks, reviewed: snapshot.reviewed, revision: snapshot.baseRevision }, timeoutMs);
    // A1-01：过期响应（切书/翻页/重进）直接丢弃，不写当前缓存、不碰当前 UI
    if (!editSessionMatches(snapshot)) return;
    if (snapshot.bookId === state.book?.id) void refreshOutline(snapshot.bookId);
    // 本次提交已确认：无论草稿是否推进，先采用新的服务端基线（版本与正文来自同一快照）
    state.pageCache.set(snapshot.page, page);
    if (state.page) state.page.revision = page.revision;
    if (editVersion !== snapshot.draftVersion) {
      // 保存期间的新草稿保留，仍标记未保存；下一次用新 revision 提交
      state.dirty = true;
      $('#save-page').disabled = false;
      updateSaveStatus();
      toast(`已保存到版本 ${page.revision ?? '最新'}；保存期间的新修改仍保留，请再次保存。`);
      return;
    }
    state.page = page; state.blocks = cloneBlocks(page.blocks); state.dirty = false; state.reviewedDraft = Boolean(page.reviewed);
    state.conflict = null;
    if (wasReviewed !== Boolean(page.reviewed)) state.book.reviewedPages = Math.max(0, Number(state.book.reviewedPages || 0) + (page.reviewed ? 1 : -1));
    const summary = summaryFor(state.currentPage); if (summary) { summary.status = page.status; summary.blockCount = page.blocks.length; summary.uncertainCount = page.blocks.filter(b => b.uncertain).length; summary.reviewed = page.reviewed; summary.title = pageTitle(page); }
    state.books = state.books.map(book => book.id === state.book.id ? state.book : book);
    renderBooks(); renderBookMeta(); renderCurrent(); toast('整页校对已保存，搜索与目录将使用新内容。', 'success');
  } catch (error) {
    // 被更快导航取代的请求静默丢弃
    if (error?.name === 'StaleRequest') return;
    // A1-01：过期会话的失败同样直接丢弃
    if (!editSessionMatches(snapshot)) return;
    if (error?.status === 409) {
      // 真冲突：保留本地草稿与原基线，常驻比较栏；不自动加 revision 重发
      state.conflict = {
        bookId: snapshot.bookId, page: snapshot.page, epoch: snapshot.epoch,
        baseRevision: snapshot.baseRevision, remoteRevision: error?.body?.currentRevision ?? null,
        remote: null, note: error.message || '远端已被更新。',
      };
      renderConflictBar();
      updateSaveStatus();
      showError(new Error(`${error.message}（远端已到版本 ${error?.body?.currentRevision ?? '未知'}）。本地草稿已保留，请在校对栏冲突条中处理。`));
      return;
    }
    // A1-S06：超时或网络中断不能断言服务端未保存——读回核实后再分类，不盲目重发。
    // 超时走完整比较（含冲突栏）；其他网络错误仅在可证明一致时认领，否则原样报错。
    if (error?.name === 'TimeoutError' || error?.name === 'TypeError') {
      const verify = await verifyUncertainSave(snapshot);
      if (!editSessionMatches(snapshot)) return;
      if (verify.outcome === 'saved' && verify.remote) {
        const remote = verify.remote;
        state.pageCache.set(snapshot.page, remote);
        if (state.page) state.page.revision = remote.revision;
        if (editVersion !== snapshot.draftVersion) { state.dirty = true; $('#save-page').disabled = false; }
        else { state.page = remote; state.blocks = cloneBlocks(remote.blocks); state.dirty = false; state.reviewedDraft = Boolean(remote.reviewed); renderCurrent(); }
        updateSaveStatus();
        toast(`服务端已确认保存（版本 ${remote.revision ?? '最新'}，中断后核实一致）。`, 'success');
        return;
      }
      if (verify.outcome === 'mismatch' && error?.name === 'TimeoutError') {
        state.conflict = {
          bookId: snapshot.bookId, page: snapshot.page, epoch: snapshot.epoch,
          baseRevision: snapshot.baseRevision, remoteRevision: verify.remote?.revision ?? null,
          remote: verify.remote, note: '请求超时，服务端版本与本次提交不一致，请核对后再保存。',
        };
        renderConflictBar();
        updateSaveStatus();
        showError(new Error('请求超时，服务端版本与本次提交不一致。本地草稿已保留，请在校对栏冲突条中处理。'));
        return;
      }
    }
    showError(error);
  }
  finally {
    clearFlight();
    // 全局按钮可能已属于另一个会话的新请求；旧请求不得清掉它的忙碌反馈。
    if (currentSaveInFlight()) {
      button.textContent = '保存中…';
      button.disabled = true;
    } else {
      setBusy(button, false);
      button.disabled = !state.dirty || Boolean(state.conflict);
    }
    updateSaveStatus();
    renderReadingWindowStatus();
  }
});

$('#export-button').addEventListener('click', () => {
  if (!state.book) return; const pending = state.book.totalPages - (state.book.processedPages || 0); const unreviewed = (state.book.processedPages || 0) - (state.book.reviewedPages || 0);
  if ((pending || unreviewed) && !window.confirm(`导出中将包含 ${pending} 个未处理页、${Math.max(0, unreviewed)} 个待校对页，并明确标注状态。仍要导出吗？`)) return;
  window.location.assign(api.exportUrl(state.book.id));
});
$('#usage-open').addEventListener('click', () => openBookUsage(state.book));

$('#toc-toggle').addEventListener('click', () => openDrawer('toc')); $('#review-toggle').addEventListener('click', () => openDrawer('review')); $('#close-review').addEventListener('click', () => closeDrawers(true)); $('#drawer-scrim').addEventListener('click', () => closeDrawers(true));
const readingOptions = $('#reading-options');
globalThis.BookReaderFonts?.init?.('#reader-font', { noteElement: '#reader-font-note' });
$('#reading-options-controls').append($('.reader-settings'));
// 主题（宣纸/月白/夜阑）与书稿纸色（宣纸/米黄/豆沙绿）：本地记忆，即时生效。
const THEME_KEY = 'book-html:theme:v1';
const PAPER_TINT_KEY = 'book-html:paper-tint:v1';
const themeSelect = $('#ui-theme');
const tintSelect = $('#paper-tint');
function applyTheme(theme, persist = true) {
  const value = ['paper', 'clear', 'night'].includes(theme) ? theme : 'paper';
  if (value === 'paper') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = value;
  if (themeSelect) themeSelect.value = value;
  if (persist) { try { localStorage.setItem(THEME_KEY, value); } catch (_) {} }
}
function applyPaperTint(tint, persist = true) {
  const value = ['plain', 'cream', 'bean'].includes(tint) ? tint : 'plain';
  if (value === 'plain') delete document.documentElement.dataset.paperTint;
  else document.documentElement.dataset.paperTint = value;
  if (tintSelect) tintSelect.value = value;
  if (persist) { try { localStorage.setItem(PAPER_TINT_KEY, value); } catch (_) {} }
}
let storedTheme = 'paper'; let storedTint = 'plain';
try { storedTheme = localStorage.getItem(THEME_KEY) || 'paper'; storedTint = localStorage.getItem(PAPER_TINT_KEY) || 'plain'; } catch (_) {}
applyTheme(storedTheme, false); applyPaperTint(storedTint, false);
themeSelect?.addEventListener('change', event => applyTheme(event.target.value));
tintSelect?.addEventListener('change', event => applyPaperTint(event.target.value));
$('#reading-options-open').addEventListener('click', () => readingOptions.showModal());
$('#reading-options-close').addEventListener('click', () => readingOptions.close());
readingOptions.addEventListener('close', () => $('#reading-options-open').focus({ preventScroll: true }));
for (const dialog of [readingOptions, $('#usage-dialog'), $('#settings-dialog')]) {
  dialog.addEventListener('click', event => {
    if (event.target !== dialog) return;
    const bounds = dialog.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) dialog.close();
  });
}
for (const dialog of [$('#usage-dialog'), $('#settings-dialog')]) dialog.addEventListener('close', () => closeMore(true));
$('#reader').addEventListener('scroll', () => { window.clearTimeout(scrollTimer); scrollTimer = window.setTimeout(saveReadingPosition, 180); });
window.addEventListener('beforeunload', event => { if (state.dirty) { event.preventDefault(); event.returnValue = ''; } });
window.addEventListener('pagehide', () => { void readingWindow.stop({ beacon: true }); });
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && state.book && !readingWindow.active() && isAutoReadEnabled()) {
    void ensureReadingWindowActive();
  }
});
window.addEventListener('keydown', event => { if (event.key === 'Escape') { closeMore(true); closeDrawers(true); } if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && state.page) { event.preventDefault(); $('#save-page').click(); } });

init();
