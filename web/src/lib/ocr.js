// The same rules as shared/…/data/ocr (Ocr.kt and ScrollPlanner.kt), for the browser: reading order of the bubbles,
// time per strip for the smart autoscroll, and the picture-only analysis when there is no OCR.

/** Manhwa top to bottom, manga right to left, comics left to right. */
export function directionFor(type) {
  const t = String(type || '').toUpperCase();
  return t === 'MANHWA' || t === 'MANHUA' ? 'down' : t === 'MANGA' ? 'rtl' : 'ltr';
}

function overlap(a, b) {
  const top = Math.max(a.y, b.y);
  const bottom = Math.min(a.y + a.h, b.y + b.h);
  return Math.max(0, (bottom - top) / Math.min(a.h, b.h));
}

/** Boxes in reading order (rows of boxes that overlap vertically; inside a row by the reading direction). */
export function readingOrder(boxes, direction) {
  if (direction === 'down') return [...boxes].sort((a, b) => a.y - b.y || a.x - b.x);
  const rows = [];
  for (const b of [...boxes].sort((p, q) => p.y - q.y)) {
    const row = rows.at(-1);
    if (row && row.some((o) => overlap(o, b) > 0.5)) row.push(b); else rows.push([b]);
  }
  const cx = (b) => b.x + b.w / 2;
  return rows.flatMap((row) => row.sort((a, b) => (direction === 'rtl' ? cx(b) - cx(a) : cx(a) - cx(b))));
}

export const DEFAULT_WPM = 220;

/** Milliseconds for a strip: text by its words and the reader's speed, panels by their detail, gutters almost none. */
export function stripMs({ words = 0, detail = 0.5, empty = false, textLike = false }, wpm = DEFAULT_WPM) {
  if (empty) return 250;
  const pace = Math.min(700, Math.max(80, wpm));
  if (words > 0) return 400 + (words / pace) * 60_000;
  // No OCR, but it looks like a bubble: read time for a short line, never the quick panel pace (as ScrollPlanner).
  if (textLike) return 400 + (5 / pace) * 60_000;
  return 900 + Math.min(1, Math.max(0, detail)) * 500;
}

/** Plan B without OCR: grey values of a strip (0..255, row by row of [width]) → { empty, detail, textLike }. */
export function analyze(grey, width) {
  if (!grey.length || width <= 1) return { empty: true, detail: 0, textLike: false };
  let mean = 0;
  for (const v of grey) mean += v;
  mean /= grey.length;
  let variance = 0;
  for (const v of grey) variance += (v - mean) ** 2;
  if (Math.sqrt(variance / grey.length) < 6) return { empty: true, detail: 0, textLike: false };
  // Mostly light with something on it: paper or bubbles with (shrunk) letters.
  let light = 0;
  for (const v of grey) if (v >= 200) light++;
  const textLike = light / grey.length >= 0.55;
  let edges = 0;
  let pairs = 0;
  for (let i = 0; i + 1 < grey.length; i++) {
    if ((i + 1) % width === 0) continue;
    pairs++;
    if (Math.abs(grey[i] - grey[i + 1]) > 24) edges++;
  }
  return { empty: false, detail: pairs ? Math.min(1, edges / pairs / 0.35) : 0, textLike };
}

/** Words of the OCR boxes inside the band [top, bottom] of a page (fractions), speech only. */
export function wordsIn(boxes, top, bottom) {
  let n = 0;
  for (const b of boxes || []) {
    if (b.kind === 'SFX' || b.y + b.h < top || b.y > bottom) continue;
    n += (b.text.match(/\p{L}+/gu) || []).length;
  }
  return n;
}

/**
 * The text box under a point of the page (x, y from 0 to 1), for a long press on a bubble (F6.3). A little margin
 * around each box, as fingers are wide; the smallest box wins when they overlap. Null when there is none.
 */
export function boxAt(boxes, x, y, pad = 0.02) {
  let best = null;
  for (const b of boxes || []) {
    if (!b?.text?.trim()) continue;
    if (x < b.x - pad || x > b.x + b.w + pad || y < b.y - pad || y > b.y + b.h + pad) continue;
    if (!best || b.w * b.h < best.w * best.h) best = b;
  }
  return best;
}
