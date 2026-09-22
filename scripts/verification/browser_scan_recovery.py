"""Real reader renderer/DOM with synthetic responses; no real manuscript or cloud call."""
import json
import mimetypes
import os
from pathlib import Path
import shutil
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
STATIC = ROOT / 'src/main/resources/static'
OUT = ROOT / 'test-results/scan-recovery'
HTML = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="icon" href="data:,"><link rel="stylesheet" href="/styles.css">
<body style="height:auto;display:block"><div id="quality"></div><article id="paper" style="width:100%;max-width:900px"></article>
<script src="/reading-layout.js"></script><script type="module">
import { renderPaper, qualityOf } from '/reader.js';
window.draw=(overrides={})=>{
 const page={status:'READY',pageNumber:1,width:600,height:800,provider:'paddle-aistudio',blocks:[],sourceRecords:[],warnings:[],...overrides};
 document.querySelector('#quality').textContent=qualityOf(page).label;
 renderPaper(document.querySelector('#paper'),{book:{id:'fixture'},page,blocks:page.blocks,view:'reading',script:'original',fontSize:20,lineHeight:1.8});
};window.draw();</script></body></html>'''
SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="800"><rect width="600" height="800" fill="white"/><path d="M20 40H580M20 100H580M40 20V780M100 20V780M140 60l20 20m-10-15v30m-10-5h25" stroke="black"/><ellipse cx="300" cy="500" rx="190" ry="160" fill="#333"/></svg>'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    checks, errors, unexpected = [], [], []
    def route(request):
        parsed = urlparse(request.request.url)
        path = parsed.path
        if parsed.hostname != 'scan-recovery.test' or request.request.method != 'GET':
            unexpected.append(request.request.method + ' ' + str(parsed.hostname)); request.abort(); return
        if path == '/': request.fulfill(content_type='text/html', body=HTML); return
        if path.startswith('/api/books/fixture/pages/1/'):
            request.fulfill(content_type='image/svg+xml', body=SVG); return
        target = (STATIC / path.lstrip('/')).resolve()
        if not target.is_relative_to(STATIC.resolve()) or not target.is_file():
            unexpected.append(path); request.abort(); return
        request.fulfill(content_type=mimetypes.guess_type(str(target))[0] or 'application/octet-stream', body=target.read_bytes())
    def check(name, ok):
        checks.append({'name': name, 'pass': bool(ok)})
        assert ok, name
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(executable_path=os.environ.get('CHROMIUM') or shutil.which('chromium') or shutil.which('google-chrome'), headless=True, args=['--no-sandbox'])
            page = browser.new_page(viewport={'width': 1200, 'height': 900})
            page.route('**/*', route)
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto('http://scan-recovery.test/', wait_until='networkidle')
            page.wait_for_function('typeof window.draw === "function"')
            check('legacy_empty_is_uncertain_not_blank', '不能据此判定' in page.locator('#paper').inner_text())
            check('legacy_empty_keeps_visible_source', page.locator('#paper .empty-reading-original img').is_visible())
            check('legacy_empty_source_is_loaded', page.locator('#paper img').evaluate('el=>el.complete && el.naturalWidth>0'))
            page.evaluate("draw({provider:'paddle-aistudio+blank'})")
            check('legacy_blank_marker_is_not_trusted', '不能据此判定' in page.locator('#paper').inner_text())
            page.evaluate("draw({provider:'paddle-aistudio+blank',warnings:['[BLANK_EVIDENCE_V2] evidence']})")
            check('new_image_evidence_displays_near_blank', '经图像检查为近空白' in page.locator('#paper').inner_text())
            block = {'id':'folio','type':'page-number','order':0,'bbox':[.4,.02,.1,.03],'original':'12','simplified':'12'}
            page.evaluate('(b)=>draw({blocks:[b],sourceRecords:[b]})', block)
            check('filtered_content_does_not_become_blank', '未进入阅读排版' in page.locator('#paper').inner_text())
            check('filtered_content_still_preserves_source', page.locator('#paper .empty-reading-original img').is_visible())
            block = {'id':'piece','type':'text','order':0,'bbox':[.2,.2,.5,.2],'original':'原图局部转录仍须逐字核对。','simplified':'原图局部转录仍须逐字核对。','issues':[],'uncertain':True}
            page.evaluate('(b)=>draw({blocks:[b],sourceRecords:[b],warnings:["[OCR_RECOVERY_PARTIAL] evidence"]})', block)
            check('partial_recovery_text_is_visible', '原图局部转录' in page.locator('#paper').inner_text())
            check('partial_recovery_warning_is_visible', page.locator('#quality').inner_text() == '仅恢复部分文字')
            page.evaluate("draw({status:'FAILED',error:'OCR_EMPTY_UNRESOLVED：需对照原稿'})")
            page.get_by_role('button', name='查看原稿大图').click()
            check('failed_recovery_still_exposes_source', page.locator('.reader-failed-raw img').is_visible())
            page.set_viewport_size({'width':390,'height':844})
            page.evaluate('draw()')
            check('mobile_fallback_has_no_horizontal_overflow', page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
            page.screenshot(path=str(OUT / 'uncertain-scan-mobile.png'), full_page=True)
            check('no_javascript_errors', not errors)
            check('no_external_or_mutating_requests', not unexpected)
            browser.close()
    finally:
        result = {'scope':'actual reader renderer and DOM; synthetic data only, no OCR', 'checks':checks, 'javascriptErrors':errors, 'unexpectedRequests':unexpected}
        (OUT / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
