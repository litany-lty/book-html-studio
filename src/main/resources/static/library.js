import { api } from './api.js';

const $ = selector => document.querySelector(selector);

export function createLibrary({ books, currentBookId, openBook, openUsage, changed, canArchiveCurrent, processBook }) {
  const dialog = $('#library-dialog');
  const search = $('#library-search');
  const sort = $('#library-sort');
  const list = $('#library-list');
  const status = $('#library-status');
  const trigger = $('#library-open');
  let archived = false;
  let working = false;

  function say(message, error = false) {
    status.textContent = message;
    status.classList.toggle('error', error);
  }

  function button(label, action, className = 'button') {
    const element = document.createElement('button');
    element.type = 'button';
    element.className = className;
    element.textContent = label;
    element.addEventListener('click', action);
    return element;
  }

  function updateTabs(all) {
    $('#library-active-count').textContent = all.filter(book => !book.archived).length;
    $('#library-archived-count').textContent = all.filter(book => book.archived).length;
    for (const [id, selected] of [['library-active', !archived], ['library-archived', archived]]) {
      const tab = $('#' + id);
      tab.classList.toggle('active', selected);
      tab.setAttribute('aria-pressed', String(selected));
    }
  }

  function display() {
    const all = books();
    updateTabs(all);
    const query = search.value.trim().toLocaleLowerCase();
    const visible = all.filter(book => Boolean(book.archived) === archived &&
      (!query || `${book.title} ${book.filename}`.toLocaleLowerCase().includes(query)));
    visible.sort((left, right) => {
      if (sort.value === 'title') return left.title.localeCompare(right.title, 'zh-CN');
      if (sort.value === 'progress') {
        const difference = right.processedPages / Math.max(1, right.totalPages) - left.processedPages / Math.max(1, left.totalPages);
        if (difference) return difference;
      }
      return Date.parse(right.createdAt) - Date.parse(left.createdAt);
    });
    list.replaceChildren();
    if (!visible.length) {
      const empty = document.createElement('p');
      empty.className = 'library-empty';
      empty.textContent = query ? '没有找到匹配的书，试试其他关键词。' : archived ? '这里还没有归档的书。' : '书架还是空的，导入 PDF 后会显示在这里。';
      list.append(empty);
      return;
    }
    for (const book of visible) {
      const item = document.createElement('article');
      item.className = 'library-book';
      const cover = document.createElement('div');
      cover.className = 'library-cover';
      cover.setAttribute('aria-hidden', 'true');
      cover.textContent = '页';
      const body = document.createElement('div');
      body.className = 'library-book-body';
      const heading = document.createElement('h3');
      heading.textContent = book.title;
      const meta = document.createElement('p');
      meta.className = 'library-book-meta';
      const created = Date.parse(book.createdAt);
      meta.textContent = `${book.totalPages} 页${book.countsStatus === 'SNAPSHOT' ? ' · 记录进度（可刷新）' : ''} · 已处理 ${book.processedPages} 页 · 已校对 ${book.reviewedPages} 页${Number.isFinite(created) ? ` · 导入于 ${new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(created)}` : ''}`;
      const filename = document.createElement('p');
      filename.className = 'library-book-file';
      filename.textContent = book.filename;
      filename.title = book.filename;
      const progress = document.createElement('progress');
      progress.max = Math.max(1, book.totalPages);
      progress.value = book.processedPages;
      progress.setAttribute('aria-label', `《${book.title}》处理进度`);
      const actions = document.createElement('div');
      actions.className = 'library-book-actions';
      if (!archived) {
        actions.append(button('打开阅读', async () => {
          if (await openBook(book.id)) dialog.close();
          else say('当前页面有未保存内容，请先保存或处理冲突后再切换书籍。', true);
        }, 'button primary'));
        if (book.processedPages < book.totalPages) {
          actions.append(button('后台处理全书', async () => {
            try {
              say(`正在启动《${book.title}》的全书处理任务…`);
              if (typeof processBook === 'function') {
                await processBook(book);
              } else {
                await api.startJob(book.id, {
                  pages: `1-${book.totalPages}`,
                  provider: 'paddle-aistudio',
                  layout: 'auto',
                  splitSpreads: false,
                  force: false,
                  assist: true
                });
              }
              say(`已启动《${book.title}》的全书处理任务，后台正在转换。`);
            } catch (err) {
              say(`启动失败：${err.message || '服务异常或已有任务在运行'}`, true);
            }
          }, 'button'));
        }
      }
      actions.append(button('用量与费用', () => {
        dialog.close();
        window.setTimeout(() => openUsage(book), 0);
      }));
      actions.append(button('改名', () => editTitle(book, body, heading)));
      actions.append(button(archived ? '恢复到书架' : '归档', () => update(book, { archived: !archived }), archived ? 'button' : 'button quiet'));
      body.append(heading, meta, filename, progress, actions);
      item.append(cover, body);
      list.append(item);
    }
  }

  async function update(book, patch) {
    if (working) return;
    if (patch.archived === true && book.id === currentBookId() && !canArchiveCurrent()) {
      say('本页正在保存或仍有未保存内容，请先完成保存或处理冲突后再归档。', true);
      return;
    }
    working = true;
    list.setAttribute('aria-busy', 'true');
    say('正在保存书架变更…');
    try {
      const result = await api.updateLibraryBook(book.id, patch);
      await changed(result, patch);
      say(patch.title !== undefined ? '书名已保存。' : patch.archived ? '已归档，书籍数据仍保留。' : '已恢复到书架。');
      display();
    } catch (error) {
      say(error.message || '保存失败，请检查服务状态后重试。', true);
    } finally {
      working = false;
      list.removeAttribute('aria-busy');
    }
  }

  function editTitle(book, body, heading) {
    if (working || body.querySelector('.library-rename')) return;
    const form = document.createElement('form');
    form.className = 'library-rename';
    const input = document.createElement('input');
    input.value = book.title;
    input.maxLength = 120;
    input.setAttribute('aria-label', `修改《${book.title}》的书名`);
    const cancel = button('取消', () => { form.remove(); heading.hidden = false; });
    const save = button('保存书名', () => form.requestSubmit(), 'button primary');
    form.append(input, save, cancel);
    form.addEventListener('submit', event => {
      event.preventDefault();
      const title = input.value.trim();
      if (!title || title.length > 120) { say('书名须为 1–120 个字符。', true); input.focus(); return; }
      if (title === book.title) { form.remove(); heading.hidden = false; return; }
      void update(book, { title });
    });
    heading.hidden = true;
    heading.after(form);
    input.focus(); input.select();
  }

  trigger.addEventListener('click', () => {
    archived = false;
    search.value = '';
    say('');
    display();
    dialog.showModal();
    search.focus();
  });
  $('#library-close').addEventListener('click', () => dialog.close());
  $('#library-import').addEventListener('click', () => { dialog.close(); $('#pdf-upload').click(); });
  dialog.addEventListener('close', () => trigger.focus({ preventScroll: true }));
  search.addEventListener('input', display);
  sort.addEventListener('change', display);
  $('#library-active').addEventListener('click', () => { archived = false; display(); });
  $('#library-archived').addEventListener('click', () => { archived = true; display(); });
  $('#library-refresh').addEventListener('click', async () => {
    if (working) return;
    working = true;
    const control = $('#library-refresh');
    control.disabled = true;
    try {
      const latest = await api.books();
      await changed(null, null, latest);
      say('书架已刷新。');
      display();
    } catch (error) { say(error.message || '刷新书架失败。', true); }
    finally { working = false; control.disabled = false; }
  });
  return { render: () => { if (dialog.open) display(); } };
}
