import { api } from './api.js';

const textual = new Set(['text', 'heading', 'caption', 'page-number']);
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

function appendIssueAwareText(element, block, page, script, onIssueSelect, pageImageSrc) {
  const text = blockText(block, script);
  const issues = (block.issues || []).map(issue => ({ issue, range: issueRange(issue, script) }))
    .filter(({ range }) => Number.isInteger(range[0]) && Number.isInteger(range[1]) && range[0] >= 0 && range[1] > range[0] && range[1] <= text.length)
    .sort((a, b) => a.range[0] - b.range[0] || a.range[1] - b.range[1]);
  let cursor = 0;
  issues.forEach(({ issue, range: [start, end] }) => {
    if (start < cursor) return;
    readingLayout.appendText(element, text.slice(cursor, start), block);
    const sourceText = text.slice(start, end);
    if (issue.resolved) {
      const resolved = document.createElement('span');
      resolved.className = 'content-issue resolved';
      readingLayout.appendText(resolved, readingLayout.displayIssueText(issue, sourceText, script), block);
      element.append(resolved);
    } else readingLayout.appendIssueText(element, {
      page, block, issue, sourceText, script, pageImageSrc,
      onEdit: () => onIssueSelect?.(block.id, issue.id)
    });
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

function statusMessage(page) {
  if (page.status === 'FAILED') return page.error || '本页转换失败。原稿仍可查看，可重新处理本页。';
  if (page.status === 'PROCESSING') return '本页正在转换，完成前先显示原稿。';
  if (page.status !== 'READY') return '本页尚未转换，当前仅显示原稿。请在“处理设置”中选择本页。';
  if (!page.blocks?.length) return '本页没有识别到内容，原稿已保留，请在校对栏手工框选。';
  const actionableWarnings = (page.warnings || []).filter(warning => !/尚未人工校对|自动结果仍需核对原图|自动原图复核候选/u.test(warning));
  if (actionableWarnings.length) return `版面提示：${actionableWarnings.join('；')}`;
  return '';
}

export function qualityOf(page) {
  if (!page || page.status !== 'READY') return { label: page?.status === 'FAILED' ? '转换失败' : '尚未转换', tone: 'pending', detail: '' };
  const blocks = page.blocks || [];
  const uncertain = blocks.filter(block => block.uncertain || (block.confidence != null && block.confidence < .75)).length;
  const unresolvedIssues = blocks.flatMap(block => block.issues || []).filter(issue => !issue.resolved).length;
  const scored = blocks.filter(block => block.confidence != null);
  const average = scored.length ? scored.reduce((sum, block) => sum + Number(block.confidence), 0) / scored.length : null;
  const reviewed = page.reviewed;
  const provider = page.provider ? ` · ${page.provider}` : '';
  const warnings = page.warnings?.length ? ` · ${page.warnings.length} 条版面提示` : '';
  const detail = `${blocks.length} 个块${unresolvedIssues ? `，${unresolvedIssues} 处内容疑点未解决` : uncertain ? `，${uncertain} 个块需留意` : ''}${average == null ? '' : `，识别参考值 ${Math.round(average * 100)}%`}${provider}${warnings}`;
  return reviewed ? { label: '已人工校对', tone: 'reviewed', detail } : uncertain || unresolvedIssues ? { label: '模型提示', tone: 'warning', detail } : { label: '自动处理完成', tone: 'ready', detail };
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
  note.textContent = '原貌 HTML 按识别坐标近似排版；原稿保留在底层，插图与复杂底纹不会被重绘。';
  container.append(stage, note);
  fitFacsimile(stage);
}

function renderReading(container, ctx) {
  const { book, page, blocks, script, fontSize, lineHeight } = ctx;
  const flow = document.createElement('div');
  const pageImageSrc = api.pageImage(book.id, page.pageNumber);
  flow.className = 'reading-flow';
  flow.style.setProperty('--reading-size', `${fontSize}px`);
  flow.style.setProperty('--reading-leading', lineHeight);
  let previousPlain = null;
  let previousText = '';
  let paragraph = null;
  [...blocks].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)).forEach(block => {
    if (block.type === 'page-number') return;
    const text = blockText(block, script);
    if (pictured.has(block.type)) {
      previousPlain = null; paragraph = null;
      const figure = document.createElement('figure');
      figure.className = `reading-figure type-${block.type}`;
      figure.dataset.blockId = block.id;
      const img = document.createElement('img');
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
        appendIssueAwareText(transcript, block, page, script, ctx.onIssueSelect, pageImageSrc);
        details.append(summary, transcript);
        figure.append(details);
      }
      flow.append(figure);
      return;
    }
    if (!text) return;
    if (readingLayout.canJoin(previousPlain, block, previousText, text) && paragraph) {
      appendIssueAwareText(paragraph, block, page, script, ctx.onIssueSelect, pageImageSrc);
      previousPlain = block; previousText = text;
      return;
    }
    const element = document.createElement(block.type === 'heading' ? `h${Math.min(4, Math.max(2, Number(block.headingLevel || 2)))}` : block.type === 'caption' ? 'aside' : 'p');
    element.className = `flow-${block.type}${block.uncertain ? ' is-uncertain' : ''}`;
    element.dataset.blockId = block.id;
    appendIssueAwareText(element, block, page, script, ctx.onIssueSelect, pageImageSrc);
    flow.append(element);
    previousPlain = block.type === 'text' ? block : null;
    previousText = text;
    paragraph = block.type === 'text' ? element : null;
  });
  if (!flow.children.length) {
    const empty = document.createElement('p');
    empty.className = 'flow-empty';
    empty.textContent = '本页没有可进入阅读流的内容，请查看原稿或在校对栏补充。';
    flow.append(empty);
  }
  container.append(flow);
}

export function renderPaper(container, ctx) {
  fitObserver?.disconnect();
  fitObserver = null;
  container.replaceChildren();
  const message = statusMessage(ctx.page);
  if (ctx.view === 'original' || ctx.page.status !== 'READY') {
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
