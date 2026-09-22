// Uses only a running SeedBook + SeedUsage fixture. Never run against the daily service.
import assert from 'node:assert/strict';
import http from 'node:http';

const base = new URL(process.argv[2] || 'http://127.0.0.1:18767');
if (base.protocol !== 'http:' || base.hostname !== '127.0.0.1' || !base.port || base.port === '18765')
  throw new Error('Use a dedicated loopback QA port, not the daily tool');
const book = 'aaaaaaaa-1111-1111-1111-111111111111';
const endpoint = path => new URL(path, base);
const read = async path => {
  const response = await fetch(endpoint(path), { signal: AbortSignal.timeout(10_000) });
  assert.equal(response.status, 200, path);
  return { response, data: await response.json() };
};
const fixture = (await read(`/api/books/${book}`)).data;
assert.equal(fixture.title, '合成证据书', 'must be a synthetic fixture');
const { response, data: settings } = await read('/api/settings');
assert.ok(response.headers.get('cache-control').split(',').some(value => value.trim() === 'no-store'));
for (const name of ['accessToken', 'apiKey', 'secretKey'])
  assert.ok(!JSON.stringify(settings).includes(`"${name}":`), 'stored secrets must never be returned');
const cookie = (response.headers.get('set-cookie') || '').split(';')[0];
for (const [name, headers] of [
  ['missing CSRF', {}],
  ['foreign origin', { 'X-Settings-Token': settings.csrfToken, Origin: 'https://invalid.test' }],
  ['different local port', { 'X-Settings-Token': settings.csrfToken, Origin: 'http://127.0.0.1:18765' }]
]) {
  const rejected = await fetch(endpoint('/api/settings'), {
    method: 'PUT', signal: AbortSignal.timeout(10_000),
    headers: { 'Content-Type': 'application/json', Cookie: cookie, ...headers },
    body: JSON.stringify({ revision: settings.revision })
  });
  assert.equal(rejected.status, 403, name);
}
// fetch may normalize Host; use the native HTTP client to test DNS-rebinding protection.
const foreignHost = await new Promise((resolve, reject) => {
  const request = http.get(endpoint('/api/settings'), { headers: { Host: 'invalid.test' }, timeout: 10_000 }, result => {
    result.resume(); resolve(result.statusCode);
  });
  request.on('error', reject); request.on('timeout', () => request.destroy(new Error('timeout')));
});
assert.equal(foreignHost, 403);
const first = (await read(`/api/books/${book}/usage?limit=50`)).data;
assert.equal(first.entries.length, 50);
assert.equal(first.totals.requests, 51);
assert.equal(first.totals.cacheHits, 5);
assert.equal(first.totals.unpricedRequests, 11);
assert.equal(first.totals.unknownInputTokenRequests, 31);
assert.equal(first.totals.unknownOutputTokenRequests, 31);
assert.equal(first.totals.reportedAmounts.length, 0);
assert.deepEqual(first.totals.estimatedAmounts.map(item => `${item.currency}:${item.amount}`).sort(), ['CNY:0.628', 'USD:0.014']);
const query = new URLSearchParams({ limit: '50', cursor: first.nextCursor, asOf: first.asOf });
const second = (await read(`/api/books/${book}/usage?${query}`)).data;
assert.equal(second.entries.length, 6);
assert.equal(second.nextCursor, null);
assert.equal(new Set([...first.entries, ...second.entries].map(entry => entry.id)).size, 56);
assert.equal((await fetch(endpoint(`/api/books/${book}/usage?cursor=invalid`))).status, 400);
console.log('SETTINGS_USAGE_API_PASS: no stored secrets; CSRF/origin/Host protection; synthetic totals, currency, unknown gaps, stable 50+6 cursor pages. No cloud calls.');
