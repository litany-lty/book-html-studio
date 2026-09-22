#!/usr/bin/env python3
"""在隔离的 ReadingWindowQaServer 合成书上验证书架管理，不调用 OCR。"""
import json
import os
import tempfile
import time
import urllib.parse
import urllib.request

from cdpdrive import Driver

SERVER = os.environ.get('LIBRARY_SERVER', 'http://127.0.0.1:18767').rstrip('/')
OUT = os.environ.get('LIBRARY_OUT', tempfile.mkdtemp(prefix='library-qa-'))
WINDOW_BOOK = 'bbbbbbbb-2222-2222-2222-222222222222'
SEED_BOOK = 'aaaaaaaa-1111-1111-1111-111111111111'


def get(path):
    with urllib.request.urlopen(SERVER + path, timeout=10) as response:
        return json.load(response)


def main():
    target = urllib.parse.urlparse(SERVER)
    if target.hostname != '127.0.0.1' or target.port in (None, 18765):
        raise RuntimeError('只允许使用独立本地端口上的合成书')
    books = get('/api/books')
    if {(b['id'], b['title']) for b in books} != {
        (WINDOW_BOOK, '随读处理合成书'), (SEED_BOOK, '合成证据书')
    }:
        raise RuntimeError('合成书身份不符；不能在用户数据上运行变更测试')
    os.makedirs(OUT, exist_ok=True)
    driver = Driver(int(os.environ.get('LIBRARY_PORT', '9361')),
                    profile=tempfile.mkdtemp(prefix='library-browser-'))
    results = []

    def check(name, condition):
        passed = bool(condition)
        results.append({'name': name, 'pass': passed})
        print(('PASS ' if passed else 'FAIL ') + name, flush=True)
        if not passed:
            raise AssertionError(name)

    try:
        driver.connect()
        session = driver.attach(driver.new_tab('about:blank'))
        driver.call('Runtime.enable', {}, session=session)

        def eval_js(source):
            return driver.eval(session, source)

        def wait(source, limit=12):
            deadline = time.monotonic() + limit
            while time.monotonic() < deadline:
                value = eval_js(source)
                if value:
                    return value
                time.sleep(.08)
            return False

        driver.call('Emulation.setDeviceMetricsOverride',
                    {'width': 1440, 'height': 900, 'deviceScaleFactor': 1, 'mobile': False}, session=session)
        driver.navigate(session, SERVER)
        check('书架两本合成书加载', wait("document.querySelectorAll('#book-select option').length===3"))
        eval_js("document.querySelector('#library-open').click()")
        check('管理书架对话框可打开', wait("document.querySelector('#library-dialog').open"))
        check('显示书籍与处理进度', eval_js("document.querySelectorAll('.library-book').length===2 && [...document.querySelectorAll('.library-book progress')].every(p=>p.max>0)"))
        eval_js("document.querySelector('#library-search').value='不存在';document.querySelector('#library-search').dispatchEvent(new Event('input'))")
        check('搜索无结果可理解', eval_js("document.querySelector('.library-empty')?.textContent.includes('没有找到')"))
        eval_js("document.querySelector('#library-search').value='随读';document.querySelector('#library-search').dispatchEvent(new Event('input'))")
        check('按书名搜索', eval_js("document.querySelectorAll('.library-book').length===1"))
        eval_js("document.querySelector('.library-book-actions button:last-child').click()")
        check('归档后从当前书架移除', wait("document.querySelector('#library-active-count').textContent==='1' && ![...document.querySelectorAll('#book-select option')].some(o=>o.value==='" + WINDOW_BOOK + "')"))
        check('归档不删除书籍数据', get('/api/books/' + WINDOW_BOOK)['archived'])
        eval_js("document.querySelector('#library-search').value='';document.querySelector('#library-search').dispatchEvent(new Event('input'));document.querySelector('#library-archived').click()")
        check('已归档清单可见', wait("document.querySelectorAll('.library-book').length===1 && document.querySelector('.library-book h3').textContent==='随读处理合成书'"))
        eval_js("document.querySelector('.library-book-actions button:last-child').click()")
        check('恢复后重新进入书架', wait("document.querySelector('#library-active-count').textContent==='2' && !!document.querySelector('#book-select option[value=\"" + WINDOW_BOOK + "\"]')"))
        eval_js("document.querySelector('#library-active').click();document.querySelector('#library-search').value='随读';document.querySelector('#library-search').dispatchEvent(new Event('input'));document.querySelector('.library-book-actions button:nth-child(3)').click()")
        check('可进入行内改名', wait("!!document.querySelector('.library-rename input')"))
        eval_js("document.querySelector('.library-rename input').value='随读工具验收书';document.querySelector('.library-rename').requestSubmit()")
        check('改名后书架与下拉同步', wait("document.querySelector('.library-book h3')?.textContent==='随读工具验收书' && [...document.querySelectorAll('#book-select option')].some(o=>o.textContent.includes('随读工具验收书'))"))
        check('后端标题持久化', get('/api/books/' + WINDOW_BOOK)['title'] == '随读工具验收书')
        eval_js("document.querySelector('.library-book-actions button:first-child').click()")
        check('从书架打开正文', wait("!document.querySelector('#library-dialog').open && document.querySelector('#book-select').value==='" + WINDOW_BOOK + "' && !!document.querySelector('#paper')"))
        eval_js("(async()=>{const {state}=await import('/store.js');state.dirty=true})()")
        eval_js("document.querySelector('#library-open').click();document.querySelector('#library-search').value='随读工具';document.querySelector('#library-search').dispatchEvent(new Event('input'));document.querySelector('.library-book-actions button:last-child').click()")
        check('未保存校对禁止归档当前书', wait("document.querySelector('#library-status').textContent.includes('未保存内容') && document.querySelector('#book-select').value==='" + WINDOW_BOOK + "'"))
        check('禁止归档未写入磁盘', not get('/api/books/' + WINDOW_BOOK)['archived'])
        eval_js("(async()=>{const {state}=await import('/store.js');state.dirty=false})()")
        eval_js("document.querySelector('#library-close').click()")
        eval_js("document.querySelector('#library-open').click()")
        check('管理书架能查看本书费用', wait("document.querySelector('#library-dialog').open"))
        eval_js("document.querySelector('#library-search').value='随读';document.querySelector('#library-search').dispatchEvent(new Event('input'));document.querySelector('.library-book-actions button:nth-child(2)').click()")
        check('打开指定书籍用量记录', wait("document.querySelector('#usage-dialog').open && document.querySelector('#usage-book-name').textContent==='随读工具验收书'"))
        eval_js("document.querySelector('#usage-close').click()")
        driver.call('Emulation.setDeviceMetricsOverride',
                    {'width': 390, 'height': 844, 'deviceScaleFactor': 1, 'mobile': True}, session=session)
        eval_js("document.querySelector('#more-toggle').click();document.querySelector('#library-open').click()")
        check('手机书架管理可打开', wait("document.querySelector('#library-dialog').open"))
        check('手机无横向溢出', eval_js("document.documentElement.scrollWidth<=innerWidth && document.querySelector('#library-dialog').scrollWidth<=innerWidth"))
        check('手机管理操作可触控', eval_js("[...document.querySelectorAll('.library-book-actions .button')].every(b=>b.getBoundingClientRect().height>=44)"))
        driver.screenshot(session, os.path.join(OUT, 'library-mobile.png'))
        check('无脚本未捕获异常', not any(event.get('method') == 'Runtime.exceptionThrown' for event in driver.events))
        print('OVERALL PASS', flush=True)
    finally:
        if results and not results[-1]['pass']:
            try:
                driver.screenshot(session, os.path.join(OUT, 'failure.png'))
            except Exception:
                pass
        with open(os.path.join(OUT, 'results.json'), 'w', encoding='utf-8') as output:
            json.dump(results, output, ensure_ascii=False, indent=2)
        driver.close()


if __name__ == '__main__':
    main()
