import assert from 'node:assert/strict';
import { test } from 'node:test';
import { LruPageCache, estimatePageBytes } from '../../src/main/resources/static/store.js';

test('source records and simplified text contribute to the memory estimate', () => {
  const text='字'.repeat(10000);
  for (const page of [{sourceRecords:[{original:text}]},{blocks:[{simplified:text}]},
    {presentation:{readingBlocks:[{original:text}]}},{blocks:[{issues:[{candidates:[{text}]}]}]}]) {
    assert.ok(estimatePageBytes(page)>=text.length*2);
  }
});
test('geometry and metadata arrays cannot bypass cache limits', () => {
  const page={issueImages:{i:{boxes:Array.from({length:1000},()=>[.1,.1,.2,.2])}}};
  assert.ok(estimatePageBytes(page)>=1000*4*8);
});
test('large source-only neighbors are evicted while the current page stays available', () => {
  const cache=new LruPageCache(12,4096,key=>key===1);
  cache.set(1,{pageNumber:1,blocks:[]});
  cache.set(2,{pageNumber:2,sourceRecords:[{original:'字'.repeat(5000)}]});
  assert.equal(cache.has(1),true);assert.equal(cache.has(2),false);assert.ok(cache.currentBytes<=4096);
});
test('aliasing and cyclic data are finite and deletion resets accounting', () => {
  const shared={original:'字'.repeat(100)};const page={blocks:[shared],sourceRecords:[shared]};page.self=page;
  const bytes=estimatePageBytes(page);assert.ok(Number.isFinite(bytes)&&bytes>=200);
  const cache=new LruPageCache(2);cache.set(1,page);cache.delete(1);assert.equal(cache.currentBytes,0);
  cache.set(2,page);cache.clear();assert.equal(cache.currentBytes,0);
});
test('deep metadata is measured without recursive stack overflow', () => {
  const root={};let node=root;for(let i=0;i<15000;i++)node=node.next={};node.text='字'.repeat(10000);
  assert.ok(estimatePageBytes(root)>=20000);
});

test('an oversized prefetch cannot evict useful small neighbors', () => {
  const cache=new LruPageCache(12,4096,key=>key===1);
  for(let n=1;n<=3;n++)cache.set(n,{pageNumber:n,blocks:[]});
  cache.set(4,{sourceRecords:[{original:'字'.repeat(5000)}]});
  assert.deepEqual([...cache.keys()],[1,2,3]);
});
test('oversized protected entries do not corrupt byte counts after deletion', () => {
  const cache=new LruPageCache(12,4096,key=>key<3);
  const huge={next:{text:'字'.repeat(17*1024*1024)}};
  cache.set(1,huge);cache.set(2,huge);cache.delete(1);cache.delete(2);
  cache.set(3,{blocks:[]});assert.equal(cache.currentBytes,estimatePageBytes({blocks:[]}));
});
test('accessor fields are not executed while estimating retained data', () => {
  let calls=0;const page={get text(){calls++;return 'private';}};
  assert.equal(estimatePageBytes(page),Number.MAX_SAFE_INTEGER);assert.equal(calls,0);
});

test('actual LRU preserves the current page and twelve-entry bound', () => {
  const cache=new LruPageCache(12,32*1024*1024,key=>key===1);
  for(let n=1;n<=15;n++)cache.set(n,{pageNumber:n,blocks:[]});
  assert.equal(cache.size,12);assert.equal(cache.has(1),true);assert.equal(cache.has(2),false);assert.equal(cache.has(15),true);
});
test('byte eviction and replacement recount actual retained records', () => {
  const current={pageNumber:1,blocks:[]}, large=n=>({pageNumber:n,sourceRecords:[{original:'字'.repeat(800)}]});
  const budget=estimatePageBytes(current)+estimatePageBytes(large(2))*2+1;
  const cache=new LruPageCache(12,budget,key=>key===1);
  cache.set(1,current);cache.set(2,large(2));cache.set(3,large(3));cache.set(4,large(4));
  assert.equal(cache.has(1),true);assert.equal(cache.has(2),false);assert.equal(cache.has(4),true);
  assert.ok(cache.currentBytes<=budget);
  cache.set(3,{pageNumber:3,blocks:[]});
  assert.equal(cache.currentBytes,[...cache.values()].reduce((sum,page)=>sum+estimatePageBytes(page),0));
});
