"""J10 离线建议摘要验证（T60/T62–T64 离线侧）。

环境变量：
  OFF_FILE  file:// 离线包 index.html（须含 decisions.js 摘要），默认 /tmp/offdec/pkg/index.html
  OFF_PORT  Chrome remote-debugging 端口，默认 9365
  OFF_OUT   产物目录，默认 /tmp/offline-decision
  OFF_PROFILE Chrome profile，默认临时目录

前置：离线包由带 CURRENT 建议的书导出（issue-1 有候选甲乙）。
不断言模型质量，只验证摘要展示、确认审计与隔离。
"""
import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from cdpdrive import Driver

FILE = os.environ.get("OFF_FILE", "file:///tmp/offdec/pkg/index.html")
PORT = int(os.environ.get("OFF_PORT", "9365"))
OUT = os.environ.get("OFF_OUT", "/tmp/offline-decision")
PROFILE = os.environ.get("OFF_PROFILE", tempfile.mkdtemp(prefix="offdec-prof-"))

results = {"checks": [], "ok": True}


def check(name, cond, detail=""):
    results["checks"].append({"name": name, "pass": bool(cond), "detail": detail})
    if not cond:
        results["ok"] = False
    print(("PASS " if cond else "FAIL ") + name + (" " + detail if detail else ""))


def wait_for(d, session, expr, timeout=25):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if d.eval(session, expr):
                return True
        except Exception:
            pass
        time.sleep(0.4)
    return False


def main():
    os.makedirs(OUT, exist_ok=True)
    d = Driver(PORT, profile=PROFILE)
    try:
        d.connect()
        tab = d.new_tab(FILE)
        session = d.attach(tab)
        assert wait_for(d, session, "document.body.innerText.includes('甲乙')"), "正文未渲染"
        check("offline-no-cloud", d.eval(session,
              "typeof globalThis.BOOK_DECISIONS === 'object'"), "摘要为静态数据，无外呼")

        # 打开本页问题对话框
        d.eval(session, "document.querySelector('.issue-page-button').click()")
        assert wait_for(d, session, "!!document.querySelector('.issue-dialog .issue-edit')"), "对话框未开"
        summary = d.eval(session,
          "(() => { const dlg = document.querySelector('.issue-dialog'); "
          "return dlg ? dlg.innerText.slice(0, 600) : ''; })()")
        check("summary-marked-unconfirmed", "辅助推荐" in summary and "未确认" in summary,
              summary[:80].replace('\n', ' '))
        # 输入框不被推荐覆盖
        before = d.eval(session,
          "document.querySelector('.issue-dialog .issue-edit').value || ''")
        check("input-not-overwritten", before == "甲乙", before[:20])

        # 辅助开关默认关闭；打开后正文出现未确认标记
        assist_default = d.eval(session,
          "document.querySelector('.issue-assist-toggle input')?.checked")
        check("assist-default-off", assist_default is False, str(assist_default))
        d.eval(session, """(() => {
          const box = document.querySelector('.issue-assist-toggle input');
          box.click();
          return true;
        })()""")
        d.eval(session, "document.querySelector('.issue-dialog').close()")
        check("assist-marker", wait_for(d, session,
              "document.querySelector('.content-issue.assist[data-unconfirmed]') ? 1 : null", 15))
        d.screenshot(session, OUT + "/shot-offline-assist.png")

        # 对照确认：勾选已对照 + 保存 → IDB 记录 JEV_ASSISTED 来源
        d.eval(session, "document.querySelector('.issue-page-button').click()")
        assert wait_for(d, session, "!!document.querySelector('.issue-dialog .issue-edit')"), "对话框未开2"
        d.eval(session, """(() => {
          document.querySelector('.issue-dialog .issue-attest input').click();
          return true;
        })()""")
        d.eval(session, """(() => {
          [...document.querySelectorAll('.issue-dialog .issue-actions button')]
            .find(b => b.textContent.includes('保存并标记解决')).click();
          return true;
        })()""")
        saved = wait_for(d, session,
          "document.body.innerText.includes('已保存到当前浏览器')", 20)
        check("offline-accept-saved", saved)
        recs = d.eval(session, """(async () => {
          const o = await globalThis.__reviewForTest.ensureIdb();
          const loaded = await o.backend.loadPage(1);
          return JSON.stringify(loaded.records.map(r => [r.issueId, r.resolved, r.replacement, r.resolution ? r.resolution.origin : null, r.resolution ? r.resolution.candidateId : null]));
        })()""")
        check("offline-resolution-jeV", '"JEV_ASSISTED"' in (recs or ''), (recs or '')[:160])

        # 普通人工录入不冒充：改文字后保存 → resolution 为空
        d.eval(session, """(() => {
          const ta = document.querySelector('.issue-dialog .issue-edit');
          if (!ta) return false;
          ta.value = '甲乙改'; ta.dispatchEvent(new Event('input', {bubbles: true}));
          [...document.querySelectorAll('.issue-dialog .issue-actions button')]
            .find(b => b.textContent.includes('保存并标记解决')).click();
          return true;
        })()""")
        time.sleep(2)
        recs2 = d.eval(session, """(async () => {
          const o = await globalThis.__reviewForTest.ensureIdb();
          const loaded = await o.backend.loadPage(1);
          return JSON.stringify(loaded.records.map(r => [r.issueId, r.replacement, r.resolution ? r.resolution.origin : null]));
        })()""")
        check("manual-no-forged-origin", '"甲乙改"' in (recs2 or '') and 'JEV_ASSISTED' not in (recs2 or ''),
              (recs2 or '')[:160])
        d.screenshot(session, OUT + "/shot-offline-decision.png")
    finally:
        d.close()
    with open(OUT + "/cdp-offline-decision-results.json", "w") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("OVERALL " + ("PASS" if results["ok"] else "FAIL"))
    return 0 if results["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
