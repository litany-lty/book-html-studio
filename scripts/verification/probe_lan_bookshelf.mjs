import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readingEntry, shelfEntries, SHELF_PAGE_SIZE, createShelfRefresh } from '../../src/main/resources/static/bookshelf.js';
import { hasReaderCapability, needsAccessControl } from '../../src/main/resources/static/lan-reader.js';
const books=[{id:'a',title:'甲书',filename:'alpha.pdf',totalPages:30,createdAt:'2026-01-01'},
  {id:'b',title:'乙书',filename:'beta.pdf',totalPages:20,createdAt:'2026-02-01'},
  {id:'c',title:'已归档',filename:'c.pdf',archived:true,totalPages:1}];
test('home discovery filters archives and searches filename without page requests',()=>{
  assert.deepEqual(shelfEntries(books,'','recent',()=>({})).map(x=>x.book.id),['b','a']);
  assert.deepEqual(shelfEntries(books,'ALPHA','reading',()=>({})).map(x=>x.book.id),['a']);
  assert.equal(SHELF_PAGE_SIZE,24);
});
test('resume page is bounded by this book and invalid state is not a reading record',()=>{
  for(const page of [-1,0,1.5,31,'NaN',Infinity])assert.equal(readingEntry(books[0],{page}).continuing,false);
  assert.equal(readingEntry(books[0],{page:17}).page,17);
});
test('recent reading is local to the supplied browser storage',()=>{
  const a=shelfEntries(books,'','reading',id=>id==='a'?{page:17,visitedAt:100}:{});
  const b=shelfEntries(books,'','reading',()=>({}));
  assert.equal(a[0].book.id,'a');assert.equal(b[0].book.id,'b');
});
test('upload authority does not imply paid, edit or administrator authority',()=>{
  const reader={capabilities:['READ','UPLOAD']};assert.equal(hasReaderCapability(reader,'UPLOAD'),true);
  for(const denied of ['EDIT','PAID','MANAGE'])assert.equal(hasReaderCapability(reader,denied),false);
  assert.equal(hasReaderCapability(null,'UPLOAD'),false);
  assert.equal(hasReaderCapability({capabilities:'MANAGE'},'UPLOAD'),false);
  assert.equal(hasReaderCapability({capabilities:['MANAGE']},'UPLOAD'),true);
});

test('default shared readers and owners do not see a pairing setting',()=>{
  assert.equal(needsAccessControl({capabilities:['READ','UPLOAD'],accessMode:'LAN_SHARED'}),false);
  assert.equal(needsAccessControl({capabilities:['MANAGE'],accessMode:'LOOPBACK'}),false);
  assert.equal(needsAccessControl(null),false);
  assert.equal(needsAccessControl({capabilities:['READ'],readRequiresPairing:true}),true);
  assert.equal(needsAccessControl({capabilities:['READ'],accessMode:'LAN_PAIRED'}),true);
});
test('simultaneous shelf returns coalesce into one metadata read',async()=>{
  let state=books,calls=0,release;
  const sync=createShelfRefresh({load:()=>{calls++;return new Promise(r=>release=r);},current:()=>state,apply:x=>state=x});
  const a=sync(),b=sync();assert.equal(a,b);await Promise.resolve();assert.equal(calls,1);
  release([]);assert.equal(await a,true);assert.deepEqual(state,[]);
});
test('delayed shelf read cannot erase an intervening upload',async()=>{
  let state=books,release;
  const sync=createShelfRefresh({load:()=>new Promise(r=>release=r),current:()=>state,apply:x=>state=x});
  const loading=sync();await Promise.resolve();state=[...books,{id:'new'}];release(books);
  assert.equal(await loading,false);assert.equal(state.at(-1).id,'new');
});
test('failed or malformed refresh preserves shelf and permits the next explicit read',async()=>{
  let state=books,calls=0;
  const sync=createShelfRefresh({load:async()=>{if(++calls===1)throw new Error('offline');if(calls===2)return {};return [];},current:()=>state,apply:x=>state=x});
  await assert.rejects(sync(),/offline/);assert.equal(state,books);
  await assert.rejects(sync(),/书架响应无效/);assert.equal(state,books);
  assert.equal(await sync(),true);assert.deepEqual(state,[]);
});

await import('../../src/main/resources/static/reader-navigation.js');
test('a late first paint or refresh cannot replace a typed page number',()=>{
  const field={value:'1'},input=globalThis.BookReaderNavigation.createPageInput();
  field.value='2';input.edit('a');input.project(field,1,'a');assert.equal(field.value,'2');
  field.value='';input.project(field,1,'a');assert.equal(field.value,'','partial editing is preserved too');
  input.reset();input.project(field,3,'a');assert.equal(field.value,'3','explicit navigation owns the new value');
});
test('page input draft never crosses a book boundary',()=>{
  const field={value:'22'},input=globalThis.BookReaderNavigation.createPageInput();
  input.edit('a');input.project(field,1,'b');assert.equal(field.value,'1');
  input.reset();input.project(field,5,'a');assert.equal(field.value,'5');
});
