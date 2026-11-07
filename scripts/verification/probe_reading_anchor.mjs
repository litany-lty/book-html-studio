import assert from 'node:assert/strict';
import { test } from 'node:test';
import { recordAnchor, restoreAnchor } from '../../src/main/resources/static/reading-anchor.js';

test('refresh retains the first visible block at its original viewport offset across nested layout', () => {
  const first = { dataset: { blockId: 'first' }, offsetTop: 10, getBoundingClientRect: () => ({ top: -100, bottom: -20 }) };
  const visible = { dataset: { blockId: 'visible' }, offsetTop: 80, getBoundingClientRect: () => ({ top: 250, bottom: 350 }) };
  const root = { scrollTop: 500, clientHeight: 600, getBoundingClientRect: () => ({ top: 100 }),
    querySelectorAll: () => [first, visible], querySelector: selector => selector.includes('visible') ? visible : first };
  globalThis.window = { getSelection: () => null };
  globalThis.CSS = { escape: value => value };
  const anchor = recordAnchor(root);
  assert.equal(anchor.fallbackId, 'visible');
  visible.getBoundingClientRect = () => ({ top: 350, bottom: 450 });
  assert.equal(restoreAnchor(root, anchor), true);
  assert.equal(root.scrollTop, 600, 'only the 100px content shift is compensated');
});

test('missing anchored blocks fall back to the prior scroll position', () => {
  const root = { scrollTop: 0, querySelector: () => null };
  assert.equal(restoreAnchor(root, { fallbackId: 'gone', viewportTop: 420 }), true);
  assert.equal(root.scrollTop, 420);
});
