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

test('texts the server sends (achievements, built-in themes, switches, rows) are translated too', () => {
  const root = new URL('../../', import.meta.url);
  const read = (p) => readFileSync(new URL(p, root), 'utf8');
  const ach = JSON.parse(read('server/src/main/resources/achievements.json'));
  const themes = JSON.parse(read('shared/src/main/resources/design/themes.json'));
  const features = [...read('server/src/main/kotlin/com/nelson/yomukai/server/Features.kt').matchAll(/Feature\("[^"]+", "([^"]+)", "([^"]+)"\)/g)].flatMap((m) => [m[1], m[2]]);
  const rows = [...read('server/src/main/kotlin/com/nelson/yomukai/server/Discover.kt').match(/val TITLES = mapOf\(([\s\S]*?)\)/)[1].matchAll(/" to "([^"]+)"/g)].map((m) => m[1]);
  const all = [
    ...(ach.achievements || ach).flatMap((a) => [a.name, a.description]),
    ...(themes.themes || themes).map((t) => t.name),
    ...features, ...rows, 'Logro secreto'
  ];
  assert.ok(features.length > 10 && rows.length > 5);
  assert.deepEqual(all.filter((k) => !dict[k]), []);
});
