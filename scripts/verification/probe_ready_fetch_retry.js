// U2 探针：ready GET 失败/取消后，同 revision 仍 READY 时可重新 GET 成功，
// 且 OCR POST（付费提交）请求数不增加。
// 用法（独立端口合成书，禁止日常 18765 端口）：
//   UX_SERVER=http://127.0.0.1:18767 node scripts/verification/probe_ready_fetch_retry.js
// 步骤：
//  1. 记录 POST /reading-window 与 GET page 的基准计数（脚本自行维护计数器）；
//  2. 对快照中 READY 的页，第一次 GET 注入失败（offline/abort）；
//  3. 同 revision 再次 snapshot → 必须重新 GET 并成功展示（seenReady 未被失败污染）；
//  4. 全程 OCR POST 计数增量必须为 0（重试的是只读 GET，不是重新提交 OCR）。
// 判定：readyRefetchOk === true && ocrPostDelta === 0。
export async function readyFetchRetryPolicy() {
  return {
    readyRefetchOk: false,
    ocrPostDelta: 0,
    note: '需在独立端口合成书 + 真实快照下运行；本文件只固定判定契约。',
  };
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { readyFetchRetryPolicy };
}
