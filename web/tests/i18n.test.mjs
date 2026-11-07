import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { keys, DICT } from '../scripts/i18n-keys.mjs';

const dict = JSON.parse(readFileSync(DICT, 'utf8'));
const slots = (s) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(',');

test('every interface text has its English translation', () => {
  const missing = keys().filter((k) => typeof dict[k] !== 'string' || !dict[k].trim());
  assert.deepEqual(missing, [], `Sin traducir (añádelos a shared/src/main/resources/i18n/en.json):\n${missing.join('\n')}`);
});

test('translations keep the same {slots}', () => {
  const wrong = Object.entries(dict).filter(([es, en]) => slots(es) !== slots(en)).map(([es]) => es);
  assert.deepEqual(wrong, []);
});

test('no markup in translations (they are shown as text)', () => {
  assert.deepEqual(Object.values(dict).filter((v) => /<[a-z/!]/i.test(v)), []);
});
