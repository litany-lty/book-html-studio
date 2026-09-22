import { api } from './api.js';

const dialog = document.querySelector('#settings-dialog');
const form = document.querySelector('#settings-form');
const status = document.querySelector('#settings-status');
const saveStatus = document.querySelector('#settings-save-status');
const saveButton = document.querySelector('#settings-save');
let current = null;
let opener = null;
let requestEpoch = 0;
let saving = false;

function lockInputs(locked) {
  form.querySelectorAll('input, select').forEach(control => { control.disabled = locked; });
  document.querySelector('#settings-local-check').disabled = locked;
}

const field = name => form.elements.namedItem(name);
const checked = name => Boolean(field(name).checked);
const value = name => field(name).value.trim();
const regions = new Set(['cn-beijing', 'ap-southeast-1', 'us-east-1', 'cn-hongkong']);

function updateEndpoint() {
  const region = value('qwenRegion');
  const workspace = value('qwenWorkspaceId');
  const node = document.querySelector('#qwen-endpoint');
  if (workspace && /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(workspace) && regions.has(region)) {
    node.textContent = `只读地址预览：https://${workspace}.${region}.maas.aliyuncs.com/compatible-mode/v1`;
  } else if (!workspace && current?.qwen?.region === region && !current?.qwen?.workspaceId) {
    node.textContent = `当前服务端地址：${current.qwen.baseUrl || '由服务端按区域固定'}；不能在此修改。`;
  } else {
    node.textContent = '保存后由服务端按区域确定官方地址；不接受自定义网址。';
  }
}

function setStatus(message, tone = '') {
  status.textContent = message;
  status.dataset.tone = tone;
}

function setPill(id, label, tone) {
  const pill = document.querySelector(`#${id}`);
  pill.textContent = label;
  pill.dataset.tone = tone;
}

const jevReason = {
  DECISION_OFF: '候选比较已关闭', MISSING_API_KEY: '缺少 API Key', MISSING_MODEL: '缺少模型',
  DATA_EGRESS_NOT_AUTHORIZED: '尚未允许外发', BUDGET_NOT_SET: '尚未设置本地调用预算',
};
const calibrationLabel = { UNCALIBRATED: '未校准', CALIBRATED: '已校准', UNKNOWN: '未知' };
const rateProviders = [
  ['paddle-aistudio', 'PaddleOCR-VL-1.6', 'PaddleOCR-VL-1.6'],
  ['ppocr', 'PP-OCRv6', 'PP-OCRv6'],
  ['qwen', 'Qwen 结构辅助', ''],
  ['jev', 'JEV 候选比较', ''],
];

function renderRates(settings) {
  const host = document.querySelector('#settings-rates');
  host.replaceChildren();
  const rates = settings.billing?.rates || [];
  for (const [provider, label, fixedModel] of rateProviders) {
    const rate = rates.find(item => item.provider === provider) || {};
    const row = document.createElement('div');
    row.className = 'settings-rate';
    row.dataset.provider = provider;
    const title = document.createElement('strong');
    title.textContent = label;
    row.append(title);
    const fields = document.createElement('div');
    fields.className = 'settings-rate-fields';
    const makeField = (text, key, initial, readonly = false) => {
      const wrapper = document.createElement('label');
      wrapper.className = 'field';
      const caption = document.createElement('span');
      caption.textContent = text;
      const input = document.createElement('input');
      input.dataset.rateField = key;
      input.value = initial || '';
      input.readOnly = readonly;
      input.spellcheck = false;
      input.autocomplete = 'off';
      if (key !== 'model') input.inputMode = 'decimal';
      wrapper.append(caption, input);
      fields.append(wrapper);
    };
    makeField('精确匹配模型', 'model', fixedModel || rate.model || '', Boolean(fixedModel));
    const currency = document.createElement('label');
    currency.className = 'field';
    const currencyCaption = document.createElement('span');
    currencyCaption.textContent = '币种';
    const currencySelect = document.createElement('select');
    currencySelect.dataset.rateField = 'currency';
    currencySelect.append(new Option('人民币 CNY', 'CNY'), new Option('美元 USD', 'USD'));
    currencySelect.value = rate.currency === 'USD' ? 'USD' : 'CNY';
    currency.append(currencyCaption, currencySelect);
    fields.append(currency);
    if (fixedModel) makeField('每次送识请求', 'perRequest', rate.perRequest);
    else {
      makeField('每百万输入 tokens', 'inputPerMillion', rate.inputPerMillion);
      makeField('每百万输出 tokens', 'outputPerMillion', rate.outputPerMillion);
    }
    row.append(fields);
    host.append(row);
  }
}

