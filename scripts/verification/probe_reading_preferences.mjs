import assert from 'node:assert/strict';
import { test } from 'node:test';
import { getBookmarks, setBookmarks, loadPreferences, savePreferences } from '../../src/main/resources/static/store.js';

test('bookmarks and reading position remain book-specific browser storage', () => {
  const storage = new Map();
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) };
  setBookmarks('a', [3, 1, 3]);
  setBookmarks('b', [2]);
  savePreferences('a', { page: 3, scrollTop: 180 });
  assert.deepEqual(getBookmarks('a'), [1, 3]);
  assert.deepEqual(getBookmarks('b'), [2]);
  assert.deepEqual(loadPreferences('a'), { page: 3, scrollTop: 180 });
  assert.deepEqual(loadPreferences('b'), {});
});

test('corrupt or unavailable browser storage does not block reading', () => {
  globalThis.localStorage = { getItem: () => '{invalid' };
  assert.deepEqual(getBookmarks('a'), []); assert.deepEqual(loadPreferences('a'), {});
  globalThis.localStorage = { getItem: () => '[0,-1,1.5,"3",4,null]' };
  assert.deepEqual(getBookmarks('a'), [4]); assert.deepEqual(loadPreferences('a'), {});
  globalThis.localStorage = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('full'); } };
  assert.deepEqual(getBookmarks('a'), []); assert.deepEqual(loadPreferences('a'), {});
  assert.doesNotThrow(() => setBookmarks('a', [1]));
  assert.doesNotThrow(() => savePreferences('a', { page: 1 }));
});
