'use strict';
// T63/T64 离线侧：legacy 后端确认来源往返 + 旧形状兼容（Node 直驱，不依赖浏览器）。
// 用法：node scripts/verification/probe_offline_store.js
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const vm = require('vm');

const store = new Map();
const sandbox = {
  console,
  localStorage: {
    getItem: k => (store.has(String(k)) ? store.get(String(k)) : null),
    setItem: (k, v) => { store.set(String(k), String(v)); },
    removeItem: k => { store.delete(String(k)); },
  },
  window: {},
  document: {},
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
const src = fs.readFileSync('src/main/resources/export/offline-edit-store.js', 'utf8');
vm.runInContext(src, sandbox, { filename: 'offline-edit-store.js' });

(async () => {
  const { OfflineEditStore } = sandbox;
  const opened = await OfflineEditStore.open('book|sha');
  assert.strictEqual(opened.mode, 'legacy-localstorage');
  const backend = opened.backend;
  const basis = { kind: 'suspected', start: 0, end: 2, simplifiedStart: 0, simplifiedEnd: 2, originalQuote: '甲乙', simplifiedQuote: '甲乙' };
  // 含 resolution 的提交往返保留
  const resolution = { origin: 'JEV_ASSISTED', decisionId: 'd1', candidateId: 'c1', candidateSetHash: 'cs1', userAttestedSourceCheck: true };
  const saved = await backend.commit(
    { sourcePage: 1, blockId: 'b1', issueId: 'i1', original: '甲乙丙丁', simplified: '甲乙丙丁', issueBasis: basis, resolved: true, replacement: '甲乙', resolution }, null);
  assert.strictEqual(saved.status, 'SAVED');
  // 无 resolution 的旧条目读为 null，不伪造
  const saved2 = await backend.commit(
    { sourcePage: 1, blockId: 'b2', issueId: 'i2', original: '丙丁', simplified: '丙丁', issueBasis: basis, resolved: false, replacement: '' }, null);
  assert.strictEqual(saved2.status, 'SAVED');
  // 非对象 resolution 消毒为 null
  await backend.commit(
    { sourcePage: 1, blockId: 'b3', issueId: 'i3', original: '戊己', simplified: '戊己', issueBasis: basis, resolved: true, replacement: '戊己', resolution: 'forged-string' }, null);
  const backup = await backend.exportBackup();
  assert.strictEqual(backup.records.length, 3);
  const byId = Object.fromEntries(backup.records.map(r => [r.issueId, r]));
  assert.strictEqual(byId.i1.resolution.origin, 'JEV_ASSISTED');
  assert.strictEqual(byId.i1.resolution.candidateId, 'c1');
  assert.strictEqual(byId.i2.resolution, null);
  assert.strictEqual(byId.i3.resolution, null);
  // legacy 不支持导入预览/导入（明确行为，非静默）
  const preview = await backend.importBackupPreview({});
  assert.strictEqual(preview.invalid[0].reason, 'LEGACY_NO_IMPORT_PREVIEW');
  console.log('OFFLINE_STORE_ALL_PASS');
})().catch(error => { console.error(error); process.exit(1); });
