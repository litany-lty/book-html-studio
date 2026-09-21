import { api } from './api.js';

// J08：在线候选面板与阅读状态。独立请求作用域（decisionFetchController/decisionInFlight），
// 绝不复用整页保存的 saveInFlight；切书/切页/重进同页换 epoch，迟到响应只能丢弃。
// 新建议不改 Page revision/status，不覆盖 OCR job.json，不写入 Block.issues。

export const REASON_LABELS = {
  RECOMMEND: '首选候选，尚未确认',
  KEEP_CURRENT: '建议维持当前转录，尚未确认',
  CANDIDATES_ONLY: '仅列候选，未达推荐标准',
  NEED_MORE_EVIDENCE: '证据不足，需要更多原图证据或人工核对',
  NONE_SUPPORTED: '候选均不受支持，保留原始转录',
  HUMAN_REQUIRED: '必须人工核对',
  UNAVAILABLE: '辅助暂不可用',
  STALE: '建议已过期',
  CANCELLED: '已取消',
  SEMANTIC_ONLY: '仅语义推测，无转录证据支持',
  SOURCE_CONFLICT: '来源冲突，需人工裁定',
  UNCALIBRATED: '模型比较首选（未验证），非正式推荐',
  LOW_SEPARATION: '候选区分度不足，仅列候选',
  EVIDENCE_GAP: '证据缺口较大，需补充原图证据',
  LOCATION_AMBIGUOUS: '定位未唯一确定，需重新定位',
  NO_CANDIDATE: '没有可用候选，转人工',
  INPUT_TOO_LARGE: '上下文超限，转人工',
  UNKNOWN_CANDIDATE: '未知候选，拒绝消费',
};

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED']);

const STAGE_LABELS = {
  LOCATING: '定位原图中',
  COLLECTING: '收集候选中',
  COMPARING: '比较候选中',
  PERSISTING: '保存结果中',
  DONE: '完成',
  CANCELLING: '取消中',
};

export function reasonLabel(code) {
  if (!code) return '';
  if (REASON_LABELS[code]) return REASON_LABELS[code];
  if (code.startsWith('UNAVAILABLE_')) return `辅助暂不可用（${code.slice('UNAVAILABLE_'.length)}）`;
  if (code.startsWith('HARD_RISK_')) return `高风险项，必须人工核对（${code.slice('HARD_RISK_'.length)}）`;
  if (code.startsWith('JEV_')) return `决策请求失败（${code.slice('JEV_'.length).toLowerCase()}）`;
  return code;
}

function stageLabel(stage) {
  return STAGE_LABELS[stage] || '处理中';
}

