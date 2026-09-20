export const PAGE_CACHE_LIMIT = 8;

class LruPageCache extends Map {
  constructor(limit, isProtected) {
    super();
    this.limit = limit;
    this.isProtected = isProtected;
  }
  get(key) {
    if (!super.has(key)) return undefined;
    const value = super.get(key);
    super.delete(key);
    super.set(key, value);
    return value;
  }
  set(key, value) {
    super.delete(key);
    super.set(key, value);
    // 阶段2：有界缓存——淘汰最久未使用页，当前页与未保存草稿所在页不得淘汰
    while (super.size > this.limit) {
      let evicted = false;
      for (const oldest of super.keys()) {
        if (typeof this.isProtected === 'function' && this.isProtected(oldest)) continue;
        super.delete(oldest);
        evicted = true;
        break;
      }
      if (!evicted) break;
    }
    return this;
  }
}

export const state = {
  config: null,
  books: [],
  book: null,
  summaries: [],
  outline: [],
  outlineStatus: 'idle',
  activeOutlineBlockId: null,
  page: null,
  blocks: [],
  currentPage: 1,
  view: 'reading',
  script: 'simplified',
  fontSize: 20,
  lineHeight: 1.8,
  focus: false,
  selectedBlockId: null,
  selectedIssueId: null,
  dirty: false,
  drawType: null,
  pollTimer: null,
  // 阶段2：已读页有限 LRU（默认 8 页，可调整的暂定参数）；当前页不得淘汰
  pageCache: new LruPageCache(PAGE_CACHE_LIMIT, key => key === state.currentPage)
};

const key = (bookId, suffix) => `paper-studio:${bookId}:${suffix}`;

export function loadPreferences(bookId) {
  try {
    return JSON.parse(localStorage.getItem(key(bookId, 'reading')) || '{}');
  } catch (_) { return {}; }
}

export function savePreferences(bookId, value) {
  try { localStorage.setItem(key(bookId, 'reading'), JSON.stringify(value)); }
  catch (_) { /* Reading remains usable when storage is unavailable. */ }
}

export function getBookmarks(bookId) {
  try { return JSON.parse(localStorage.getItem(key(bookId, 'bookmarks')) || '[]'); }
  catch (_) { return []; }
}

export function setBookmarks(bookId, pages) {
  try { localStorage.setItem(key(bookId, 'bookmarks'), JSON.stringify([...new Set(pages)].sort((a, b) => a - b))); }
  catch (_) { /* Bookmark persistence is optional. */ }
}

export function cloneBlocks(blocks = []) {
  return blocks.map(block => ({
    ...block,
    bbox: Array.isArray(block.bbox) ? [...block.bbox] : [0, 0, .2, .1],
    sourceIds: Array.isArray(block.sourceIds) ? [...block.sourceIds] : block.sourceIds,
    issues: Array.isArray(block.issues) ? block.issues.map(issue => ({ ...issue })) : []
  }));
}
