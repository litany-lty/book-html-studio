"""随读真实 HTTP / 浏览器验收；仅用于 ReadingWindowQaServer 门控模拟处理器。

不调用云模型；实际使用生产调度、磁盘保存与页面 API。不能用于用户服务或真实书籍。
"""
import json
import os
import tempfile
import time
import urllib.parse
import urllib.request
import urllib.error
import uuid

from cdpdrive import Driver

SERVER = os.environ.get("READING_SERVER", "http://127.0.0.1:18767").rstrip("/")
OUT = os.environ.get("READING_OUT", tempfile.mkdtemp(prefix="reading-window-evidence-"))
BOOK = "bbbbbbbb-2222-2222-2222-222222222222"


def http(path, body=None, method=None):
    request = urllib.request.Request(SERVER + path, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def main():
    target = urllib.parse.urlparse(SERVER)
    if target.hostname != "127.0.0.1" or target.port in (None, 18765):
        raise RuntimeError("仅允许独立本地端口")
    fixture = http("/__qa/reading-calls")
    if fixture.get("fixture") != BOOK or fixture.get("calls"):
        raise RuntimeError("必须使用尚未调用的 ReadingWindowQaServer 合成夹具")
    book = http("/api/books/" + BOOK)
    if book["title"] != "随读处理合成书" or book["totalPages"] != 60:
        raise RuntimeError("合成书身份不符")
    os.makedirs(OUT, exist_ok=True)
    driver = Driver(int(os.environ.get("READING_PORT", "9360")), profile=tempfile.mkdtemp(prefix="reading-window-profile-"))
    results = []

    def check(name, value):
        passed = bool(value)
        results.append({"name": name, "pass": passed, "detail": value})
        print(("PASS " if passed else "FAIL ") + name, flush=True)
        if not passed:
            raise AssertionError(name)

    def request_status(path, body):
        try:
            http(path, body)
            return 200
        except urllib.error.HTTPError as error:
            return error.code

    try:
        inactive = http("/api/books/" + BOOK + "/reading-window", {
            "sessionId": str(uuid.uuid4()), "sequence": 1, "currentPage": 1, "provider": "paddle-aistudio",
            "layout": "auto", "splitSpreads": True, "assist": False, "allowCloud": True, "start": False})
        check("旧会话心跳不能在新服务上开启识别", inactive["status"] == "EXPIRED" and not inactive["enabled"])
        driver.connect()
        session = driver.attach(driver.new_tab("about:blank"))
        driver.call("Runtime.enable", {}, session=session)
        driver.call("Network.enable", {}, session=session)

        def evaluate(code):
            return driver.eval(session, code)

        def wait(code, seconds=15):
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                value = evaluate(code)
                if value:
                    return value
                time.sleep(.08)
            return False

        def calls():
            return http("/__qa/reading-calls")["calls"]

        def wait_calls(count):
            end = time.monotonic() + 12
            while time.monotonic() < end:
                observed = calls()
                if len(observed) >= count:
                    return observed
                time.sleep(.08)
            return []

        def whole_book_reads():
            paths = {"/api/books/" + BOOK, "/api/books/" + BOOK + "/pages", "/api/books/" + BOOK + "/outline"}
            return sum(1 for event in driver.events if event.get("method") == "Network.requestWillBeSent"
                       and urllib.parse.urlparse(event.get("params", {}).get("request", {}).get("url", "")).path in paths)

        def jump(number):
            evaluate(f"document.querySelector('#page-jump').value='{number}';document.querySelector('#jump-form').requestSubmit()")
            return wait(f"(async()=>{{const {{state}}=await import('/store.js');return state.currentPage==={number} && state.page?.pageNumber==={number}}})()")

        def enable():
            evaluate("document.querySelector('#reading-window-open').click()")
            check("开启前展示云用量说明", wait("document.querySelector('#reading-window-dialog').open && document.querySelector('#reading-window-choice').textContent.length>0"))
            evaluate("document.querySelector('#reading-window-enable').click()")

        driver.call("Emulation.setDeviceMetricsOverride", {"width": 1440, "height": 900, "deviceScaleFactor": 1, "mobile": False}, session=session)
        driver.navigate(session, SERVER)
        check("书架就绪", wait(f"!!document.querySelector('#book-select option[value=\"{BOOK}\"]')"))
        evaluate(f"document.querySelector('#book-select').value='{BOOK}';document.querySelector('#book-select').dispatchEvent(new Event('change'))")
        check("未识别页也可进入阅读", wait("!!document.querySelector('#paper img')"))
        time.sleep(1.2)
        check("默认关闭不识别", len(calls()) == 0)
        check("跳至第10页", jump(10))
        initial_whole_book_reads = whole_book_reads()
        enable()
        check("开启立即进入静止等待", wait("document.querySelector('#reading-window-bar').textContent.includes('1 秒') || document.querySelector('#reading-window-bar').textContent.includes('停留')"))
        check("快速跳12", jump(12))
        check("快速跳20", jump(20))
        check("快速跳30", jump(30))
        last_move = time.time() * 1000
        time.sleep(.35)
        check("停留不足1秒无识别调用", len(calls()) == 0)
        observed = wait_calls(1)
        check("仅最新中心30先识别", [item["pageNumber"] for item in observed] == [30])
        check("服务端1秒门禁生效", observed[0]["startedAt"] - last_move >= 800)
        check("识别等待期间仍显示当前原稿", evaluate("!!document.querySelector('#paper img') && document.querySelector('#page-jump').value==='30'"))
        check("其他标签无法抢占当前窗口", request_status("/api/books/" + BOOK + "/reading-window", {
            "sessionId": str(uuid.uuid4()), "sequence": 1, "currentPage": 50, "provider": "paddle-aistudio",
            "layout": "auto", "splitSpreads": True, "assist": False, "allowCloud": True, "start": True}) == 409)
        check("手动任务不能插入随读队列", request_status("/api/books/" + BOOK + "/jobs", {
            "pages": "50", "provider": "paddle-aistudio", "layout": "auto", "splitSpreads": True,
            "assist": False, "force": False}) == 409)
        check("在途时跳40", jump(40))
        check("再跳42", jump(42))
        time.sleep(1.1)
        check("在途未完成时不并发送新页", [item["pageNumber"] for item in calls()] == [30])
        http("/__qa/release", {})
        observed = wait_calls(2)
        check("旧请求收尾后最新42优先", [item["pageNumber"] for item in observed] == [30, 42])
        check("旧30结果不切回旧页", evaluate("document.querySelector('#page-jump').value==='42'"))
        http("/__qa/release", {})
        observed = wait_calls(3)
        check("邻页顺序当前后先43", [item["pageNumber"] for item in observed] == [30, 42, 43])
        check("不等全窗口当前42自动展示文字", wait("document.querySelector('#paper').textContent.includes('这是第 42 页')"))
        check("随读轮询不重复查询全书内容与目录", whole_book_reads() == initial_whole_book_reads)
        driver.screenshot(session, os.path.join(OUT, "desktop-reading.png"))
        driver.call("Emulation.setDeviceMetricsOverride", {"width": 390, "height": 844, "deviceScaleFactor": 1, "mobile": True}, session=session)
        time.sleep(.3)
        check("390px开启态无横向溢出", evaluate("document.documentElement.scrollWidth<=innerWidth"))
        check("390px停止与刷新可点击", evaluate("['reading-window-stop','reading-window-refresh'].every(id=>{const r=document.getElementById(id).getBoundingClientRect();return r.height>=44 && r.width>=44 && r.right<=innerWidth && r.bottom<=innerHeight})"))
        evaluate("document.querySelector('#reader').scrollTop=document.querySelector('#reader').scrollHeight")
        time.sleep(.3)
        check("状态条不遮挡底部跳页输入", evaluate("document.querySelector('#page-jump').getBoundingClientRect().bottom<=document.querySelector('#reading-window-bar').getBoundingClientRect().top"))
        driver.screenshot(session, os.path.join(OUT, "mobile-active.png"))
        driver.call("Emulation.setDeviceMetricsOverride", {"width": 1440, "height": 900, "deviceScaleFactor": 1, "mobile": False}, session=session)
        evaluate("document.querySelector('#reader').scrollTop=0")

        # Another page is gated in flight. A concurrent saved revision of page 42 must not
        # replace the local dirty draft when a window status exposes the newer revision.
        evaluate("(()=>{const input=document.querySelector('#mark-reviewed');input.checked=!input.checked;input.dispatchEvent(new Event('change'))})()")
        check("建立未保存草稿", wait("(async()=>{const {state}=await import('/store.js');return state.dirty})()"))
        page = http("/api/books/" + BOOK + "/pages/42")
        page["blocks"][0]["original"] += " 外部修订。"
        page["blocks"][0]["simplified"] += " 外部修订。"
        http("/api/books/" + BOOK + "/pages/42", {"blocks": page["blocks"], "reviewed": False, "revision": page["revision"]}, "PUT")
        check("新结果提示而非覆盖草稿", wait("!document.querySelector('#reading-window-apply').hidden"))
        check("未保存正文未被远端替换", evaluate("(async()=>{const {state}=await import('/store.js');return state.dirty && !state.blocks[0].original.includes('外部修订')})()"))
        evaluate("document.querySelector('#reading-window-stop').click()")
        # A protected draft keeps its higher-priority update notice. Require the
        # server-acknowledged stop message, not the draft-dependent status title.
        check("停止新队列", wait("document.querySelector('#reading-window-stop').hidden && document.querySelector('#reading-window-pages').textContent.includes('已停止后续排队')"))
        http("/__qa/release", {})
        time.sleep(1.4)
        check("停止后无旧队列继续发送", [item["pageNumber"] for item in calls()] == [30, 42, 43])
        evaluate("window.confirm=()=>true")
        check("回到已在旧窗口完成的30", jump(30))
        check("旧窗口完成页不受PENDING缓存遮挡", wait("document.querySelector('#paper').textContent.includes('这是第 30 页')"))
        check("读取完成页不重复识别", len(calls()) == 3)

        driver.call("Emulation.setDeviceMetricsOverride", {"width": 390, "height": 844, "deviceScaleFactor": 1, "mobile": True}, session=session)
        time.sleep(.3)
        check("390px无横向溢出", evaluate("document.documentElement.scrollWidth<=innerWidth"))
        check("手机随读入口可见且触控合格", evaluate("(()=>{const r=document.querySelector('#reading-window-open').getBoundingClientRect();return r.width>=44&&r.height>=44&&r.right<=innerWidth})()"))
        driver.screenshot(session, os.path.join(OUT, "mobile-reading.png"))
        check("页面缓存有界", evaluate("(async()=>{const {state,PAGE_CACHE_LIMIT}=await import('/store.js');return state.pageCache.size<=PAGE_CACHE_LIMIT&&PAGE_CACHE_LIMIT<=20})()"))
        driver.navigate(session, SERVER)
        check("刷新恢复书架", wait("!!document.querySelector('#reading-window-open')"))
        time.sleep(1.3)
        check("刷新不自动恢复云识别", len(calls()) == 3)
        check("没有JS未处理异常", not any(e.get("method") == "Runtime.exceptionThrown" for e in driver.events))
        print("OVERALL PASS", flush=True)
    finally:
        if results and not results[-1]["pass"]:
            try:
                driver.screenshot(session, os.path.join(OUT, "failure.png"))
                events = [event for event in driver.events if event.get("method") == "Runtime.exceptionThrown"]
                with open(os.path.join(OUT, "failure-errors.json"), "w", encoding="utf-8") as output:
                    json.dump(events, output, ensure_ascii=False, indent=2)
            except Exception:
                pass
        with open(os.path.join(OUT, "results.json"), "w", encoding="utf-8") as output:
            json.dump(results, output, ensure_ascii=False, indent=2)
        driver.close()


if __name__ == "__main__":
    main()
