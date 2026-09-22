'use strict';
// A→B→A 回归：执行 app.js 中真实的任务函数和提交监听器，不启动服务或创建任务。
// 用法：node scripts/verification/probe_job_scope.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../src/main/resources/static/app.js'), 'utf8');

function functionSource(name) {
  const match = new RegExp(`^(?:async )?function ${name}\\(`, 'm').exec(source);
  assert.ok(match, `app.js 缺少 ${name}`);
  const end = source.indexOf('\n}\n', match.index);
  assert.ok(end > match.index, `app.js 中 ${name} 无法提取`);
  return source.slice(match.index, end + 3);
}

function submitListenerSource() {
  const start = source.indexOf("$('#job-form').addEventListener('submit'");
  assert.ok(start >= 0, 'app.js 缺少任务提交监听器');
  const end = source.indexOf('\n});', start);
  assert.ok(end > start, 'app.js 中任务提交监听器无法提取');
  return source.slice(start, end + 4);
}

const actualCode = [
  'let bookRequest = 1; let jobSyncError = false;',
  ...['clearPolling', 'jobSessionMatches', 'schedulePoll', 'showJobSyncError', 'refreshJob'].map(functionSource),
  submitListenerSource(),
].join('\n');

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function fixture() {
  const effects = { render: 0, error: 0, toast: 0, busyClear: 0 };
  const button = { disabled: false };
  const nodes = {
    '#all-pages': { checked: true },
    '#qwen-assist': { checked: false, disabled: false },
    '#force-processing': { checked: false },
    '#job-progress': { hidden: true },
    '#job-recovery': { hidden: true },
    '#job-recovery-message': {},
    '#refresh-job': { hidden: true },
    '#edit-job': { hidden: true },
  };
  const listeners = {};
  nodes['#job-form'] = { addEventListener(type, handler) { listeners[type] = handler; } };
  const context = vm.createContext({
    state: { book: { id: 'A', totalPages: 3 }, pollTimer: null },
    activeJobs: new Set(['RUNNING', 'QUEUED']),
    readingWindow: { active: () => false },
    api: {},
    window: {
      setTimeout(callback) { context.timer = callback; return 1; },
      clearTimeout() {},
      confirm() { return true; },
    },
    $: selector => nodes[selector] || (nodes[selector] = {}),
    FormData: class { get(key) { return key === 'pages' ? '1' : key === 'layout' ? 'auto' : null; } },
    selectedProvider: () => 'local',
    providerQuotaSource: () => null,
    validateRange: () => true,
    setBusy(_button, busy) { if (!busy) effects.busyClear++; },
    renderJob() { effects.render++; },
    toast() { effects.toast++; },
    showError() { effects.error++; },
  });
  vm.runInContext(actualCode, context, { filename: 'app.js (job scope excerpt)' });
  assert.equal(typeof listeners.submit, 'function');
  return { context, effects, button, nodes, listeners };
}

function switchAwayAndBack({ context }) {
  vm.runInContext("state.book = { id: 'B', totalPages: 3 }; ++bookRequest; state.book = { id: 'A', totalPages: 3 }; ++bookRequest;", context);
}

async function checkRefreshJob(rejectOldResponse) {
  const test = fixture();
  const response = deferred();
  test.context.api.job = () => response.promise;
  const pending = vm.runInContext('refreshJob()', test.context);
  switchAwayAndBack(test);
  if (rejectOldResponse) response.reject(new Error('旧 A 刷新失败'));
  else response.resolve({ status: 'FAILED' });
  await pending;
  assert.equal(test.effects.render, 0);
  assert.equal(test.effects.error, 0);
  assert.equal(test.nodes['#job-recovery'].hidden, true);
}

async function checkSchedulePoll(rejectOldResponse) {
  const test = fixture();
  const response = deferred();
  test.context.api.job = () => response.promise;
  vm.runInContext('schedulePoll()', test.context);
  const pending = test.context.timer();
  switchAwayAndBack(test);
  if (rejectOldResponse) response.reject(new Error('旧 A 轮询失败'));
  else response.resolve({ status: 'FAILED' });
  await pending;
  assert.equal(test.effects.render, 0);
  assert.equal(test.effects.error, 0);
  assert.equal(test.nodes['#job-recovery'].hidden, true);
}

async function checkStartJob(rejectOldResponse) {
  const test = fixture();
  const response = deferred();
  let requestedBookId;
  test.context.api.startJob = id => { requestedBookId = id; return response.promise; };
  const pending = test.listeners.submit({
    preventDefault() {},
    currentTarget: { querySelector() { return test.button; } },
  });
  switchAwayAndBack(test);
  test.button.disabled = true; // 新 A 会话已设置的状态不应被旧请求 finally 清除。
  if (rejectOldResponse) response.reject(new Error('旧 A 创建失败'));
  else response.resolve({ status: 'RUNNING' });
  await pending;
  assert.equal(requestedBookId, 'A');
  assert.equal(test.effects.render, 0);
  assert.equal(test.effects.toast, 0);
  assert.equal(test.effects.error, 0);
  assert.equal(test.effects.busyClear, 0);
  assert.equal(test.button.disabled, true);
}

async function checkCurrentSessionStillUpdates() {
  const refreshed = fixture();
  refreshed.context.api.job = async () => ({ status: 'FAILED' });
  await vm.runInContext('refreshJob()', refreshed.context);
  assert.equal(refreshed.effects.render, 1);

  const polled = fixture();
  polled.context.api.job = async () => ({ status: 'RUNNING' });
  vm.runInContext('schedulePoll()', polled.context);
  await polled.context.timer();
  assert.equal(polled.effects.render, 1);

  const started = fixture();
  started.context.api.startJob = async () => ({ status: 'RUNNING' });
  await started.listeners.submit({
    preventDefault() {},
    currentTarget: { querySelector() { return started.button; } },
  });
  assert.equal(started.effects.render, 1);
  assert.equal(started.effects.toast, 1);
  assert.equal(started.effects.busyClear, 1);
}

(async () => {
  for (const rejectOldResponse of [false, true]) {
    await checkRefreshJob(rejectOldResponse);
    await checkSchedulePoll(rejectOldResponse);
    await checkStartJob(rejectOldResponse);
  }
  await checkCurrentSessionStillUpdates();
  console.log('JOB_SCOPE_ALL_PASS: 旧会话 6 组成功/失败响应隔离、旧 startJob finally 按钮隔离，以及当前会话 3 组正向更新');
})().catch(error => { console.error(error); process.exitCode = 1; });
