const BLOCK_TYPES = [
  ['text', '正文'], ['heading', '标题'], ['advertisement', '广告'], ['figure', '插图'], ['table', '表格'], ['formula', '公式'], ['caption', '图注'], ['page-number', '页码']
];

function field(labelText, control) {
  const label = document.createElement('label');
  label.className = 'editor-field';
  const span = document.createElement('span');
  span.textContent = labelText;
  label.append(span, control);
  return label;
}

function select(options, value) {
  const el = document.createElement('select');
  options.forEach(([key, label]) => { const option = document.createElement('option'); option.value = key; option.textContent = label; option.selected = key === value; el.append(option); });
  return el;
}

function update(control, event, block, key, onChange) {
  control.addEventListener(event, () => {
    block[key] = control.type === 'number' ? Number(control.value) : control.value;
    onChange(block);
  });
}

function archiveIssues(block) {
  const details = (block.issues || []).map(issue => {
    const quote = (block.original || '').slice(issue.start, issue.end);
    const parts = [`${issue.kind === 'unreadable' ? '模糊缺损' : '疑似错字'}「${quote}」`, issue.reason || '未说明依据'];
    if (issue.inferredText) parts.push(`推测：${issue.inferredText}`);
    if (issue.replacement) parts.push(`编辑：${issue.replacement}`);
    return parts.join('，');
  });
  if (details.length) {
    const archive = `原内容疑点范围因整段文字被修改而过期，已转存待复核：${details.join('；')}`;
    block.suggestion = block.suggestion ? `${block.suggestion}；${archive}` : archive;
    block.issues = [];
    block.uncertain = true;
  }
}

function issueProtectedTextarea(block, key, placeholder, onChange) {
  const textarea = document.createElement('textarea');
  textarea.rows = 3;
  textarea.value = block[key] || '';
  textarea.placeholder = placeholder;
  textarea.addEventListener('beforeinput', event => {
    if (!(block.issues || []).length) return;
    const confirmed = window.confirm('整段修改会使现有疑点范围失效。疑点依据、推测和已填替换会转存为块级待复核说明，确定继续吗？');
    if (!confirmed) { event.preventDefault(); return; }
    archiveIssues(block);
  });
  textarea.addEventListener('input', () => { block[key] = textarea.value; onChange(block); });
  return textarea;
}

export function issueReferences(blocks) {
  const refs = [];
  [...blocks].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)).forEach(block => {
    (block.issues || []).forEach(issue => refs.push({ block, issue, synthetic: false }));
    if (!(block.issues || []).length && block.suggestion) {
      refs.push({ block, issue: { id: `suggestion:${block.id}`, kind: 'suspected', reason: block.suggestion, resolved: false }, synthetic: true });
    }
  });
  return refs;
}

