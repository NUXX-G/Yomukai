#!/usr/bin/env node
// Tiempo de cada fuente, paso a paso, contra un servidor de Yomukai en marcha (F2.1):
//   listado (novedades) → ficha → lista de capítulos → páginas / texto / vídeo (primer capítulo)
// Cada paso se mide dos veces: en frío y en caliente (la segunda sale de la caché del servidor).
//   node scripts/source-timing.mjs [URL_BASE] [usuario] [contraseña] [fuente1,fuente2…]
// Escribe una tabla Markdown en la salida estándar.
const base = process.argv[2] || 'http://localhost:8193';
const user = process.argv[3] || 'admin';
const pass = process.argv[4] || 'admin-password-1';
const only = process.argv[5]?.split(',').filter(Boolean);
const TIMEOUT = 60_000;

let cookie = '';
async function call(path, init = {}) {
  const t = performance.now();
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), TIMEOUT);
  try {
    const r = await fetch(base + path, { ...init, signal: ctl.signal, headers: { 'content-type': 'application/json', cookie, ...(init.headers || {}) } });
    const body = await r.text();
    const ms = Math.round(performance.now() - t);
    if (!r.ok) return { ms, error: r.status + ' ' + body.slice(0, 80) };
    return { ms, data: body ? JSON.parse(body) : null };
  } catch (e) {
    return { ms: Math.round(performance.now() - t), error: e.name === 'AbortError' ? 'tiempo agotado' : e.message };
  } finally { clearTimeout(timer); }
}

const login = await fetch(base + '/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: user, password: pass }) });
cookie = (login.headers.get('set-cookie') || '').split(';')[0];
if (!login.ok) { console.error('No se pudo entrar:', login.status); process.exit(1); }

const all = (await call('/api/sources')).data;
const sources = (Array.isArray(all) ? all : all?.sources || []).filter((s) => s.enabled && (!only || only.includes(s.id)));
const enc = encodeURIComponent;
const fmt = (r) => (r ? (r.error ? `✗ ${r.ms} ms (${r.error.slice(0, 40)})` : `${r.ms} ms`) : '—');
const twice = async (path) => { const cold = await call(path); const warm = cold.error ? null : await call(path); return { cold, warm }; };

console.log('| Fuente | Tipo | Listado | Ficha (frío / caliente) | Capítulos (frío / caliente) | Contenido (frío / caliente) | 1.ª imagen (frío / caliente) |');
console.log('|---|---|---|---|---|---|---|');
for (const s of sources) {
  const list = await call(`/api/sources/${enc(s.id)}/latest?page=1`);
  const item = (list.data?.items || list.data || [])[0];
  if (!item) { console.log(`| ${s.id} | ${s.kind} | ${fmt(list)} | — | — | — |`); continue; }
  const media = await twice(`/api/media?s=${enc(s.id)}&id=${enc(item.id)}`);
  const chapters = await twice(`/api/chapters?s=${enc(s.id)}&id=${enc(item.id)}`);
  const first = chapters.cold.data?.chapters?.at(-1) || chapters.cold.data?.chapters?.[0];
  let content = { cold: null, warm: null };
  if (first) {
    const path = s.kind === 'anime' ? `/api/videos?s=${enc(s.id)}&c=${enc(first.url)}`
      : s.kind === 'novel' ? `/api/text?s=${enc(s.id)}&c=${enc(first.url)}`
        : `/api/pages?s=${enc(s.id)}&c=${enc(first.url)}`;
    content = await twice(path);
  }
  // The first page image, through the server's image proxy (what the reader waits for first).
  let image = { cold: null, warm: null };
  const pages = content.cold?.data?.pages || content.cold?.data;
  const src = Array.isArray(pages) ? (pages[0]?.url || pages[0]?.src || pages[0]) : null;
  if (typeof src === 'string' && src.startsWith('/')) {
    const get = async () => { const t = performance.now(); const r = await fetch(base + src, { headers: { cookie } }); const b = await r.arrayBuffer(); return r.ok ? { ms: Math.round(performance.now() - t), kb: Math.round(b.byteLength / 1024) } : { ms: Math.round(performance.now() - t), error: String(r.status) }; };
    image = { cold: await get(), warm: null };
    if (!image.cold.error) image.warm = await get();
  }
  console.log(`| ${s.id} | ${s.kind} | ${fmt(list)} | ${fmt(media.cold)} / ${fmt(media.warm)} | ${fmt(chapters.cold)} / ${fmt(chapters.warm)} | ${fmt(content.cold)} / ${fmt(content.warm)} | ${image.cold ? fmt(image.cold) + (image.cold.kb ? ` (${image.cold.kb} KB)` : "") + " / " + fmt(image.warm) : "—"} |`);
}
