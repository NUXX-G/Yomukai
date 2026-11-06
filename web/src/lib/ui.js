// Small helpers shared by the redesigned pages.
import { navigate, media } from './router.svelte.js';
import { openRelated } from './open.svelte.js';

/** A proxied image at about [width] CSS pixels (the proxy keeps a shrunk copy); other addresses as they are. */
export function sized(url, width) {
  if (!url || !url.startsWith('/api/img')) return url || '';
  const px = Math.round(width * Math.min(window.devicePixelRatio || 1, 2));
  return url + (url.includes('?') ? '&' : '?') + 'w=' + px;
}

/** "8,4" from AniList's 0..100. */
export const score10 = (s) => (s ? (s / 10).toFixed(1).replace('.', ',') : '');

const FORMAT = {
  TV: 'Serie', TV_SHORT: 'Corto', MOVIE: 'Película', SPECIAL: 'Especial', OVA: 'OVA', ONA: 'ONA', MUSIC: 'Música',
  MANGA: 'Manga', NOVEL: 'Novela', ONE_SHOT: 'One-shot'
};
export const formatName = (f) => FORMAT[f] || '';

const GENRE_ES = {
  Action: 'Acción', Adventure: 'Aventura', Comedy: 'Comedia', Drama: 'Drama', Ecchi: 'Ecchi', Fantasy: 'Fantasía',
  Horror: 'Terror', 'Mahou Shoujo': 'Mahou shoujo', Mecha: 'Mecha', Music: 'Música', Mystery: 'Misterio',
  Psychological: 'Psicológico', Romance: 'Romance', 'Sci-Fi': 'Ciencia ficción', 'Slice of Life': 'Recuentos de la vida',
  Sports: 'Deportes', Supernatural: 'Sobrenatural', Thriller: 'Suspense'
};
export const genreName = (g) => GENRE_ES[g] || g;
export const GENRES = Object.keys(GENRE_ES);

const SEASON = { WINTER: 'Invierno', SPRING: 'Primavera', SUMMER: 'Verano', FALL: 'Otoño' };
export const seasonName = (s, y) => [SEASON[s], y].filter(Boolean).join(' ');

/**
 * Opens an AniList title: straight to its page when a source already has it, otherwise the sheet that looks for it
 * in your sources.
 */
export function openDiscover(item) {
  if (item.sourceId && item.mediaId) return navigate(media(item.sourceId, item.mediaId));
  openRelated({
    title: item.title, names: item.names || [], cover: item.cover, type: item.type, genres: item.genres || [],
    relation: null, format: item.format || '', tags: [], item: null
  });
}

/** True when the visitor asked the system for less motion. */
export const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
