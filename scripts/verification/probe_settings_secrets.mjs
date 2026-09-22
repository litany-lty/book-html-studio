import assert from 'node:assert/strict';
import { secretFields, buildSecretUpdates, secretValidation, scrubSecretInputs, wipeSecretPayload,
  syncSecretControls, secretStorageMessage } from '../../src/main/resources/static/settings-secret-store.js';
const fields = Object.fromEntries(secretFields.flat().map(name => [name, { value: '', checked: false, disabled: false, type: 'password' }]));
const buttons = secretFields.map(() => ({ disabled: false, setAttribute(name, value) { this[name] = value; } }));
const form = { elements: { namedItem: name => fields[name] }, querySelectorAll: () => buttons };
let checks = 0;
function check(condition) { assert.ok(condition); checks++; }
check(Object.keys(buildSecretUpdates(form, true)).length === 0);
fields.qwenApiKey.value = 'test-only-qwen-canary';
check(buildSecretUpdates(form, true).qwenApiKey.value === fields.qwenApiKey.value);
check(Object.keys(buildSecretUpdates(form, false)).length === 0);
check(Object.keys(buildSecretUpdates(form, undefined)).length === 0);
fields.clearQwenApiKey.checked = true;
check(Boolean(secretValidation(form))); assert.throws(() => buildSecretUpdates(form, true)); checks++;
fields.qwenApiKey.value = ''; check(buildSecretUpdates(form, true).qwenApiKey.clearSecret === true);
fields.clearQwenApiKey.checked = false;
for (const invalid of ['****', 'REDACTED', '   ', 'bad\nkey']) { fields.qwenApiKey.value = invalid; check(Boolean(secretValidation(form))); }
fields.qwenApiKey.value = 'test-only-canary'; const body = { revision: 7, secretUpdates: buildSecretUpdates(form, true) };
wipeSecretPayload(body); check(body.revision === 7 && !JSON.stringify(body).includes('test-only'));
scrubSecretInputs(form); check(secretFields.every(([name, clear]) => fields[name].value === '' && !fields[clear].checked && fields[name].type === 'password'));
syncSecretControls(form, { secretStorage: { mode: 'ENV_ONLY', writable: false } });
check(secretFields.flat().every(name => fields[name].disabled)); check(buttons.every(button => button.disabled));
syncSecretControls(form, { secretStorage: { writable: true } }, true); check(secretFields.flat().every(name => fields[name].disabled));
syncSecretControls(form, { secretStorage: { writable: true } }, false); check(secretFields.flat().every(name => !fields[name].disabled));
syncSecretControls(form, null); check(buttons.every(button => button.disabled));
check(secretStorageMessage({ secretStorage: { mode: 'ENV_ONLY' } }).includes('环境注入'));
console.log(JSON.stringify({ suite: 'settings-secret-controls', checks, failures: 0, cloudCalls: 0 }));
