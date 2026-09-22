const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticRoot = path.resolve(__dirname, '../../src/main/resources/static');
const fontsRoot = path.join(staticRoot, 'fonts');
const source = fs.readFileSync(path.join(staticRoot, 'reader-fonts.js'), 'utf8');
const manifest = JSON.parse(fs.readFileSync(path.join(fontsRoot, 'manifest.json'), 'utf8'));
assert.equal(manifest.schemaVersion, 1);
assert.ok(manifest.assets.length > 10);
const names = new Set();
for (const entry of manifest.assets) {
  assert.match(entry.file, /^[a-z0-9-]+\.(woff2|css|txt)$/);
  assert.ok(!names.has(entry.file), `duplicate asset: ${entry.file}`);
  names.add(entry.file);
  const bytes = fs.readFileSync(path.join(fontsRoot, entry.file));
  assert.equal(bytes.length, entry.bytes, entry.file);
  assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), entry.sha256, entry.file);
  if (entry.file.endsWith('.woff2')) assert.equal(bytes.subarray(0, 4).toString('ascii'), 'wOF2');
  if (entry.file.endsWith('-ofl.txt')) assert.match(bytes.toString(), /SIL OPEN FONT LICENSE/);
}
assert.ok(names.has('jetbrains-mono-regular.woff2'));
assert.equal([...names].filter(name => name.endsWith('-ofl.txt')).length, 4);
for (const filename of [...names].filter(name => name.endsWith('.css')).concat('reader-fonts.css')) {
  const css = fs.readFileSync(path.join(fontsRoot, filename), 'utf8');
  assert.doesNotMatch(css, /https?:\/\//, `${filename} must be offline`);
  for (const match of css.matchAll(/url\(["']?([^\s)"']+)["']?\)/g)) {
    assert.ok(names.has(match[1]), `${filename}: missing ${match[1]}`);
  }
}

function element() {
  return {
    tagName: 'SELECT', value: '', children: [], events: new Map(), textContent: '',
    classList: { add() {} },
    replaceChildren(...children) { this.children = children; },
    addEventListener(name, callback) { this.events.set(name, callback); },
    removeEventListener(name) { this.events.delete(name); }
  };
}
function runtime(storage) {
  const attributes = {};
  const context = { localStorage: storage, document: {
    documentElement: { setAttribute(name, value) { attributes[name] = value; } },
    createElement: element, querySelector: () => null
  } };
  vm.runInNewContext(source, context);
  return { api: context.BookReaderFonts, attributes };
}
const stored = new Map([['book-html:reader-font:v1', 'wenkai']]);
const storage = { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value) };
const { api, attributes } = runtime(storage);
assert.equal(api.current(), 'wenkai');
assert.equal(api.options.length, 6);
const first = element(), second = element(), note = element();
let changeCount = 0;
const dispose = api.init(first, { noteElement: note, onChange: () => changeCount++ });
assert.equal(api.init(first), dispose, 'initialization is idempotent');
api.init(second);
assert.equal(first.children.length, 6);
first.value = 'jetbrains-mono'; first.events.get('change')();
assert.equal(changeCount, 1);
assert.equal(second.value, 'jetbrains-mono');
assert.match(note.textContent, /中文使用内置 Noto 宋体/);
assert.equal(attributes['data-reader-font'], 'jetbrains-mono');
assert.equal(runtime(storage).api.current(), 'jetbrains-mono', 'preference survives reload');
assert.equal(api.set('url(https://invalid.test/inject)'), 'serif');
assert.equal(attributes['data-reader-font'], 'serif');
dispose(); assert.equal(first.events.size, 0);
const denied = runtime({ getItem() { throw new Error('denied'); }, setItem() { throw new Error('quota'); } });
assert.equal(denied.api.current(), 'serif');
assert.equal(denied.api.set('source-sans'), 'source-sans');
assert.equal(denied.api.init(null), null);
assert.equal(denied.api.init({ tagName: 'HTML', replaceChildren() { throw new Error('must not replace root'); } }), null);
console.log(`READER_FONTS_PASS: ${manifest.assets.length} verified local assets; persistence, whitelist, controls and storage fallback`);
