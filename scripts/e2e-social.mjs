// Prueba de punta a punta del bloque 4 (DMs, salas comunes, compartir) en Chromium con dos usuarios.
// Uso: servidor en http://localhost:8194 con usuarios ana y beto (contraseña <nombre>-password-1),
// 'npm i playwright' en una carpeta aparte y: node scripts/e2e-social.mjs <carpeta de capturas>
// Prueba de punta a punta del bloque 4 en un navegador real: dos personas (ana y beto) a la vez.
import { chromium, devices } from 'playwright';

const B = 'http://localhost:8194';
const OUT = process.argv[2];
const log = (...a) => console.log(...a);
let fails = 0;
const check = (ok, what) => { log((ok ? 'OK   ' : 'FALLO') + ' ' + what); if (!ok) fails++; };

const browser = await chromium.launch();
async function person(name, mobile = false) {
  const ctx = await browser.newContext(mobile ? { ...devices['iPhone 13'] } : { viewport: { width: 1366, height: 860 } });
  const r = await ctx.request.post(B + '/auth/login', { data: { name, password: name + '-password-1' } });
  if (!r.ok()) throw new Error('login ' + name + ' ' + r.status());
  const page = await ctx.newPage();
  page.on('pageerror', (e) => { log('ERROR JS (' + name + '): ' + e.message); fails++; });
  return { ctx, page };
}

const ana = await person('ana');
const beto = await person('beto', true); // beto en el móvil

// 1. Beto abre la lista de mensajes; Ana le escribe desde el escritorio.
await beto.page.goto(B + '/mensajes');
await beto.page.waitForSelector('text=Mensajes');
await ana.page.goto(B + '/mensajes/beto');
const box = ana.page.getByPlaceholder('Escribe un mensaje');
await box.waitFor();
const stamp = 'Prueba en vivo ' + Date.now();
await box.fill(stamp);
await box.press('Enter');
await ana.page.waitForSelector(`text=${stamp}`);
check(true, 'Ana envía con Enter y ve su mensaje');

// El contador del icono de Beto sube sin recargar.
const badge = beto.page.locator('a[href="/mensajes"] .count');
await badge.waitFor({ timeout: 8000 }).catch(() => {});
check(await badge.isVisible(), 'Beto (móvil) ve el contador de no leídos en el icono sin recargar');
await beto.page.screenshot({ path: OUT + '/movil-lista.png' });

// 2. Beto abre la conversación: ve el mensaje; Ana ve «Visto».
await beto.page.getByRole('link', { name: /ana/ }).first().click();
await beto.page.waitForSelector(`text=${stamp}`);
check(true, 'Beto abre la conversación y ve el mensaje');
await ana.page.waitForSelector('text=Visto', { timeout: 8000 }).then(() => check(true, 'Ana ve «Visto»'), () => check(false, 'Ana ve «Visto»'));

// 3. «escribiendo...»: Beto escribe, Ana lo ve.
await beto.page.getByPlaceholder('Escribe un mensaje').type('hola, ', { delay: 40 });
await ana.page.waitForSelector('text=escribiendo...', { timeout: 8000 }).then(() => check(true, 'Ana ve «escribiendo...»'), () => check(false, 'Ana ve «escribiendo...»'));
await beto.page.getByPlaceholder('Escribe un mensaje').type('¿qué tal?');
await beto.page.getByRole('button', { name: 'Enviar' }).click();
await ana.page.waitForSelector('text=hola, ¿qué tal?', { timeout: 8000 }).then(() => check(true, 'El mensaje de Beto llega en vivo a Ana'), () => check(false, 'El mensaje de Beto llega en vivo a Ana'));
await beto.page.screenshot({ path: OUT + '/movil-conversacion.png' });
await ana.page.screenshot({ path: OUT + '/escritorio-conversacion.png' });

// 4. Tarjeta con escudo anti-spoilers: Ana comparte un episodio; Beto la ve tapada y la destapa.
await ana.ctx.request.post(B + '/api/dm/beto', { data: { text: '', card: { kind: 'episode', sourceId: 'animeflv', mediaId: 'frieren', chapterId: 'frieren-9', title: 'Frieren', type: 'ANIME', lang: 'es', number: 9, label: 'Ep. 9', spoiler: true } } });
const shield = beto.page.getByRole('button', { name: /Spoiler de Frieren/ });
await shield.last().waitFor({ timeout: 8000 }).then(() => check(true, 'La tarjeta llega tapada (escudo anti-spoilers)'), () => check(false, 'La tarjeta llega tapada'));
await shield.last().click();
const link = beto.page.locator('a.card[href="/ver/animeflv/frieren/frieren-9"]').last();
check(await link.isVisible(), 'Al tocarla se ve la tarjeta y enlaza dentro de la app');

