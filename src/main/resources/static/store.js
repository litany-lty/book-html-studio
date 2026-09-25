export const PAGE_CACHE_LIMIT = 12;
export const PAGE_CACHE_BYTES_LIMIT = 32 * 1024 * 1024; // 32 MiB

// Conservative retained-object estimate, not an exact browser heap measurement.
// Count every JSON field (including sourceRecords, simplified text and evidence),
// visit aliased objects once, and stop oversized/untrusted graphs without recursion.
export function estimatePageBytes(page) {
  if (!page) return 0;
  let bytes = 0, visited = 0;
  const stack = [page], seen = new WeakSet();
  while (stack.length) {
    const value = stack.pop();
    if (typeof value === 'string') bytes += 24 + value.length * 2;
    else if (value === null || typeof value === 'number' || typeof value === 'boolean' || value === undefined) bytes += 8;
    else if (typeof value === 'object') {
      if (seen.has(value)) continue;
      seen.add(value);
      if (++visited > 100000) return Number.MAX_SAFE_INTEGER;
      const array = Array.isArray(value);
      if (!array && Object.getPrototypeOf(value) !== Object.prototype && Object.getPrototypeOf(value) !== null)
        return Number.MAX_SAFE_INTEGER;
      bytes += array ? 40 + value.length * 8 : 64;
      if (bytes > PAGE_CACHE_BYTES_LIMIT) return Number.MAX_SAFE_INTEGER;
      for (const key of Object.keys(value)) {
        bytes += 32 + key.length * 2;
        const property = Object.getOwnPropertyDescriptor(value, key);
        if (!property || !Object.hasOwn(property, 'value')) return Number.MAX_SAFE_INTEGER;
        stack.push(property.value);
        if (stack.length > 100000 || bytes > PAGE_CACHE_BYTES_LIMIT) return Number.MAX_SAFE_INTEGER;
      }
    } else return Number.MAX_SAFE_INTEGER;
    if (!Number.isSafeInteger(bytes) || bytes > PAGE_CACHE_BYTES_LIMIT) return Number.MAX_SAFE_INTEGER;
  }
  return Math.max(bytes, 512);
}

export class LruPageCache extends Map {
  constructor(limit = PAGE_CACHE_LIMIT, maxBytesOrProtected = PAGE_CACHE_BYTES_LIMIT, isProtected) {
    super();
    if (typeof maxBytesOrProtected === 'function') {
      this.limit = limit;
      this.maxBytes = PAGE_CACHE_BYTES_LIMIT;
      this.isProtected = maxBytesOrProtected;
    } else {
      this.limit = limit;
      this.maxBytes = typeof maxBytesOrProtected === 'number' ? maxBytesOrProtected : PAGE_CACHE_BYTES_LIMIT;
      this.isProtected = isProtected;
    }
    this.byteSizes = new Map();
    this.totalBytes = 0;
  }
  get(key) {
    if (!super.has(key)) return undefined;
    const value = super.get(key);
    super.delete(key);
    super.set(key, value);
    return value;
  }
  set(key, value) {
    const nextBytes = estimatePageBytes(value);
    // A speculative oversized page must not evict every useful neighbor before
    // being evicted itself. The visible page/draft remains an explicit exception.
    if (nextBytes > this.maxBytes && !(typeof this.isProtected === 'function' && this.isProtected(key))) {
      this.delete(key); return this;
    }

    super.delete(key);
    super.set(key, value);
    this.byteSizes.set(key, nextBytes);
    this.recountBytes();

    // G11: 12页 / 32MiB 缓存双上限，淘汰最久未使用页，当前页与未保存草稿所在页不得淘汰
    while (super.size > this.limit || this.totalBytes > this.maxBytes) {
      let evicted = false;
      for (const oldest of super.keys()) {
        if (typeof this.isProtected === 'function' && this.isProtected(oldest)) continue;
        this.byteSizes.delete(oldest);
        super.delete(oldest);
        this.recountBytes();
        evicted = true;
        break;
      }
      if (!evicted) break;
    }
    return this;
  }
  delete(key) {
    if (!super.has(key)) return false;
    this.byteSizes.delete(key);
    this.recountBytes();
    return super.delete(key);
  }
  clear() {
    this.byteSizes.clear();
    this.totalBytes = 0;
    super.clear();
  }
  recountBytes() {
    this.totalBytes = 0;
    for (const bytes of this.byteSizes.values())
      this.totalBytes = Math.min(Number.MAX_SAFE_INTEGER, this.totalBytes + bytes);
  }
  get currentBytes() {
    return this.totalBytes;
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
  // 阶段2：已读页有限 LRU（默认 8 页，可调整的暂定参数）；当前页不得淘汰
  pageCache: new LruPageCache(PAGE_CACHE_LIMIT, key => key === state.currentPage)
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
  try { const value = JSON.parse(localStorage.getItem(key(bookId, 'bookmarks')) || '[]');
    return Array.isArray(value) ? value.filter(n => Number.isSafeInteger(n) && n > 0) : []; }
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

// G11: 交互会话守卫——管理页切换、书切换及编辑会话代次，杜绝跨会话异步状态污染
export class SessionGuard {
  constructor() {
    this.bookId = null;
    this.page = null;
    this.epoch = 0;
  }
  setSession(bookId, page) {
    if (this.bookId !== bookId || this.page !== page) {
      this.bookId = bookId;
      this.page = page;
      this.epoch++;
    }
    return this.token;
  }
  get token() {
    return { bookId: this.bookId, page: this.page, epoch: this.epoch };
  }
  isValid(token) {
    return !!token && token.bookId === this.bookId && token.page === this.page && token.epoch === this.epoch;
  }
  invalidate() {
    this.epoch++;
  }
}

export const sessionGuard = new SessionGuard();
export function createSessionGuard() { return new SessionGuard(); }