export function createDecisionPanel(deps) {
  const { getSession, hasDirty, onAccepted, onRecommendation, showError } = deps;
  let disposed = false;
  let fetchController = null;
  let pollTimer = 0;
  let current = null; // {bookId, page, epoch, blockId, issueId, basis, revision}

  function session() {
    return getSession();
  }

  function sameScope(snapshot) {
    const s = session();
    return s && snapshot
      && s.bookId === snapshot.bookId && s.page === snapshot.page && s.epoch === snapshot.epoch;
  }

  function abortFlight() {
    fetchController?.abort();
    fetchController = null;
    if (pollTimer) { window.clearTimeout(pollTimer); pollTimer = 0; }
  }

  function dispose() {
    disposed = true;
    abortFlight();
  }

  /** 草稿门禁轻量同步：只切换需干净态按钮的禁用与提示，不重绘面板（保轮询与焦点）。 */
  function syncDraftGuard(dirty) {
    const titles = { compare: '零新增视觉调用', vision: '将使用实际能力及预算条件（默认最多一次）', accept: '' };
    document.querySelectorAll('[data-decision-panel] [data-needs-clean]').forEach(button => {
      const action = button.dataset.action;
      if (action !== 'compare' && action !== 'vision' && action !== 'accept') return;
      button.disabled = Boolean(dirty);
      button.title = dirty ? '有未保存草稿，请先保存' : (titles[action] || '');
    });
  }

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function uuid() {
    return window.crypto?.randomUUID ? window.crypto.randomUUID()
      : `op-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  async function loadBasis(blockId, issueId) {
    const s = session();
    const snapshot = { ...s };
    fetchController?.abort();
    fetchController = new AbortController();
    const data = await api.decisions(s.bookId, s.page, issueId, fetchController.signal);
    if (!sameScope(snapshot)) throw Object.assign(new Error('已切换页面'), { name: 'StaleRequest' });
    return { basis: data.basis, current: data.current, history: data.history || [] };
  }

  async function createJob(allowFreshVision) {
    if (!current) return;
    if (hasDirty()) {
      showError(new Error('有未保存草稿，请先保存后再评估；评估只针对已保存内容。'));
      return;
    }
    const s = session();
    const snapshot = { ...s, blockId: current.blockId, issueId: current.issueId };
    const scope = { bookId: s.bookId, page: s.page, epoch: s.epoch };
    fetchController?.abort();
    fetchController = new AbortController();
    const signal = fetchController.signal;
    renderStatus(`正在创建评估任务…`);
    try {
      const job = await api.decisionJobsCreate(s.bookId, s.page, current.issueId, {
        clientOperationId: uuid(),
        blockId: current.blockId,
        expectedPageRevision: current.revision,
        issueBasisHash: current.basis,
        allowFreshVision,
      }, signal);
      if (!sameScope(scope)) return;
      pollJob(job.jobId);
    } catch (error) {
      if (error?.name === 'StaleRequest') return;
      if (!sameScope(scope)) return;
      renderError(error);
    }
  }

  async function pollJob(jobId) {
    if (pollTimer) { window.clearTimeout(pollTimer); pollTimer = 0; }
    const s = session();
    const scope = { ...s };
    const tick = async () => {
      if (disposed || !sameScope(scope)) return;
      try {
        const job = await api.decisionJob(s.bookId, jobId, fetchController?.signal);
        if (!sameScope(scope)) return;
        if (TERMINAL.has(job.jobState)) {
          await refresh();
          return;
        }
        renderStatus(`${stageLabel(job.progressStage)}…`);
        pollTimer = window.setTimeout(tick, 800);
      } catch (error) {
        if (error?.name === 'StaleRequest') return;
        if (!sameScope(scope)) return;
        renderError(error);
      }
    };
    await tick();
  }

  async function cancelJob(jobId, stateVersion) {
    const s = session();
    const scope = { ...s };
    try {
      // 明确取消该决策作业（绑定 jobId 与状态版本）；关闭面板不等同取消。
      await api.decisionJobCancel(s.bookId, jobId, { expectedStateVersion: stateVersion });
      if (!sameScope(scope)) return;
      await refresh();
    } catch (error) {
      if (!sameScope(scope)) return;
      renderError(error);
    }
  }

  async function acceptDecision(decisionId, candidateId, basis, revision, operationId) {
    const s = session();
    const scope = { ...s };
    if (hasDirty()) {
      showError(new Error('有未保存草稿，请先保存后再确认；确认只针对已保存内容。'));
      return;
    }
    renderStatus('正在提交确认…');
    try {
      const result = await api.decisionAccept(s.bookId, s.page, current.issueId, decisionId, {
        clientOperationId: operationId,
        blockId: current.blockId,
        expectedPageRevision: revision,
        issueBasisHash: basis,
        candidateSetHash: current.candidateSetHash,
        candidateId,
        userAttestedSourceCheck: true,
      });
      if (!sameScope(scope)) return;
      onAccepted(result);
    } catch (error) {
      // 超时/断网读回核实：按 operationId 而不是文本碰巧相同判断成功
      if (error?.name === 'TimeoutError' || error?.name === 'TypeError') {
        try {
          const remote = await api.page(s.bookId, s.page);
          const confirmed = (remote.blocks || []).flatMap(b => (b.issues || []).map(i => ({ b, i })))
            .find(({ i }) => i.id === current.issueId && i.resolution?.clientOperationId === operationId);
          if (confirmed && sameScope(scope)) {
            onAccepted({ pageRevision: remote.revision, idempotent: true, resolved: true });
            return;
          }
        } catch (_) { /* 读回失败则报原错 */ }
      }
      if (!sameScope(scope)) return;
      renderError(error);
    }
  }

  function renderStatus(text) {
    const status = document.querySelector('[data-decision-status]');
    if (status) { status.hidden = false; status.textContent = text; }
    const error = document.querySelector('[data-decision-error]');
    if (error) error.hidden = true;
  }

  function renderError(error) {
    const node = document.querySelector('[data-decision-error]');
    if (node) { node.hidden = false; node.textContent = error?.message || '请求失败'; }
    const status = document.querySelector('[data-decision-status]');
    if (status) status.hidden = true;
  }

  function candidateLabel(candidate) {
    const kind = { NATIVE_TEXT: '原生文字', PRIMARY_OCR: '主识别', CROP_OCR: '局部复识别', VISION_TRANSCRIPTION: '视觉转录', SEMANTIC_INFERENCE: '语义推测', LEGACY_INFERENCE: '旧推测', HUMAN_INPUT: '人工输入' }[candidate.sourceKind] || candidate.sourceKind;
    const alignment = { EXACT: '精确对齐', EXPANDED_SPAN: '需扩大范围', AMBIGUOUS: '对齐不定', UNALIGNED: '未对齐' }[candidate.alignment] || '';
    return `${kind} · ${alignment}`;
  }

  async function refresh() {
    const host = document.querySelector('[data-decision-panel]');
    if (!host || !current) return;
    const s = session();
    const scope = { ...s, blockId: current.blockId, issueId: current.issueId };
    try {
      const { basis, current: decision, history } = await loadBasis(current.blockId, current.issueId);
      if (!sameScope(scope)) return;
      current.basis = basis.issueBasisHash;
      current.revision = basis.pageRevision;
      current.candidateSetHash = decision?.candidateSetHash || null;
      renderPanel(host, { basis, decision, history });
      if (decision?.recommendedCandidateId && decision?.candidates) {
        const hit = decision.candidates.find(c => c.candidateId === decision.recommendedCandidateId);
        if (hit) onRecommendation(current.issueId, { text: hit.originalText || hit.displayText, candidateId: hit.candidateId, decisionId: decision.decisionId, verdict: decision.verdict });
      }
    } catch (error) {
      if (error?.name === 'StaleRequest') return;
      if (!sameScope(scope)) return;
      renderError(error);
    }
  }

  function renderPanel(host, { basis, decision, history }) {
    host.replaceChildren();
    const title = el('h3', 'decision-title', '候选比较（辅助阅读）');
    host.append(title);
    const status = el('p', 'decision-status'); status.dataset.decisionStatus = ''; status.hidden = true;
    const error = el('p', 'decision-error'); error.dataset.decisionError = ''; error.hidden = true;
    host.append(status, error);
    const meta = el('p', 'decision-meta', `基线版本 ${basis.pageRevision} · 映射 ${basis.mappingVersion}`);
    host.append(meta);
    if (!decision) {
      const empty = el('p', 'decision-empty', '暂无建议。先比较现有候选（零新增视觉调用），或补充一次原图复识别。');
      host.append(empty);
    } else {
      const verdict = el('p', 'decision-verdict',
        `结论：${reasonLabel(decision.verdict)}${decision.verdict && decision.verdict !== 'RECOMMEND' && decision.verdict !== 'KEEP_CURRENT' ? '（未确认）' : '（未确认，需对照原图）'}`);
      host.append(verdict);
      const list = el('ol', 'decision-candidates');
      const radios = [];
      (decision.candidates || []).forEach(candidate => {
        const li = el('li', 'decision-candidate');
        const label = el('label', '');
        const radio = document.createElement('input');
        radio.type = 'radio'; radio.name = `decision-${decision.decisionId}`;
        radio.value = candidate.candidateId;
        radio.checked = candidate.candidateId === decision.recommendedCandidateId;
        const text = el('span', 'decision-candidate-text', candidate.originalText || candidate.displayText || '（空）');
        const kind = el('span', 'decision-candidate-kind', candidateLabel(candidate));
        label.append(radio, text, kind);
        li.append(label);
        list.append(li);
        radios.push(radio);
      });
      host.append(list);
      const reasons = el('p', 'decision-reasons',
        `限制：${(decision.reasonCodes || []).map(reasonLabel).join('；') || '无'}`);
      host.append(reasons);
      const checkRow = el('label', 'decision-attest');
      const check = document.createElement('input');
      check.type = 'checkbox';
      checkRow.append(check, document.createTextNode('我已对照原图，确认此处文字'));
      host.append(checkRow);
      const acceptRow = el('div', 'decision-actions');
      const acceptButton = el('button', 'button primary decision-action', '对照原图并确认');
      acceptButton.dataset.needsClean = '1'; acceptButton.dataset.action = 'accept';
      acceptButton.addEventListener('click', () => {
        const picked = radios.find(r => r.checked)?.value || decision.recommendedCandidateId;
        if (!picked) { showError(new Error('请先选择一个候选。')); return; }
        if (!check.checked) { showError(new Error('请先勾选“已对照原图”。没有原图时不能显示已对照，只能普通人工输入。')); return; }
        acceptDecision(decision.decisionId, picked, current.basis, current.revision, uuid());
      });
      const keepButton = el('button', 'button decision-action', '保留待核对');
      keepButton.addEventListener('click', () => {
        const note = el('p', 'decision-note', '已保留待核对：这不是失败，待核对计数不变。');
        host.append(note);
      });
      acceptRow.append(acceptButton, keepButton);
      host.append(acceptRow);
    }
    const actions = el('div', 'decision-actions');
    const compareButton = el('button', 'button decision-action', '比较现有候选');
    compareButton.dataset.needsClean = '1'; compareButton.dataset.action = 'compare';
    compareButton.disabled = hasDirty();
    compareButton.title = hasDirty() ? '有未保存草稿，请先保存' : '零新增视觉调用';
    compareButton.addEventListener('click', () => createJob(false));
    const visionButton = el('button', 'button decision-action', '补充一次原图复识别');
    visionButton.dataset.needsClean = '1'; visionButton.dataset.action = 'vision';
    visionButton.disabled = hasDirty();
    visionButton.title = hasDirty() ? '有未保存草稿，请先保存' : '将使用实际能力及预算条件（默认最多一次）';
    visionButton.addEventListener('click', () => createJob(true));
    actions.append(compareButton, visionButton);
    if (decision?.jobId && !['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(decision.jobState)) {
      const cancelButton = el('button', 'button quiet decision-action', '取消本次评估');
      cancelButton.addEventListener('click', () => cancelJob(decision.jobId, decision.jobStateVersion ?? 0));
      actions.append(cancelButton);
    }
    host.append(actions);
    if (history?.length) {
      const details = el('details', 'decision-history');
      const summary = el('summary', '', `历史建议（${history.length}）`);
      details.append(summary);
      history.forEach(item => {
        const row = el('p', 'decision-history-row',
          `${item.verdict || item.jobState} · 适用性 ${item.applicability || '未知'} · ${(item.reasonCodes || []).map(reasonLabel).join('；')}`);
        details.append(row);
      });
      host.append(details);
    }
  }

  function render(host, block, issue) {
    abortFlight();
    const s = session();
    current = { bookId: s.bookId, page: s.page, epoch: s.epoch, blockId: block.id, issueId: issue.id, basis: null, revision: null };
    host.replaceChildren();
    host.dataset.decisionPanel = '';
    const loading = el('p', 'decision-status', '正在读取建议基线…');
    host.append(loading);
    refresh();
  }

  return { render, refresh, dispose, syncDraftGuard };
}
