import { readFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// 有界样页探测；不在参数、日志或结果文件中包含凭证。
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const envText = await readFile(path.join(root, '.env'), 'utf8');
const config = Object.fromEntries(envText.split(/\r?\n/).filter(l => /^[A-Z_]+=/.test(l)).map(l => {
  const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1).replace(/^(['"])(.*)\1$/, '$2')];
}));
const [provider = 'qwen', imagePath, sourcePath] = process.argv.slice(2);
if (!imagePath || !['qwen', 'minimax', 'minimax-layout'].includes(provider)) throw new Error('用法：node scripts/probe-cloud.mjs qwen|minimax|minimax-layout 图片路径 [页JSON]');
const data = await readFile(imagePath);
if (data.length > 9_000_000) throw new Error('样图过大，先缩小样图');
const image = `data:image/png;base64,${data.toString('base64')}`;
const qwen = provider === 'qwen';
const key = config[qwen ? 'DASHSCOPE_API_KEY' : 'MINIMAX_API_KEY'];
if (!key) throw new Error('对应服务未配置密钥');
const base = config[qwen ? 'QWEN_BASE_URL' : 'MINIMAX_BASE_URL'].replace(/\/$/, '');
const model = config[qwen ? 'QWEN_MODEL' : 'MINIMAX_MODEL'];
const url = base + (qwen ? '/services/aigc/multimodal-generation/generation' : '/chat/completions');
const body = qwen ? {
  model, input: { messages: [{ role: 'user', content: [{ image, min_pixels: 3072, max_pixels: 8388608, enable_rotate: false }] }] },
  parameters: { ocr_options: { task: 'advanced_recognition' } },
} : {
  model, max_completion_tokens: 1200, thinking: { type: 'disabled' }, reasoning_split: true,
  messages: [{ role: 'user', content: [
    { type: 'image_url', image_url: { url: image, detail: 'high' } },
    { type: 'text', text: '这是PDF转HTML工具的样页连通性验证。请只返回JSON对象，包含writingMode（vertical-rl或horizontal-tb）、hasDiagram（布尔）、readingOrder（简述阅读顺序）、sampleText（原样抄录最清楚的最多30个汉字，不要改成简体）、warnings（数组）。不要编造模糊字。' },
  ] }],
};
if (provider === 'minimax-layout') {
  const page = JSON.parse(await readFile(sourcePath, 'utf8'));
  const sources = (page.sourceRecords || page.blocks).map((b, i) => ({ id: b.id, readingOrder: i, text: b.original, bbox: b.bbox }));
  const code = await readFile(path.join(root, 'src/main/java/studio/bookhtml/service/MiniMaxVisionClient.java'), 'utf8');
  const prefix = code.match(/String prompt=("(?:\\.|[^"\\])*")\+layout/);
  if (!prefix) throw new Error('无法找到当前版面提示词');
  body.max_completion_tokens = 8192;
  body.messages[0].content = [{type:'text', text:JSON.parse(prefix[1])+'vertical。sourceLines='+JSON.stringify(sources)}, {type:'image_url',image_url:{url:image,detail:'high'}}];
}
const began = Date.now();
try {
  const response = await fetch(url, { method: 'POST', headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(90000) });
  const raw = await response.text();
  const safe = Object.values(config).filter(v => v.startsWith('sk-')).reduce((s, v) => s.split(v).join('[REDACTED]'), raw);
  let result;
  try { result = JSON.parse(safe); } catch { result = { error: '上游返回非 JSON', bodyLength: raw.length }; }
  await mkdir(path.join(root, 'verification'), { recursive: true });
  const report = { provider, model, status: response.status, elapsedMs: Date.now() - began, result };
  await writeFile(path.join(root, 'verification', `probe-${provider}.json`), JSON.stringify(report, null, 2), { mode: 0o600 });
  const content = qwen ? result.output?.choices?.[0]?.message?.content : result.choices?.[0]?.message?.content;
  const words = Array.isArray(content) ? content.flatMap(c => c.ocr_result?.words_info || []) : [];
  console.log(JSON.stringify({ provider, model, status: response.status, elapsedMs: report.elapsedMs, code: result.code || result.error?.code || result.base_resp?.status_code, message: result.message || result.error?.message || result.base_resp?.status_msg, usage: result.usage, finishReason: result.choices?.[0]?.finish_reason, lineCount: words.length, sample: qwen ? words.slice(0, 3) : provider === 'minimax-layout' ? String(content).slice(0,180) : content }, null, 2));
  if (!response.ok || result.error || (result.base_resp && result.base_resp.status_code !== 0)) process.exitCode = 1;
} catch (error) {
  console.error(JSON.stringify({ provider, error: error.name, elapsedMs: Date.now() - began }));
  process.exitCode = 1;
}
