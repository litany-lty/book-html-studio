import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const root = new URL('../../', import.meta.url);
const progressSource = await readFile(new URL('src/main/resources/static/page-progress.js', root), 'utf8');
const { progressView, createPageProgress } = await import(`data:text/javascript;base64,${Buffer.from(progressSource).toString('base64')}`);
const processing = (units, extra = {}) => ({ lifecycle: 'RUNNING', stage: 'REVIEW', units, ...extra });

test('current-stage percentage uses completed units including explicit failures, not time', () => {
  const view = progressView('READY', processing({total: 8, succeeded: 3, failed: 1}));
  assert.equal(view.percent, 50); assert.equal(view.text, '核对 · 50%');
  assert.match(view.title, /不是耗时比例/); assert.match(view.title, /1 个单位失败/);
});
test('missing totals stay indeterminate; counters are bounded', () => {
  assert.equal(progressView('PROCESSING', processing({total: 0})).percent, null);
  assert.equal(progressView('PROCESSING', processing({total: 3, succeeded: 900})).percent, 100);
  assert.equal(progressView('PROCESSING', processing({total: 3, succeeded: -1})).percent, 0);
});
test('pending page starts at zero, finished OCR is not claimed error-free', () => {
  assert.equal(progressView('PENDING', null).percent, 0);
  assert.equal(progressView('READY', null).text, '');
  assert.equal(progressView('READY', processing({}, {lifecycle: 'PARTIAL'})).text, '部分完成 · 待核对');
  assert.equal(progressView('READY', processing({}, {lifecycle: 'SUCCEEDED'})).percent, null);
});
test('progress is the immediate right-hand sibling of saved status', async () => {
  const html = await readFile(new URL('src/main/resources/static/index.html', root), 'utf8');
  assert.match(html, /id="page-save-status"[^>]*><\/span><span id="page-processing-progress"/);
});
test('opening dispatches current page before whole-book summaries and outline', async () => {
  const app = await readFile(new URL('src/main/resources/static/app.js', root), 'utf8');
  const select = app.slice(app.indexOf('async function selectBook('), app.indexOf('function validateRange('));
  assert.ok(select.indexOf('await goToPage(') < select.indexOf('api.pages(id)'));
  assert.ok(select.indexOf('await goToPage(') < select.indexOf('refreshOutline(id)'));
  assert.match(select, /api.bookMetadata\(id\)/);
});
test('progress reads cannot submit OCR or rebuild the reading DOM', () => {
  assert.doesNotMatch(progressSource, /innerHTML|replaceChildren|startJob|method:\s*['"]POST/);
  assert.match(progressSource, /ownGeneration !== generation/);
  assert.match(progressSource, /snapshotVersion/);
});
test('stale same-attempt and other-book snapshots are ignored', () => {
  const attributes = new Map(); const element = {dataset: {}, setAttribute(k,v){attributes.set(k,v)}, removeAttribute(k){attributes.delete(k)}};
  const state = {book: {id: 'a'}, currentPage: 4, page:{status:'READY'}};
  const watch = createPageProgress({element, state, api:{}});
  const snap = {...processing({total:4,succeeded:2}),bookId:'a',pageNumber:4,attemptId:'one',snapshotVersion:3,startedAt:'2026-01-01T00:00:00Z'};
  watch.accept(snap); assert.equal(element.textContent,'核对 · 50%');
  watch.accept({...snap,snapshotVersion:2,units:{total:4,succeeded:1}}); assert.equal(element.textContent,'核对 · 50%');
  watch.accept({...snap,bookId:'b',units:{total:4,succeeded:4}}); assert.equal(element.textContent,'核对 · 50%');
});
