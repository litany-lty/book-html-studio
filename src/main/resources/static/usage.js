import { api } from './api.js';

const dialog = document.querySelector('#usage-dialog');
const status = document.querySelector('#usage-status');
const content = document.querySelector('#usage-content');
let book = null;
let opener = null;
let cursor = null;
let nextOffset = null;
let epoch = 0;
let controller = null;
let asOf = null;

const add = (parent, tag, className, message) => {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (message != null) element.textContent = message;
  parent.append(element);
  return element;
};
const count = value => value != null && Number.isFinite(Number(value)) ? Number(value).toLocaleString('zh-CN') : '未知';
const amounts = (items, empty) => Array.isArray(items) && items.length
  ? items.map(item => `${item.amount ?? '未知'} ${item.currency || '币种未知'}`).join('、') : empty;
const operationLabel = { OCR: '文字识别', OCR_PAGE: '页面解析', QWEN: 'Qwen 结构辅助', JEV: 'JEV 候选比较', JEV_DECISION: 'JEV 候选比较', LOCAL_OCR: '局部识别' };
const providerLabel = { 'paddle-aistudio': '飞桨 AI Studio', ppocr: '百度智能云 PP-OCRv6', qwen: '百炼 Qwen', jev: 'JEV' };
const statusLabel = { PREPARED: '已准备，未进入发送', SENT_UNKNOWN: '发送结果待确认', OUTCOME_UNKNOWN: '远端结果未知', NOT_SENT: '未发送', SUCCEEDED: '成功', FAILED: '失败', CACHE_REUSED: '缓存复用', PENDING: '远端处理中' };
const feeLabel = { UNKNOWN: '费用未知', ESTIMATED: '按自填单价估算', REPORTED: '供应商回传', CACHE_REUSE: '缓存复用，未新增外呼' };

function renderSummary(data) {
  content.replaceChildren();
  add(content, 'p', 'usage-coverage', `本书自 ${data.recordedSince ? new Date(data.recordedSince).toLocaleString('zh-CN') : '记录启用时'} 起累计记录；旧版本历史未覆盖，不能把缺失记录视为 0 元。`);
  add(content, 'p', 'usage-caveat', '这不是供应商账单。未收到实付回传时，估算只基于你填写的对应模型单价；币种分别列出，不合并换算。');
  const totals = data.totals || {};
  const summary = add(content, 'section', 'usage-summary');
  add(summary, 'h3', '', '累计摘要');
  const stats = add(summary, 'dl', 'usage-stats');
  for (const [label, value] of [
    ['请求', totals.requests], ['成功', totals.success], ['失败', totals.failed],
    ['待确认', totals.pending], ['未发送', totals.notSent], ['待发送', totals.prepared], ['缓存命中', totals.cacheHits],
    ['已知输入 tokens', totals.inputTokens], ['已知输出 tokens', totals.outputTokens],
    ['缺输入用量请求', totals.unknownInputTokenRequests], ['缺输出用量请求', totals.unknownOutputTokenRequests],
    ['费用未知请求', totals.unpricedRequests],
  ]) {
    add(stats, 'dt', '', label);
    add(stats, 'dd', '', count(value));
  }
  const money = add(summary, 'div', 'usage-money');
  add(money, 'p', '', `供应商实付回传：${amounts(totals.reportedAmounts, '暂无回传金额，不能确认实际支付额')}`);
  add(money, 'p', '', `按自填单价估算：${amounts(totals.estimatedAmounts, '暂无可估算金额')}`);
  const providers = add(content, 'section', 'usage-providers');
  add(providers, 'h3', '', '按通道与模型');
  const providerRows = Array.isArray(data.providers) ? data.providers : [];
  if (!providerRows.length) add(providers, 'p', 'usage-empty', '尚无追踪启用后的调用记录。');
  for (const row of providerRows) {
    const item = add(providers, 'div', 'usage-provider');
    add(item, 'strong', '', `${providerLabel[row.provider] || row.provider || '未知通道'} · ${row.model || '未知模型'}`);
    add(item, 'span', '', `${count(row.requests)} 次请求 · ${count(row.cacheHits)} 次缓存 · ${count(row.unpricedRequests)} 次费用未知`);
    add(item, 'small', '', `估算：${amounts(row.estimatedAmounts, '未知')}；实付回传：${amounts(row.reportedAmounts, '无')}`);
  }
  const history = add(content, 'section', 'usage-history');
  add(history, 'h3', '', '调用记录');
  const list = add(history, 'ol', 'usage-list');
  list.id = 'usage-list';
  const more = add(history, 'button', 'button usage-more', '加载更多');
  more.id = 'usage-more';
  more.type = 'button';
  more.hidden = data.nextCursor == null && data.nextOffset == null;
  more.addEventListener('click', () => void loadNext());
  renderEntries(data.entries || []);
  cursor = data.nextCursor;
  nextOffset = data.nextCursor == null ? data.nextOffset : null;
}

