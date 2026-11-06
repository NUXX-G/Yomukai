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
  adminReactivate: (id) => request('POST', `/api/admin/users/${id}/reactivate`),

  twoFactor: (challenge, code) => request('POST', '/auth/2fa', { challenge, code }),
  inviteInfo: (token) => request('GET', `/auth/invite/${encodeURIComponent(token)}`),
  redeemInvite: (token, name, password) => request('POST', `/auth/invite/${encodeURIComponent(token)}`, { name, password }),
  setup: (token, name, password) => request('POST', '/auth/setup', { token, name, password }),
  sessions: () => request('GET', '/api/sessions'),
  closeSession: (id) => request('DELETE', `/api/sessions/${id}`),
  closeOtherSessions: () => request('POST', '/api/sessions/close-others'),
  twoFactorStatus: () => request('GET', '/api/2fa'),
  twoFactorSetup: (password) => request('POST', '/api/2fa/setup', { password }),
  twoFactorEnable: (code) => request('POST', '/api/2fa/enable', { code }),
  twoFactorDisable: (password, code) => request('POST', '/api/2fa/disable', { password, code }),
  adminInvites: () => request('GET', '/api/admin/invites'),
  adminCreateInvite: (days, note) => request('POST', '/api/admin/invites', { days, note }),
  adminRevokeInvite: (id) => request('DELETE', `/api/admin/invites/${id}`),
  adminAudit: () => request('GET', '/api/admin/audit'),
  adminCanary: () => request('GET', '/api/admin/canary'),
  adminRunCanary: (id) => request('POST', `/api/admin/canary/${encodeURIComponent(id)}`),
  features: () => request('GET', '/api/features'),
  continueList: () => request('GET', '/api/continue'),
  discover: (kind) => cached(`/api/discover/${kind}`),
  discoverBrowse: (kind, filters) => request('GET', `/api/discover/${kind}/browse?${qs(filters)}`),
  unified: () => request('GET', '/api/updates/unified'),
  work: (s, id) => request('GET', `/api/work?${qs({ s, id })}`),
  workChapters: (key) => request('GET', `/api/works/${encodeURIComponent(key)}/chapters`),
  workChoice: (key, number, sourceId) => request('PUT', `/api/works/${encodeURIComponent(key)}/choice`, { number, sourceId }),
  workContinue: (key, episode) => request('GET', `/api/works/${encodeURIComponent(key)}/continue?${qs({ episode })}`),
  calendar: (days = 7) => request('GET', `/api/calendar?${qs({ days })}`),
  /** Episode number -> 'FILLER' | 'MIXED' (the server answers in lower case). */
  fillers: async (title) => Object.fromEntries(Object.entries((await request('GET', `/api/fillers?${qs({ title })}`)) || {}).map(([n, k]) => [n, String(k).toUpperCase()])),
  setAdaptation: (body) => request('PUT', '/api/adaptations', body),
  rooms: () => request('GET', '/api/rooms'),
  roomMine: () => request('GET', '/api/rooms/mine'),
  roomCreate: (name) => request('POST', '/api/rooms', { name }),
  room: (code) => request('GET', `/api/rooms/${encodeURIComponent(code)}`),
  profile: () => request('GET', '/api/profile'),
  saveProfile: (body) => request('PUT', '/api/profile', body),
  uploadProfileImage: async (kind, file) => {
    const res = await fetch(`/api/profile/${kind}`, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': file.type || 'application/octet-stream' }, body: file });
    if (!res.ok) throw new ApiError((await res.json().catch(() => ({}))).error || 'No se pudo subir la imagen', res.status);
  },
  removeProfileImage: (kind) => request('DELETE', `/api/profile/${kind}`),
  users: () => request('GET', '/api/users'),
  user: (name) => request('GET', `/api/users/${encodeURIComponent(name)}`),
  presence: (body) => request('POST', '/api/presence', body),
  feed: () => request('GET', '/api/feed'),
  friendsOn: (s, id) => request('GET', `/api/friends-on?${qs({ s, id })}`),
  ratings: (s, id) => request('GET', `/api/ratings?${qs({ s, id })}`),
  rate: (body) => request('PUT', '/api/ratings', body),
  recommend: (name, body) => request('POST', `/api/recommend/${encodeURIComponent(name)}`, body),
  recommendations: () => request('GET', '/api/recommendations'),
  recommendationsSeen: () => request('POST', '/api/recommendations/seen'),
  achievements: () => request('GET', '/api/achievements'),
  newAchievements: () => request('GET', '/api/achievements/new'),
  achievementEvent: (kind) => request('POST', '/api/achievements/event', { kind }),
  forumCategories: () => request('GET', '/api/forum/categories'),
  forumList: (c, page = 0) => request('GET', `/api/forum?${qs({ c, page })}`),
  forumWork: (s, id) => request('GET', `/api/forum/work?${qs({ s, id })}`),
  forumThread: (id, upTo, page = 0) => request('GET', `/api/forum/${id}?${qs({ upTo, page })}`),
  forumNew: (body) => request('POST', '/api/forum', body),
  forumReply: (id, body) => request('POST', `/api/forum/${id}/posts`, body),
  forumEdit: (postId, body) => request('PUT', `/api/forum/posts/${postId}`, { body }),
  forumDelete: (postId) => request('DELETE', `/api/forum/posts/${postId}`),
  forumReact: (postId, emoji) => request('POST', `/api/forum/posts/${postId}/react`, { emoji }),
  forumImage: async (file) => {
    const res = await fetch('/api/forum/images', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': file.type || 'application/octet-stream' }, body: file });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new ApiError(data.error || 'No se pudo subir la imagen', res.status);
    return data.id;
  },
  adminThread: (id, body) => request('PUT', `/api/admin/forum/threads/${id}`, body),
  adminMute: (name, minutes, reason) => request('POST', '/api/admin/mute', { name, minutes, reason }),
  notifications: () => request('GET', '/api/notifications'),
  notificationsRead: () => request('POST', '/api/notifications/read'),
  pushStatus: () => request('GET', '/api/notifications/push'),
  pushEnable: () => request('POST', '/api/notifications/push'),
  pushDisable: () => request('DELETE', '/api/notifications/push'),
  ocr: (s, c, p) => request('GET', `/api/ocr?${qs({ s, c, p })}`),
  ocrRequest: (sourceId, chapterId, lang) => request('POST', '/api/ocr/request', { sourceId, chapterId, lang }),
  translate: (texts, from, to) => request('POST', '/api/translate', { texts, from, to }),
  wordLevels: (lang) => request('GET', `/api/words/levels?${qs({ lang })}`),
  saveWord: (body) => request('POST', '/api/words', body),
  wordsDue: () => request('GET', '/api/words/due'),
  words: () => request('GET', '/api/words'),
  reviewWord: (id, grade) => request('POST', `/api/words/${id}/review`, { grade }),
  deleteWord: (id) => request('DELETE', `/api/words/${id}`),
  subtitles: (s, c, lang) => request('GET', `/api/subtitles?${qs({ s, c, lang })}`, undefined, { raw: true }),
  ttsPrefetch: (voice, texts, rate) => request('POST', '/api/tts/prefetch', { voice, texts, rate }),
  themes: () => request('GET', '/api/themes'),
  currentTheme: () => request('GET', '/api/themes/current'),
  checkTheme: (theme) => request('POST', '/api/themes/check', theme),
  saveTheme: (id, theme, published) => (id ? request('PUT', `/api/themes/${id}`, { theme, published }) : request('POST', '/api/themes', { theme, published })),
  deleteTheme: (id) => request('DELETE', `/api/themes/${id}`),
  useTheme: (body) => request('PUT', '/api/themes/use', body),
  uploadBackground: async (file) => {
    const res = await fetch('/api/themes/backgrounds', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': file.type || 'application/octet-stream' }, body: file });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new ApiError(data.error || 'No se pudo subir la imagen', res.status);
    return data.id;
  },
  skip: (s, c, other) => request('GET', `/api/skip?${qs({ s, c, other })}`),
  job: (id) => request('GET', `/api/jobs/${id}`),
  adminStatus: () => request('GET', '/api/admin/status'),
  integrations: () => request('GET', '/api/admin/integrations'),
  saveIntegrations: (body) => request('PUT', '/api/admin/integrations', body),
  testJellyfin: () => request('POST', '/api/admin/integrations/jellyfin/test'),
  jellyfinDownload: (sourceId, mediaId, chapterIds) => request('POST', '/api/admin/jellyfin/download', { sourceId, mediaId, chapterIds }),
  cbzExport: (sourceId, mediaId, chapterIds) => request('POST', '/api/admin/cbz/export', { sourceId, mediaId, chapterIds }),
  adminWorkers: () => request('GET', '/api/admin/workers'),
  adminCreateWorker: (name) => request('POST', '/api/admin/workers', { name }),
  adminPauseWorker: (id, paused) => request('POST', `/api/admin/workers/${id}/pause`, { paused }),
  adminWorkerBackup: (id, allowed) => request('POST', `/api/admin/workers/${id}/backup`, { allowed }),
  adminRevokeWorker: (id) => request('DELETE', `/api/admin/workers/${id}`),
  adminTestWorker: () => request('POST', '/api/admin/jobs/echo'),
  adminFeatures: () => request('GET', '/api/admin/features'),
  adminSetFeature: (name, enabled) => request('PUT', `/api/admin/features/${encodeURIComponent(name)}`, { enabled })
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
    // A download never loads the frame; an error answer does. The empty page a new frame starts with
    // also fires "load": removing the frame then cancelled any download slower than a second to
    // start, which is every anime episode (the server first has to find a video server that works).
    let href;
    try { href = frame.contentWindow?.location?.href || ''; } catch { href = 'cross-origin'; }
    if (!href || href === 'about:blank') return;
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