function rateValues() {
  return [...document.querySelectorAll('#settings-rates .settings-rate')].map(row => {
    const entry = { provider: row.dataset.provider, model: '', currency: 'CNY', perRequest: '', inputPerMillion: '', outputPerMillion: '' };
    row.querySelectorAll('[data-rate-field]').forEach(input => { entry[input.dataset.rateField] = input.value.trim(); });
    return entry;
  });
}

function safeError(error) {
  let message = error?.message || '读取配置失败，请稍后重试。';
  // The server should never echo credentials. Also mask anything newly entered if an upstream error does.
  for (const name of ['paddleAccessToken', 'ppocrApiKey', 'ppocrSecretKey', 'qwenApiKey', 'jevApiKey']) {
    const secret = field(name).value;
    if (secret) message = message.replaceAll(secret, '［凭据已隐藏］');
    if (secret.trim() && secret.trim() !== secret) message = message.replaceAll(secret.trim(), '［凭据已隐藏］');
  }
  return message;
}

function setSelectValue(selectElement, val, defaultVal) {
  const target = val || defaultVal;
  if (!target || !selectElement) return;
  const exists = Array.from(selectElement.options).some(opt => opt.value === target);
  if (!exists) {
    const opt = new Option(`${target}（当前已保存）`, target);
    selectElement.add(opt);
  }
  selectElement.value = target;
}

function render(settings) {
  current = settings;
  form.hidden = false;
  form.reset();
  const provider = settings.ocr?.defaultProvider;
  const radio = form.querySelector(`input[name="defaultProvider"][value="${provider === 'ppocr' ? 'ppocr' : 'paddle-aistudio'}"]`);
  if (radio) radio.checked = true;
  field('fallbackEnabled').checked = Boolean(settings.ocr?.fallbackEnabled);
  field('qwenEnabled').checked = Boolean(settings.qwen?.enabled);
  field('qwenRegion').value = regions.has(settings.qwen?.region) ? settings.qwen.region : 'cn-beijing';
  setSelectValue(field('qwenModel'), settings.qwen?.model, 'qwen3.8-max');
  field('qwenWorkspaceId').value = settings.qwen?.workspaceId || '';
  field('jevEnabled').checked = Boolean(settings.jev?.enabled);
  setSelectValue(field('jevModel'), settings.jev?.model, 'jev-1.13.0');
  field('jevBudgetUnits').value = settings.jev?.budgetUnits || '';
  field('jevAllowCloudData').checked = Boolean(settings.jev?.allowCloudData);
  field('paddleAccessToken').value = '';
  if (settings.ocr?.paddleAiStudio?.accessTokenSet) field('paddleAccessToken').placeholder = '已配置；留空保留，输入新值替换';
  field('ppocrApiKey').value = '';
  if (settings.ocr?.ppocr?.apiKeySet) field('ppocrApiKey').placeholder = '已配置；留空保留，输入新值替换';
  field('ppocrSecretKey').value = '';
  if (settings.ocr?.ppocr?.secretKeySet) field('ppocrSecretKey').placeholder = '已配置；留空保留，输入新值替换';
  field('qwenApiKey').value = '';
  if (settings.qwen?.apiKeySet) field('qwenApiKey').placeholder = '已配置；留空保留，输入新值替换';
  field('jevApiKey').value = '';
  if (settings.jev?.apiKeySet) field('jevApiKey').placeholder = '已配置；留空保留，输入新值替换';
  document.querySelectorAll('.password-toggle-btn').forEach(btn => {
    const input = field(btn.dataset.target);
    if (input) input.type = 'password';
    btn.textContent = '👁️';
    btn.setAttribute('aria-pressed', 'false');
  });
  renderRates(settings);
  document.querySelector('#jev-budget-label').textContent = settings.jev?.budgetUnitLabel || '本地调用预算单位';
  updateEndpoint();
  syncToggles();
  const calibration = calibrationLabel[settings.jev?.calibrationStatus] || settings.jev?.calibrationStatus || '未知';
  const mode = settings.jev?.mode || 'OFF';
  document.querySelector('#jev-detail').textContent = `当前模式：${mode}；校准：${calibration}；${jevReason[settings.jev?.reason] || settings.jev?.reason || '调用门槛当前已满足，结果仍需对照原图。'}`;
  setPill('paddle-status', settings.ocr?.paddleAiStudio?.configured ? '已配置 · 未验证连通' : '未配置', settings.ocr?.paddleAiStudio?.configured ? 'ready' : 'muted');
  const ppocr = settings.ocr?.ppocr || {};
  const ppocrMissing = [!ppocr.apiKeySet ? 'API Key' : '', !ppocr.secretKeySet ? 'Secret Key' : ''].filter(Boolean).join('、');
  setPill('ppocr-status', ppocr.configured ? '已配置 · 未验证连通' : `未配置 · 缺 ${ppocrMissing}`, ppocr.configured ? 'ready' : 'muted');
  setPill('qwen-status', !settings.qwen?.enabled ? '已关闭' : settings.qwen?.configured ? '已配置 · 未验证连通' : '未配置', settings.qwen?.enabled && settings.qwen?.configured ? 'ready' : 'muted');
  setPill('jev-status', !settings.jev?.enabled ? '已关闭' : !settings.jev?.apiKeySet ? '未配置' : settings.jev?.available ? '已配置 · 未验证连通' : '已配置 · 受门槛限制', settings.jev?.enabled && settings.jev?.available ? 'ready' : 'muted');
  const busy = Boolean(settings.busy);
  saveButton.disabled = busy;
  setStatus(busy ? '当前有处理或评估任务运行。配置变更暂被锁定，请待任务结束后重新打开。' : '只检查本地填写，不会自动调用云服务或消耗额度。', busy ? 'warning' : '');
  saveStatus.textContent = '';
  if (saving) lockInputs(true);
}