function renderEntries(entries) {
  const list = document.querySelector('#usage-list');
  if (!list) return;
  if (entries.length) list.querySelector('.usage-empty')?.remove();
  if (!list.children.length && !entries.length) add(list, 'li', 'usage-empty', '此范围内没有调用记录。');
  for (const entry of entries) {
    const item = add(list, 'li', 'usage-entry');
    const date = entry.createdAt ? new Date(entry.createdAt).toLocaleString('zh-CN') : '时间未知';
    add(item, 'strong', '', `${operationLabel[entry.operation] || entry.operation || '调用'} · ${providerLabel[entry.provider] || entry.provider || '未知通道'}`);
    add(item, 'span', '', `${date} · ${entry.pageNumber ? `第 ${entry.pageNumber} 页 · ` : ''}${statusLabel[entry.status] || entry.status || '状态未知'}`);
    add(item, 'span', '', `模型 ${entry.model || '未知'}；输入 ${count(entry.inputTokens)} / 输出 ${count(entry.outputTokens)} tokens`);
    add(item, 'span', 'usage-fee', `${feeLabel[entry.feeKind] || '费用未知'}${entry.amount != null && entry.currency ? `：${entry.amount} ${entry.currency}` : ''}`);
  }
}

async function loadFirst() {
  const myEpoch = ++epoch;
  controller?.abort();
  controller = new AbortController();
  asOf = null;
  cursor = null;
  nextOffset = null;
  content.replaceChildren();
  content.hidden = true;
  status.textContent = '正在读取本书累计记录…';
  try {
    const data = await api.bookUsage(book.id, null, 50, null, controller.signal);
    if (myEpoch !== epoch || !dialog.open) return;
    asOf = data.asOf || null;
    renderSummary(data);
    content.hidden = false;
    status.textContent = '已读取当前记录；新调用需刷新后显示。';
  } catch (error) {
    if (myEpoch !== epoch || !dialog.open || error?.name === 'StaleRequest') return;
    status.textContent = `未能读取用量记录：${error?.message || '请稍后重试。'}`;
    const retry = add(content, 'button', 'button', '重新读取');
    retry.type = 'button';
    retry.addEventListener('click', () => void loadFirst());
    content.hidden = false;
  }
}

async function loadNext() {
  if ((cursor == null && nextOffset == null) || !book) return;
  const myEpoch = epoch;
  const button = document.querySelector('#usage-more');
  button.disabled = true;
  button.textContent = '正在加载…';
  try {
    const data = await api.bookUsage(book.id, cursor, 50, asOf, controller?.signal, nextOffset);
    if (myEpoch !== epoch || !dialog.open) return;
    renderEntries(data.entries || []);
    cursor = data.nextCursor;
    nextOffset = data.nextCursor == null ? data.nextOffset : null;
    button.hidden = cursor == null && nextOffset == null;
    status.textContent = '更多记录已加载。';
  } catch (error) {
    if (myEpoch !== epoch || !dialog.open || error?.name === 'StaleRequest') return;
    status.textContent = `加载下一页失败：${error?.message || '请重试。'}`;
  } finally {
    if (myEpoch === epoch) { button.disabled = false; button.textContent = '加载更多'; }
  }
}

export function openBookUsage(selectedBook) {
  if (!selectedBook) return;
  book = selectedBook;
  opener = document.activeElement;
  document.querySelector('#usage-book-name').textContent = selectedBook.title || '当前书籍';
  dialog.showModal();
  void loadFirst();
}

document.querySelector('#usage-close').addEventListener('click', () => dialog.close());
document.querySelector('#usage-refresh').addEventListener('click', () => { if (book) void loadFirst(); });
dialog.addEventListener('close', () => {
  epoch++;
  controller?.abort();
  controller = null;
  asOf = null;
  cursor = null;
  nextOffset = null;
  book = null;
  content.replaceChildren();
  if (opener?.isConnected) opener.focus({ preventScroll: true });
  opener = null;
});
