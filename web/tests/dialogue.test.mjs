// The web's reading-aloud cut must match the Kotlin one (shared/…/speech/SpeechPlan.kt): the server prepares audio
// overnight with Kotlin, and it is only reused when both cut the text the same way.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { planRoles } from '../src/lib/dialogue.js';

const cases = JSON.parse(readFileSync(new URL('../../shared/src/test/resources/speech_parity.json', import.meta.url), 'utf8'));

test('same cut as SpeechPlan.kt', () => {
  for (const c of cases) {
    const pieces = (characters) => planRoles(c.paragraph, characters).map(({ text, role }) => ({ text, role }));
    assert.deepEqual(pieces(true), c.characters, c.paragraph);
    assert.deepEqual(pieces(false), c.single, c.paragraph);
  }
});