function validate() {
  const modelPattern = /^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/;
  for (const [secret, clear] of [
    ['paddleAccessToken', 'clearPaddleAccessToken'], ['ppocrApiKey', 'clearPpocrApiKey'],
    ['ppocrSecretKey', 'clearPpocrSecretKey'], ['qwenApiKey', 'clearQwenApiKey'], ['jevApiKey', 'clearJevApiKey'],
  ]) {
    if (value(secret) && checked(clear)) return '同一项凭据不能同时填写新值并勾选清除。';
  }
  if (value('qwenModel') && !modelPattern.test(value('qwenModel'))) return 'Qwen 模型需为 1–120 位字母、数字、点、下划线或连字符。';
  if (value('jevModel') && !modelPattern.test(value('jevModel'))) return 'JEV 模型需为 1–120 位字母、数字、点、下划线或连字符。';
  if (value('jevBudgetUnits') && !/^[1-9]\d{0,17}$/.test(value('jevBudgetUnits'))) return 'JEV 本地调用预算单位必须为不超过 18 位的正整数。';
  if (!regions.has(value('qwenRegion'))) return '请选择受支持的百炼服务区域。';
  if (value('qwenWorkspaceId') && !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(value('qwenWorkspaceId'))) return '业务空间 ID 只能是安全的域名前缀：小写字母、数字和中间的连字符。';
  const pricePattern = /^(?:0|[1-9]\d{0,11})(?:\.\d{1,8})?$/;
  for (const rate of rateValues()) {
    if (rate.model && !modelPattern.test(rate.model)) return `${rate.provider} 的单价模型标识格式无效。`;
    for (const key of ['perRequest', 'inputPerMillion', 'outputPerMillion']) {
      if (rate[key] && !pricePattern.test(rate[key])) return `${rate.provider} 的参考单价须为非负小数，最多 8 位小数。`;
    }
  }
  return '';
}

