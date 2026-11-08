// The browser's port must behave like shared/…/data/ocr. Run with: npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readingOrder, directionFor, stripMs, analyze, wordsIn } from '../src/lib/ocr.js';

const box = (x, y, text) => ({ x, y, w: 0.1, h: 0.05, text, kind: 'BUBBLE' });

test('reading order by kind of work', () => {
  const boxes = [box(0.4, 0.6, 'abajo'), box(0.1, 0.1, 'izquierda'), box(0.7, 0.12, 'derecha')];
  assert.deepEqual(readingOrder(boxes, 'rtl').map((b) => b.text), ['derecha', 'izquierda', 'abajo']);
  assert.deepEqual(readingOrder(boxes, 'ltr').map((b) => b.text), ['izquierda', 'derecha', 'abajo']);
  assert.equal(directionFor('MANHWA'), 'down');
  assert.equal(directionFor('MANGA'), 'rtl');
});

test('strip times like ScrollPlanner', () => {
  assert.equal(stripMs({ words: 22 }, 220), 6400);
  assert.equal(stripMs({ empty: true }), 250);
  assert.ok(stripMs({ detail: 1 }) > stripMs({ detail: 0 }));
});

test('picture analysis and words per band', () => {
  assert.deepEqual(analyze(new Array(400).fill(250), 20), { empty: true, detail: 0, textLike: false });
  // A shrunk bubble (white with grey smudges) is read like text, slower than a dark busy drawing.
  const bubble = Array.from({ length: 400 }, (_, i) => (i % 7 === 0 ? 150 : 250));
  assert.ok(analyze(bubble, 20).textLike);
  assert.ok(stripMs(analyze(bubble, 20)) > stripMs({ detail: 1 }));
  assert.equal(analyze(Array.from({ length: 400 }, (_, i) => (i % 2 ? 255 : 0)), 20).detail, 1);
  const boxes = [{ ...box(0, 0.1, 'Hola qué tal') }, { ...box(0, 0.8, 'BOOM'), kind: 'SFX' }];
  assert.equal(wordsIn(boxes, 0, 0.5), 3);
  assert.equal(wordsIn(boxes, 0.5, 1), 0);
});
