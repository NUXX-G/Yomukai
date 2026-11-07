// Watching from the home NAS (Jellyfin) together: every viewer outside the tailnet pulls their own copy through the
// VPS, so the home upload is shared out. The quality advised is the best that fits everyone with some margin.

/** Qualities the server offers for a NAS episode, as "q" values: kbit/s, or "orig" (direct play). */
export const NAS_QUALITIES = ['orig', '8000', '4000', '1500'];

/** Direct play is assumed to need about this much (a typical 1080p anime file). */
const ORIGINAL_KBPS = 10000;

/**
 * The "q" to use with [viewers] people and [uploadMbps] of upload at home: 80 % of it split among them, then the best
 * quality that fits; never below the lowest.
 */
export function recommendedQuality(viewers, uploadMbps) {
  const n = Math.max(1, Math.floor(viewers || 1));
  const budget = (Math.max(1, uploadMbps || 20) * 1000 * 0.8) / n;
  if (budget >= ORIGINAL_KBPS) return 'orig';
  return NAS_QUALITIES.slice(1).find((q) => Number(q) <= budget) || '1500';
}

/** The "q" of one server link from the NAS (null for any other source). */
export const qualityOf = (url) => (typeof url === 'string' && url.startsWith('/api/jellyfin/stream/') ? new URL(url, 'http://x').searchParams.get('q') : null);
