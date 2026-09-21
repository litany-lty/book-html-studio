"""PARTIAL 收口：T53 脏草稿禁用、T60 复制提示（真实 Chrome/CDP）。

环境变量：PART_SERVER（默认 http://127.0.0.1:18771）、PART_PORT（默认 9367）、
PART_OUT（默认 /tmp/partial-close）、PART_BOOK（默认种子书）。
前置：合成服务（MOCK/ASSIST）+ 未解决的 issue-1。
"""
import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cdpdrive import Driver

SERVER = os.environ.get("PART_SERVER", "http://127.0.0.1:18771")
PORT = int(os.environ.get("PART_PORT", "9367"))
OUT = os.environ.get("PART_OUT", "/tmp/partial-close")
PROFILE = tempfile.mkdtemp(prefix="part-prof-")
BOOK = os.environ.get("PART_BOOK", "aaaaaaaa-1111-1111-1111-111111111111")

results = {"checks": [], "ok": True}


def check(name, cond, detail=""):
    results["checks"].append({"name": name, "pass": bool(cond), "detail": detail})
    if not cond:
        results["ok"] = False
    print(("PASS " if cond else "FAIL ") + name + (" " + detail if detail else ""))


def wait_for(d, session, expr, timeout=30):
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
        try:
            d.call("Browser.grantPermissions",
                   {"origin": SERVER, "permissions": ["clipboardReadWrite", "clipboardSanitizedWrite"]})
        except Exception as e:
            print("grantPermissions failed: " + str(e)[:100])
        tab = d.new_tab("about:blank")
        session = d.attach(tab)
        d.navigate(session, SERVER + "/")
        assert wait_for(d, session, "document.querySelectorAll('#book-select option').length > 0", 45)
        for _ in range(2):
            d.eval(session, f"""(() => {{ const sel = document.querySelector('#book-select');
              sel.value = '{BOOK}'; sel.dispatchEvent(new Event('change', {{bubbles: true}})); return true; }})()""")
            if wait_for(d, session,
                        "document.querySelectorAll('#paper .content-issue.pending').length > 0", 45):
                break
        assert wait_for(d, session,
                        "document.querySelectorAll('#paper .content-issue.pending').length > 0", 10), \
            "正文未渲染"
        # 进抽屉（先试 DOM click；无响应则用真实鼠标事件点 span 中心，最多 3 轮）
        opened = False
        for _ in range(3):
            d.eval(session, "document.querySelector('#paper .content-issue.pending').click()")
            if wait_for(d, session, "document.querySelector('dialog[open]') ? 1 : null", 6):
                opened = True
                break
            rect = d.eval(session, """(() => { const r = document.querySelector(
              '#paper .content-issue.pending').getBoundingClientRect();
              return [r.x + r.width / 2, r.y + r.height / 2]; })()""")
            if rect:
                d.call("Input.dispatchMouseEvent",
                       {"type": "mousePressed", "x": rect[0], "y": rect[1], "button": "left",
                        "clickCount": 1}, session=session)
                d.call("Input.dispatchMouseEvent",
                       {"type": "mouseReleased", "x": rect[0], "y": rect[1], "button": "left",
                        "clickCount": 1}, session=session)
                if wait_for(d, session, "document.querySelector('dialog[open]') ? 1 : null", 6):
                    opened = True
                    break
            time.sleep(1.0)
        assert opened, "原稿依据对话框未开"
        d.eval(session, """[...document.querySelectorAll('dialog[open] button')]
          .find(b => b.textContent.includes('修改')).click()""")
        assert wait_for(d, session, "document.querySelector('#review-panel.open') ? 1 : null", 15)
        assert wait_for(d, session,
                        "document.querySelector('[data-decision-panel] .decision-action') ? 1 : null", 15)

        # 先比较出推荐，再开辅助阅读做复制（T60）；T53 脏草稿放最后（会锁导航）
        d.eval(session, """[...document.querySelectorAll('[data-decision-panel] .decision-action')]
          .find(b => b.textContent.includes('比较现有候选')).click()""")
        assert wait_for(d, session,
                        "document.querySelectorAll('[data-decision-panel] .decision-candidate').length", 40), \
            "候选未出现"
        # T59：终态后轮询停止——终态时刻后 5 秒内 decision-jobs 网络请求为 0
        try:
            d.call("Network.enable", {}, session=session)
        except Exception:
            pass
        d.drain_events()
        terminal_at = time.time()
        time.sleep(5)
        stray = [e for e in d.drain_events()
                 if e.get("method") == "Network.requestWillBeSent"
                 and "decision-jobs" in str(e.get("params", {}).get("request", {}).get("url", ""))
                 and float(e.get("params", {}).get("timestamp", 0)) > 0]
        # 只要终态后无新的 decision-jobs 请求即通过（计数窗口以 drain 为准）
        check("T59-poll-stops", len(stray) == 0, f"stray={len(stray)}")
        d.eval(session, "document.querySelector('#evidence-toggle').click()")
        assert wait_for(d, session,
                        "document.querySelector('#paper .content-issue.pending.assist[data-unconfirmed]') ? 1 : null", 15), \
            "辅助标记未出现"
        copied = d.eval(session, """(() => {
          // execCommand 在 headless 下不可靠：直发 copy 事件验证处理器逻辑（标记识别 + 未确认提示）
          const marker = document.querySelector('#paper .content-issue.pending.assist');
          if (!marker) return 'NO_MARKER';
          const range = document.createRange();
          range.selectNodeContents(marker);
          const sel = window.getSelection();
          sel.removeAllRanges(); sel.addRange(range);
          const captured = {};
          const ev = new Event('copy', { bubbles: true, cancelable: true });
          ev.clipboardData = { setData(t, v) { captured[t] = v; } };
          marker.dispatchEvent(ev);
          sel.removeAllRanges();
          return JSON.stringify({ prevented: ev.defaultPrevented, text: captured['text/plain'] || '' });
        })()""")
        check("T60-copy-note", isinstance(copied, str) and "未确认" in copied, (copied or "")[:80])

        # T53 放最后：制造脏草稿 → 决策按钮禁用并提示先保存（本轮末尾，无需清理）
        d.eval(session, """(() => { const ta = document.querySelector('#issue-workbench .issue-edit textarea');
          if (!ta) return false; ta.value = '脏草稿'; ta.dispatchEvent(new Event('input', {bubbles: true})); return true; })()""")
        time.sleep(0.8)
        states = d.eval(session,
          "[...document.querySelectorAll('[data-decision-panel] .decision-action')].map(b => [b.textContent.trim(), b.disabled, b.title])")
        compare = [s for s in (states or []) if "比较" in s[0]]
        check("T53-dirty-disables", bool(compare) and all(s[1] for s in compare), json.dumps(states, ensure_ascii=False)[:200])
        check("T53-dirty-hint", bool(compare) and all("保存" in (s[2] or "") for s in compare), "")
        d.screenshot(session, OUT + "/shot-partial.png")
    finally:
        d.close()
    with open(OUT + "/cdp-partial-results.json", "w") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("OVERALL " + ("PASS" if results["ok"] else "FAIL"))
    return 0 if results["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
