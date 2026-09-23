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
                         'providers': [{'id': 'paddle-aistudio', 'label': '合成已配置通道（未授权）', 'available': True}], 'ocrChannels': [],
                         'qwenAssist': {'configured': False, 'assistEnabled': False}, 'capabilities': {'schemaVersion': 2}}
            elif path == '/api/books': value = [META]
            elif path in (base, base + '/metadata', base + '/reader'): value = META
            elif path == base + '/pages':
                value = [{'pageNumber': n, 'status': page_data(n)['status'], 'blockCount': 14, 'uncertainCount': 0, 'reviewed': False} for n in range(1, TOTAL+1)]
            elif path == base + '/outline': value = []
            elif path == base + '/job': value = {'status': 'IDLE', 'completed': 0, 'total': 0, 'errors': []}
            elif path.startswith(base + '/reader/pages/') and path.endswith('/progress'):
                n = int(path.split('/')[-2]); value = {'pageNumber':n, 'status':page_data(n)['status'], 'revision':1, 'processing':None}
            elif match := re.fullmatch(re.escape(base) + r'/(?:reader/)?pages/(\d+)', path): value = page_data(int(match[1]))
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
            context = browser.new_context(viewport={'width': 1440, 'height': 1000}, reduced_motion='reduce', has_touch=True)
            context.route('**/*', respond)
            page = context.new_page()
            def edge_point(direction):
                bounds = page.locator('#reader').bounding_box()
                width = page.locator('#reader').evaluate('el=>el.clientWidth')
                return (bounds['x'] + (20 if direction < 0 else width - 20), bounds['y'] + 180)
            def edge_click(direction, touch=False):
                x, y = edge_point(direction)
                (page.touchscreen.tap if touch else page.mouse.click)(x, y)
                page.wait_for_timeout(340)  # Includes the word-selection settlement window.
            def no_turn_after_wait():
                page.wait_for_timeout(340)
                return page.locator('#page-jump').input_value()
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
            geometry = page.evaluate("""() => ({reader:document.querySelector('#reader').getBoundingClientRect().toJSON(),
              footer:document.querySelector('#reading-progress').getBoundingClientRect().toJSON(),
              viewport:[innerWidth,innerHeight],devicePixelRatio,bodyClass:document.body.className})""")
            (OUT/'initial-geometry.json').write_text(json.dumps(geometry,indent=2)+'\n',encoding='utf-8')
            page.screenshot(path=str(OUT / 'initial-focus.png'))
            details = page.evaluate("""() => ({elements:['body','#workspace','#reader','#reading-progress'].map(selector=>{
              const el=document.querySelector(selector),s=getComputedStyle(el); return {selector,rect:el.getBoundingClientRect().toJSON(),
                height:s.height,display:s.display,position:s.position,left:s.left,right:s.right,flex:s.flex,padding:s.padding,inline:el.getAttribute('style'),
                matches:el.matches('body.focus-reading *')};}),sheets:[...document.styleSheets].map(s=>({href:s.href,rules:s.cssRules.length}))})""")
            (OUT/'layout-details.json').write_text(json.dumps(details,indent=2)+'\n',encoding='utf-8')
            check('full_viewport_and_bottom_progress', page.evaluate('''() => {
              const r=document.querySelector('#reader').getBoundingClientRect(), p=document.querySelector('#reading-progress').getBoundingClientRect();
              return r.top===0 && Math.abs(r.height-innerHeight)<=1 && Math.abs(p.bottom-innerHeight)<=1 && p.width===innerWidth;
            }'''))
            check('no_visible_arrows_or_side_overlay', page.evaluate("""() => [...document.querySelectorAll('.focus-page-zone')].every(el=>{
              const r=el.getBoundingClientRect(); return r.width<=1 && r.height<=1 && getComputedStyle(el).pointerEvents==='none' && !/[‹›]/.test(el.textContent);
            })"""))
            page.screenshot(path=str(OUT / 'desktop-focus.png'))
            check('first_page_previous_disabled', page.locator('#focus-prev-page').is_disabled())
            edge_click(1)
            page.wait_for_function("document.querySelector('#page-jump').value==='2' && document.querySelector('#paper').textContent.includes('第2页')")
            check('right_region_turns_next', True)
            edge_click(-1)
            page.wait_for_function("document.querySelector('#page-jump').value==='1' && document.querySelector('#paper').textContent.includes('第1页')")
            check('left_region_turns_previous', True)
            page.locator('#reader').focus(); page.keyboard.press('ArrowRight')
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第2页')")
            check('arrow_keys_turn_pages', True)
            page.locator('#focus-next-page').focus(); page.keyboard.press('ArrowRight')
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第3页')")
            page.keyboard.press('ArrowLeft')
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第2页')")
            check('keyboard_accessible_navigation_controls_work', True)
            page.evaluate("import('/store.js').then(({state})=>{state.pageCache.clear(); for(let i=0;i<3;i++)document.querySelector('#focus-next-page').click();})")
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第5页')")
            check('rapid_clicks_keep_latest_page', page.locator('#page-jump').input_value() == '5')
            page.evaluate("for(let i=0;i<3;i++)document.querySelector('#focus-prev-page').click()")
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第2页')")
            page.evaluate('''() => { const range=document.createRange();range.selectNodeContents(document.querySelector('#paper .reading-flow p'));getSelection().removeAllRanges();getSelection().addRange(range); }''')
            edge_click(1)
            check('selected_text_not_accidentally_turned', page.locator('#page-jump').input_value() == '2')
            page.evaluate('getSelection().removeAllRanges()')
            # Long press and a drag in a side region are not page turns.
            x, y = edge_point(1)
            page.mouse.move(x, y); page.mouse.down(); page.wait_for_timeout(550); page.mouse.up()
            check('long_press_not_a_turn', no_turn_after_wait() == '2')
            page.mouse.move(x, y); page.mouse.down(); page.mouse.move(x, y+60, steps=5); page.mouse.up()
            check('drag_not_a_turn', no_turn_after_wait() == '2')
            page.mouse.move(x, y); page.mouse.down(); page.mouse.move(x, y+70, steps=5); page.mouse.move(x, y, steps=5); page.mouse.up()
            check('out_and_back_drag_not_a_turn', no_turn_after_wait() == '2')
            page.mouse.move(x, y); page.mouse.down()
            page.locator('#reader').evaluate('el=>el.scrollTop+=60')
            page.wait_for_timeout(50); page.mouse.up()
            check('scroll_during_tap_not_a_turn', no_turn_after_wait() == '2')
            page.locator('#reader').evaluate('el=>el.scrollTop=0')
            # Original jump form is the common route, including draft confirmation.
            page.evaluate("import('/store.js').then(({state})=>{state.dirty=true;})")
            page.once('dialog', lambda d: d.dismiss())
            edge_click(1)
            check('cancelled_dirty_navigation_preserves_page', page.locator('#page-jump').input_value() == '2')
            page.evaluate("import('/store.js').then(({state})=>{state.dirty=false;})")
            page.locator('#reader').focus(); page.keyboard.press('Escape')
            page.wait_for_selector('body:not(.focus-reading) #paper .original-frame')
            # Focus is restored on the next animation frame, after DOM replacement.
            page.wait_for_function("document.activeElement===document.querySelector('#focus-toggle')", timeout=3000)
            check('escape_restores_previous_view_at_current_page', page.locator('#page-jump').input_value() == '2' and page.locator('#focus-toggle').evaluate('el=>el===document.activeElement'))
            page.click('#focus-toggle')
            page.locator('#reading-progress-range').evaluate("el=>{el.value='8';el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}")
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第8页')")
            check('existing_progress_scrubber_works_and_last_page_bounded', page.locator('#focus-next-page').is_disabled())
            edge_click(-1)
            page.wait_for_selector('#focus-page-empty')
            check('failed_page_not_fabricated', '解析未完成' in page.locator('#focus-page-empty').inner_text())
            edge_click(-1)
            page.wait_for_function("document.querySelector('#focus-page-empty').textContent.includes('尚未解析')")
            check('unparsed_page_still_navigable', page.locator('#reading-progress-range').is_enabled())
            edge_click(-1)
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
            check('mobile_text_reclaims_both_side_columns', page.evaluate('''() => {
              const r=document.querySelector('#reader'), node=document.querySelector('#paper .reading-flow p'), p=node.getBoundingClientRect();
              return p.width>=r.clientWidth-26 && p.left>=12 && p.right<=innerWidth && parseFloat(getComputedStyle(node).fontSize)===20;
            }'''))
            check('edge_text_not_covered_by_transparent_button', page.evaluate('''() => {
              const p=document.querySelector('#paper .reading-flow p'), r=p.getBoundingClientRect();
              return p.contains(document.elementFromPoint(r.left+5,r.top+10)) && p.contains(document.elementFromPoint(r.right-5,r.top+10));
            }'''))
            check('mobile_progress_controls_visible_and_unoccluded', page.evaluate('''() => ['reading-progress-range', 'reading-progress-output', 'focus-exit'].every(id=>{
              const el=document.getElementById(id), r=el.getBoundingClientRect();
              const hit=document.elementFromPoint(r.left+r.width/2, r.top+r.height/2);
              return r.width>0 && r.height>0 && r.top>=0 && r.bottom<=innerHeight && r.left>=0 && r.right<=innerWidth && (hit===el || el.contains(hit));
            })'''))
            page.locator('#reader').focus()
            page.screenshot(path=str(OUT / 'mobile-focus.png'))
            edge_click(1, touch=True)
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第5页')")
            edge_click(-1, touch=True)
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第4页')")
            check('mobile_touch_regions_turn_pages', True)
            # Choose a real glyph within the left tap region; no overlay may block it.
            glyph = page.evaluate('''() => {
              const p=document.querySelector('#paper .reading-flow p'), walk=document.createTreeWalker(p,NodeFilter.SHOW_TEXT);
              for(let text; (text=walk.nextNode());) {
                if(!text.length) continue;
                const range=document.createRange();range.setStart(text,0);range.setEnd(text,1);
                const r=range.getBoundingClientRect();
                if(r.width>0) return {x:r.x+r.width/2,y:r.y+r.height/2};
              }
            }''')
            page.mouse.dblclick(glyph['x'],glyph['y'],delay=80)
            check('edge_double_click_selects_text_without_turning', no_turn_after_wait() == '4' and page.evaluate('getSelection().toString().length>0'))
            page.evaluate('getSelection().removeAllRanges()')
            page.mouse.click(glyph['x'],glyph['y'])
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第3页')")
            check('single_tap_on_edge_text_turns_page', True)
            # A pending edge tap is cancelled when the user scrolls immediately after it.
            x,y=edge_point(1)
            page.mouse.click(x,y)
            page.locator('#reader').evaluate('el=>el.scrollTop=120')
            check('scroll_cancels_pending_turn', no_turn_after_wait() == '3')
            page.locator('#reader').evaluate('''async el => {
                el.scrollTop=0;
                await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                if(el.scrollTop!==0) throw new Error('scroll reset did not settle');
            }''')
            edge_click(1)
            page.wait_for_function("document.querySelector('#paper').textContent.includes('第4页')")
            page.evaluate('''() => {
              const a=document.createElement('a');a.id='fixture-edge-link';a.href='#fixture-link';a.textContent='正文链接';
              document.querySelector('#paper .reading-flow p').prepend(a);
            }''')
            link=page.locator('#fixture-edge-link').bounding_box()
            page.mouse.click(link['x']+3,link['y']+8)
            check('edge_link_click_remains_native_and_does_not_turn', no_turn_after_wait() == '4' and page.evaluate("location.hash==='#fixture-link'"))
            page.locator('#fixture-edge-link').evaluate('el=>el.remove()')
            # Nothing reappears on hover; touch/pointer use never reveals the keyboard controls.
            x,y=edge_point(1);page.mouse.move(x,y)
            check('hover_does_not_reveal_side_buttons', page.evaluate("[...document.querySelectorAll('.focus-page-zone')].every(el=>getComputedStyle(el).clipPath!=='none' && getComputedStyle(el).pointerEvents==='none')"))
            page.locator('#reader').focus()
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
            # Seed before the new document initializes. Mutating live preferences here
            # races the previous page's legitimate debounced scroll-position save.
            page.add_init_script(f"localStorage.setItem('paper-studio:{BOOK}:reading', JSON.stringify({{page:3, view:'original', focus:true}}))")
            page.reload(wait_until='networkidle')
            page.select_option('#book-select', BOOK)
            try:
                page.wait_for_selector('body.focus-reading #paper .reading-flow')
            except Exception:
                state = page.evaluate("async()=>{const {state}=await import('/store.js');return {focus:state.focus,view:state.view,page:state.currentPage,status:state.page?.status,bodyClass:document.body.className,shellHidden:document.querySelector('#reader-shell').hidden,hasFlow:!!document.querySelector('#paper .reading-flow')};}")
                (OUT/'restore-failure.json').write_text(json.dumps(state,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
                page.screenshot(path=str(OUT/'restore-failure.png'))
                raise
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
