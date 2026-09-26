(() => {
  'use strict';

  function progress(page, total) {
    const numericTotal = Number(total);
    const safeTotal = Number.isFinite(numericTotal) ? Math.max(0, Math.trunc(numericTotal)) : 0;
    if (!safeTotal) return { page: 0, total: 0, percent: 0 };
    const numericPage = Number(page);
    const safePage = Math.min(safeTotal, Math.max(1, Number.isFinite(numericPage) ? Math.trunc(numericPage) : 1));
    const percent = safeTotal === 1 ? 100 : (safePage - 1) / (safeTotal - 1) * 100;
    return { page: safePage, total: safeTotal, percent };
  }

  function activeIndex(outline, page, blockId) {
    if (!Array.isArray(outline) || !outline.length) return -1;
    const currentPage = Math.max(1, Math.trunc(Number(page) || 1));
    if (blockId) {
      const exact = outline.findIndex(entry => Number(entry?.pageNumber) === currentPage && entry?.blockId === blockId);
      if (exact >= 0) return exact;
    }
    const samePage = outline.findIndex(entry => Number(entry?.pageNumber) === currentPage);
    if (samePage >= 0) return samePage;
    let active = -1;
    let activePage = -1;
    outline.forEach((entry, index) => {
      const entryPage = Number(entry?.pageNumber);
      if (Number.isInteger(entryPage) && entryPage < currentPage && entryPage >= activePage) {
        active = index;
        activePage = entryPage;
      }
    });
    return active;
  }

  // A number field being edited is a navigation intent, not another render target.
  // Page/network refreshes may update status without replacing the unsubmitted value.
  function createPageInput() {
    let editingBook = null;
    return Object.freeze({
      edit: bookId => { editingBook = bookId || null; },
      reset: () => { editingBook = null; },
      project: (input, page, bookId) => {
        if (!bookId || editingBook !== bookId) input.value = String(page);
      }
    });
  }

  globalThis.BookReaderNavigation = Object.freeze({ activeIndex, progress, createPageInput });
})();
