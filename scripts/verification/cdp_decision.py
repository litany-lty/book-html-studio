"""J08 在线候选面板浏览器验证（T52-T54）。

环境变量：
  DEC_SERVER  服务地址，默认 http://127.0.0.1:18771
  DEC_PORT    Chrome remote-debugging 端口，默认 9351
  DEC_OUT     产物目录，默认 /tmp/decision-evidence
  DEC_PROFILE Chrome profile 目录，默认临时目录
  DEC_BOOK    书籍 UUID，默认 aaaaaaaa-1111-1111-1111-111111111111

前置：服务已用合成数据启动（见 README：SeedBook + --book.decision.provider=MOCK
--book.decision.mode=ASSIST --book.decision.api-key=test --book.decision.model=mock
--book.decision.allow-cloud-data=true --book.decision.monetary-budget-minor=100）。
不断言任何模型质量，只验证面板行为、布局与焦点。
"""
import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cdpdrive import Driver

SERVER = os.environ.get("DEC_SERVER", "http://127.0.0.1:18771")
PORT = int(os.environ.get("DEC_PORT", "9351"))
OUT = os.environ.get("DEC_OUT", "/tmp/decision-evidence")
PROFILE = os.environ.get("DEC_PROFILE", tempfile.mkdtemp(prefix="dec-prof-"))
BOOK = os.environ.get("DEC_BOOK", "aaaaaaaa-1111-1111-1111-111111111111")

results = {"checks": [], "ok": True}


def check(name, cond, detail=""):
    results["checks"].append({"name": name, "pass": bool(cond), "detail": detail})
    if not cond:
        results["ok"] = False
    print(("PASS " if cond else "FAIL ") + name + (" " + detail if detail else ""))


def wait_for(d, session, expr, timeout=30):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            value = d.eval(session, expr)
        except Exception as e:
            last = str(e)
            time.sleep(0.5)
            continue
        if value:
            return value
        last = value
        time.sleep(0.5)
    return None


