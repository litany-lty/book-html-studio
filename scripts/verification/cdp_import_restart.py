import os
#!/usr/bin/env python3
"""A1-P05 导入 + A1-P07 重启持久（file:// + 真实 IndexedDB）。"""
import json
import shutil
import tempfile
import sys
import time

sys.path.insert(0, "/tmp/a1-evidence")
from cdpdrive import Driver

FILE = os.environ.get('A1_FILE', "file:///tmp/a1b-evidence/offline2/index.html")
OUT, FAILS = {}, []


def check(name, cond, detail=""):
    OUT[name] = {"pass": bool(cond), "detail": detail}
    print(("PASS " if cond else "FAIL ") + name + (" | " + str(detail) if detail else ""))
    if not cond:
        FAILS.append(name)


def wait_for(d, s, expr, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if d.eval(s, expr):
                return True
        except Exception:
            pass
        time.sleep(0.4)
    return False


def main():
    d = Driver(9344, profile=os.environ.get("A1_PROFILE", tempfile.mkdtemp(prefix="a1-prof-")))
    d.connect()
    t = d.new_tab(FILE)
    s = d.attach(t)
    d.call("Runtime.enable", {}, session=s)
    assert wait_for(d, s, "document.body.innerText.includes('甲乙')"), "正文未渲染"
    mode = d.eval(s, "globalThis.__reviewForTest.backendMode()")
    check("import-idb-mode", mode == "indexeddb", mode)

    # 打开对话框，定位备份导入区
    d.eval(s, "document.querySelector('.issue-page-button').click()")
    assert wait_for(d, s, "!!document.querySelector('.issue-dialog .import-file')"), "导入区未出现"

    # CDP 设置文件输入
    r = d.call("DOM.getDocument", {"depth": 0}, session=s)
    root = r["root"]["nodeId"]
    q = d.call("DOM.querySelector", {"nodeId": root, "selector": ".issue-dialog .import-file"}, session=s)
    d.call("DOM.setFileInputFiles", {"files": [os.environ.get('A1_OUT', '/tmp/a1-evidence') + '/import-backup.json'], "nodeId": q["nodeId"]}, session=s)
    # 点 预览并应用
    d.eval(s, """(() => {
      [...document.querySelectorAll('.issue-dialog .import-review button')]
        .find(b => b.textContent.includes('预览并应用')).click();
    })()""")
    assert wait_for(d, s, "document.querySelector('.import-preview').textContent.includes('已应用')", timeout=25), "导入未完成"
    preview = d.eval(s, "document.querySelector('.import-preview').textContent")
    check("P05-import-applied", "已应用 2" in preview, preview)
    # 恢复确认：重载后 UI 显示导入的修订
    d.call("Page.navigate", {"url": FILE}, session=s)
    assert wait_for(d, s, "document.body.innerText.includes('TAB2-NEW')", timeout=25), "导入后重载恢复"
    check("P05-reload-after-import", True, "TAB2-NEW restored")
    d.screenshot(s, os.environ.get('A1_OUT', '/tmp/a1-evidence') + '/shot-p05-import.png')

    # ---- P07：杀掉整个浏览器进程，同 profile 重开 ----
    d.close()
    time.sleep(1)
    d2 = Driver(9345, profile=os.environ.get("A1_PROFILE", tempfile.mkdtemp(prefix="a1-prof-")))
    d2.connect()
    t2 = d2.new_tab(FILE)
    s2 = d2.attach(t2)
    assert wait_for(d2, s2, "document.body.innerText.includes('TAB2-NEW')", timeout=25), "重启后恢复"
    recs = d2.eval(s2, """(async () => {
      const o = await globalThis.__reviewForTest.ensureIdb();
      const a = await o.backend.loadPage(1);
      return JSON.stringify(a.records.map(r => [r.issueId, r.resolved, r.replacement]));
    })()""")
    check("P07-restart-persist", "TAB2-NEW" in recs, recs)
    d2.screenshot(s2, os.environ.get('A1_OUT', '/tmp/a1-evidence') + '/shot-p07-restart.png')
    with open(os.environ.get('A1_OUT', '/tmp/a1-evidence') + '/cdp-p0507-results.json', "w") as f:
        json.dump(OUT, f, ensure_ascii=False, indent=2)
    d2.close()
    print("FAILS:", FAILS)
    sys.exit(1 if FAILS else 0)


main()
