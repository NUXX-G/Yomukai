import test from 'node:test';
import assert from 'node:assert/strict';

import { continueTarget } from '../src/lib/continue.js';

const eps = [1, 2, 3, 4, 5].map((n, i) => ({ id: 'e' + n, index: i, number: n }));

test('nothing watched: start at the first one', () => {
  assert.deepEqual(continueTarget(eps, {}), { chapter: eps[0], started: false });
});

// F1.5: switched from the Spanish edition to the English one after watching up to 3 in Spanish.
test('watched in another language counts: continue after the furthest one', () => {
  const elsewhere = { e1: { read: true, elsewhere: true }, e2: { read: true, elsewhere: true }, e3: { read: true, elsewhere: true } };
  assert.equal(continueTarget(eps, elsewhere).chapter.id, 'e4');
});

test('what was opened here last still wins over what was read elsewhere', () => {
  const p = { e1: { read: true, elsewhere: true }, e2: { read: true, elsewhere: true }, e5: { read: false, updatedAt: 99 } };
  assert.deepEqual(continueTarget(eps, p), { chapter: eps[4], started: true });
});
