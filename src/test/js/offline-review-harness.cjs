'use strict';
// A1-02 回归：离线修订的可信源页身份与疑点基线。
// 用最小 DOM/localStorage 桩执行真实的 issue-review.js，不依赖浏览器。
// 旧提交上 A1-I01/I02 应自动应用（本文件断言 pending），先红后绿见 A1 报告。
const fs = require('fs');
const path = require('path');
const assert = require('assert');

function makeEnv({ quotaFail = false } = {}) {
  const store = new Map();
  function stubElement(tag) {
    const e = {
      tag, children: [], className: '', textContent: '', dataset: {}, style: {},
      open: false, disabled: false, value: '', rows: 0, maxLength: 1000,
      classList: { add() {}, toggle() {}, remove() {}, contains: () => false },
      setAttribute() {}, getAttribute: () => null,
      addEventListener() {}, removeEventListener() {},
      append(...kids) { e.children.push(...kids); return e; },
      replaceChildren(...kids) { e.children = [...kids]; return e; },
      prepend(...kids) { e.children.unshift(...kids); return e; },
      click() {}, showModal() { e.open = true; }, close() { e.open = false; },
      focus() {}, scrollIntoView() {},
    };
    return e;
  }
  const localStorage = {
    getItem: k => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => {
      if (quotaFail) { const err = new Error('quota'); err.name = 'QuotaExceededError'; throw err; }
      store.set(String(k), String(v));
    },
    removeItem: k => { store.delete(k); },
  };
  const sandbox = {
    console, URL: { createObjectURL: () => 'blob:x', revokeObjectURL() {} },
    Blob: class Blob { constructor(parts) { this.parts = parts; } },
    localStorage,
    window: { addEventListener() {}, confirm: () => true },
    document: {
      createElement: stubElement,
      createTextNode: text => ({ nodeType: 3, textContent: String(text ?? '') }),
      querySelector: () => null,
      querySelectorAll: () => [],
      body: stubElement('body'),
    },
    BookReadingLayout: {
      appendText() {}, appendIssueText() {},
      // J10：保真/辅助渲染入口与线上 frozen 导出一致
      appendConfirmedIssueText() {}, appendAssistedIssueText() {},
      resolveReadingText: (issue, sourceText) => ({ text: String(sourceText ?? ''), provenance: 'SOURCE', unresolved: true }),
      displayIssueText: (issue, text) => text,
    },
  };
  sandbox.globalThis = sandbox;
  return { sandbox, store };
}

function loadReview(env, source) {
  const vm = require('vm');
  vm.createContext(env.sandbox);
  vm.runInContext(source, env.sandbox, { filename: 'issue-review.js' });
  return env.sandbox.BookIssueReview;
}

const source = fs.readFileSync(
  path.join(__dirname, '..', '..', 'main', 'resources', 'export', 'issue-review.js'), 'utf8');

// 附录 B.1 夹具
function page(localPage, sourcePage, start = 0, end = 1) {
  return {
    pageNumber: localPage, sourcePageNumber: sourcePage, status: 'READY',
    blocks: [{
      id: 'shared-block', original: '甲乙丙丁', simplified: '甲乙丙丁', bbox: [0, 0, 1, 1],
      issues: [{
        id: 'shared-issue', kind: 'suspected', start, end,
        simplifiedStart: start, simplifiedEnd: end,
        resolved: false, replacement: '',
      }],
    }],
  };
}
const book = { id: 'book-1', title: 't', sourcePdfSha256: 'abc' };
const V3 = 'book-html:book-1:issue-edits:v3';

// A1-I01：源页 2 的修订绝不能应用到源页 9（同文本同 ID）
{
  const env = makeEnv();
  const Review = loadReview(env, source);
  let current = page(1, 2);
  const r = Review.create({ book, pages: [], getPage: () => current, getScript: () => 's', render: () => {} });
  current.blocks[0].issues[0].resolved = true;
  current.blocks[0].issues[0].replacement = '源页二专用修订';
  assert.strictEqual(r.saveCurrent(current.blocks[0]).ok, true);
  const target = page(2, 9);
  const c = r.hydratePage(target);
  assert.strictEqual(c.applied, 0, 'A1-I01 不得串页应用');
  assert.ok(c.pending >= 1 || r.pendingList(target).length >= 1 || target.blocks[0].issues[0].resolved === false);
  assert.strictEqual(target.blocks[0].issues[0].resolved, false);
  assert.strictEqual(target.blocks[0].issues[0].replacement, '');
  assert.strictEqual(r.recordCount(), 1, 'A1-I01 修订仍可找回');
  console.log('A1-I01 ok');
}