// 5. Borrar un mensaje propio: desaparece también para el otro.
const mine = ana.page.locator('.msg.mine', { hasText: stamp });
await mine.hover();
await mine.getByRole('button', { name: 'Borrar este mensaje' }).click();
await beto.page.waitForSelector(`text=${stamp}`, { state: 'detached', timeout: 8000 }).then(() => check(true, 'Borrar un mensaje propio lo quita también al otro'), () => check(false, 'Borrar un mensaje propio'));

// 6. Buscar en los mensajes.
await ana.page.getByPlaceholder('Buscar en esta conversación').fill('qué tal');
await ana.page.waitForSelector('.found >> text=hola, ¿qué tal?', { timeout: 8000 }).then(() => check(true, 'Buscar encuentra el mensaje'), () => check(false, 'Buscar encuentra el mensaje'));

// 7. Salas comunes: Ana crea una, Beto la ve en /salas y entra; la cola aparece en los dos.
for (const r of await (await ana.ctx.request.get(B + '/api/rooms/open')).json()) if (r.owner === 'ana') await ana.ctx.request.delete(B + '/api/rooms/' + r.code);
await ana.page.goto(B + '/salas');
await ana.page.getByPlaceholder(/Nombre de la sala/).fill('Prueba Playwright');
await ana.page.getByRole('button', { name: 'Crear sala común' }).click();
await ana.page.waitForURL(/\/sala\//);
const code = ana.page.url().split('/sala/')[1];
await beto.page.goto(B + '/salas');
await beto.page.waitForSelector('text=Prueba Playwright');
check(true, 'Beto ve la sala común en /salas');
await beto.page.screenshot({ path: OUT + '/movil-salas.png' });
await beto.page.goto(B + '/sala/' + code);
await beto.page.getByRole('tab', { name: /Cola/ }).waitFor();
// Ana (anfitriona) mete un episodio en la cola por el mismo WebSocket que usa el reproductor.
await ana.page.evaluate((code) => new Promise((ok) => {
  const ws = new WebSocket(`ws://${location.host}/api/rooms/${code}/ws`);
  ws.onopen = () => { ws.send(JSON.stringify({ t: 'queue_add', item: { sourceId: 'animeflv', mediaId: 'frieren', chapterId: 'frieren-10', title: 'Frieren', episode: 'Ep. 10' } })); setTimeout(() => { ws.close(); ok(); }, 800); };
}), code);
await beto.page.getByRole('tab', { name: /Cola/ }).click();
await beto.page.waitForSelector('.queue >> text=Frieren', { timeout: 8000 }).then(() => check(true, 'La cola llega en vivo al otro'), () => check(false, 'La cola llega en vivo'));
await beto.page.screenshot({ path: OUT + '/movil-cola.png' });

// 8. Compartir en una sala desde fuera (la hoja de compartir usa POST /chat) y verlo en el chat de la sala.
await beto.page.getByRole('tab', { name: 'Chat' }).click();
await ana.ctx.request.post(B + `/api/rooms/${code}/chat`, { data: { text: 'mirad esta', card: { kind: 'work', sourceId: 'mangadex', mediaId: 'x', title: 'Dandadan', type: 'MANGA' } } });
await beto.page.waitForSelector('text=Dandadan', { timeout: 8000 }).then(() => check(true, 'Lo compartido en la sala aparece en su chat'), () => check(false, 'Lo compartido en la sala'));

// 9. Foro: el editor tiene «Obra» para buscar y adjuntar.
await ana.page.goto(B + '/foro');
await ana.page.getByRole('button', { name: /Nuevo hilo/ }).click().catch(() => {});
const shareWork = ana.page.getByRole('button', { name: 'Compartir una obra' });
check(await shareWork.isVisible().catch(() => false), 'El editor del foro tiene «Compartir una obra»');

log(fails ? `\n${fails} FALLOS` : '\nTODO BIEN');
await browser.close();
process.exit(fails ? 1 : 0);
