// Reading progress, sent to the server a moment after it changes. Same rules as the app:
// finishing a chapter marks every earlier one as read too.
import { api } from './api.js';

let timer = 0;
let pending = null;

function historyRow(media, chapter) {
  return {
    sourceId: media.sourceId, mediaId: media.id, chapterId: chapter.id, chapterTitle: chapter.label + (chapter.name ? ' · ' + chapter.name : ''),
    title: media.title, cover: media.cover, type: media.type, readAt: Date.now()
  };
}

function row(media, chapter, { read = false, page = 0, total = 0, fraction = 0 } = {}) {
  return { sourceId: media.sourceId, chapterId: chapter.id, mediaId: media.id, read, page, total, fraction, updatedAt: Date.now() };
}

/** Where the user is in [chapter]; saved within a second, the last call wins. */
export function track(media, chapter, state) {
  pending = { rows: [row(media, chapter, state)], history: historyRow(media, chapter) };
  clearTimeout(timer);
  timer = setTimeout(flush, 1000);
}

export function flush() {
  clearTimeout(timer);
  if (!pending) return;
  const body = pending;
  pending = null;
  // keepalive lets the save finish even when the page is being closed.
  fetch('/api/progress', { method: 'POST', credentials: 'same-origin', keepalive: true, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).catch(() => {});
}

addEventListener('pagehide', flush);
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') flush(); });

/** Marks [chapter] and every chapter before it as read (or not), at once. */
export async function markThrough(media, chapters, chapter, read) {
  const rows = chapters.filter((c) => c.index <= chapter.index).map((c) => row(media, c, { read }));
  await api.saveProgress(rows, read ? historyRow(media, chapter) : undefined);
  return rows;
}

export async function markOne(media, chapter, read) {
  await api.saveProgress([row(media, chapter, { read })]);
}

/** chapterId -> progress row. */
export async function progressMap(sourceId, mediaId) {
  const rows = await api.progress(sourceId, mediaId).catch(() => []);
  return Object.fromEntries(rows.map((r) => [r.chapterId, r]));
}

export { continueTarget } from './continue.js';
