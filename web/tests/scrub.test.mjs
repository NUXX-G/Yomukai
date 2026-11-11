import test from 'node:test';
import assert from 'node:assert/strict';
import { createScrubber, fillPercent } from '../src/lib/scrub.js';

test('fill follows the value and stays in 0..100', () => {
  assert.equal(fillPercent(30, 120), 25);
  assert.equal(fillPercent(5, 0), 0);
  assert.equal(fillPercent(NaN, 100), 0);
  assert.equal(fillPercent(200, 100), 100);
});

// F1.2: the thumb moved to 2 min but the filled part stayed at 0:06 while the video was still seeking.
test('a click on the bar shows the new place at once, while the video is still seeking', () => {
  const s = createScrubber();
  s.clock(6, false);
  s.pick(120);
  s.release();
  assert.equal(s.clock(6, true), 120, 'a late tick from the old place must not pull the bar back');
  assert.equal(s.clock(120, true), 120);
  assert.equal(s.clock(120.4, false), 120.4, 'once there, the video drives it again');
  assert.equal(s.busy, false);
});

test('while dragging, the video clock never moves the bar', () => {
  const s = createScrubber();
  s.pick(50);
  assert.equal(s.clock(10, false), 50);
  s.pick(80);
  assert.equal(s.clock(50, false), 80);
  s.release();
  assert.equal(s.clock(80.2, false), 80.2);
});

test('a seek that the page makes (keys, ±10 s, the room) is shown at once', () => {
  const s = createScrubber();
  s.clock(30, false);
  s.jump(40);
  assert.equal(s.value, 40);
  assert.equal(s.clock(30, true), 40);
  assert.equal(s.clock(40, false), 40);
});

test('a seek that fails does not leave the bar stuck: when seeking ends the real time wins', () => {
  const s = createScrubber();
  s.pick(300);
  s.release();
  assert.equal(s.clock(6, false), 6);
});
