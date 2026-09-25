"""Actual reading/proof UI with synthetic evidence. Never sends a mutating/provider request."""
import asyncio, json, mimetypes, os, re, shutil
from pathlib import Path
from urllib.parse import urlparse
from playwright.async_api import async_playwright
ROOT=Path(__file__).resolve().parents[2]
STATIC=ROOT/'src/main/resources/static'
OUT=Path(os.environ.get('WORKBENCH_TEST_OUT',ROOT/'test-results/reader-workbench'))
BOOK='11111111-1111-4111-8111-111111111111'
def payload(n):
    text=f'第{n}页疑字测试。已保存正文不应因切换阅读与校对丢失。'
    issue={'id':f'issue-{n}','start':3,'end':4,'simplifiedStart':3,'simplifiedEnd':4,'kind':'suspected',
           'originalText':text[3:4],'inferredText':'候','reason':'合成疑点','resolved':False}
    block={'id':f'block-{n}','type':'text','order':0,'original':text,'simplified':text,
           'bbox':[.1,.1,.8,.2],'writingMode':'horizontal-tb','sourceIds':[f'block-{n}'],'source':'fixture','issues':[issue]}
    note={'id':f'note-{n}','type':'text','order':1,'original':f'第{n}页第二个独立内容块','simplified':f'第{n}页第二个独立内容块',
          'bbox':[.1,.4,.8,.2],'writingMode':'horizontal-tb','sourceIds':[f'note-{n}'],'source':'fixture','issues':[]}
    return {'pageNumber':n,'width':600,'height':800,'status':'READY','provider':'fixture','revision':1,
            'blocks':[block,note],'sourceRecords':[block,note],'warnings':[],'reviewed':False,'issueImages':{}}
