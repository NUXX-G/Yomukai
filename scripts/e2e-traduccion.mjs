// F6.3: seleccionar una frase en una novela → «Traducir», con traductor del navegador simulado y sin ninguno.
// Uso: servidor de pruebas (por defecto http://localhost:8195, o YOMUKAI_URL) con admin / admin-password-1 y la web
// construida; "npm i playwright" en una carpeta aparte y: node scripts/<este>.mjs <carpeta de capturas>
// Usa fuentes reales (MangaDex EN, NovelCool EN): si una cambia, actualiza los ids de abajo.
import { chromium, devices } from 'playwright';
const out = process.argv[2];
const B = process.env.YOMUKAI_URL || "http://localhost:8195";
const enc = encodeURIComponent;
const url = `${B}/leer/novelcool_novels_en/${enc('https://www.novelcool.com/novel/original/id-281961.html')}/${enc('https://www.novelcool.com/chapter/Update-1/13770189/')}`;
const browser = await chromium.launch();

async function run(name, opts, fakeTranslator) {
  const c = await browser.newContext({ ...opts, locale: 'es-ES' });
  await c.request.post(B + '/auth/login', { data: { name: 'admin', password: 'admin-password-1' } });
  if (fakeTranslator) {
    // Chrome's on-device Translator API, simulated (Playwright's Chromium has no model).
    await c.addInitScript(() => {
      self.Translator = {
        availability: async () => 'available',
        create: async ({ sourceLanguage, targetLanguage }) => ({ translate: async (t) => `[${sourceLanguage}>${targetLanguage}] ${t}` })
      };
    });
  }
  const p = await c.newPage();
  const errors = [];
  p.on('requestfinished', async (r) => { if (r.url().includes('/api/translate') || r.url().includes('dictionary')) { const res = await r.response(); console.log('REQ', r.url(), res?.status()); } });
  p.on('pageerror', (e) => errors.push(e.message));
  await p.goto(url);
  await p.waitForSelector('article p', { timeout: 60000 });
  await p.waitForTimeout(1000);
  // Select the first words of a paragraph, as a drag or the phone's long press would.
  const picked = await p.evaluate(() => {
    const para = [...document.querySelectorAll('article p')].find((x) => x.textContent.trim().split(/\s+/).length > 6);
    const node = [...para.childNodes].find((n) => n.nodeType === 3 && n.textContent.trim().length > 20) || para.firstChild;
    const text = node.textContent;
    const start = text.search(/\S/);
    const end = text.indexOf(' ', text.indexOf(' ', text.indexOf(' ', start) + 1) + 1);
    const r = document.createRange();
    r.setStart(node, start);
    r.setEnd(node, end);
    const s = document.getSelection();
    s.removeAllRanges();
    s.addRange(r);
    para.scrollIntoView({ block: 'center' });
    return s.toString();
  });
  await p.waitForSelector('.translate-chip', { timeout: 3000 });
  await p.screenshot({ path: `${out}/tr-${name}-chip.png` });
  await p.dispatchEvent('.translate-chip', 'pointerdown');
  await p.waitForSelector('.sheet', { timeout: 3000 });
  await p.waitForFunction(() => !document.querySelector('.sheet .spinner'), null, { timeout: 30000 }).catch(async () => { console.log('SPINNER STUCK', await p.innerHTML('.sheet')); });
  const sheet = (await p.textContent('.sheet')).replace(/\s+/g, ' ').slice(0, 260);
  await p.screenshot({ path: `${out}/tr-${name}-sheet.png` });
  console.log(name, '| selected:', JSON.stringify(picked), '\n  sheet:', sheet, '\n  errors:', errors);
  await c.close();
}

await run('desktop-browser', { viewport: { width: 1280, height: 800 } }, true);
await run('mobile-none', devices['iPhone 13'], false);

await browser.close();
