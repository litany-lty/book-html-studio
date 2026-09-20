import { api } from './api.js';
import { state, loadPreferences, savePreferences, getBookmarks, setBookmarks, cloneBlocks } from './store.js';
import { renderPaper, qualityOf } from './reader.js';
import { renderEditor, renderIssueWorkbench, issueReferences, createOverlay, enableDrawing } from './editor.js';

const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const activeJobs = new Set(['QUEUED', 'RUNNING', 'CANCELLING']);
const readerNavigation = globalThis.BookReaderNavigation;
let cancelDrawing = null;
let scrollTimer = null;
let bookRequest = 0;
let pageRequest = 0;
// 阶段2：快速翻页取消过时正文请求
let pageFetchController = null;
let editVersion = 0;
let outlineRequest = 0;
let pageInputPending = false;
let assistPreference;
let splitSpreadsPreference = true;

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

function imageStage() {
  return $('#paper .original-frame');
}

function selectedProvider() {
  return $('#provider-options input[name="provider"]:checked')?.value || '';
}

function providerChannel(id) {
  return state.config?.ocrChannels?.find(channel => channel.id === id);
}

function providerQuotaSource(provider) {
  return provider?.quotaSource || providerChannel(provider?.id)?.quotaSource || '';
}

function unavailableProviderReason(provider) {
  const reason = provider?.reason || '当前不可用，请检查服务端配置。';
  if (provider?.id === 'paddle-aistudio' && !reason.includes('PADDLEOCR_ACCESS_TOKEN')) {
    return `${reason} 请在服务端环境变量中设置 PADDLEOCR_ACCESS_TOKEN；Token 不会进入浏览器。`;
  }
  if (provider?.id === 'paddle' || provider?.id === 'ppocr') {
    if (!reason.includes('BAIDU_OCR_API_KEY') || !reason.includes('BAIDU_OCR_SECRET_KEY')) return `${reason} 请在服务端环境变量中设置 BAIDU_OCR_API_KEY 与 BAIDU_OCR_SECRET_KEY。`;
    return reason;
  }
  return reason;
}

function providerUsage(provider) {
  const quotaSource = providerQuotaSource(provider);
  return quotaSource
    ? `额度来源：${quotaSource}。这里只标明认证账户通道，不代表可用余额或免费额度。`
    : '实际用量由该云服务账户结算。';
}

function renderProviders() {
  const wrap = $('#provider-options');
  wrap.replaceChildren();
  const providers = state.config?.providers || [];
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
  const defaultProvider = state.config?.defaultProvider || 'paddle';
  const preferred = [...wrap.querySelectorAll('input:not(:disabled)')].find(input => input.value === defaultProvider);
  if (preferred) preferred.checked = true;
  updateProviderNote();
}

function updateProviderNote() {
  const id = selectedProvider();
  const provider = state.config?.providers?.find(item => item.id === id);
  if (!provider) {
    const expected = state.config?.defaultProvider || 'paddle';
    $('#provider-note').textContent = `默认识别方式 ${expected} 当前不可用，请检查服务端配置或主动选择其他方式；不会静默改用旧提供商。`;
    updateAssistAvailability();
    updateSplitSpreadsAvailability();
    return;
  }
  if (id === 'local') $('#provider-note').textContent = '本地处理不外发；扫描页自动结果不保证无误，可按需抽查。切换识别方式不会自动重跑已有 READY 页。';
  else $('#provider-note').textContent = `${provider.label}会把所选页面图片发送给对应云服务。${providerUsage(provider)} 密钥只由服务端读取；所选 paddle 系通道额度不足或无权限时会自动改用同系其他可用通道并在页面警告中注明，也不会自动重跑已有 READY 页。重跑需勾选“覆盖已有识别与校对结果”并再次确认。`;
  updateAssistAvailability();
  updateSplitSpreadsAvailability();
}

