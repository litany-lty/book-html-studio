// Maintainer-only asset refresh. Normal builds and readers do not use the network.
// Node 24+: add --use-env-proxy if this machine needs its existing HTTP(S) proxy.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const destination = fileURLToPath(new URL('../src/main/resources/static/fonts/', import.meta.url));
const userAgent = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36';
const families = [
  { family: 'Noto Serif SC', id: 'noto-serif-sc', license: 'notoserifsc' },
  { family: 'Noto Sans SC', id: 'noto-sans-sc', license: 'notosanssc' },
  { family: 'LXGW WenKai TC', id: 'lxgw-wenkai-tc', license: 'lxgwwenkaitc' }
];
const artifacts = [];
async function download(url) {
  const host = new URL(url).hostname;
  if (!['fonts.googleapis.com', 'fonts.gstatic.com', 'raw.githubusercontent.com'].includes(host)) throw new Error('Unexpected font host');
  const response = await fetch(url, { headers: { 'User-Agent': userAgent }, signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`Font download failed: ${response.status} ${host}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length > 5 * 1024 * 1024) throw new Error('Unexpected font asset size');
  return bytes;
}
async function save(filename, bytes, source) {
  if (!/^[a-z0-9-]+\.(woff2|css|txt)$/.test(filename)) throw new Error('Invalid asset filename');
  await fs.writeFile(path.join(destination, filename), bytes);
  artifacts.push({ file: filename, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'), source });
}
async function pool(items, operation) {
  let index = 0;
  await Promise.all(Array.from({ length: 6 }, async () => {
    while (index < items.length) { const current = index++; await operation(items[current], current); }
  }));
}
await fs.mkdir(destination, { recursive: true });
for (const font of families) {
  const cssSource = `https://fonts.googleapis.com/css2?family=${encodeURIComponent(font.family)}:wght@400&display=swap`;
  let css = (await download(cssSource)).toString('utf8');
  const urls = [...new Set([...css.matchAll(/url\((https:[^)]+)\)/g)].map(match => match[1]))];
  if (!urls.length || urls.length > 160 || urls.some(url => !url.endsWith('.woff2'))) throw new Error('Expected bounded WOFF2 font subsets');
  await pool(urls, async (url, index) => {
    const filename = `${font.id}-${String(index + 1).padStart(3, '0')}.woff2`;
    const bytes = await download(url);
    if (bytes.subarray(0, 4).toString('ascii') !== 'wOF2') throw new Error('Invalid WOFF2 header');
    await save(filename, bytes, url);
  });
  urls.forEach((url, index) => { css = css.replaceAll(url, `${font.id}-${String(index + 1).padStart(3, '0')}.woff2`); });
  if (/https?:\/\//.test(css)) throw new Error('Remote URL remains in font stylesheet');
  await save(`${font.id}.css`, Buffer.from(css), cssSource);
  const licenseSource = `https://raw.githubusercontent.com/google/fonts/main/ofl/${font.license}/OFL.txt`;
  const license = await download(licenseSource);
  if (!license.toString('utf8').includes('SIL OPEN FONT LICENSE')) throw new Error('Expected SIL OFL license');
  await save(`${font.id}-ofl.txt`, license, licenseSource);
  console.log(`${font.family}: ${urls.length} local subsets`);
}
const monoSource = 'https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/fonts/webfonts/JetBrainsMono-Regular.woff2';
const mono = await download(monoSource);
if (mono.subarray(0, 4).toString('ascii') !== 'wOF2') throw new Error('Invalid JetBrains Mono font');
await save('jetbrains-mono-regular.woff2', mono, monoSource);
const monoLicenseSource = 'https://raw.githubusercontent.com/JetBrains/JetBrainsMono/v2.304/OFL.txt';
const monoLicense = await download(monoLicenseSource);
if (!monoLicense.toString('utf8').includes('SIL OPEN FONT LICENSE')) throw new Error('Expected JetBrains license');
await save('jetbrains-mono-ofl.txt', monoLicense, monoLicenseSource);
artifacts.sort((a, b) => a.file.localeCompare(b.file));
await fs.writeFile(path.join(destination, 'manifest.json'), JSON.stringify({ schemaVersion: 1, assets: artifacts }, null, 2) + '\n');
console.log(JSON.stringify({ files: artifacts.length, totalBytes: artifacts.reduce((sum, item) => sum + item.bytes, 0) }));
