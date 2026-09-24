#!/usr/bin/env python3
"""Real browser against ReadingWindowQaServer: synthetic books/mock OCR, no paid calls.
Run via run_reader_browser.sh after mvn verify. Network restrictions are failures, not PASS.
"""
from pathlib import Path
import argparse, os, shutil, socket, subprocess, tempfile, xml.etree.ElementTree as ET
from playwright.sync_api import sync_playwright
import json,time
parser=argparse.ArgumentParser(); parser.add_argument('--base', default='http://127.0.0.1:19872'); parser.add_argument('--out', default='test-results/browser-reader'); args=parser.parse_args()
out=Path(args.out);out.mkdir(parents=True,exist_ok=True)
base=args.base; book='bbbbbbbb-2222-2222-2222-222222222222'

def wait_script(page, predicate, timeout=30000):
    """Use CDP function calls rather than page-side eval; keep production CSP intact."""
    import time
    deadline=time.monotonic()+timeout/1000
    while time.monotonic()<deadline:
        if page.evaluate("() => ("+predicate+")"): return
        page.wait_for_timeout(40)
    diagnostics=page.evaluate("() => ({progress:document.querySelector('#page-processing-progress')?.textContent,paper:document.querySelector('#paper')?.textContent?.slice(0,160),messages:[...document.querySelectorAll('.toast')].map(x=>x.textContent)})")
    print('SYNTHETIC_SMOKE_DIAGNOSTIC', diagnostics)
    raise AssertionError("Browser condition was not met: "+predicate)


def check_environment_settings(page):
    """Exercise the real settings module before any reading/paid-work lease is admitted."""
    names=['paddleAccessToken','ppocrApiKey','ppocrSecretKey','qwenApiKey','jevApiKey']
    page.locator('#settings-open').click()
    wait_script(page, "!document.querySelector('#settings-form').hidden")
    before=page.request.get(base+'/api/settings').json()
    assert before['secretStorage']['mode']=='ENV_ONLY' and not before['secretStorage']['writable']
    for name in names:
        control=page.locator(f'#settings-form [name="{name}"]')
        assert control.is_disabled() and control.input_value()==''
    assert '环境注入' in page.locator('#settings-secret-storage-note').inner_text()
    page.screenshot(path=str(out/'settings-environment.png'))
    is_put=lambda response: response.url==base+'/api/settings' and response.request.method=='PUT'
    with page.expect_response(is_put) as saved:
        page.locator('#settings-save').click()
    assert saved.value.status==200, 'non-secret settings must remain writable'
    wait_script(page, "!document.querySelector('#settings-save').disabled && document.querySelector('#settings-save-status').textContent.includes('配置已保存')")
    after=page.request.get(base+'/api/settings').json()
    assert after['revision']==before['revision']+1
    assert 'qa-mock-not-a-credential' not in json.dumps(after)
    page.locator('#settings-close').click()
    wait_script(page, "!document.querySelector('#settings-dialog').open")
    page.locator('#settings-open').click()
    wait_script(page, "!document.querySelector('#settings-form').hidden")
    for name in names:
        assert page.locator(f'#settings-form [name="{name}"]').input_value()==''
    # Emulate a stale/autofilled value and a failed write without sending either to a provider.
    canary='test-only-browser-secret-canary'
    page.locator('#settings-form [name="qwenApiKey"]').evaluate('(el,value)=>{el.value=value}',canary)
    requests=[]
    def reject_write(route):
        if route.request.method=='PUT':
            assert canary not in (route.request.post_data or ''), 'ENV_ONLY must not send disabled credentials'
            requests.append(1)
            route.fulfill(status=500,content_type='application/json',body='{"message":"injected-save-failure"}')
        else:
            route.continue_()
    page.route('**/api/settings',reject_write)
    try:
        page.locator('#settings-save').click()
        wait_script(page, "document.querySelector('#settings-status').textContent.includes('配置未确认保存') && !document.querySelector('#settings-save').disabled")
        assert len(requests)==1
        for name in names:
            control=page.locator(f'#settings-form [name="{name}"]')
            assert control.input_value()=='' and control.get_attribute('type')=='password'
    finally:
        page.unroute('**/api/settings',reject_write)
    assert page.request.get(base+'/api/settings').json()['revision']==after['revision']
    page.locator('#settings-close').click()
    wait_script(page, "!document.querySelector('#settings-dialog').open")
    return {'mode':'ENV_ONLY','secret_controls_read_only':True,'non_secret_save_and_reopen':True,
            'failed_save_scrubs_inputs':True,'disabled_credentials_not_submitted':True}

