// The dominant colorful hue of a cover, and the theme colors derived from it.
// Same algorithm as CoverPalette.kt in the Android app. Covers are served by our own server,
// so the canvas can read their pixels.

const cache = new Map();

function toHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h * 60, s, l];
}

function hsl(h, s, l) {
  return `hsl(${h.toFixed(0)} ${(s * 100).toFixed(0)}% ${(l * 100).toFixed(1)}%)`;
}

/** Hue and saturation of the cover's accent, or null for gray covers. */
export function dominant(pixels) {
  const buckets = 12, weight = new Float64Array(buckets), sr = new Float64Array(buckets), sg = new Float64Array(buckets), sb = new Float64Array(buckets);
  let colorful = 0, total = 0;
  for (let i = 0; i < pixels.length; i += 4) {
    if (pixels[i + 3] < 128) continue;
    total++;
    const [h, s, l] = toHsl(pixels[i], pixels[i + 1], pixels[i + 2]);
    if (s < 0.22 || l < 0.12 || l > 0.9) continue;
    colorful++;
    const bucket = Math.min(buckets - 1, Math.floor((h / 360) * buckets));
    const w = s * (1 - Math.abs(l - 0.5) * 1.2);
    weight[bucket] += w; sr[bucket] += pixels[i] * w; sg[bucket] += pixels[i + 1] * w; sb[bucket] += pixels[i + 2] * w;
  }
  if (!total || colorful < total * 0.04) return null;
  let best = 0;
  for (let i = 1; i < buckets; i++) if (weight[i] > weight[best]) best = i;
  if (weight[best] <= 0) return null;
  const [h, s] = toHsl(sr[best] / weight[best], sg[best] / weight[best], sb[best] / weight[best]);
  return { h, s };
}

/** CSS variables for a title page tinted by its cover; empty when the cover has no color. */
export function themeFor(seed) {
  if (!seed) return {};
  const { h } = seed;
  const s = (cap) => Math.min(seed.s, cap);
  return {
    '--primary': hsl(h, s(0.75), 0.74),
    '--on-primary': hsl(h, s(0.75), 0.14),
    '--primary-container': hsl(h, s(0.5), 0.28),
    '--on-primary-container': hsl(h, s(0.75), 0.9),
    '--secondary': hsl(h, s(0.35), 0.8),
    '--secondary-container': hsl(h, s(0.3), 0.22),
    '--on-secondary-container': hsl(h, s(0.3), 0.9),
    '--bg': hsl(h, s(0.28), 0.07),
    '--surface': hsl(h, s(0.22), 0.11),
    '--surface-high': hsl(h, s(0.2), 0.14),
    '--surface-variant': hsl(h, s(0.18), 0.17)
  };
}

/** Reads a tiny copy of [url] and returns its accent; remembered per cover. */
export function coverSeed(url) {
  if (!url) return Promise.resolve(null);
  if (cache.has(url)) return Promise.resolve(cache.get(url));
  return new Promise((resolve) => {
    const img = new Image();
    img.decoding = 'async';
    img.onload = () => {
      try {
        const canvas = document.createElement('canvas');
        canvas.width = canvas.height = 48;
        const ctx = canvas.getContext('2d', { willReadFrequently: true });
        ctx.drawImage(img, 0, 0, 48, 48);
        const seed = dominant(ctx.getImageData(0, 0, 48, 48).data);
        cache.set(url, seed);
        resolve(seed);
      } catch {
        resolve(null);
      }
    };
    img.onerror = () => resolve(null);
    img.src = url;
  });
}

export function styleOf(vars) {
  return Object.entries(vars).map(([k, v]) => `${k}:${v}`).join(';');
}
