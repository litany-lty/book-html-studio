import { api } from './api.js';

const textual = new Set(['text', 'heading', 'advertisement', 'caption', 'page-number']);
const pictured = new Set(['figure', 'table', 'formula']);
let fitObserver;

const readingLayout = globalThis.BookReadingLayout;

function fitFacsimile(stage) {
  const fit = () => {
    stage.querySelectorAll('.facsimile-block').forEach(element => {
      const span = element.firstElementChild;
      const vertical = element.style.writingMode === 'vertical-rl';
      const cross = vertical ? element.clientWidth : element.clientHeight;
      let low = 2, high = Math.min(40, Math.max(3, cross / 1.08));
      for (let i = 0; i < 12; i++) {
        const mid = (low + high) / 2;
        span.style.fontSize = `${mid}px`;
        if (span.scrollHeight > span.clientHeight + 1 || span.scrollWidth > span.clientWidth + 1) high = mid;
        else low = mid;
      }
      span.style.fontSize = `${low}px`;
    });
  };
  fitObserver = new ResizeObserver(fit);
  fitObserver.observe(stage);
  requestAnimationFrame(fit);
}

function blockText(block, script) {
  return script === 'original' ? (block.original || block.simplified || '') : (block.simplified || block.original || '');
}

function issueRange(issue, script) {
  const start = script === 'original' ? issue.start : (issue.simplifiedStart ?? issue.start);
  const end = script === 'original' ? issue.end : (issue.simplifiedEnd ?? issue.end);
  return [Number(start), Number(end)];
}

function appendIssueAwareText(element, block, page, script, onIssueSelect, pageImageSrc, evidence) {
  const text = blockText(block, script);
  const issues = (block.issues || []).map(issue => ({ issue, range: issueRange(issue, script) }))
    .filter(({ range }) => Number.isInteger(range[0]) && Number.isInteger(range[1]) && range[0] >= 0 && range[1] > range[0] && range[1] <= text.length)
    .sort((a, b) => a.range[0] - b.range[0] || a.range[1] - b.range[1]);
  const assisted = evidence && evidence.mode === 'assisted';
  let cursor = 0;
  issues.forEach(({ issue, range: [start, end] }) => {
    if (start < cursor) return;
    readingLayout.appendText(element, text.slice(cursor, start), block);
    const sourceText = text.slice(start, end);
    const candidate = (evidence?.assistMap && evidence.assistMap[issue.id]?.text)
      || (typeof issue.inferredText === 'string' && issue.inferredText.trim() ? issue.inferredText.trim() : null);
    if (issue.resolved) {
      const resolved = document.createElement('span');
      resolved.className = 'content-issue resolved';
      readingLayout.appendText(resolved, readingLayout.displayIssueText(issue, sourceText, script), block);
      element.append(resolved);
    } else if (candidate && (assisted || (script !== 'original' && issue.inferredText))) {
      // 辅助阅读：优先显示上下文更准确的候选词（如 Qwen 纠正的词语），带未确认标记；resolved 仍为 false，点击可核对原稿/采纳
      readingLayout.appendAssistedIssueText(element, {
        page, block, issue, sourceText, script, pageImageSrc,
        recommendation: candidate,
        onEdit: () => onIssueSelect?.(block.id, issue.id)
      });
    } else {
      // 保真阅读（默认）：未确认推测不进入正文，只显示原始转录
      readingLayout.appendConfirmedIssueText(element, {
        page, block, issue, sourceText, script, pageImageSrc,
        onEdit: () => onIssueSelect?.(block.id, issue.id)
      });
    }
    cursor = end;
  });
  readingLayout.appendText(element, text.slice(cursor), block);
}

function originalImage(book, page, className = '') {
  const img = document.createElement('img');
  img.src = api.pageImage(book.id, page.pageNumber);
  img.alt = `第 ${page.pageNumber} 页原稿`;
  img.className = className;
  img.draggable = false;
  return img;
}

