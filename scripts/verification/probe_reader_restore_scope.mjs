import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
const source = await readFile(process.env.RESTORE_SUBJECT || new URL('../../src/main/resources/static/reading-window.js', import.meta.url), 'utf8');
const { createReadingWindow } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const KEY = 'book-html:reading-window:v1';
function setup() {
  const original = globalThis.sessionStorage;
  const saved = new Map([[KEY, JSON.stringify({ id: 'old-session', bookId: 'A' })]]);
  globalThis.sessionStorage = { getItem:k=>saved.get(k), setItem:(k,v)=>saved.set(k,v), removeItem:k=>saved.delete(k) };
  let resolve, reject;
  const pending = new Promise((yes,no)=>{resolve=yes;reject=no;});
  const state = { book:{id:'A',totalPages:20}, currentPage:1, pageCache:new Map() };
  const statuses=[]; let writes=0;
  const window = createReadingWindow({state,api:{readingWindowStatus:()=>pending,readingWindow:()=>{writes++;throw new Error('unexpected write');}},
    onStatus:value=>statuses.push(value),onPageReady(){assert.fail('restore must not fetch pages');},onError(){}});
  return {state,saved,statuses,window,resolve,reject,writes:()=>writes,close(){globalThis.sessionStorage=original;}};
}
test('valid existing session restores stop capability without a heartbeat or new POST', async()=>{
  const f=setup();try {
    const result=f.window.restore('A');f.resolve({sessionId:'old-session',enabled:true,sequence:4});
    assert.equal(await result,true);assert.equal(f.window.active(),true);assert.equal(f.writes(),0);
  } finally {f.close();}
});
test('late success cannot erase the newer book session', async()=>{
  const f=setup();try {
    const result=f.window.restore('A');f.state.book={id:'B',totalPages:20};
    f.saved.set(KEY,JSON.stringify({id:'new-session',bookId:'B'}));
    f.resolve({sessionId:'old-session',enabled:true,sequence:4});
    assert.equal(await result,false);assert.equal(JSON.parse(f.saved.get(KEY)).bookId,'B');assert.equal(f.statuses.length,0);
  } finally {f.close();}
});
test('late failure cannot erase the newer book session', async()=>{
  const f=setup();try {
    const result=f.window.restore('A');f.state.book={id:'B',totalPages:20};
    f.saved.set(KEY,JSON.stringify({id:'new-session',bookId:'B'}));f.reject(new Error('synthetic read error'));
    assert.equal(await result,false);assert.equal(JSON.parse(f.saved.get(KEY)).id,'new-session');
  } finally {f.close();}
});
test('stopped in-flight restoration cannot resurrect a session in the same book', async()=>{
  const f=setup();try {
    const result=f.window.restore('A');await f.window.stop();
    f.resolve({sessionId:'old-session',enabled:true,sequence:4});
    assert.equal(await result,false);assert.equal(f.window.active(),false);assert.equal(f.writes(),0);
  } finally {f.close();}
});
test('a response for a different session cannot be adopted', async()=>{
  const f=setup();try {
    const result=f.window.restore('A');f.resolve({sessionId:'another-session',enabled:true,sequence:4});
    assert.equal(await result,false);assert.equal(f.window.active(),false);assert.equal(f.statuses.length,0);
  } finally {f.close();}
});
test('A-B-A navigation does not make the first A response current again', async()=>{
  const f=setup();try {
    const result=f.window.restore('A');f.state.book={id:'B',totalPages:20};await f.window.stop();
    f.state.book={id:'A',totalPages:20};f.resolve({sessionId:'old-session',enabled:true,sequence:4});
    assert.equal(await result,false);assert.equal(f.window.active(),false);
  } finally {f.close();}
});