async def main():
    OUT.mkdir(parents=True,exist_ok=True)
    checks=[];errors=[];writes=[];external=[]
    def check(name,ok): checks.append({'name':name,'pass':bool(ok)})
    async with async_playwright() as pw:
        browser=await pw.chromium.launch(executable_path=os.environ.get('CHROMIUM') or shutil.which('google-chrome') or shutil.which('chromium'),headless=True)
        async def fixture(width=1440):
            context=await browser.new_context(viewport={'width':width,'height':1000},reduced_motion='reduce')
            page=await context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
            requests=[]
            async def route(r):
                url=urlparse(r.request.url);path=url.path;requests.append(path)
                if url.hostname!='reader-workbench.test': external.append(url.hostname);await r.abort();return
                if r.request.method!='GET': writes.append(path);await r.fulfill(status=403,json={'message':'no writes'});return
                if path.startswith('/api/'):
                    meta={'id':BOOK,'title':'校对衔接样本','filename':'fixture.pdf','totalPages':4,'processedPages':4,'reviewedPages':0,'countsStatus':'SNAPSHOT'}
                    if '/image' in path:
                        await r.fulfill(content_type='image/svg+xml',body='<svg xmlns="http://www.w3.org/2000/svg" width="600" height="800"><text x="60" y="80">synthetic evidence</text></svg>');return
                    if path=='/api/config':value={'defaultProvider':'paddle-aistudio','providers':[],'ocrChannels':[],'qwenAssist':{},'capabilities':{'schemaVersion':2}}
                    elif path=='/api/books':value=[meta]
                    elif path=='/api/reading-policy':value={}
                    elif path.endswith('/job'):value={'status':'IDLE','completed':0,'total':0,'errors':[]}
                    elif path.endswith('/outline') or path.endswith('/pages'):value=[]
                    elif path.endswith('/progress'):value={'pageNumber':int(path.split('/')[-2]),'revision':1,'status':'READY','processing':None}
                    elif m:=re.fullmatch(r'/api/books/[^/]+/(?:reader/)?pages/(\d+)',path):value=payload(int(m[1]))
                    elif re.fullmatch(r'/api/books/[^/]+(?:/reader)?',path):value=meta
                    elif '/issues/' in path:value=None
                    else:value={}
                    await r.fulfill(json=value);return
                f=(STATIC/path.lstrip('/')).resolve() if path!='/' else STATIC/'index.html'
                if not f.is_relative_to(STATIC.resolve()) or not f.is_file():await r.fulfill(status=404,body='missing');return
                await r.fulfill(content_type=mimetypes.guess_type(str(f))[0] or 'application/octet-stream',body=f.read_bytes())
            await context.route('**/*',route)
            await page.goto('http://reader-workbench.test/',wait_until='domcontentloaded')
            await page.select_option('#book-select',BOOK)
            await page.wait_for_selector('#paper .reading-flow')
            return context,page,requests
        async def settle(page):await page.wait_for_timeout(80)
        async def mounted(page,n):
            return await page.locator(f'#review-list [data-block-id="block-{n}"] textarea').count()>0 and await page.locator('#issue-workbench textarea').count()>0
        async def cleared(page):
            return await page.evaluate("""() => !document.querySelector('#review-list').children.length && !document.querySelector('#issue-workbench').children.length && !document.querySelector('#review-original').hasAttribute('src')""")
        try:
            context,page,requests=await fixture()
            check('initial_reading_does_not_mount_editors',await cleared(page))
            await page.click('#proof-toggle');await settle(page)
            check('desktop_proof_entry_mounts_current_page',await mounted(page,1))
            check('desktop_proof_has_consistent_mode',await page.evaluate("document.body.dataset.readerMode==='proof' && document.querySelector('#review-panel').classList.contains('open')"))
            await page.locator('#review-list [data-block-id="note-1"] .block-identity').click();await settle(page)
            check('selecting_another_block_is_not_reset_by_issue_selection',await page.locator('#review-list [data-block-id="note-1"]').evaluate("el=>el.classList.contains('selected')"))
            # Visible issue evidence may legitimately fall back to a page crop. This
            # contract concerns only the separate, still-collapsed full-page reference.
            check('collapsed_reference_has_no_image_source',not await page.locator('#review-original').get_attribute('src'))
            await page.locator('.review-reference > summary').click();await settle(page)
            check('expanded_reference_loads_current_page',f'/pages/1/image' in (await page.locator('#review-original').get_attribute('src') or ''))
            await page.locator('.review-reference > summary').click();await settle(page)
            check('collapsed_reference_releases_its_image',not await page.locator('#review-original').get_attribute('src'))
            await page.click('#proof-toggle');await settle(page)
            check('return_to_reading_unmounts_all_workbench_resources',await cleared(page))
            # Original view is not itself a request to show the hidden correction workbench.
            await page.click('[data-view="original"]');await settle(page)
            check('original_view_does_not_mount_hidden_editors',await cleared(page))
            await page.click('#proof-toggle');await page.click('[data-view="reading"]');await settle(page)
            # This also establishes an editor on the old implementation for the later stale-page checks.
            for n in (2,3):
                await page.locator('#page-jump').fill(str(n));await page.locator('#page-jump').press('Enter')
                await page.wait_for_function("n=>document.querySelector('#paper').textContent.includes('第'+n+'页')",arg=n)
                await settle(page)
                check(f'visible_desktop_proof_tracks_page_{n}',await mounted(page,n))
                check(f'old_page_editor_absent_on_page_{n}',await page.locator(f'#review-list [data-block-id="block-{n-1}"]').count()==0)
            await page.screenshot(path=str(OUT/'desktop-proof.png'))
            await page.click('#focus-toggle');await settle(page)
            check('focus_mode_releases_hidden_workbench',await cleared(page))
            await page.click('#focus-exit');await settle(page)
            check('exit_focus_button_does_not_also_jump_progress',await page.locator('#page-jump').input_value()=='3')
            check('leaving_focus_restores_current_visible_proof',await mounted(page,3))
            await page.click('#proof-toggle');await settle(page)
            await context.close()
            context,page,requests=await fixture()
            await page.click('#proof-toggle');await settle(page)
            await page.locator('#issue-workbench textarea').fill('用户尚未保存的校对草稿')
            await page.click('#proof-toggle');await settle(page)
            check('closing_proof_keeps_unsaved_status',await page.locator('#page-save-status').inner_text()=='未保存')
            check('closing_dirty_proof_releases_nodes_not_data',await cleared(page))
            await page.click('#proof-toggle');await settle(page)
            check('reopening_proof_preserves_unconfirmed_draft',await page.locator('#issue-workbench textarea').input_value()=='用户尚未保存的校对草稿')
            await page.click('#reading-options-open');await page.keyboard.press('Escape');await settle(page)
            check('escape_in_modal_does_not_close_underlying_proof',await mounted(page,1) and await page.locator('#proof-toggle').get_attribute('aria-pressed')=='true')
            await page.keyboard.press('Escape');await settle(page)
            check('escape_outside_modal_closes_proof_and_returns_focus',await cleared(page) and await page.evaluate("document.activeElement.id==='proof-toggle'"))
            await context.close()
            context,page,requests=await fixture(390)
            await page.click('#review-toggle');await settle(page)
            check('mobile_proof_entry_mounts_current_page',await mounted(page,1))
            check('mobile_open_has_scrim',await page.locator('#drawer-scrim').is_visible())
            await page.locator('#drawer-scrim').click(position={'x':4,'y':600});await settle(page)
            check('mobile_scrim_closes_mode_and_panel',await page.evaluate("document.body.dataset.readerMode==='reading' && !document.querySelector('#review-panel').classList.contains('open')"))
            check('mobile_close_releases_workbench',await cleared(page))
            await page.click('#review-toggle');await settle(page)
            check('mobile_reopen_uses_one_mode_transition',await page.evaluate("document.body.dataset.readerMode==='proof' && document.querySelector('#review-toggle').getAttribute('aria-expanded')==='true'"))
            await page.locator('#close-review').click();await settle(page)
            check('mobile_close_restores_visible_opener',await page.evaluate("document.activeElement?.id==='review-toggle'"))
            await context.close()
            check('no_page_script_errors',not errors);check('no_mutating_requests',not writes);check('no_external_requests',not external)
        finally:
            await browser.close()
            report={'scope':'actual frontend, synthetic book and evidence','checks':checks,'errors':errors,'writes':writes,'external':external}
            (OUT/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
            print(json.dumps(report,ensure_ascii=False,indent=2))
    if any(not c['pass'] for c in checks):raise SystemExit(1)
if __name__=='__main__':asyncio.run(main())
