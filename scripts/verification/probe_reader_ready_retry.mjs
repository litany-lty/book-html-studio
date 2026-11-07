import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createReadingWindow } from '../../src/main/resources/static/reading-window.js';

test('a failed READY read can be retried at the same revision without another processing POST', async () => {
  let reads = 0, posts = 0, sessionId;
  const published = [], errors = [];
  const state = { book: { id: 'a', totalPages: 3 }, currentPage: 1, pageCache: new Map() };
  const snapshot = () => ({ sessionId, sequence: 1, enabled: true, status: 'PROCESSING',
    pages: [{ pageNumber: 1, status: 'READY', revision: 2 }] });
  const controller = createReadingWindow({ state, api: {
    readingWindow: async (_book, body) => { posts++; sessionId = body.sessionId; return snapshot(); },
    readingWindowStatus: async () => snapshot(), stopReadingWindow: async () => {},
    page: async () => { if (++reads === 1) throw new Error('synthetic read failure'); return { pageNumber: 1, status: 'READY', revision: 2 }; }
  }, onStatus() {}, onPageReady: (n, page) => published.push([n, page.revision]), onError: error => errors.push(error) });
  try {
    await controller.enable({ provider: 'fixture' });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(reads, 1); assert.equal(errors.length, 1); assert.deepEqual(published, []);
    await controller.refreshStatus();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(reads, 2); assert.equal(posts, 1); assert.deepEqual(published, [[1, 2]]);
  } finally { await controller.stop(); }
});
