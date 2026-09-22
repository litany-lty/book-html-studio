import { PageCache } from './page-cache.js';
export const PAGE_CACHE_LIMIT = 16;

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
  // A1-01：编辑会话代次——相同页号不代表相同会话；旧保存响应不得写入新会话
  editorEpoch: 0,
  // A1-01：单会话在途保存（跨书/跨页可各自有一个，同会话最多一个）
  saveInFlight: null,
  // A1-01：真冲突比较栏（与当前书页绑定）
  conflict: null,
  // J08：阅读依据（语言脚本与证据状态分离；默认保真阅读）与辅助推荐映射（内存态，不写 page JSON）
  evidenceMode: 'confirmed',
  assistMap: {},
  job: null,
  // Cached JSON: at most 16 pages / 12 MiB estimated; prefer retaining the current page.
  pageCache: new PageCache(PAGE_CACHE_LIMIT, 12 * 1024 * 1024, key => key === state.currentPage)
};

const key = (bookId, suffix) => `paper-studio:${bookId}:${suffix}`;

export function loadPreferences(bookId) {
  try {
    const value = JSON.parse(localStorage.getItem(key(bookId, 'reading')) || '{}');
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch (_) { return {}; }
}

export function savePreferences(bookId, value) {
  try { localStorage.setItem(key(bookId, 'reading'), JSON.stringify(value)); }
  catch (_) { /* Reading remains usable when storage is unavailable. */ }
}

export function getBookmarks(bookId) {
  try {
    const value = JSON.parse(localStorage.getItem(key(bookId, 'bookmarks')) || '[]');
    return Array.isArray(value) ? [...new Set(value.filter(n => Number.isSafeInteger(n) && n > 0))] : [];
  }
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

// A1-01：编辑会话身份判断（纯函数）。相同页号不代表相同会话；
// bookId/page/epoch 任一不同即为过期响应，不得写入当前缓存与 UI。
export function isSameSession(current, snap) {
  return !!snap && !!current
    && current.bookId === snap.bookId && current.page === snap.page && current.epoch === snap.epoch;
}

// A1-S06：键序无关的规范 JSON，用于比对“服务端已存”与“本次提交”。
export function canonicalJson(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonicalJson(value[k])}`).join(',')}}`;
}