export function renderIssueWorkbench(container, blocks, options) {
  const refs = issueReferences(blocks);
  container.replaceChildren();
  container.hidden = !refs.length;
  if (!refs.length) return;
  const selectedIndex = Math.max(0, refs.findIndex(ref => ref.issue.id === options.selectedIssueId));
  const current = refs[selectedIndex];
  const unresolved = refs.filter(ref => !ref.synthetic && !ref.issue.resolved).length;

  const header = document.createElement('header');
  const heading = document.createElement('div');
  const title = document.createElement('strong'); title.textContent = '当前页内容疑点';
  const count = document.createElement('span'); count.textContent = `${selectedIndex + 1} / ${refs.length} · ${unresolved} 个未解决`;
  heading.append(title, count);
  const navigation = document.createElement('div');
  const previous = document.createElement('button'); previous.type = 'button'; previous.className = 'button quiet'; previous.textContent = '上一处'; previous.disabled = selectedIndex === 0;
  previous.addEventListener('click', () => options.onSelect(refs[selectedIndex - 1].block.id, refs[selectedIndex - 1].issue.id));
  const next = document.createElement('button'); next.type = 'button'; next.className = 'button quiet'; next.textContent = '下一处'; next.disabled = selectedIndex === refs.length - 1;
  next.addEventListener('click', () => options.onSelect(refs[selectedIndex + 1].block.id, refs[selectedIndex + 1].issue.id));
  navigation.append(previous, next); header.append(heading, navigation);

  const comparison = document.createElement('div'); comparison.className = 'issue-comparison';
  const source = document.createElement('section'); source.className = 'issue-source';
  const sourceTitle = document.createElement('strong'); sourceTitle.textContent = '原图与 OCR（只读）';
  const crop = document.createElement('div'); crop.className = 'issue-crop'; crop.setAttribute('role', 'img'); crop.setAttribute('aria-label', '当前疑点的原稿区域');
  const [x, y, w, h] = current.block.bbox || [0, 0, 1, 1];
  crop.style.backgroundImage = `url(${JSON.stringify(options.imageUrl)})`;
  crop.style.backgroundSize = `${100 / Math.max(.01, w)}% ${100 / Math.max(.01, h)}%`;
  crop.style.backgroundPosition = `${x >= 1 - w ? 100 : x / Math.max(.01, 1 - w) * 100}% ${y >= 1 - h ? 100 : y / Math.max(.01, 1 - h) * 100}%`;
  const pageWidth = Number(options.pageWidth || 1), pageHeight = Number(options.pageHeight || 1);
  crop.style.aspectRatio = String(Math.max(.6, Math.min(4, (w * pageWidth) / Math.max(.001, h * pageHeight))));
  const rawText = current.synthetic ? (current.block.original || '') : (current.block.original || '').slice(current.issue.start, current.issue.end);
  const raw = document.createElement('p');
  raw.textContent = rawText;
  source.append(sourceTitle, crop, raw);

  const edit = document.createElement('section'); edit.className = 'issue-edit';
  const editTitle = document.createElement('strong'); editTitle.textContent = current.synthetic ? '块级模型建议' : current.issue.kind === 'unreadable' ? '模糊缺损' : '普通疑点';
  const reason = document.createElement('p'); reason.className = 'issue-reason'; reason.textContent = current.issue.reason || '没有提供图像依据。';
  edit.append(editTitle, reason);
  if (!current.synthetic) {
    const inferredLabel = document.createElement('span'); inferredLabel.className = 'issue-label'; inferredLabel.textContent = '推测候选';
    const inferred = document.createElement('p'); inferred.className = 'issue-inferred'; inferred.textContent = current.issue.inferredText || '无推测候选';
    const inferredNote = document.createElement('p'); inferredNote.className = 'issue-reason';
    inferredNote.textContent = current.issue.inferredText
      ? '候选尚未确认；阅读正文以纯文字和细虚线标出差异，点击可查看原字依据。原始 OCR 保留在左侧。'
      : '没有候选时，原始 OCR 仍保留在左侧供核对。';
    const replacement = document.createElement('textarea'); replacement.rows = 2; replacement.maxLength = 1000;
    replacement.value = current.issue.resolved ? (current.issue.replacement ?? '') : (current.issue.inferredText || rawText);
    replacement.placeholder = '输入确认后的文字；可清空以删除伪识别片段';
    replacement.addEventListener('input', () => { current.issue.replacement = replacement.value; options.onDirty(); });
    edit.append(inferredLabel, inferred, inferredNote, field('当前编辑', replacement));
    const actions = document.createElement('div'); actions.className = 'issue-actions';
    if (current.issue.inferredText) {
      const adopt = document.createElement('button'); adopt.type = 'button'; adopt.className = 'button primary'; adopt.textContent = '采用推测并确认';
      adopt.addEventListener('click', () => options.onUpdate(current.block, current.issue, { replacement: current.issue.inferredText, resolved: true }));
      actions.append(adopt);
    }
    const resolve = document.createElement('button'); resolve.type = 'button'; resolve.className = 'button'; resolve.textContent = '标记解决';
    resolve.addEventListener('click', () => options.onUpdate(current.block, current.issue, { replacement: replacement.value, resolved: true }));
    actions.append(resolve);
    if (current.issue.resolved) {
      const restore = document.createElement('button'); restore.type = 'button'; restore.className = 'button quiet'; restore.textContent = '恢复未解决';
      restore.addEventListener('click', () => options.onUpdate(current.block, current.issue, { resolved: false }));
      actions.append(restore);
    }
    edit.append(actions);
  }
  comparison.append(source, edit);

  const list = document.createElement('ol'); list.className = 'issue-list';
  refs.forEach((ref, index) => {
    const li = document.createElement('li');
    const button = document.createElement('button'); button.type = 'button';
    button.className = `${index === selectedIndex ? 'selected ' : ''}${ref.issue.resolved ? 'resolved' : ''}`.trim();
    const label = ref.synthetic ? '块级建议' : ref.issue.kind === 'unreadable' ? '模糊缺损' : '疑似文字';
    button.textContent = `${ref.issue.resolved ? '已解决 · ' : ''}${label} · 块 ${ref.block.order ?? '—'}`;
    button.addEventListener('click', () => options.onSelect(ref.block.id, ref.issue.id));
    li.append(button); list.append(li);
  });
  container.append(header, comparison, list);
}

