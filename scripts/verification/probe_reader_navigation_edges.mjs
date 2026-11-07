import assert from 'node:assert/strict';
import { test } from 'node:test';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync('src/main/resources/static/app.js', 'utf8');
const functionSource = (start, end) => {
  const from = source.indexOf(start), to = source.indexOf(end, from + start.length);
  assert.ok(from >= 0 && to > from, `missing source boundary: ${start}`);
  return source.slice(from, to);
};
const tick = () => new Promise(resolve => setImmediate(resolve));

function outlineFixture() {
  const frames = [], actions = [];
  const target = { dataset: { blockId: 'heading' }, scrollIntoView: () => actions.push('scroll'), focus: () => actions.push('focus') };
  const context = vm.createContext({
    state: { book: { id: 'a' }, currentPage: 1, page: {}, view: 'reading', editorEpoch: 1 },
    bookRequest: 1, pageRequest: 1,
    goToPage: async () => true, renderCurrent() {}, saveReadingPosition() {}, closeDrawers() {},
    requestAnimationFrame: callback => frames.push(callback),
    window: { matchMedia: () => ({ matches: true }) },
    $: selector => selector === '#paper' ? { querySelectorAll: () => [target] } : { scrollTop: 0 }
  });
  vm.runInContext(functionSource('async function goToOutlineEntry(', 'async function commitPageInput('), context);
  return { context, frames, actions };
}

test('outline focus is discarded after a page change before animation frames', async () => {
  const fixture = outlineFixture();
  await fixture.context.goToOutlineEntry({ pageNumber: 1, blockId: 'heading' });
  fixture.context.state.currentPage = 2;
  fixture.context.pageRequest++;
  while (fixture.frames.length) fixture.frames.shift()();
  assert.deepEqual(fixture.actions, []);
});

test('outline navigation cannot change another book with the same page number', async () => {
  const fixture = outlineFixture();
  let resolve;
  fixture.context.goToPage = () => new Promise(done => { resolve = done; });
  const pending = fixture.context.goToOutlineEntry({ pageNumber: 1, blockId: 'heading' });
  fixture.context.state.book = { id: 'b' };
  fixture.context.bookRequest++;
  resolve(true);
  await pending;
  while (fixture.frames.length) fixture.frames.shift()();
  assert.deepEqual(fixture.actions, []);
  assert.equal(fixture.context.state.activeOutlineBlockId, undefined);
});

test('current outline destination is still focused and scrolled', async () => {
  const fixture = outlineFixture();
  await fixture.context.goToOutlineEntry({ pageNumber: 1, blockId: 'heading' });
  while (fixture.frames.length) fixture.frames.shift()();
  assert.deepEqual(fixture.actions, ['scroll', 'focus']);
});

test('same-page revalidation errors cannot surface after an A to B to A navigation', async () => {
  let reject, signal;
  const errors = [];
  const context = vm.createContext({
    state: { book: { id: 'a', totalPages: 8 }, currentPage: 1, page: { revision: 1, status: 'READY' }, editorEpoch: 1 },
    pageRequest: 1, pageFetchController: new AbortController(), AbortController,
    pageInput: { reset() {} }, renderToc() {}, renderReadingProgress() {},
    $: () => ({ value: '' }), olderRevision: () => false, acceptReadyPage() {}, showError: error => errors.push(error),
    api: { page: (_book, _page, value) => { signal = value; return new Promise((_done, fail) => { reject = fail; }); } }
  });
  vm.runInContext(functionSource('async function goToPage(', 'async function selectBook('), context);
  await context.goToPage(1);
  context.pageRequest += 2;
  context.state.editorEpoch += 2;
  reject(new Error('old request'));
  await tick();
  assert.deepEqual(errors, []);
  assert.ok(signal instanceof AbortSignal, 'same-page refresh participates in cancellation');
});

test('progress range commits one navigation for pointer release plus change', () => {
  const handlers = {}, calls = [];
  const range = { value: '4', addEventListener: (event, callback) => { handlers[event] = callback; } };
  const context = vm.createContext({
    $: () => range, goToPage: target => calls.push(target), renderReadingProgress() {}
  });
  vm.runInContext(functionSource('const commitProgressRange = ', "$('#reading-progress')?.addEventListener"), context);
  handlers.pointerup?.();
  handlers.change?.();
  assert.deepEqual(calls, [4]);
});

for (const action of ['onIssueSelect', 'onAdvertisementLocate']) {
  test(`${action} cannot focus a different page after a deferred frame`, () => {
    const frames = [], actions = [];
    let callbacks;
    const target = { dataset: { blockId: 'block' }, scrollIntoView: () => actions.push('scroll'), focus: () => actions.push('focus') };
    const element = { value: '', setAttribute() {}, querySelector: () => target, querySelectorAll: () => [target] };
    const noop = () => {};
    const context = vm.createContext({
      state: { book: { id: 'a', totalPages: 2 }, page: {}, currentPage: 1, editorEpoch: 1, blocks: [], view: 'reading' },
      pageProgress: { setPage: noop }, pageInput: { project: noop },
      $: () => element, $$: () => [], renderPaper: (_paper, options) => { callbacks = options; },
      renderPageMessage: noop, renderReadingProgress: noop, syncEvidenceToggle: noop, renderQuality: noop,
      renderBookmarkButton: noop, renderToc: noop, renderReview: noop, syncOverlays: noop,
      openDrawer: noop, saveReadingPosition: noop, retryCurrentPage: noop, reloadCurrentPage: noop,
      document: { body: { classList: { toggle: noop } } }, window: { matchMedia: () => ({ matches: true }) },
      requestAnimationFrame: callback => frames.push(callback)
    });
    vm.runInContext(functionSource('function renderCurrent(', 'function saveReadingPosition('), context);
    context.renderCurrent(false);
    callbacks[action]('block', 'issue');
    context.state.book = { id: 'b', totalPages: 2 };
    context.state.editorEpoch++;
    while (frames.length) frames.shift()();
    assert.deepEqual(actions, []);
  });
}
