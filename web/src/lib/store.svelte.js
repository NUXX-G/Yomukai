// What the whole app shares: who is logged in, their settings (kept on the server) and the library.
import { api, clearMemo } from './api.js';
import { t, tk, tPairs } from './i18n.svelte.js';
import { applyTheme, rememberTheme } from './theme.svelte.js';

export const session = $state({ user: null, ready: false, offline: false, suspended: '', guestsEnabled: false, guestDays: 1, setupNeeded: false });

const DEFAULT_SETTINGS = {
  enabledSources: null,
  exclusions: { global: [], perSource: {} },
  reader: { mode: 'vertical', fit: 'width', infinite: true },
  novel: { size: 18, line: 1.7, theme: 'dark', font: 'serif' },
  player: { autoNext: true, speed: 1, skipFiller: false },
  /** Language learning: off unless the user turns it on; with it off, nothing changes anywhere. */
  learning: { enabled: false, target: 'es' },
  searchHistory: []
};

export const settings = $state(structuredClone(DEFAULT_SETTINGS));
export const library = $state({ items: [], loaded: false });
export const sources = $state({ list: [], loaded: false });
export const toast = $state({ text: '', at: 0 });
/** What the server has switched on and what the home worker can do right now (see /api/features). */
export const features = $state({ flags: {}, worker: { online: false, gpu: false }, available: {}, loaded: false });

/** Asks the server again; never fails (an old server without /api/features just means everything off). */
export async function loadFeatures() {
  try {
    const f = await api.features();
    Object.assign(features, f, { loaded: true });
  } catch {
    features.loaded = true;
  }
}

/** True when [name] is on (unknown flags count as off). */
export const featureOn = (name) => features.flags?.[name] === true;
/** What the home page showed: coming back to it shows the same at once, in place, instead of loading it all again. */
export const home = $state({ sections: {}, history: null, forYou: null });

export function notify(text) {
  toast.text = text;
  toast.at = Date.now();
  const at = toast.at;
  setTimeout(() => { if (toast.at === at) toast.text = ''; }, 3500);
}

export async function loadSession(attempt = 0) {
  try {
    const me = await api.me();
    session.user = me.user;
    session.guestsEnabled = me.guestsEnabled;
    // The account's theme (the same one the app uses); the browser remembers it for the next visit.
    if (me.user && !me.user.guest) {
      api.currentTheme().then((r) => { applyTheme(r?.theme || null); rememberTheme(r?.theme || null); }, () => {});
    }
    session.guestDays = me.guestDays;
  } catch (e) {
    // The server is restarting or the connection dropped: the session is still good, so wait and retry
    // instead of showing the login screen.
    if (e.status === 403) {
      session.suspended = e.message;
      session.ready = true;
      return;
    }
    if (e.status !== 401 && attempt < 30) {
      session.offline = true;
      await new Promise((r) => setTimeout(r, Math.min(5000, 1000 + attempt * 500)));
      return loadSession(attempt + 1);
    }
    session.user = null;
    if (e.status === 401) {
      // The answer still says whether guests may come in.
      try {
        const res = await fetch('/auth/me', { credentials: 'same-origin' });
        const data = await res.json();
        session.guestsEnabled = data.guestsEnabled ?? false;
        session.guestDays = data.guestDays ?? 7;
        session.setupNeeded = data.setupNeeded === true;
      } catch {}
    }
  }
  session.offline = false;
  session.ready = true;
  if (session.user) await loadUserData();
}

export async function loadUserData() {
  loadFeatures();
  const [s, lib, src] = await Promise.all([api.settings().catch(() => ({})), api.library().catch(() => []), api.sources().catch(() => [])]);
  Object.assign(settings, structuredClone(DEFAULT_SETTINGS), s, {
    reader: { ...DEFAULT_SETTINGS.reader, ...(s.reader || {}) },
    novel: { ...DEFAULT_SETTINGS.novel, ...(s.novel || {}) },
    player: { ...DEFAULT_SETTINGS.player, ...(s.player || {}) },
    learning: { ...DEFAULT_SETTINGS.learning, ...(s.learning || {}) },
    exclusions: { global: [], perSource: {}, ...(s.exclusions || {}) }
  });
  library.items = lib;
  library.loaded = true;
  sources.list = src;
  sources.loaded = true;
  loadCategories();
}

/**
 * The phone app may have changed the library or settings meanwhile: coming back to the tab after a
 * while picks that up.
 */
let lastRefresh = Date.now();
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible' || !session.user || Date.now() - lastRefresh < 60_000) return;
    lastRefresh = Date.now();
    loadUserData().catch(() => {});
  });
}

