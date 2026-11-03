// Talks to the Yomukai server. Every call sends the session cookie; a 401 means "log in again".

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(fn) { onUnauthorized = fn; }

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
  if (!res.ok) throw new ApiError(data?.error || `Error ${res.status}`, res.status);
  return data;
}

function safeJson(text) {
  try { return JSON.parse(text); } catch { return { error: text }; }
}

const qs = (params) => new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')).toString();

export const api = {
  me: () => request('GET', '/auth/me'),
  login: (name, password) => request('POST', '/auth/login', { name, password }),
  guest: () => request('POST', '/auth/guest'),
  logout: () => request('POST', '/auth/logout'),

  sources: () => request('GET', '/api/sources'),
  latest: (id, page = 1, opts) => request('GET', `/api/sources/${encodeURIComponent(id)}/latest?${qs({ page })}`, undefined, opts),
  browse: (id, page, filters, opts) => request('GET', `/api/sources/${encodeURIComponent(id)}/browse?${qs({ page, f: filters ? JSON.stringify(filters) : '' })}`, undefined, opts),
  media: (s, id) => request('GET', `/api/media?${qs({ s, id })}`),
  chapters: (s, id, status) => request('GET', `/api/chapters?${qs({ s, id, status })}`),
  pages: (s, c) => request('GET', `/api/pages?${qs({ s, c })}`),
  text: (s, c) => request('GET', `/api/text?${qs({ s, c })}`),
  videos: (s, c) => request('GET', `/api/videos?${qs({ s, c })}`),
  metadata: (s, id) => request('GET', `/api/metadata?${qs({ s, id })}`),
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
  history: () => request('GET', '/api/history'),
  removeHistory: (s, id) => request('DELETE', `/api/history?${qs({ s, id })}`),
  settings: () => request('GET', '/api/settings'),
  saveSettings: (json) => request('PUT', '/api/settings', json),
  exportUrl: '/api/export',
  importData: (text, mode) => request('POST', `/api/import?${qs({ mode })}`, text),

  adminUsers: () => request('GET', '/api/admin/users'),
  adminCreate: (name, password, admin) => request('POST', '/api/admin/users', { name, password, admin }),
  adminReset: (id) => request('POST', `/api/admin/users/${id}/password`),
  adminDelete: (id) => request('DELETE', `/api/admin/users/${id}`)
};

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
