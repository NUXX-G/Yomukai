import test from 'node:test';
import assert from 'node:assert/strict';
import { nextEpisode } from '../src/lib/next-episode.js';

const ep = (number, index = number - 1) => ({ id: 'e' + number, index, number, label: 'Ep. ' + number });
const own = [ep(1), ep(2), ep(3)];

test('plain source: the next number', () => {
  assert.equal(nextEpisode(own[0], own).episode.id, 'e2');
  assert.equal(nextEpisode(own[2], own), null);
});

// F1.1: in a work with several sources the next episode can be only in a sister source.
test('the next episode comes from a sister source when this one stops', () => {
  const others = [{ chapterId: 'x4', sourceId: 'jkanime', mediaId: 'm', number: 4 }, { chapterId: 'x3', sourceId: 'jkanime', mediaId: 'm', number: 3 }];
  const next = nextEpisode(own[2], own, others);
  assert.deepEqual([next.episode.id, next.sourceId, next.mediaId], ['x4', 'jkanime', 'm']);
});

test('this source wins when both have the next number', () => {
  const others = [{ chapterId: 'x2', sourceId: 'b', mediaId: 'm', number: 2 }];
  const next = nextEpisode(own[0], own, others);
  assert.equal(next.episode.id, 'e2');
  assert.equal(next.sourceId, undefined);
});

test('a gap in this source is filled by the sister before jumping ahead', () => {
  const gappy = [ep(1, 0), ep(3, 1)];
  const others = [{ chapterId: 'x2', sourceId: 'b', mediaId: 'm', number: 2 }];
  assert.equal(nextEpisode(gappy[0], gappy, others).episode.id, 'x2');
});

test('playing in the sister source goes back to the main one for the next number', () => {
  const sister = [{ id: 's4', index: 0, number: 4 }];
  const others = [{ chapterId: 'e5', sourceId: 'main', mediaId: 'mm', number: 5 }];
  const next = nextEpisode(sister[0], sister, others);
  assert.deepEqual([next.episode.id, next.sourceId], ['e5', 'main']);
});

test('filler is skipped when asked, the plain next one otherwise', () => {
  const list = [ep(1), ep(2), ep(3)];
  const filler = (c) => c.number === 2;
  assert.equal(nextEpisode(list[0], list, [], filler).episode.id, 'e3');
  assert.equal(nextEpisode(list[0], list, [], () => true).episode.id, 'e2');
});

test('unnumbered episodes (specials) follow the source order', () => {
  const sp = [{ id: 'a', index: 0, number: 0 }, { id: 'b', index: 1, number: 0 }];
  assert.equal(nextEpisode(sp[0], sp).episode.id, 'b');
});

test('half episodes (12.5) come between 12 and 13', () => {
  const list = [ep(12, 0), { id: 'e12.5', index: 1, number: 12.5 }, ep(13, 2)];
  assert.equal(nextEpisode(list[0], list).episode.id, 'e12.5');
});
