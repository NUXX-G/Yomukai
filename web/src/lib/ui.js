// Small helpers shared by the redesigned pages.
import { navigate, media } from './router.svelte.js';
import { openRelated } from './open.svelte.js';
import { t, tk, i18n } from './i18n.svelte.js';

/** A proxied image at about [width] CSS pixels (the proxy keeps a shrunk copy); other addresses as they are. */
export function sized(url, width) {
  if (!url || !url.startsWith('/api/img')) return url || '';
  // Up to 3x for 4K screens and TVs; the server keeps shrunk copies in a few sizes and never enlarges.
  const px = Math.round(width * Math.min(window.devicePixelRatio || 1, 3));
  const base = url.replace(/([?&])w=\d+(&|$)/, (m, a, b) => (b ? a : '')).replace(/[?&]$/, '');
  return base + (base.includes('?') ? '&' : '?') + 'w=' + px;
}

/** "8,4" from AniList's 0..100. */
export const score10 = (s) => (s ? (s / 10).toFixed(1).replace('.', ',') : '');

const FORMAT = {
  TV: tk('Serie'), TV_SHORT: tk('Corto'), MOVIE: tk('Película'), SPECIAL: tk('Especial'), OVA: 'OVA', ONA: 'ONA', MUSIC: tk('Música'),
  MANGA: tk('Manga'), NOVEL: tk('Novela'), ONE_SHOT: tk('One-shot')
};
export const formatName = (f) => (FORMAT[f] ? t(FORMAT[f]) : '');

const GENRE_ES = {
  Action: tk('Acción'), Adventure: tk('Aventura'), Comedy: tk('Comedia'), Drama: tk('Drama'), Ecchi: 'Ecchi', Fantasy: tk('Fantasía'),
  Horror: tk('Terror'), 'Mahou Shoujo': tk('Mahou shoujo'), Mecha: 'Mecha', Music: tk('Música'), Mystery: tk('Misterio'),
  Psychological: tk('Psicológico'), Romance: tk('Romance'), 'Sci-Fi': tk('Ciencia ficción'), 'Slice of Life': tk('Recuentos de la vida'),
  Sports: tk('Deportes'), Supernatural: tk('Sobrenatural'), Thriller: tk('Suspense')
};
export const genreName = (g) => (GENRE_ES[g] ? t(GENRE_ES[g]) : g);
export const GENRES = Object.keys(GENRE_ES);

const SEASON = { WINTER: tk('Invierno'), SPRING: tk('Primavera'), SUMMER: tk('Verano'), FALL: tk('Otoño') };
export const seasonName = (s, y) => [SEASON[s] && t(SEASON[s]), y].filter(Boolean).join(' ');

/**
 * Opens an AniList title: as a WORK in the user's language (O1, O2: its best source there, subtitled first), so
 * a Spanish user never lands on an English source because it was the first one linked.
 */
export function openDiscover(item) {
  if (item.workKey) return navigate('/obras/' + encodeURIComponent(item.workKey) + '?lang=' + i18n.lang);
  if (item.sourceId && item.mediaId) return navigate(media(item.sourceId, item.mediaId));
  openRelated({
    title: item.title, names: item.names || [], cover: item.cover, type: item.type, genres: item.genres || [],
    relation: null, format: item.format || '', tags: [], item: null
  });
}

/** True when the visitor asked the system for less motion. */
export const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