let saveTimer = 0;
/** Saves settings a moment after the last change, so dragging a slider sends one request. */
export function saveSettings({ reloadSources = false } = {}) {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    try {
      await api.saveSettings($state.snapshot(settings));
      if (reloadSources) sources.list = await api.sources();
    } catch (e) { notify(t('No se pudieron guardar los ajustes: ') + e.message); }
  }, 400);
}

export function inLibrary(sourceId, mediaId) {
  return library.items.find((r) => r.sourceId === sourceId && r.mediaId === mediaId);
}

export async function toggleLibrary(media) {
  const existing = inLibrary(media.sourceId, media.id);
  if (existing) {
    library.items = library.items.filter((r) => r !== existing);
    await api.removeLibrary(media.sourceId, media.id).catch((e) => notify(e.message));
    return false;
  }
  // Same as the app: a title just added is "plan to read" until reading starts.
  const row = {
    sourceId: media.sourceId, mediaId: media.id, title: media.title, cover: media.cover, type: media.type,
    status: media.status || '', author: media.author || '', genres: (media.genres || []).join('|'),
    addedAt: Date.now(), readingStatus: 'PLAN_TO_READ', categories: []
  };
  library.items = [row, ...library.items];
  await api.saveLibrary(row).catch((e) => notify(e.message));
  return true;
}

/** The app's reading statuses (same names, so they sync), in the order the library shows them. */
export const READING_STATUSES = tPairs([
  ['READING', tk('Leyendo')], ['PLAN_TO_READ', tk('Pendiente')], ['COMPLETED', tk('Completado')], ['ON_HOLD', tk('En pausa')], ['DROPPED', tk('Abandonado')], ['NONE', tk('Sin estado')]
]);
/** Older web rows used "PLAN". */
export const statusOf = (row) => (row?.readingStatus === 'PLAN' ? 'PLAN_TO_READ' : row?.readingStatus || 'NONE');

/** The user's own library categories. */
export const categories = $state({ list: [], loaded: false });
export async function loadCategories() {
  try { categories.list = await api.categories(); categories.loaded = true; } catch {}
}
export async function editCategory(change) {
  try {
    categories.list = await api.editCategory(change);
    // Titles in a renamed or deleted category changed on the server too.
    library.items = await api.library();
  } catch (e) { notify(e.message); }
}
export async function setCategories(sourceId, mediaId, names) {
  const row = inLibrary(sourceId, mediaId);
  if (!row) return;
  const updated = { ...row, categories: names };
  library.items = library.items.map((r) => (r === row ? updated : r));
  await api.saveLibrary(updated).catch((e) => notify(e.message));
}

export async function setReadingStatus(sourceId, mediaId, status) {
  const row = inLibrary(sourceId, mediaId);
  if (!row) return;
  const updated = { ...row, readingStatus: status };
  library.items = library.items.map((r) => (r === row ? updated : r));
  await api.saveLibrary(updated).catch((e) => notify(e.message));
}

/** New chapters per saved title, fetched once per visit and shared by the library and the title pages. */
export const updates = $state({ byKey: {}, loading: false, at: 0 });
export async function loadUpdates(force = false) {
  if (updates.loading || (!force && Date.now() - updates.at < 10 * 60_000)) return;
  updates.loading = true;
  try {
    const rows = await api.updates();
    updates.byKey = Object.fromEntries(rows.map((r) => [r.sourceId + '|' + r.mediaId, r]));
    updates.at = Date.now();
  } catch (e) {
    notify(t('No se pudieron buscar novedades: ') + e.message);
  } finally {
    updates.loading = false;
  }
}

export function sourceById(id) {
  return sources.list.find((s) => s.id === id);
}

export function rememberSearch(query) {
  const q = query.trim();
  if (!q) return;
  settings.searchHistory = [q, ...settings.searchHistory.filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, 20);
  saveSettings();
}

export function resetUserState() {
  clearMemo();
  home.sections = {};
  home.history = null;
  home.forYou = null;
  session.user = null;
  library.items = [];
  library.loaded = false;
  Object.assign(settings, structuredClone(DEFAULT_SETTINGS));
}

/** Titles seen in lists, so a title page can show its header at once while the rest loads. */
const seenItems = new Map();
export function rememberItems(items) {
  for (const i of items || []) seenItems.set(i.sourceId + '|' + i.id, i);
  if (seenItems.size > 3000) seenItems.delete(seenItems.keys().next().value);
}
export function seenItem(sourceId, id) {
  return seenItems.get(sourceId + '|' + id) || null;
}
