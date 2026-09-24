import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { progressView, createPageProgress } from '../../src/main/resources/static/page-progress.js';
import { api } from '../../src/main/resources/static/api.js';
const app = readFileSync(new URL('../../src/main/resources/static/app.js', import.meta.url), 'utf8');
const structure = app.slice(app.indexOf('async function applyStructureOverride('), app.indexOf('// J08：候选比较面板'));
const search = app.slice(app.indexOf("$('#search-form').addEventListener('submit'"), app.indexOf("$('#uncertain-only')", app.indexOf("$('#search-form').addEventListener('submit'")));
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return {promise, resolve, reject}; };
function fixture() {
  const nodes = new Map(), trace = [];
  const node = () => ({ children: [], handlers: {}, value: '', textContent: '',
    append(...items) { this.children.push(...items); }, replaceChildren(...items) { this.children=items; },
    addEventListener(type, handler) { this.handlers[type]=handler; } });
  const context = vm.createContext({ AbortController, console,
    state: {book:{id:'A'}, currentPage:3, editorEpoch:1, page:{revision:1}, blocks:[], dirty:false,
      pageCache: {delete(n) {trace.push(['invalidate',context.state.book.id,n]);}}},
    editVersion:0, bookRequest:1, structureGeneration:0, searchGeneration:0, searchController:null,
    deferredReady:null, api:{}, window:{getSelection:()=>({toString:()=>''})},
    document:{createElement:node},
    $: key => {if(!nodes.has(key)) nodes.set(key,node());return nodes.get(key);},
    currentPageProtected:()=>context.state.dirty,
    renderReadingWindowStatus:()=>trace.push(['defer']),
    goToPage:async n=>{ trace.push(['navigate',context.state.book.id,n]);context.state.currentPage=n;context.state.editorEpoch++;return true; },
    refreshOutline:async id=>trace.push(['outline',id]),
    showError:error=>trace.push(['error',error.message]),renderReview:()=>{},syncOverlays:()=>{} });
  vm.runInContext(structure+search,context);
  return {context,nodes,trace,submit:()=>nodes.get('#search-form').handlers.submit({preventDefault(){}})};
}
test('late structure success never refreshes another book', async () => {
  const f=fixture(), d=deferred();f.context.api.applyPresentationOverride=()=>d.promise;
  const p=f.context.applyStructureOverride({blockId:'x',sourceHash:'h'},'KEEP');
  f.context.state.book={id:'B'};f.context.bookRequest++;f.context.state.currentPage=8;
  d.resolve({});await p;assert.deepEqual(f.trace,[]);
});
test('same-book late structure invalidates only the original page', async () => {
  const f=fixture(),d=deferred();f.context.api.applyPresentationOverride=()=>d.promise;
  const p=f.context.applyStructureOverride({blockId:'x',sourceHash:'h'},'KEEP');
  f.context.state.currentPage=8;f.context.state.editorEpoch++;
  d.resolve({});await p;assert.deepEqual(f.trace,[['invalidate','A',3],['outline','A']]);
});
test('draft created while a structure operation waits is not replaced', async () => {
  const f=fixture(),d=deferred();f.context.api.applyPresentationOverride=()=>d.promise;
  const p=f.context.applyStructureOverride({blockId:'x',sourceHash:'h'},'KEEP');
  f.context.state.dirty=true;f.context.editVersion++;d.resolve({});await p;
  assert.ok(f.trace.some(t=>t[0]==='defer'));assert.ok(!f.trace.some(t=>t[0]==='navigate'));
});
test('old errors are silent after book switch', async () => {
  const f=fixture(),d=deferred();f.context.api.applyPresentationOverride=()=>d.promise;
  const p=f.context.applyStructureOverride({blockId:'x',sourceHash:'h'},'KEEP');
  f.context.state.book={id:'B'};f.context.bookRequest++;d.reject(new Error('old'));await p;
  assert.deepEqual(f.trace,[]);
});
test('search response cannot cross books', async () => {
  const f=fixture(),d=deferred();f.context.api.search=()=>d.promise;
  f.context.$('#search-input').value='old';const p=f.submit();
  f.context.state.book={id:'B'};f.context.bookRequest++;
  d.resolve([{pageNumber:2,blockId:'A-block',text:'old'}]);await p;
  assert.equal(f.context.$('#search-results').children.length,0);
});
test('latest query wins and obsolete result clicks cannot navigate', async () => {
  const f=fixture(),a=deferred(),b=deferred();let calls=0;
  f.context.api.search=()=>++calls===1?a.promise:b.promise;
  f.context.$('#search-input').value='one';const p1=f.submit();
  f.context.$('#search-input').value='two';const p2=f.submit();
  b.resolve([{pageNumber:2,blockId:'new',text:'second'}]);await p2;
  a.resolve([{pageNumber:7,blockId:'old',text:'first'}]);await p1;
  const result=f.context.$('#search-results').children[0].children[0];
  assert.equal(f.context.$('#search-results').children.length,1);
  assert.equal(result.children[1].textContent,'second');
  f.context.state.book={id:'B'};f.context.bookRequest++;await result.handlers.click();
  assert.ok(!f.trace.some(t=>t[0]==='navigate'));
});
test('unknown is terminal; recovered partial never invents a percentage', () => {
  const value=progressView({lifecycle:'UNKNOWN',stage:'PUBLISHING',percent:95}, {status:'READY'});
  assert.equal(value.state,'settled');assert.match(value.label,/结果待确认/);
  const recovered=progressView({lifecycle:'PARTIAL',stage:'RECOVERED',percent:0},{status:'READY'});
  assert.equal(recovered.hidden,false);assert.equal(recovered.percent,null);assert.ok(!recovered.label.includes('%'));
});
test('304 reuses only an explicit scoped prior response without parsing an empty body', async () => {
  const oldFetch=globalThis.fetch,oldWindow=globalThis.window;
  globalThis.window={setTimeout,clearTimeout};let calls=0;
  globalThis.fetch=async (url,options)=> {
    if(calls++===0) return new Response(JSON.stringify({pageNumber:1,status:'READY',processing:null}),{headers:{ETag:'"v1"'}});
    assert.equal(options.headers['If-None-Match'],'"v1"');
    return new Response(null,{status:304,headers:{ETag:'"v1"'}});
  };
  try { const first=await api.pageProgress('A',1);assert.equal(first._etag,'"v1"');
    assert.equal(await api.pageProgress('A',1,undefined,first),first);
  } finally { globalThis.fetch=oldFetch;globalThis.window=oldWindow; }
});

