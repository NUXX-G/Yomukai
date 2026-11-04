// Talks to the Yomukai server. Every call sends the session cookie; a 401 means "log in again".

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(fn) { onUnauthorized = fn; }
let onSuspended = () => {};
/** The account was suspended for abusive use: the app shows a notice instead of its pages. */
export function setSuspendedHandler(fn) { onSuspended = fn; }

async function request(method, path, body, { raw = false, signal } = {}) {
  const init = { method, credentials: 'same-origin', signal, headers: {} };
  if (body !== undefined) {
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
    init.headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(path, init);
  if (res.status === 401 && !path.startsWith('/auth/')) onUnauthorized();
  if (raw) return res;
  const text = await res.text();
  const data = text ? safeJson(text) : null;
  if (res.status === 403 && data?.suspended) onSuspended(data.error);
  if (!res.ok) throw new ApiError(data?.error || `Error ${res.status}`, res.status);
  return data;
}

function safeJson(text) {
  try { return JSON.parse(text); } catch { return { error: text }; }
}

/**
 * Answers that rarely change (a title, its chapter list, a chapter's pages...) are kept for a few
 * minutes, and a request already on its way is shared: going from a title to its reader and back,
 * or from one chapter to the next, then needs no round trip at all.
 */
const memo = new Map();
const MEMO_MS = 5 * 60_000;
const MEMO_MAX = 300;
/** Forgets every kept answer: on logout or a new session, nothing of the previous user may be reused. */
export function clearMemo() { memo.clear(); }
function cached(path) {
  const hit = memo.get(path);
  if (hit && Date.now() - hit.at < MEMO_MS) return hit.promise;
  const promise = request('GET', path);
  memo.set(path, { at: Date.now(), promise });
  promise.catch(() => memo.delete(path));
  if (memo.size > MEMO_MAX) memo.delete(memo.keys().next().value);
  return promise;
}

const qs = (params) => new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')).toString();

export const api = {
  me: () => request('GET', '/auth/me'),
  login: (name, password) => { memo.clear(); return request('POST', '/auth/login', { name, password }); },
  guest: () => { memo.clear(); return request('POST', '/auth/guest'); },
  logout: () => { memo.clear(); return request('POST', '/auth/logout'); },

  sources: () => request('GET', '/api/sources'),
  latest: (id, page = 1, opts) => request('GET', `/api/sources/${encodeURIComponent(id)}/latest?${qs({ page })}`, undefined, opts),
  browse: (id, page, filters, opts) => request('GET', `/api/sources/${encodeURIComponent(id)}/browse?${qs({ page, f: filters ? JSON.stringify(filters) : '' })}`, undefined, opts),
  media: (s, id) => cached(`/api/media?${qs({ s, id })}`),
  chapters: (s, id, status) => cached(`/api/chapters?${qs({ s, id, status })}`),
  pages: (s, c) => cached(`/api/pages?${qs({ s, c })}`),
  text: (s, c) => cached(`/api/text?${qs({ s, c })}`),
  videos: (s, c) => request('GET', `/api/videos?${qs({ s, c })}`),
  metadata: (s, id) => cached(`/api/metadata?${qs({ s, id })}`),
  similar: (s, id) => request('GET', `/api/similar?${qs({ s, id })}`),
  alternatives: (s, id) => request('GET', `/api/alternatives?${qs({ s, id })}`),
  locate: (related) => request('POST', '/api/locate', related),
  random: (kind, genre) => request('GET', `/api/random?${qs({ kind, genre })}`),
  forYou: () => request('GET', '/api/foryou'),
  updates: () => request('GET', '/api/updates'),

  library: () => request('GET', '/api/library'),
  saveLibrary: (row) => request('PUT', '/api/library', row),
  removeLibrary: (s, id) => request('DELETE', `/api/library?${qs({ s, id })}`),
  progress: (s, id) => request('GET', `/api/progress?${qs({ s, id })}`),
  saveProgress: (rows, history) => request('POST', '/api/progress', { rows, history }),
  categories: () => request('GET', '/api/categories'),
  /** {old, new}: only new creates, both rename, only old deletes. Returns the new list. */
  editCategory: (change) => request('POST', '/api/categories', change),
  bookmarks: (s, id) => request('GET', `/api/bookmarks?${qs({ s, id })}`),
  saveBookmark: (row) => request('PUT', '/api/bookmarks', row),
  history: () => request('GET', '/api/history'),
  removeHistory: (s, id) => request('DELETE', `/api/history?${qs({ s, id })}`),
  settings: () => request('GET', '/api/settings'),
  saveSettings: (json) => request('PUT', '/api/settings', json),
  exportUrl: '/api/export',
  importData: (text, mode) => request('POST', `/api/import?${qs({ mode })}`, text),

  adminUsers: () => request('GET', '/api/admin/users'),
  adminCreate: (name, password, admin) => request('POST', '/api/admin/users', { name, password, admin }),
  adminReset: (id) => request('POST', `/api/admin/users/${id}/password`),
  adminDelete: (id) => request('DELETE', `/api/admin/users/${id}`),
  adminReactivate: (id) => request('POST', `/api/admin/users/${id}/reactivate`)
};

/**
 * Downloads chapters to the user's device; the server puts the file together while it is sent.
 * Everything goes through a hidden frame: a file goes straight to the browser's downloads and never
 * loads the frame, while an error (a download already running, no server for that episode...) does,
 * so it is shown as a message instead of being saved as a file. iPhones and iPads only download from
 * a plain link, so there one chapter still uses one.
 */
const IOS = /iPhone|iPad|iPod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

export function downloadChapters({ sourceId, mediaId, title, chapterIds }, onError) {
  if (chapterIds.length === 1 && !IOS) {
    const frame = errorFrame(onError);
    frame.src = `/api/download?${qs({ s: sourceId, id: mediaId, t: title, c: chapterIds[0] })}`;
    return;
  }
  if (chapterIds.length === 1) {
    const a = document.createElement('a');
    a.href = `/api/download?${qs({ s: sourceId, id: mediaId, t: title, c: chapterIds[0] })}`;
    a.download = '';
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
    return;
  }
  const frame = errorFrame(onError);
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = '/api/download';
  form.target = frame.name;
  const add = (k, v) => { const i = document.createElement('input'); i.type = 'hidden'; i.name = k; i.value = v; form.appendChild(i); };
  add('s', sourceId); add('id', mediaId); add('t', title || '');
  add('ids', chapterIds.join('\n'));
  document.body.appendChild(form);
  form.submit();
  form.remove();
}

/** A hidden frame that reports what the server answered when it was an error, not a file. */
function errorFrame(onError) {
  const frame = document.createElement('iframe');
  frame.name = 'yk-download-' + Date.now() + '-' + Math.random().toString(36).slice(2);
  frame.hidden = true;
  frame.onload = () => {
    // A download never loads the frame; an error answer does.
    try {
      const text = frame.contentDocument?.body?.innerText || '';
      if (text) onError?.(safeJson(text).error || text);
    } catch {}
    setTimeout(() => frame.remove(), 1000);
  };
  document.body.appendChild(frame);
  // The frame stays a while in case the server answers late with an error.
  setTimeout(() => frame.remove(), 30 * 60_000);
  return frame;
}

/**
 * Searches all enabled sources; [onUpdate] gets the ranked groups each time one answers.
 * Returns a function that stops the search.
 */
export function searchStream(query, { types, lang } = {}, onUpdate, onEnd) {
  const url = `/api/search?${qs({ q: query, types: types?.join(','), lang })}`;
  const source = new EventSource(url, { withCredentials: true });
  source.addEventListener('update', (e) => onUpdate(JSON.parse(e.data)));
  const finish = () => { source.close(); onEnd?.(); };
  source.addEventListener('end', finish);
  source.onerror = finish;
  return () => source.close();
}
