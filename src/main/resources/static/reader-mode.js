import { initFocusReading } from './focus-reading.js';

// One owner for proof mode, panel visibility and its controls; never modifies book data.
const MODE_KEY = 'paper-workshop.reader-mode';
let initialized = false;
let modeChanged = () => {};
let lastProofOpener = null;
const narrow = () => Boolean(window.matchMedia?.('(max-width: 1050px)').matches);
function currentMode() { return document.body.dataset.readerMode === 'proof' ? 'proof' : 'reading'; }
function focusable(element) { return Boolean(element?.isConnected && element.getClientRects().length && !element.disabled); }
function visibleOpener() {
  return [lastProofOpener, document.querySelector('#proof-toggle'), document.querySelector('#review-toggle')].find(focusable);
}
function applyMode(mode, trigger = null) {
  const next = mode === 'proof' ? 'proof' : 'reading';
  if (next === 'proof' && trigger) lastProofOpener = trigger;
  document.body.dataset.readerMode = next;
  try { localStorage.setItem(MODE_KEY, next); } catch (_) {}
  const active = next === 'proof';
  const desktop = document.querySelector('#proof-toggle');
  if (desktop) {
    desktop.setAttribute('aria-pressed', String(active));
    desktop.textContent = active ? '返回阅读' : '校对';
  }
  document.querySelector('#review-toggle')?.setAttribute('aria-expanded', String(active));
  document.querySelector('#review-panel')?.classList.toggle('open', active);
  if (active) {
    document.querySelector('#toc-panel')?.classList.remove('open');
    document.querySelector('#toc-toggle')?.setAttribute('aria-expanded', 'false');
  }
  const scrim = document.querySelector('#drawer-scrim');
  if (scrim) scrim.hidden = !narrow() || (!active && !document.querySelector('#toc-panel')?.classList.contains('open'));
  modeChanged(next);
  if (!active && focusable(trigger)) trigger.focus({ preventScroll: true });
  return next;
}

export function initReaderMode({ onChange = () => {} } = {}) {
  modeChanged = onChange;
  if (initialized) return; // No duplicate click handlers or toggles on reinitialization.
  initialized = true;
  initFocusReading({ onProofRequested: () => applyMode('proof') });
  let initial = 'reading';
  try { initial = localStorage.getItem(MODE_KEY) === 'proof' ? 'proof' : 'reading'; } catch (_) {}
  if (narrow()) initial = 'reading';
  applyMode(initial);
  for (const id of ['#proof-toggle', '#review-toggle']) {
    document.querySelector(id)?.addEventListener('click', event => {
      if (currentMode() === 'proof') closeProofMode(true);
      else applyMode('proof', event.currentTarget);
    });
  }
  document.querySelector('#close-review')?.addEventListener('click', () => closeProofMode(true));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !event.defaultPrevented && !event.isComposing && currentMode() === 'proof'
        && !document.querySelector('dialog[open]')) {
      event.preventDefault(); closeProofMode(true);
    }
  });
  window.matchMedia?.('(max-width: 1050px)').addEventListener?.('change', () => applyMode(currentMode()));
}

export function enterProofMode(trigger = null) { return applyMode('proof', trigger); }
export function closeProofMode(restoreFocus = false) { return applyMode('reading', restoreFocus ? visibleOpener() : null); }
export function enterReadingMode(trigger) { return applyMode('reading', trigger || null); }
export function isProofMode() { return currentMode() === 'proof'; }
