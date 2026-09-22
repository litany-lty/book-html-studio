// U1：唯一任务入口与按需详情。只展示状态/错误，不自行决定付费重试。
// 后台完成不弹 Toast、不自动滚动、不打开面板；用户主动操作（导出完成）在其操作区反馈。
export function initTaskCenter({ openDialog }) {
  const entry = document.querySelector('#reading-window-open');
  const dialog = document.querySelector('#reading-window-dialog');
  if (!entry || !dialog) return;
  entry.addEventListener('click', () => {
    if (typeof openDialog === 'function') { openDialog(); return; }
    if (typeof dialog.showModal === 'function' && !dialog.open) dialog.showModal();
  });
  // 后台事件不得抢焦点：详情只在用户点击时打开，此处不监听任何自动打开路径。
}

export function taskEntryText(activeCount) {
  return activeCount > 0 ? `任务 · ${activeCount}` : '任务';
}
