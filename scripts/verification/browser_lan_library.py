"""Real loopback Spring service, real PDF upload/restart, simulated LAN peer; no cloud keys."""
import argparse, asyncio, hashlib, json, os, re, shutil, socket, subprocess, tempfile, time, xml.etree.ElementTree as ET
from pathlib import Path
from playwright.async_api import async_playwright, expect
ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--direct',action='store_true',help='Verify the default private-peer shared upload without a PIN; no production setting is changed.')
DIRECT=parser.parse_args().direct
OUT=ROOT/('test-results/shared-lan-browser' if DIRECT else 'test-results/lan-library-browser')

async def main():
    OUT.mkdir(parents=True,exist_ok=True)
    reports=list((ROOT/'target/surefire-reports').glob('TEST-*.xml'))
    if not reports: raise RuntimeError('run mvn verify before browser integration')
    props=ET.parse(reports[0]).getroot().find('properties')
    cp=next(p.attrib['value'] for p in props if p.attrib['name']=='java.class.path')
    checks=[];errors=[];external=[];requests=[];process=None;logs=[]
    upload_gate=asyncio.Event()
    def check(name,ok):
        checks.append({'name':name,'pass':bool(ok)})
        if not ok: raise AssertionError(name)
    with tempfile.TemporaryDirectory(prefix='book-lan-library-') as directory:
        temp=Path(directory).resolve();classes=temp/'classes';classes.mkdir();data=temp/'data'
        subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'scripts/verification/LanLibraryQaServer.java')],check=True)
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        base=f'http://127.0.0.1:{port}'
        def start(index):
            nonlocal process
            log=(OUT/f'server-{index}.log').open('w');logs.append(log)
            process=subprocess.Popen(['java','-Djava.io.tmpdir='+str(temp),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',str(classes)+os.pathsep+cp,'LanLibraryQaServer',str(data),str(port)],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
            for _ in range(240):
                if process.poll() is not None:raise RuntimeError('isolated server exited; inspect server log')
                try:
                    with socket.create_connection(('127.0.0.1',port),timeout=.2):return
                except OSError:time.sleep(.1)
            raise RuntimeError('isolated server startup timed out')
        def stop():
            nonlocal process
            if process and process.poll() is None:
                process.terminate()
                try:process.wait(timeout=20)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5);raise
            process=None
        try:
            start(1)
            async with async_playwright() as pw:
                browser=await pw.chromium.launch(executable_path=os.environ.get('CHROMIUM') or shutil.which('google-chrome') or shutil.which('chromium'),headless=True)
                async def context(lan=True,width=1100):
                    ctx=await browser.new_context(viewport={'width':width,'height':900},extra_http_headers={'X-QA-Reader':'lan-direct' if DIRECT else 'lan-test'} if lan else {})
                    async def guard(route):
                        if not route.request.url.startswith(base+'/'):
                            external.append(route.request.url);await route.abort();return
                        requests.append({'url':route.request.url[len(base):], 'method':route.request.method})
                        if DIRECT and route.request.url==base+'/api/books' and route.request.method=='POST':await upload_gate.wait()
                        await route.continue_()
                    await ctx.route('**/*',guard)
                    p=await ctx.new_page();p.on('pageerror',lambda e:errors.append(str(e)))
                    await p.goto(base,wait_until='domcontentloaded')
                    await expect(p.locator('#shelf-books')).to_have_attribute('aria-busy','false')
                    return ctx,p
                owner,admin=await context(False)
                reader,page=await context()
                check('home_is_reading_bookshelf',await page.locator('#shelf-heading').is_visible())
                check('empty_shared_shelf',await page.locator('.shelf-book').count()==0)
                await expect(page.locator('body')).to_have_attribute('data-lan-role','reader')
                status_reads=sum(r['url']=='/api/lan/status' for r in requests)
                await page.set_input_files('#pdf-upload',str(temp/'sample.pdf'))
                await expect(page.locator('#pdf-upload')).to_be_disabled()
                await page.locator('#pdf-upload').evaluate("input => input.dispatchEvent(new Event('change',{bubbles:true}))")
                check('duplicate_file_event_keeps_active_upload_owned',await page.locator('#pdf-upload').is_disabled())
                if DIRECT:
                    check('direct_upload_needs_no_pair_dialog',not await page.locator('#lan-reader-dialog').is_visible())
                    check('ordinary_reader_has_no_authorization_menu',not await page.locator('#lan-reader-open').is_visible())
                    check('upload_does_not_refetch_device_configuration',status_reads==sum(r['url']=='/api/lan/status' for r in requests))
                    upload_gate.set()
                else:
                    await page.wait_for_selector('#lan-reader-dialog[open]')
                    check('nonprivate_upload_still_waits_for_pairing',not any(r['url']=='/api/books' and r['method']=='POST' for r in requests))
                    pin_result=await owner.request.post(base+'/api/lan/pin',headers={'Origin':base})
                    check('only_owner_can_issue_fallback_pin',pin_result.status==200)
                    pin=(await pin_result.json())['pin']
                    await page.fill('#lan-pin',pin);pin=None
                    await page.click('#lan-pair-submit')
                await page.wait_for_selector('#reader-shell:not([hidden])')
                await expect(page.locator('#book-select')).to_have_value(re.compile(r'.+'))
                await expect(page.locator('#page-jump')).to_have_value('1')
                book=await page.locator('#book-select').input_value()
                check('real_pdf_saved_on_server',(data/'books'/book/'source.pdf').is_file())
                check('original_pdf_bytes_unchanged',hashlib.sha256((data/'books'/book/'source.pdf').read_bytes()).digest()==hashlib.sha256((temp/'sample.pdf').read_bytes()).digest())
                cookies=await reader.cookies(base+'/api/books')
                if DIRECT:
                    check('default_upload_does_not_create_pairing_cookie',not any(c['name']=='BOOK_LAN_SESSION' for c in cookies))
                else:
                    cookie=next(c for c in cookies if c['name']=='BOOK_LAN_SESSION')
                    check('credential_httponly_and_samesite',cookie['httpOnly'] and cookie['sameSite']=='Strict')
                check('credential_not_exposed_to_script', 'BOOK_LAN_SESSION' not in await page.evaluate('document.cookie'))
                check('credential_not_in_web_storage',not await page.evaluate("Object.keys(localStorage).some(k=>/token|pairing|pin/i.test(k))"))
                await page.fill('#page-jump','2');await page.locator('#jump-form').evaluate('(f)=>f.requestSubmit()')
                await expect(page.locator('#page-jump')).to_have_value('2')
                await page.locator('#paper img').first.wait_for(state='attached')
                await page.click('#shelf-home');await page.wait_for_selector('.shelf-book button')
                check('shelf_offers_continue_not_conversion', '第 2 页' in await page.locator('.shelf-book button').inner_text())
                await page.reload();await page.wait_for_selector('.shelf-book button')
                check('same_browser_return_retains_position','第 2 页' in await page.locator('.shelf-book button').inner_text())
                check('desktop_shelf_does_not_reserve_hidden_sidebar',await page.evaluate("document.querySelector('#reader').getBoundingClientRect().width > innerWidth * .9"))
                other,second=await context(True,390)
                await second.wait_for_selector('.shelf-book button')
                check('other_device_sees_shared_uploaded_pdf',await second.locator('.shelf-book').get_attribute('data-book-id')==book)
                check('other_device_has_its_own_reading_position',await second.locator('.shelf-book button').inner_text()=='开始阅读')
                check('mobile_shelf_no_horizontal_overflow',await second.evaluate('document.documentElement.scrollWidth<=innerWidth'))
                await second.screenshot(path=str(OUT/'mobile-shared-shelf.png'),full_page=True)
                for path in [f'/api/books/{book}/jobs','/api/cloud-consents']:
                    response=await reader.request.post(base+path,headers={'Origin':base,'Content-Type':'application/json'},data='{}')
                    check('upload_grant_denies_'+path.split('/')[-1],response.status==(401 if DIRECT else 403))
                check('no_paid_job_dispatched',not any(r['method']=='POST' and ('reading-window' in r['url'] or r['url'].endswith('/jobs')) for r in requests))
                stop();start(2)
                await page.reload();await page.wait_for_selector('.shelf-book button')
                check('shelf_survives_server_restart',await page.locator('.shelf-book').get_attribute('data-book-id')==book)
                check('reading_position_survives_server_restart','第 2 页' in await page.locator('.shelf-book button').inner_text())
                status=await (await reader.request.get(base+'/api/lan/status')).json()
                check('server_restart_does_not_resurrect_upload_grant',not status['isPaired'])
                if DIRECT:
                    # Another device is already reading; returning home must discover a new shared upload.
                    await second.locator('.shelf-book button').click()
                    await expect(second.locator('#book-select')).to_have_value(book)
                    await page.set_input_files('#pdf-upload',{'name':'another-shared-book.pdf','mimeType':'application/pdf','buffer':(temp/'sample.pdf').read_bytes()})
                    await expect(page.locator('#pdf-upload')).to_be_enabled()
                    check('restart_still_allows_upload_without_new_configuration',len(list((data/'books').glob('*/book.json')))==2)
                    await page.click('#shelf-home');await expect(page.locator('.shelf-book')).to_have_count(2)
                    await second.click('#shelf-home');await expect(second.locator('.shelf-book')).to_have_count(2)
                    check('return_to_shared_shelf_discovers_other_device_upload',True)
                    check('no_device_ever_requests_pairing_or_privileged_config',not any(r['url'].startswith('/api/lan/browser-pair') or r['url']=='/api/settings' for r in requests))
                    await second.screenshot(path=str(OUT/'mobile-shared-shelf.png'),full_page=True)
                else:
                    await page.set_input_files('#pdf-upload',str(temp/'sample.pdf'));await page.wait_for_selector('#lan-reader-dialog[open]')
                    await page.click('#lan-reader-close');await expect(page.locator('#pdf-upload')).to_be_enabled()
                    check('cancel_pairing_does_not_upload',len(list((data/'books').glob('*/book.json')))==1)
                check('no_browser_errors',not errors);check('no_external_requests',not external)
                await page.screenshot(path=str(OUT/'desktop-shared-shelf.png'),full_page=True)
                await browser.close()
        finally:
            stop()
            for log in logs:log.close()
            (OUT/'results.json').write_text(json.dumps({'checks':checks,'pageErrors':errors,'external':external,'mode':'direct-private-shared' if DIRECT else 'nonprivate-pairing-fallback','lanPeer':'test-only servlet peer wrapper; no forwarded-header trust in production'},ensure_ascii=False,indent=2))
    print(json.dumps({'checks':len(checks),'passed':sum(c['pass'] for c in checks),'pageErrors':errors}))
if __name__=='__main__':asyncio.run(main())