function readinessWarnings() {
  const hasKey = (set, clear, typed) => !clear && (set || Boolean(typed));
  const warnings = [];
  const paddle = hasKey(current?.ocr?.paddleAiStudio?.accessTokenSet, checked('clearPaddleAccessToken'), value('paddleAccessToken'));
  const ppocr = hasKey(current?.ocr?.ppocr?.apiKeySet, checked('clearPpocrApiKey'), value('ppocrApiKey'))
    && hasKey(current?.ocr?.ppocr?.secretKeySet, checked('clearPpocrSecretKey'), value('ppocrSecretKey'));
  if (field('defaultProvider').value === 'paddle-aistudio' && !paddle) warnings.push('默认 OCR 缺 Access Token');
  if (field('defaultProvider').value === 'ppocr' && !ppocr) warnings.push('默认 OCR 缺 API Key 或 Secret Key');
  if (checked('qwenEnabled')) {
    if (!hasKey(current?.qwen?.apiKeySet, checked('clearQwenApiKey'), value('qwenApiKey'))) warnings.push('Qwen 缺 API Key');
    if (!value('qwenModel')) warnings.push('Qwen 缺模型');
  }
  if (checked('jevEnabled')) {
    if (!hasKey(current?.jev?.apiKeySet, checked('clearJevApiKey'), value('jevApiKey'))) warnings.push('JEV 缺 API Key');
    if (!value('jevModel')) warnings.push('JEV 缺模型');
    if (!value('jevBudgetUnits') && !current?.jev?.budgetUnits) warnings.push('JEV 缺本地调用预算');
    if (!checked('jevAllowCloudData')) warnings.push('JEV 未授权外发');
  }
  return warnings;
}

function payload() {
  const secret = (name, key, clearName, clearKey) => ({ ...(value(name) ? { [key]: value(name) } : {}), ...(checked(clearName) ? { [clearKey]: true } : {}) });
  return {
    revision: current.revision,
    ocr: {
      defaultProvider: field('defaultProvider').value,
      fallbackEnabled: checked('fallbackEnabled'),
      paddleAiStudio: secret('paddleAccessToken', 'accessToken', 'clearPaddleAccessToken', 'clearAccessToken'),
      ppocr: { ...secret('ppocrApiKey', 'apiKey', 'clearPpocrApiKey', 'clearApiKey'), ...secret('ppocrSecretKey', 'secretKey', 'clearPpocrSecretKey', 'clearSecretKey') },
    },
    qwen: { enabled: checked('qwenEnabled'), region: value('qwenRegion'), model: value('qwenModel'), workspaceId: value('qwenWorkspaceId'), ...secret('qwenApiKey', 'apiKey', 'clearQwenApiKey', 'clearApiKey') },
    jev: { enabled: checked('jevEnabled'), model: value('jevModel'), ...(value('jevBudgetUnits') ? { budgetUnits: value('jevBudgetUnits') } : {}), allowCloudData: checked('jevAllowCloudData'), ...secret('jevApiKey', 'apiKey', 'clearJevApiKey', 'clearApiKey') },
    billing: { rates: rateValues() },
  };
}

async function refresh() {
  const epoch = ++requestEpoch;
  form.hidden = true;
  if (saving) {
    setStatus('前次保存仍在进行。完成后会重新读取配置；请勿重复提交。', 'warning');
    return;
  }
  setStatus('正在读取本机配置…');
  try {
    const settings = await api.settings();
    if (epoch !== requestEpoch || !dialog.open) return;
    render(settings);
  } catch (error) {
    if (epoch !== requestEpoch || !dialog.open) return;
    setStatus(safeError(error), 'error');
  }
}

