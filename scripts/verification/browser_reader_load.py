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
    parser.add_argument('--identity-only',action='store_true',help='Gate only the implemented response-identity contract; default also audits known unfixed navigation cases.')
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
                    if mode=='switch' and path==f'/api/books/{B}/reader':await gate.wait()
                    if mode=='refresh' and state['refreshing'] and (path==f'/api/books/{A}' or path.endswith('/outline')):await gate.wait()
                    if path=='/api/config':value={'defaultProvider':'paddle-aistudio','providers':[], 'ocrChannels':[], 'qwenAssist':{},'capabilities':{'schemaVersion':2}}
                    elif path=='/api/books':value=[book(A),book(B)]
                    elif path=='/api/reading-policy':value={}
                    elif path.endswith('/job'):
                        state['jobReads']+=1
                        if mode=='refresh' and state['jobReads']==1:
                            await route.fulfill(status=503,json={'message':'test-only initial status failure'});return
                        value={'status':'IDLE','completed':0,'total':0,'errors':[]}
                    elif path.endswith('/outline'):value=[]
                    elif path.endswith('/progress'):value={'pageNumber':1,'revision':1,'status':'READY','processing':None}
                    elif path.endswith('/pages'):value=[]
                    elif m:=re.fullmatch(r'/api/books/([^/]+)/(?:reader/)?pages/(\d+)',path):
                        n=int(m[2]);value=page_data(m[1],n,state['revision'])
                        if mode=='wrongpage':value['pageNumber']=39
                    elif m:=re.fullmatch(r'/api/books/([^/]+)(?:/reader)?',path):value=book(m[1])
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
            ctx,page,gate,state,requests=await fixture('wrongpage')
            await page.wait_for_function("document.querySelector('#book-select').options.length>1")
            await page.select_option('#book-select',A);await page.wait_for_timeout(500)
            check('mismatched_page_response_not_rendered',await page.locator('#paper .reading-flow').count()==0)
            await ctx.close()
            check('no_javascript_errors',not errors);check('no_mutating_requests',not writes);check('no_external_requests',not external)
        finally:
            await browser.close()
            report={'scope':'actual frontend, synthetic APIs, controlled latency','selection':'implemented-response-identity' if args.identity_only else 'full-audit-including-unfixed-navigation','checks':checks,'errors':errors,'writes':writes,'external':external}
            (OUT/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
            print(json.dumps(report,ensure_ascii=False,indent=2))
    if any(not c['pass'] for c in checks):raise SystemExit(1)
if __name__=='__main__':asyncio.run(main())
