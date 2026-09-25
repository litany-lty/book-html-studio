import assert from 'node:assert/strict';
import { test } from 'node:test';
let serial = 0;
async function fixture(mobile = false) {
  const saved = {window:globalThis.window, document:globalThis.document, localStorage:globalThis.localStorage};
  const events = new Map(), values = new Map();
  let focused = null, narrow = mobile;
  const element = (id, visible = true) => ({id,isConnected:true,disabled:false,visible,hidden:false,attributes:{},listeners:new Map(),
    classList:{values:new Set(),toggle(k,on){if(on===undefined)on=!this.values.has(k);on?this.values.add(k):this.values.delete(k);},remove(k){this.values.delete(k);},contains(k){return this.values.has(k);}},
    setAttribute(k,v){this.attributes[k]=v;},getClientRects(){return this.visible?[{}]:[];},focus(){focused=this.id;},
    addEventListener(k,f){const a=this.listeners.get(k)||[];a.push(f);this.listeners.set(k,a);},
    click(){for(const fn of this.listeners.get('click')||[])fn({currentTarget:this});}});
  const nodes=Object.fromEntries(['proof-toggle','review-toggle','review-panel','drawer-scrim','toc-toggle','toc-panel','close-review'].map(id=>['#'+id,element(id)]));
  nodes['#proof-toggle'].visible=!mobile;nodes['#review-toggle'].visible=mobile;
  const media={get matches(){return narrow;},addEventListener(type,f){events.set('media-'+type,f);}};
  const doc={body:{dataset:{}},getElementById(){return null;},querySelector(s){return nodes[s]||null;},
    addEventListener(type,f){const a=events.get(type)||[];a.push(f);events.set(type,a);}};
  globalThis.document=doc;globalThis.window={matchMedia:()=>media};
  globalThis.localStorage={getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v)};
  const module=await import(new URL('../../src/main/resources/static/reader-mode.js?test='+ ++serial,import.meta.url));
  const changes=[];module.initReaderMode({onChange:mode=>changes.push(mode)});
  return {nodes,doc,module,changes,focused:()=>focused,values,events,setNarrow(v){narrow=v;events.get('media-change')?.();},
    close(){Object.assign(globalThis,saved);}};
}
test('desktop controls render once per transition and publish one coherent mode', async()=>{
  const f=await fixture();try {
    f.nodes['#proof-toggle'].click();assert.equal(f.module.isProofMode(),true);
    assert.equal(f.nodes['#review-panel'].classList.contains('open'),true);
    assert.equal(f.nodes['#drawer-scrim'].hidden,true);
    f.nodes['#proof-toggle'].click();assert.equal(f.module.isProofMode(),false);
    assert.deepEqual(f.changes,['reading','proof','reading']);assert.equal(f.focused(),'proof-toggle');
  }finally{f.close();}
});
test('mobile close uses its visible opener, not hidden desktop control',async()=>{
  const f=await fixture(true);try{
    f.nodes['#review-toggle'].click();assert.equal(f.nodes['#drawer-scrim'].hidden,false);
    f.nodes['#close-review'].click();assert.equal(f.focused(),'review-toggle');
    assert.equal(f.nodes['#review-toggle'].attributes['aria-expanded'],'false');
    assert.equal(f.nodes['#review-panel'].classList.contains('open'),false);
  }finally{f.close();}
});
test('programmatic proof entry synchronizes mode and callbacks',async()=>{
  const f=await fixture();try{f.module.enterProofMode();assert.equal(f.module.isProofMode(),true);assert.equal(f.changes.at(-1),'proof');f.module.closeProofMode();assert.equal(f.focused(),null);}finally{f.close();}
});
test('initializing twice cannot attach a second click handler',async()=>{
  const f=await fixture();try{f.module.initReaderMode({onChange:m=>f.changes.push(m)});f.nodes['#proof-toggle'].click();assert.equal(f.module.isProofMode(),true);assert.equal(f.nodes['#proof-toggle'].listeners.get('click').length,1);}finally{f.close();}
});
test('mode change closes competing mobile navigation and follows viewport changes',async()=>{
  const f=await fixture(true);try{
    f.nodes['#toc-panel'].classList.toggle('open',true);f.module.enterProofMode();
    assert.equal(f.nodes['#toc-panel'].classList.contains('open'),false);assert.equal(f.nodes['#toc-toggle'].attributes['aria-expanded'],'false');
    f.setNarrow(false);assert.equal(f.module.isProofMode(),true);assert.equal(f.nodes['#drawer-scrim'].hidden,true);
  }finally{f.close();}
});
test('Escape respects an open dialog and does not discard mode underneath it',async()=>{
  const f=await fixture();try{
    f.module.enterProofMode();f.nodes['dialog[open]']={};
    const e={key:'Escape',preventDefault(){this.defaultPrevented=true;}};
    for(const fn of f.events.get('keydown'))fn(e);assert.equal(f.module.isProofMode(),true);
    delete f.nodes['dialog[open]'];for(const fn of f.events.get('keydown'))fn(e);
    assert.equal(f.module.isProofMode(),false);assert.equal(e.defaultPrevented,true);
  }finally{f.close();}
});
