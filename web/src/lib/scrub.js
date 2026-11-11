// Position shown by a seek bar. The filled part and the thumb must always agree, also while the video is still
// seeking (a slow proxy can take seconds) and while the user drags: the video's own clock lags behind then.

/** Filled part of a range, 0..100. */
export function fillPercent(value, max) {
  if (!(max > 0) || !Number.isFinite(value)) return 0;
  return Math.min(100, Math.max(0, (value / max) * 100));
}

/**
 * Keeps the shown position: what the user picked wins until the video gets there (or the user lets go and the
 * seek ends); after that the video's clock drives it again.
 */
export function createScrubber() {
  let shown = 0;
  /** Where the user asked to go, until the video has caught up. */
  let target = null;
  let holding = false;
  return {
    get value() { return shown; },
    get busy() { return target != null; },
    /** The user moved the thumb (drag, click or keyboard). */
    pick(value) { shown = value; target = value; holding = true; },
    /** The user let go of the thumb. */
    release() { holding = false; },
    /** A `timeupdate`/`seeking`/`seeked` from the video. */
    clock(time, seeking) {
      if (target != null && (holding || seeking)) return shown;
      target = null;
      shown = time;
      return shown;
    },
    /** A seek the page made itself (buttons, keys, the room): shown at once. */
    jump(time) { shown = time; target = time; holding = false; }
  };
}
