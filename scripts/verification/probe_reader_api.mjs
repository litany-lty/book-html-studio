import assert from 'node:assert/strict';
import {test} from 'node:test';
import {api} from '../../src/main/resources/static/api.js';
async function fixture(response,run){
  const oldWindow=globalThis.window,oldFetch=globalThis.fetch;
  globalThis.window={setTimeout,clearTimeout};globalThis.fetch=async()=>new Response(JSON.stringify(response),{headers:{'Content-Type':'application/json'}});
  try{await run();}finally{globalThis.window=oldWindow;globalThis.fetch=oldFetch;}
}
test('reader rejects a response belonging to a different page',()=>fixture({pageNumber:9,blocks:[]},async()=>{
  await assert.rejects(api.page('book',3),/响应与请求页/);
}));
test('reader accepts the requested page without cloning or rewriting its content',()=>fixture({pageNumber:3,blocks:[{original:'原稿'}],revision:2},async()=>{
  assert.equal((await api.page('book',3)).blocks[0].original,'原稿');
}));
test('missing page blocks are not accepted as an empty, successfully read page',()=>fixture({pageNumber:3},async()=>{
  await assert.rejects(api.page('book',3));
}));
test('numeric strings do not coerce into a valid response page identity',()=>fixture({pageNumber:'3',blocks:[]},async()=>{
  await assert.rejects(api.page('book',3));
}));
