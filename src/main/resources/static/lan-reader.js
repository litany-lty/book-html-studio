import { api } from './api.js';

// UI projections only; every write is independently authorized on the server.
export function hasReaderCapability(status, capability) {
  return Boolean(status && Array.isArray(status.capabilities) &&
    (status.capabilities.includes('MANAGE') || status.capabilities.includes(capability)));
}
export function createLanReader({ changed = () => {} } = {}) {
  const $ = s => document.querySelector(s), dialog = $('#lan-reader-dialog');
  let current = null, pending = null, busy = false, resolveUpload = null, scope = 0;
  function render() {
    const manager = hasReaderCapability(current, 'MANAGE');
    $('#lan-manager').hidden = !manager; $('#lan-pair-form').hidden = manager;
    $('#lan-reader-logout').hidden = !current?.isPaired || current?.isLoopback || manager;
    $('#lan-access-description').textContent = manager ? '本机可上传和管理。为局域网读者生成上传码，不会授予校对、管理或付费识别权限。'
      : hasReaderCapability(current, 'UPLOAD') ? '本设备已获准上传。书架与原稿由服务器保存；阅读位置和书签只记录在本浏览器。'
      : '已有书籍可以直接阅读。上传前，请向部署者索取十分钟内有效的配对码。';
    if (current?.readRequiresPairing && !current?.isPaired) $('#lan-access-description').textContent = '部署者要求配对后访问书架。请输入部署者提供的配对码。';
    changed(current);
  }
  async function refresh() {
    if (pending) return pending;
    pending = api.lanStatus().then(value => {
      if (!value || !['LOOPBACK', 'LAN_PAIRED', 'TRUSTED_PROXY'].includes(value.accessMode) || !Array.isArray(value.capabilities))
        throw new Error('设备权限暂不可用，请稍后重试。');
      current = value; render(); return value;
    }).finally(() => { pending = null; });
    return pending;
  }
  function open() {
    $('#lan-reader-status').textContent = ''; $('#lan-pin-output').textContent = '';
    render(); if (!dialog.open) dialog.showModal();
    if (!$('#lan-pair-form').hidden) $('#lan-pin').focus();
    void refresh().catch(error => { $('#lan-reader-status').textContent = error.message; });
  }
  async function ensureUpload() {
    const value = await refresh();
    if (hasReaderCapability(value, 'UPLOAD')) return true;
    if (resolveUpload) return false;
    open(); return new Promise(resolve => { resolveUpload = resolve; });
  }
  $('#lan-reader-open').addEventListener('click', open);
  $('#lan-reader-close').addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => {
    ++scope; $('#lan-pin').value = ''; $('#lan-pin-output').textContent = '';
    if (resolveUpload) { resolveUpload(false); resolveUpload = null; }
  });
  $('#lan-pair-form').addEventListener('submit', async event => {
    event.preventDefault(); if (busy) return;
    let pin = $('#lan-pin').value.trim();
    if (!/^\d{6}$/.test(pin)) { $('#lan-reader-status').textContent = '请输入六位配对码。'; return; }
    busy = true; const generation = scope; const button = $('#lan-pair-submit'); button.disabled = true;
    try {
      await api.pairReader(pin); pin = ''; $('#lan-pin').value = ''; await refresh();
      if (generation !== scope) return; // Closing never resumes an old file selection.
      const finish = resolveUpload; resolveUpload = null;
      dialog.close(); finish?.(true);
    } catch (error) { if (generation === scope) $('#lan-reader-status').textContent = error.message || '配对没有完成。'; }
    finally { pin = ''; $('#lan-pin').value = ''; busy = false; button.disabled = false; }
  });
  $('#lan-generate-pin').addEventListener('click', async event => {
    const button = event.currentTarget, generation = scope; button.disabled = true;
    try { const result = await api.lanPin(); if (generation === scope && dialog.open) $('#lan-pin-output').textContent = `上传码 ${result.pin} · 十分钟有效`; }
    catch (error) { $('#lan-reader-status').textContent = error.message; }
    finally { button.disabled = false; }
  });
  $('#lan-reader-logout').addEventListener('click', async () => {
    try { await api.unpairReader(); await refresh(); $('#lan-reader-status').textContent = '本设备上传授权已退出，书籍不会被删除。'; }
    catch (error) { $('#lan-reader-status').textContent = error.message; }
  });
  return { refresh, ensureUpload, can: capability => hasReaderCapability(current, capability), known: () => current !== null };
}
