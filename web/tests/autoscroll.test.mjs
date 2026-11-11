import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createAutoScroll, clampSmart, ease, novelSpeed } from '../src/lib/autoscroll.js';

// F6.2: a fake clock and screen drive the engine frame by frame.
function rig({ speed = 80, fps = 60, height = 100_000, more = false, jank = [] } = {}) {
  let t = 0;
  let y = 0;
  let queued = null;
  let frames = 0;
  const s = {
    stopped: false,
    get y() { return y; },
    height,
    engine: null,
    run(seconds) {
      const end = t + seconds * 1000;
      while (t < end && queued) {
        frames++;
        t += jank.includes(frames) ? 900 : 1000 / fps;
        const cb = queued;
        queued = null;
        cb(t);
      }
    }
  };
  s.engine = createAutoScroll({
    target: () => (typeof speed === 'function' ? speed(y) : speed),
    atEnd: () => y >= s.height - 800,
    waitMore: () => more,
    onStop: () => (s.stopped = true),
    scrollBy: (px) => { y = Math.min(s.height - 800, y + px); },
    frame: (cb) => { queued = cb; return 1; },
    cancel: () => { queued = null; },
    now: () => t
  });
  return s;
}

test('every speed is kept, from very slow to very fast, at 30, 60 and 120 frames a second', () => {
  for (const fps of [30, 60, 120]) {
    for (const speed of [5, 15, 80, 240, 800]) {
      const s = rig({ speed, fps });
      s.engine.start();
      s.run(1); // easing in
      const from = s.y;
      s.run(10);
      const measured = (s.y - from) / 10;
      assert.ok(Math.abs(measured - speed) <= Math.max(1, speed * 0.02), `${speed} px/s at ${fps} fps gave ${measured}`);
    }
  }
});

test('a long frame (busy phone, hidden tab) is not a jump', () => {
  const s = rig({ speed: 800, jank: [30] });
  s.engine.start();
  s.run(0.49);
  const before = s.y;
  s.run(0.02);
  assert.ok(s.y - before < 60, `jumped ${s.y - before} px`);
});

test('at the end it waits for the next chapter instead of stopping', () => {
  const s = rig({ speed: 800, height: 3_000, more: true });
  s.engine.start();
  s.run(5);
  assert.equal(s.stopped, false);
  assert.ok(s.engine.running);
  s.height = 10_000; // the next chapter arrived
  s.run(2);
  assert.ok(s.y > 3_000);
  const done = rig({ speed: 800, height: 3_000, more: false });
  done.engine.start();
  done.run(5);
  assert.equal(done.stopped, true);
});

test('a finger or the wheel pauses it a moment, then it goes on', () => {
  const s = rig({ speed: 100 });
  s.engine.start();
  s.run(1);
  s.engine.nudge();
  const at = s.y;
  s.run(1);
  assert.equal(s.y, at);
  s.run(2);
  assert.ok(s.y > at + 50 && s.engine.running);
});

test('smart speeds stay around the reader pace and change smoothly', () => {
  assert.equal(clampSmart(5000, 100), 300);
  assert.equal(clampSmart(1, 100), 35);
  assert.equal(clampSmart(NaN, 100), 100);
  assert.ok(ease(100, 300, 1 / 60) < 110);
  assert.equal(ease(0, 300, 1 / 60), 300);
  assert.equal(novelSpeed(100, 600, 240), 24); // 4 words a second, 6 px a word
  assert.equal(novelSpeed(0, 600, 240), null);
});
