import { initFocusReading } from './focus-reading.js';

// U1：阅读/校对模式切换。同一本书、同一页、同一版本；只改变面板可见性，不调用云识别。
// 校对是用户主动进入的工作台；后台任务变化不得改变布局列数或主工具栏高度。
const MODE_KEY = 'paper-workshop.reader-mode';

function currentMode() {
  return document.body.dataset.readerMode === 'proof' ? 'proof' : 'reading';
}

function applyMode(mode, trigger) {
  const next = mode === 'proof' ? 'proof' : 'reading';
  document.body.dataset.readerMode = next;
  try { localStorage.setItem(MODE_KEY, next); } catch (_) {}
  document.querySelectorAll('#proof-toggle').forEach(btn => {
    btn.setAttribute('aria-pressed', String(next === 'proof'));
    btn.textContent = next === 'proof' ? '返回阅读' : '校对';
  });
  // 移动端既有校对开关保持同步
  const mobile = document.querySelector('#review-toggle');
  if (mobile) mobile.setAttribute('aria-expanded', String(next === 'proof'));
  if (next === 'proof') {
    document.querySelector('#review-panel')?.classList.add('open');
  } else {
    document.querySelector('#review-panel')?.classList.remove('open');
    if (trigger) trigger.focus({ preventScroll: true });
  }
  return next;
}

export function initReaderMode() {
  initFocusReading({ onProofRequested: () => applyMode('proof', null) });
  let initial = 'reading';
  try { initial = localStorage.getItem(MODE_KEY) === 'proof' ? 'proof' : 'reading'; } catch (_) {}
  // 窄屏默认收起导航与校对，不挤压正文；宽屏尊重用户上次选择。
  if (window.matchMedia?.('(max-width: 1050px)').matches) initial = 'reading';
  document.body.dataset.readerMode = initial;
  applyMode(initial, null);
  document.querySelector('#proof-toggle')?.addEventListener('click', event => {
    const next = currentMode() === 'proof' ? 'reading' : 'proof';
    applyMode(next, event.currentTarget);
  });
  document.querySelector('#review-toggle')?.addEventListener('click', event => {
    const next = currentMode() === 'proof' ? 'reading' : 'proof';
    applyMode(next, null);
    event.currentTarget.setAttribute('aria-expanded', String(next === 'proof'));
  });
  document.querySelector('#close-review')?.addEventListener('click', () => applyMode('reading', document.querySelector('#proof-toggle')));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && currentMode() === 'proof' && !document.querySelector('dialog[open]')) {
      applyMode('reading', document.querySelector('#proof-toggle'));
    }
  });
}

export function enterProofMode() { return applyMode('proof', null); }
export function enterReadingMode(trigger) { return applyMode('reading', trigger || null); }
export function isProofMode() { return currentMode() === 'proof'; }
