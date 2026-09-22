(function (root) {
  'use strict';
  const STORAGE_KEY = 'book-html:reader-font:v1';
  const options = Object.freeze([
    { id: 'serif', label: '系统宋体', note: '传统书籍风格；优先使用本机宋体，缺字回退到内置 Noto 宋体。' },
    { id: 'sans', label: '系统黑体', note: '简洁清晰；优先使用本机黑体，缺字回退到内置 Noto 黑体。' },
    { id: 'source-serif', label: 'Noto 宋体', note: '内置 Noto Serif SC，适合中文长文；离线也可使用。' },
    { id: 'source-sans', label: 'Noto 黑体', note: '内置 Noto Sans SC，笔画清晰；离线也可使用。' },
    { id: 'wenkai', label: '霞鹜文楷', note: '内置 LXGW WenKai TC，保留楷书书写感；缺字自动回退，不改变原文。' },
    { id: 'jetbrains-mono', label: 'JetBrains Mono', note: '英文、数字使用 JetBrains Mono；中文使用内置 Noto 宋体。原稿图片不受影响。' }
  ].map(Object.freeze));
  const normalize = value => options.some(option => option.id === value) ? value : 'serif';
  let selected = 'serif';
  try { selected = normalize(root.localStorage?.getItem(STORAGE_KEY)); } catch (_) {}
  const controls = new Set();
  function apply(value, persist = true) {
    selected = normalize(value);
    root.document?.documentElement?.setAttribute('data-reader-font', selected);
    if (persist) { try { root.localStorage?.setItem(STORAGE_KEY, selected); } catch (_) {} }
    for (const { select, note } of controls) {
      select.value = selected;
      if (note) note.textContent = options.find(option => option.id === selected).note;
    }
    return selected;
  }
  function init(target, config = {}) {
    const select = typeof target === 'string' ? root.document.querySelector(target) : target;
    // The document root also has data-reader-font; never replace arbitrary nodes.
    if (!select || select.tagName !== 'SELECT') return null;
    const note = typeof config.noteElement === 'string' ? root.document.querySelector(config.noteElement) : config.noteElement;
    const existing = [...controls].find(control => control.select === select);
    if (existing) return existing.dispose;
    select.replaceChildren(...options.map(option => {
      const item = root.document.createElement('option');
      item.value = option.id; item.textContent = option.label; return item;
    }));
    select.classList.add('reader-font-select');
    const change = () => { apply(select.value); config.onChange?.(selected); };
    const control = { select, note, dispose: () => { select.removeEventListener('change', change); controls.delete(control); } };
    controls.add(control);
    select.addEventListener('change', change);
    apply(selected, false);
    return control.dispose;
  }
  root.BookReaderFonts = Object.freeze({ options, init, set: apply, current: () => selected, normalize });
  apply(selected, false);
})(typeof globalThis !== 'undefined' ? globalThis : this);
