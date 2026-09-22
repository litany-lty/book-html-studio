"""阅读工作台回归；仅可用于独立端口上的 SeedBook，不调用云模型。

UX_SERVER 默认 http://127.0.0.1:18767，UX_OUT 为截图/结果目录。
复用 cdpdrive，不安装新依赖；保存与冲突测试会修改合成书第 1 页。
"""
import json
import os
import tempfile
import time
import urllib.parse
import urllib.request

from cdpdrive import Driver

SERVER = os.environ.get("UX_SERVER", "http://127.0.0.1:18767").rstrip("/")
OUT = os.environ.get("UX_OUT", tempfile.mkdtemp(prefix="reader-ux-evidence-"))
BOOK = "aaaaaaaa-1111-1111-1111-111111111111"


def main():
    target = urllib.parse.urlparse(SERVER)
    if target.hostname != "127.0.0.1" or target.port in (None, 18765):
        raise RuntimeError("仅允许独立本地端口，禁止日常工具端口")
    root = SERVER + "/api/books/" + BOOK
    with urllib.request.urlopen(root) as response:
        book = json.load(response)
    if book["title"] != "合成证据书" or book["totalPages"] != 3:
        raise RuntimeError("必须使用 SeedBook 合成夹具")
    os.makedirs(OUT, exist_ok=True)
    driver = Driver(int(os.environ.get("UX_PORT", "9358")), profile=tempfile.mkdtemp(prefix="reader-ux-profile-"))
    results = []

    def check(name, value):
        passed = bool(value)
        results.append({"name": name, "pass": passed, "detail": value})
        print(("PASS " if passed else "FAIL ") + name, flush=True)
        if not passed:
            raise AssertionError(name)

    try:
        driver.connect()
        session = driver.attach(driver.new_tab("about:blank"))
        driver.call("Runtime.enable", {}, session=session)

        def evaluate(code):
            return driver.eval(session, code)

        def wait(code):
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                value = evaluate(code)
                if value:
                    return value
                time.sleep(.1)
            return False

        def viewport(width, height):
            driver.call("Emulation.setDeviceMetricsOverride", {"width": width, "height": height,
                        "deviceScaleFactor": 1, "mobile": width < 600}, session=session)

        viewport(1440, 900)
        driver.navigate(session, SERVER)
        check("书架载入", wait(f"!!document.querySelector('#book-select option[value=\"{BOOK}\"]')"))
        evaluate(f"document.querySelector('#book-select').value='{BOOK}'; document.querySelector('#book-select').dispatchEvent(new Event('change'))")
        check("合成书正文载入", wait("!!document.querySelector('#issue-workbench textarea')"))
        check("桌面无横向溢出", evaluate("document.documentElement.scrollWidth <= innerWidth"))
        check("编辑框在桌面首屏", evaluate("document.querySelector('#issue-workbench textarea').getBoundingClientRect().bottom < innerHeight"))
        evaluate("document.querySelector('#reading-options-open').click()")
        check("字体预览存在", wait("!!document.querySelector('#reader-font-preview') && document.querySelector('#reading-options').open"))
        evaluate("document.querySelector('#reader-font').value='jetbrains-mono'; document.querySelector('#reader-font').dispatchEvent(new Event('change'))")
        check("字体选项齐全", evaluate("document.querySelectorAll('#reader-font option').length >= 6"))
        driver.call("Input.dispatchKeyEvent", {"type": "keyDown", "key": "Escape", "code": "Escape", "windowsVirtualKeyCode": 27}, session=session)
        check("阅读设置关闭回焦", wait("!document.querySelector('#reading-options').open && document.activeElement.id==='reading-options-open'"))

        evaluate("""(()=>{window.uxUnexpectedConfirm=0;window.uxOriginalConfirm=window.confirm;
          window.confirm=()=>{window.uxUnexpectedConfirm++;return false};
          const input=document.querySelector('#mark-reviewed');input.checked=!input.checked;input.dispatchEvent(new Event('change'));
          document.querySelector('#issue-workbench .issue-list button').click();
        })()""")
        check("关闭JEV仍可查看面板", wait("!!document.querySelector('.decision-inactive .decision-empty')"))
        check("渲染候选不弹离页确认", evaluate("window.uxUnexpectedConfirm===0"))
        evaluate("new Promise(resolve=>setTimeout(resolve,350))")
        check("切换疑点不跳走工作台", evaluate("document.querySelector('#issue-workbench').getBoundingClientRect().top>=document.querySelector('#review-panel').getBoundingClientRect().top"))
        evaluate("window.confirm=window.uxOriginalConfirm")

        # 延迟真实本地保存，观察 UI 的在途状态，不伪造保存成功。
        evaluate("""(async()=>{ const {api}=await import('/api.js'); window.uxRealSave=api.savePage;
          api.savePage=async(...args)=>{await new Promise(resolve=>window.uxReleaseSave=resolve);return window.uxRealSave(...args)};
          const input=document.querySelector('#mark-reviewed');input.checked=!input.checked;input.dispatchEvent(new Event('change'));
        })()""")
        check("修改显示未保存", evaluate("document.querySelector('#review-save-status').textContent.includes('未保存')"))
        evaluate("document.querySelector('#save-page').click()")
        check("保存中禁重复提交", wait("!!window.uxReleaseSave && document.querySelector('#save-page').disabled && document.querySelector('#review-save-status').textContent.includes('保存中')"))
        evaluate("window.uxReleaseSave()")
        check("真实保存成功反馈", wait("document.querySelector('#review-save-status').textContent.includes('已保存') && document.querySelector('#save-page').disabled"))
        evaluate("(async()=>{const {api}=await import('/api.js');api.savePage=window.uxRealSave})()")

        # A 的旧响应不能解除 B 的在途锁；B 发送后继续编辑也不能显示已保存。
        evaluate("""(async()=>{const {api}=await import('/api.js'); window.uxReleases={};window.uxSettled={};
          api.savePage=async(...args)=>{await new Promise(resolve=>window.uxReleases[args[1]]=resolve);try{return await window.uxRealSave(...args)}finally{window.uxSettled[args[1]]=true}};
          window.confirm=()=>true;
          const input=document.querySelector('#mark-reviewed');input.checked=!input.checked;input.dispatchEvent(new Event('change'));
          document.querySelector('#save-page').click();
        })()""")
        check("A页延迟保存开始", wait("!!window.uxReleases[1]"))
        evaluate("document.querySelector('#next-page').click()")
        check("切入B页", wait("document.querySelector('#page-jump').value==='2' && !document.querySelector('#review-content').hidden"))
        evaluate("(()=>{const input=document.querySelector('#mark-reviewed');input.checked=!input.checked;input.dispatchEvent(new Event('change'));document.querySelector('#save-page').click()})()")
        check("B页独立保存开始", wait("!!window.uxReleases[2]"))
        evaluate("window.uxReleases[1]()")
        check("A实际写入完成", wait("!!window.uxSettled[1]"))
        check("旧保存不解除新保存按钮", evaluate("document.querySelector('#save-page').disabled && document.querySelector('#save-page').textContent.includes('保存中') && document.querySelector('#review-save-status').textContent.includes('保存中')"))
        evaluate("(()=>{const input=document.querySelector('#mark-reviewed');input.checked=!input.checked;input.dispatchEvent(new Event('change'));window.uxReleases[2]()})()")
        check("保存后新增草稿仍待保存", wait("document.querySelector('#review-save-status').textContent.includes('未保存') && !document.querySelector('#save-page').disabled"))
        evaluate("(async()=>{const {api}=await import('/api.js');api.savePage=window.uxRealSave;document.querySelector('#save-page').click()})()")
        check("新增草稿可再次保存", wait("document.querySelector('#review-save-status').textContent.includes('已保存') && document.querySelector('#save-page').disabled"))
        evaluate("document.querySelector('#prev-page').click()")
        check("返回A页", wait("document.querySelector('#page-jump').value==='1' && !!document.querySelector('#issue-workbench textarea')"))

        # 制造真实 revision 冲突：另一个写入者先保存同一份合成书。
        with urllib.request.urlopen(root + "/pages/1") as response:
            remote = json.load(response)
        payload = json.dumps({"blocks": remote["blocks"], "reviewed": remote["reviewed"], "revision": remote["revision"]}).encode()
        request = urllib.request.Request(root + "/pages/1", data=payload, method="PUT", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request) as response:
            check("独立写入推进版本", json.load(response)["revision"] > remote["revision"])
        evaluate("(()=>{const input=document.querySelector('#mark-reviewed');input.checked=!input.checked;input.dispatchEvent(new Event('change'));document.querySelector('#save-page').click()})()")
        check("冲突常驻且不显示已保存", wait("!!document.querySelector('.conflict-bar') && document.querySelector('#save-page').disabled && !document.querySelector('#review-save-status').textContent.includes('已保存')"))
        driver.screenshot(session, OUT + "/desktop-conflict.png")

        # 换新标签，放弃的仅是合成书浏览器内草稿，避免卸载确认干扰布局验收。
        session = driver.attach(driver.new_tab(SERVER))
        viewport(390, 844)
        check("手机书架载入", wait(f"!!document.querySelector('#book-select option[value=\"{BOOK}\"]')"))
        evaluate(f"document.querySelector('#book-select').value='{BOOK}';document.querySelector('#book-select').dispatchEvent(new Event('change'))")
        check("手机正文载入", wait("!!document.querySelector('#paper .reading-flow')"))
        evaluate("document.querySelector('#page-jump').value='1';document.querySelector('#jump-form').requestSubmit()")
        check("手机校对页载入", wait("document.querySelector('#page-jump').value==='1' && !!document.querySelector('#issue-workbench textarea')"))
        check("手机正文首屏空间", evaluate("document.querySelector('#paper').getBoundingClientRect().top <= 300"))
        check("手机无横向溢出", evaluate("document.documentElement.scrollWidth <= innerWidth"))
        evaluate("document.querySelector('#more-toggle').click()")
        check("更多工具可打开", wait("document.querySelector('#more-toggle').getAttribute('aria-expanded')==='true'"))
        driver.call("Input.dispatchKeyEvent", {"type": "keyDown", "key": "Escape", "code": "Escape", "windowsVirtualKeyCode": 27}, session=session)
        check("更多关闭回焦", wait("document.querySelector('#more-toggle').getAttribute('aria-expanded')==='false' && document.activeElement.id==='more-toggle'"))
        evaluate("document.querySelector('#review-toggle').click()")
        check("手机关键触控尺寸", evaluate("['save-page','job-toggle','review-toggle','toc-toggle','reading-options-open'].every(id=>document.getElementById(id).getBoundingClientRect().height>=44)"))
        check("手机编辑框可见", evaluate("document.querySelector('#issue-workbench textarea').getBoundingClientRect().bottom < document.querySelector('.save-dock').getBoundingClientRect().top"))
        driver.screenshot(session, OUT + "/mobile-review.png")
        errors = [event for event in driver.drain_events() if event.get("method") == "Runtime.exceptionThrown"]
        check("无未捕获脚本异常", not errors)
    finally:
        driver.close()
        with open(OUT + "/results.json", "w", encoding="utf-8") as output:
            json.dump(results, output, ensure_ascii=False, indent=2)
    print("OVERALL PASS", OUT)


if __name__ == "__main__":
    main()
