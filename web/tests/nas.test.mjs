import { test } from 'node:test';
import assert from 'node:assert/strict';
import { recommendedQuality, qualityOf } from '../src/lib/nas.js';

test('alone at home upload of 20 Mbps plays the original', () => {
  assert.equal(recommendedQuality(1, 20), 'orig');
});

test('the more people the lower the quality', () => {
  assert.equal(recommendedQuality(2, 20), '8000');
  assert.equal(recommendedQuality(4, 20), '4000');
  assert.equal(recommendedQuality(8, 20), '1500');
  assert.equal(recommendedQuality(40, 20), '1500');
});

test('quality of a NAS link and of anything else', () => {
  assert.equal(qualityOf('/api/jellyfin/stream/abc?q=4000'), '4000');
  assert.equal(qualityOf('/api/stream?u=x'), null);
});