with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.environ.get('CHROME_BIN') or shutil.which('google-chrome') or shutil.which('chromium'),headless=True,args=['--no-sandbox'])
    page=browser.new_page(viewport={'width':1440,'height':1000},reduced_motion='reduce')
    errors=[]; page.on('pageerror',lambda e: errors.append(str(e)))
    page.add_init_script(f"localStorage.setItem('paper-studio:{book}:reading', JSON.stringify({{page:8, view:'reading'}}));")
    # Model a browser that explicitly opted in after the v3 preference migration; a fresh browser remains unconsented.
    page.add_init_script("localStorage.setItem('book_html_auto_read_default_v3', 'true'); localStorage.setItem('book_html_auto_read', 'true');")
    page.goto(base,wait_until='domcontentloaded')
    wait_script(page, "document.querySelector('#book-select').options.length > 1")
    settings_result=check_environment_settings(page)
    page.evaluate("id=>{const el=document.querySelector('#book-select');el.value=id;el.dispatchEvent(new Event('change'));}",book)
    wait_script(page, "document.querySelector('#page-processing-progress').textContent.includes('10%')",timeout=15000)
    page.screenshot(path=str(out/'desktop-processing.png'))
    result={'errors':errors,'progress':page.locator('#page-processing-progress').inner_text(),'settings':settings_result}
    result['positions']=page.evaluate("""() => { const r=id=>{const x=document.querySelector(id).getBoundingClientRect();return {x:x.x,y:x.y,width:x.width,height:x.height}};return {save:r('#page-save-status'), progress:r('#page-processing-progress'), reader:r('#reader'), paper:r('#paper')}; }""")
    assert result['positions']['progress']['x'] >= result['positions']['save']['x']+result['positions']['save']['width']-1
    deadline=time.monotonic()+10
    while True:
        trace=page.request.get(base+'/__qa/reading-calls').json()
        calls=trace['calls']; admissions=trace['admissions']
        if len(calls)>=2 and len(admissions)>=2: break
        assert time.monotonic()<deadline, trace
        time.sleep(.05)
    result['initial_calls']=calls;result['admissions']=admissions
    # Assert the real synchronous admission boundary, not the independently
    # scheduled workers' wall-clock arrival order. Neither call has finished.
    assert [entry['pageNumber'] for entry in admissions]==[8,9], admissions
    assert {call['pageNumber'] for call in calls}=={8,9} and len(calls)==2,calls
    assert all(not call['completed'] for call in calls),calls
    settings=page.request.get(base+'/api/settings').json()
    assert 'qa-mock-not-a-credential' not in json.dumps(settings)
    result['saved_credentials_not_returned']=True
    page.locator('#page-jump').fill('27');page.locator('#page-jump').press('Enter')
    wait_script(page, "document.querySelector('#page-jump').value === '27'")
    deadline=time.time()+10
    while time.time()<deadline:
        calls=page.request.get(base+'/__qa/reading-calls').json()['calls']
        if any(c['pageNumber']==27 for c in calls):break
        time.sleep(.1)
    assert any(c['pageNumber']==27 for c in calls),calls
    assert len([c for c in calls if not c['completed']])<=3,calls
    result['after_jump']=calls
    page.set_viewport_size({'width':390,'height':844})
    page.screenshot(path=str(out/'mobile-processing.png'))
    result['mobile']=page.evaluate("""() => {const p=document.querySelector('#page-processing-progress').getBoundingClientRect();return {overflow:document.documentElement.scrollWidth>innerWidth,progressRight:p.right,viewport:innerWidth,visible:!document.querySelector('#page-processing-progress').hidden};}""")
    assert not result['mobile']['overflow'],result['mobile']
    assert result['mobile']['progressRight']<=390,result['mobile']
    pair=page.evaluate("() => {const a=document.querySelector('#page-save-status').getBoundingClientRect(),b=document.querySelector('#page-processing-progress').getBoundingClientRect();return {sameRow:Math.abs(a.top-b.top)<8,right:b.left>=a.right}}")
    assert pair['sameRow'] and pair['right'],pair
    result['mobile_status_pair']=pair
    # Release the isolated mock calls; this fixture never contacts a paid provider.
    for _ in range(4): page.request.post(base+'/__qa/release')
    wait_script(page, "document.querySelector('#paper').textContent.includes('这是第 27 页')",timeout=15000)
    wait_script(page, "document.querySelector('#page-processing-progress').hidden",timeout=15000)
    result['ready_progress_hidden']=page.locator('#page-processing-progress').is_hidden()
    page.screenshot(path=str(out/'mobile-ready.png'))
    result['errors']=errors
    assert not errors,errors
    (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    browser.close()
print(json.dumps(result,ensure_ascii=False,indent=2))
