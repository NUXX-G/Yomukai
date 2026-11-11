// Auto-scroll for every reader (F6.2), one engine for manga, manhwa, comics and novels:
// - two modes kept apart: "normal" (a fixed speed) and "smart" (the speed of what is on screen, around the reader's
//   own pace); the smart mode never reads the normal mode's speed (it did, so its ×1 meant anything);
// - steady at every speed: fractions of a pixel are carried from frame to frame, a long frame (a busy phone, a tab in
//   the background) never turns into a jump, and speed changes are eased;
// - it keeps going: at the end of a chapter it waits while the next one loads instead of stopping; a wheel or a
//   finger on the page pauses it for a moment and it goes on by itself.

/** Pixels per second for the "normal" mode at ×1, per reader. */
export const BASE = { manga: 80, novel: 30 };
/** Reading pace at ×1 in the "smart" mode, words per minute. */
export const BASE_WPM = 220;

/** The smart target kept around the reader's pace (never 15× faster in a gap, never stuck on a busy page). */
export function clampSmart(target, pace, low = 0.35, high = 3) {
  if (!Number.isFinite(target) || target <= 0) return pace;
  return Math.min(pace * high, Math.max(pace * low, target));
}

/** One step of easing from [speed] towards [target] after [dt] seconds (about 0.4 s to get most of the way). */
export function ease(speed, target, dt, rate = 2.5) {
  if (!speed) return target;
  return speed + (target - speed) * Math.min(1, dt * rate);
}

/**
 * Novels in smart mode: pixels per second so that [wpm] words a minute go by, from the paragraph being read
 * ([words] words over [height] pixels). Null when it cannot tell.
 */
export function novelSpeed(words, height, wpm) {
  if (!words || !height || !wpm) return null;
  return (wpm / 60) * (height / words);
}

/**
 * The engine. [target]() gives the wanted speed (px/s) each frame; [atEnd]() says the page cannot go further now;
 * [waitMore]() says more is coming (the next chapter loading) so it should wait instead of stopping; [onStop] is
 * called when it stops by itself. [scrollBy] and [now]/[frame] can be replaced (tests).
 */
export function createAutoScroll({
  target, atEnd, waitMore = () => false, onStop = () => {}, scrollBy = (px) => window.scrollBy(0, px),
  frame = (cb) => requestAnimationFrame(cb), cancel = (id) => cancelAnimationFrame(id), now = () => performance.now(),
  pauseMs = 1500, maxWaitMs = 30_000
}) {
  let running = false;
  let id = 0;
  let last = 0;
  let carry = 0;
  let speed = 0;
  let pausedUntil = 0;
  let waitingSince = 0;

  function step(t) {
    if (!running) return;
    const dt = Math.min(0.05, Math.max(0, (t - last) / 1000));
    last = t;
    if (t < pausedUntil) { speed = 0; carry = 0; id = frame(step); return; }
    if (atEnd()) {
      if (waitMore()) {
        waitingSince ||= t;
        if (t - waitingSince < maxWaitMs) { id = frame(step); return; }
      }
      stop();
      onStop();
      return;
    }
    waitingSince = 0;
    speed = ease(speed, Math.max(0, target()), dt);
    carry += dt * speed;
    const px = Math.floor(carry);
    if (px > 0) { scrollBy(px); carry -= px; }
    id = frame(step);
  }

  function start() {
    if (running) return;
    running = true;
    speed = 0;
    carry = 0;
    waitingSince = 0;
    last = now();
    id = frame(step);
  }
  function stop() { running = false; cancel(id); }

  return {
    start,
    stop,
    get running() { return running; },
    /** A wheel or a finger moved the page: wait a moment, then go on from there. */
    nudge() { pausedUntil = now() + pauseMs; },
    get speed() { return speed; }
  };
}
