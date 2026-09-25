import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createReadingWindow } from '../../src/main/resources/static/reading-window.js';

const flush=async()=>{for(let i=0;i<120;i++)await Promise.resolve();};
async function harness(pages,body) {
  const original=[globalThis.setTimeout,globalThis.clearTimeout];const timers=new Map();let seq=0;
  globalThis.setTimeout=(fn,delay)=>{const id=++seq;timers.set(id,{fn,delay});return id;};
  globalThis.clearTimeout=id=>timers.delete(id);
  const state={book:{id:'A',totalPages:5000},currentPage:2500,pageCache:new Map()};
  const gets=[],ready=[],errors=[],posts=[];let identity;
  const snapshot=()=>({sessionId:identity.sessionId,sequence:identity.sequence,enabled:true,status:'READY',pages:pages()});
  const api={readingWindow:async(_,request)=>{identity=request;posts.push(request);return snapshot();},
    readingWindowStatus:async()=>snapshot(),stopReadingWindow:async()=>{},
    page:async(_,n,signal)=>{gets.push(n);return body(n,signal);}};
  const controller=createReadingWindow({api,state,onStatus(){},onPageReady:(n,page)=>{ready.push(page);state.pageCache.set(n,page);},onError:e=>errors.push(e)});
  return {state,gets,ready,errors,posts,controller,async poll(){
    const entry=[...timers.entries()].find(([,task])=>task.delay===1500);assert.ok(entry,'status poll expected');
    timers.delete(entry[0]);await entry[1].fn();await flush();
  },async close(){await controller.stop({silent:true});globalThis.setTimeout=original[0];globalThis.clearTimeout=original[1];}};
}
const page=(n,revision=1)=>({pageNumber:n,revision,status:'READY',blocks:[]});
test('large READY snapshots only fetch current and ten neighboring pages',async()=>{
  const h=await harness(()=>Array.from({length:5000},(_,i)=>page(i+1)),n=>page(n));
  try {await h.controller.enable({});await flush();assert.equal(h.gets.length,11);
    assert.equal(h.gets[0],2500);assert.ok(h.gets.every(n=>Math.abs(n-2500)<=5));assert.equal(h.posts.length,1);
  }finally{await h.close();}
});
test('out-of-book, fractional, negative and invalid-revision metadata starts no GET',async()=>{
  const bad=[page(0),page(5001),page(2500.5),page(-1),page(2500,-1),page(2500,1.5),page(2500,'2')];
  const h=await harness(()=>bad,n=>page(n));
  try {await h.controller.enable({});await flush();assert.deepEqual(h.gets,[]);}finally{await h.close();}
});
test('mismatched READY response cannot be shown or remembered as successful',async()=>{
  let good=false;const h=await harness(()=>[page(2500,2)],n=>good?page(n,2):page(n+1,2));
  try {await h.controller.enable({});await flush();assert.equal(h.ready.length,0);assert.equal(h.errors.length,1);
    good=true;await h.poll();assert.equal(h.gets.length,2);assert.equal(h.ready[0].pageNumber,2500);
  }finally{await h.close();}
});
test('a pending response is not accepted merely because metadata advertised READY',async()=>{
  const h=await harness(()=>[page(2500,2)],n=>({...page(n,2),status:'PENDING'}));
  try {await h.controller.enable({});await flush();assert.equal(h.ready.length,0);assert.equal(h.errors.length,1);}finally{await h.close();}
});
test('newer metadata does not queue multiple GETs behind one in-flight page',async()=>{
  let revision=1,resolve;const deferred=new Promise(r=>{resolve=r;});let first=true;
  const h=await harness(()=>[page(2500,revision)],n=>{if(first){first=false;return deferred;}return page(n,revision);});
  try {await h.controller.enable({});await flush();
    for(revision=2;revision<=30;revision++)await h.controller.refreshStatus();
    assert.equal(h.gets.length,1);resolve(page(2500,1));await flush();assert.equal(h.ready.length,0);
    await h.poll();assert.equal(h.gets.length,2);assert.equal(h.ready[0].revision,revision);assert.equal(h.posts.length,1);
  }finally{resolve?.(page(2500));await h.close();}
});
test('fresh response can advance beyond the advertised revision without extra cloud work',async()=>{
  const h=await harness(()=>[page(2500,2)],n=>page(n,3));
  try {await h.controller.enable({});await flush();assert.equal(h.ready[0].revision,3);assert.equal(h.posts.length,1);}finally{await h.close();}
});
