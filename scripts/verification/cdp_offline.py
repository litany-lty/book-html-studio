import os
#!/usr/bin/env python3
"""A1-03 离线事务 E2E（file:// + 真实 IndexedDB + 双 tab）：P01/P02/P08/备份导出。"""
import json
import sys
import tempfile
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from cdpdrive import Driver

FILE = os.environ.get('A1_FILE', "file:///tmp/a1b-evidence/offline2/index.html")
OUT = {}
FAILS = []


def check(name, cond, detail=""):
    OUT[name] = {"pass": bool(cond), "detail": detail}
    print(("PASS " if cond else "FAIL ") + name + (" | " + str(detail) if detail else ""))
    if not cond:
        FAILS.append(name)


def wait_for(d, sess, expr, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if d.eval(sess, expr):
                return True
        except Exception:
            pass
        time.sleep(0.4)
    return False


def main():
    d = Driver(9341, profile=os.environ.get("A1_PROFILE", tempfile.mkdtemp(prefix="a1-prof-")))
    d.connect()
    # console + network 采集
    t0 = d.new_tab(FILE)
    s0 = d.attach(t0)
    d.call("Runtime.enable", {}, session=s0)
    d.call("Log.enable", {}, session=s0)
    d.call("Network.enable", {}, session=s0)
    assert wait_for(d, s0, "typeof globalThis.__BOOK__ !== 'undefined'"), "book 未加载"
    assert wait_for(d, s0, "document.body.innerText.includes('甲乙')"), "正文未渲染"

    mode = d.eval(s0, "globalThis.__reviewForTest.backendMode()")
    check("idb-mode-indexeddb", mode == "indexeddb", mode)

    # ---- P01：两 tab 改不同页，都保留 ----
    d.eval(s0, """(() => {
      document.querySelector('.issue-page-button').click();
    })()""")
    assert wait_for(d, s0, "!!document.querySelector('.issue-dialog .issue-edit')"), "tab1 对话框未开"
    d.eval(s0, """(() => {
      const ta = document.querySelector('.issue-dialog .issue-edit');
      ta.value = '甲乙-TAB1'; ta.dispatchEvent(new Event('input', { bubbles: true }));
      [...document.querySelectorAll('.issue-dialog .issue-actions button')]
        .find(b => b.textContent.includes('保存并标记解决')).click();
    })()""")
    assert wait_for(d, s0, "document.body.innerText.includes('已保存到当前浏览器') || (document.querySelector('.issue-save-status')||{}).textContent"), "tab1 保存确认"
    time.sleep(1)

    t1 = d.new_tab(FILE)
    s1 = d.attach(t1)
    d.call("Runtime.enable", {}, session=s1)
    assert wait_for(d, s1, "document.body.innerText.includes('甲乙')"), "tab2 正文未渲染"
    # tab2 到第 2 页
    d.eval(s1, """(() => {
      const j = document.querySelector('[data-jump]');
      j.value = '2'; j.dispatchEvent(new Event('change', { bubbles: true }));
    })()""")
    assert wait_for(d, s1, "document.body.innerText.includes('寅卯')"), "tab2 第2页未渲染"
    d.eval(s1, """(() => { document.querySelector('.issue-page-button').click(); })()""")
    assert wait_for(d, s1, "!!document.querySelector('.issue-dialog .issue-edit')"), "tab2 对话框未开"
    d.eval(s1, """(() => {
      const ta = document.querySelector('.issue-dialog .issue-edit');
      ta.value = '寅卯-TAB2'; ta.dispatchEvent(new Event('input', { bubbles: true }));
      [...document.querySelectorAll('.issue-dialog .issue-actions button')]
        .find(b => b.textContent.includes('保存并标记解决')).click();
    })()""")
    assert wait_for(d, s1, "document.body.innerText.includes('已保存到当前浏览器')"), "tab2 保存确认"
    time.sleep(1)

    recs = d.eval(s1, """(async () => {
      const o = await globalThis.__reviewForTest.ensureIdb();
      const a = await o.backend.loadPage(1);
      const b = await o.backend.loadPage(2);
      return JSON.stringify({ p1: a.records.map(r => [r.issueId, r.resolved, r.replacement, r.editRevision]), p2: b.records.map(r => [r.issueId, r.resolved, r.replacement, r.editRevision]) });
    })()""")
    recs = json.loads(recs)
    check("P01-both-pages-saved", any(r[2] == "甲乙-TAB1" for r in recs["p1"]) and any(r[2] == "寅卯-TAB2" for r in recs["p2"]), recs)

    # 重载 tab1，两页恢复
    d.call("Page.navigate", {"url": FILE}, session=s0)
    assert wait_for(d, s0, "document.body.innerText.includes('甲乙-TAB1')", timeout=25), "tab1 重载恢复页1"
    check("P01-reload-restore", True, "甲乙-TAB1 in text after reload")

    # ---- P02：同 issue 陈旧 expected → CONFLICT（确定性，经两 tab 真实共享库）----
    # tab2 先把 issue-2 推到 rev2；tab1 用过期 expected=1 提交同一 key
    cur = d.eval(s1, """(async () => {
      const o = await globalThis.__reviewForTest.ensureIdb();
      const l = await o.backend.loadPage(2);
      return JSON.stringify(l.records.map(r => [r.issueId, r.editRevision]));
    })()""")
    cur = json.loads(cur)
    rev2 = [r[1] for r in cur if r[0] == "issue-2"][0]
    bump = d.eval(s1, """(async () => {
      const o = await globalThis.__reviewForTest.ensureIdb();
      const l = await o.backend.loadPage(2);
      const cur = l.records.find(r => r.issueId === 'issue-2');
      const out = await o.backend.commit({ sourcePage: 2, blockId: 'c1', issueId: 'issue-2',
        original: '寅卯辰巳午未', simplified: '寅卯辰巳午未',
        issueBasis: { kind: 'suspected', start: 0, end: 2, simplifiedStart: 0, simplifiedEnd: 2, originalQuote: '寅卯', simplifiedQuote: '寅卯' },
        resolved: true, replacement: 'TAB2-WINS' }, cur.editRevision);
      return JSON.stringify(out);
    })()""")
    bump = json.loads(bump)
    stale = d.eval(s0, """(async () => {
      const o = await globalThis.__reviewForTest.ensureIdb();
      const out = await o.backend.commit({ sourcePage: 2, blockId: 'c1', issueId: 'issue-2',
        original: '寅卯辰巳午未', simplified: '寅卯辰巳午未',
        issueBasis: { kind: 'suspected', start: 0, end: 2, simplifiedStart: 0, simplifiedEnd: 2, originalQuote: '寅卯', simplifiedQuote: '寅卯' },
        resolved: true, replacement: 'TAB1-STALE' }, %d);
      return JSON.stringify(out);
    })()""" % rev2)
    stale = json.loads(stale)
    check("P02-stale-commit-conflicts", stale.get("status") == "CONFLICT" and (stale.get("remote") or {}).get("replacement") == "TAB2-WINS", stale)
    final = d.eval(s0, """(async () => {
      const o = await globalThis.__reviewForTest.ensureIdb();
      const l = await o.backend.loadPage(2);
      return JSON.stringify(l.records.filter(r => r.issueId === 'issue-2').map(r => [r.replacement, r.editRevision]));
    })()""")
    check("P02-winner-kept", json.loads(final) == [["TAB2-WINS", rev2 + 1]], final)

    # ---- P08：编辑中收到外部变更通知，输入不被覆盖 ----
    d.eval(s0, """(() => {
      const j = document.querySelector('[data-jump]');
      j.value = '1'; j.dispatchEvent(new Event('change', { bubbles: true }));
    })()""")
    assert wait_for(d, s0, "document.body.innerText.includes('甲乙')"), "tab1 回页1"
    d.eval(s0, """(() => {
      document.querySelector('.issue-page-button').click();
      const ta = document.querySelector('.issue-dialog .issue-edit');
      ta.value = 'TAB1-正在输入'; ta.dispatchEvent(new Event('input', { bubbles: true }));
    })()""")
    time.sleep(0.5)
    # tab2 改同一 issue 并保存（新值）
    d.eval(s1, """(async () => {
      const o = await globalThis.__reviewForTest.ensureIdb();
      const l = await o.backend.loadPage(1);
      const cur = l.records.find(r => r.issueId === 'issue-1');
      await o.backend.commit({ sourcePage: 1, blockId: 'b1', issueId: 'issue-1',
        original: '甲乙丙丁戊己', simplified: '甲乙丙丁戊己',
        issueBasis: cur ? cur.issueBasis : null,
        resolved: true, replacement: 'TAB2-NEW' }, cur ? cur.editRevision : 0);
      return 'tab2-saved';
    })()""")
    time.sleep(2)
    state8 = d.eval(s0, """(() => JSON.stringify({
      ta: (document.querySelector('.issue-dialog .issue-edit') || {}).value || null,
      note: !!document.querySelector('.issue-external-note'),
    }))()""")
    state8 = json.loads(state8)
    check("P08-input-preserved-with-notice", state8["ta"] == "TAB1-正在输入" and state8["note"] is True, state8)
    d.screenshot(s0, os.environ.get('A1_OUT', '/tmp/a1-evidence') + '/shot-p08-external-notice.png')

    # ---- 备份导出 ----
    dl = d.eval(s0, """(async () => {
      const o = await globalThis.__reviewForTest.ensureIdb();
      const b = await o.backend.exportBackup();
      return JSON.stringify({ records: b.records.length, kind: b.kind });
    })()""")
    check("backup-export", json.loads(dl)["records"] >= 2, dl)

    d.screenshot(s0, os.environ.get('A1_OUT', '/tmp/a1-evidence') + '/shot-p01-final.png')
    with open(os.environ.get('A1_OUT', '/tmp/a1-evidence') + '/cdp-p010208-results.json', "w") as f:
        json.dump(OUT, f, ensure_ascii=False, indent=2)
    d.close()
    print("FAILS:", FAILS)
    sys.exit(1 if FAILS else 0)


main()