document.querySelector('#settings-open').addEventListener('click', () => {
  opener = document.activeElement;
  dialog.showModal();
  void refresh();
});
document.querySelector('#settings-close').addEventListener('click', () => dialog.close());
dialog.addEventListener('close', () => {
  requestEpoch++;
  current = null;
  form.reset();
  form.hidden = true;
  document.querySelectorAll('.password-toggle-btn').forEach(btn => {
    const input = field(btn.dataset.target);
    if (input) input.type = 'password';
    btn.textContent = '👁️';
    btn.setAttribute('aria-pressed', 'false');
  });
  if (opener?.isConnected) opener.focus({ preventScroll: true });
  opener = null;
});
document.querySelectorAll('.password-toggle-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const input = field(btn.dataset.target);
    if (!input) return;
    const isPass = input.type === 'password';
    input.type = isPass ? 'text' : 'password';
    btn.textContent = isPass ? '🙈' : '👁️';
    btn.setAttribute('aria-pressed', String(isPass));
  });
});
document.querySelector('#settings-local-check').addEventListener('click', () => {
  const error = validate();
  const warnings = error ? [] : readinessWarnings();
  setStatus(error || (warnings.length ? `本地格式通过，但以下配置尚不可用：${warnings.join('；')}。未调用云服务。` : '本地填写符合格式要求；未向云服务发起连通验证。'), error ? 'error' : warnings.length ? 'warning' : 'ready');
});
function syncToggles() {
  const jevOn = field('jevEnabled')?.checked;
  document.querySelector('#settings-jev-body')?.classList.toggle('is-disabled-body', !jevOn);
  const qwenOn = field('qwenEnabled')?.checked;
  document.querySelector('#settings-qwen-body')?.classList.toggle('is-disabled-body', !qwenOn);
  const model = field('jevModel')?.value;
  const modelTag = document.querySelector('#jev-active-model-tag');
  if (modelTag && model) modelTag.textContent = `模型: ${model}`;
}

field('qwenRegion').addEventListener('change', updateEndpoint);
field('qwenWorkspaceId').addEventListener('input', updateEndpoint);
field('jevEnabled').addEventListener('change', syncToggles);
field('qwenEnabled').addEventListener('change', syncToggles);
field('jevModel').addEventListener('change', syncToggles);
form.addEventListener('submit', async event => {
  event.preventDefault();
  if (!current || saveButton.disabled || saving) return;
  const error = validate();
  if (error) { setStatus(error, 'error'); return; }
  const cleared = [...form.querySelectorAll('input[name^="clear"]:checked')];
  if (cleared.length && !window.confirm(`将清除 ${cleared.length} 项已保存凭据。保存后无法恢复，确定继续吗？`)) return;
  const body = payload();
  const csrfToken = current.csrfToken;
  const saveEpoch = requestEpoch;
  saving = true;
  lockInputs(true);
  saveButton.disabled = true;
  saveStatus.textContent = '正在保存…';
  let saved = false;
  let needsRefresh = false;
  const sameOpen = () => dialog.open && requestEpoch === saveEpoch;
  try {
    await api.saveSettings(body, csrfToken);
    saved = true;
    let settings = null;
    try { settings = await api.settings(); } catch (_) { /* 已保存，但读回可能失败。 */ }
    if (sameOpen()) {
      if (settings) {
        render(settings);
        saveButton.disabled = true;
        saveStatus.textContent = '配置已保存；连通与额度尚未验证。';
        setStatus('配置已保存；正在刷新可用处理通道。', 'ready');
      } else {
        current = null;
        form.hidden = true;
        saveStatus.textContent = '已保存 · 待核对';
        setStatus('配置已保存，但读取最新状态失败。请关闭并重新打开配置页核对，再开始处理。', 'warning');
      }
    }
    try {
      await window.refreshProcessingConfig?.();
      if (sameOpen() && settings) setStatus('配置已保存，处理通道已刷新；未进行云端连通测试。', 'ready');
    } catch (_) {
      if (sameOpen()) setStatus('配置已保存，但处理通道未能刷新。请刷新页面后再开始任务。', 'warning');
    }
  } catch (failure) {
    if (!saved && failure?.status === 409) needsRefresh = true;
    else if (!saved && sameOpen()) {
      setStatus(`配置未确认保存：${safeError(failure)} 请重新读取配置确认状态。`, 'error');
      saveStatus.textContent = '';
    }
  } finally {
    saving = false;
    lockInputs(false);
    if (dialog.open && (!sameOpen() || needsRefresh)) {
      await refresh();
      if (needsRefresh && dialog.open && !form.hidden) setStatus('配置未保存：版本已变化或任务正在运行。已重新读取服务端状态，请核对后再修改。', 'warning');
    } else if (dialog.open) saveButton.disabled = !current || Boolean(current.busy);
  }
});
