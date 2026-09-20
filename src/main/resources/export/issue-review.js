(() => {
  'use strict';
  const readingLayout = globalThis.BookReadingLayout;
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
  globalThis.BookIssueReview = {
    create({ book, pages, getPage, getScript, render }) {
      const key = `book-html:${book.id}:issue-edits:v1`;
      const edits = {};
      try {
        const stored = JSON.parse(localStorage.getItem(key) || '{}');
        for (const page of pages) for (const block of page.blocks || []) {
          const id = `${page.pageNumber}:${block.id}`, saved = stored[id];
          if (!saved || saved.original !== block.original || saved.simplified !== block.simplified) continue;
          const baseline = new Map((block.issues || []).map(issue => [issue.id, issue]));
          if (!Array.isArray(saved.issues)) continue;
          for (const entry of saved.issues) {
            const issue = baseline.get(entry.id);
            if (!valid(issue, block) || typeof entry.resolved !== 'boolean' || typeof entry.replacement !== 'string' || entry.replacement.length > 1000) continue;
            issue.resolved = entry.resolved; issue.replacement = entry.replacement;
          }
          edits[id] = saved;
        }
      } catch (_) { /* The reader remains usable without browser storage. */ }
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
        edits[`${getPage().pageNumber}:${block.id}`] = {
          original: block.original, simplified: block.simplified,
          issues: (block.issues || []).map(issue => ({ id: issue.id, resolved: Boolean(issue.resolved), replacement: issue.replacement || '' }))
        };
        try { localStorage.setItem(key, JSON.stringify(edits)); return true; } catch (_) { return false; }
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
      function draw(message = '') {
        dialog.replaceChildren();
        const all = entries(), header = el('header', 'issue-header');
        header.append(el('h2', '', `当前页问题处理 · 原 PDF 第 ${getPage().sourcePageNumber || getPage().pageNumber} 页`),
          button('关闭', () => { if (mayLeave()) dialog.close(); }));
        dialog.append(header);
        const status = el('p', 'issue-save-status', message || '离线修改保存在当前浏览器，不回写原 PDF；可下载修改记录备份。');
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
          draw(persisted ? '已保存到当前浏览器。原始 OCR 与推测记录仍保留。' : '浏览器无法持久保存，请立即下载修改记录。');
        }
        actions.append(button('保存并标记解决', () => accept(input.value)));
        if (issue.inferredText) actions.append(button('采用推测并确认', () => accept(issue.inferredText)));
        actions.append(button('恢复未解决', () => {
          if (!mayLeave()) return; issue.resolved = false; dirty = false;
          const persisted = save(block); render(); refresh(); draw(persisted ? '已恢复问题标记。' : '当前修改仅在本次打开中有效，请下载备份。');
        }));
        result.append(actions); grid.append(list, source, result); dialog.append(grid);
        const footer = el('footer', 'issue-footer');
        const previous = button('上一个', () => { if (mayLeave()) { selected--; dirty = false; draw(); } }); previous.disabled = selected === 0;
        const next = button('下一个', () => { if (mayLeave()) { selected++; dirty = false; draw(); } }); next.disabled = selected === all.length - 1;
        footer.append(previous, el('span', '', `${selected + 1} / ${all.length}`), next,
          button('下载修改记录', () => {
            const url = URL.createObjectURL(new Blob([JSON.stringify({ schemaVersion: 1, bookId: book.id, edits }, null, 2)], { type: 'application/json' }));
            const a = el('a'); a.href = url; a.download = '书页修改记录.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
          })); dialog.append(footer);
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
      return { appendText, refresh, open };
    }
  };
})();
