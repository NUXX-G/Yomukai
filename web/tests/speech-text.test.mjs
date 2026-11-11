import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { normalizeSpeech, cardinalEs, yearEn } from '../src/lib/speech-text.js';

// F6.1: the same cases as the Kotlin side (SpeechTextTest).
const cases = JSON.parse(readFileSync(new URL('../../shared/src/test/resources/speech_text.json', import.meta.url), 'utf8'));

test('the shared cases read the same in the web', () => {
  const wrong = cases.filter((c) => normalizeSpeech(c.in, c.lang) !== c.out).map((c) => `${c.in}\n  esperado: ${c.out}\n  sale:     ${normalizeSpeech(c.in, c.lang)}`);
  assert.equal(wrong.join('\n'), '');
});

test('numbers', () => {
  assert.equal(cardinalEs(31000), 'treinta y un mil');
  assert.equal(cardinalEs(2300000), 'dos millones trescientos mil');
  assert.equal(yearEn(2026), 'twenty twenty-six');
});
