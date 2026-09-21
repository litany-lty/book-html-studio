(() => {
  'use strict';

  const structuredSources = ['qwen-toc-recovery', 'paddle-span'];
  const visualTypes = new Set(['figure', 'table', 'formula']);
  const sentenceEnd = /[。！？!?；;：:.…](?:[”’」』】）》〕〉"']*)$/u;
  const continuationEnd = /[，、,](?:[”’」』】）》〕〉"']*)$/u;
  const continuationStart = /^[，、,]/u;
  const listStart = /^(?:[一二三四五六七八九十百]+[、.．]|[（(][一二三四五六七八九十百0-9]+[)）]|\d+[、.．、]|[•▪◦●○-]\s)/u;
  const shortLabelStart = /^[^\n：:]{1,12}[：:]/u;

  const numberedLine = /^(?:[一二三四五六七八九十百]+[、.．]|[（(][一二三四五六七八九十百0-9]+[)）]|\d+[、.．、])/u;

  function numberedMultiline(value) {
    const lines = String(value ?? '').replace(/\r\n?/g, '\n').split('\n').map(line => line.trim()).filter(Boolean);
    if (lines.length < 2) return false;
    const numbered = lines.filter(line => numberedLine.test(line)).length;
    return numbered >= 2 && numbered * 2 >= lines.length;
  }

  function parallelMultiline(value, block) {
    if (String(block?.writingMode || '') !== 'horizontal-tb') return false;
    const lines = String(value ?? '').replace(/\r\n?/g, '\n').split('\n').map(line => line.trim()).filter(Boolean);
    if (lines.length < 4 || lines.length > 13) return false;
    const heading = Array.from(lines[0]);
    if (heading.length < 2 || heading.length > 13 || !/[：:]$/u.test(lines[0])) return false;
    const entries = lines.slice(1);
    if (entries.some(line => /[，、,。！？!?；;：:]/u.test(line))) return false;
    const lengths = entries.map(line => Array.from(line).length);
    if (lengths.some(length => length < 2 || length > 18)) return false;
    return Math.max(...lengths) - Math.min(...lengths) <= 2;
  }

  // Printed contents often arrive as one OCR block. Leaders plus page labels
  // are entry boundaries, not soft scan-line wraps; keep them in both readers.
  function contentsMultiline(value) {
    const lines = String(value ?? '').replace(/\r\n?/g, '\n').split('\n').map(line => line.trim()).filter(Boolean);
    if (lines.length < 3) return false;
    const entry = /^.{1,120}?(?:[.．·•…]{2,}|[—─_-]{3,}|\s\/\s*)\s*[（(]?[0-9０-９一二三四五六七八九十百千〇零IVXLCDM]{1,10}[）)]?\s*$/iu;
    const count = lines.filter(line => entry.test(line)).length;
    return count >= 3 && count / lines.length >= .7;
  }

  function preservesLineEntries(block, value) {
    if (visualTypes.has(String(block?.type || ''))) return true;
    const source = String(block?.source || '');
    if (structuredSources.some(prefix => source.startsWith(prefix))) return true;
    const fullTexts = [block?.original, block?.simplified].filter(text => typeof text === 'string' && text);
    const structural = text => numberedMultiline(text) || parallelMultiline(text, block) || contentsMultiline(text);
    return fullTexts.some(structural) || structural(value);
  }

  function bridge(left, right) {
    const before = left.trimEnd();
    const after = right.trimStart();
    if (/[A-Za-z0-9][,;:]?$/u.test(before) && /^[A-Za-z0-9]/u.test(after)) return ' ';
    return '';
  }

  function normalizeText(value, block) {
    let text = String(value ?? '').replace(/\r\n?/g, '\n');
    if (!visualTypes.has(String(block?.type || ''))) {
      // Paddle may encode underlining and circled footnotes as TeX in prose.
      // Decode only complete, allowlisted formatting wrappers, never equations.
      // This runs after issue ranges are sliced: stored text/offsets stay intact.
      text = text.replace(/\$\s*\\(?:underline|uwave)\s*\{\s*\\text\s*\{([^{}\n]*)\}\s*\}\s*\$/gu, '$1')
        .replace(/\$\s*\^\s*\{([①-⑳㉑-㉟㊱-㊿])\}\s*\$/gu, '$1');
    }
    if (!text || preservesLineEntries(block, text)) return text;
    return text.split(/(\n{2,})/u).map(part => {
      if (/^\n{2,}$/u.test(part)) return '\n\n';
      const lines = part.split('\n');
      return lines.reduce((joined, line, index) => index === 0
        ? line
        : `${joined.trimEnd()}${bridge(joined, line)}${line.trimStart()}`, '');
    }).join('');
  }

  function appendText(container, value, block) {
    const parts = normalizeText(value, block).split('\n');
    parts.forEach((part, index) => {
      if (index) container.append(document.createElement('br'));
      if (part) container.append(document.createTextNode(part));
    });
  }

  function makeActivatable(element, label, activate) {
    element.tabIndex = 0;
    element.setAttribute('role', 'button');
    element.setAttribute('aria-label', label);
    element.addEventListener('click', activate);
    element.addEventListener('keydown', event => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      activate();
    });
    return element;
  }

  function displayIssueText(issue, sourceText, script = 'simplified') {
    const source = String(sourceText ?? '');
    if (issue?.resolved) return issue.replacement == null ? source : String(issue.replacement);
    if (script === 'original') return source;
    if (typeof issue?.inferredText === 'string' && issue.inferredText.trim()) return issue.inferredText;
    return issue?.kind === 'unreadable' ? '□' : source;
  }

  function issueTextParts(issue, sourceText, script = 'simplified') {
    const displayed = displayIssueText(issue, sourceText, script);
    const result = { displayed, prefix: '', focus: displayed, suffix: '', focused: false };
    if (script === 'original' || issue?.resolved || typeof issue?.inferredText !== 'string'
        || !issue.inferredText.trim() || sourceText === displayed) return result;
    const sourcePoints = Array.from(String(sourceText ?? ''));
    const displayPoints = Array.from(displayed);
    let left = 0;
    while (left < sourcePoints.length && left < displayPoints.length && sourcePoints[left] === displayPoints[left]) left++;
    let right = 0;
    while (right < sourcePoints.length - left && right < displayPoints.length - left
        && sourcePoints[sourcePoints.length - 1 - right] === displayPoints[displayPoints.length - 1 - right]) right++;
    const sourceFocused = sourcePoints.slice(left, sourcePoints.length - right).join('');
    const focused = displayPoints.slice(left, displayPoints.length - right).join('');
    if (!sourceFocused || !focused) return result;
    return {
      displayed,
      prefix: displayPoints.slice(0, left).join(''),
      focus: focused,
      suffix: right ? displayPoints.slice(displayPoints.length - right).join('') : '',
      focused: true
    };
  }

  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  };
  let inspectorDialog;
  let restoreState;
  let skipRestore = false;

  function scrollState(trigger) {
    const targets = [];
    for (let node = trigger?.parentElement; node; node = node.parentElement) {
      if (node.scrollHeight > node.clientHeight || node.scrollWidth > node.clientWidth) {
        targets.push({ node, top: node.scrollTop, left: node.scrollLeft });
      }
    }
    return { trigger, targets, x: window.scrollX, y: window.scrollY };
  }

  function restoreReadingPosition(state) {
    if (!state) return;
    requestAnimationFrame(() => {
      state.trigger?.focus?.({ preventScroll: true });
      state.targets.forEach(({ node, top, left }) => { node.scrollTop = top; node.scrollLeft = left; });
      window.scrollTo?.({ left: state.x, top: state.y, behavior: 'auto' });
    });
  }

  let evidenceFetcher = null;
  let openIssueId = null;

  function setEvidenceFetcher(fetcher) { evidenceFetcher = typeof fetcher === 'function' ? fetcher : null; }

  function ensureInspector() {
    if (inspectorDialog) return inspectorDialog;
    inspectorDialog = el('dialog', 'issue-inspector');
    inspectorDialog.setAttribute('aria-label', '查看文字依据');
    inspectorDialog.addEventListener('keydown', event => event.stopPropagation());
    inspectorDialog.addEventListener('cancel', event => { event.preventDefault(); inspectorDialog.close(); });
    inspectorDialog.addEventListener('close', () => {
      openIssueId = null;
      if (!skipRestore) restoreReadingPosition(restoreState);
      skipRestore = false;
      restoreState = null;
    });
    document.body.append(inspectorDialog);
    return inspectorDialog;
  }

  function safeAssetUrl(value) {
    if (typeof value !== 'string' || !value) return '';
    try {
      const url = new URL(value, document.baseURI);
      if (['http:', 'https:', 'file:', 'blob:'].includes(url.protocol)) return value;
      if (url.protocol === 'data:' && /^data:image\/(?:png|jpeg|webp);/iu.test(value)) return value;
    } catch (_) { /* Invalid evidence paths are shown as unavailable. */ }
    return '';
  }

  function evidenceImage(src, alt, className) {
    const safeSrc = safeAssetUrl(src);
    if (!safeSrc) return null;
    const link = el('a', className);
    link.href = safeSrc; link.target = '_blank'; link.rel = 'noopener'; link.title = '打开原图放大查看';
    const image = el('img'); image.src = safeSrc; image.alt = alt;
    link.append(image);
    return link;
  }

  function normalizedBox(value) {
    const box = Array.isArray(value) ? value : value?.bbox;
    if (!Array.isArray(box) || box.length < 4) return null;
    const numbers = box.slice(0, 4).map(Number);
    return numbers.every(Number.isFinite) && numbers[2] > 0 && numbers[3] > 0 ? numbers : null;
  }

  function contextBoxes(entry) {
    if (entry?.mode !== 'glyphs') return [];
    const context = normalizedBox(entry?.contextBbox);
    if (!context || !Array.isArray(entry?.boxes)) return [];
    const [cx, cy, cw, ch] = context;
    return entry.boxes.map(normalizedBox).filter(Boolean).map(([x, y, width, height]) => {
      const left = Math.max(x, cx), top = Math.max(y, cy);
      const right = Math.min(x + width, cx + cw), bottom = Math.min(y + height, cy + ch);
      if (right <= left || bottom <= top) return null;
      return {
        left: (left - cx) / cw * 100,
        top: (top - cy) / ch * 100,
        width: (right - left) / cw * 100,
        height: (bottom - top) / ch * 100
      };
    }).filter(Boolean);
  }

  function contextEvidence(entry) {
    const safeContextSrc = safeAssetUrl(entry?.contextSrc);
    const safeFallbackSrc = safeAssetUrl(entry?.src);
    const src = safeContextSrc || safeFallbackSrc;
    if (!src) return null;
    const viewport = el('div', 'issue-context-stage');
    const link = el('a', 'issue-context-canvas');
    link.href = src; link.target = '_blank'; link.rel = 'noopener'; link.title = '打开原图放大查看';
    const image = el('img'); image.src = src;
    image.alt = safeContextSrc ? '文字所在原稿整行或竖列' : '原稿定位片段';
    link.append(image);
    const targets = contextBoxes(entry).map((box, index) => {
      const marker = el('span', 'issue-context-box');
      marker.setAttribute('aria-hidden', 'true');
      marker.style.left = `${box.left}%`; marker.style.top = `${box.top}%`;
      marker.style.width = `${box.width}%`; marker.style.height = `${box.height}%`;
      marker.dataset.issueBox = String(index + 1);
      link.append(marker);
      return marker;
    });
    viewport.append(link);
    return { viewport, image, targets, hasContext: Boolean(safeContextSrc) };
  }

  function centerContextTarget(contextView) {
    const { viewport, targets } = contextView || {};
    const target = targets?.[0];
    if (!viewport || !target) return;
    const targetLeft = target.offsetLeft + target.offsetWidth / 2;
    const targetTop = target.offsetTop + target.offsetHeight / 2;
    viewport.scrollLeft = Math.max(0, targetLeft - viewport.clientWidth / 2);
    viewport.scrollTop = Math.max(0, targetTop - viewport.clientHeight / 2);
  }

  function inspectIssue(options, trigger = document.activeElement) {
    const dialog = ensureInspector();
    if (dialog.open) { skipRestore = true; dialog.close(); }
    restoreState = scrollState(trigger);
    const { page, block, issue, sourceText = '', script = 'simplified', pageImageSrc, onEdit } = options;
    const entry = page?.issueImages?.[issue?.id];
    openIssueId = issue?.id || null;
    const header = el('header', 'issue-inspector-header');
    const heading = el('h2', '', '文字依据');
    const close = el('button', 'button quiet', '关闭'); close.type = 'button'; close.addEventListener('click', () => dialog.close());
    header.append(heading, close);

    const evidence = el('section', 'issue-inspector-evidence');
    evidence.append(el('h3', '', '原稿上下文'));
    const contextView = contextEvidence(entry);
    const simplifiedText = String(block?.simplified ?? '');
    const simplifiedStart = Number(issue?.simplifiedStart ?? issue?.start ?? 0);
    const simplifiedEnd = Number(issue?.simplifiedEnd ?? issue?.end ?? 0);
    const simplifiedSource = simplifiedStart >= 0 && simplifiedEnd > simplifiedStart && simplifiedEnd <= simplifiedText.length
      ? simplifiedText.slice(simplifiedStart, simplifiedEnd) : String(sourceText ?? '');
    const likelyParts = issueTextParts(issue, simplifiedSource, 'simplified');
    const hasCandidate = typeof issue?.inferredText === 'string' && Boolean(issue.inferredText.trim());
    const glyphCount = Number(entry?.glyphCount);
    const focusedGlyphsMatch = entry?.mode === 'glyphs' && likelyParts.focused && Number.isInteger(glyphCount)
      && glyphCount === Array.from(likelyParts.focus).length
      && contextView?.targets.length === Array.from(likelyParts.focus).length;
    const likely = hasCandidate ? (focusedGlyphsMatch ? likelyParts.focus : issue.inferredText) : (simplifiedSource || sourceText || '□');
    const evidenceRow = el('div', `issue-evidence-primary ${entry?.mode || 'missing'}`);
    if (contextView) evidenceRow.append(contextView.viewport);
    else evidence.append(el('p', 'issue-evidence-note', '当前没有独立原稿截图，请通过整页原稿核对。'));
    const likelyText = el('span', 'issue-evidence-likely', `（${likely}）`);
    likelyText.title = hasCandidate ? '最可能文字，尚未确认' : '未提供候选，括号内为当前 OCR';
    likelyText.setAttribute('aria-label', hasCandidate ? `最可能文字，尚未确认：${likely}` : `候选不可得，当前 OCR：${likely}`);
    evidenceRow.append(likelyText); evidence.append(evidenceRow);
    if (contextView && !contextView.hasContext) evidence.append(el('p', 'issue-evidence-note', '暂无整行或竖列截图，当前显示定位片段。'));
    // 阶段2：正文仅附带轻量索引，精确字框点击后按需加载；摘要态不谎称“只到行或段”
    if (entry?.pending) evidence.append(el('p', 'issue-evidence-note', '正在加载精确字框…'));
    else if (entry?.mode === 'region') evidence.append(el('p', 'issue-evidence-note', '定位只到行或段，不能精确对应单个字；未绘制疑字框，请结合上下文核对。'));
    else if (contextView?.hasContext && !contextView.targets.length) evidence.append(el('p', 'issue-evidence-note', '当前截图没有可靠的逐字坐标，未绘制疑字框。'));
    if (hasCandidate && entry?.mode === 'glyphs' && likelyParts.focused && !focusedGlyphsMatch) evidence.append(el('p', 'issue-evidence-note', '截图范围与最小差异长度不一致，括号内保守显示完整候选。'));
    else if (hasCandidate && !focusedGlyphsMatch && simplifiedSource !== issue.inferredText) evidence.append(el('p', 'issue-evidence-note', '候选按整个疑点片段显示，未缩小到单字。'));
    if (!hasCandidate) evidence.append(el('p', 'issue-evidence-note', '未提供候选，括号内为当前 OCR。'));
    const text = el('section', 'issue-inspector-text');
    const original = String(block?.original ?? '').slice(Number(issue?.start || 0), Number(issue?.end || 0));
    const showingCandidate = script !== 'original' && !issue?.resolved && typeof issue?.inferredText === 'string' && issue.inferredText.trim();
    if (!(contextView && hasCandidate)) {
      text.append(el('span', 'issue-inspector-label', showingCandidate ? '当前候选（未确认）' : '待核对文字'),
        el('p', 'issue-inspector-current', displayIssueText(issue, sourceText, script)));
    }
    const details = el('details', 'issue-inspector-details');
    details.append(el('summary', '', '识别详情'));
    const detailBody = el('div', 'issue-inspector-detail-body');
    detailBody.append(el('span', 'issue-inspector-label', '原始 OCR'), el('p', 'issue-inspector-ocr', original));
    if (issue?.inferredText) detailBody.append(el('span', 'issue-inspector-label', '候选（未确认）'), el('p', 'issue-inspector-candidate', issue.inferredText));
    if (issue?.reason) detailBody.append(el('p', 'issue-inspector-reason', issue.reason));
    const safePageImage = safeAssetUrl(pageImageSrc);
    if (safePageImage) {
      const pageLink = el('a', 'issue-page-link', '打开整页原稿');
      pageLink.href = safePageImage; pageLink.target = '_blank'; pageLink.rel = 'noopener';
      detailBody.append(pageLink);
    }
    details.append(detailBody); text.append(details);

    const context = el('section', 'issue-inspector-context');
    const exactImage = evidenceImage(entry?.src, entry?.mode === 'glyphs' ? '原稿对应字或词组小图' : '原稿定位片段', `issue-evidence-image ${entry?.mode || ''}`);
    if (exactImage && contextView?.hasContext) {
      const details = el('details');
      details.append(el('summary', '', '查看字词小图'), exactImage);
      context.append(details);
    } else if (!exactImage) context.append(el('p', 'issue-evidence-note', '暂无独立字词小图。'));

    const footer = el('footer', 'issue-inspector-actions');
    const done = el('button', 'button', '返回阅读'); done.type = 'button'; done.addEventListener('click', () => dialog.close()); footer.append(done);
    if (typeof onEdit === 'function') {
      const edit = el('button', 'button primary', '修改'); edit.type = 'button';
      edit.addEventListener('click', () => { skipRestore = true; dialog.close(); onEdit(block?.id, issue?.id); });
      footer.append(edit);
    }
    dialog.replaceChildren(header, evidence, text, context, footer);
    dialog.showModal();
    // 阶段2：摘要态按需升级为精确证据；失败只影响证据面板，不阻断阅读
    if (entry?.pending && typeof evidenceFetcher === 'function' && issue?.id) {
      const loadingFor = issue.id;
      evidenceFetcher({ page, issueId: loadingFor }).then(precise => {
        if (!precise || !dialog.open || openIssueId !== loadingFor) return;
        inspectIssue({ ...options }, trigger);
      }).catch(() => {});
    }
    if (contextView) contextView.image.addEventListener('load', () => requestAnimationFrame(() => centerContextTarget(contextView)), { once: true });
    requestAnimationFrame(() => {
      close.focus({ preventScroll: true });
      centerContextTarget(contextView);
    });
  }

  function appendIssueText(container, options) {
    const { block, issue, sourceText, script = 'simplified' } = options;
    const { displayed, prefix, focus: markerText, suffix } = issueTextParts(issue, sourceText, script);
    if (prefix) appendText(container, prefix, block);
    const marker = el('span', `content-issue pending ${issue?.kind === 'unreadable' ? 'unreadable' : 'suspected'}`);
    appendText(marker, markerText, block);
    marker.title = '文字待核对，点击查看原稿依据';
    makeActivatable(marker, `待核对文字：${markerText || displayed || '空白'}。点击查看原稿依据`, () => inspectIssue(options, marker));
    container.append(marker);
    if (suffix) appendText(container, suffix, block);
    return marker;
  }

  // J08/12.1：语言脚本与证据状态拆成两个维度。
  // 文字体系（原文/简体）只决定用哪套转录；阅读依据决定是否显示未确认推荐。
  // 默认保真阅读（confirmed）：未确认推测绝不进入正文；辅助阅读（assisted）显示
  // 首选候选并带显式标记，resolved 仍为 false，不改 original，不写回 inferredText。
  // 返回 {text, provenance: SOURCE|CONFIRMED|ASSISTED, unresolved}。
  function resolveReadingText(issue, sourceText, simplifiedText, script, evidence) {
    const source = String(script === 'original' ? (sourceText ?? '') : (simplifiedText ?? sourceText ?? ''));
    if (issue?.resolved) {
      return { text: issue.replacement == null ? source : String(issue.replacement), provenance: 'CONFIRMED', unresolved: false };
    }
    const recommendation = evidence && evidence.mode === 'assisted' && typeof evidence.recommendation === 'string' && evidence.recommendation
      ? evidence.recommendation : null;
    if (recommendation) return { text: recommendation, provenance: 'ASSISTED', unresolved: true };
    return { text: source, provenance: 'SOURCE', unresolved: true };
  }

  function appendConfirmedIssueText(container, options) {
    const { block, issue, sourceText, script = 'simplified' } = options;
    const marker = el('span', `content-issue pending ${issue?.kind === 'unreadable' ? 'unreadable' : 'suspected'}`);
    appendText(marker, String(sourceText ?? ''), block);
    marker.title = '文字待核对，点击查看原稿依据';
    makeActivatable(marker, `待核对文字：${String(sourceText ?? '') || '空白'}。点击查看原稿依据`, () => inspectIssue(options, marker));
    container.append(marker);
    return marker;
  }

  function appendAssistedIssueText(container, options) {
    const { block, issue, sourceText, recommendation } = options;
    const marker = el('span', `content-issue pending assist ${issue?.kind === 'unreadable' ? 'unreadable' : 'suspected'}`);
    appendText(marker, String(recommendation ?? sourceText ?? ''), block);
    marker.title = '首选候选，尚未确认，点击查看来源与限制';
    marker.setAttribute('data-unconfirmed', '1');
    makeActivatable(marker, `首选候选尚未确认：${String(recommendation ?? '')}。点击查看来源与限制`, () => inspectIssue(options, marker));
    container.append(marker);
    return marker;
  }

  function canJoin(previous, current, previousText, currentText) {
    if (!previous || !current || previous.type !== 'text' || current.type !== 'text') return false;
    const before = String(previousText ?? '').trim();
    const after = String(currentText ?? '').trim();
    if (preservesLineEntries(previous, before) || preservesLineEntries(current, after)) return false;
    if (!before || !after || /\n\s*\n/u.test(before) || /\n\s*\n/u.test(after)) return false;
    if (listStart.test(after) || shortLabelStart.test(after) || sentenceEnd.test(before)) return false;
    if (continuationEnd.test(before) || continuationStart.test(after)) return true;
    if (previous.writingMode !== 'vertical-rl' || current.writingMode !== 'vertical-rl') return false;
    const previousBox = previous.bbox, currentBox = current.bbox;
    if (!Array.isArray(previousBox) || previousBox.length < 4 || !Array.isArray(currentBox) || currentBox.length < 4) return false;
    const previousCenter = Number(previousBox[0]) + Number(previousBox[2]) / 2;
    const currentCenter = Number(currentBox[0]) + Number(currentBox[2]) / 2;
    return Number.isFinite(previousCenter) && Number.isFinite(currentCenter) && previousCenter >= .5 && currentCenter < .5;
  }

  globalThis.BookReadingLayout = Object.freeze({ appendIssueText, appendConfirmedIssueText, appendAssistedIssueText, resolveReadingText, appendText, canJoin, displayIssueText, inspectIssue, issueTextParts, normalizeText, numberedMultiline, parallelMultiline, preservesLineEntries, setEvidenceFetcher });
})();
