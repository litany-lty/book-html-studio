import assert from 'node:assert/strict';
import { test } from 'node:test';
import { pageAfter, isShortTap } from '../../src/main/resources/static/focus-reading.js';

test('page boundaries never wrap or accept malformed values', () => {
  assert.equal(pageAfter(1, 10, -1), null);
  assert.equal(pageAfter(10, 10, 1), null);
  assert.equal(pageAfter(1, 1, 1), null);
  for (const n of [NaN, Infinity, 0, -1, 1.5, '2', null]) assert.equal(pageAfter(n, 10, 1), null);
  assert.equal(pageAfter(2, undefined, 1), null);
  assert.equal(pageAfter(2, 10, 2), null);
  assert.equal(pageAfter(2, 10, -1), 1);
  assert.equal(pageAfter(2, 10, 1), 3);
});

test('only short, still, unselected primary pointer gestures are taps', () => {
  const start = { pointerId: 1, x: 20, y: 100, time: 20, scrollTop: 0, selected: false };
  const end = { ...start, time: 120 };
  assert.equal(isShortTap(start, end), true);
  for (const change of [{ time: 600 }, { time: 10 }, { x: 40 }, { y: 130 }, { scrollTop: 10 }, { pointerId: 2 }]) {
    assert.equal(isShortTap(start, { ...end, ...change }), false);
  }
  assert.equal(isShortTap({ ...start, selected: true }, end), false);
  assert.equal(isShortTap(null, end), false);
});
