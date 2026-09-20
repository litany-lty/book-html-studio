(() => {
  'use strict';
  const readingLayout = globalThis.BookReadingLayout;
  const STORAGE_VERSION = 2;
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
  function valid(issue, block) {
    return issue && typeof issue.id === 'string' && ['unreadable', 'suspected'].includes(issue.kind)
      && Number.isInteger(issue.start) && Number.isInteger(issue.end)
      && issue.start >= 0 && issue.end > issue.start && issue.end <= (block.original || '').length;
  }
  // R02：长期身份使用源 PDF 页号，不用节选连续页号；同一本书的节选导出共享同一份记录。
  function baseBookId(book) {
    return String((book && book.id) || (book && book.title) || '').split(':selection:')[0];
  }
  function recordKey(sourcePage, blockId) {
    return `${sourcePage}:${blockId}`;
  }
  function validRecord(value) {
    if (!value || typeof value !== 'object') return false;
    if (typeof value.blockId !== 'string' || !value.blockId) return false;
    if (typeof value.original !== 'string' || typeof value.simplified !== 'string') return false;
    if (!Array.isArray(value.issues)) return false;
    return value.issues.every(entry => entry && typeof entry.id === 'string'
      && typeof entry.resolved === 'boolean' && typeof entry.replacement === 'string'
      && entry.replacement.length <= 1000);
  }
  globalThis.BookIssueReview = {
    create({ book, pages, getPage, getScript, render }) {
      const key = `book-html:${baseBookId(book)}:issue-edits:v${STORAGE_VERSION}`;
      const legacyKey = `book-html:${(book && book.id) || ''}:issue-edits:v1`;
      // R02：初始化只读取并校验整份旧字典，不用“当前已加载页”过滤；恢复推迟到各页加载后。
      let records = {};
      let storageNote = '';
      let storageUsable = true;
      try {
        const raw = localStorage.getItem(key);
        if (raw) {
          const parsed = JSON.parse(raw);
          if (parsed && parsed.schemaVersion === STORAGE_VERSION && parsed.records && typeof parsed.records === 'object') {
            for (const [recordId, value] of Object.entries(parsed.records)) {
              if (validRecord(value)) records[recordId] = value;
            }
          } else {
            storageNote = '浏览器中的修改记录格式无法识别，已保留本次可保存的修改；建议及时下载备份。';
          }
        } else {
          // 兼容旧版扁平字典：整体迁入，不丢字段；基线不一致的记录后续进入待核验。
          const legacyRaw = localStorage.getItem(legacyKey);
          if (legacyRaw) {
            const legacy = JSON.parse(legacyRaw);
            if (legacy && typeof legacy === 'object' && !Array.isArray(legacy)) {
              for (const [recordId, value] of Object.entries(legacy)) {
                if (value && typeof value === 'object' && typeof value.original === 'string'
                    && typeof value.simplified === 'string' && Array.isArray(value.issues)) {
                  records[recordId] = {
                    sourcePage: typeof value.sourcePage === 'number' ? value.sourcePage : null,
                    blockId: String(recordId).split(':').slice(1).join(':') || String(recordId),
                    original: value.original, simplified: value.simplified, issues: value.issues,
                  };
                }
              }
              persist();
              storageNote = '已把旧版修改记录升级，新记录不再丢失字段。';
            }
          }
        }
      } catch (_) {
        storageUsable = false;
        storageNote = '浏览器本地修改记录损坏无法读取，本次修改可重新保存；建议及时下载备份。';
      }
      let prevRaw = null;
      function persist() {
        const payload = JSON.stringify({ schemaVersion: STORAGE_VERSION, bookId: baseBookId(book), records });
        try {
          localStorage.setItem(key, payload);
          return { ok: true };
        } catch (error) {
          const reason = error && error.name === 'QuotaExceededError' ? '存储配额已满' : '浏览器存储不可用';
          return { ok: false, reason };
        }
      }
      function snapshotPrev() {
        try { prevRaw = localStorage.getItem(key); } catch (_) { prevRaw = null; }
      }
      function findRecord(page, block) {
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        const stable = records[recordKey(sourcePage, block.id)];
        if (stable) return stable;
        const legacy = records[`${page.pageNumber}:${block.id}`];
        return legacy || null;
      }
      // R02：在正文加载成功后、渲染前调用；重复调用幂等；基线不一致只计数不套用。
      function hydratePage(page) {
        const counts = { applied: 0, pending: 0 };
        if (!page) return counts;
        for (const block of page.blocks || []) {
          const saved = findRecord(page, block);
          if (!saved) continue;
          if (saved.original !== block.original || saved.simplified !== block.simplified) { counts.pending++; continue; }
          if (!Array.isArray(saved.issues)) continue;
          const baseline = new Map((block.issues || []).map(issue => [issue.id, issue]));
          let applied = false;
          for (const entry of saved.issues) {
            const issue = baseline.get(entry.id);
            if (!valid(issue, block) || typeof entry.resolved !== 'boolean' || typeof entry.replacement !== 'string' || entry.replacement.length > 1000) continue;
            if (issue.resolved !== entry.resolved || issue.replacement !== entry.replacement) {
              issue.resolved = entry.resolved; issue.replacement = entry.replacement; applied = true;
            }
          }
          if (applied) counts.applied++;
        }
        return counts;
      }
      // 已内联正文的旧格式包：初始化时直接恢复。
      for (const page of pages || []) hydratePage(page);
      // R02：跨标签页尽力同步（读—改—写非事务，仅做最新提醒，不宣称并发安全）。
      try {
        window.addEventListener('storage', event => {
          if (!event || (event.key !== key && event.key !== legacyKey)) return;
          try {
            const raw = localStorage.getItem(key);
            if (!raw) return;
            const parsed = JSON.parse(raw);
            if (parsed && parsed.schemaVersion === STORAGE_VERSION && parsed.records && typeof parsed.records === 'object') {
              const next = {};
              for (const [recordId, value] of Object.entries(parsed.records)) {
                if (validRecord(value)) next[recordId] = value;
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
        // R02：只合并当前块变更，保留其他页记录；成功后才提示“已保存”。
        const page = getPage();
        const sourcePage = page.sourcePageNumber || page.pageNumber;
        snapshotPrev();
        records[recordKey(sourcePage, block.id)] = {
          sourcePage, blockId: block.id,
          original: block.original, simplified: block.simplified,
          issues: (block.issues || []).map(issue => ({ id: issue.id, resolved: Boolean(issue.resolved), replacement: issue.replacement || '' }))
        };
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
            downloadRecord('书页修改记录.json', JSON.stringify({ schemaVersion: STORAGE_VERSION, bookId: baseBookId(book), edits: records }, null, 2));
          }));
        if (prevRaw) {
          const backup = prevRaw;
          footer.append(button('下载上一版备份', () => {
            downloadRecord('书页修改记录-上一版.json', backup);
          }));
        }
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
      return { appendText, refresh, open, hydratePage, saveCurrent: save, recordCount: () => Object.keys(records).length };
    }
  };
})();
