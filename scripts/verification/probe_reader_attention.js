// U1 探针：后台自动任务 UI 与阅读视口交叠面积必须为 0。
// 用法（独立端口合成书，禁止日常 18765 端口）：
//   UX_SERVER=http://127.0.0.1:18767 node scripts/verification/probe_reader_attention.js
// 断言：
//  1. .reader-viewport 或 #reader 视口矩形与所有自动任务层（.reading-window-bar,
//     .page-progress-card, .toast-region .toast）无交叠；
//  2. 视口顶部/中部/底部抽查点 elementsFromPoint 未被任务元素截获；
//  3. 用户主动打开的任务详情（dialog[open]）不计入自动覆盖。
// 后台无任务的空闲页截图不能作为通过证据；必须在真实任务状态下执行。
export async function attentionOverlap({ viewportRect, autoLayers, pointHits }) {
  const overlap = (a, b) => {
    const x = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
    const y = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
    return x * y;
  };
  let total = 0;
  for (const layer of autoLayers) total += overlap(viewportRect, layer);
  const hijacked = (pointHits || []).filter(hit => hit.hijackedByTask);
  return { totalOverlap: total, hijacked, pass: total === 0 && hijacked.length === 0 };
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { attentionOverlap };
}
