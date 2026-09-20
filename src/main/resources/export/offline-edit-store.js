(() => {
  'use strict';
  // A1-03：离线修订的浏览器事务存储适配器。
  // IndexedDB 逐记录比较更新（同一事务内读版本→比对→写回），只在 transaction.oncomplete
  // 后报告已保存。storage/BroadcastChannel 只作变更通知，不担任锁或事实来源。
  const DB_NAME = 'book-html-offline-edits';
  const DB_VERSION = 1;

  function recordKey(bookUid, sourcePage, blockId, issueId) {
    return `${bookUid}\u0000${sourcePage}\u0000${blockId}\u0000${issueId}`;
  }

  function openDatabase() {
    return new Promise((resolve, reject) => {
      let request;
      try {
        request = indexedDB.open(DB_NAME, DB_VERSION);
      } catch (error) {
        reject(error);
        return;
      }
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains('records')) {
          const store = db.createObjectStore('records', { keyPath: 'key' });
          store.createIndex('byPage', ['bookUid', 'sourcePage'], { unique: false });
        }
        if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta', { keyPath: 'name' });
        if (!db.objectStoreNames.contains('quarantine')) db.createObjectStore('quarantine', { keyPath: 'qkey' });
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error('indexeddb-open-failed'));
    });
  }

  // 能力检查：在真实事务里写→读→删一条探针记录。file:// 行为按浏览器如实返回。
  function probeTransaction(db) {
    return new Promise(resolve => {
      try {
        const tx = db.transaction('records', 'readwrite');
        const store = tx.objectStore('records');
        const probe = { key: '__probe__\u0000 rejoice', bookUid: '__probe__', sourcePage: -1, blockId: '', issueId: '' };
        store.put(probe);
        const get = store.get(probe.key);
        let seen = null;
        get.onsuccess = () => { seen = get.result || null; };
        store.delete(probe.key);
        tx.oncomplete = () => resolve(!!seen && seen.key === probe.key);
        tx.onerror = () => resolve(false);
        tx.onabort = () => resolve(false);
      } catch (_) {
        resolve(false);
      }
    });
  }

  async function openIdb() {
    const db = await openDatabase();
    const ok = await probeTransaction(db);
    if (!ok) { try { db.close(); } catch (_) { /* ignore */ } return null; }
    return db;
  }

  function txPromise(db, mode, work) {
    // work(store, tx) 发起请求；只在 oncomplete 后 resolve（单个 put 成功不等于提交）。
    return new Promise((resolve, reject) => {
      let result = { status: 'UNKNOWN' };
      let tx;
      try {
        tx = db.transaction('records', mode);
      } catch (error) {
        reject(error);
        return;
      }
      const store = tx.objectStore('records');
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => reject(tx.error || new Error('indexeddb-tx-failed'));
      tx.onabort = () => reject(tx.error || new Error('indexeddb-tx-aborted'));
      try {
        result = work(store, tx) || result;
      } catch (error) {
        try { tx.abort(); } catch (_) { /* ignore */ }
        reject(error);
      }
    });
  }

  function notify(bookUid, message) {
    try {
      if (typeof BroadcastChannel !== 'undefined') {
        const channel = new BroadcastChannel('book-html-edits');
        channel.postMessage({ ...message, bookUid });
        channel.close();
      }
    } catch (_) { /* 通知失败不影响已提交事实 */ }
  }

  function makeIdbBackend(db, bookUid) {
    return {
      mode: 'indexeddb',
      async loadPage(sourcePage) {
        return txPromise(db, 'readonly', store => {
          const box = { records: [] };
          const request = store.index('byPage').getAll([bookUid, sourcePage]);
          request.onsuccess = () => { box.records = request.result || []; };
          return box;
        });
      },
      async commit(entry, expectedEditRevision) {
        const key = recordKey(bookUid, entry.sourcePage, entry.blockId, entry.issueId);
        const outcome = await txPromise(db, 'readwrite', store => {
          const box = { status: 'UNKNOWN' };
          const get = store.get(key);
          get.onsuccess = () => {
            const current = get.result || null;
            const currentRev = current ? current.editRevision : 0;
            if (expectedEditRevision != null && currentRev !== expectedEditRevision) {
              box.status = 'CONFLICT';
              box.remote = current;
              return;
            }
            const next = {
              key, bookUid,
              sourcePage: entry.sourcePage, blockId: entry.blockId, issueId: entry.issueId,
              editRevision: currentRev + 1,
              original: entry.original, simplified: entry.simplified,
              issueBasis: entry.issueBasis,
              resolved: entry.resolved, replacement: entry.replacement,
              updatedAt: Date.now(),
            };
            const put = store.put(next);
            put.onsuccess = () => { box.status = 'SAVED'; box.editRevision = next.editRevision; };
          };
          return box;
        });
        if (outcome.status === 'SAVED') notify(bookUid, { type: 'edit-saved', key });
        return outcome;
      },
      async exportBackup() {
        const all = await txPromise(db, 'readonly', store => {
          const box = { records: [] };
          const cursor = store.openCursor();
          cursor.onsuccess = () => {
            const c = cursor.result;
            if (c) {
              if (c.value && c.value.bookUid === bookUid) box.records.push({ ...c.value });
              c.continue();
            }
          };
          return box;
        });
        return { schemaVersion: 1, kind: 'book-html-offline-backup', bookUid, exportedAt: Date.now(), records: all.records };
      },
      async importBackupPreview(backup) {
        const result = { appliable: [], conflicts: [], invalid: [] };
        const list = backup && Array.isArray(backup.records) ? backup.records : null;
        if (!list) return { ...result, invalid: [{ reason: 'NOT_A_BACKUP' }] };
        for (const item of list) {
          if (!item || typeof item !== 'object' || item.bookUid !== bookUid
            || !Number.isInteger(item.sourcePage) || typeof item.blockId !== 'string'
            || typeof item.issueId !== 'string' || !item.issueBasis || typeof item.issueBasis !== 'object') {
            result.invalid.push({ reason: 'INVALID_RECORD', key: (item && item.key) || null });
            continue;
          }
          const current = await txPromise(db, 'readonly', store => {
            const box = { current: null };
            const get = store.get(recordKey(bookUid, item.sourcePage, item.blockId, item.issueId));
            get.onsuccess = () => { box.current = get.result || null; };
            return box;
          });
          if (!current.current) result.appliable.push({ key: item.key, mode: 'create' });
          else result.conflicts.push({ key: item.key, remoteEditRevision: current.current.editRevision });
        }
        return result;
      },
      async confirmImport(items) {
        const results = [];
        for (const item of items || []) {
          try {
            // 先读当前版本再提交：并发导入同一记录时恰好一个成功，另一个明确冲突
            const current = await txPromise(db, 'readonly', store => {
              const box = { current: null };
              const get = store.get(recordKey(bookUid, item.sourcePage, item.blockId, item.issueId));
              get.onsuccess = () => { box.current = get.result || null; };
              return box;
            });
            const outcome = await this.commit(
              {
                sourcePage: item.sourcePage, blockId: item.blockId, issueId: item.issueId,
                original: item.original, simplified: item.simplified, issueBasis: item.issueBasis,
                resolved: item.resolved, replacement: item.replacement,
              },
              current.current ? current.current.editRevision : 0);
            results.push({ key: item.key, ...outcome });
          } catch (error) {
            results.push({ key: item && item.key, status: 'STORAGE_FAILED', reason: String((error && error.message) || error) });
          }
        }
        return results;
      },
      onExternalChange(handler) {
        if (typeof BroadcastChannel === 'undefined') return () => {};
        const channel = new BroadcastChannel('book-html-edits');
        const listener = event => {
          const message = event && event.data;
          if (message && message.bookUid === bookUid && typeof handler === 'function') handler(message);
        };
        channel.addEventListener('message', listener);
        return () => { try { channel.close(); } catch (_) { /* ignore */ } };
      },
      close() { try { db.close(); } catch (_) { /* ignore */ } },
    };
  }

  // localStorage 降级后端：同接口、无 CAS；调用方必须显示单标签页横幅，不宣称并发安全。
  function makeLegacyBackend(bookUid) {
    const key = `book-html:${bookUid}:issue-edits:v3-legacy-tx`;
    const readAll = () => {
      try {
        const parsed = JSON.parse(localStorage.getItem(key) || '{}');
        return parsed && typeof parsed === 'object' ? parsed : {};
      } catch (_) { return {}; }
    };
    return {
      mode: 'legacy-localstorage',
      async loadPage(sourcePage) {
        const all = readAll();
        return { records: Object.values(all).filter(r => r && r.sourcePage === sourcePage) };
      },
      async commit(entry) {
        const all = readAll();
        const k = recordKey(bookUid, entry.sourcePage, entry.blockId, entry.issueId);
        const currentRev = all[k] ? all[k].editRevision : 0;
        all[k] = { ...entry, key: k, bookUid, editRevision: currentRev + 1, updatedAt: Date.now() };
        try {
          localStorage.setItem(key, JSON.stringify(all));
        } catch (error) {
          return { status: 'STORAGE_FAILED', reason: (error && error.name) || 'quota' };
        }
        return { status: 'SAVED', editRevision: currentRev + 1, legacy: true };
      },
      async exportBackup() {
        return { schemaVersion: 1, kind: 'book-html-offline-backup', bookUid, exportedAt: Date.now(), records: Object.values(readAll()) };
      },
      async importBackupPreview() { return { appliable: [], conflicts: [], invalid: [{ reason: 'LEGACY_NO_IMPORT_PREVIEW' }] }; },
      async confirmImport() { return [{ status: 'STORAGE_FAILED', reason: 'LEGACY_NO_IMPORT' }]; },
      onExternalChange() { return () => {}; },
      close() {},
    };
  }

  globalThis.OfflineEditStore = {
    recordKey,
    async open(bookUid) {
      if (typeof indexedDB === 'undefined') {
        return { backend: makeLegacyBackend(bookUid), mode: 'legacy-localstorage', reason: 'NO_INDEXEDDB' };
      }
      try {
        const db = await openIdb();
        if (!db) return { backend: makeLegacyBackend(bookUid), mode: 'legacy-localstorage', reason: 'PROBE_FAILED' };
        return { backend: makeIdbBackend(db, bookUid), mode: 'indexeddb', reason: null };
      } catch (error) {
        return { backend: makeLegacyBackend(bookUid), mode: 'legacy-localstorage', reason: String((error && error.message) || error) };
      }
    },
  };
})();
