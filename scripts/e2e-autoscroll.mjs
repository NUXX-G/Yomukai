// F6.2: mide la velocidad real del autoscroll (px/s) en modo normal e inteligente. Args: manga|novel segundos; END=1 empieza al final del capítulo, ONLY=normal80 una sola medida.
// Uso: servidor de pruebas (por defecto http://localhost:8195, o YOMUKAI_URL) con admin / admin-password-1 y la web
// construida; "npm i playwright" en una carpeta aparte y: node scripts/<este>.mjs <carpeta de capturas>
// Usa fuentes reales (MangaDex EN, NovelCool EN): si una cambia, actualiza los ids de abajo.
// Measures the real autoscroll speed in the manga reader: px/s over time for several speeds and both modes.
import { chromium } from 'playwright';
const B = process.env.YOMUKAI_URL || "http://localhost:8195";
const [, , kind = 'manga', seconds = '8'] = process.argv;
const END = process.env.END === '1';
const ONLY = process.env.ONLY;
const url = kind === 'manga'
  ? `${B}/leer/mangadex_en/dc07afaa-643a-4bf4-a9d0-b21ecc101736/5a9d1282-9b6b-4853-b18e-396d54f0fc52`
  : `${B}/leer/novelcool_novels_en/${encodeURIComponent('https://www.novelcool.com/novel/original/id-281961.html')}/${encodeURIComponent('https://www.novelcool.com/chapter/Update-1/13770189/')}`;
const browser = await chromium.launch();
const c = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'es-ES' });
await c.request.post(B + '/auth/login', { data: { name: 'admin', password: 'admin-password-1' } });

async function setReader(patch, novel = false) {
  const cur = await (await c.request.get(B + '/api/settings')).json();
  const key = novel ? 'novel' : 'reader';
  cur[key] = { ...(cur[key] || {}), ...patch };
  await c.request.put(B + '/api/settings', { data: cur });
}

async function measure(label, expected) {
  const p = await c.newPage();
  await p.goto(url);
  await p.waitForSelector(kind === 'manga' ? '.pg img' : 'article p', { timeout: 60000 });
  await p.waitForTimeout(2500);
  await p.evaluate((end) => window.scrollTo(0, end ? document.documentElement.scrollHeight - 1500 : 0), END);
  const btn = p.locator('button[aria-label="Desplazamiento automático"]').first();
  if (!(await btn.isVisible().catch(() => false))) { await p.mouse.click(640, 400); await p.waitForTimeout(400); }
  if (await btn.isVisible().catch(() => false)) await btn.click();
  else {
    // The novel reader starts it from its options sheet.
    await p.locator('button[aria-label="Texto"]').first().click().catch(() => {});
    await p.waitForTimeout(300);
    await p.locator('.opt-row button:has-text("Empezar")').first().click().catch((e) => console.log('no start', e.message));
  }
  const samples = [];
  const t0 = Date.now();
  for (let i = 0; i <= Number(seconds) * 2; i++) {
    samples.push([Date.now() - t0, await p.evaluate(() => [Math.round(scrollY), document.documentElement.scrollHeight])]);
    await p.waitForTimeout(500);
  }
  const speeds = samples.slice(1).map(([t, [y]], i) => Math.round((y - samples[i][1][0]) / ((t - samples[i][0]) / 1000)));
  console.log(label, 'expected', expected, 'px/s | measured:', speeds.join(' '), '| end y', samples.at(-1)[1][0], 'of', samples.at(-1)[1][1]);
  await p.close();
}

const startHook = () => {
  window.__startScroll = () => {
    const btn = [...document.querySelectorAll('button')].find((b) => /Desplazamiento automático|Parar desplazamiento/.test(b.getAttribute('aria-label') || ''));
    if (btn) btn.click(); else console.log('no button');
  };
};
await c.addInitScript(startHook);
const novel = kind !== 'manga';
for (const smart of [false, true]) {
  for (const speed of [15, 80, 240, 800]) {
    if (ONLY && ONLY !== (smart ? 'smart' : 'normal') + speed) continue;
    await setReader({ autoSpeed: speed, scrollMode: smart ? 'smart' : 'normal', wpm: 220 }, novel);
    await measure((smart ? 'smart ' : 'normal ') + speed, speed);
  }
}
await browser.close();
