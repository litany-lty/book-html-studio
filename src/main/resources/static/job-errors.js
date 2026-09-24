// 任务错误按原因聚合：把「第 123 页xxx」这类逐页条目合并成「原因（共 N 页：区间）」，
// 让几百页失败时用户先看到结论而不是几百行。与 app.js 共用区间压缩逻辑。
export function compressPages(pages) {
  const sorted = [...new Set(pages)].sort((a, b) => a - b);
  const parts = [];
  let start = null;
  let prev = null;
  for (const page of sorted) {
    if (start === null) { start = prev = page; continue; }
    if (page === prev + 1) { prev = page; continue; }
    parts.push(start === prev ? String(start) : `${start}-${prev}`);
    start = prev = page;
  }
  if (start !== null) parts.push(start === prev ? String(start) : `${start}-${prev}`);
  return parts.join(',');
}

/**
 * @param {string[]} errors 任务错误原文（形如「第 12 页已识别主要文字，但未能逐字验证…」）
 * @returns {{reason: string, pages: number[], text: string}[]} 按原因聚合的结果，保持首次出现顺序
 */
export function groupJobErrors(errors) {
  const groups = new Map();
  for (const raw of errors || []) {
    const text = String(raw ?? '').trim();
    if (!text) continue;
    const match = text.match(/第\s*(\d+)\s*页/);
    const page = match ? Number(match[1]) : null;
    const reason = text.replace(/第\s*\d+\s*页\s*/, '').trim() || text;
    if (!groups.has(reason)) groups.set(reason, []);
    if (Number.isInteger(page)) groups.get(reason).push(page);
  }
  return [...groups.entries()].map(([reason, pages]) => {
    const unique = [...new Set(pages)].sort((a, b) => a - b);
    return {
      reason,
      pages: unique,
      text: unique.length ? `${reason}（共 ${unique.length} 页：${compressPages(unique)}）` : reason
    };
  });
}