// A1-I02：范围漂移不自动应用
{
  const env = makeEnv();
  const Review = loadReview(env, source);
  let current = page(1, 1, 0, 1);
  const r = Review.create({ book, pages: [], getPage: () => current, getScript: () => 's', render: () => {} });
  current.blocks[0].issues[0].resolved = true;
  current.blocks[0].issues[0].replacement = '只修第一个字';
  assert.strictEqual(r.saveCurrent(current.blocks[0]).ok, true);
  const moved = page(1, 1, 1, 2);
  const c = r.hydratePage(moved);
  assert.strictEqual(c.applied, 0, 'A1-I02 不得误套范围');
  assert.strictEqual(moved.blocks[0].issues[0].resolved, false);
  const pend = r.pendingList(moved);
  assert.ok(pend.some(p => p.reason === 'RANGE_CHANGED'), 'A1-I02 待核验原因');
  console.log('A1-I02 ok');
}

// A1-I03：完全相同的重导出正常恢复，重复 hydration 幂等
{
  const env = makeEnv();
  const Review = loadReview(env, source);
  let current = page(1, 9);
  const r = Review.create({ book, pages: [], getPage: () => current, getScript: () => 's', render: () => {} });
  current.blocks[0].issues[0].resolved = true;
  current.blocks[0].issues[0].replacement = '修好';
  r.saveCurrent(current.blocks[0]);
  const fresh = page(1, 9);
  const c1 = r.hydratePage(fresh);
  assert.strictEqual(c1.applied, 1, 'A1-I03 正常恢复');
  assert.strictEqual(fresh.blocks[0].issues[0].replacement, '修好');
  const c2 = r.hydratePage(fresh);
  assert.strictEqual(c2.applied, 0, 'A1-I03 幂等');
  assert.strictEqual(c2.pending, 0);
  console.log('A1-I03 ok');
}

// A1-I04：v1 有映射迁移为待核验；无映射隔离
{
  const selBook = { id: 'sel-1', title: 't' };
  const legacy = { '2:shared-block': { original: '甲乙丙丁', simplified: '甲乙丙丁', issues: [{ id: 'shared-issue', resolved: true, replacement: '旧改' }] } };
  const env = makeEnv();
  env.store.set('book-html:sel-1:issue-edits:v1', JSON.stringify(legacy));
  const Review = loadReview(env, source);
  let current = page(2, 9);
  const r = Review.create({ book: selBook, pages: [], pageMap: { 2: 9 }, getPage: () => current, getScript: () => 's', render: () => {} });
  assert.strictEqual(r.recordCount(), 1, 'A1-I04 有映射迁移保留');
  const c = r.hydratePage(current);
  assert.strictEqual(c.applied, 0, 'A1-I04 无基线不得自动应用');
  assert.strictEqual(current.blocks[0].issues[0].resolved, false);
  const ok = r.confirmPending('9:shared-block', 'shared-issue');
  assert.strictEqual(ok.ok, true, 'A1-I04 确认沿用');
  assert.strictEqual(current.blocks[0].issues[0].replacement, '旧改');
  // 无映射：隔离
  const env2 = makeEnv();
  env2.store.set('book-html:sel-1:issue-edits:v1', JSON.stringify(legacy));
  const Review2 = loadReview(env2, source);
  const r2 = Review2.create({ book: selBook, pages: [], getPage: () => current, getScript: () => 's', render: () => {} });
  assert.strictEqual(r2.recordCount(), 0, 'A1-I04 无映射不混入');
  assert.ok(r2.storageStatus().quarantined >= 1, 'A1-I04 无映射隔离保留');
  console.log('A1-I04 ok');
}

// A1-I05：非法记录不应用不删库，合法保留
{
  const env = makeEnv();
  env.store.set(V3, JSON.stringify({
    schemaVersion: 3, bookId: 'book-1',
    records: {
      '2:good': { sourcePage: 2, blockId: 'good', original: '甲', simplified: '甲', issues: [] },
      '0:x': { sourcePage: 0, blockId: 'x', original: '甲', simplified: '甲', issues: [] },
      '9:other': { sourcePage: 2, blockId: 'other', original: '甲', simplified: '甲', issues: [] },
    },
  }));
  const Review = loadReview(env, source);
  const r = Review.create({ book, pages: [], getPage: () => page(1, 1), getScript: () => 's', render: () => {} });
  assert.strictEqual(r.recordCount(), 1, 'A1-I05 仅合法记录');
  assert.ok(r.storageStatus().quarantined >= 2, 'A1-I05 非法隔离');
  // 伪造 bookId：全部隔离
  const env2 = makeEnv();
  env2.store.set(V3, JSON.stringify({ schemaVersion: 3, bookId: 'forged', records: { '2:good': { sourcePage: 2, blockId: 'good', original: '甲', simplified: '甲', issues: [] } } }));
  const Review2 = loadReview(env2, source);
  const r2 = Review2.create({ book, pages: [], getPage: () => page(1, 1), getScript: () => 's', render: () => {} });
  assert.strictEqual(r2.recordCount(), 0, 'A1-I05 伪造 bookId 不应用');
  console.log('A1-I05 ok');
}

