// Labels and small helpers shared by the pages, matching the Android app's wording.
import { t, tk, tMap, tLabels, tPairs, locale } from './i18n.svelte.js';

export const TYPE_NAMES = tMap({ MANGA: tk('Manga'), MANHWA: tk('Manhwa'), MANHUA: tk('Manhua'), COMIC: tk('Cómic'), NOVEL: tk('Novela'), ANIME: tk('Anime') });
export const TYPE_COLORS = { ANIME: '#E53935', MANGA: '#1E88E5', MANHWA: '#43A047', MANHUA: '#FB8C00', COMIC: '#00897B', NOVEL: '#8E24AA' };

export const TYPE_FILTERS = tLabels([
  { key: 'ALL', label: tk('Todo'), types: null },
  { key: 'MANGA', label: tk('Manga'), types: ['MANGA'] },
  { key: 'COMIC', label: tk('Cómics'), types: ['COMIC'] },
  { key: 'MANHWA', label: tk('Manhwa'), types: ['MANHWA', 'MANHUA'] },
  { key: 'NOVEL', label: tk('Novelas'), types: ['NOVEL'] },
  { key: 'ANIME', label: tk('Anime'), types: ['ANIME'] }
]);

export const LANGUAGES = tLabels([{ key: '', label: tk('Todos') }, { key: 'ES', label: tk('Español') }, { key: 'EN', label: tk('Inglés') }, { key: 'ZH', label: tk('Chino') }]);

export const RANDOM_KINDS = tLabels([
  { key: 'ANY', label: tk('Lo que sea') }, { key: 'ANIME', label: tk('Anime') }, { key: 'MANGA', label: tk('Manga') },
  { key: 'MANHWA', label: tk('Manhwa') }, { key: 'NOVEL', label: tk('Novela') }
]);

export const RANDOM_GENRES = tPairs([
  ['Action', tk('Acción')], ['Adventure', tk('Aventura')], ['Comedy', tk('Comedia')], ['Drama', tk('Drama')], ['Ecchi', tk('Ecchi')],
  ['Fantasy', tk('Fantasía')], ['Horror', tk('Terror')], ['Mahou Shoujo', tk('Mahou shoujo')], ['Mecha', tk('Mecha')], ['Music', tk('Música')],
  ['Mystery', tk('Misterio')], ['Psychological', tk('Psicológico')], ['Romance', tk('Romance')], ['Sci-Fi', tk('Ciencia ficción')],
  ['Slice of Life', tk('Recuentos de la vida')], ['Sports', tk('Deportes')], ['Supernatural', tk('Sobrenatural')], ['Thriller', tk('Suspenso')]
]);

const STATUS = [
  ['ONGOING', tk('En emisión'), ['ongoing', 'emisi', 'active', 'activ', 'publish', 'curso', 'reciente']],
  ['COMPLETED', tk('Finalizado'), ['complet', 'finaliz', 'finish', 'terminad', 'ended']],
  ['HIATUS', tk('En pausa'), ['hiatus', 'pausa', 'paused']],
  ['CANCELLED', tk('Cancelado'), ['cancel', 'dropped', 'abandon']]
];

/** Same rules as the app: free-form status text to ONGOING, COMPLETED... or null. */
export function parseStatus(raw) {
  const s = (raw || '').toLowerCase();
  if (!s) return null;
  for (const [key, , cues] of [STATUS[1], STATUS[2], STATUS[3], STATUS[0]]) if (cues.some((c) => s.includes(c))) return key;
  return null;
}
export const statusName = (key) => { const s = STATUS.find((x) => x[0] === key)?.[1]; return s ? t(s) : s; };

export const isAnime = (type) => type === 'ANIME';
export const chapterNoun = (count, anime) => t(anime ? (count === 1 ? 'episodio' : 'episodios') : (count === 1 ? 'capítulo' : 'capítulos'));

export function score(value) {
  return value == null ? '' : value.toLocaleString(locale(), { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}

export function monthYear(ms) {
  return new Date(ms).toLocaleDateString(locale(), { month: 'long', year: 'numeric' });
}

export function time(seconds) {
  if (!isFinite(seconds) || seconds < 0) seconds = 0;
  const h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60), s = Math.floor(seconds % 60);
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
}

export function clock() {
  return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** Badges for a card that groups one work from several sources: "En tu NAS" when the home Jellyfin has it. */
/** How an anime is heard (F3.1): its name in the interface and a short tag for cards. */
export const AUDIO = {
  ORIGINAL: { label: tk('Subtitulado'), short: 'SUB' },
  CASTELLANO: { label: tk('Castellano'), short: 'CAST' },
  LATINO: { label: tk('Latino'), short: 'LAT' },
  INGLES: { label: tk('Doblaje en inglés'), short: 'DUB EN' },
  DOBLADO: { label: tk('Doblado'), short: 'DOB' }
};

/** Card badges for the dubs a work or a page has (subtitled is the usual, so it is not marked). */
export function audioBadges(audios) {
  return (audios || []).filter((a) => a && a !== 'ORIGINAL' && AUDIO[a]).map((a) => ({ text: AUDIO[a].short, tone: 'dark', title: t(AUDIO[a].label) }));
}

export function groupBadges(g) {
  const all = [g.primary, ...(g.others || [])];
  const badges = [];
  if (all.some((x) => x?.sourceId === 'jellyfin')) badges.push({ text: t('En tu NAS'), tone: 'accent' });
  if (all.length > 1) badges.push({ text: t('{0} fuentes', [all.length]), tone: 'dark' });
  return badges;
}
