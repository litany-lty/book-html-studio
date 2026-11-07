import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createReadingWindow } from '../../src/main/resources/static/reading-window.js';

test('entering save-data mode cancels existing prefetch before skipping new reads', async () => {
  const timers = new Map(), calls = [];
  let id = 0;
  const previousNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  const previousSetTimeout = globalThis.setTimeout, previousClearTimeout = globalThis.clearTimeout;
  const connection = { saveData: false };
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { connection } });
  globalThis.setTimeout = (callback, delay) => { timers.set(++id, { callback, delay }); return id; };
  globalThis.clearTimeout = timer => timers.delete(timer);
  try {
    const state = { book: { id: 'a', totalPages: 20 }, currentPage: 4, pageCache: new Map() };
    const window = createReadingWindow({ state, api: {
      page: (_book, page, signal) => { calls.push({ page, signal }); return new Promise(() => {}); }
    }, onStatus() {}, onPageReady() {}, onError() {} });
    window.prefetch();
    const timer = [...timers.values()].find(value => value.delay === 1000);
    assert.ok(timer);
    void timer.callback();
    assert.equal(calls.length, 2);
    connection.saveData = true;
    state.currentPage = 12;
    window.navigated();
    assert.ok(calls.every(call => call.signal.aborted), 'old transfers are cancelled even when new prefetch is disabled');
    assert.equal([...timers.values()].filter(value => value.delay === 1000).length, 0);
    await window.stop();
  } finally {
    globalThis.setTimeout = previousSetTimeout;
    globalThis.clearTimeout = previousClearTimeout;
    if (previousNavigator) Object.defineProperty(globalThis, 'navigator', previousNavigator);
    else delete globalThis.navigator;
  }
});