export function statusMessage(page) {
  if (!page) return '';
  if (page.status === 'FAILED') return page.error || '本页暂未生成文字，可以先读原稿。';
  if (page.status === 'PROCESSING') {
    if (page.isReprocessing || (page.blocks?.length > 0)) {
      return '正在重新处理，保留当前内容。';
    }
    return '正在识别本页，原稿可以继续阅读。';
  }
  if (page.status !== 'READY') return '本页尚未识别，原稿保留，可随时对照。';
  // U1：空白/纯图页使用轻量空态，不误报为识别失败，不诱导付费重试。
  if (!page.blocks?.length) return '本页为空白页或仅含插图，原稿保留，可切换原稿查看。';
  // U1：默认阅读不拼出长技术条；多条提示只给一句话，详情进入校对/诊断。
  // 处理追溯（通道/版式/PDF 指纹）是技术信息，只进诊断详情，不占阅读首屏。
  const actionableWarnings = (page.warnings || []).filter(warning => !/尚未人工校对|自动结果仍需核对原图|自动原图复核候选|处理追溯/u.test(warning));
  if (actionableWarnings.length > 3) return '有多条版面提示，可在校对详情查看。';
  if (actionableWarnings.length) return `版面提示：${actionableWarnings.join('；')}`;
  return '';
}

export function qualityOf(page) {
  if (!page || page.status !== 'READY') {
    if (page?.status === 'PROCESSING') {
      const isReproc = page.isReprocessing || (page.blocks?.length > 0);
      return {
        label: isReproc ? '正在重新处理' : '正在识别本页',
        tone: 'processing',
        detail: ''
      };
    }
    return { label: page?.status === 'FAILED' ? '本页暂未生成文字' : '原稿保留，可随时对照', tone: 'pending', detail: '' };
  }
  const blocks = page.blocks || [];
  const uncertain = blocks.filter(block => block.uncertain || (block.confidence != null && block.confidence < .75)).length;
  const unresolvedIssues = blocks.flatMap(block => block.issues || []).filter(issue => !issue.resolved).length;
  const scored = blocks.filter(block => block.confidence != null);
  const average = scored.length ? scored.reduce((sum, block) => sum + Number(block.confidence), 0) / scored.length : null;
  const reviewed = page.reviewed;
  const provider = page.provider ? ` · ${page.provider}` : '';
  const warnings = page.warnings?.length ? ` · ${page.warnings.length} 条版面提示` : '';
  const detail = `${blocks.length} 个块${unresolvedIssues ? `，${unresolvedIssues} 处内容疑点未解决` : uncertain ? `，${uncertain} 个块需留意` : ''}${average == null ? '' : `，识别参考值 ${Math.round(average * 100)}%`}${provider}${warnings}`;
  // U1：默认阅读只显示轻量标签（有待核对文字）；块数/provider/参考值/提示数进入诊断详情。
  const label = reviewed ? '已人工校对' : uncertain || unresolvedIssues ? '有待核对文字' : '自动处理完成';
  const tone = reviewed ? 'reviewed' : uncertain || unresolvedIssues ? 'warning' : 'ready';
  return { label, tone, detail };
}

// U1：诊断详情保留完整技术信息（块数、参考置信度、provider、提示数），按需查看。
export function qualityDiagnostics(page) {
  const trace = (page?.warnings || []).filter(warning => /处理追溯/u.test(warning)).join('；');
  return [qualityOf(page).detail || '', trace].filter(Boolean).join(' · ');
}

function renderFacsimile(container, ctx) {
  const { book, page, blocks, script } = ctx;
  const stage = document.createElement('div');
  stage.className = 'facsimile-stage';
  stage.style.aspectRatio = `${page.width || 1} / ${page.height || 1}`;
  stage.append(originalImage(book, page, 'facsimile-background'));
  blocks.forEach(block => {
    if (!textual.has(block.type)) return;
    const [x, y, w, h] = block.bbox || [0, 0, .1, .1];
    const element = document.createElement(block.type === 'heading' ? 'h2' : 'div');
    element.className = `facsimile-block type-${block.type}${block.uncertain ? ' is-uncertain' : ''}`;
    element.dataset.blockId = block.id;
    Object.assign(element.style, { left: `${x * 100}%`, top: `${y * 100}%`, width: `${w * 100}%`, height: `${h * 100}%`, writingMode: block.writingMode || 'horizontal-tb' });
    const span = document.createElement('span');
    span.textContent = blockText(block, script);
    span.className = 'facsimile-text';
    element.append(span);
    stage.append(element);
  });
  const note = document.createElement('p');
  note.className = 'layout-note';
  note.textContent = '原版排布按识别坐标近似呈现；原稿保留在底层，插图与复杂底纹不会被重绘。';
  container.append(stage, note);
  fitFacsimile(stage);
}

