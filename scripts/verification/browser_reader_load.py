"""Actual frontend, controlled latency and synthetic books. No provider/write requests."""
import argparse, asyncio, json, mimetypes, os, re, shutil
from pathlib import Path
from urllib.parse import urlparse
from playwright.async_api import async_playwright, TimeoutError as BrowserTimeout

ROOT=Path(__file__).resolve().parents[2]
STATIC=ROOT/'src/main/resources/static'
OUT=ROOT/'test-results/reader-load-browser'
A='11111111-1111-4111-8111-111111111111'
B='22222222-2222-4222-8222-222222222222'
def book(b):
    return {'id':b,'title':'甲书' if b==A else '乙书','filename':'synthetic.pdf','totalPages':40,
            'processedPages':0,'reviewedPages':0,'archived':False,'createdAt':'2026-01-01T00:00:00Z'}
def page_data(b,n,revision):
    text=('甲书' if b==A else '乙书')+f' 第{n}页 版本{revision} '+('阅读内容，不应被其他书的响应覆盖。'*16)
    block={'id':f'p{n}-b','type':'text','order':0,'bbox':[.1,.1,.8,.1],'original':text,
           'simplified':text,'writingMode':'horizontal-tb','sourceIds':[f'p{n}-b'],'issues':[]}
    return {'pageNumber':n,'revision':revision,'status':'READY','width':600,'height':800,
            'provider':'fixture','blocks':[block],'sourceRecords':[block],'warnings':[],'issueImages':{}}
