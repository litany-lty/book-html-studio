import { test } from 'node:test';
import assert from 'node:assert/strict';
import { settledJobSummary as summary } from '../../src/main/resources/static/job-summary.js';

test('cancelled and interrupted jobs cannot claim all-success with an empty error array', () => {
  for (const status of ['CANCELLED', 'INTERRUPTED', 'FAILED']) {
    const text = summary({ status, total: 20, completed: 3 }, 0);
    assert.ok(!text.includes('全部成功') && !text.includes('本轮处理完成'));
    assert.ok(text.includes('17 页未完成'));
  }
});
test('completed workflow is distinct from character accuracy', () => {
  const text = summary({ status: 'COMPLETED', total: 20, completed: 20 });
  assert.ok(text.includes('本轮处理完成') && text.includes('疑点'));
});
test('partial status and incomplete count do not become successful', () => {
  assert.ok(summary({ status: 'COMPLETED_WITH_ERRORS', total: 20, completed: 20 }).includes('部分'));
  assert.ok(summary({ status: 'COMPLETED', total: 20, completed: 3 }).includes('状态待确认'));
});
test('malformed count cannot display negative, NaN, or infinite progress', () => {
  assert.equal(summary({ total: Infinity, completed: 1 }), '');
  assert.ok(summary({ status: 'FAILED', total: 5, completed: -4 }).includes('0 / 5'));
});
