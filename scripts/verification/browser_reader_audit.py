"""Synthetic local browser audit. Serves only fixtures/static assets; rejects all writes.
Requires Playwright + a local Chromium binary. No real book or cloud model is used.
"""
from __future__ import annotations
import functools
import json
import os
from pathlib import Path
import re
import shutil
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(os.environ.get('READER_AUDIT_OUT', ROOT / 'test-results/reader-browser'))
BOOK = '11111111-1111-1111-1111-111111111111'
META = {'id': BOOK, 'title': '合成审查书', 'filename': 'synthetic.pdf', 'totalPages': 12,
        'processedPages': 12, 'reviewedPages': 0, 'archived': False}
TRACE, WRITES = [], []
COUNTERS = {'reads': 0, 'peak': 0}
LOCK = threading.Lock()

def snapshot(n: int):
    if n not in (3, 8): return None
    return {'bookId': BOOK, 'pageNumber': n, 'attemptId': 'synthetic-attempt-' + str(n),
            'snapshotVersion': 4, 'startedAt': '2026-01-01T00:00:00Z', 'lifecycle': 'RUNNING',
            'stage': 'REVIEW' if n == 3 else 'OCR', 'canRead': n == 3,
            'contentAvailability': 'OCR_READABLE' if n == 3 else 'ORIGINAL_ONLY',
            'units': {'kind': 'REVIEW_CHUNK', 'total': 4, 'succeeded': 2 if n == 3 else 0}}

def page_data(n: int):
    block = {'id': 'b' + str(n), 'type': 'text', 'order': 0, 'bbox': [.1, .2, .8, .2],
             'writingMode': 'horizontal-tb', 'original': f'第{n}页合成正文。阅读内容优先呈现，目录和邻页在后续加载。',
             'simplified': f'第{n}页合成正文。阅读内容优先呈现，目录和邻页在后续加载。',
             'confidence': .95, 'uncertain': False, 'reviewed': False, 'source': 'ocr', 'sourceIds': ['b' + str(n)], 'issues': []}
    return {'pageNumber': n, 'width': 600, 'height': 800, 'status': 'READY', 'provider': 'native',
            'blocks': [block], 'sourceRecords': [block], 'warnings': [], 'reviewed': False, 'revision': 1,
            'processing': snapshot(n), 'issueImages': {}}

