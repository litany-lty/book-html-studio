"""Real browser + actual reader assets; synthetic API only, all external requests blocked.
Run with an installed Playwright and Chromium. No real books, credentials or paid calls.
"""
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
STATIC = ROOT / 'src/main/resources/static'
OUT = Path(os.environ.get('FOCUS_TEST_OUT', ROOT / 'test-results/focus-reading'))
BOOK = '11111111-1111-1111-1111-111111111111'
TOTAL = 8
META = {'id': BOOK, 'title': '专注阅读验收', 'filename': 'fixture.pdf', 'totalPages': TOTAL,
        'processedPages': 6, 'reviewedPages': 0, 'archived': False}


def page_data(n):
    blocks = [{'id': f'p{n}-b{i}', 'type': 'heading' if i == 0 else 'text', 'order': i,
               'bbox': [.1, .05 + i * .02, .8, .015], 'writingMode': 'horizontal-tb',
               'original': f'第{n}页 第{i}段。' + ('山川有清音，书页有来处。专注阅读保留正文与阅读位置。' * (1 if i == 0 else 4)),
               'confidence': .98, 'reviewed': False, 'uncertain': False, 'source': 'ocr', 'sourceIds': [f'p{n}-b{i}'], 'issues': []}
              for i in range(14)]
    for b in blocks:
        b['simplified'] = b['original']
    status = 'PENDING' if n == 6 else 'FAILED' if n == 7 else 'READY'
    return {'pageNumber': n, 'width': 600, 'height': 800, 'status': status, 'provider': 'fixture',
            'blocks': blocks if status == 'READY' else [], 'sourceRecords': blocks if status == 'READY' else [],
            'warnings': [], 'reviewed': False, 'revision': 1, 'issueImages': {}}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    checks, errors, writes, external = [], [], [], []
    def check(name, value):
        checks.append({'name': name, 'pass': bool(value)})
        assert value, name
    def respond(route):
        url = urlparse(route.request.url)
        if url.hostname != 'focus-reader.test':
            external.append(url.hostname); route.abort(); return
        path = url.path
        if route.request.method != 'GET':
            writes.append(path); route.fulfill(status=403, json={'message': 'No writes in fixture'}); return
        if path.startswith('/api/'):
            base = '/api/books/' + BOOK
            if path == '/api/config':
                value = {'defaultProvider': 'paddle-aistudio', 'maxUploadMb': 300, 'fallbackEnabled': False,
                         'providers': [{'id': 'paddle-aistudio', 'label': '未启用', 'available': False}], 'ocrChannels': [],
                         'qwenAssist': {'configured': False, 'assistEnabled': False}, 'capabilities': {'schemaVersion': 2}}
            elif path == '/api/books': value = [META]
            elif path == base or path == base + '/metadata': value = META
            elif path == base + '/pages':
                value = [{'pageNumber': n, 'status': page_data(n)['status'], 'blockCount': 14, 'uncertainCount': 0, 'reviewed': False} for n in range(1, TOTAL+1)]
            elif path == base + '/outline': value = []
            elif path == base + '/job': value = {'status': 'IDLE', 'completed': 0, 'total': 0, 'errors': []}
            elif match := re.fullmatch(re.escape(base) + r'/pages/(\d+)', path): value = page_data(int(match[1]))
            elif '/image' in path or '/figures/' in path:
                route.fulfill(content_type='image/svg+xml', body='<svg xmlns="http://www.w3.org/2000/svg" width="600" height="800"><text x="50" y="60">Source fixture</text></svg>'); return
            else: value = {}
            route.fulfill(json=value); return
        target = (STATIC / path.lstrip('/')).resolve() if path != '/' else STATIC / 'index.html'
        if not target.is_relative_to(STATIC.resolve()) or not target.is_file(): route.fulfill(status=404, body='Not found'); return
        mime = mimetypes.guess_type(str(target))[0] or 'application/octet-stream'
        route.fulfill(content_type=mime, body=target.read_bytes())

    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(executable_path=os.environ.get('CHROMIUM') or shutil.which('chromium') or shutil.which('google-chrome'), headless=True, args=['--no-sandbox'])
            context = browser.new_context(viewport={'width': 1440, 'height': 1000}, reduced_motion='reduce')
            context.route('**/*', respond)
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto('http://focus-reader.test/', wait_until='networkidle')
            page.select_option('#book-select', BOOK)
            page.wait_for_selector('#paper .reading-flow')
            page.wait_for_function("document.querySelector('link[href$=\"focus-reading.css\"]')?.sheet")
            check('entry_in_primary_toolbar', page.locator('.reader-primary-actions #focus-toggle').count() == 1)
            page.click('[data-view="original"]')
            page.click('#focus-toggle')
            page.wait_for_selector('body.focus-reading #paper .reading-flow')
            check('parsed_reading_not_source_image', page.locator('#paper .original-frame').count() == 0)
            check('chrome_hidden', all(page.locator(s).is_hidden() for s in ['.topbar', '#job-panel', '.reader-toolbar', '.page-head', '.pagination', '#toc-panel', '#review-panel']))
            check('full_viewport_and_bottom_progress', page.evaluate('''() => {
              const r=document.querySelector('#reader').getBoundingClientRect(), p=document.querySelector('#reading-progress').getBoundingClientRect();
              return r.top===0 && Math.abs(r.height-innerHeight)<=1 && Math.abs(p.bottom-innerHeight)<=1 && p.width===innerWidth;
            }'''))
            page.screenshot(path=str(OUT / 'desktop-focus.png'))
            check('first_page_previous_disabled', page.locator('#focus-prev-page').is_disabled())
            page.click('#focus-next-page')
            page.wait_for_function("document.querySelector('#page-jump').value==='2' && document.querySelector('#paper').textContent.includes('第2页')")
            check('right_region_turns_next', True)
            page.click('#focus-prev-page')
            page.wait_for_function("document.querySelector('#page-jump').value==='1' && document.querySelector('#paper').textContent.includes('第1页')")
            check('left_region_turns_previous', True)
            page.locator('#reader').focus(); page.keyboard.press('ArrowRight')
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第2页')")
            check('arrow_keys_turn_pages', True)
            page.evaluate('''() => { const range=document.createRange();range.selectNodeContents(document.querySelector('#paper .reading-flow p'));getSelection().removeAllRanges();getSelection().addRange(range); }''')
            page.click('#focus-next-page')
            check('selected_text_not_accidentally_turned', page.locator('#page-jump').input_value() == '2')
            page.evaluate('getSelection().removeAllRanges()')
            # Long press and a drag in a side region are not page turns.
            region = page.locator('#focus-next-page').bounding_box()
            x, y = region['x']+region['width']/2, region['y']+150
            page.mouse.move(x, y); page.mouse.down(); page.wait_for_timeout(550); page.mouse.up()
            check('long_press_not_a_turn', page.locator('#page-jump').input_value() == '2')
            page.mouse.move(x, y); page.mouse.down(); page.mouse.move(x, y+60, steps=5); page.mouse.up()
            check('drag_not_a_turn', page.locator('#page-jump').input_value() == '2')
            # Original jump form is the common route, including draft confirmation.
            page.evaluate("import('/store.js').then(({state})=>{state.dirty=true;})")
            page.once('dialog', lambda d: d.dismiss())
            page.click('#focus-next-page')
            check('cancelled_dirty_navigation_preserves_page', page.locator('#page-jump').input_value() == '2')
            page.evaluate("import('/store.js').then(({state})=>{state.dirty=false;})")
            page.locator('#reader').focus(); page.keyboard.press('Escape')
            page.wait_for_selector('body:not(.focus-reading) #paper .original-frame')
            check('escape_restores_previous_view_at_current_page', page.locator('#page-jump').input_value() == '2' and page.locator('#focus-toggle').evaluate('el=>el===document.activeElement'))
            page.click('#focus-toggle')
            page.locator('#reading-progress-range').evaluate("el=>{el.value='8';el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}")
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第8页')")
            check('existing_progress_scrubber_works_and_last_page_bounded', page.locator('#focus-next-page').is_disabled())
            page.click('#focus-prev-page')
            page.wait_for_selector('#focus-page-empty')
            check('failed_page_not_fabricated', '解析未完成' in page.locator('#focus-page-empty').inner_text())
            page.click('#focus-prev-page')
            page.wait_for_function("document.querySelector('#focus-page-empty').textContent.includes('尚未解析')")
            check('unparsed_page_still_navigable', page.locator('#reading-progress-range').is_enabled())
            page.click('#focus-prev-page')
            page.wait_for_selector('#paper .reading-flow')
            # Center double click remains normal text selection, not navigation.
            page.locator('#paper .reading-flow p').first.dblclick()
            check('center_double_click_selects_without_turning', page.locator('#page-jump').input_value() == '5' and page.evaluate('getSelection().toString().length > 0'))
            page.evaluate('getSelection().removeAllRanges()')
            page.locator('#reading-progress-range').focus(); page.keyboard.press('ArrowLeft')
            check('slider_arrow_not_hijacked_by_focus', page.locator('#reading-progress-range').input_value() == '4')
            page.wait_for_selector('#paper .reading-flow')
            page.evaluate("document.querySelector('#reading-options').showModal()")
            page.keyboard.press('Escape')
            check('escape_closes_dialog_before_focus', page.locator('body.focus-reading').count() == 1 and page.locator('#reading-options').is_hidden())
            page.set_viewport_size({'width': 390, 'height': 844})
            page.wait_for_timeout(100)
            check('mobile_no_horizontal_overflow', page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
            check('mobile_footer_and_gutters_do_not_cover_text', page.evaluate('''() => {
              const b=document.querySelector('#paper .reading-flow p').getBoundingClientRect(), l=document.querySelector('#focus-prev-page').getBoundingClientRect(), r=document.querySelector('#focus-next-page').getBoundingClientRect(), f=document.querySelector('#reading-progress').getBoundingClientRect();
              return l.right<=b.left && r.left>=b.right && Math.abs(l.bottom-f.top)<=1 && f.bottom<=innerHeight+1;
            }'''))
            page.screenshot(path=str(OUT / 'mobile-focus.png'))
            page.locator('#reader').evaluate('el=>el.scrollTop=el.scrollHeight')
            check('last_line_clear_of_footer', page.evaluate("document.querySelector('#paper .reading-flow').getBoundingClientRect().bottom <= document.querySelector('#reading-progress').getBoundingClientRect().top"))
            page.locator('#reader').evaluate('el=>el.scrollTop=0')
            page.evaluate("document.documentElement.dataset.theme='night'")
            check('night_theme_paper_retained', page.evaluate("getComputedStyle(document.querySelector('#reading-progress')).backgroundColor===getComputedStyle(document.querySelector('#workspace')).backgroundColor"))
            page.screenshot(path=str(OUT / 'mobile-focus-night.png'))
            page.click('#focus-exit')
            page.wait_for_selector('body:not(.focus-reading) .reader-toolbar')
            check('visible_exit_restores_workspace', page.locator('.topbar').is_visible())
            # Reopen a legacy saved focus preference while the saved view is original.
            page.evaluate(f"localStorage.setItem('paper-studio:{BOOK}:reading', JSON.stringify({{page:3, view:'original', focus:true}}))")
            page.reload(wait_until='networkidle')
            page.select_option('#book-select', BOOK)
            page.wait_for_selector('body.focus-reading #paper .reading-flow')
            check('restored_focus_forces_parsed_view', page.locator('#focus-exit').is_visible())
            page.click('#focus-exit')
            page.select_option('#book-select', '')
            check('unselected_book_leaves_workspace_visible', page.locator('.topbar').is_visible() and page.locator('#focus-exit').is_hidden())
            check('no_javascript_errors', not errors)
            check('no_paid_or_mutating_requests', not writes)
            check('no_external_requests', not external)
            browser.close()
    finally:
        report = {'scope': 'actual application frontend, synthetic HTTP API; not real backend/OCR', 'checks': checks, 'pageErrors': errors, 'writes': writes, 'external': external}
        (OUT/'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
