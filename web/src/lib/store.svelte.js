// What the whole app shares: who is logged in, their settings (kept on the server) and the library.
import { api } from './api.js';

export const session = $state({ user: null, ready: false, guestsEnabled: true, guestDays: 7 });

const DEFAULT_SETTINGS = {
  enabledSources: null,
  exclusions: { global: [], perSource: {} },
  reader: { mode: 'vertical', fit: 'width', infinite: true },
  novel: { size: 18, line: 1.7, theme: 'dark', font: 'serif' },
  player: { autoNext: true, speed: 1 },
  searchHistory: []
};

export const settings = $state(structuredClone(DEFAULT_SETTINGS));
export const library = $state({ items: [], loaded: false });
export const sources = $state({ list: [], loaded: false });
export const toast = $state({ text: '', at: 0 });

export function notify(text) {
  toast.text = text;
  toast.at = Date.now();
  const at = toast.at;
  setTimeout(() => { if (toast.at === at) toast.text = ''; }, 3500);
}

export async function loadSession() {
  try {
    const me = await api.me();
    session.user = me.user;
    session.guestsEnabled = me.guestsEnabled;
    session.guestDays = me.guestDays;
  } catch (e) {
    session.user = null;
    if (e.status === 401) {
      // The answer still says whether guests may come in.
      try {
        const res = await fetch('/auth/me', { credentials: 'same-origin' });
        const data = await res.json();
        session.guestsEnabled = data.guestsEnabled ?? true;
        session.guestDays = data.guestDays ?? 7;
      } catch {}
    }
  }
  session.ready = true;
  if (session.user) await loadUserData();
}

export async function loadUserData() {
  const [s, lib, src] = await Promise.all([api.settings().catch(() => ({})), api.library().catch(() => []), api.sources().catch(() => [])]);
  Object.assign(settings, structuredClone(DEFAULT_SETTINGS), s, {
    reader: { ...DEFAULT_SETTINGS.reader, ...(s.reader || {}) },
    novel: { ...DEFAULT_SETTINGS.novel, ...(s.novel || {}) },
    player: { ...DEFAULT_SETTINGS.player, ...(s.player || {}) },
    exclusions: { global: [], perSource: {}, ...(s.exclusions || {}) }
  });
  library.items = lib;
  library.loaded = true;
  sources.list = src;
  sources.loaded = true;
}

let saveTimer = 0;
/** Saves settings a moment after the last change, so dragging a slider sends one request. */
export function saveSettings({ reloadSources = false } = {}) {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    try {
      await api.saveSettings($state.snapshot(settings));
      if (reloadSources) sources.list = await api.sources();
    } catch (e) { notify('No se pudieron guardar los ajustes: ' + e.message); }
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
  const row = {
    sourceId: media.sourceId, mediaId: media.id, title: media.title, cover: media.cover, type: media.type,
    status: media.status || '', author: media.author || '', genres: (media.genres || []).join('|'),
    addedAt: Date.now(), readingStatus: 'READING'
  };
  library.items = [row, ...library.items];
  await api.saveLibrary(row).catch((e) => notify(e.message));
  return true;
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
  session.user = null;
  library.items = [];
  library.loaded = false;
  Object.assign(settings, structuredClone(DEFAULT_SETTINGS));
}
