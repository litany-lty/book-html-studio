import { state } from './store.js';

// Focus mode is a presentation adapter. The existing toggle/view/jump handlers
// remain the only owners of rendering, draft confirmation and page requests.
export function pageAfter(page, total, direction) {
  if (![page, total].every(Number.isInteger) || total < 1 || page < 1 || page > total || ![-1, 1].includes(direction)) return null;
  const target = page + direction;
  return target >= 1 && target <= total ? target : null;
}

export function isShortTap(start, end) {
  return Boolean(start && end && start.pointerId === end.pointerId && !start.selected
    && end.time >= start.time && end.time - start.time < 500
    && Math.hypot(end.x - start.x, end.y - start.y) <= 12
    && Math.abs(end.scrollTop - start.scrollTop) <= 2);
}

const interactive = 'input, textarea, select, button, a, summary, [contenteditable]:not([contenteditable="false"]), [role="slider"], [role="textbox"], [role="button"]';

export function initFocusReading({ document: doc = document, model = state, onProofRequested = () => {} } = {}) {
  const toggle = doc.getElementById('focus-toggle');
  const reader = doc.getElementById('reader');
  const shell = doc.getElementById('reader-shell');
  const paper = doc.getElementById('paper');
  const progress = doc.getElementById('reading-progress');
  const jump = doc.getElementById('page-jump');
  const jumpForm = doc.getElementById('jump-form');
  const actions = doc.querySelector('.reader-primary-actions');
  if (!toggle || !reader || !shell || !paper || !progress || !jump || !jumpForm || !actions || doc.getElementById('focus-exit')) return;
  const win = doc.defaultView;
  const css = doc.createElement('link');
  css.rel = 'stylesheet'; css.href = new URL('./focus-reading.css', import.meta.url).href;
  doc.head.append(css);
  // Keep one entry, in the main toolbar rather than inside the settings dialog.
  actions.prepend(toggle);
  toggle.setAttribute('aria-controls', 'reader-shell');
  toggle.title = '隐藏工具栏与侧栏；点击左右边缘翻页，Esc 退出';

  const makeButton = (id, text, label) => {
    const button = doc.createElement('button');
    button.id = id; button.type = 'button'; button.textContent = text;
    button.setAttribute('aria-label', label); button.hidden = true;
    return button;
  };
  const previous = makeButton('focus-prev-page', '‹', '上一页');
  const next = makeButton('focus-next-page', '›', '下一页');
  for (const button of [previous, next]) {
    button.className = 'focus-page-zone';
    button.setAttribute('aria-controls', 'paper');
    button.title = button === previous ? '上一页（←）' : '下一页（→）';
    shell.append(button);
  }
  const exit = makeButton('focus-exit', '退出专注', '退出专注模式');
  exit.className = 'button quiet focus-exit'; exit.title = '退出专注模式（Esc）';
  exit.setAttribute('aria-keyshortcuts', 'Escape');
  progress.append(exit);
  const help = doc.createElement('p');
  help.id = 'focus-reading-help'; help.className = 'focus-reading-help'; help.hidden = true;
  help.textContent = '点击左侧空白区域上一页、右侧空白区域下一页，也可用左右方向键。长页可上下滚动；底部进度条可跳页。按 Esc 或退出专注返回工作台。';
  const empty = doc.createElement('p');
  empty.id = 'focus-page-empty'; empty.className = 'focus-page-empty'; empty.hidden = true;
  empty.setAttribute('role', 'status');
  paper.before(help, empty);

  let returnState = null;
  let transition = 0;
  let bookBefore = null;
  let positionBefore = null;
  const selected = () => Boolean(win.getSelection?.()?.toString());
  const active = () => Boolean(model.focus && model.book && !shell.hidden);
  const setClass = (name, value) => {
    if (doc.body.classList.contains(name) !== value) doc.body.classList.toggle(name, value);
  };
  function location() {
    const top = reader.getBoundingClientRect().top;
    const block = [...paper.querySelectorAll('[data-block-id]')].find(node => {
      const rect = node.getBoundingClientRect();
      return rect.height > 0 && rect.bottom > top && rect.top < top + reader.clientHeight;
    });
    return { bookId: model.book?.id, page: model.currentPage, view: model.view,
      scrollTop: reader.scrollTop, blockId: block?.dataset.blockId,
      offset: block ? block.getBoundingClientRect().top - top : 0 };
  }
  function restorePosition(saved) {
    if (!saved || saved.bookId !== model.book?.id || saved.page !== model.currentPage) return;
    if (saved.view !== model.view) { reader.scrollTop = 0; return; }
    const block = saved.blockId && [...paper.querySelectorAll('[data-block-id]')].find(node => node.dataset.blockId === saved.blockId);
    reader.scrollTop = block
      ? reader.scrollTop + block.getBoundingClientRect().top - reader.getBoundingClientRect().top - saved.offset
      : saved.scrollTop;
  }
  function measureFooter() {
    if (!active()) return;
    const scrollbar = `${Math.max(0, reader.offsetWidth - reader.clientWidth)}px`;
    if (doc.body.style.getPropertyValue('--focus-scrollbar-width') !== scrollbar) doc.body.style.setProperty('--focus-scrollbar-width', scrollbar);
    const value = `${Math.ceil(progress.getBoundingClientRect().height)}px`;
    if (doc.body.style.getPropertyValue('--focus-footer-height') !== value) doc.body.style.setProperty('--focus-footer-height', value);
  }
  function syncNavigation() {
    previous.disabled = pageAfter(model.currentPage, model.book?.totalPages, -1) === null;
    next.disabled = pageAfter(model.currentPage, model.book?.totalPages, 1) === null;
  }
  function sync() {
    const enabled = active();
    if (bookBefore !== model.book?.id) {
      if (returnState?.bookId !== model.book?.id) returnState = null;
      bookBefore = model.book?.id;
    }
    // Restore older saved focus preferences too; focus always means parsed reading.
    if (enabled && model.view !== 'reading' && model.page) {
      returnState ||= location();
      doc.querySelector('[data-view="reading"]')?.click();
    }
    setClass('focus-reading', enabled);
    for (const node of [previous, next, exit, help]) node.hidden = !enabled;
    const unavailable = Boolean(enabled && model.page && !paper.querySelector('.reading-flow'));
    setClass('focus-unavailable', unavailable);
    empty.hidden = !unavailable;
    if (unavailable) {
      const message = model.page.presentation?.fallbackMode === 'PAGE_IMAGE'
        ? '本页版式需对照原稿，退出专注可查看。'
        : model.page.status === 'FAILED' ? '本页解析未完成，退出专注可查看原因或重试。'
        : model.page.status === 'PROCESSING' ? '本页正在解析，可继续翻页或稍后返回。'
        : '本页尚未解析，可继续翻页，或退出专注查看原稿。';
      if (empty.textContent !== message) empty.textContent = message;
    }
    if (enabled) reader.setAttribute('aria-describedby', help.id);
    else if (reader.getAttribute('aria-describedby') === help.id) reader.removeAttribute('aria-describedby');
    syncNavigation(); measureFooter();
  }
  // Runs before app.js's existing listener; it still performs the actual toggle,
  // render and preference save. Do not add a second state.focus toggle here.
  toggle.addEventListener('click', event => {
    if (!model.book) { event.preventDefault(); event.stopImmediatePropagation(); return; }
    positionBefore = location();
    if (!model.focus) {
      returnState = positionBefore;
      model.view = 'reading';
    } else {
      model.view = returnState?.bookId === model.book.id ? returnState.view : 'reading';
      if (returnState?.page === model.currentPage && model.view !== 'reading') positionBefore = returnState;
    }
  }, true);
  toggle.addEventListener('click', () => {
    sync();
    const ticket = ++transition;
    const saved = positionBefore;
    const bookId = model.book?.id, page = model.currentPage;
    win.requestAnimationFrame(() => {
      if (ticket !== transition || bookId !== model.book?.id || page !== model.currentPage) return;
      measureFooter(); restorePosition(saved);
      (active() ? reader : toggle).focus({ preventScroll: true });
    });
  });
  exit.addEventListener('click', () => { if (active()) toggle.click(); });

  function navigate(direction) {
    if (!active() || doc.querySelector('dialog[open]') || selected()) return;
    const target = pageAfter(model.currentPage, model.book?.totalPages, direction);
    if (target === null) return;
    // Use the existing navigation gate (draft confirmation, request cancellation,
    // cache and current-page priority); never create a separate OCR/API path.
    jump.value = String(target);
    jumpForm.requestSubmit();
    syncNavigation();
  }
  for (const [button, direction] of [[previous, -1], [next, 1]]) {
    let start = null, tap = false;
    const point = event => ({ pointerId: event.pointerId, x: event.clientX, y: event.clientY,
      time: event.timeStamp, scrollTop: reader.scrollTop });
    button.addEventListener('pointerdown', event => {
      tap = false;
      start = event.isPrimary && event.button === 0 && !event.ctrlKey && !event.metaKey && !event.altKey
        ? { ...point(event), selected: selected() } : null;
    });
    button.addEventListener('pointerup', event => { tap = isShortTap(start, point(event)); start = null; });
    button.addEventListener('pointercancel', () => { start = null; tap = false; });
    button.addEventListener('click', event => {
      if (event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
      const allowed = event.detail === 0 || tap;
      tap = false;
      if (allowed) navigate(direction);
    });
  }
  doc.addEventListener('keydown', event => {
    if (!active() || event.defaultPrevented || event.isComposing || event.ctrlKey || event.metaKey || event.altKey || doc.querySelector('dialog[open]')) return;
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopImmediatePropagation(); toggle.click(); return;
    }
    const onTurnControl = event.target === previous || event.target === next;
    if (event.shiftKey || event.repeat || selected() || (!onTurnControl && event.target.closest?.(interactive))) return;
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault(); event.stopPropagation(); navigate(event.key === 'ArrowLeft' ? -1 : 1);
    }
  }, true);
  // An explicit request for correction/source inspection leaves focus first, so
  // existing drawers and their focus targets cannot become invisible overlays.
  doc.addEventListener('click', event => {
    if (!active()) return;
    const edit = event.target.closest?.('.issue-inspector-actions .primary');
    if (edit || event.target.closest?.('.filtered-ads button')) {
      toggle.click();
      if (edit) onProofRequested();
    }
  }, true);

  // Observe only presentation signals, never text edits, percentage ticks or all
  // document mutations. Also handles persisted focus and book/page replacement.
  const observer = new win.MutationObserver(sync);
  observer.observe(doc.body, { attributes: true, attributeFilter: ['class'] });
  observer.observe(shell, { attributes: true, attributeFilter: ['hidden'] });
  observer.observe(paper, { childList: true });
  const sizeObserver = new win.ResizeObserver(measureFooter);
  sizeObserver.observe(progress);
  css.addEventListener('load', measureFooter);
  sync();
}