// A1-I06：代理对切断拒绝；确认删除与简体范围正常
{
  const env = makeEnv();
  const Review = loadReview(env, source);
  const sur = { pageNumber: 1, sourcePageNumber: 1, status: 'READY', blocks: [{
    id: 'surr', original: '甲𠮷乙', simplified: '甲𠮷乙', bbox: [0, 0, 1, 1],
    issues: [{ id: 'i', kind: 'suspected', start: 2, end: 3, simplifiedStart: 2, simplifiedEnd: 3, resolved: true, replacement: 'X' }],
  }] };
  let current = sur;
  const r = Review.create({ book, pages: [], getPage: () => current, getScript: () => 's', render: () => {} });
  // 存入时不校验（保存当时负责），恢复时拒绝：直接写一条带基线的记录再 hydrate
  r.saveCurrent(sur.blocks[0]);
  const fresh = JSON.parse(JSON.stringify(sur));
  fresh.blocks[0].issues[0].resolved = false;
  fresh.blocks[0].issues[0].replacement = '';
  const c = r.hydratePage(fresh);
  assert.strictEqual(c.applied, 0, 'A1-I06 切断代理对不得应用');
  assert.strictEqual(fresh.blocks[0].issues[0].resolved, false);
  // 确认删除（空串替换）合法范围正常应用
  const del = page(3, 3);
  let cur2 = del;
  const r2env = makeEnv();
  const Review2 = loadReview(r2env, source);
  const rr = Review2.create({ book, pages: [], getPage: () => cur2, getScript: () => 's', render: () => {} });
  cur2.blocks[0].issues[0].resolved = true;
  cur2.blocks[0].issues[0].replacement = '';
  rr.saveCurrent(cur2.blocks[0]);
  const fresh2 = page(3, 3);
  const c2 = rr.hydratePage(fresh2);
  assert.strictEqual(c2.applied, 1, 'A1-I06 确认删除可恢复');
  assert.strictEqual(fresh2.blocks[0].issues[0].replacement, '');
  console.log('A1-I06 ok');
}

// E02a 等价：刷新后恢复；E02d 配额失败不丢内存
{
  const env = makeEnv();
  const Review = loadReview(env, source);
  let current = page(1, 1);
  const r = Review.create({ book, pages: [], getPage: () => current, getScript: () => 's', render: () => {} });
  current.blocks[0].issues[0].resolved = true;
  current.blocks[0].issues[0].replacement = '甲';
  assert.strictEqual(r.saveCurrent(current.blocks[0]).ok, true);
  const env2 = makeEnv();
  env2.store.set(V3, env.store.get(V3));
  const Review2 = loadReview(env2, source);
  const r2 = Review2.create({ book, pages: [], getPage: () => ({ pageNumber: 1, status: 'LOADING', blocks: [] }), getScript: () => 's', render: () => {} });
  assert.strictEqual(r2.recordCount(), 1, 'E02a 未加载页记录保留');
  const fresh = page(1, 1);
  assert.strictEqual(r2.hydratePage(fresh).applied, 1, 'E02a 恢复');
  assert.strictEqual(fresh.blocks[0].issues[0].replacement, '甲');

  const env3 = makeEnv({ quotaFail: true });
  const Review3 = loadReview(env3, source);
  const p3 = page(1, 1);
  const r3 = Review3.create({ book, pages: [], getPage: () => p3, getScript: () => 's', render: () => {} });
  p3.blocks[0].issues[0].resolved = true;
  const res = r3.saveCurrent(p3.blocks[0]);
  assert.strictEqual(res.ok, false, 'E02d 配额失败');
  assert.ok(res.reason);
  assert.strictEqual(r3.recordCount(), 1, 'E02d 内存不丢');
  console.log('E02a/E02d ok');
}

console.log('ALL_OFFLINE_REVIEW_CASES_PASS');
