import { loadPreferences } from './store.js';

export const SHELF_PAGE_SIZE = 24;
export function readingEntry(book, preferences = {}) {
  const page = Number(preferences.page);
  const valid = Number.isSafeInteger(page) && page > 0 && Number.isSafeInteger(book.totalPages) && page <= book.totalPages;
  const visitedAt = Number(preferences.visitedAt);
  return { page: valid ? page : 1, continuing: valid,
    visitedAt: valid && Number.isFinite(visitedAt) && visitedAt > 0 ? visitedAt : 0 };
}
export function shelfEntries(books, query = '', sort = 'reading', preferences = loadPreferences) {
  const needle = query.trim().toLocaleLowerCase();
  return books.filter(book => !book.archived && (!needle || `${book.title} ${book.filename}`.toLocaleLowerCase().includes(needle)))
    .map(book => ({ book, ...readingEntry(book, preferences(book.id)) }))
    .sort((a, b) => {
      if (sort === 'title') return a.book.title.localeCompare(b.book.title, 'zh-CN') || a.book.id.localeCompare(b.book.id);
      if (sort === 'reading' && a.visitedAt !== b.visitedAt) return b.visitedAt - a.visitedAt;
      return (Date.parse(b.book.createdAt) || 0) - (Date.parse(a.book.createdAt) || 0) || a.book.id.localeCompare(b.book.id);
    });
}
export function createBookshelf({ books, openBook, refresh }) {
  const $ = selector => document.querySelector(selector);
  const list = $('#shelf-books'), search = $('#shelf-search'), sort = $('#shelf-sort'), status = $('#shelf-status');
  let offset = 0, opening = false, error = '';
  const node = (tag, className, text) => { const el = document.createElement(tag); el.className = className; if (text != null) el.textContent = text; return el; };
  function render(reset = false) {
    if (reset) offset = 0;
    const entries = shelfEntries(books(), search.value, sort.value);
    if (offset >= entries.length) offset = Math.max(0, Math.floor((entries.length - 1) / SHELF_PAGE_SIZE) * SHELF_PAGE_SIZE);
    list.replaceChildren();
    for (const entry of entries.slice(offset, offset + SHELF_PAGE_SIZE)) {
      const { book } = entry;
      const card = node('article', 'shelf-book'); card.dataset.bookId = book.id;
      const icon = node('span', 'shelf-spine', 'PDF'); icon.setAttribute('aria-hidden', 'true');
      const title = node('h3', '', book.title);
      const meta = node('p', 'shelf-book-meta', `${book.totalPages} 页 · ${entry.continuing ? `本设备读到第 ${entry.page} 页` : '还未在本设备阅读'}`);
      const button = node('button', 'button primary', entry.continuing ? `继续阅读 · 第 ${entry.page} 页` : '开始阅读');
      button.type = 'button'; button.disabled = opening;
      button.setAttribute('aria-label', `${button.textContent}：《${book.title}》`);
      button.addEventListener('click', async () => {
        if (opening) return;
        opening = true; button.disabled = true;
        try { await openBook(book.id); } finally { opening = false; button.disabled = false; }
      });
      card.append(icon, title, meta, button); list.append(card);
    }
    const count = entries.length;
    status.textContent = error || (count ? `${count} 本书 · 显示 ${offset + 1}–${Math.min(offset + SHELF_PAGE_SIZE, count)}`
      : search.value.trim() ? '没有匹配的书籍，试试其他关键词。' : '书架还没有书。上传 PDF 后会保存在这台服务器，之后无需重复上传。');
    $('#shelf-previous').disabled = offset === 0;
    $('#shelf-next').disabled = offset + SHELF_PAGE_SIZE >= count;
    $('#shelf-pagination').hidden = count <= SHELF_PAGE_SIZE;
    list.setAttribute('aria-busy', 'false');
  }
  search.addEventListener('input', () => { error = ''; render(true); });
  sort.addEventListener('change', () => render(true));
  $('#shelf-previous').addEventListener('click', () => { offset = Math.max(0, offset - SHELF_PAGE_SIZE); render(); });
  $('#shelf-next').addEventListener('click', () => { offset += SHELF_PAGE_SIZE; render(); });
  $('#shelf-refresh').addEventListener('click', async event => {
    const button = event.currentTarget; button.disabled = true; list.setAttribute('aria-busy', 'true'); error = '';
    try { await refresh(); } catch (failure) { error = failure.message || '书架暂不可用，已有书籍未被删除。'; }
    finally { button.disabled = false; render(); }
  });
  return { render: () => { error = ''; render(); }, failed: message => { error = message; render(); } };
}

// Coalesce only lightweight metadata reads. A delayed read cannot erase an upload,
// rename or archive that already replaced the local list while it was in flight.
export function createShelfRefresh({ load, current, apply }) {
  let pending = null;
  return () => {
    if (pending) return pending;
    const before = current();
    pending = Promise.resolve().then(load).then(latest => {
      if (!Array.isArray(latest)) throw new Error('书架响应无效，已有书籍未改动。');
      if (current() !== before) return false;
      apply(latest); return true;
    }).finally(() => { pending = null; });
    return pending;
  };
}