export function renderEditor(container, blocks, options) {
  const { selectedId, uncertainOnly, onSelect, onChange, onDelete } = options;
  container.replaceChildren();
  const visible = [...blocks].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)).filter(block => !uncertainOnly || block.uncertain || (block.issues || []).some(issue => !issue.resolved));
  if (!visible.length) {
    const empty = document.createElement('p');
    empty.className = 'side-empty';
    empty.textContent = uncertainOnly ? '本页没有标记为疑字的块。' : '本页尚无内容块，可在原稿上框选添加。';
    container.append(empty);
    return;
  }
  visible.forEach(block => {
    const item = document.createElement('section');
    item.className = `block-editor${block.id === selectedId ? ' selected' : ''}${block.uncertain ? ' uncertain' : ''}`;
    item.dataset.blockId = block.id;
    item.tabIndex = 0;
    item.addEventListener('focus', () => onSelect(block.id));
    item.addEventListener('click', event => { if (!event.target.closest('button,input,textarea,select')) onSelect(block.id); });

    const header = document.createElement('header');
    const identity = document.createElement('button');
    identity.className = 'block-identity'; identity.type = 'button';
    const confidence = block.confidence == null ? '' : ` · 识别参考 ${Math.round(Number(block.confidence) * 100)}%`;
    const source = block.source ? ` · ${block.source}` : '';
    identity.textContent = `${block.uncertain ? '疑字 · ' : ''}块 ${block.order ?? '—'}${confidence}${source}`;
    identity.addEventListener('click', () => onSelect(block.id));
    const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'delete-block'; remove.textContent = '删除此块';
    remove.addEventListener('click', () => onDelete(block));
    header.append(identity, remove);

    const type = select(BLOCK_TYPES, block.type);
    update(type, 'change', block, 'type', onChange);
    const order = document.createElement('input'); order.type = 'number'; order.min = '0'; order.value = block.order ?? 0;
    update(order, 'change', block, 'order', onChange);
    const writingMode = select([['horizontal-tb', '横排'], ['vertical-rl', '竖排']], block.writingMode || 'horizontal-tb');
    update(writingMode, 'change', block, 'writingMode', onChange);
    const row = document.createElement('div'); row.className = 'editor-grid'; row.append(field('类型', type), field('阅读顺序', order), field('排向', writingMode));

    const original = issueProtectedTextarea(block, 'original', '识别原文', onChange);
    const simplified = issueProtectedTextarea(block, 'simplified', '简体正文', onChange);

    const bbox = document.createElement('div'); bbox.className = 'bbox-grid';
    ['左', '上', '宽', '高'].forEach((name, index) => {
      const input = document.createElement('input'); input.type = 'number'; input.min = '0'; input.max = '100'; input.step = '.1'; input.value = ((block.bbox?.[index] ?? 0) * 100).toFixed(1);
      input.addEventListener('change', () => { block.bbox[index] = Math.max(0, Math.min(1, Number(input.value) / 100)); onChange(block); });
      bbox.append(field(`${name}%`, input));
    });
    item.append(header, row, field('识别原文', original), field('简体文字', simplified));
    if (block.suggestion) {
      const suggestion = document.createElement('div');
      suggestion.className = 'model-suggestion';
      const title = document.createElement('strong'); title.textContent = '模型疑点建议（不会自动替换）';
      const text = document.createElement('p'); text.textContent = block.suggestion;
      const hint = document.createElement('small'); hint.textContent = '请对照原稿后修改上方文字；复核说明不会当作正文写入。';
      suggestion.append(title, text, hint); item.append(suggestion);
    }
    item.append(bbox);
    container.append(item);
  });
}

