import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Deterministic, zero-network body lifecycle probes. No wall-clock sleeps.
const timers = new Map();
let timerId = 0;
globalThis.window = {
  setTimeout(callback) { timers.set(++timerId, callback); return timerId; },
  clearTimeout(id) { timers.delete(id); },
};
const source = await readFile(new URL('../../src/main/resources/static/api.js', import.meta.url), 'utf8');
const { api } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function pendingBody() {
  const entered = deferred(), body = deferred();
  globalThis.fetch = async (_url, { signal }) => ({
    ok: true, status: 200,
    json() {
      const abort = () => body.reject(Object.assign(new Error('aborted'), { name: 'AbortError' }));
      if (signal.aborted) abort();
      else signal.addEventListener('abort', abort, { once: true });
      entered.resolve();
      return body.promise.finally(() => signal.removeEventListener('abort', abort));
    },
  });
  return { entered: entered.promise, body };
}
{
  const fixture = pendingBody();
  const pending = api.page('book', 1);
  await fixture.entered;
  assert.equal(timers.size, 1, 'timer must cover the pending JSON body');
  fixture.body.resolve({ pageNumber: 1, blocks: [], revision: 4 });
  assert.deepEqual(await pending, { pageNumber: 1, blocks: [], revision: 4 });
  assert.equal(timers.size, 0);
}
{
  const fixture = pendingBody(), external = new AbortController();
  const pending = api.page('book', 1, external.signal);
  await fixture.entered;
  external.abort();
  await assert.rejects(pending, { name: 'StaleRequest' });
  assert.equal(timers.size, 0);
}
{
  const fixture = pendingBody();
  const pending = api.page('book', 1);
  await fixture.entered;
  [...timers.values()][0]();
  await assert.rejects(pending, { name: 'TimeoutError' });
  assert.equal(timers.size, 0);
}
{
  const fixture = pendingBody();
  const pending = api.page('book', 1);
  await fixture.entered;
  fixture.body.reject(new SyntaxError('invalid JSON'));
  await assert.rejects(pending, SyntaxError);
  assert.equal(timers.size, 0);
}
{
  globalThis.fetch = async () => ({ ok: true, status: 204, json() { throw new Error('204 has no body'); } });
  await assert.rejects(api.page('book', 1), /页面响应与请求页/);
  assert.equal(await api.settings(), null, 'generic 204 still has no JSON body');
  assert.equal(timers.size, 0);
}
console.log('PASS: 5 API full-body lifecycle cases; no real network calls');
