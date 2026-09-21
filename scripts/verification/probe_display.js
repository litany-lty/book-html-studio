const fs = require('fs');
const src = fs.readFileSync('src/main/resources/static/reading-layout.js', 'utf8');
const sandbox = { console };
sandbox.globalThis = sandbox;
sandbox.window = sandbox;
sandbox.document = { createElement: () => { throw new Error('no-dom'); } };
const vm = require('vm');
vm.createContext(sandbox);
vm.runInContext(src, sandbox);
const R = sandbox.BookReadingLayout;
const assert = require('assert');
const eq = (a, b) => assert.strictEqual(JSON.stringify(a), JSON.stringify(b));
eq(R.resolveReadingText({resolved: false}, '末', '末', 'original', {mode: 'confirmed'}), {text: '末', provenance: 'SOURCE', unresolved: true});
eq(R.resolveReadingText({resolved: false, inferredText: '未'}, '末', '末', 'simplified', {mode: 'confirmed'}), {text: '末', provenance: 'SOURCE', unresolved: true});
eq(R.resolveReadingText({resolved: false, inferredText: '未'}, '末', '末', 'simplified', {mode: 'assisted', recommendation: '本'}), {text: '本', provenance: 'ASSISTED', unresolved: true});
eq(R.resolveReadingText({resolved: true, replacement: '本', inferredText: '未'}, '末', '末', 'simplified', {mode: 'assisted', recommendation: '甲'}), {text: '本', provenance: 'CONFIRMED', unresolved: false});
console.log('RESOLVE_READING_TEXT_ALL_PASS');
const prose = {type: 'text', writingMode: 'horizontal-tb', source: 'paddle-aistudio'};
for (const contents of [
  '第一章 ..... (12)\n第二章 ..... (18)\n附录 ..... (32)',
  '引言……一\n正文……二十\n后记……三十',
  'Preface ... IV\nIntroduction ... 1\nAppendix ... 230',
  '第一章 总论 / 13\n阴阳 / 15\n五行 / 16',
]) {
  assert.strictEqual(R.normalizeText(contents, {...prose, original: contents}), contents);
}
assert.strictEqual(R.normalizeText('他迟疑地说……\n我们明天再去。', prose), '他迟疑地说……我们明天再去。');
assert.strictEqual(R.normalizeText('通常，\n逗号后应继续阅读。', prose), '通常，逗号后应继续阅读。');
assert.strictEqual(R.normalizeText('章节索引 ... 12\n这仍是普通正文的一行。\n继续说明。', prose), '章节索引 ... 12这仍是普通正文的一行。继续说明。');
console.log('CONTENTS_AND_PROSE_LINE_BOUNDARIES_PASS');
assert.strictEqual(R.normalizeText('这是 $ \\uwave{\\text{客观存在}} $ 与 $ ^{①} $ 标记。', prose), '这是 客观存在 与 ① 标记。');
assert.strictEqual(R.normalizeText('$ \\underline{\\text{第一章}} $ 总论 / 13\n阴阳 / 15\n五行 / 16', prose), '第一章 总论 / 13\n阴阳 / 15\n五行 / 16');
assert.strictEqual(R.normalizeText('公式 $ x^{2} + y = 3 $', prose), '公式 $ x^{2} + y = 3 $');
assert.strictEqual(R.normalizeText('$ \\underline{\\text{甲}} $', {...prose, type: 'formula'}), '$ \\underline{\\text{甲}} $');
console.log('SAFE_PROSE_FORMATTING_PASS');