class Handler(SimpleHTTPRequestHandler):
    def log_message(self, *_): pass
    def send_json(self, value, code=200):
        body = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json;charset=utf-8')
        self.send_header('Cache-Control', 'no-store'); self.send_header('Content-Length', str(len(body))); self.end_headers()
        try: self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError): pass
    def do_POST(self):
        WRITES.append(self.path); self.send_json({'message': 'Synthetic test forbids writes/cloud calls'}, 403)
    do_PUT = do_POST
    do_PATCH = do_POST
    do_DELETE = do_POST
    def do_GET(self):
        path = self.path.split('?')[0]
        if not path.startswith('/api/'): return super().do_GET()
        with LOCK: TRACE.append({'path': path, 'event': 'start', 't': time.monotonic()})
        base = '/api/books/' + BOOK
        if path == '/api/config':
            data = {'defaultProvider': 'paddle-aistudio', 'fallbackEnabled': False, 'maxUploadMb': 300,
                    'providers': [{'id': 'paddle-aistudio', 'label': '合成离线夹具', 'available': False, 'reason': '不调用云服务'}],
                    'ocrChannels': [], 'qwenAssist': {'configured': False, 'assistEnabled': False, 'model': 'synthetic'},
                    'capabilities': {'schemaVersion': 2, 'presentationV2': False}}
        elif path == '/api/books': data = [META]
        elif path == base: data = META
        elif path == base + '/job': data = {'status': 'IDLE', 'total': 0, 'completed': 0, 'errors': []}
        elif path == base + '/outline': time.sleep(1.5); data = []
        elif path == base + '/pages':
            time.sleep(1.5)
            data = [{'pageNumber': n, 'status': 'READY', 'blockCount': 1, 'uncertainCount': 0, 'title': f'第{n}页', 'reviewed': False} for n in range(1, 13)]
        elif (match := re.fullmatch(re.escape(base) + r'/pages/(\d+)/progress', path)):
            data = {'processing': snapshot(int(match.group(1)))}
        elif (match := re.fullmatch(re.escape(base) + r'/pages/(\d+)', path)):
            with LOCK:
                COUNTERS['reads'] += 1; COUNTERS['peak'] = max(COUNTERS['peak'], COUNTERS['reads'])
            time.sleep(.18)
            data = page_data(int(match.group(1)))
            with LOCK: COUNTERS['reads'] -= 1
        else: data = {}
        self.send_json(data)
        with LOCK: TRACE.append({'path': path, 'event': 'end', 't': time.monotonic()})

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Handler, directory=str(ROOT / 'src/main/resources/static')))
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    base = 'http://127.0.0.1:' + str(server.server_port)
    results, errors = [], []
    def check(name, ok, detail=''):
        results.append({'name': name, 'pass': bool(ok), 'detail': detail})
        if not ok: raise AssertionError(name + ': ' + str(detail))
    try:
        with sync_playwright() as pw:
            executable = os.environ.get('CHROMIUM') or shutil.which('chromium') or shutil.which('google-chrome')
            browser = pw.chromium.launch(executable_path=executable, headless=True, args=['--no-sandbox', '--disable-dev-shm-usage'])
            page = browser.new_page(viewport={'width': 1280, 'height': 900}, reduced_motion='reduce')
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.add_init_script(f"localStorage.setItem('paper-studio:{BOOK}:reading', JSON.stringify({{page:3}}));")
            page.goto(base, wait_until='domcontentloaded')
            page.wait_for_function("document.querySelector('#book-select').options.length > 1")
            page.select_option('#book-select', BOOK, force=True)
            page.locator('#paper').get_by_text('第3页合成正文。阅读内容优先呈现，目录和邻页在后续加载。').first.wait_for()
            page.locator('#page-processing-progress').wait_for()
            check('current_page_before_slow_metadata', not any(x['path'] == '/api/books/' + BOOK + '/pages' and x['event'] == 'end' for x in TRACE))
            check('percentage_immediately_after_saved', page.eval_on_selector('#page-save-status', "el=>el.nextElementSibling.id==='page-processing-progress'"))
            check('event_driven_percentage', '77%' in page.locator('#page-processing-progress').inner_text())
            page.screenshot(path=str(OUT / 'reader-desktop.png'), full_page=True)
            page.wait_for_timeout(1800)
            check('bounded_readonly_prefetch', 1 < COUNTERS['peak'] <= 3, COUNTERS['peak'])
            page.locator('#page-jump').fill('8'); page.locator('#page-jump').press('Enter')
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第8页合成正文')")
            page.wait_for_function("document.querySelector('#page-processing-progress').textContent.includes('10%')")
            check('page_change_clears_old_progress', '77%' not in page.locator('#page-processing-progress').inner_text())
            page.set_viewport_size({'width': 390, 'height': 844})
            page.screenshot(path=str(OUT / 'reader-mobile.png'), full_page=True)
            check('mobile_progress_stays_right_of_saved', page.evaluate('''() => {
              const saved = document.querySelector('#page-save-status').getBoundingClientRect();
              const progress = document.querySelector('#page-processing-progress').getBoundingClientRect();
              return progress.left >= saved.right && Math.abs((saved.top + saved.bottom) / 2 - (progress.top + progress.bottom) / 2) <= 2;
            }'''))
            check('no_mobile_horizontal_overflow', page.evaluate('document.documentElement.scrollWidth <= window.innerWidth + 1'))
            check('no_task_overlay_on_open', page.locator('#drawer-scrim').is_hidden())
            check('no_automatic_paid_writes', not WRITES, WRITES)
            check('no_javascript_errors', not errors, errors)
            browser.close()
    finally:
        server.shutdown(); server.server_close()
        report = {'status': 'PASS' if len(results) == 10 and all(x['pass'] for x in results) else 'FAIL_OR_BLOCKED', 'fixture': 'synthetic-only; not real OCR latency or accuracy', 'checks': results, 'javascriptErrors': errors,
                  'peakPageJsonReads': COUNTERS['peak'], 'writes': WRITES,
                  'trace': [{**x, 't': round(x['t'] - TRACE[0]['t'], 3)} for x in TRACE] if TRACE else []}
        (OUT / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'checks': results, 'javascriptErrors': errors}, ensure_ascii=False, indent=2))

if __name__ == '__main__': main()