test('progress wake is single-flight; hidden and superseded page replies cannot repaint', async () => {
  const previous={document:globalThis.document,setTimeout:globalThis.setTimeout,clearTimeout:globalThis.clearTimeout};
  const listeners={},timers=new Map(),requests=[],published=[];
  let sequence=0;
  const element={hidden:true,dataset:{},style:{setProperty(){}},setAttribute(k,v){this[k]=v;},removeAttribute(k){delete this[k];}};
  globalThis.document={hidden:false,querySelector:()=>element,addEventListener:(name,fn)=>{listeners[name]=fn;}};
  globalThis.setTimeout=fn=>{const id=++sequence;timers.set(id,fn);return id;};
  globalThis.clearTimeout=id=>timers.delete(id);
  const transport={pageProgress:(book,page,signal)=>{const d=deferred();requests.push({book,page,signal,...d});return d.promise;},
    page:async()=>{throw new Error('unexpected content read');}};
  const progress=createPageProgress({api:transport,onPublished:(...args)=>published.push(args)});
  try {
    progress.setPage('A',1,{status:'READY',revision:1});progress.wake();progress.wake();
    assert.equal(requests.length,1);
    progress.setPage('B',2,{status:'READY',revision:2});assert.equal(requests.length,2);
    assert.equal(requests[0].signal.aborted,true);
    requests[0].resolve({status:'READY',revision:1,processing:{bookId:'A',pageNumber:1,lifecycle:'UNKNOWN',percent:95}});
    await new Promise(setImmediate);assert.equal(published.length,0);assert.equal(element.hidden,true);
    requests[1].resolve({status:'READY',revision:2,processing:null});await new Promise(setImmediate);
    assert.equal(timers.size,1);
    document.hidden=true;listeners.visibilitychange();progress.wake();assert.equal(timers.size,0);assert.equal(requests.length,2);
    document.hidden=false;listeners.visibilitychange();progress.wake();assert.equal(requests.length,3);
    progress.clear();requests[2].resolve({status:'READY',revision:2,processing:null});await new Promise(setImmediate);
    assert.equal(timers.size,0);assert.equal(element.hidden,true);
  } finally {progress.clear();Object.assign(globalThis,previous);}
});

test('restarted server can recover an attempt even after old event version 100; retired replies stay rejected', () => {
  const previous=globalThis.document;
  const element={hidden:true,dataset:{},style:{setProperty(){}},setAttribute(k,v){this[k]=v;},removeAttribute(k){delete this[k];}};
  globalThis.document={hidden:true,querySelector:()=>element,addEventListener(){}};
  const progress=createPageProgress({api:{},onPublished(){}});
  try {
    progress.setPage('A',1,{status:'READY',revision:3});
    const running={bookId:'A',pageNumber:1,attemptId:'attempt',attemptSeq:2,snapshotVersion:100,
      lifecycle:'RUNNING',stage:'PUBLISHING',percent:95,serverInstanceId:'first-process'};
    progress.update(running);assert.equal(element['aria-valuenow'],'95');
    progress.update({...running,serverInstanceId:'second-process',snapshotVersion:0,stage:'RECOVERED',lifecycle:'UNKNOWN'});
    assert.match(element.textContent,/结果待确认/);assert.equal(element['aria-valuenow'],undefined);
    progress.update({...running,snapshotVersion:101});
    assert.match(element.textContent,/结果待确认/);assert.equal(element.dataset.state,'settled');
  } finally {progress.clear();globalThis.document=previous;}
});

test('failed processing retains a usable retry control while preventing duplicate clicks', async () => {
  const previous = globalThis.document;
  const progressEl = { hidden: true, dataset: {}, style: { setProperty() {} }, setAttribute(k, v) { this[k] = v; }, removeAttribute(k) { delete this[k]; } };
  const retryBtn = {
    hidden: true,
    handlers: {},
    addEventListener(type, handler) { this.handlers[type] = handler; },
    click() { if (this.handlers.click) return this.handlers.click(); }
  };
  globalThis.document = {
    hidden: false,
    querySelector: sel => sel === '#page-retry-button' ? retryBtn : progressEl,
    addEventListener() {}
  };
  let retried = 0;
  const progress = createPageProgress({ api: {}, onPublished() {}, onRetry: () => { retried++; } });
  try {
    progress.setPage('A', 1, { status: 'FAILED', revision: 1 });
    assert.equal(retryBtn.hidden, false);
    const pending = retryBtn.click();
    assert.equal(retried, 1);
    assert.equal(retryBtn.disabled, true);
    retryBtn.click(); assert.equal(retried, 1);
    await pending;
    assert.equal(retryBtn.disabled, false);
    assert.equal(retryBtn.hidden, false);
  } finally {
    progress.clear();
    globalThis.document = previous;
  }
});

