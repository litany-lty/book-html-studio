const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

class Element {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.style = {};
    this.dataset = {};
    this.events = {};
    this.className = '';
    this.value = '';
  }
  append(...children) { children.forEach(child => { child.parent = this; this.children.push(child); }); }
  replaceChildren(...children) {
    this.children.forEach(child => { child.parent = null; });
    this.children = [];
    this.append(...children);
  }
  contains(node) { return this === node || this.children.some(child => child.contains?.(node)); }
  addEventListener(type, listener) { (this.events[type] ||= []).push(listener); }
  setAttribute(name, value) { this[name] = value; }
  fire(type) { for (const listener of this.events[type] || []) listener({ target: this }); }
}

function find(root, predicate) {
  if (predicate(root)) return root;
  for (const child of root.children) {
    const found = find(child, predicate);
    if (found) return found;
  }
  return null;
}

const sandbox = {
  console,
  URL,
  Promise,
  document: { baseURI: 'http://localhost/', createElement: tag => new Element(tag) },
  requestAnimationFrame: callback => callback()
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync('src/main/resources/static/reading-layout.js', 'utf8'), sandbox);
vm.runInContext(fs.readFileSync('src/main/resources/static/editor.js', 'utf8')
  .replace(/export (function|const) /g, '$1 ') + '\nglobalThis.EditorProbe = { renderIssueWorkbench };', sandbox);

const exact = {
  mode: 'glyphs', pending: false, glyphCount: 1,
  contextSrc: '/context.png', src: '/glyph.png',
  contextBbox: [.1, .2, .1, .6], boxes: [[.12, .3, .02, .03]]
};
const context = sandbox.BookReadingLayout.createContextEvidence(exact);
assert.equal(context.targets.length, 1);
assert.equal(context.targets[0].style.left, '19.99999999999999%');
assert.equal(sandbox.BookReadingLayout.createContextEvidence({ ...exact, mode: 'region' }).targets.length, 0);
assert.equal(sandbox.BookReadingLayout.createContextEvidence({ ...exact, contextSrc: '' }).targets.length, 0);

const issues = [
  { id: 'a', start: 0, end: 90, inferredText: '甲乙', reason: '第一处', resolved: false, replacement: '' },
  { id: 'b', start: 90, end: 100, reason: '第二处', resolved: false, replacement: '' }
];
const block = { id: 'block', order: 1, bbox: [.1, .2, .03, .55], original: '壹貳參肆'.repeat(30), issues };
const page = { issueImages: { a: { pending: true }, b: { pending: true } } };
const pending = {};
const loadIssueEvidence = id => new Promise(resolve => { pending[id] = resolve; });
const container = new Element('section');
const options = selectedIssueId => ({
  selectedIssueId, page, pageWidth: 728, pageHeight: 515,
  imageUrl: '/page.png', loadIssueEvidence,
  onSelect() {}, onUpdate() {}, onDirty() {}
});
const render = id => sandbox.EditorProbe.renderIssueWorkbench(container, [block], options(id));
const selectedText = () => find(container, element => element.tagName === 'TEXTAREA');
const visual = () => find(container, element => element.className === 'issue-source-visual');
const flush = async () => { await Promise.resolve(); await Promise.resolve(); };

(async () => {
  render('a');
  assert.equal(selectedText().value, '甲乙');
  selectedText().value = '手写草稿'; selectedText().fire('input');
  assert.equal(issues[0].replacement, '手写草稿');
  await flush();
  render('b');
  assert.equal(selectedText().value, block.original.slice(90, 100));
  await flush();
  pending.a(exact);
  await flush();
  assert.equal(visual().children[0].textContent, '正在加载当前疑点的原稿依据…');
  pending.b({ ...exact, contextSrc: '/b-context.png' });
  await flush();
  assert.equal(find(visual(), node => node.tagName === 'IMG').src, '/b-context.png');
  render('a');
  assert.equal(selectedText().value, '手写草稿');
  selectedText().value = ''; selectedText().fire('input');
  render('b'); render('a');
  assert.equal(selectedText().value, '');
  await flush();
  pending.a(null);
  await flush();
  const fallback = find(visual(), element => element.className === 'issue-fallback-crop');
  assert.ok(fallback);
  assert.ok(Number(fallback.style.aspectRatio) < .6);
  assert.match(fallback.style.backgroundSize, /% auto$/);
  assert.ok(find(container, element => element.tagName === 'DETAILS' && element.className === 'issue-source-ocr'));
  let opened = false;
  sandbox.BookReadingLayout = { ...sandbox.BookReadingLayout, inspectIssue({ page: openedPage }) {
    assert.strictEqual(openedPage, page);
    opened = true;
  } };
  find(container, element => element.className === 'issue-source-actions').children[0].fire('click');
  assert.ok(opened);
  const sixLines = '1. 命宫\n2. 兄弟宫\n3. 夫妻宫\n4. 子女宫\n5. 财帛宫\n6. 疾厄宫';
  const issue23 = { id: 'p23', start: 0, end: sixLines.length, inferredText: sixLines,
    reason: '原图是分行目录样式，当前候选仅供核对；需要对照整行原稿逐项判断，不应自动视为已确认的最终文字。', replacement: '', resolved: false };
  const p23Container = new Element('section');
  sandbox.EditorProbe.renderIssueWorkbench(p23Container,
    [{ id: 'p23-block', order: 5, bbox: [.5, .16, .033, .54], original: sixLines, issues: [issue23] }],
    { ...options('p23'), page: { issueImages: { p23: { mode: 'region', contextSrc: '/p23.png', src: '/p23.png' } } } });
  for (const label of ['原始 OCR', '推测候选（未确认）', '判断依据']) {
    assert.ok(find(p23Container, element => element.tagName === 'SUMMARY' && element.textContent.startsWith(label)), label);
  }
  assert.ok(find(p23Container, element => element.tagName === 'TEXTAREA'));
  const p23Edit = find(p23Container, element => element.className === 'issue-edit');
  const editIndex = p23Edit.children.findIndex(element => element.tagName === 'LABEL');
  assert.ok(editIndex >= 0);
  assert.equal(p23Edit.children[editIndex + 1].className, 'issue-actions');
  assert.equal(p23Edit.children[editIndex + 2].className, 'issue-meta-details');
  console.log('EDITOR_EVIDENCE_RACE_DRAFT_GEOMETRY_PASS');
})().catch(error => { console.error(error); process.exitCode = 1; });
