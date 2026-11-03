// Finds the plain white or black margins around a page, like the app's border crop.

const cache = new Map();

/**
 * Fractions to cut from each side of a loaded <img>, or null when there is nothing worth cutting.
 * [sidesOnly] keeps top and bottom (long strips must join seamlessly).
 */
export function detectCrop(img, sidesOnly = false) {
  const key = img.currentSrc || img.src;
  if (cache.has(key + sidesOnly)) return cache.get(key + sidesOnly);
  let result = null;
  try {
    const W = img.naturalWidth, H = img.naturalHeight;
    if (!W || !H) return null;
    // A small copy is enough to see margins and keeps this cheap on phones.
    const scale = Math.min(1, 240 / W);
    const w = Math.max(1, Math.round(W * scale)), h = Math.max(1, Math.round(H * scale));
    const canvas = document.createElement('canvas');
    canvas.width = w; canvas.height = h;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(img, 0, 0, w, h);
    const data = ctx.getImageData(0, 0, w, h).data;
    const at = (x, y) => (y * w + x) * 4;
    // The margin colour: white or black, taken from the corners.
    const corner = data[at(0, 0)] + data[at(0, 0) + 1] + data[at(0, 0) + 2];
    const target = corner > 600 ? 255 : corner < 90 ? 0 : -1;
    if (target < 0) { cache.set(key + sidesOnly, null); return null; }
    const plain = (i) => Math.abs(data[i] - target) < 28 && Math.abs(data[i + 1] - target) < 28 && Math.abs(data[i + 2] - target) < 28;
    const column = (x) => { for (let y = 0; y < h; y += 2) if (!plain(at(x, y))) return false; return true; };
    const row = (y) => { for (let x = 0; x < w; x += 2) if (!plain(at(x, y))) return false; return true; };
    let l = 0, r = w - 1, t = 0, b = h - 1;
    while (l < r && column(l)) l++;
    while (r > l && column(r)) r--;
    if (!sidesOnly) {
      while (t < b && row(t)) t++;
      while (b > t && row(b)) b--;
    }
    // A little margin back, so lines at the edge are not shaved.
    l = Math.max(0, l - 2); t = Math.max(0, t - 2); r = Math.min(w - 1, r + 2); b = Math.min(h - 1, b + 2);
    const crop = { l: l / w, t: t / h, r: (w - 1 - r) / w, b: (h - 1 - b) / h };
    const cut = crop.l + crop.r + crop.t + crop.b;
    // Nothing worth it, or a page that is almost all margin (a blank page): leave it alone.
    if (cut > 0.04 && crop.l + crop.r < 0.8 && crop.t + crop.b < 0.8) result = crop;
  } catch {
    result = null;
  }
  cache.set(key + sidesOnly, result);
  if (cache.size > 2000) cache.delete(cache.keys().next().value);
  return result;
}

/** Styles for a wrapper and its image that show only the cropped part. */
export function cropStyles(img, crop) {
  const W = img.naturalWidth, H = img.naturalHeight;
  const cw = 1 - crop.l - crop.r, ch = 1 - crop.t - crop.b;
  return {
    ratio: (W * cw) / (H * ch),
    width: `${100 / cw}%`,
    left: `${(-crop.l / cw) * 100}%`,
    top: `${(-crop.t / ch) * 100}%`
  };
}
