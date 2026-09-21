#!/usr/bin/env python3
"""自控 Chrome 的最小 CDP 驱动：多 tab、求值、截图、console/网络事件。"""
import base64
import os
import json
import subprocess
import threading
import time
import urllib.request

import websocket


class Driver:
    def __init__(self, port, chrome_bin="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                 profile="/tmp/a1-chrome-profile", extra_args=None):
        self.port = port
        args = [chrome_bin, "--headless=new", "--disable-gpu",
                f"--remote-debugging-port={port}", f"--user-data-dir={profile}",
                "--allow-file-access-from-files", "--no-first-run", "--no-default-browser-check", "--remote-allow-origins=*",
                "about:blank"]
        if extra_args:
            args.extend(extra_args)
        self.proc = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.base = f"http://127.0.0.1:{port}"
        for _ in range(100):
            try:
                urllib.request.urlopen(self.base + "/json/version", timeout=2)
                break
            except Exception:
                time.sleep(0.3)
        self.lock = threading.Lock()
        self.seq = 0
        self.pending = {}
        self.events = []
        self._ws = None

    def _browser_ws(self):
        with urllib.request.urlopen(self.base + "/json/version", timeout=5) as r:
            return json.load(r)["webSocketDebuggerUrl"]

    def connect(self):
        self._ws = websocket.create_connection(self._browser_ws(), timeout=60)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        while True:
            try:
                m = json.loads(self._ws.recv())
            except Exception:
                break
            if "id" in m:
                with self.lock:
                    q = self.pending.pop(m["id"], None)
                if q is not None:
                    q.append(m)
            else:
                with self.lock:
                    self.events.append(m)

    def call(self, method, params=None, session=None, timeout=30):
        with self.lock:
            self.seq += 1
            i = self.seq
            q = []
            self.pending[i] = q
        msg = {"id": i, "method": method}
        if params:
            msg["params"] = params
        if session:
            msg["sessionId"] = session
        self._ws.send(json.dumps(msg))
        end = time.time() + timeout
        while time.time() < end:
            with self.lock:
                if q:
                    m = q.pop(0)
                    if "error" in m:
                        raise RuntimeError(m["error"])
                    return m.get("result", {})
            time.sleep(0.02)
        raise TimeoutError(method)

    def targets(self, type_filter="page"):
        with urllib.request.urlopen(self.base + "/json/list", timeout=5) as r:
            items = json.load(r)
        return [t for t in items if t["type"] == type_filter]

    def new_tab(self, url="about:blank"):
        r = self.call("Target.createTarget", {"url": url})
        time.sleep(0.3)
        return r["targetId"]

    def attach(self, target_id):
        return self.call("Target.attachToTarget", {"targetId": target_id, "flatten": True})["sessionId"]

    def eval(self, session, expr, await_promise=True, timeout=30):
        r = self.call("Runtime.evaluate",
                      {"expression": expr, "awaitPromise": await_promise, "returnByValue": True},
                      session=session, timeout=timeout)
        res = r.get("result", {})
        if res.get("subtype") == "error" or res.get("type") == "error":
            raise RuntimeError(res.get("description", res))
        return res.get("value")

    def navigate(self, session, url):
        return self.call("Page.navigate", {"url": url}, session=session)

    def screenshot(self, session, path):
        self.call("Page.enable", {}, session=session)
        r = self.call("Page.captureScreenshot", {"format": "png"}, session=session)
        with open(path, "wb") as f:
            f.write(base64.b64decode(r["data"]))

    def drain_events(self):
        with self.lock:
            ev, self.events = self.events, []
        return ev

    def close(self):
        try:
            self.proc.terminate()
        except Exception:
            pass
