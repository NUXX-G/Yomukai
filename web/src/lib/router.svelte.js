// A small history router: real URLs, the back button works, and pages keep their scroll.

export const route = $state({ path: location.pathname, query: new URLSearchParams(location.search), params: {}, name: '' });

const routes = [];
const scrolls = new Map();

/** Registers [name] for a pattern like "/obra/:source/:id". */
export function define(name, pattern) {
  const keys = [];
  const regex = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '/?$');
  routes.push({ name, regex, keys });
}

function resolve() {
  route.path = location.pathname;
  route.query = new URLSearchParams(location.search);
  for (const r of routes) {
    const m = r.regex.exec(location.pathname);
    if (m) {
      route.name = r.name;
      route.params = Object.fromEntries(r.keys.map((k, i) => [k, decodeURIComponent(m[i + 1])]));
      return;
    }
  }
  route.name = 'home';
  route.params = {};
}

export function navigate(url, { replace = false } = {}) {
  scrolls.set(location.pathname + location.search, window.scrollY);
  if (replace) history.replaceState({}, '', url);
  else history.pushState({}, '', url);
  resolve();
  requestAnimationFrame(() => window.scrollTo(0, 0));
}

export function back(fallback = '/') {
  if (history.length > 1 && document.referrer !== '' || history.state) history.back();
  else navigate(fallback, { replace: true });
}

window.addEventListener('popstate', () => {
  resolve();
  restoreScroll(scrolls.get(location.pathname + location.search) ?? 0);
});

/** Back where the page was left; pages fill in as their data arrives, so it waits (briefly) for the height. */
function restoreScroll(y) {
  const until = performance.now() + 2000;
  const attempt = () => {
    const reachable = document.documentElement.scrollHeight - innerHeight >= y - 4;
    if (reachable || performance.now() > until) { window.scrollTo(0, y); return; }
    requestAnimationFrame(attempt);
  };
  requestAnimationFrame(attempt);
}

/** Makes plain <a href="/..."> links navigate without reloading the page. */
document.addEventListener('click', (e) => {
  const a = e.target.closest?.('a[href]');
  if (!a || a.target || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return;
  const href = a.getAttribute('href');
  if (!href.startsWith('/') || href.startsWith('/api/')) return;
  e.preventDefault();
  navigate(href);
});

export const enc = encodeURIComponent;
export const media = (s, id) => `/obra/${enc(s)}/${enc(id)}`;
export const reader = (kind, s, id, chapterId) => `/${kind === 'anime' ? 'ver' : 'leer'}/${enc(s)}/${enc(id)}/${enc(chapterId)}`;

export function startRouter() {
  // The router puts the scroll back itself; the browser's own attempt fires before the page has content.
  if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
  resolve();
}