export function createOverlay(stage, blocks, selectedId, handlers) {
  stage.querySelectorAll('.edit-overlay').forEach(node => node.remove());
  blocks.forEach(block => {
    const [x, y, w, h] = block.bbox || [0, 0, .1, .1];
    const box = document.createElement('button');
    box.type = 'button'; box.className = `edit-overlay${block.id === selectedId ? ' selected' : ''}${block.uncertain ? ' uncertain' : ''}`;
    box.dataset.blockId = block.id;
    box.setAttribute('aria-label', `选择第 ${block.order ?? 0} 个${block.type === 'figure' ? '插图' : '内容'}块`);
    Object.assign(box.style, { left: `${x * 100}%`, top: `${y * 100}%`, width: `${w * 100}%`, height: `${h * 100}%` });
    box.addEventListener('click', event => { event.stopPropagation(); handlers.onSelect(block.id); });
    box.addEventListener('pointerdown', event => {
      if (!handlers.canMove()) return;
      event.preventDefault(); event.stopPropagation(); box.setPointerCapture(event.pointerId);
      const rect = stage.getBoundingClientRect(); const start = { x: event.clientX, y: event.clientY, bx: block.bbox[0], by: block.bbox[1] };
      const move = moveEvent => { block.bbox[0] = Math.max(0, Math.min(1 - block.bbox[2], start.bx + (moveEvent.clientX - start.x) / rect.width)); block.bbox[1] = Math.max(0, Math.min(1 - block.bbox[3], start.by + (moveEvent.clientY - start.y) / rect.height)); Object.assign(box.style, { left: `${block.bbox[0] * 100}%`, top: `${block.bbox[1] * 100}%` }); handlers.onMove(block, false); };
      const up = () => { box.removeEventListener('pointermove', move); handlers.onMove(block, true); };
      box.addEventListener('pointermove', move); box.addEventListener('pointerup', up, { once: true }); box.addEventListener('pointercancel', up, { once: true });
    });
    stage.append(box);
  });
}

export function enableDrawing(stage, type, onDraw) {
  stage.classList.add('drawing');
  const abort = new AbortController();
  const cancel = () => { stage.classList.remove('drawing'); abort.abort(); };
  stage.addEventListener('pointerdown', event => {
    if (event.target.closest('.edit-overlay')) return;
    event.preventDefault();
    const rect = stage.getBoundingClientRect();
    const startX = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
    const startY = Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height));
    const preview = document.createElement('div'); preview.className = 'draw-preview'; stage.append(preview);
    const move = moveEvent => {
      const endX = Math.max(0, Math.min(1, (moveEvent.clientX - rect.left) / rect.width));
      const endY = Math.max(0, Math.min(1, (moveEvent.clientY - rect.top) / rect.height));
      Object.assign(preview.style, { left: `${Math.min(startX, endX) * 100}%`, top: `${Math.min(startY, endY) * 100}%`, width: `${Math.abs(endX - startX) * 100}%`, height: `${Math.abs(endY - startY) * 100}%` });
    };
    const up = upEvent => {
      const endX = Math.max(0, Math.min(1, (upEvent.clientX - rect.left) / rect.width));
      const endY = Math.max(0, Math.min(1, (upEvent.clientY - rect.top) / rect.height));
      preview.remove(); cancel();
      const bbox = [Math.min(startX, endX), Math.min(startY, endY), Math.abs(endX - startX), Math.abs(endY - startY)];
      if (bbox[2] > .01 && bbox[3] > .01) onDraw(type, bbox);
    };
    stage.addEventListener('pointermove', move, { signal: abort.signal });
    stage.addEventListener('pointerup', up, { once: true, signal: abort.signal });
  }, { once: true, signal: abort.signal });
  document.addEventListener('keydown', event => { if (event.key === 'Escape') cancel(); }, { signal: abort.signal });
  return cancel;
}
