import { test } from 'node:test';
import assert from 'node:assert/strict';
import { emptyReadingMessage, statusMessage, qualityOf } from '../../src/main/resources/static/reader.js';

const empty = { status: 'READY', provider: 'paddle-aistudio', blocks: [], sourceRecords: [], warnings: [] };
test('legacy empty READY is not certified blank', () => {
  assert.match(statusMessage(empty), /不能据此判定/);
  assert.equal(qualityOf(empty).tone, 'warning');
  assert.doesNotMatch(statusMessage(empty), /本页为空白页或仅含插图/);
});
test('legacy +blank marker needs new image evidence before certification', () => {
  const page = { ...empty, provider: 'paddle-aistudio+blank' };
  assert.match(emptyReadingMessage(page), /不能据此判定/);
  assert.equal(qualityOf(page).tone, 'warning');
  const proven = { ...page, warnings: ['[BLANK_EVIDENCE_V2] 图像检查'] };
  assert.match(emptyReadingMessage(proven), /经图像检查为近空白/);
});
test('filtered text and original source are not absence of text', () => {
  for (const field of ['blocks', 'sourceRecords']) {
    assert.match(emptyReadingMessage({ ...empty, [field]: [{ original: '竖排文字' }] }), /未进入阅读排版/);
  }
});
test('partial recovery remains warning, not a finished transcription', () => {
  const partial = { ...empty, blocks: [{ original: '局部片段', type: 'text' }], warnings: ['[OCR_RECOVERY_PARTIAL] 原图核对'] };
  assert.equal(qualityOf(partial).label, '仅恢复部分文字');
  assert.equal(qualityOf(partial).tone, 'warning');
  assert.equal(qualityOf({ ...partial, reviewed: true }).label, '已人工校对');
});
test('failed and pending states are not changed to success', () => {
  assert.equal(statusMessage({ ...empty, status: 'FAILED', error: 'OCR_EMPTY_UNRESOLVED' }), 'OCR_EMPTY_UNRESOLVED');
  assert.match(statusMessage({ ...empty, status: 'PENDING' }), /尚未识别/);
});
