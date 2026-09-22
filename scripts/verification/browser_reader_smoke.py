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
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=os.environ.get('CHROME_BIN') or shutil.which('google-chrome') or shutil.which('chromium'),headless=True,args=['--no-sandbox'])
    page=browser.new_page(viewport={'width':1440,'height':1000},reduced_motion='reduce')
    errors=[]; page.on('pageerror',lambda e: errors.append(str(e)))
    page.add_init_script(f"localStorage.setItem('paper-studio:{book}:reading', JSON.stringify({{page:8, view:'reading'}}));")
    page.goto(base,wait_until='domcontentloaded')
    page.wait_for_function("document.querySelector('#book-select').options.length > 1")
    page.evaluate("id=>{const el=document.querySelector('#book-select');el.value=id;el.dispatchEvent(new Event('change'));}",book)
    page.wait_for_function("document.querySelector('#page-processing-progress').textContent.includes('10%')",timeout=15000)
    page.screenshot(path=str(out/'desktop-processing.png'))
    result={'errors':errors,'progress':page.locator('#page-processing-progress').inner_text()}
    result['positions']=page.evaluate("""() => { const r=id=>{const x=document.querySelector(id).getBoundingClientRect();return {x:x.x,y:x.y,width:x.width,height:x.height}};return {save:r('#page-save-status'), progress:r('#page-processing-progress'), reader:r('#reader'), paper:r('#paper')}; }""")
    assert result['positions']['progress']['x'] >= result['positions']['save']['x']+result['positions']['save']['width']-1
    calls=page.request.get(base+'/__qa/reading-calls').json()['calls'];result['initial_calls']=calls
    assert calls[0]['pageNumber']==8, calls
    assert len(calls)<=2,calls
    settings=page.request.get(base+'/api/settings').json()
    assert 'qa-mock-not-a-credential' not in json.dumps(settings)
    result['saved_credentials_not_returned']=True
    page.locator('#page-jump').fill('27');page.locator('#page-jump').press('Enter')
    page.wait_for_function("document.querySelector('#page-jump').value === '27'")
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
    page.wait_for_function("document.querySelector('#paper').textContent.includes('这是第 27 页')",timeout=15000)
    page.wait_for_function("document.querySelector('#page-processing-progress').hidden",timeout=15000)
    result['ready_progress_hidden']=page.locator('#page-processing-progress').is_hidden()
    page.screenshot(path=str(out/'mobile-ready.png'))
    result['errors']=errors
    assert not errors,errors
    (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    browser.close()
print(json.dumps(result,ensure_ascii=False,indent=2))
