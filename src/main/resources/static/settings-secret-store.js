// Credential values exist only in the active form/request, never in persisted browser state.
export const secretFields = Object.freeze([
  ['paddleAccessToken', 'clearPaddleAccessToken'], ['ppocrApiKey', 'clearPpocrApiKey'],
  ['ppocrSecretKey', 'clearPpocrSecretKey'], ['qwenApiKey', 'clearQwenApiKey'], ['jevApiKey', 'clearJevApiKey'],
].map(pair => Object.freeze(pair)));

export function secretValidation(form) {
  for (const [name, clear] of secretFields) {
    const value = form.elements.namedItem(name).value;
    if (value && form.elements.namedItem(clear).checked) return '同一项凭据不能同时填写新值并勾选清除。';
    if (value && (!value.trim() || /^[*•●]{3,}$/.test(value.trim()) || /^REDACTED$/i.test(value.trim())
      || value.length > 4096 || /[\x00-\x1f\x7f]/.test(value))) return '凭据格式无效；请填写新密钥原文，不能使用掩码占位。';
  }
  return '';
}
export function buildSecretUpdates(form, writable) {
  if (writable !== true) return {};
  const error = secretValidation(form);
  if (error) throw new Error(error);
  const updates = {};
  for (const [name, clear] of secretFields) {
    const value = form.elements.namedItem(name).value;
    if (form.elements.namedItem(clear).checked) updates[name] = { clearSecret: true };
    else if (value) updates[name] = { value };
  }
  return updates;
}
export function scrubSecretInputs(form) {
  for (const [name, clear] of secretFields) {
    const input = form.elements.namedItem(name);
    input.value = ''; input.type = 'password';
    form.elements.namedItem(clear).checked = false;
  }
  for (const button of form.querySelectorAll('.password-toggle-btn')) {
    button.textContent = '👁️'; button.setAttribute('aria-pressed', 'false');
  }
}
export function wipeSecretPayload(body) {
  if (body?.secretUpdates) {
    for (const update of Object.values(body.secretUpdates)) delete update.value;
    delete body.secretUpdates;
  }
}
export function syncSecretControls(form, settings, locked = false) {
  const writable = settings?.secretStorage?.writable === true;
  for (const [name, clear] of secretFields) {
    form.elements.namedItem(name).disabled = locked || !writable;
    form.elements.namedItem(clear).disabled = locked || !writable;
  }
  for (const button of form.querySelectorAll('.password-toggle-btn')) button.disabled = locked || !writable;
}
export function secretStorageMessage(settings) {
  if (settings?.secretStorage?.writable === true) return '凭据由外部主密钥加密保存；留空保留，清除需明确勾选。主密钥不在此页面填写。';
  if (settings?.secretStorage?.mode === 'ENV_ONLY') return '凭据由进程环境注入，页面仅修改非秘密配置。需要在页面保存密钥时，请先配置 ENCRYPTED_FILE 与外部主密钥后重启。';
  return '秘密存储状态未确认，暂不可修改凭据；非秘密配置仍可查看。';
}
