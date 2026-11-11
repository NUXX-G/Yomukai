// F5: calendario con portadas, vista previa (ratón y pulsación larga), recomendar, sin desbordes en móvil.
// Uso: servidor de pruebas (por defecto http://localhost:8195, o YOMUKAI_URL) con admin / admin-password-1 y la web
// construida; "npm i playwright" en una carpeta aparte y: node scripts/<este>.mjs <carpeta de capturas>
// Usa fuentes reales (MangaDex EN, NovelCool EN): si una cambia, actualiza los ids de abajo.
import { chromium, devices } from 'playwright';
const out = process.argv[2];
const B = process.env.YOMUKAI_URL || "http://localhost:8195";
const browser = await chromium.launch();
async function ctx(opts) {
  const c = await browser.newContext({ ...opts, locale: 'es-ES' });
  await c.request.post(B + '/auth/login', { data: { name: 'admin', password: 'admin-password-1' } });
  return c;
}
const errors = [];
// Desktop
const d = await ctx({ viewport: { width: 1440, height: 900 } });
const p = await d.newPage();
p.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await p.goto(B + '/calendario');
await p.waitForSelector('.entry:not(.skeleton)', { timeout: 20000 });
await p.waitForTimeout(6000);
const imgs = await p.$$eval('.grid .entry img', (l) => l.map((i) => i.complete && i.naturalWidth > 0));
console.log('entries', (await p.$$('.entry')).length, 'imgs loaded', imgs.filter(Boolean).length, '/', imgs.length);
await p.screenshot({ path: out + '/cal-desktop.png' });
await p.hover('.entry >> nth=2');
await p.waitForSelector('.popover', { timeout: 3000 }); await p.waitForTimeout(2500);
console.log('popover text:', (await p.textContent('.popover')).slice(0, 200).replace(/\s+/g, ' '));
await p.screenshot({ path: out + '/cal-hover.png' });
// Recommend from the popover
await p.click('.popover .btn.tonal');
await p.fill('.popover textarea', 'Prueba del grupo');
await p.click('.popover >> text=Guardar');
await p.waitForTimeout(800);
console.log('picks section:', (await p.textContent('.picks')).slice(0, 200).replace(/\s+/g, ' '));
await p.mouse.move(5, 5);
await p.waitForTimeout(500);
await p.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
await p.waitForTimeout(800);
await p.waitForTimeout(3000); await p.screenshot({ path: out + '/cal-bottom.png', fullPage: false });
// Mobile, long press
const m = await ctx({ ...devices['iPhone 13'] });
const mp = await m.newPage();
await mp.goto(B + '/calendario');
await mp.waitForSelector('.entry:not(.skeleton)', { timeout: 20000 });
await mp.waitForTimeout(5000);
await mp.screenshot({ path: out + '/cal-mobile.png' });
const box = await mp.locator('.entry').nth(1).boundingBox();
const cdp = await m.newCDPSession(mp);
const pt = { x: box.x + 30, y: box.y + 20 };
await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [pt] });
await mp.waitForTimeout(700);
await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
await mp.waitForTimeout(500);
console.log('sheet open:', await mp.isVisible('.sheet'), 'url', mp.url());
await mp.screenshot({ path: out + '/cal-mobile-sheet.png' });
const hscroll = await mp.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
console.log('horizontal overflow mobile:', hscroll);
console.log('errors:', errors);
await browser.close();
