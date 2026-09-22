/** LRU memory bound for page JSON only. Dirty editor state lives outside this disposable cache. */
export class PageCache extends Map {
  constructor(maxPages = 16, maxBytes = 12 * 1024 * 1024, isProtected = () => false) {
    super(); this.maxPages = maxPages; this.maxBytes = maxBytes; this.bytes = 0; this.sizes = new Map(); this.isProtected = isProtected;
  }
  get(key) {
    const value = super.get(key);
    if (super.has(key)) { super.delete(key); super.set(key, value); }
    return value;
  }
  set(key, value) {
    this.delete(key);
    // UTF-16 estimate is deliberately conservative; huge pages stay in the active editor only.
    const size = JSON.stringify(value ?? null).length * 2;
    if (size > this.maxBytes) return this;
    super.set(key, value); this.sizes.set(key, size); this.bytes += size;
    while (this.size > this.maxPages || this.bytes > this.maxBytes) {
      const oldest = [...this.keys()].find(key => !this.isProtected(key));
      // Only one active page is protected. An oversized active page lives in editor state, not cache.
      if (oldest === undefined) { this.delete(this.keys().next().value); break; }
      this.delete(oldest);
    }
    return this;
  }
  delete(key) {
    this.bytes -= this.sizes?.get(key) || 0; this.sizes?.delete(key);
    return super.delete(key);
  }
  clear() { super.clear(); this.sizes?.clear(); this.bytes = 0; }
}