function updateAssistAvailability() {
  const input = $('#qwen-assist');
  const assist = state.config?.qwenAssist;
  const cloudPrimary = selectedProvider() && selectedProvider() !== 'local';
  const available = Boolean(assist?.configured && cloudPrimary);
  if (assistPreference === undefined) assistPreference = Boolean(assist?.assistEnabled);
  input.disabled = !available;
  input.checked = available && Boolean(assistPreference);
  $('#qwen-assist-label').classList.toggle('disabled', !available);
  const model = assist?.model || 'Qwen3.8-Max';
  if (!assist?.configured) $('#qwen-assist-note').textContent = '结构辅助未配置；需在服务端设置对应阿里云凭据，密钥不会进入浏览器。';
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
  state.books.forEach(book => select.append(new Option(`${book.title}（${book.totalPages} 页）`, book.id)));
  select.value = previous;
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

function renderBookMeta() {
  if (!state.book) {
    $('#book-summary').textContent = '先导入一本 PDF，原稿会始终保留。';
    $('#export-button').disabled = true;
    $('#job-form button[type="submit"]').disabled = true;
    renderReadingProgress();
    return;
  }
  const b = state.book;
  $('#book-summary').textContent = `${b.title} · ${b.totalPages} 页 · 已处理 ${b.processedPages || 0} 页 · 已校对 ${b.reviewedPages || 0} 页`;
  $('#export-button').disabled = false;
  $('#job-form button[type="submit"]').disabled = false;
  $('#page-jump').max = b.totalPages;
  $('#total-pages').textContent = `/ ${b.totalPages} 页`;
  renderReadingProgress();
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
  if (pageInputPending) return;
  pageInputPending = true;
  try { await goToPage(Number($('#page-jump').value)); }
  finally { pageInputPending = false; }
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
  const quality = qualityOf(state.page);
  $('#quality-badge').className = `quality-badge ${quality.tone}`;
  $('#quality-badge').textContent = quality.label;
  $('#quality-detail').textContent = quality.detail;
}

function renderReview() {
  const available = Boolean(state.page);
  $('#review-empty').hidden = available;
  $('#review-content').hidden = !available;
  if (!available) return;
  const reviewImage = $('#review-original');
  const imageUrl = api.pageImage(state.book.id, state.currentPage, 900);
  if (reviewImage.getAttribute('src') !== imageUrl) reviewImage.src = imageUrl;
  const refs = issueReferences(state.blocks);
  if (!refs.some(ref => ref.issue.id === state.selectedIssueId)) {
    state.selectedIssueId = (refs.find(ref => !ref.synthetic && !ref.issue.resolved) || refs[0])?.issue.id || null;
  }
  renderIssueWorkbench($('#issue-workbench'), state.blocks, {
    selectedIssueId: state.selectedIssueId,
    imageUrl,
    pageWidth: state.page.width,
    pageHeight: state.page.height,
    onSelect(blockId, issueId) {
      state.selectedBlockId = blockId; state.selectedIssueId = issueId; renderReview(); syncOverlays(); scrollToSelectedBlock(blockId);
    },
    onUpdate(block, issue, patch) {
      Object.assign(issue, patch); state.selectedBlockId = block.id; state.selectedIssueId = issue.id; markDirty(); renderCurrent();
    },
    onDirty() { markDirty(); }
  });
  $('#mark-reviewed').checked = Boolean(state.reviewedDraft);
  $('#save-page').disabled = !state.dirty;
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

function scrollToSelectedBlock(id) {
  requestAnimationFrame(() => {
    const item = [...$('#review-list').querySelectorAll('[data-block-id]')].find(node => node.dataset.blockId === id);
    item?.scrollIntoView({ block: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  });
}

function markDirty() {
  editVersion++;
  state.dirty = true;
  $('#save-page').disabled = false;
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
  const paper = $('#paper');
  const message = renderPaper(paper, {
    book: state.book,
    page: { ...state.page, blocks: state.blocks },
    blocks: state.blocks,
    view: state.view,
    script: state.script,
    fontSize: state.fontSize,
    lineHeight: state.lineHeight,
    onIssueSelect(blockId, issueId) {
      state.selectedBlockId = blockId; state.selectedIssueId = issueId; renderReview(); syncOverlays(); openDrawer('review');
      requestAnimationFrame(() => {
        const workbench = $('#issue-workbench');
        const target = workbench.querySelector('.issue-edit textarea') || workbench;
        if (target === workbench) target.tabIndex = -1;
        target.focus({ preventScroll: true });
        target.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
      });
    }
  });
  $('#page-message').hidden = !message;
  $('#page-message').textContent = message;
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
  renderQuality(); renderBookmarkButton(); renderToc();
  if (full) renderReview();
  if (state.view === 'original') requestAnimationFrame(syncOverlays);
}

function saveReadingPosition() {
  if (!state.book) return;
  savePreferences(state.book.id, { page: state.currentPage, view: state.view, script: state.script, fontSize: state.fontSize, lineHeight: state.lineHeight, focus: state.focus, scrollTop: $('#reader').scrollTop });
}

async function goToPage(n, options = {}) {
  if (!state.book || !Number.isInteger(n) || n < 1 || n > state.book.totalPages) { renderReadingProgress(); if (state.book) $('#page-jump').value = state.currentPage; return false; }
  if (!options.force && n === state.currentPage && state.page) {
    state.activeOutlineBlockId = options.outlineBlockId || null; renderToc(); renderReadingProgress(); $('#page-jump').value = state.currentPage; return true;
  }
  if (!options.force && hasDirtyChanges()) { renderReadingProgress(); $('#page-jump').value = state.currentPage; return false; }
  const previousScrollTop = $('#reader').scrollTop;
  const previous = { currentPage: state.currentPage, page: state.page, blocks: state.blocks, selectedBlockId: state.selectedBlockId,
    selectedIssueId: state.selectedIssueId, reviewedDraft: state.reviewedDraft, dirty: state.dirty,
    activeOutlineBlockId: state.activeOutlineBlockId };
  cancelDrawing?.(); cancelDrawing = null; state.drawType = null; $('#draw-hint').hidden = true;
  if (!options.skipSavePosition) saveReadingPosition();
  state.currentPage = n; state.page = null; state.blocks = []; state.selectedBlockId = null; state.selectedIssueId = null; state.dirty = false; state.activeOutlineBlockId = options.outlineBlockId || null;
  const requestId = ++pageRequest, bookId = state.book.id;
  // 阶段2：取消上一次未完成的正文请求，后端仍以自身预算为准继续或终止解码
  pageFetchController?.abort();
  pageFetchController = new AbortController();
  const fetchSignal = pageFetchController.signal;
  renderReview(); renderToc(); renderReadingProgress(n); $('#page-jump').value = n;
  $('#paper').replaceChildren();
  const loading = document.createElement('p'); loading.className = 'paper-loading'; loading.textContent = `正在读取第 ${n} 页…`; $('#paper').append(loading);
  try {
    const page = state.pageCache.get(n) || await api.page(bookId, n, fetchSignal);
    if (requestId !== pageRequest || state.book?.id !== bookId) return;
    state.pageCache.set(n, page); state.page = page; state.blocks = cloneBlocks(page.blocks); state.reviewedDraft = Boolean(page.reviewed); renderCurrent();
    const position = options.restoreScroll ? Number(options.scrollTop || 0) : 0;
    requestAnimationFrame(() => { $('#reader').scrollTop = position; });
    closeDrawers();
    return true;
  } catch (error) {
    // 阶段2：被更快翻页取代的请求静默丢弃，不恢复旧页、不报错
    if (error?.name === 'StaleRequest' || requestId !== pageRequest) return false;
    Object.assign(state, previous);
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
  const requestId = ++bookRequest;
  ++pageRequest;
  pageFetchController?.abort();
  clearPolling(); ++outlineRequest; state.pageCache.clear(); state.page = null; state.blocks = []; state.selectedIssueId = null; state.dirty = false; state.outline = []; state.outlineStatus = 'idle'; state.activeOutlineBlockId = null;
  if (!id) {
    state.book = null; state.summaries = []; state.focus = false; document.body.classList.remove('focus-reading'); $('#workspace').classList.add('is-empty'); $('#empty-state').hidden = false; $('#reader-shell').hidden = true; renderBookMeta(); renderToc(); return;
  }
  try {
    const [book, summaries] = await Promise.all([api.book(id), api.pages(id)]);
    if (requestId !== bookRequest) return;
    state.book = book; state.summaries = Array.isArray(summaries) ? summaries : []; $('#book-select').value = id;
    $('#workspace').classList.remove('is-empty'); $('#empty-state').hidden = true; $('#reader-shell').hidden = false;
    renderBookMeta(); renderBookmarks();
    const finiteDefault = Math.min(20, book.totalPages);
    if (!$('#all-pages').checked) $('#page-range').value = finiteDefault > 1 ? `1-${finiteDefault}` : '1';
    const prefs = loadPreferences(id);
    state.view = ['original', 'facsimile', 'reading'].includes(prefs.view) ? prefs.view : 'reading';
    state.script = prefs.script === 'original' ? 'original' : 'simplified'; state.fontSize = Number(prefs.fontSize || 20); state.lineHeight = Number(prefs.lineHeight || 1.8); state.focus = Boolean(prefs.focus);
    $('#font-size').value = state.fontSize; $('#font-output').value = state.fontSize; $('#line-height').value = state.lineHeight; $('#line-output').value = state.lineHeight;
    const page = Math.min(book.totalPages, Math.max(1, Number(prefs.page || 1)));
    state.currentPage = page;
    const outlinePromise = refreshOutline(id);
    await goToPage(page, { force: true, restoreScroll: true, scrollTop: prefs.scrollTop, skipSavePosition: true });
    await Promise.all([refreshJob(), outlinePromise]);
  } catch (error) { showError(error); }
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
  const active = activeJobs.has(job.status);
  $('#job-progress').hidden = job.status === 'IDLE';
  $('#cancel-job').hidden = !active;
  $('#job-form button[type="submit"]').disabled = active || !state.book;
  const labels = { IDLE: '未开始', QUEUED: '等待处理', RUNNING: '正在转换', CANCELLING: '正在取消', COMPLETED: '处理完成', COMPLETED_WITH_ERRORS: '完成，部分页面失败', CANCELLED: '任务已取消', INTERRUPTED: '任务因服务重启而中断', FAILED: '任务失败' };
  $('#progress-label').textContent = labels[job.status] || job.status;
  $('#progress-count').textContent = job.total ? `${job.completed || 0} / ${job.total}${job.currentPage ? ` · 第 ${job.currentPage} 页` : ''}` : '';
  $('#progress-bar').max = Math.max(1, job.total || 1); $('#progress-bar').value = job.completed || 0;
  const errors = [...(job.errors || []), ...(job.error ? [job.error] : [])]; $('#job-errors').textContent = errors.slice(-3).join('；');
  if (active) schedulePoll(); else clearPolling();
}

function schedulePoll() {
  clearPolling();
  const bookId = state.book?.id;
  state.pollTimer = window.setTimeout(async () => {
    if (!state.book) return;
    try {
      const job = await api.job(bookId);
      if (state.book?.id !== bookId) return;
      renderJob(job);
      if (!activeJobs.has(job.status)) await refreshBookData();
    } catch (error) { clearPolling(); showError(error); }
  }, 1500);
}

async function refreshJob() {
  if (!state.book) return;
  try { renderJob(await api.job(state.book.id)); } catch (error) { showError(error); }
}

async function refreshBookData() {
  if (!state.book) return;
  const id = state.book.id;
  const [book, summaries] = await Promise.all([api.book(id), api.pages(id)]);
  if (state.book?.id !== id) return;
  state.book = book; state.books = state.books.map(item => item.id === id ? book : item); state.summaries = summaries; state.pageCache.clear(); renderBooks(); renderBookMeta(); renderToc();
  await refreshOutline(id);
  if (state.dirty) { toast('任务状态已更新，保留当前未保存的校对内容。'); return; }
  await goToPage(state.currentPage, { force: true });
}

function openDrawer(type) {
  const panel = type === 'toc' ? $('#toc-panel') : $('#review-panel');
  const other = type === 'toc' ? $('#review-panel') : $('#toc-panel');
  other.classList.remove('open'); panel.classList.add('open'); $('#drawer-scrim').hidden = false;
  $('#toc-toggle').setAttribute('aria-expanded', String(type === 'toc')); $('#review-toggle').setAttribute('aria-expanded', String(type === 'review'));
}

function closeDrawers() {
  $('#toc-panel').classList.remove('open'); $('#review-panel').classList.remove('open'); $('#drawer-scrim').hidden = true;
  $('#toc-toggle').setAttribute('aria-expanded', 'false'); $('#review-toggle').setAttribute('aria-expanded', 'false');
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
    if (!state.book || !page || !issueId) return Promise.resolve(null);
    return api.issueMetadata(state.book.id, page.pageNumber, issueId).then(precise => {
      if (precise && page.issueImages) page.issueImages[issueId] = precise;
      return precise;
    });
  });
  try {
    const [config, books] = await Promise.all([api.config(), api.books()]);
    state.config = config; state.books = books; renderProviders(); renderBooks(); renderBookMeta();
  } catch (error) { showError(error); $('#provider-note').textContent = '无法读取服务端配置，请确认 Java 服务已启动。'; }
}

$('#book-select').addEventListener('change', event => selectBook(event.target.value));
$('#pdf-upload').addEventListener('change', async event => {
  const file = event.target.files[0]; if (!file) return;
  if (file.type && file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) { toast('请选择 PDF 文件。', 'error'); event.target.value = ''; return; }
  const max = Number(state.config?.maxUploadMb || 0); if (max && file.size > max * 1024 * 1024) { toast(`文件超过 ${max} MB 上传上限。`, 'error'); event.target.value = ''; return; }
  const label = event.target.closest('label'); label.classList.add('busy');
  try { const book = await api.upload(file); state.books = [book, ...state.books.filter(item => item.id !== book.id)]; renderBooks(); await selectBook(book.id); toast('PDF 已导入，可以先浏览原稿。', 'success'); }
  catch (error) { showError(error); }
  finally { label.classList.remove('busy'); event.target.value = ''; }
});

$('#job-toggle').addEventListener('click', () => { const form = $('#job-form'); form.hidden = !form.hidden; $('#job-toggle').setAttribute('aria-expanded', String(!form.hidden)); });
$('#all-pages').addEventListener('change', event => { $('#page-range').disabled = event.target.checked; if (event.target.checked) $('#page-range').value = ''; else { const end = Math.min(20, state.book?.totalPages || 20); $('#page-range').value = end > 1 ? `1-${end}` : '1'; } });
$('#provider-options').addEventListener('change', updateProviderNote);
$('#qwen-assist').addEventListener('change', event => { assistPreference = event.target.checked; });
$('#split-spreads').addEventListener('change', event => { splitSpreadsPreference = event.target.checked; });
$('#job-form').addEventListener('submit', async event => {
  event.preventDefault(); if (!state.book) return;
  const form = new FormData(event.currentTarget); const all = $('#all-pages').checked; const pages = all ? 'all' : String(form.get('pages') || '');
  if (!all && !validateRange(pages, state.book.totalPages)) { toast(`请输入 1 到 ${state.book.totalPages} 页内的页码，例如 1-20 或 1,3,25。`, 'error'); $('#page-range').focus(); return; }
  const provider = selectedProvider(); if (!provider) { toast('请选择可用的识别方式。', 'error'); return; }
  const assist = $('#qwen-assist').checked && !$('#qwen-assist').disabled;
  const providerConfig = state.config?.providers?.find(item => item.id === provider);
  const providerLabel = providerConfig?.label || provider;
  const quotaSource = providerQuotaSource(providerConfig);
  const providerTarget = quotaSource
    ? `${providerLabel}（额度来源：${quotaSource}；仅表示认证账户通道，不代表余额）`
    : providerLabel;
  const assistLabel = state.config?.qwenAssist?.model || 'Qwen3.8-Max';
  const cloudTargets = assist ? `${providerTarget}，并额外发送给阿里云 ${assistLabel} 做结构整理与疑点建议（另计阿里云账户用量）` : providerTarget;
  if (provider !== 'local' && !window.confirm(`所选页面图片将发送给 ${cloudTargets}。所选 paddle 系通道额度不足时会按可用情况自动改用同系另一通道并注明。自动结果会直接生成横排阅读稿，但不保证无误，确定开始吗？`)) return;
  if (form.get('force') && !window.confirm('这会替换所选页已有的识别和手工校对结果，确定重新识别吗？')) return;
  const button = event.currentTarget.querySelector('button[type="submit"]'); setBusy(button, true, '正在创建任务…');
  try { const job = await api.startJob(state.book.id, { pages, provider, assist, layout: form.get('layout'), splitSpreads: form.get('splitSpreads') === 'on', force: form.get('force') === 'on' }); renderJob(job); toast('处理任务已开始。', 'success'); }
  catch (error) { showError(error); }
  finally { setBusy(button, false); button.disabled = Boolean(state.pollTimer); }
});
$('#cancel-job').addEventListener('click', async () => { if (!state.book || !window.confirm('取消会停止本地处理或等待，但不能保证撤销已提交的远端任务或计费；已经完成的页面会保留。确定继续吗？')) return; try { renderJob(await api.cancelJob(state.book.id)); toast('已请求取消任务。'); } catch (error) { showError(error); } });

$$('[data-view]').forEach(button => button.addEventListener('click', () => { state.view = button.dataset.view; renderCurrent(); saveReadingPosition(); }));
$('#focus-toggle').addEventListener('click', () => { state.focus = !state.focus; closeDrawers(); renderCurrent(false); saveReadingPosition(); });
$('#script-toggle').addEventListener('click', () => { state.script = state.script === 'simplified' ? 'original' : 'simplified'; renderCurrent(); saveReadingPosition(); });
$('#font-size').addEventListener('input', event => { state.fontSize = Number(event.target.value); $('#font-output').value = state.fontSize; renderCurrent(false); saveReadingPosition(); });
$('#line-height').addEventListener('input', event => { state.lineHeight = Number(event.target.value); $('#line-output').value = state.lineHeight; renderCurrent(false); saveReadingPosition(); });
$('#prev-page').addEventListener('click', () => goToPage(state.currentPage - 1)); $('#next-page').addEventListener('click', () => goToPage(state.currentPage + 1));
$('#jump-form').addEventListener('submit', event => { event.preventDefault(); commitPageInput(); }); $('#page-jump').addEventListener('change', commitPageInput);
$('#reading-progress-range').addEventListener('input', event => renderReadingProgress(Number(event.target.value)));
$('#reading-progress-range').addEventListener('change', event => goToPage(Number(event.target.value)));
$('#bookmark-button').addEventListener('click', () => { if (!state.book) return; const pages = getBookmarks(state.book.id); const found = pages.indexOf(state.currentPage); found >= 0 ? pages.splice(found, 1) : pages.push(state.currentPage); setBookmarks(state.book.id, pages); renderBookmarkButton(); renderBookmarks(); });

$$('[data-left-tab]').forEach(button => button.addEventListener('click', () => { $$('[data-left-tab]').forEach(tab => { const active = tab === button; tab.classList.toggle('active', active); tab.setAttribute('aria-selected', String(active)); }); $$('[data-left-panel]').forEach(panel => { panel.hidden = panel.dataset.leftPanel !== button.dataset.leftTab; }); }));
$('#search-form').addEventListener('submit', async event => {
  event.preventDefault(); if (!state.book) return; const query = $('#search-input').value.trim(); if (!query) return;
  $('#search-status').textContent = '正在搜索…'; $('#search-results').replaceChildren();
  try { const results = await api.search(state.book.id, query); $('#search-status').textContent = results.length ? `${results.length} 处命中` : '没有找到。可切换繁简字词再试。'; results.forEach(result => { const li = document.createElement('li'); const button = document.createElement('button'); button.type = 'button'; button.className = 'search-result'; const page = document.createElement('strong'); page.textContent = `第 ${result.pageNumber} 页`; const text = document.createElement('span'); text.textContent = result.text; button.append(page, text); button.addEventListener('click', async () => { const navigated = await goToPage(result.pageNumber); if (!navigated || state.currentPage !== result.pageNumber || !state.page) return; state.selectedBlockId = result.blockId; renderReview(); syncOverlays(); }); li.append(button); $('#search-results').append(li); }); }
  catch (error) { $('#search-status').textContent = ''; showError(error); }
});

$('#uncertain-only').addEventListener('change', renderReview); $('#add-text').addEventListener('click', () => startDrawing('text')); $('#add-figure').addEventListener('click', () => startDrawing('figure'));
$('#mark-reviewed').addEventListener('change', event => { state.reviewedDraft = event.target.checked; markDirty(); });
$('#save-page').addEventListener('click', async () => {
  if (!state.book || !state.page) return; const button = $('#save-page'); setBusy(button, true, '保存中…');
  // R07：提交时捕获快照与序号；2xx 后即使草稿已推进，也更新已确认 revision，下次用新基线提交
  const savedBookId = state.book.id, savedPage = state.currentPage, submittedVersion = editVersion;
  const submittedRevision = state.page?.revision ?? null;
  try {
    const wasReviewed = Boolean(state.page.reviewed);
    const page = await api.savePage(savedBookId, savedPage, { blocks: state.blocks, reviewed: $('#mark-reviewed').checked, revision: submittedRevision });
    void refreshOutline(savedBookId);
    if (state.book?.id !== savedBookId || state.currentPage !== savedPage) {
      // 切书后到达的响应：只更新原书缓存，不渲染到当前书
      state.pageCache.set(savedPage, page);
      toast('原页面的校对已保存。', 'success');
      return;
    }
    // 本次提交已确认：无论草稿是否推进，先采用新的服务端基线
    state.pageCache.set(savedPage, page);
    if (state.page) state.page.revision = page.revision;
    if (editVersion !== submittedVersion) {
      // 保存期间的新草稿保留，仍标记未保存；下一次用新 revision 提交
      state.dirty = true;
      $('#save-page').disabled = false;
      toast(`已保存到版本 ${page.revision ?? '最新'}；保存期间的新修改仍保留，请再次保存。`);
      return;
    }
    state.page = page; state.blocks = cloneBlocks(page.blocks); state.dirty = false; state.reviewedDraft = Boolean(page.reviewed);
    if (wasReviewed !== Boolean(page.reviewed)) state.book.reviewedPages = Math.max(0, Number(state.book.reviewedPages || 0) + (page.reviewed ? 1 : -1));
    const summary = summaryFor(state.currentPage); if (summary) { summary.status = page.status; summary.blockCount = page.blocks.length; summary.uncertainCount = page.blocks.filter(b => b.uncertain).length; summary.reviewed = page.reviewed; summary.title = pageTitle(page); }
    state.books = state.books.map(book => book.id === state.book.id ? state.book : book);
    renderBooks(); renderBookMeta(); renderCurrent(); toast('整页校对已保存，搜索与目录将使用新内容。', 'success');
  } catch (error) {
    if (error?.status === 409) {
      // 真冲突：保留本地草稿不覆盖，把远端版本取回供比较，不擅自加一重试
      try {
        const remote = await api.page(savedBookId, savedPage);
        state.pageCache.set(savedPage, remote);
        showError(new Error(`${error.message}（远端已到版本 ${remote?.revision ?? '未知'}）。本地草稿已保留，请刷新对比后再保存。`));
      } catch (_) {
        showError(error);
      }
      return;
    }
    showError(error);
  }
  finally { setBusy(button, false); button.disabled = !state.dirty; }
});

$('#export-button').addEventListener('click', () => {
  if (!state.book) return; const pending = state.book.totalPages - (state.book.processedPages || 0); const unreviewed = (state.book.processedPages || 0) - (state.book.reviewedPages || 0);
  if ((pending || unreviewed) && !window.confirm(`导出中将包含 ${pending} 个未处理页、${Math.max(0, unreviewed)} 个待校对页，并明确标注状态。仍要导出吗？`)) return;
  window.location.assign(api.exportUrl(state.book.id));
});

$('#toc-toggle').addEventListener('click', () => openDrawer('toc')); $('#review-toggle').addEventListener('click', () => openDrawer('review')); $('#close-review').addEventListener('click', closeDrawers); $('#drawer-scrim').addEventListener('click', closeDrawers);
$('#reader').addEventListener('scroll', () => { window.clearTimeout(scrollTimer); scrollTimer = window.setTimeout(saveReadingPosition, 180); });
window.addEventListener('beforeunload', event => { if (state.dirty) { event.preventDefault(); event.returnValue = ''; } });
window.addEventListener('keydown', event => { if (event.key === 'Escape') closeDrawers(); if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && state.page) { event.preventDefault(); $('#save-page').click(); } });

init();
