import assert from 'node:assert/strict';
import { test } from 'node:test';
import { allowsAutomaticReading as permits } from '../../src/main/resources/static/auto-reading-policy.js';
const book = 'book-A', provider = 'paddle-aistudio';
const consent = { consentId:'11111111-1111-4111-8111-111111111111', scope:{kind:'BOOK',bookId:book},
  allowedProviders:[provider], mode:'AUTO_CURRENT', expiresAt:null };
const policy = {schemaVersion:1,policyRevision:1,defaultWindow:{mode:'AUTO_CURRENT'},validConsents:[consent]};
test('missing, unrecognized and malformed policy never auto-dispatches', () => {
  for (const value of [null,{}, {...policy,schemaVersion:2},{...policy,validConsents:{}},{...policy,policyRevision:'1'}]) assert.equal(permits(value,book,provider),false);
});
test('configured provider requires a server consent in this book', () => {
  assert.equal(permits({...policy,validConsents:[]},book,provider),false);
  assert.equal(permits(policy,'book-B',provider),false);
  assert.equal(permits(policy,book,'ppocr'),false);
  assert.equal(permits(policy,book,provider),true);
});
test('explicit all-book server grant is reusable without repeating confirmation', () => {
  const global={...policy,validConsents:[{...consent,scope:{kind:'ALL_BOOKS'},allowedProviders:['*']}]};
  assert.equal(permits(global,'book-B','ppocr'),true);
});
test('expiry and invalid date are never treated as a permanent grant', () => {
  for (const expiry of ['invalid','2026-01-01T00:00:00Z']) {
    assert.equal(permits({...policy,validConsents:[{...consent,expiresAt:expiry}]},book,provider,Date.parse('2026-02-01')),false);
  }
});
test('manual-only policy and consent do not trigger automatic starts', () => {
  assert.equal(permits({...policy,defaultWindow:{mode:'MANUAL'}},book,provider),false);
  assert.equal(permits({...policy,validConsents:[{...consent,mode:'MANUAL'}]},book,provider),false);
});