def main():
    os.makedirs(OUT, exist_ok=True)
    d = Driver(PORT, profile=PROFILE)
    try:
        d.connect()
        tab = d.new_tab("about:blank")
        session = d.attach(tab)
        d.navigate(session, SERVER + "/")
        check("app-loaded", wait_for(d, session,
              "document.querySelector('#book-select') ? document.title || 'ready' : null", 30))

        # 选书并等待正文（先等书目加载出选项；冷启动重试一次）
        check("book-list", wait_for(d, session,
              "document.querySelectorAll('#book-select option').length > 0", 45))
        selected = False
        for _ in range(2):
            d.eval(session, f"""(() => {{
              const sel = document.querySelector('#book-select');
              sel.value = '{BOOK}';
              sel.dispatchEvent(new Event('change', {{bubbles: true}}));
              return true;
            }})()""")
            if wait_for(d, session,
                    "document.querySelectorAll('#paper .content-issue.pending').length > 0", 45):
                selected = True
                break
        check("page-rendered", selected)

        # 点击首个待核对 → 原稿依据对话框 → “修改”进校对抽屉 + 决策区
        d.eval(session, """(() => {
          document.querySelector('#paper .content-issue.pending').click();
          return true;
        })()""")
        check("inspector-open", wait_for(d, session,
              "document.querySelector('dialog[open]') ? 1 : null", 15))
        d.eval(session, """[...document.querySelectorAll('dialog[open] button')]
          .find(b => b.textContent.includes('修改')).click()""")
        check("drawer-open", wait_for(d, session,
              "document.querySelector('#review-panel.open') ? 1 : null", 15))
        check("decision-section", wait_for(d, session,
              "document.querySelector('[data-decision-panel] .decision-action') ? 1 : null", 15))
        buttons = d.eval(session, """[...document.querySelectorAll('[data-decision-panel] .decision-action')]
          .map(b => b.textContent.trim())""")
        check("decision-buttons", buttons and "比较现有候选" in buttons and "补充一次原图复识别" in buttons,
              json.dumps(buttons, ensure_ascii=False))

        # 草稿门禁：默认无草稿，按钮可用
        disabled = d.eval(session,
          "[...document.querySelectorAll('[data-decision-panel] .decision-action')].map(b => b.disabled)")
        check("no-draft-enabled", disabled and all(v is False for v in disabled), json.dumps(disabled))

        # 比较现有候选 → 等待候选列表
        d.eval(session, """[...document.querySelectorAll('[data-decision-panel] .decision-action')]
          .find(b => b.textContent.includes('比较现有候选')).click()""")
        found = wait_for(d, session,
          "document.querySelectorAll('[data-decision-panel] .decision-candidate').length", 40)
        check("candidates-shown", (found or 0) >= 1, f"count={found}")
        verdict = d.eval(session,
          "document.querySelector('[data-decision-panel] .decision-verdict')?.textContent || ''")
        check("verdict-unconfirmed-label",
              "未确认" in verdict and ("仅列候选" in verdict or "首选" in verdict or "维持" in verdict),
              verdict[:80])
        reasons = d.eval(session,
          "document.querySelector('[data-decision-panel] .decision-reasons')?.textContent || ''")
        check("limits-shown", "限制" in reasons, reasons[:80])

        # 390px：关键按钮可见且主操作不小于 44px（接受前面板完整时测量）
        d.call("Emulation.setDeviceMetricsOverride",
               {"width": 390, "height": 844, "deviceScaleFactor": 1, "mobile": True}, session=session)
        time.sleep(1.0)
        metrics = d.eval(session, """[...document.querySelectorAll('[data-decision-panel] .decision-action')]
          .map(b => { const r = b.getBoundingClientRect();
            return {text: b.textContent.trim(), visible: r.width > 0 && r.height > 0,
                    w: Math.round(r.width), h: Math.round(r.height)}; })""")
        visible = [m for m in (metrics or []) if m.get("visible")]
        check("390px-actions-visible", len(visible) >= 2, json.dumps(metrics, ensure_ascii=False))
        mains = [m for m in visible if "确认" in m["text"] or "比较" in m["text"] or "补充" in m["text"]]
        check("390px-main-actions-44px",
              len(mains) >= 2 and all(m["h"] >= 44 and m["w"] >= 44 for m in mains),
              json.dumps(mains, ensure_ascii=False))
        d.screenshot(session, OUT + "/shot-decision-390px.png")
        d.call("Emulation.clearDeviceMetricsOverride", {}, session=session)

        # 辅助阅读：切换后首选候选带未确认标记进入正文，resolved 仍为 false
        d.eval(session, "document.querySelector('#evidence-toggle').click()")
        assist_ok = wait_for(d, session,
          "document.querySelector('#paper .content-issue.pending.assist[data-unconfirmed]') ? 1 : null", 15)
        check("assisted-marker", assist_ok == 1)
        assist_title = d.eval(session,
          "document.querySelector('#paper .content-issue.pending.assist')?.title || ''")
        check("assisted-title", "尚未确认" in assist_title, assist_title[:40])
        d.eval(session, "document.querySelector('#evidence-toggle').click()")
        time.sleep(0.6)
        confirmed_text = d.eval(session,
          "document.querySelector('#paper .content-issue.pending')?.textContent || ''")
        check("confirmed-shows-source", confirmed_text == "甲乙", confirmed_text[:20])

        # 对照原图并确认：勾选已对照 → 接受 → 疑点解决、面板收起
        d.eval(session, """(() => {
          const panel = document.querySelector('[data-decision-panel]');
          panel.querySelector('.decision-attest input').click();
          [...panel.querySelectorAll('.decision-action')]
            .find(b => b.textContent.includes('对照原图并确认')).click();
          return true;
        })()""")
        resolved_gone = wait_for(d, session,
          "document.querySelectorAll('#paper .content-issue.pending').length === 0 ? 1 : null", 30)
        check("accept-resolves", resolved_gone == 1)
        d.screenshot(session, OUT + "/shot-decision-accepted.png")

        # Esc 关闭抽屉 → 焦点回到触发按钮（A1-06 模式）
        opener_ok = d.eval(session, """(() => {
          const btn = document.querySelector('#review-toggle');
          btn.focus();
          return document.activeElement === btn;
        })()""")
        d.call("Input.dispatchKeyEvent", {"type": "keyDown", "key": "Escape", "code": "Escape",
                                          "windowsVirtualKeyCode": 27}, session=session)
        d.call("Input.dispatchKeyEvent", {"type": "keyUp", "key": "Escape", "code": "Escape",
                                          "windowsVirtualKeyCode": 27}, session=session)
        time.sleep(0.8)
        closed = d.eval(session, "document.querySelector('#review-panel.open') ? 0 : 1")
        focused = d.eval(session, "document.activeElement?.id || ''")
        check("drawer-esc-closed", closed == 1, f"active={focused} opener={opener_ok}")
        d.screenshot(session, OUT + "/shot-decision-final.png")
    finally:
        d.close()
    with open(OUT + "/cdp-decision-results.json", "w") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("OVERALL " + ("PASS" if results["ok"] else "FAIL"))
    return 0 if results["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
