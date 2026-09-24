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
const INTERACTIVE_MODES = new Set(['SHADOW', 'ASSIST']);

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
  let pollController = null;
  let pollTimer = 0;
  let panelEpoch = 0;
  let acceptInFlight = false;
  let inFlightOperationId = null;
  let current = null; // {bookId, page, epoch, panelEpoch, blockId, issueId, basis, revision, candidateSetHash}

  function session() {
    return getSession();
  }

  function sameScope(snapshot) {
    const s = session();
    return Boolean(s && snapshot && current
      && s.bookId === snapshot.bookId
      && s.page === snapshot.page
      && s.epoch === snapshot.epoch
      && snapshot.panelEpoch === current.panelEpoch
      && snapshot.blockId === current.blockId
      && snapshot.issueId === current.issueId);
  }

  function abortFlight() {
    fetchController?.abort();
    fetchController = null;
    pollController?.abort();
    pollController = null;
    if (pollTimer) { window.clearTimeout(pollTimer); pollTimer = 0; }
  }

  function dispose() {
    disposed = true;
    abortFlight();
  }

  /** 草稿门禁轻量同步：只切换需干净态按钮的禁用与提示，不重绘面板（保轮询与焦点）。 */
  function syncDraftGuard(dirty) {
    const titles = { compare: '仅比较已有候选，不补做视觉识别', accept: '' };
    document.querySelectorAll('[data-decision-panel] [data-needs-clean]').forEach(button => {
      const action = button.dataset.action;
      if (action !== 'compare' && action !== 'accept') return;
      button.disabled = Boolean(dirty) || !INTERACTIVE_MODES.has(current?.mode)
        || (action === 'accept' && (acceptInFlight || button.dataset.canAdmit !== 'true'));
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
    if (typeof window !== 'undefined' && window.crypto?.randomUUID) {
      return window.crypto.randomUUID();
    }
    if (typeof window !== 'undefined' && typeof window.crypto?.getRandomValues === 'function') {
      const bytes = new Uint8Array(16);
      window.crypto.getRandomValues(bytes);
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
      const r = (Math.random() * 16) | 0;
      const v = c === 'x' ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }

  async function loadBasis(scope) {
    fetchController?.abort();
    fetchController = new AbortController();
    const data = await api.decisions(scope.bookId, scope.page, scope.issueId, fetchController.signal);
    if (!sameScope(scope)) throw Object.assign(new Error('已切换疑点或页面'), { name: 'StaleRequest' });
    return { basis: data.basis, current: data.current, history: data.history || [],
      decisionMode: data.decisionMode || null, configuredModel: data.configuredModel || null };
  }

  async function createJob() {
    if (!current) return;
    if (!INTERACTIVE_MODES.has(current.mode)) return;
    if (hasDirty()) {
      showError(new Error('有未保存草稿，请先保存后再评估；评估只针对已保存内容。'));
      return;
    }
    const scope = { ...current };
    fetchController?.abort();
    fetchController = new AbortController();
    const signal = fetchController.signal;
    renderStatus(`正在创建评估任务…`);
    try {
      const job = await api.decisionJobsCreate(scope.bookId, scope.page, scope.issueId, {
        clientOperationId: uuid(),
        blockId: scope.blockId,
        expectedPageRevision: scope.revision,
        issueBasisHash: scope.basis,
        allowFreshVision: false,
      }, signal);
      if (!sameScope(scope)) return;
      pollJob(job.jobId, scope);
    } catch (error) {
      if (error?.name === 'StaleRequest') return;
      if (!sameScope(scope)) return;
      renderError(error);
    }
  }

  async function pollJob(jobId, scope) {
    if (pollTimer) { window.clearTimeout(pollTimer); pollTimer = 0; }
    // JR-02：轮询用独立 AbortController，不与 loadBasis 共享；瞬时失败重约，
    // Abort 中止只是减少浪费，不能当正确性保证（迟到响应一律 sameScope 丢弃）。
    const controller = new AbortController();
    pollController = controller;
    let failures = 0;
    const tick = async () => {
      if (disposed || !sameScope(scope)) return;
      try {
        const job = await api.decisionJob(scope.bookId, jobId, controller.signal);
        if (!sameScope(scope)) return;
        if (TERMINAL.has(job.jobState)) {
          await refresh();
          return;
        }
        failures = 0;
        renderStatus(`${stageLabel(job.progressStage)}…`);
        pollTimer = window.setTimeout(tick, 800);
      } catch (error) {
        if (error?.name === 'StaleRequest') return;
        if (!sameScope(scope)) return;
        failures++;
        if (failures < 30) {
          pollTimer = window.setTimeout(tick, 800);
          return;
        }
        renderError(error);
      }
    };
    // 旧链路停止轮询，避免双重 tick
    fetchController?.abort();
    await tick();
  }

  async function cancelJob(jobId, stateVersion, scope) {
    try {
      // 明确取消该决策作业（绑定 jobId 与状态版本）；关闭面板不等同取消。
      await api.decisionJobCancel(scope.bookId, jobId, { expectedStateVersion: stateVersion });
      if (!sameScope(scope)) return;
      await refresh();
    } catch (error) {
      if (!sameScope(scope)) return;
      renderError(error);
    }
  }

  async function acceptDecision(decisionId, candidateId, basis, revision, scope) {
    if (acceptInFlight) return;
    if (hasDirty()) {
      showError(new Error('有未保存草稿，请先保存后再确认；确认只针对已保存内容。'));
      return;
    }
    const operationId = inFlightOperationId || uuid();
    inFlightOperationId = operationId;
    acceptInFlight = true;
    renderStatus('正在提交确认…');
    syncDraftGuard(true);
    try {
      const result = await api.decisionAccept(scope.bookId, scope.page, scope.issueId, decisionId, {
        clientOperationId: operationId,
        blockId: scope.blockId,
        expectedPageRevision: revision,
        issueBasisHash: basis,
        candidateSetHash: scope.candidateSetHash,
        candidateId,
        userAttestedSourceCheck: true,
      });
      inFlightOperationId = null;
      acceptInFlight = false;
      if (!sameScope(scope)) return;
      onAccepted(result);
    } catch (error) {
      acceptInFlight = false;
      // 超时/断网读回核实：按 target+operationId+candidate/setHash 而不是文本碰巧相同判断成功
      if (error?.name === 'TimeoutError' || error?.name === 'TypeError') {
        try {
          const remote = await api.page(scope.bookId, scope.page);
          const confirmed = (remote.blocks || []).flatMap(b => (b.issues || []).map(i => ({ b, i })))
            .find(({ b, i }) => i.id === scope.issueId && b.id === scope.blockId
              && i.resolution?.clientOperationId === operationId
              && i.resolution?.candidateId === candidateId
              && (scope.candidateSetHash == null || i.resolution?.candidateSetHash === scope.candidateSetHash));
          if (confirmed && sameScope(scope)) {
            inFlightOperationId = null;
            onAccepted({ pageRevision: remote.revision, idempotent: true, resolved: true });
            return;
          }
        } catch (_) { /* 读回失败则保留 operationId 供重试 */ }
      } else {
        inFlightOperationId = null;
      }
      if (!sameScope(scope)) return;
      renderError(error);
      syncDraftGuard(hasDirty());
    }
  }

  function renderStatus(text) {
    const status = document.querySelector('[data-decision-status]');
    if (status) {
      status.hidden = false;
      status.classList.add('decision-pending');
      status.setAttribute('role', 'status');
      status.setAttribute('aria-live', 'polite');
      status.textContent = text;
      const skeleton = el('span', 'decision-loading-lines');
      skeleton.setAttribute('aria-hidden', 'true');
      status.append(skeleton);
    }
    const error = document.querySelector('[data-decision-error]');
    if (error) error.hidden = true;
  }

  function renderError(error) {
    const node = document.querySelector('[data-decision-error]');
    if (node) { node.hidden = false; node.textContent = error?.message || '请求失败'; }
    const status = document.querySelector('[data-decision-status]');
    if (status) { status.hidden = true; status.classList.remove('decision-pending'); status.replaceChildren(); }
  }

  function candidateLabel(candidate) {
    const kind = { NATIVE_TEXT: '原生文字', PRIMARY_OCR: '主识别', CROP_OCR: '局部复识别', VISION_TRANSCRIPTION: '视觉转录', SEMANTIC_INFERENCE: '语义推测', LEGACY_INFERENCE: '旧推测', HUMAN_INPUT: '人工输入' }[candidate.sourceKind] || candidate.sourceKind;
    const alignment = { EXACT: '精确对齐', EXPANDED_SPAN: '需扩大范围', AMBIGUOUS: '对齐不定', UNALIGNED: '未对齐' }[candidate.alignment] || '';
    return `${kind} · ${alignment}`;
  }

  async function refresh() {
    const host = document.querySelector('[data-decision-panel]');
    if (!host || !current) return;
    const scope = { ...current };
    try {
      const { basis, current: decision, history, decisionMode, configuredModel } = await loadBasis(scope);
      if (!sameScope(scope)) return;
      current.basis = basis.issueBasisHash;
      current.revision = basis.pageRevision;
      current.candidateSetHash = decision?.candidateSetHash || null;
      current.mode = decisionMode || null;
      renderPanel(host, { basis, decision, history, decisionMode, configuredModel }, scope);
      // JR-08-T06：仅正式推荐（admittedRecommendationId + RECOMMEND/KEEP_CURRENT）才触发推荐；
      // 模型偏好（modelPreferred）绝不当正式推荐。
      const admittedId = decision?.admittedRecommendationId || null;
      const verdict = decision?.verdict || null;
      if (decisionMode === 'ASSIST' && admittedId && (verdict === 'RECOMMEND' || verdict === 'KEEP_CURRENT') && decision?.candidates) {
        const hit = decision.candidates.find(c => c.candidateId === admittedId);
        if (hit && sameScope(scope)) {
          onRecommendation(scope.issueId, {
            text: hit.originalText || hit.displayText,
            candidateId: hit.candidateId,
            decisionId: decision.decisionId,
            verdict: decision.verdict
          });
        }
      }
    } catch (error) {
      if (error?.name === 'StaleRequest') return;
      if (!sameScope(scope)) return;
      renderError(error);
    }
  }

  function renderPanel(host, { basis, decision, history, decisionMode, configuredModel }, scope) {
    if (!scope || !sameScope(scope)) return;
    host.replaceChildren();
    host.classList.toggle('decision-inactive', !INTERACTIVE_MODES.has(decisionMode));
    const title = el('h3', 'decision-title', '候选比较（辅助阅读）');
    host.append(title);
    const status = el('p', 'decision-status'); status.dataset.decisionStatus = ''; status.hidden = true; status.setAttribute('aria-live', 'polite');
    const error = el('p', 'decision-error'); error.dataset.decisionError = ''; error.hidden = true;
    host.append(status, error);
    const modelName = decision?.model || configuredModel || '';
    const metaText = `基线版本 ${basis.pageRevision} · 映射 ${basis.mappingVersion}${modelName ? ` · 模型: ${modelName}` : ''}`;
    const meta = el('p', 'decision-meta', metaText);
    host.append(meta);
    if (!decision) {
      const empty = el('div', 'decision-empty');
      if (!INTERACTIVE_MODES.has(decisionMode)) {
        empty.append(
          el('p', 'decision-empty-text', '候选比较已关闭。可继续对照原图手工校对；既有历史建议仍可查看。'),
          (() => {
            const btn = el('button', 'button primary decision-open-settings', '⚙️ 前往工具配置开启 JEV');
            btn.type = 'button';
            btn.addEventListener('click', () => {
              document.querySelector('#settings-open')?.click();
              setTimeout(() => {
                document.querySelector('#settings-jev-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
              }, 120);
            });
            return btn;
          })()
        );
      } else {
        empty.append(el('p', 'decision-empty-text', '暂无建议。可主动比较已有候选；没有足够证据时请对照原图人工校对。'));
      }
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
        // JR-08-T06：默认选中仅用正式推荐 admittedRecommendationId；模型偏好不预选
        radio.checked = candidate.candidateId === (decision.admittedRecommendationId || null);
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
      // JR-08-T01：SHADOW/OFF 不开放 JEV 接受入口；仅 ASSIST + 正式推荐才可确认
      const canAdmit = decisionMode === 'ASSIST'
        && (decision.verdict === 'RECOMMEND' || decision.verdict === 'KEEP_CURRENT')
        && Boolean(decision.admittedRecommendationId);
      if (!canAdmit) {
        const note = el('p', 'decision-note',
          decisionMode && decisionMode !== 'ASSIST'
            ? `当前为${decisionMode}模式：仅展示候选，不开放确认入口。`
            : '当前结论未形成正式推荐：可对照原图人工输入，不可一键确认。');
        host.append(note);
      }
      const checkRow = el('label', 'decision-attest');
      const check = document.createElement('input');
      check.type = 'checkbox';
      checkRow.append(check, document.createTextNode('我已对照原图，确认此处文字'));
      if (canAdmit) host.append(checkRow);
      const acceptRow = el('div', 'decision-actions');
      const acceptButton = el('button', 'button primary decision-action', '对照原图并确认');
      acceptButton.dataset.needsClean = '1'; acceptButton.dataset.action = 'accept';
      acceptButton.dataset.canAdmit = String(canAdmit);
      acceptButton.disabled = !canAdmit;
      acceptButton.title = canAdmit ? '' : '未形成正式推荐或非 ASSIST 模式，不能一键确认';
      acceptButton.addEventListener('click', () => {
        const picked = radios.find(r => r.checked)?.value || decision.admittedRecommendationId;
        if (!picked) { showError(new Error('请先选择一个候选。')); return; }
        if (!check.checked) { showError(new Error('请先勾选“已对照原图”。没有原图时不能显示已对照，只能普通人工输入。')); return; }
        acceptDecision(decision.decisionId, picked, scope.basis, scope.revision, scope);
      });
      const keepButton = el('button', 'button decision-action', '保留待核对');
      keepButton.addEventListener('click', () => {
        const note = el('p', 'decision-note', '已保留待核对：这不是失败，待核对计数不变。');
        host.append(note);
      });
      if (canAdmit) acceptRow.append(acceptButton);
      acceptRow.append(keepButton);
      host.append(acceptRow);
    }
    const actions = el('div', 'decision-actions');
    const compareButton = el('button', 'button primary decision-action', '比较现有候选');
    compareButton.dataset.needsClean = '1'; compareButton.dataset.action = 'compare';
    compareButton.disabled = hasDirty();
    compareButton.title = hasDirty() ? '有未保存草稿，请先保存' : '仅比较已有候选，不补做视觉识别';
    compareButton.addEventListener('click', () => createJob());
    if (INTERACTIVE_MODES.has(decisionMode)) {
      actions.append(compareButton);
      host.append(el('p', 'decision-gate-note', '点击上方“比较现有候选”开启模型评估；校准、预算与外发许可等门槛都通过后执行评估。'));
    }
    if (decision?.jobId && !['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(decision.jobState)) {
      const cancelButton = el('button', 'button quiet decision-action', '取消本次评估');
      cancelButton.addEventListener('click', () => cancelJob(decision.jobId, decision.jobStateVersion ?? 0, scope));
      actions.append(cancelButton);
    }
    if (actions.children.length) host.append(actions);
    if (history?.length) {
      const details = el('details', 'decision-history');
      const summary = el('summary', '', `历史建议（${history.length}）`);
      details.append(summary);
      history.forEach(item => {
        const itemModel = item.model ? `模型: ${item.model} · ` : '';
        const row = el('p', 'decision-history-row',
          `${itemModel}${item.verdict || item.jobState} · 适用性 ${item.applicability || '未知'} · ${(item.reasonCodes || []).map(reasonLabel).join('；')}`);
        details.append(row);
      });
      host.append(details);
    }
  }

  function render(host, block, issue) {
    abortFlight();
    disposed = false;
    panelEpoch++;
    inFlightOperationId = null;
    acceptInFlight = false;
    const s = session();
    current = {
      bookId: s.bookId,
      page: s.page,
      epoch: s.epoch,
      panelEpoch,
      blockId: block.id,
      issueId: issue.id,
      basis: null,
      revision: null,
      candidateSetHash: null,
      mode: null,
    };
    host.replaceChildren();
    host.dataset.decisionPanel = '';
    const loading = el('p', 'decision-status decision-loading', '正在读取建议基线…');
    loading.setAttribute('role', 'status');
    loading.setAttribute('aria-live', 'polite');
    const skeleton = el('span', 'decision-loading-lines');
    skeleton.setAttribute('aria-hidden', 'true');
    loading.append(skeleton);
    host.append(loading);
    refresh();
  }

  return { render, refresh, dispose, syncDraftGuard };
}