async def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--identity-only',action='store_true',help='Gate only the implemented response-identity contract; default verifies the complete navigation and refresh contract.')
    args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    checks=[];errors=[];writes=[];external=[]
    def check(name,ok):checks.append({'name':name,'pass':bool(ok)})
    async def wait(page,expression,timeout=900):
        try:await page.wait_for_function(expression,timeout=timeout);return True
        except BrowserTimeout:return False
    async with async_playwright() as pw:
        browser=await pw.chromium.launch(executable_path=os.environ.get('CHROMIUM') or shutil.which('google-chrome') or shutil.which('chromium'),headless=True)
        async def fixture(mode):
            ctx=await browser.new_context(viewport={'width':1100,'height':900})
            page=await ctx.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
            gate=asyncio.Event();state={'revision':1,'jobReads':0,'refreshing':False};requests=[]
            async def respond(route):
                u=urlparse(route.request.url);path=u.path;requests.append(path)
                if u.hostname!='reader-load.test':external.append(u.hostname);await route.abort();return
                if route.request.method!='GET':writes.append(path);await route.fulfill(status=403,json={'message':'writes forbidden'});return
                if path.startswith('/api/'):
                    if mode=='library' and path=='/api/books' and u.query!='view=reader':await gate.wait()
                    if mode=='config' and path=='/api/config':await gate.wait()
                    if mode in ('switch','switch-race') and path==f'/api/books/{B}/reader':await gate.wait()
                    if mode=='refresh' and state['refreshing'] and (path==f'/api/books/{A}' or path.endswith('/outline')):await gate.wait()
                    if mode=='page-race' and path==f'/api/books/{A}/reader/pages/2':await gate.wait()
                    if mode=='jump-draft' and path==f'/api/books/{A}/reader/pages/1':await gate.wait()
                    if mode in ('refresh-read','refresh-draft') and state['refreshing'] and path==f'/api/books/{A}/reader/pages/1':await gate.wait()
                    if mode=='metadata' and (path.endswith('/pages') or path.endswith('/outline')):await gate.wait()
                    if mode=='config-failure' and path=='/api/config':
                        await route.fulfill(status=503,json={'message':'synthetic unavailable configuration'});return
                    if mode=='switch-failure' and path==f'/api/books/{B}/reader':
                        await route.fulfill(status=503,json={'message':'synthetic unavailable book'});return
                    if path=='/api/config':value={'defaultProvider':'paddle-aistudio','providers':[], 'ocrChannels':[], 'qwenAssist':{},'capabilities':{'schemaVersion':2}}
                    elif path=='/api/books':value=[book(A),book(B)]
                    elif path=='/api/reading-policy':value={}
                    elif path.endswith('/job'):
                        state['jobReads']+=1
                        if mode in ('refresh','refresh-read','refresh-draft') and state['jobReads']==1:
                            await route.fulfill(status=503,json={'message':'test-only initial status failure'});return
                        value={'status':'IDLE','completed':0,'total':0,'errors':[]}
                    elif path.endswith('/outline'):value=[]
                    elif path.endswith('/progress'):value={'pageNumber':1,'revision':1,'status':'READY','processing':None}
                    elif path.endswith('/pages'):value=[]
                    elif m:=re.fullmatch(r'/api/books/([^/]+)/(?:reader/)?pages/(\d+)',path):
                        n=int(m[2]);value=page_data(m[1],n,state['revision'])
                        if mode=='wrongpage':value['pageNumber']=39
                    elif m:=re.fullmatch(r'/api/books/([^/]+)(?:/reader)?',path):
                        value=book(A if mode=='switch-mismatch' and m[1]==B else m[1])
                    else:value={}
                    try:await route.fulfill(json=value)
                    except Exception:
                        if not ctx.pages:return
                        # A cancelled read may have already detached its route.
                        if page.is_closed():return
                        raise
                    return
                target=(STATIC/path.lstrip('/')).resolve() if path!='/' else STATIC/'index.html'
                if not target.is_relative_to(STATIC.resolve()) or not target.is_file():await route.fulfill(status=404,body='missing');return
                await route.fulfill(content_type=mimetypes.guess_type(str(target))[0] or 'application/octet-stream',body=target.read_bytes())
            await ctx.route('**/*',respond)
            await page.goto('http://reader-load.test/',wait_until='domcontentloaded')
            return ctx,page,gate,state,requests
        try:
            for mode in ([] if args.identity_only else ['library','config']):
                ctx,page,gate,state,requests=await fixture(mode)
                check(mode+'_does_not_block_library_options',await wait(page,"document.querySelector('#book-select').options.length>1"))
                if mode=='config':
                    await page.select_option('#book-select',A)
                    check('slow_config_does_not_block_existing_page',await wait(page,"document.querySelector('#paper').textContent.includes('甲书')"))
                    check('reader_not_busy_after_body_loaded',await page.locator('#reader-shell').get_attribute('aria-busy')=='false')
                gate.set();await page.wait_for_function("document.querySelector('#book-select').options.length>1")
                await ctx.close()
            if not args.identity_only:
                ctx,page,gate,state,requests=await fixture('switch')
                await page.wait_for_function("document.querySelector('#book-select').options.length>1")
                await page.select_option('#book-select',A);await page.wait_for_selector('#paper .reading-flow')
                await page.select_option('#book-select',B)
                check('switch_hides_old_book_while_manifest_pending',await wait(page,"!document.querySelector('#paper').textContent.includes('甲书')"))
                await page.evaluate("document.querySelector('#page-jump').value='2';document.querySelector('#jump-form').requestSubmit()")
                await page.wait_for_timeout(100)
                check('switch_does_not_navigate_old_book',f'/api/books/{A}/reader/pages/2' not in requests)
                gate.set();await page.wait_for_function("document.querySelector('#paper').textContent.includes('乙书')")
                check('new_book_owns_final_content',True);await ctx.close()
                ctx,page,gate,state,requests=await fixture('refresh')
                await page.wait_for_function("document.querySelector('#book-select').options.length>1")
                await page.select_option('#book-select',A);await page.wait_for_selector('#paper .reading-flow')
                await page.wait_for_function("!document.querySelector('#job-recovery').hidden")
                state['revision']=2;state['refreshing']=True
                await page.click('#reading-window-open');await page.click('#refresh-job')
                check('current_page_refresh_does_not_wait_for_book_metadata',await wait(page,"document.querySelector('#paper').textContent.includes('版本2')"))
                gate.set();await ctx.close()
                # A second selection supersedes the pending first manifest, including A-B-A.
                ctx,page,gate,state,requests=await fixture('switch-race')
                await page.wait_for_function("document.querySelector('#book-select').options.length>1")
                await page.select_option('#book-select',A);await page.wait_for_selector('#paper .reading-flow')
                await page.select_option('#book-select',B)
                await page.select_option('#book-select',A)
                check('return_to_original_book_does_not_wait_for_other_manifest',await wait(page,"document.querySelector('#paper').textContent.includes('甲书')"))
                gate.set();await page.wait_for_timeout(120)
                check('late_other_manifest_cannot_replace_reselected_book',await page.locator('#book-select').input_value()==A and '乙书' not in await page.locator('#paper').inner_text())
                await ctx.close()
                for mode in ('switch-failure','switch-mismatch'):
                    ctx,page,gate,state,requests=await fixture(mode)
                    await page.wait_for_function("document.querySelector('#book-select').options.length>1")
                    await page.select_option('#book-select',A);await page.wait_for_selector('#paper .reading-flow')
                    await page.select_option('#book-select',B)
                    check(mode+'_retires_old_body',await wait(page,"document.querySelector('#paper').textContent.includes('书籍暂未打开') && !document.querySelector('#paper').textContent.includes('甲书')"))
                    check(mode+'_cannot_navigate_unloaded_book',await page.locator('#page-jump').is_disabled())
                    await page.select_option('#book-select',A)
                    check(mode+'_can_recover_by_reselecting',await wait(page,"document.querySelector('#paper').textContent.includes('甲书')"))
                    await ctx.close()
                for mode in ('metadata','config-failure'):
                    ctx,page,gate,state,requests=await fixture(mode)
                    await page.wait_for_function("document.querySelector('#book-select').options.length>1")
                    await page.select_option('#book-select',A)
                    check(mode+'_cannot_block_current_body',await wait(page,"document.querySelector('#paper').textContent.includes('甲书')"))
                    await page.locator('#page-jump').fill('3');await page.locator('#page-jump').press('Enter')
                    check(mode+'_cannot_block_next_page',await wait(page,"document.querySelector('#paper').textContent.includes('第3页')"))
                    gate.set();await ctx.close()
                # Deterministic version of a real upload/first-paint race: enter a page
                # while the first page is in flight, release it, then submit the intent.
                ctx,page,gate,state,requests=await fixture('jump-draft')
                await page.wait_for_function("document.querySelector('#book-select').options.length>1")
                await page.select_option('#book-select',A)
                await page.wait_for_function("!document.querySelector('#page-jump').disabled")
                await page.locator('#page-jump').fill('2')
                gate.set();await page.wait_for_selector('#paper .reading-flow')
                check('first_page_paint_preserves_unsubmitted_jump',await page.locator('#page-jump').input_value()=='2')
                await page.locator('#page-jump').press('Enter')
                check('unsubmitted_jump_remains_usable_after_first_paint',await wait(page,"document.querySelector('#paper').textContent.includes('第2页')"))
                await page.locator('#page-jump').fill('30');await page.select_option('#book-select',B)
                check('typed_jump_does_not_cross_book_selection',await wait(page,"document.querySelector('#book-select').value==='"+B+"' && document.querySelector('#page-jump').value==='1'"))
                await ctx.close()
                ctx,page,gate,state,requests=await fixture('page-race')
                await page.wait_for_function("document.querySelector('#book-select').options.length>1")
                await page.select_option('#book-select',A);await page.wait_for_selector('#paper .reading-flow')
                await page.locator('#page-jump').fill('2');await page.locator('#page-jump').press('Enter')
                await page.locator('#page-jump').fill('3');await page.locator('#page-jump').press('Enter')
                check('fast_page_three_bypasses_slow_page_two',await wait(page,"document.querySelector('#paper').textContent.includes('第3页')"))
                gate.set();await page.wait_for_timeout(100)
                check('late_page_two_cannot_replace_page_three','第3页' in await page.locator('#paper').inner_text())
                await ctx.close()
                for mode in ('refresh-read','refresh-draft'):
                    ctx,page,gate,state,requests=await fixture(mode)
                    await page.wait_for_function("document.querySelector('#book-select').options.length>1")
                    await page.select_option('#book-select',A);await page.wait_for_selector('#paper .reading-flow')
                    await page.wait_for_function("!document.querySelector('#job-recovery').hidden")
                    state['revision']=2;state['refreshing']=True
                    await page.click('#reading-window-open');await page.click('#refresh-job')
                    await page.wait_for_timeout(80)
                    check(mode+'_keeps_visible_body_while_reading','版本1' in await page.locator('#paper').inner_text())
                    await page.keyboard.press('Escape')
                    if mode=='refresh-read':
                        await page.locator('#page-jump').fill('3');await page.locator('#page-jump').press('Enter')
                        check('navigation_does_not_wait_for_background_refresh',await wait(page,"document.querySelector('#paper').textContent.includes('第3页')"))
                        gate.set();await page.wait_for_timeout(100)
                        check('late_refresh_cannot_take_over_navigation','第3页' in await page.locator('#paper').inner_text())
                    else:
                        await page.click('#proof-toggle')
                        await page.locator('#mark-reviewed').evaluate("el=>{el.checked=true;el.dispatchEvent(new Event('change',{bubbles:true}));}")
                        gate.set();await page.wait_for_timeout(150)
                        check('late_refresh_keeps_unsaved_review_draft',await page.locator('#mark-reviewed').is_checked() and '版本1' in await page.locator('#paper').inner_text() and await page.locator('#save-page').is_enabled())
                    await ctx.close()
            ctx,page,gate,state,requests=await fixture('wrongpage')
            await page.wait_for_function("document.querySelector('#book-select').options.length>1")
            await page.select_option('#book-select',A);await page.wait_for_timeout(500)
            check('mismatched_page_response_not_rendered',await page.locator('#paper .reading-flow').count()==0)
            await ctx.close()
            check('no_javascript_errors',not errors);check('no_mutating_requests',not writes);check('no_external_requests',not external)
        finally:
            await browser.close()
            report={'scope':'actual frontend, synthetic APIs, controlled latency','selection':'implemented-response-identity' if args.identity_only else 'full-navigation-and-refresh-contract','checks':checks,'errors':errors,'writes':writes,'external':external}
            (OUT/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
            print(json.dumps(report,ensure_ascii=False,indent=2))
    if any(not c['pass'] for c in checks):raise SystemExit(1)
if __name__=='__main__':asyncio.run(main())
