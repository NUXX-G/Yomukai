// Labels and small helpers shared by the pages, matching the Android app's wording.

export const TYPE_NAMES = { MANGA: 'Manga', MANHWA: 'Manhwa', MANHUA: 'Manhua', COMIC: 'Cómic', NOVEL: 'Novela', ANIME: 'Anime' };
export const TYPE_COLORS = { ANIME: '#E53935', MANGA: '#1E88E5', MANHWA: '#43A047', MANHUA: '#FB8C00', COMIC: '#00897B', NOVEL: '#8E24AA' };

export const TYPE_FILTERS = [
  { key: 'ALL', label: 'Todo', types: null },
  { key: 'MANGA', label: 'Manga', types: ['MANGA'] },
  { key: 'COMIC', label: 'Cómics', types: ['COMIC'] },
  { key: 'MANHWA', label: 'Manhwa', types: ['MANHWA', 'MANHUA'] },
  { key: 'NOVEL', label: 'Novelas', types: ['NOVEL'] },
  { key: 'ANIME', label: 'Anime', types: ['ANIME'] }
];

export const LANGUAGES = [{ key: '', label: 'Todos' }, { key: 'ES', label: 'Español' }, { key: 'EN', label: 'Inglés' }, { key: 'ZH', label: 'Chino' }];

export const RANDOM_KINDS = [
  { key: 'ANY', label: 'Lo que sea' }, { key: 'ANIME', label: 'Anime' }, { key: 'MANGA', label: 'Manga' },
  { key: 'MANHWA', label: 'Manhwa' }, { key: 'NOVEL', label: 'Novela' }
];

export const RANDOM_GENRES = [
  ['Action', 'Acción'], ['Adventure', 'Aventura'], ['Comedy', 'Comedia'], ['Drama', 'Drama'], ['Ecchi', 'Ecchi'],
  ['Fantasy', 'Fantasía'], ['Horror', 'Terror'], ['Mahou Shoujo', 'Mahou shoujo'], ['Mecha', 'Mecha'], ['Music', 'Música'],
  ['Mystery', 'Misterio'], ['Psychological', 'Psicológico'], ['Romance', 'Romance'], ['Sci-Fi', 'Ciencia ficción'],
  ['Slice of Life', 'Recuentos de la vida'], ['Sports', 'Deportes'], ['Supernatural', 'Sobrenatural'], ['Thriller', 'Suspenso']
];

const STATUS = [
  ['ONGOING', 'En emisión', ['ongoing', 'emisi', 'active', 'activ', 'publish', 'curso', 'reciente']],
  ['COMPLETED', 'Finalizado', ['complet', 'finaliz', 'finish', 'terminad', 'ended']],
  ['HIATUS', 'En pausa', ['hiatus', 'pausa', 'paused']],
  ['CANCELLED', 'Cancelado', ['cancel', 'dropped', 'abandon']]
];

/** Same rules as the app: free-form status text to ONGOING, COMPLETED... or null. */
export function parseStatus(raw) {
  const s = (raw || '').toLowerCase();
  if (!s) return null;
  for (const [key, , cues] of [STATUS[1], STATUS[2], STATUS[3], STATUS[0]]) if (cues.some((c) => s.includes(c))) return key;
  return null;
}
export const statusName = (key) => STATUS.find((s) => s[0] === key)?.[1];

export const isAnime = (type) => type === 'ANIME';
export const chapterNoun = (count, anime) => anime ? (count === 1 ? 'episodio' : 'episodios') : (count === 1 ? 'capítulo' : 'capítulos');

export function score(value) {
  return value == null ? '' : value.toLocaleString('es-ES', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}

export function monthYear(ms) {
  return new Date(ms).toLocaleDateString('es-ES', { month: 'long', year: 'numeric' });
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
export function groupBadges(g) {
  const all = [g.primary, ...(g.others || [])];
  const badges = [];
  if (all.some((x) => x?.sourceId === 'jellyfin')) badges.push({ text: 'En tu NAS', tone: 'accent' });
  if (all.length > 1) badges.push({ text: `${all.length} fuentes`, tone: 'dark' });
  return badges;
}
