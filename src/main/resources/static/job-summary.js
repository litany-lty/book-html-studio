// An ended page is not necessarily accurately recognized or manually verified.
export function settledJobSummary(job, errorCount = 0) {
  const total = Number.isSafeInteger(job?.total) && job.total > 0 ? job.total : 0;
  if (!total) return '';
  const ended = Number.isSafeInteger(job.completed) ? Math.max(0, Math.min(total, job.completed)) : 0;
  const base = `本轮已结束 ${ended} / ${total} 页`;
  const remaining = ended < total ? ` · ${total - ended} 页未完成` : '';
  if (job.status === 'CANCELLED') return `${base} · 已停止${remaining}`;
  if (job.status === 'INTERRUPTED') return `${base} · 已中断${remaining}`;
  if (job.status === 'FAILED') return `${base} · 处理失败${remaining}`;
  if (job.status === 'COMPLETED_WITH_ERRORS' || errorCount > 0)
    return `${base} · 部分未完成或待核对${remaining}`;
  if (job.status === 'COMPLETED' && ended === total)
    return `${base} · 本轮处理完成，文字疑点仍须核对`;
  return `${base} · 状态待确认${remaining}`;
}
