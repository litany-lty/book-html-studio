(() => {
  'use strict';
  const readingLayout = globalThis.BookReadingLayout;
  const STORAGE_VERSION = 3;
  const el = (tag, cls, text) => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  };
  const button = (text, action) => {
    const node = el('button', 'button', text);
    node.type = 'button'; node.addEventListener('click', action); return node;
  };
  function cutsSurrogate(text, start, end) {
    const lead = cp => cp >= 0xD800 && cp <= 0xDBFF, trail = cp => cp >= 0xDC00 && cp <= 0xDFFF;
    const at = i => text.charCodeAt(i);
    if (start > 0 && start < text.length && trail(at(start)) && lead(at(start - 1))) return true;
    if (end > 0 && end < text.length && trail(at(end)) && lead(at(end - 1))) return true;
    return false;
  }
  function valid(issue, block) {
    if (!issue || typeof issue.id !== 'string' || !['unreadable', 'suspected'].includes(issue.kind)
      || !Number.isInteger(issue.start) || !Number.isInteger(issue.end)
      || issue.start < 0 || issue.end <= issue.start || issue.end > (block.original || '').length) return false;
    if (cutsSurrogate(block.original || '', issue.start, issue.end)) return false;
    return true;
  }
  // R02/A1-02：长期身份使用源 PDF 页号，不用节选连续页号；同一本书的节选导出共享同一份记录。
  function baseBookId(book) {
    return String((book && book.id) || (book && book.title) || '').split(':selection:')[0];
  }
  function recordKey(sourcePage, blockId) {
    return `${sourcePage}:${blockId}`;
  }
  function validSourcePage(value) {
    return Number.isInteger(value) && value >= 1 && value <= 1000000;
  }
  function validRecord(value, key) {
    if (!value || typeof value !== 'object') return false;
    if (!validSourcePage(value.sourcePage)) return false;
    if (typeof value.blockId !== 'string' || !value.blockId) return false;
    if (key !== recordKey(value.sourcePage, value.blockId)) return false;
    if (typeof value.original !== 'string' || typeof value.simplified !== 'string') return false;
    if (!Array.isArray(value.issues)) return false;
    return value.issues.every(entry => entry && typeof entry.id === 'string'
      && typeof entry.resolved === 'boolean' && typeof entry.replacement === 'string'
      && entry.replacement.length <= 1000);
  }
  function validBasis(basis) {
    return !!basis && typeof basis.kind === 'string'
      && Number.isInteger(basis.start) && Number.isInteger(basis.end)
      && Number.isInteger(basis.simplifiedStart) && Number.isInteger(basis.simplifiedEnd)
      && typeof basis.originalQuote === 'string' && typeof basis.simplifiedQuote === 'string';
  }
  const REASON_TEXT = {
    NO_BASIS: '缺少疑点位置基线，需人工确认后沿用',
    RANGE_CHANGED: '疑点范围已变化，需人工确认',
    QUOTE_CHANGED: '疑点引用文字已变化，需人工确认',
    KIND_CHANGED: '疑点类型已变化，需人工确认',
    BAD_RANGE: '疑点范围非法（可能切断字符），拒绝自动应用',
    BASELINE_CHANGED: '段落文字基线已变化，需人工确认',
    BOOK_MISMATCH: '记录所属书籍与当前不符，暂不应用',
    SOURCE_CHANGED: '原稿已更换，暂不应用旧修订',
    MAPPING_UNKNOWN: '旧记录页号无法映射到源页，暂不应用',
    INVALID_RECORD: '记录格式非法，已隔离保留',
  };
  globalThis.BookIssueReview = {
    create({ book, pages, pageMap, getPage, getScript, render }) {
      const bookUid = `${baseBookId(book)}|${(book && book.sourcePdfSha256) || 'nosha'}`;
      const key = `book-html:${baseBookId(book)}:issue-edits:v${STORAGE_VERSION}`;
      const legacyV2Key = `book-html:${baseBookId(book)}:issue-edits:v2`;
      const legacyV1Key = `book-html:${(book && book.id) || ''}:issue-edits:v1`;
      // ---- legacy localStorage 实现（A1-02；IDB 不可用时降级使用，见下） ----
      let records = {};
      let quarantined = {};
      let storageNote = '';
      let storageUsable = true;
      function markPending(recordId, issueId, reason) {
        const rec = records[recordId];
        if (!rec || rec._dropped) return;
        rec._pending = rec._pending || [];
        if (!rec._pending.some(p => p.issueId === issueId && p.reason === reason)) {
          rec._pending.push({ issueId, reason, sourcePage: rec.sourcePage, blockId: rec.blockId });
        }
      }
      function persist() {
        const clean = {};
        for (const [recordId, value] of Object.entries(records)) {
          const { _pending, ...rest } = value || {};
          clean[recordId] = rest;
        }
        const payload = JSON.stringify({
          schemaVersion: STORAGE_VERSION, bookId: baseBookId(book),
          sourcePdfSha256: (book && book.sourcePdfSha256) || undefined,
          records: clean, quarantined,
        });
        try {
          localStorage.setItem(key, payload);
          return { ok: true };
        } catch (error) {
          const reason = error && error.name === 'QuotaExceededError' ? '存储配额已满' : '浏览器存储不可用';
          return { ok: false, reason };
        }
      }
      function loadStored() {
        let raw = null;
        try { raw = localStorage.getItem(key); }
        catch (_) { storageUsable = false; storageNote = '浏览器本地存储不可用，修改仅在本次打开中有效，请下载备份。'; return; }
        if (!raw) { migrateLegacy(); return; }
        let parsed = null;
        try { parsed = JSON.parse(raw); }
        catch (_) {
          storageNote = '浏览器本地修改记录损坏无法读取，本次修改可重新保存；建议及时下载备份。';
          quarantined.__corrupt = { reason: 'JSON_PARSE_FAILED', rawLength: raw.length };
          return;
        }
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
          quarantined.__corrupt = { reason: 'INVALID_SHAPE' };
          return;
        }
        if (parsed.schemaVersion === STORAGE_VERSION && parsed.records && typeof parsed.records === 'object') {
          if (parsed.bookId && parsed.bookId !== baseBookId(book)) {
            for (const [recordId, value] of Object.entries(parsed.records)) {
              quarantined[recordId] = { reason: 'BOOK_MISMATCH', value };
            }
            storageNote = '浏览器中的修改记录属于另一本书，已隔离保留，未应用。';
            return;
          }
          for (const [recordId, value] of Object.entries(parsed.records)) {
            if (validRecord(value, recordId)) records[recordId] = { ...value };
            else quarantined[recordId] = { reason: 'INVALID_RECORD', value };
          }
          if (parsed.quarantined && typeof parsed.quarantined === 'object') {
            for (const [recordId, value] of Object.entries(parsed.quarantined)) {
              if (!(recordId in records)) quarantined[recordId] = value;
            }
          }
          return;
        }
        quarantined.__unknown = { reason: 'UNKNOWN_SCHEMA', schemaVersion: parsed.schemaVersion ?? null };
        storageNote = '浏览器中的修改记录版本无法识别，已隔离保留，请下载备份后处理。';
      }
      function migrateLegacy() {
        let migrated = 0;
        try {
          const raw = localStorage.getItem(legacyV2Key);
          if (raw) {
            const parsed = JSON.parse(raw);
            if (parsed && parsed.bookId && parsed.bookId !== baseBookId(book)) {
              quarantined.__v2 = { reason: 'BOOK_MISMATCH' };
            } else if (parsed && parsed.records && typeof parsed.records === 'object') {
              for (const [recordId, value] of Object.entries(parsed.records)) {
                if (!value || typeof value !== 'object' || !validSourcePage(value.sourcePage)
                    || typeof value.blockId !== 'string' || !value.blockId
                    || recordId !== recordKey(value.sourcePage, value.blockId)
                    || typeof value.original !== 'string' || typeof value.simplified !== 'string'
                    || !Array.isArray(value.issues)) {
                  quarantined[recordId] = { reason: 'INVALID_RECORD', value };
                  continue;
                }
                records[recordId] = {
                  sourcePage: value.sourcePage, blockId: value.blockId,
                  original: value.original, simplified: value.simplified,
                  issues: value.issues.filter(e => e && typeof e.id === 'string'),
                  _pending: value.issues.filter(e => e && typeof e.id === 'string')
                    .map(e => ({ issueId: e.id, reason: 'NO_BASIS', sourcePage: value.sourcePage, blockId: value.blockId })),
                };
                migrated++;
              }
            }
          }
        } catch (_) { /* 旧 key 损坏则保留不动 */ }
        try {
          const raw = localStorage.getItem(legacyV1Key);
          if (raw && (book && book.id) && pageMap && typeof pageMap === 'object') {
            const parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
              for (const [recordId, value] of Object.entries(parsed)) {
                if (!value || typeof value !== 'object' || typeof value.original !== 'string'
                    || typeof value.simplified !== 'string' || !Array.isArray(value.issues)) {
                  quarantined[recordId] = { reason: 'INVALID_RECORD', value };
                  continue;
                }
                const [localPage, ...rest] = String(recordId).split(':');
                const blockId = rest.join(':');
                const sourcePage = Number(pageMap[localPage] ?? pageMap[Number(localPage)]);
                if (!blockId || !validSourcePage(sourcePage)) {
                  quarantined[recordId] = { reason: 'MAPPING_UNKNOWN', value };
                  continue;
                }
                const stable = recordKey(sourcePage, blockId);
                if (!records[stable]) {
                  records[stable] = {
                    sourcePage, blockId, original: value.original, simplified: value.simplified,
                    issues: value.issues.filter(e => e && typeof e.id === 'string'),
                    _pending: value.issues.filter(e => e && typeof e.id === 'string')
                      .map(e => ({ issueId: e.id, reason: 'NO_BASIS', sourcePage, blockId, origin: 'migrated-v1' })),
                  };
                  migrated++;
                }
              }
            }
          } else if (raw) {
            quarantined.__v1 = { reason: 'MAPPING_UNKNOWN' };
          }
        } catch (_) { /* 旧 key 损坏则保留不动 */ }
        if (migrated > 0) {
          persist();
          storageNote = `已把旧版修改记录升级（${migrated} 条待核验），旧数据原样保留。`;
        }
      }
      function captureBasis(block, issue) {
        return {
          kind: issue.kind, start: issue.start, end: issue.end,
          simplifiedStart: issue.simplifiedStart, simplifiedEnd: issue.simplifiedEnd,
          originalQuote: (block.original || '').slice(issue.start, issue.end),
          simplifiedQuote: (typeof issue.simplifiedStart === 'number' && typeof issue.simplifiedEnd === 'number')
            ? (block.simplified || '').slice(issue.simplifiedStart, issue.simplifiedEnd) : '',
        };
      }
      function basisMatches(block, issue, basis) {
        if (!validBasis(basis)) return 'NO_BASIS';
        if (basis.kind !== issue.kind) return 'KIND_CHANGED';
        if (basis.start !== issue.start || basis.end !== issue.end
          || basis.simplifiedStart !== issue.simplifiedStart || basis.simplifiedEnd !== issue.simplifiedEnd) return 'RANGE_CHANGED';
        if ((block.original || '').slice(issue.start, issue.end) !== basis.originalQuote) return 'QUOTE_CHANGED';
        if (typeof issue.simplifiedStart === 'number' && typeof issue.simplifiedEnd === 'number'
          && (block.simplified || '').slice(issue.simplifiedStart, issue.simplifiedEnd) !== basis.simplifiedQuote) return 'QUOTE_CHANGED';
        if (cutsSurrogate(block.original || '', issue.start, issue.end)) return 'BAD_RANGE';
        return null;
      }
      // R02/A1-02：在正文加载成功后、渲染前调用；重复调用幂等；存疑只计数不套用。
      function hydratePage(page) {
        const counts = { applied: 0, pending: 0 };
        if (!page) return counts;
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        if (!validSourcePage(sourcePage)) return counts;
        for (const block of page.blocks || []) {
          const saved = records[recordKey(sourcePage, block.id)];
          if (!saved) continue;
          if (saved.original !== block.original || saved.simplified !== block.simplified) {
            for (const entry of saved.issues || []) {
              if (entry && typeof entry.id === 'string') markPending(recordKey(sourcePage, block.id), entry.id, 'BASELINE_CHANGED');
            }
            counts.pending++;
            continue;
          }
          if (!Array.isArray(saved.issues)) continue;
          const baseline = new Map((block.issues || []).map(issue => [issue.id, issue]));
          let applied = false;
          for (const entry of saved.issues) {
            if (!entry || typeof entry.id !== 'string') continue;
            const issue = baseline.get(entry.id);
            if (!valid(issue, block) || typeof entry.resolved !== 'boolean' || typeof entry.replacement !== 'string' || entry.replacement.length > 1000) continue;
            const problem = entry.issueBasis ? basisMatches(block, issue, entry.issueBasis) : 'NO_BASIS';
            if (problem) { markPending(recordKey(sourcePage, block.id), entry.id, problem); counts.pending++; continue; }
            if (issue.resolved !== entry.resolved || issue.replacement !== entry.replacement) {
              issue.resolved = entry.resolved; issue.replacement = entry.replacement; applied = true;
            }
          }
          if (applied) counts.applied++;
        }
        return counts;
      }
      function pendingList(page) {
        const sourcePage = page ? (page.sourcePageNumber || page.pageNumber) : null;
        const out = [];
        for (const [recordId, rec] of Object.entries(records)) {
          for (const p of (rec && rec._pending) || []) {
            if (sourcePage == null || p.sourcePage === sourcePage) {
              out.push({ recordKey: recordId, issueId: p.issueId, reason: p.reason, sourcePage: p.sourcePage, blockId: p.blockId, origin: p.origin });
            }
          }
        }
        for (const item of idbPendingFor(sourcePage)) out.push(item);
        return out;
      }
      function confirmPending(recordKeyId, issueId) {
        const rec = records[recordKeyId];
        if (!rec) return { ok: false, reason: '记录不存在' };
        const page = getPage();
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        const block = (page.blocks || []).find(b => b.id === rec.blockId && recordKey(sourcePage, b.id) === recordKeyId);
        if (!block) return { ok: false, reason: '当前页找不到该内容块' };
        if (rec.original !== block.original || rec.simplified !== block.simplified) {
          return { ok: false, reason: '段落基线已变化，不能确认沿用' };
        }
        const issue = (block.issues || []).find(i => i.id === issueId);
        if (!valid(issue, block)) return { ok: false, reason: '当前疑点范围非法' };
        const savedEntry = (rec.issues || []).find(e => e && e.id === issueId);
        if (!savedEntry) return { ok: false, reason: '记录中没有该疑点' };
        issue.resolved = Boolean(savedEntry.resolved);
        issue.replacement = String(savedEntry.replacement || '');
        rec.issues = (rec.issues || []).filter(e => e && e.id !== issueId);
        rec.issues.push({ id: issueId, resolved: issue.resolved, replacement: issue.replacement, issueBasis: captureBasis(block, issue) });
        rec._pending = (rec._pending || []).filter(p => p.issueId !== issueId);
        const result = persist();
        render(); refresh();
        return result.ok ? { ok: true } : { ok: false, reason: result.reason };
      }
      function save(block) {
        // R02/A1-02：只合并当前块变更，保留其他页记录；成功后才提示“已保存”。
        const page = getPage();
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        if (!validSourcePage(sourcePage)) return { ok: false, reason: '页号非法，拒绝保存' };
        records[recordKey(sourcePage, block.id)] = {
          sourcePage, blockId: block.id,
          original: block.original, simplified: block.simplified,
          issues: (block.issues || []).map(issue => ({
            id: issue.id, resolved: Boolean(issue.resolved), replacement: issue.replacement || '',
            issueBasis: captureBasis(block, issue),
          })),
        };
        records[recordKey(sourcePage, block.id)]._pending = [];
        return persist();
      }
      loadStored();
      // ---- A1-03：事务存储层（IndexedDB），legacy localStorage 仅作降级 ----
      let idb = null;
      let idbReady = null;
      const idbPending = new Map();
      function ensureIdb() {
        if (!idbReady) {
          idbReady = (async () => {
            try {
              if (!globalThis.OfflineEditStore) return { backend: null, mode: 'legacy-localstorage', reason: 'NO_ADAPTER' };
              const opened = await globalThis.OfflineEditStore.open(bookUid);
              idb = opened;
              if (opened.mode === 'indexeddb' && opened.backend) {
                await migrateLegacyToIdb(opened.backend);
                try {
                  opened.backend.onExternalChange(msg => noteExternalChange(msg));
                } catch (_) { /* 通知失败不影响保存 */ }
              }
              return opened;
            } catch (error) {
              return { backend: null, mode: 'legacy-localstorage', reason: String((error && error.message) || error) };
            }
          })();
        }
        return idbReady;
      }
      async function migrateLegacyToIdb(backend) {
        let marker = null;
        try { marker = localStorage.getItem(`${key}:idb-migrated`); } catch (_) { marker = 'unreadable'; }
        if (marker) return { migrated: 0, skipped: true };
        let migrated = 0;
        for (const [recordId, rec] of Object.entries(records)) {
          if (!rec || typeof rec !== 'object' || !validSourcePage(rec.sourcePage)) continue;
          for (const entry of rec.issues || []) {
            if (!entry || typeof entry.id !== 'string') continue;
            try {
              const outcome = await backend.commit({
                sourcePage: rec.sourcePage, blockId: rec.blockId, issueId: entry.id,
                original: rec.original, simplified: rec.simplified,
                issueBasis: entry.issueBasis || null,
                resolved: Boolean(entry.resolved), replacement: String(entry.replacement || ''),
              }, 0);
              if (outcome.status === 'SAVED') migrated++;
              // CONFLICT：另一标签页已先迁入，保留 IDB 版本
            } catch (_) { /* 单条失败不阻断其余 */ }
          }
        }
        try { localStorage.setItem(`${key}:idb-migrated`, JSON.stringify({ at: Date.now(), migrated })); }
        catch (_) { /* 标记写失败则下次重试（幂等） */ }
        return { migrated, skipped: false };
      }
      function idbPendingFor(sourcePage) {
        if (sourcePage == null) {
          const all = [];
          for (const list of idbPending.values()) all.push(...list);
          return all;
        }
        return idbPending.get(sourcePage) || [];
      }
      function rememberIdbPending(sourcePage, items) {
        idbPending.set(sourcePage, items);
      }
      function collectIdbPending(page, stored) {
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        const items = [];
        const byBlock = new Map();
        for (const rec of stored || []) {
          if (!rec || rec.blockId == null) continue;
          if (!byBlock.has(rec.blockId)) byBlock.set(rec.blockId, []);
          byBlock.get(rec.blockId).push(rec);
        }
        for (const block of page.blocks || []) {
          const list = byBlock.get(block.id) || [];
          if (!list.length) continue;
          const probe = list[0];
          if (probe.original !== block.original || probe.simplified !== block.simplified) {
            for (const rec of list) {
              items.push({ recordKey: recordKey(sourcePage, block.id), issueId: rec.issueId, reason: 'BASELINE_CHANGED', sourcePage, blockId: block.id, idb: true, editRevision: rec.editRevision });
            }
            continue;
          }
          const baseline = new Map((block.issues || []).map(issue => [issue.id, issue]));
          for (const rec of list) {
            const issue = baseline.get(rec.issueId);
            if (!valid(issue, block)) continue;
            const problem = rec.issueBasis ? basisMatches(block, issue, rec.issueBasis) : 'NO_BASIS';
            if (problem) {
              items.push({ recordKey: recordKey(sourcePage, block.id), issueId: rec.issueId, reason: problem, sourcePage, blockId: block.id, idb: true, editRevision: rec.editRevision });
            }
          }
        }
        return items;
      }
      async function refreshIdbPending(page) {
        const opened = await ensureIdb();
        if (opened.mode !== 'indexeddb' || !opened.backend || !page) return false;
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        if (!validSourcePage(sourcePage)) return false;
        let stored = [];
        try {
          const loaded = await opened.backend.loadPage(sourcePage);
          stored = (loaded && loaded.records) || [];
        } catch (_) {
          return false;
        }
        const before = JSON.stringify(idbPendingFor(sourcePage));
        const items = collectIdbPending(page, stored);
        rememberIdbPending(sourcePage, items);
        return JSON.stringify(items) !== before;
      }
      // A1-03 主入口：正文加载成功后、渲染前调用；IDB 逐记录读取并校验后应用。
      async function preparePage(page) {
        const opened = await ensureIdb();
        if (!page) return { applied: 0, pending: 0, mode: opened.mode };
        if (opened.mode !== 'indexeddb' || !opened.backend) return { ...hydratePage(page), mode: 'legacy-localstorage' };
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        if (!validSourcePage(sourcePage)) return { applied: 0, pending: 0, mode: opened.mode };
        let stored = [];
        try {
          const loaded = await opened.backend.loadPage(sourcePage);
          stored = (loaded && loaded.records) || [];
        } catch (_) {
          return { applied: 0, pending: 0, mode: opened.mode, error: 'LOAD_FAILED' };
        }
        const counts = { applied: 0, pending: 0, mode: opened.mode };
        const items = collectIdbPending(page, stored);
        const byBlock = new Map();
        for (const rec of stored) {
          if (!rec || rec.blockId == null) continue;
          if (!byBlock.has(rec.blockId)) byBlock.set(rec.blockId, []);
          byBlock.get(rec.blockId).push(rec);
        }
        for (const block of page.blocks || []) {
          const list = byBlock.get(block.id) || [];
          if (!list.length) continue;
          const probe = list[0];
          if (probe.original !== block.original || probe.simplified !== block.simplified) {
            counts.pending += list.length;
            continue;
          }
          const baseline = new Map((block.issues || []).map(issue => [issue.id, issue]));
          let applied = false;
          for (const rec of list) {
            const issue = baseline.get(rec.issueId);
            if (!valid(issue, block)) continue;
            const problem = rec.issueBasis ? basisMatches(block, issue, rec.issueBasis) : 'NO_BASIS';
            if (problem) { counts.pending++; continue; }
            if (issue.resolved !== rec.resolved || issue.replacement !== rec.replacement) {
              issue.resolved = rec.resolved; issue.replacement = rec.replacement; applied = true;
            }
          }
          if (applied) counts.applied++;
        }
        rememberIdbPending(sourcePage, items);
        return counts;
      }
      async function commitIssue(block, issue) {
        const opened = await ensureIdb();
        if (opened.mode !== 'indexeddb' || !opened.backend) {
          const result = save(block);
          return result.ok ? { status: 'SAVED', legacy: true } : { status: 'STORAGE_FAILED', reason: result.reason, legacy: true };
        }
        const page = getPage();
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        if (!validSourcePage(sourcePage)) return { status: 'STORAGE_FAILED', reason: '页号非法，拒绝保存' };
        const entry = {
          sourcePage, blockId: block.id, issueId: issue.id,
          original: block.original, simplified: block.simplified,
          issueBasis: captureBasis(block, issue),
          resolved: Boolean(issue.resolved), replacement: issue.replacement || '',
        };
        try {
          const loaded = await opened.backend.loadPage(sourcePage);
          const current = (loaded.records || []).find(r => r.blockId === block.id && r.issueId === issue.id) || null;
          const outcome = await opened.backend.commit(entry, current ? current.editRevision : 0);
          if (outcome.status === 'SAVED') {
            rememberIdbPending(sourcePage, collectIdbPending(page, [...(loaded.records || []).filter(r => !(r.blockId === block.id && r.issueId === issue.id)),
              { ...entry, editRevision: outcome.editRevision }]));
            return { status: 'SAVED', editRevision: outcome.editRevision };
          }
          return outcome;
        } catch (error) {
          return { status: 'STORAGE_FAILED', reason: String((error && error.message) || error) };
        }
      }
      async function confirmIdbPending(recordKeyId, issueId) {
        const opened = await ensureIdb();
        if (opened.mode !== 'indexeddb' || !opened.backend) return confirmPending(recordKeyId, issueId);
        const page = getPage();
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        const block = (page.blocks || []).find(b => b.id === recordKeyId.split(':').slice(1).join(':'));
        if (!block) return { ok: false, reason: '当前页找不到该内容块' };
        const issue = (block.issues || []).find(i => i.id === issueId);
        if (!valid(issue, block)) return { ok: false, reason: '当前疑点范围非法' };
        let savedEntry = null;
        try {
          const loaded = await opened.backend.loadPage(sourcePage);
          savedEntry = (loaded.records || []).find(r => r.blockId === block.id && r.issueId === issueId) || null;
        } catch (error) {
          return { ok: false, reason: String((error && error.message) || error) };
        }
        if (!savedEntry) return { ok: false, reason: '记录不存在' };
        if (savedEntry.original !== block.original || savedEntry.simplified !== block.simplified) {
          return { ok: false, reason: '段落基线已变化，不能确认沿用' };
        }
        issue.resolved = Boolean(savedEntry.resolved);
        issue.replacement = String(savedEntry.replacement || '');
        const result = await commitIssue(block, issue);
        if (result.status === 'SAVED') {
          render(); refresh();
          draw('已确认为当前基线的修订。');
          return { ok: true };
        }
        if (result.status === 'CONFLICT') return { ok: false, reason: '另一页面刚刚修改了该疑点，请查看后再确认' };
        return { ok: false, reason: result.reason || '保存失败' };
      }
      function noteExternalChange(msg) {
        // A1-P08：只追加提示，不触碰正在输入的 textarea
        try {
          if (!msg || msg.type !== 'edit-saved') return;
          const status = dialog.querySelector('.issue-save-status');
          if (!status || status.dataset.externalNoted) return;
          status.dataset.externalNoted = '1';
          const note = el('p', 'issue-external-note', '当前浏览器另一页面刚刚保存了修订；本页显示不受影响，如需查看可重新打开。');
          status.after(note);
        } catch (_) { /* 提示失败不影响编辑 */ }
      }
      function storageMessage() {
        if (!storageUsable) return '浏览器本地存储不可用，修改仅在本次打开中有效，请下载备份。';
        if (storageNote) return storageNote;
        return '';
      }
      const trigger = button('本页待处理', () => open());
      trigger.classList.add('issue-page-button');
      document.querySelector('.page-meta')?.append(trigger);
      const dialog = el('dialog', 'issue-dialog');
      dialog.setAttribute('aria-label', '当前页问题处理');
      document.body.append(dialog);
      let selected = 0, dirty = false;
      const entries = () => (getPage().blocks || []).flatMap(block => (block.issues || [])
        .filter(issue => valid(issue, block)).map(issue => ({ block, issue })));
      const label = issue => issue.resolved ? '已解决' : issue.kind === 'suspected' ? '识别疑点'
        : issue.inferredText ? '待核对原字' : '模糊缺损内容';
      function mayLeave() { return !dirty || window.confirm('有尚未保存的修改，是否放弃？'); }
      dialog.addEventListener('cancel', event => { if (!mayLeave()) event.preventDefault(); });
      // Keep cursor/navigation keys inside the modal, away from reader page shortcuts.
      dialog.addEventListener('keydown', event => event.stopPropagation());
      function downloadRecord(filename, jsonText) {
        const url = URL.createObjectURL(new Blob([jsonText], { type: 'application/json' }));
        const a = el('a'); a.href = url; a.download = filename; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
      async function downloadBackup() {
        const opened = await ensureIdb();
        if (opened.mode === 'indexeddb' && opened.backend) {
          try {
            const backup = await opened.backend.exportBackup();
            downloadRecord('书页修改记录-备份.json', JSON.stringify(backup, null, 2));
            return;
          } catch (_) { /* 回退到 legacy 导出 */ }
        }
        const clean = {};
        for (const [recordId, value] of Object.entries(records)) {
          const { _pending, ...rest } = value || {};
          clean[recordId] = rest;
        }
        downloadRecord('书页修改记录.json', JSON.stringify({ schemaVersion: STORAGE_VERSION, bookId: baseBookId(book), edits: clean }, null, 2));
      }
      function allEntries() {
        return (getPage().blocks || []).flatMap(block => (block.issues || [])
          .filter(issue => valid(issue, block)).map(issue => ({ block, issue })));
      }
      async function importBackupFlow(picker, preview) {
        const opened = await ensureIdb();
        if (opened.mode !== 'indexeddb' || !opened.backend) return '当前为兼容模式，不支持导入预览；请下载备份后在新版离线包中导入。';
        const file = picker && picker.files && picker.files[0];
        if (!file) return '请先选择备份文件。';
        let parsed = null;
        try {
          parsed = JSON.parse(await file.text());
        } catch (_) {
          return '备份文件不是有效 JSON，未做任何修改。';
        }
        let result = null;
        try {
          result = await opened.backend.importBackupPreview(parsed);
        } catch (error) {
          return `预览失败：${String((error && error.message) || error)}，未做任何修改。`;
        }
        const ok = (result.appliable || []).length, conflict = (result.conflicts || []).length,
          invalid = (result.invalid || []).length, pending = (result.pending || []).length;
        if (ok === 0 && conflict === 0 && pending === 0) return `无可应用条目（无效 ${invalid}），未做任何修改。`;
        let applied = 0;
        try {
          const findRecord = k => (parsed.records || []).find(r => r && r.key === k) || {};
          const confirm = await opened.backend.confirmImport([
            ...(result.appliable || []).map(a => ({ ...findRecord(a.key), key: a.key })),
            ...(result.pending || []).map(a => ({ ...findRecord(a.key), key: a.key })),
          ]);
          applied = confirm.filter(r => r.status === 'SAVED').length;
        } catch (error) {
          return `应用失败：${String((error && error.message) || error)}，部分条目可能已写入，请核对。`;
        }
        render(); refresh();
        const message = `预览：可应用 ${ok}，待核验 ${pending}，冲突 ${conflict}，无效 ${invalid}；已应用 ${applied}。冲突条目未覆盖。`;
        try {
          await refreshIdbPending(getPage());
          draw();
          const box = dialog.querySelector('.import-preview');
          if (box) box.textContent = message;
        } catch (_) { /* 保持预览结果 */ }
        return message;
      }
      function appendText(container, block, text, page = getPage()) {
        const original = getScript() === 'original';
        const script = original ? 'original' : 'simplified';
        const issues = (block.issues || []).filter(issue => valid(issue, block)).map(issue => ({
          issue, start: original ? issue.start : issue.simplifiedStart, end: original ? issue.end : issue.simplifiedEnd
        })).filter(item => Number.isInteger(item.start) && Number.isInteger(item.end) && item.start >= 0 && item.end > item.start && item.end <= text.length)
          .sort((a, b) => a.start - b.start);
        let cursor = 0;
        for (const { issue, start, end } of issues) {
          if (start < cursor) continue;
          readingLayout.appendText(container, text.slice(cursor, start), block);
          const sourceText = text.slice(start, end);
          if (issue.resolved) readingLayout.appendText(container, readingLayout.displayIssueText(issue, sourceText, script), block);
          else readingLayout.appendIssueText(container, {
            page, block, issue, sourceText, script, pageImageSrc: page.image,
            onEdit: () => open(block.id, issue.id)
          });
          cursor = end;
        }
        readingLayout.appendText(container, text.slice(cursor), block);
      }
      function idbModeNote() {
        if (idb && idb.mode === 'legacy-localstorage') {
          return `当前浏览器不支持事务存储（${idb.reason || '未知原因'}），已降级为单标签页兼容模式：请勿多标签页同时编辑，重要修改请下载备份。`;
        }
        return '';
      }
      function refresh() {
        const pending = entries().filter(({ issue }) => !issue.resolved).length;
        trigger.textContent = `本页待处理 ${pending}`;
        trigger.classList.toggle('has-issues', pending > 0);
      }
      function open(blockId, issueId) {
        const all = entries();
        selected = Math.max(0, all.findIndex(item => item.block.id === blockId && item.issue.id === issueId));
        dirty = false; draw(); if (!dialog.open) dialog.showModal();
        // 打开后再刷新一次待核验（其他标签页可能在中间提交过），有变化才重绘，不碰输入
        refreshIdbPending(getPage()).then(changed => {
          if (changed && dialog.open) {
            const active = document.activeElement;
            const isTyping = active && active.classList && active.classList.contains('issue-edit');
            if (!isTyping) draw();
            else {
              const pendings = pendingList(getPage());
              if (pendings.length && !dialog.querySelector('.pending-review')) draw();
            }
          }
        }).catch(() => {});
      }
      function showSaveConflict(remote) {
        // A1-P02：输入保留，只追加冲突提示与远端覆盖入口
        if (dialog.querySelector('.save-conflict-note')) return;
        const note = el('p', 'save-conflict-note',
          `另一页面已先修改该疑点（版本 ${remote ? remote.editRevision : '未知'}），输入内容已保留，未覆盖对方。`);
        const actions = dialog.querySelector('.issue-actions');
        (actions || dialog).append(note);
        if (actions && remote) {
          const useRemote = button('用远端覆盖输入', () => {
            const input = dialog.querySelector('.issue-edit');
            if (input) input.value = remote.replacement || '';
            note.textContent += '（已填入远端值，可修改后再次保存）';
            useRemote.disabled = true;
          });
          actions.append(useRemote);
        }
      }
      function draw(message = '') {
        dialog.replaceChildren();
        const all = entries(), header = el('header', 'issue-header');
        header.append(el('h2', '', `当前页问题处理 · 原 PDF 第 ${getPage().sourcePageNumber || getPage().pageNumber} 页`),
          button('关闭', () => { if (mayLeave()) dialog.close(); }));
        dialog.append(header);
        const notice = message || storageMessage() || idbModeNote() || '离线修改保存在当前浏览器，不回写原 PDF；可下载修改记录备份。';
        const status = el('p', 'issue-save-status', notice);
        status.setAttribute('role', 'status'); dialog.append(status);
        const pendings = pendingList(getPage());
        if (pendings.length) {
          const box = el('details', 'pending-review');
          const summary = el('summary', '', `待核验修订（${pendings.length}）`);
          box.append(summary);
          for (const item of pendings) {
            const row = el('div', 'pending-row');
            row.append(el('span', '', `${item.blockId} / ${item.issueId}：${REASON_TEXT[item.reason] || item.reason}`));
            const key = item.recordKey, issueId = item.issueId, isIdb = !!item.idb;
            row.append(button('确认沿用', async () => {
              const result = isIdb ? await confirmIdbPending(key, issueId) : confirmPending(key, issueId);
              if (!isIdb) { draw(result.ok ? '已确认为当前基线的修订。' : `无法确认沿用：${result.reason || '未知原因'}`); refresh(); }
            }));
            box.append(row);
          }
          dialog.append(box);
        }
        if (!all.length) {
          dialog.append(el('p', 'issue-empty', '本页没有结构化疑点标记；这不代表已证明文字完全无误。'));
          appendImportSectionFooter();
          return;
        }
        selected = Math.min(selected, all.length - 1);
        const { block, issue } = all[selected], grid = el('div', 'issue-grid'), list = el('nav', 'issue-list');
        list.setAttribute('aria-label', '本页待处理位置');
        all.forEach((item, i) => {
          const row = button(`${i + 1}. ${label(item.issue)} · ${(item.block.original || '').slice(item.issue.start, item.issue.end).slice(0, 24)}`, () => {
            if (!mayLeave()) return; selected = i; dirty = false; draw();
          });
          row.classList.toggle('selected', i === selected); row.setAttribute('aria-current', String(i === selected)); list.append(row);
        });
        const source = el('section', 'issue-source'), result = el('section', 'issue-result');
        source.append(el('h3', '', '原图与识别依据'));
        if (getPage().image) {
          const stage = el('div', 'issue-image-stage'), image = el('img');
          image.src = getPage().image; image.alt = '原始扫描页，蓝框为当前内容块';
          const box = el('span', 'issue-source-box'), [x, y, w, h] = block.bbox || [0, 0, 1, 1];
          Object.assign(box.style, { left: `${x * 100}%`, top: `${y * 100}%`, width: `${w * 100}%`, height: `${h * 100}%` });
          stage.append(image, box); source.append(stage);
          const link = el('a', '', '打开原图放大查看'); link.href = getPage().image; link.target = '_blank'; link.rel = 'noopener'; source.append(link);
        }
        source.append(el('h4', '', '原始 OCR 片段'), el('p', 'issue-raw', (block.original || '').slice(issue.start, issue.end)),
          el('p', 'issue-reason', issue.reason || '模型标记需复核，原始识别记录保留。'));
        if (issue.inferredText) source.append(el('h4', '', '推测候选（不代表确认）'), el('p', 'issue-inference', issue.inferredText));
        result.append(el('h3', '', '编辑结果'), el('p', '', label(issue)));
        const input = el('textarea', 'issue-edit'); input.rows = 8;
        input.setAttribute('aria-label', '修订文字'); input.maxLength = 1000;
        input.value = issue.resolved ? issue.replacement || '' : issue.inferredText || (block.original || '').slice(issue.start, issue.end);
        input.addEventListener('input', () => { dirty = true; }); result.append(input);
        const actions = el('div', 'issue-actions');
        function accept(value) {
          // A1-03：先改内存对象用于即时显示，以事务提交成功为准；失败保留输入并提示
          issue.replacement = value; issue.resolved = true; dirty = false;
          commitIssue(block, issue).then(result => {
            if (result.status === 'SAVED') {
              render(); refresh();
              draw('已保存到当前浏览器。原始 OCR 与推测记录仍保留。');
            } else if (result.status === 'CONFLICT') {
              issue.resolved = false;
              render(); refresh();
              draw('保存冲突：另一页面已先修改该疑点，输入内容已保留，未覆盖对方。');
              showSaveConflict(result.remote);
              const input = dialog.querySelector('.issue-edit');
              if (input) { input.value = value; dirty = true; }
            } else {
              issue.resolved = false;
              render(); refresh();
              const suffix = result.legacy ? '（当前为单标签页兼容模式）' : '';
              draw(`浏览器无法持久保存（${result.reason || '未知原因'}）${suffix}，输入内容已保留，请立即下载修改记录。`);
              const input = dialog.querySelector('.issue-edit');
              if (input) { input.value = value; dirty = true; }
            }
          });
        }
        actions.append(button('保存并标记解决', () => accept(input.value)));
        if (issue.inferredText) actions.append(button('采用推测并确认', () => accept(issue.inferredText)));
        actions.append(button('恢复未解决', () => {
          if (!mayLeave()) return;
          issue.resolved = false; dirty = false;
          commitIssue(block, issue).then(result => {
            render(); refresh();
            draw(result.status === 'SAVED' ? '已恢复问题标记。'
              : result.status === 'CONFLICT' ? '对方有更新，未覆盖；输入已保留。'
              : '当前修改仅在本次打开中有效，请下载备份。');
          });
        }));
        result.append(actions); grid.append(list, source, result); dialog.append(grid);
        appendImportSectionFooter();
      }
      function appendImportSectionFooter() {
        const footer = el('footer', 'issue-footer');
        const all = entries();
        const previous = button('上一个', () => { if (mayLeave()) { selected--; dirty = false; draw(); } }); previous.disabled = selected === 0;
        const next = button('下一个', () => { if (mayLeave()) { selected++; dirty = false; draw(); } }); next.disabled = selected === all.length - 1;
        footer.append(previous, el('span', '', `${all.length ? selected + 1 : 0} / ${all.length}`), next,
          button('下载修改记录', () => { downloadBackup(); }));
        const importBox = el('details', 'import-review');
        importBox.append(el('summary', '', '备份导入'));
        const picker = el('input', 'import-file');
        picker.type = 'file'; picker.accept = 'application/json,.json';
        const preview = el('p', 'import-preview', '选择导出的修订备份文件，先预览再应用。');
        const apply = button('预览并应用', async () => {
          preview.textContent = await importBackupFlow(picker, preview);
        });
        importBox.append(picker, preview, apply);
        footer.append(importBox);
        dialog.append(footer);
      }
      return {
        appendText, refresh, open, hydratePage, preparePage,
        saveCurrent: save, recordCount: () => Object.keys(records).length,
        pendingList: () => pendingList(getPage()), confirmPending,
        commitIssue, confirmIdbPending,
        idbPendingList: (page) => pendingList(page),
        ensureIdb, backendMode: () => (idb ? idb.mode : 'pending'),
        storageStatus: () => ({
          usable: storageUsable, note: storageNote,
          records: Object.keys(records).length, quarantined: Object.keys(quarantined).length,
          idbMode: idb ? idb.mode : 'pending', idbReason: idb ? idb.reason : null,
        }),
      };
    }
  };
})();
