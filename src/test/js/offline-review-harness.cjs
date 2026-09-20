'use strict';
// R02 回归：离线校对记录的延迟恢复、跨页保留、旧格式升级、配额失败。
// 用最小 DOM/localStorage 桩执行真实的 issue-review.js，不依赖浏览器。
const fs = require('fs');
const path = require('path');
const assert = require('assert');

function makeEnv({ quotaFail = false } = {}) {
  const store = new Map();
  const listeners = {};
  function stubElement(tag) {
    const e = {
      tag, children: [], className: '', textContent: '', dataset: {}, style: {},
      open: false, disabled: false, value: '', rows: 0, maxLength: 0,
      classList: { add() {}, toggle() {}, remove() {}, contains: () => false },
      setAttribute() {}, getAttribute: () => null,
      addEventListener() {}, removeEventListener() {},
      append(...kids) { e.children.push(...kids); return e; },
      replaceChildren(...kids) { e.children = [...kids]; return e; },
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
    window: {
      addEventListener: (t, fn) => { (listeners[t] = listeners[t] || []).push(fn); },
      confirm: () => true,
    },
    document: {
      createElement: stubElement,
      querySelector: () => null,
      querySelectorAll: () => [],
      body: stubElement('body'),
    },
    BookReadingLayout: {
      appendText() {}, appendIssueText() {},
      displayIssueText: (issue, text) => text,
    },
  };
  sandbox.globalThis = sandbox;
  sandbox.window.localStorage = localStorage;
  return { sandbox, store, listeners };
}

function loadReview(env, source) {
  const vm = require('vm');
  vm.createContext(env.sandbox);
  vm.runInContext(source, env.sandbox, { filename: 'issue-review.js' });
  return env.sandbox.BookIssueReview;
}

const source = fs.readFileSync(
  path.join(__dirname, '..', '..', 'main', 'resources', 'export', 'issue-review.js'), 'utf8');

function block(id, original, issues) {
  return { id, original, simplified: original, bbox: [0, 0, 1, 1], issues };
}
function issue(id, start, end) {
  return { id, kind: 'suspected', start, end, reason: '', resolved: false, replacement: '' };
}
const book = { id: 'book-1', title: 't' };
function pages() {
  return [
    { pageNumber: 1, sourcePageNumber: 1, status: 'READY', blocks: [block('b1', '甲乙丙丁', [issue('i1', 0, 2)])] },
    { pageNumber: 2, sourcePageNumber: 2, status: 'READY', blocks: [block('b2', '戊己庚辛', [issue('i2', 0, 2)])] },
  ];
}

// E02a：A 页保存后“刷新”（重建实例、空占位），加载 A 页正文后恢复。
{
  const env = makeEnv();
  const Review = loadReview(env, source);
  let current = pages()[0];
  const r1 = Review.create({ book, pages: [], getPage: () => current, getScript: () => 'simplified', render: () => {} });
  current.blocks[0].issues[0].resolved = true;
  current.blocks[0].issues[0].replacement = '甲乙';
  const saved = r1.saveCurrent(current.blocks[0]);
  assert.strictEqual(saved.ok, true, 'E02a save ok');
  // “刷新”：全新实例，创建时只有空占位
  const env2 = makeEnv();
  env2.store.set(`book-html:book-1:issue-edits:v2`, env.store.get(`book-html:book-1:issue-edits:v2`));
  const Review2 = loadReview(env2, source);
  const fresh = pages();
  let cur2 = { pageNumber: 1, status: 'LOADING', blocks: [] };
  const r2 = Review2.create({ book, pages: [], getPage: () => cur2, getScript: () => 'simplified', render: () => {} });
  assert.strictEqual(r2.recordCount(), 1, 'E02a 未加载页的记录必须保留');
  cur2 = fresh[0];
  const counts = r2.hydratePage(cur2);
  assert.strictEqual(counts.applied, 1, 'E02a 恢复应用');
  assert.strictEqual(cur2.blocks[0].issues[0].resolved, true);
  assert.strictEqual(cur2.blocks[0].issues[0].replacement, '甲乙');
  console.log('E02a ok');
}

// E02b：再改 B 页，A 记录仍在；E02e：基线变更不套用。
{
  const env = makeEnv();
  const Review = loadReview(env, source);
  const ps = pages();
  let current = ps[0];
  const r = Review.create({ book, pages: ps, getPage: () => current, getScript: () => 'simplified', render: () => {} });
  ps[0].blocks[0].issues[0].resolved = true;
  ps[0].blocks[0].issues[0].replacement = '甲乙';
  assert.strictEqual(r.saveCurrent(ps[0].blocks[0]).ok, true);
  current = ps[1];
  ps[1].blocks[0].issues[0].resolved = true;
  ps[1].blocks[0].issues[0].replacement = '戊己';
  assert.strictEqual(r.saveCurrent(ps[1].blocks[0]).ok, true);
  assert.strictEqual(r.recordCount(), 2, 'E02b 两页记录并存');
  const raw = JSON.parse(env.store.get('book-html:book-1:issue-edits:v2'));
  assert.strictEqual(raw.schemaVersion, 2);
  assert.ok(raw.records['1:b1'] && raw.records['2:b2'], 'E02b 存储键使用源页号');
  // 基线变更的旧记录不得套用
  const changed = block('b1', '甲乙丙丁改', [issue('i1', 0, 2)]);
  const c = r.hydratePage({ pageNumber: 1, sourcePageNumber: 1, blocks: [changed] });
  assert.strictEqual(c.pending, 1, 'E02e 基线不一致进入待核验');
  assert.strictEqual(changed.issues[0].resolved, false, 'E02e 不得套用旧记录');
  assert.strictEqual(r.recordCount(), 2, 'E02e 待核验记录不得丢弃');
  console.log('E02b/E02e ok');
}

// E02g：旧版扁平字典升级不丢字段。
{
  const env = makeEnv();
  env.store.set('book-html:book-1:issue-edits:v1', JSON.stringify({
    '1:b1': { original: '甲乙丙丁', simplified: '甲乙丙丁', issues: [{ id: 'i1', resolved: true, replacement: '甲乙' }] },
  }));
  const Review = loadReview(env, source);
  const ps = pages();
  const r = Review.create({ book, pages: [], getPage: () => ps[0], getScript: () => 'simplified', render: () => {} });
  assert.strictEqual(r.recordCount(), 1, 'E02g 旧记录迁入');
  const c = r.hydratePage(ps[0]);
  assert.strictEqual(c.applied, 1, 'E02g 旧记录可恢复');
  assert.strictEqual(ps[0].blocks[0].issues[0].replacement, '甲乙');
  const migrated = JSON.parse(env.store.get('book-html:book-1:issue-edits:v2'));
  assert.strictEqual(migrated.schemaVersion, 2);
  console.log('E02g ok');
}

// E02d：配额失败不丢内存记录，返回明确原因。
{
  const env = makeEnv({ quotaFail: true });
  const Review = loadReview(env, source);
  const ps = pages();
  const r = Review.create({ book, pages: ps, getPage: () => ps[0], getScript: () => 'simplified', render: () => {} });
  ps[0].blocks[0].issues[0].resolved = true;
  const res = r.saveCurrent(ps[0].blocks[0]);
  assert.strictEqual(res.ok, false, 'E02d 配额失败必须返回失败');
  assert.ok(res.reason, 'E02d 必须给出原因');
  assert.strictEqual(r.recordCount(), 1, 'E02d 内存记录不得丢失');
  console.log('E02d ok');
}

console.log('ALL_OFFLINE_REVIEW_CASES_PASS');
