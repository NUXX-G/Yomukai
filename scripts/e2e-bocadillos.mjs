// F6.3: clic derecho / pulsación larga sobre un bocadillo del manga, con la respuesta del OCR simulada.
// Uso: servidor de pruebas (por defecto http://localhost:8195, o YOMUKAI_URL) con admin / admin-password-1 y la web
// construida; "npm i playwright" en una carpeta aparte y: node scripts/<este>.mjs <carpeta de capturas>
// Usa fuentes reales (MangaDex EN, NovelCool EN): si una cambia, actualiza los ids de abajo.
import { chromium, devices } from 'playwright';
const out = process.argv[2];
const B = process.env.YOMUKAI_URL || "http://localhost:8195";
const url = `${B}/leer/mangadex_en/dc07afaa-643a-4bf4-a9d0-b21ecc101736/5a9d1282-9b6b-4853-b18e-396d54f0fc52`;
const browser = await chromium.launch();

async function run(name, opts, act) {
  const c = await browser.newContext({ ...opts, locale: 'es-ES' });
  await c.request.post(B + '/auth/login', { data: { name: 'admin', password: 'admin-password-1' } });
  const cur = await (await c.request.get(B + '/api/settings')).json();
  cur.reader = { ...(cur.reader || {}), mode: 'vertical' };
  await c.request.put(B + '/api/settings', { data: cur });
  const p = await c.newPage();
  // Every page "has" one bubble in the middle of the page.
  await p.route('**/api/ocr?*', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify({ boxes: [{ x: 0.3, y: 0.0, w: 0.4, h: 1, text: 'Where are you going?', kind: 'BUBBLE' }] }) }));
  await p.goto(url);
  await p.waitForSelector('.pg img', { timeout: 60000 });
  await p.waitForTimeout(2500);
  const img = await p.locator('.pg img').first().boundingBox();
  const x = img.x + img.width / 2;
  const y = Math.min(img.y + img.height / 2, (opts.viewport?.height || 800) / 2);
  await act(p, x, y, c);
  await p.waitForSelector('.sheet', { timeout: 8000 }).catch(() => {});
  await p.waitForFunction(() => !document.querySelector('.sheet .spinner'), null, { timeout: 30000 }).catch(() => {});
  const sheet = (await p.textContent('.sheet').catch(() => 'SIN HOJA')).replace(/\s+/g, ' ').slice(0, 200);
  await p.screenshot({ path: `${out}/bubble-${name}.png` });
  console.log(name, '|', sheet);
  await c.close();
}

await run('desktop-rightclick', { viewport: { width: 1280, height: 800 } }, async (p, x, y) => { await p.mouse.click(x, y, { button: 'right' }); });
await run('mobile-longpress', devices['iPhone 13'], async (p, x, y, c) => {
  const cdp = await c.newCDPSession(p);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  await p.waitForTimeout(800);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
});
await browser.close();
