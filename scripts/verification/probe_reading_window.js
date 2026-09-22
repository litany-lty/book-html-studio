'use strict';
// node scripts/verification/probe_reading_window.js — no server or cloud calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};

(async () => {
  const source = fs.readFileSync('src/main/resources/static/reading-window.js', 'utf8');
  const { nearbyPages, createReadingWindow } = await import(`data:text/javascript,${encodeURIComponent(source)}`);
  assert.deepEqual(nearbyPages(1, 12), [2, 3, 4, 5, 6]);
  assert.deepEqual(nearbyPages(7, 12), [6, 8, 5, 9, 4, 10, 3, 11, 2, 12]);
  const posts = [], stops = [], pageCalls = [], ready = [], errors = [];
  const pendingPage = () => { const result = deferred(); pageCalls.push(result); return result.promise; };
  const state = { book: { id: 'book', totalPages: 80 }, currentPage: 30, pageCache: new Map() };
  let sessionId;
  const snapshot = body => ({ sessionId: body.sessionId, sequence: body.sequence, enabled: true, status: 'SETTLING',
    centerPage: body.currentPage, fromPage: body.currentPage - 5, toPage: body.currentPage + 5, pages: [] });
  const api = {
    readingWindow: async (_, body) => { posts.push(body); sessionId = body.sessionId; return snapshot(body); },
    readingWindowStatus: async () => ({ sessionId, sequence: posts.at(-1).sequence, status: 'PROCESSING',
      centerPage: state.currentPage, pages: [{ pageNumber: 30, status: 'READY', revision: 2 }] }),
    stopReadingWindow: async (_, body) => { stops.push(body); },
    page: () => pendingPage()
  };
  const controller = createReadingWindow({ api, state, onStatus: () => {},
    onPageReady: (n, page) => ready.push([n, page.revision]), onError: error => errors.push(error) });
  await controller.enable({ provider: 'paddle-aistudio', layout: 'auto', splitSpreads: true, assist: false });
  assert.equal(posts.length, 1);
  assert.equal(posts[0].currentPage, 30);
  assert.equal(posts[0].sequence, 1);
  assert.equal(posts[0].start, true, 'explicit enable alone may create a new server session');
  await controller.refreshStatus();
  assert.equal(pageCalls.length, 1, 'READY page revalidation starts locally');
  state.currentPage = 42;
  controller.navigated();
  assert.equal(posts.at(-1).currentPage, 42, 'new center posts without waiting for page JSON');
  assert.equal(posts.at(-1).sequence, 2);
  assert.equal(posts.at(-1).start, false, 'confirmed session navigation cannot recreate after restart');
  pageCalls[0].resolve({ pageNumber: 30, revision: 2, status: 'READY' });
  await delay(0);
  assert.deepEqual(ready, [], 'old center response cannot update new view');
  await delay(1050);
  assert.equal(pageCalls.length, 3, 'two neighbor reads reserve capacity for the current page');
  state.currentPage = 50;
  controller.navigated();
  pageCalls[1].resolve({ pageNumber: 41, revision: 1, status: 'PENDING' });
  pageCalls[2].resolve({ pageNumber: 43, revision: 1, status: 'PENDING' });
  await delay(0);
  assert.equal(state.pageCache.size, 0, 'old-window prefetch cannot pollute new cache');
  await controller.stop();
  assert.equal(stops[0].sequence, posts.at(-1).sequence + 1);
  assert.equal(controller.active(), false);
  assert.deepEqual(errors, []);

  const expired = createReadingWindow({ api: {
    readingWindow: async (_, body) => ({ ...snapshot(body), enabled: true }),
    readingWindowStatus: async () => ({ sessionId: expirySession, sequence: 1, enabled: false, status: 'EXPIRED', pages: [] }),
    stopReadingWindow: async () => {}, page: () => pendingPage()
  }, state, onStatus: value => { if (value?.sessionId) expirySession = value.sessionId; },
  onPageReady: () => {}, onError: error => errors.push(error) });
  let expirySession;
  await expired.enable({ provider: 'ppocr', layout: 'auto', splitSpreads: false, assist: false });
  assert.equal(expired.active(), true);
  await expired.refreshStatus();
  assert.equal(expired.active(), false, 'expired session cannot keep heartbeating or block manual processing');

  // Drive the heartbeat with a fake timer; it must never carry start:true.
  const realSetTimeout = global.setTimeout, realClearTimeout = global.clearTimeout;
  const scheduled = new Map();
  let timerId = 0;
  global.setTimeout = (callback, milliseconds) => { const id = ++timerId; scheduled.set(id, { callback, milliseconds }); return id; };
  global.clearTimeout = id => { scheduled.delete(id); };
  try {
    const heartbeatPosts = [];
    const heart = createReadingWindow({ api: {
      readingWindow: async (_, body) => { heartbeatPosts.push(body); return snapshot(body); },
      readingWindowStatus: async () => ({}), stopReadingWindow: async () => {}, page: () => pendingPage()
    }, state, onStatus: () => {}, onPageReady: () => {}, onError: error => errors.push(error) });
    await heart.enable({ provider: 'ppocr', layout: 'auto', splitSpreads: false, assist: false, start: true });
    assert.equal(heartbeatPosts[0].start, true);
    const heartbeat = [...scheduled.values()].find(timer => timer.milliseconds === 20000);
    assert.ok(heartbeat, 'heartbeat is scheduled');
    await heartbeat.callback();
    assert.equal(heartbeatPosts[1].start, false, 'heartbeat never creates an unknown session');
    await heart.stop();

    const firstPost = deferred(), unconfirmedPosts = [];
    const unconfirmed = createReadingWindow({ api: {
      readingWindow: (_, body) => { unconfirmedPosts.push(body); return unconfirmedPosts.length === 1 ? firstPost.promise : Promise.resolve(snapshot(body)); },
      readingWindowStatus: async () => ({}), stopReadingWindow: async () => {}, page: () => pendingPage()
    }, state, onStatus: () => {}, onPageReady: () => {}, onError: error => errors.push(error) });
    state.currentPage = 60;
    const enabling = unconfirmed.enable({ provider: 'ppocr', layout: 'auto', splitSpreads: false, assist: false });
    state.currentPage = 61;
    unconfirmed.navigated();
    assert.deepEqual(unconfirmedPosts.map(body => body.start), [true, true], 'user navigation may establish an unconfirmed session');
    firstPost.resolve(snapshot(unconfirmedPosts[0]));
    await enabling;
    await unconfirmed.stop();

    const stopAck = deferred(), stopStatuses = [];
    const stopController = createReadingWindow({ api: {
      readingWindow: async (_, body) => snapshot(body), readingWindowStatus: async () => ({}),
      stopReadingWindow: () => stopAck.promise, page: () => pendingPage()
    }, state, onStatus: value => stopStatuses.push(value?.status),
    onPageReady: () => {}, onError: error => errors.push(error) });
    await stopController.enable({ provider: 'ppocr', layout: 'auto', splitSpreads: false, assist: false });
    const stopping = stopController.stop();
    assert.equal(stopStatuses.at(-1), 'STOPPING', 'requesting stop is not yet acknowledged');
    stopAck.resolve({}); await stopping;
    assert.equal(stopStatuses.at(-1), 'STOPPED', 'only the acknowledged stop is labelled stopped');

    const lateAck = deferred(), lateStatuses = [];
    let stopCalls = 0;
    const resumed = createReadingWindow({ api: {
      readingWindow: async (_, body) => snapshot(body), readingWindowStatus: async () => ({}),
      stopReadingWindow: () => ++stopCalls === 1 ? lateAck.promise : Promise.resolve({}), page: () => pendingPage()
    }, state, onStatus: value => lateStatuses.push(value?.status),
    onPageReady: () => {}, onError: error => errors.push(error) });
    await resumed.enable({ provider: 'ppocr', layout: 'auto', splitSpreads: false, assist: false });
    const oldStop = resumed.stop();
    await resumed.enable({ provider: 'ppocr', layout: 'auto', splitSpreads: false, assist: false });
    lateAck.resolve({}); await oldStop;
    assert.equal(lateStatuses.at(-1), 'SETTLING', 'old stop ACK cannot overwrite the new session');
    await resumed.stop();
  } finally { global.setTimeout = realSetTimeout; global.clearTimeout = realClearTimeout; }

  // Run the actual jump-input handler: a second target must not wait behind a slow first GET.
  const app = fs.readFileSync('src/main/resources/static/app.js', 'utf8');
  const start = app.indexOf('async function commitPageInput()');
  const end = app.indexOf('\n}', start);
  assert.ok(start >= 0 && end > start);
  const jump = { value: '30' }, jumps = [], requests = [];
  const context = vm.createContext({ $: () => jump, goToPage: n => {
    jumps.push(n); const request = deferred(); requests.push(request); return request.promise;
  } });
  vm.runInContext(`let pendingPageTarget = null; let pageInputIntent = 0; ${app.slice(start, end + 2)}`, context);
  const first = vm.runInContext('commitPageInput()', context);
  jump.value = '30';
  await vm.runInContext('commitPageInput()', context);
  assert.deepEqual(jumps, [30], 'duplicate change/submit is coalesced');
  jump.value = '42';
  const second = vm.runInContext('commitPageInput()', context);
  assert.deepEqual(jumps, [30, 42], 'new target starts before old request settles');
  requests[1].resolve(true); await second;
  requests[0].resolve(false); await first;
  jump.value = '50';
  const third = vm.runInContext('commitPageInput()', context);
  assert.deepEqual(jumps, [30, 42, 50], 'old finally cannot retain pending intent');
  requests[2].resolve(true); await third;

  // The window snapshot updates only the touched pages; polling unchanged metadata is inert.
  const extract = name => {
    const at = app.indexOf(`function ${name}(`);
    const close = app.indexOf('\n}', at);
    assert.ok(at >= 0 && close > at, `${name} is present`);
    return app.slice(at, close + 2);
  };
  const initial = n => ({ pageNumber: n, status: 'PENDING', blockCount: 0, uncertainCount: 0,
    width: 100, height: 100, title: `第 ${n} 页`, reviewed: false });
  const metadataState = { book: { id: 'book', totalPages: 60, processedPages: 0, reviewedPages: 0 },
    books: [], summaries: Array.from({ length: 60 }, (_, index) => initial(index + 1)), outline: [],
    currentPage: 42, page: { revision: 0 }, pageCache: new Map() };
  const renders = { book: 0, toc: 0 };
  const metadataContext = vm.createContext({ state: metadataState, Map, Number, JSON,
    renderBookMeta: () => { renders.book++; }, renderToc: () => { renders.toc++; } });
  vm.runInContext(`const readingMetadataSignatures = new Map(); const readingMetadataVersions = new Map(); const readingMetadataProfiles = new Map();
    ${extract('olderRevision')} ${extract('sameOutline')} ${extract('mergeReadingMetadata')}`, metadataContext);
  const ready42 = { pageNumber: 42, status: 'READY', revision: 1,
    summary: { ...initial(42), status: 'READY', blockCount: 1, title: '章节' },
    outline: [{ pageNumber: 42, blockId: 'h42', title: '章节', level: 1 }] };
  metadataContext.snapshot = { pages: [ready42] };
  vm.runInContext('mergeReadingMetadata(snapshot)', metadataContext);
  assert.equal(metadataState.book.processedPages, 1);
  assert.equal(metadataState.summaries[41].status, 'READY');
  assert.equal(metadataState.outline[0].blockId, 'h42');
  assert.deepEqual(renders, { book: 1, toc: 1 });
  vm.runInContext('mergeReadingMetadata(snapshot)', metadataContext);
  assert.deepEqual(renders, { book: 1, toc: 1 }, 'identical polling does not rerender');
  metadataContext.snapshot = { pages: [{ pageNumber: 42, status: 'PENDING', revision: 0,
    summary: initial(42), outline: [] }] };
  vm.runInContext('mergeReadingMetadata(snapshot)', metadataContext);
  assert.equal(metadataState.book.processedPages, 1, 'older snapshot cannot regress local counts');
  console.log('READING_WINDOW_PROBE_PASS');
})().catch(error => { console.error(error); process.exit(1); });