const PAGE_NUM_RE = /^[-—–~～·•●*#❖◆◇■□▲△▼▽|/\\_—=\[\]()（）《》【】\s]*(?:[0-9]{1,5}|[第]?[〇零一二三四五六七八九十百千兩两廿卅]{1,8}[页頁]?)[-—–~～·•●*#❖◆◇■□▲△▼▽|/\\_—=\[\]()（）《》【】\s]*$/;
const DECORATOR_RE = /^[-—–~～·•●*#❖◆◇■□▲△▼▽★☆|/\\.,:;!?！？，。；：'\"“”‘’`·•_—=+§†‡\s]+$/;
const FOLIO_CHAR_RE = /^[0-9*#❖◆◇■□▲△▼▽·•~～\-—_—=+BboO]+$/;

function isPageNumberOrFolioSymbol(block, allBlocks) {
  if (!block) return false;
  if (block.type === 'page-number') return true;
  if (block.type !== 'text' && block.type !== 'caption') return false;
  const bbox = block.bbox;
  if (!bbox || bbox.length < 4) return false;
  const top = bbox[1], bottom = bbox[1] + bbox[3], height = bbox[3], width = bbox[2];
  const inMargin = (top <= 0.12 || bottom <= 0.14) || (top >= 0.80 || bottom >= 0.86);
  if (!inMargin || height > 0.12) return false;
  const text = (block.original || block.simplified || '').trim();
  if (!text || text.length > 24) return false;
  if (PAGE_NUM_RE.test(text) || DECORATOR_RE.test(text)) return true;
  if (text.length <= 3 && width <= 0.08 && height <= 0.08 && FOLIO_CHAR_RE.test(text)) {
    if (top >= 0.84 || bottom <= 0.08) return true;
    if (allBlocks && allBlocks.some(other => other !== block && (other.type === 'page-number' || (other.bbox && PAGE_NUM_RE.test((other.original || other.simplified || '').trim()))) && Math.abs(other.bbox[1] - top) <= 0.05)) {
      return true;
    }
  }
  return false;
}

function renderReading(container, ctx) {
  const { book, page, blocks, script, fontSize, lineHeight } = ctx;
  // U3：消费统一展示投影；未取得投影用保守兼容路径（人工块不隐藏）。
  const presentationMap = new Map();
  for (const view of (page.presentation?.blocks || [])) {
    if (view && view.blockId && !presentationMap.has(view.blockId)) presentationMap.set(view.blockId, view);
  }
  const viewOf = block => presentationMap.get(block.id);
  const hiddenByProjection = block => {
    const view = viewOf(block);
    if (!view || view.showInReading !== false) return false;
    // 人工内容保护：已校对/手工块不受阅读层隐藏。
    if (block.reviewed || block.source === 'manual') return false;
    return true;
  };
  const joinableByProjection = block => viewOf(block)?.allowJoinNext !== false;
  const flow = document.createElement('div');
  const pageImageSrc = api.pageImage(book.id, page.pageNumber);
  flow.className = 'reading-flow';
  flow.style.setProperty('--reading-size', `${fontSize}px`);
  flow.style.setProperty('--reading-leading', lineHeight);
  let previousPlain = null;
  let previousText = '';
  let paragraph = null;
  const advertisements = [];
  [...blocks].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)).forEach(block => {
    if (hiddenByProjection(block)) { previousPlain = null; paragraph = null; return; }
    if (block.type === 'advertisement') { advertisements.push(block); previousPlain = null; paragraph = null; return; }
    if (block.type === 'page-number' || isPageNumberOrFolioSymbol(block, blocks)) return;
    const text = blockText(block, script);
    if (pictured.has(block.type)) {
      previousPlain = null; paragraph = null;
      const figure = document.createElement('figure');
      figure.className = `reading-figure type-${block.type}`;
      figure.dataset.blockId = block.id;
      const img = document.createElement('img');
      // U6：按需加载图片（懒加载 + 尺寸占位在 CSS max 约束内），防布局跳动。
      img.loading = 'lazy';
      img.decoding = 'async';
      img.src = api.figureImage(book.id, page.pageNumber, block.id);
      img.alt = block.type === 'table' ? '原稿中的表格裁图' : block.type === 'formula' ? '原稿中的公式裁图' : '原稿中的插图';
      figure.append(img);
      if (text) {
        const details = document.createElement('details');
        details.className = 'visual-ocr';
        const pending = (block.issues || []).filter(issue => !issue.resolved).length;
        const summary = document.createElement('summary');
        summary.textContent = pending ? `图内识别文字（${pending} 处待核对）` : '图内识别文字（展开查看）';
        const transcript = document.createElement('p');
        appendIssueAwareText(transcript, block, page, script, ctx.onIssueSelect, pageImageSrc, ctx.evidence);
        details.append(summary, transcript);
        figure.append(details);
      }
      flow.append(figure);
      return;
    }
    if (!text) return;
    if (joinableByProjection(block) && readingLayout.canJoin(previousPlain, block, previousText, text) && paragraph) {
      appendIssueAwareText(paragraph, block, page, script, ctx.onIssueSelect, pageImageSrc, ctx.evidence);
      previousPlain = block; previousText = text;
      return;
    }
    const element = document.createElement(block.type === 'heading' ? `h${Math.min(4, Math.max(2, Number(block.headingLevel || 2)))}` : block.type === 'caption' ? 'aside' : 'p');
    element.className = `flow-${block.type}${block.uncertain ? ' is-uncertain' : ''}`;
    element.dataset.blockId = block.id;
    appendIssueAwareText(element, block, page, script, ctx.onIssueSelect, pageImageSrc, ctx.evidence);
    flow.append(element);
    previousPlain = block.type === 'text' ? block : null;
    previousText = text;
    paragraph = block.type === 'text' ? element : null;
  });
  if (!flow.children.length) {
    const empty = document.createElement('p');
    empty.className = 'flow-empty';
    empty.textContent = '本页为空白页或仅含插图，可切换原稿查看。';
    flow.append(empty);
  }
  if (advertisements.length) {
    const details = document.createElement('details');
    details.className = 'filtered-ads';
    const summary = document.createElement('summary');
    summary.textContent = `已过滤 ${advertisements.length} 处广告 · 查看`;
    const note = document.createElement('p'); note.className = 'filtered-ads-note';
    note.textContent = '仅从横排正文中收起，原识别字与原稿位置仍保留。';
    const list = document.createElement('ol');
    advertisements.forEach(block => {
      const item = document.createElement('li');
      const transcript = document.createElement('p');
      transcript.textContent = block.original || block.simplified || '（未识别到文字）';
      const locate = document.createElement('button'); locate.type = 'button'; locate.className = 'button quiet';
      locate.textContent = '在原稿定位';
      locate.addEventListener('click', () => ctx.onAdvertisementLocate?.(block.id));
      item.append(transcript, locate); list.append(item);
    });
    details.append(summary, note, list); flow.append(details);
  }
  container.append(flow);
}

export function renderPaper(container, ctx) {
  fitObserver?.disconnect();
  fitObserver = null;
  container.replaceChildren();
  const message = statusMessage(ctx.page);
  if (ctx.page.status === 'FAILED') {
    const card = document.createElement('div');
    card.className = 'reader-failed-card';
    const icon = document.createElement('div');
    icon.className = 'reader-failed-icon';
    icon.innerHTML = '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>';
    const title = document.createElement('h3');
    title.textContent = `第 ${ctx.page.pageNumber} 页转换失败`;
    const desc = document.createElement('p');
    desc.textContent = ctx.page.error || '该页面在识别或结构整理过程中遇到问题。下方已取消大面积内容占位，您可直接重试。';
    const actions = document.createElement('div');
    actions.className = 'reader-failed-actions';
    const retryBtn = document.createElement('button');
    retryBtn.type = 'button';
    retryBtn.className = 'button primary';
    retryBtn.textContent = '重新转换本页';
    retryBtn.addEventListener('click', () => ctx.onRetry?.());
    actions.append(retryBtn);

    const rawContainer = document.createElement('div');
    rawContainer.className = 'reader-failed-raw';
    rawContainer.hidden = true;
    const frame = document.createElement('div');
    frame.className = 'original-frame';
    frame.style.aspectRatio = `${ctx.page.width || 1} / ${ctx.page.height || 1}`;
    frame.append(originalImage(ctx.book, ctx.page, 'original-image'));
    rawContainer.append(frame);

    const toggleRaw = document.createElement('button');
    toggleRaw.type = 'button';
    toggleRaw.className = 'button quiet';
    toggleRaw.textContent = '查看原稿大图';
    toggleRaw.addEventListener('click', () => {
      const show = rawContainer.hidden;
      rawContainer.hidden = !show;
      toggleRaw.textContent = show ? '收起原稿大图' : '查看原稿大图';
    });
    actions.append(toggleRaw);

    card.append(icon, title, desc, actions, rawContainer);
    container.append(card);
    return message;
  }
  const hasProcessedBlocks = Array.isArray(ctx.blocks) && ctx.blocks.length > 0;
  const isReprocessing = Boolean(ctx.page?.isReprocessing || ctx.isReprocessing);

  // U6：复杂版面保真降级。正文几何缺失、顺序不可验证时整页看原稿，不拼错误正文。
  if (ctx.view === 'reading' && ctx.page?.presentation?.fallbackMode === 'PAGE_IMAGE') {
    const frame = document.createElement('div');
    frame.className = 'original-frame';
    frame.style.aspectRatio = `${ctx.page.width || 1} / ${ctx.page.height || 1}`;
    frame.append(originalImage(ctx.book, ctx.page, 'original-image'));
    const note = document.createElement('p');
    note.className = 'layout-note';
    note.textContent = '本页版式复杂，暂以原稿呈现；原稿保留，可随时对照。';
    container.append(frame, note);
    return statusMessage(ctx.page);
  }

  if (ctx.view === 'original' || (ctx.page.status !== 'READY' && !hasProcessedBlocks)) {
    const frame = document.createElement('div');
    frame.className = 'original-frame';
    frame.style.aspectRatio = `${ctx.page.width || 1} / ${ctx.page.height || 1}`;
    frame.append(originalImage(ctx.book, ctx.page, 'original-image'));
    container.append(frame);
  } else if (ctx.view === 'facsimile') {
    renderFacsimile(container, ctx);
  } else {
    renderReading(container, ctx);
  }

  if (isReprocessing || (ctx.page.status === 'PROCESSING' && hasProcessedBlocks)) {
    const progressLine = document.createElement('div');
    progressLine.className = 'reprocessing-progress-line';
    progressLine.setAttribute('role', 'progressbar');
    progressLine.setAttribute('aria-label', '正在二次处理本页');
    container.prepend(progressLine);
    container.classList.add('is-reprocessing-paper');
  } else {
    container.classList.remove('is-reprocessing-paper');
  }
  return message;
}

export function headingsFrom(summaries, pageCache) {
  const result = [];
  summaries.forEach(summary => {
    const page = pageCache.get(summary.pageNumber);
    const headings = page?.blocks?.filter(block => block.type === 'heading' && (block.simplified || block.original)) || [];
    if (headings.length) headings.sort((a, b) => (a.order ?? 0) - (b.order ?? 0)).forEach(block => result.push({ pageNumber: summary.pageNumber, text: block.simplified || block.original, level: block.headingLevel || 2 }));
    else if (summary.title) result.push({ pageNumber: summary.pageNumber, text: summary.title, level: 2 });
    else result.push({ pageNumber: summary.pageNumber, text: `第 ${summary.pageNumber} 页`, level: 0 });
  });
  return result;
}
