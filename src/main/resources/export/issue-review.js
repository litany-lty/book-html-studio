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
      const key = `book-html:${baseBookId(book)}:issue-edits:v${STORAGE_VERSION}`;
      const legacyV2Key = `book-html:${baseBookId(book)}:issue-edits:v2`;
      const legacyV1Key = `book-html:${(book && book.id) || ''}:issue-edits:v1`;
      const bookFingerprint = (book && book.sourcePdfSha256) || '';
      let records = {};
      let quarantined = {};
      let pending = [];
      let storageNote = '';
      let storageUsable = true;
      function recomputePending() {
        pending = [];
        for (const [recordId, item] of Object.entries(records)) {
          if (item && item._pending) {
            for (const p of item._pending) pending.push({ recordKey: recordId, ...p });
          }
        }
      }
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
          sourcePdfSha256: bookFingerprint || undefined,
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
        // 未知 schema：隔离整份，不删除
        quarantined.__unknown = { reason: 'UNKNOWN_SCHEMA', schemaVersion: parsed.schemaVersion ?? null };
        storageNote = '浏览器中的修改记录版本无法识别，已隔离保留，请下载备份后处理。';
      }
      function migrateLegacy() {
        // v2（源页身份）→ v3：无 issueBasis 的一律待核验，不自动应用
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
        // v1（节选连续页号）：仅当同一节选导出 id 且有可信 pageMap 时映射，否则隔离
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
      function findRecord(page, block) {
        // A1-02：只用源页身份查找；禁止用节选连续页号回退查同一字典
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        if (!validSourcePage(sourcePage)) return null;
        return records[recordKey(sourcePage, block.id)] || null;
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
      loadStored();
      // 已内联正文的旧格式包：初始化时直接恢复。
      for (const page of pages || []) hydratePage(page);
      // R02：跨标签页尽力同步（读—改—写非事务，仅做最新提醒，不宣称并发安全）。
      // A1-03 将保存改为事务存储后，此处只作变更通知。
      try {
        window.addEventListener('storage', event => {
          if (!event || event.key !== key) return;
          try {
            const raw = localStorage.getItem(key);
            if (!raw) return;
            const parsed = JSON.parse(raw);
            if (parsed && parsed.schemaVersion === STORAGE_VERSION && parsed.records && typeof parsed.records === 'object') {
              const next = {};
              for (const [recordId, value] of Object.entries(parsed.records)) {
                const { _pending, ...rest } = value || {};
                if (validRecord(rest, recordId)) next[recordId] = { ...rest };
              }
              records = next;
              hydratePage(getPage()); render(); refresh();
            }
          } catch (_) { /* 保持当前页可用 */ }
        });
      } catch (_) { /* 无 window 时跳过 */ }
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
      function refresh() {
        const pending = entries().filter(({ issue }) => !issue.resolved).length;
        trigger.textContent = `本页待处理 ${pending}`;
        trigger.classList.toggle('has-issues', pending > 0);
      }
      function save(block) {
        // R02/A1-02：只合并当前块变更，保留其他页记录；成功后才提示“已保存”。
        // A1-03 将底层换成事务存储后，此处语义不变（逐记录比较更新）。
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
        const rec = records[recordKey(sourcePage, block.id)];
        rec._pending = [];
        return persist();
      }
      function storageMessage() {
        if (!storageUsable) return '浏览器本地存储不可用，修改仅在本次打开中有效，请下载备份。';
        if (storageNote) return storageNote;
        return '';
      }
      function mayLeave() { return !dirty || window.confirm('有尚未保存的修改，是否放弃？'); }
      dialog.addEventListener('cancel', event => { if (!mayLeave()) event.preventDefault(); });
      // Keep cursor/navigation keys inside the modal, away from reader page shortcuts.
      dialog.addEventListener('keydown', event => event.stopPropagation());
      function open(blockId, issueId) {
        const all = entries();
        selected = Math.max(0, all.findIndex(item => item.block.id === blockId && item.issue.id === issueId));
        dirty = false; draw(); if (!dialog.open) dialog.showModal();
      }
      function downloadRecord(filename, jsonText) {
        const url = URL.createObjectURL(new Blob([jsonText], { type: 'application/json' }));
        const a = el('a'); a.href = url; a.download = filename; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
      function draw(message = '') {
        dialog.replaceChildren();
        const all = entries(), header = el('header', 'issue-header');
        header.append(el('h2', '', `当前页问题处理 · 原 PDF 第 ${getPage().sourcePageNumber || getPage().pageNumber} 页`),
          button('关闭', () => { if (mayLeave()) dialog.close(); }));
        dialog.append(header);
        const notice = message || storageMessage() || '离线修改保存在当前浏览器，不回写原 PDF；可下载修改记录备份。';
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
            const key = item.recordKey, issueId = item.issueId;
            row.append(button('确认沿用', () => {
              const result = confirmPending(key, issueId);
              draw(result.ok ? '已确认为当前基线的修订。' : `无法确认沿用：${result.reason || '未知原因'}`);
              refresh();
            }));
            box.append(row);
          }
          dialog.append(box);
        }
        if (!all.length) { dialog.append(el('p', 'issue-empty', '本页没有结构化疑点标记；这不代表已证明文字完全无误。')); return; }
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
          issue.replacement = value; issue.resolved = true; dirty = false;
          const persisted = save(block); render(); refresh();
          draw(persisted.ok ? '已保存到当前浏览器。原始 OCR 与推测记录仍保留。' : `浏览器无法持久保存（${persisted.reason || '未知原因'}），请立即下载修改记录。`);
        }
        actions.append(button('保存并标记解决', () => accept(input.value)));
        if (issue.inferredText) actions.append(button('采用推测并确认', () => accept(issue.inferredText)));
        actions.append(button('恢复未解决', () => {
          if (!mayLeave()) return; issue.resolved = false; dirty = false;
          const persisted = save(block); render(); refresh(); draw(persisted.ok ? '已恢复问题标记。' : '当前修改仅在本次打开中有效，请下载备份。');
        }));
        result.append(actions); grid.append(list, source, result); dialog.append(grid);
        const footer = el('footer', 'issue-footer');
        const previous = button('上一个', () => { if (mayLeave()) { selected--; dirty = false; draw(); } }); previous.disabled = selected === 0;
        const next = button('下一个', () => { if (mayLeave()) { selected++; dirty = false; draw(); } }); next.disabled = selected === all.length - 1;
        footer.append(previous, el('span', '', `${selected + 1} / ${all.length}`), next,
          button('下载修改记录', () => {
            const clean = {};
            for (const [recordId, value] of Object.entries(records)) {
              const { _pending, ...rest } = value || {};
              clean[recordId] = rest;
            }
            downloadRecord('书页修改记录.json', JSON.stringify({ schemaVersion: STORAGE_VERSION, bookId: baseBookId(book), edits: clean }, null, 2));
          }));
        dialog.append(footer);
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
      return {
        appendText, refresh, open, hydratePage,
        saveCurrent: save, recordCount: () => Object.keys(records).length,
        pendingList: () => pendingList(getPage()), confirmPending,
        storageStatus: () => ({
          usable: storageUsable, note: storageNote,
          records: Object.keys(records).length, quarantined: Object.keys(quarantined).length,
        }),
      };
    }
  };
})();
