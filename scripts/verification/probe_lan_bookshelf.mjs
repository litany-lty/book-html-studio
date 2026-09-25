import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readingEntry, shelfEntries, SHELF_PAGE_SIZE } from '../../src/main/resources/static/bookshelf.js';
import { hasReaderCapability } from '../../src/main/resources/static/lan-reader.js';
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
