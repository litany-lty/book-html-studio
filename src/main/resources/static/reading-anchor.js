// U1：阅读锚点。记录 bookId + pageNumber + blockId + 文本位置 + 视口内偏移；
// 节点消失时按原始 blockId 或邻近仍存在节点回退，不只恢复 scrollTop。
// 选择文字/编辑/原图缩放时延后更新，由调用方决定时机。
export function recordAnchor(root) {
  const selection = window.getSelection?.();
  const selectedText = selection && selection.rangeCount ? String(selection.toString()).slice(0, 80) : '';
  const viewportTop = root ? root.scrollTop : window.scrollY;
  const viewportHeight = root ? root.clientHeight : window.innerHeight;
  let blockId = null;
  let textOffset = 0;
  let viewportOffset = null;
  const rootTop = root?.getBoundingClientRect?.().top || 0;
  try {
    const node = selection?.anchorNode;
    const el = node instanceof Element ? node : node?.parentElement;
    const holder = el?.closest?.('[data-block-id]');
    if (holder && root?.contains(holder)) {
      blockId = holder.dataset.blockId || null;
      textOffset = selection?.anchorOffset || 0;
      viewportOffset = holder.getBoundingClientRect().top - rootTop;
    }
  } catch (_) {}
  // 视口中部第一个带 blockId 的元素作为稳定回退
  let fallbackId = null;
  let fallbackViewportOffset = null;
  try {
    const elements = root?.querySelectorAll?.('[data-block-id]') || [];
    const mid = rootTop + viewportHeight / 2;
    for (const el of elements) {
      const rect = el.getBoundingClientRect();
      if (rect.bottom > rootTop && rect.top <= mid) {
        fallbackId = el.dataset.blockId;
        fallbackViewportOffset = rect.top - rootTop;
        break;
      }
    }
    if (!fallbackId && elements.length) {
      fallbackId = elements[0].dataset.blockId;
      fallbackViewportOffset = elements[0].getBoundingClientRect().top - rootTop;
    }
  } catch (_) {}
  return { blockId, textOffset, selectedText, viewportTop, viewportHeight, viewportOffset, fallbackId, fallbackViewportOffset, at: Date.now() };
}

export function restoreAnchor(root, anchor) {
  if (!root || !anchor) return false;
  const selected = anchor.blockId && root.querySelector(`[data-block-id="${CSS.escape(anchor.blockId)}"]`);
  const target = selected || (anchor.fallbackId && root.querySelector(`[data-block-id="${CSS.escape(anchor.fallbackId)}"]`));
  if (target) {
    const offset = selected ? anchor.viewportOffset : anchor.fallbackViewportOffset;
    const delta = target.getBoundingClientRect().top - root.getBoundingClientRect().top;
    root.scrollTop = Math.max(0, root.scrollTop + delta - (Number.isFinite(offset) ? offset : 0));
    return true;
  }
  if (Number.isFinite(anchor.viewportTop)) { root.scrollTop = anchor.viewportTop; return true; }
  return false;
}
