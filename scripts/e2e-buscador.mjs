// Una sola barra de búsqueda (W5, 2026-11-15): buscar desde la barra de arriba, volver a buscar en /buscar y que
// solo haya un campo visible y los resultados cambien. Uso: servidor en http://localhost:8193 con admin.
//   node scripts/e2e-buscador.mjs [URL_BASE]
import { chromium, devices } from 'playwright';

const B = process.argv[2] || 'http://localhost:8193';
const fails = [];
const check = (ok, msg) => { console.log((ok ? 'OK  ' : 'MAL ') + msg); if (!ok) fails.push(msg); };

const browser = await chromium.launch();
for (const [name, opts] of [['escritorio', { viewport: { width: 1366, height: 860 } }], ['móvil', devices['iPhone 13']]]) {
  const ctx = await browser.newContext(opts);
  const r = await ctx.request.post(B + '/auth/login', { data: { name: 'admin', password: 'admin-password-1' } });
  if (!r.ok()) throw new Error('login ' + r.status());
  const page = await ctx.newPage();
  await page.goto(B + '/');
  await page.keyboard.press('Control+k');
  if (!(await page.locator('.palette input').isVisible().catch(() => false))) {
    await page.locator('header.top button[aria-label^="Buscar"]:visible').first().click();
  }
  await page.locator('.palette input').fill('iruma');
  await page.keyboard.press('Enter');
  await page.waitForURL(/\/buscar\?q=iruma/);
  const visible = async () => page.locator('input[type=search]:visible, .palette input:visible').count();
  check((await visible()) === 1, `${name}: un solo campo de búsqueda en /buscar`);
  check((await page.locator('header.top .search:visible, header.top .mobile-only[aria-label="Buscar"]:visible').count()) === 0, `${name}: la barra de arriba no se duplica`);
  check((await page.locator('.page input[type=search]').inputValue()) === 'iruma', `${name}: el campo muestra la consulta`);
  // Second search from the same (only) bar.
  await page.locator('.page input[type=search]').fill('frieren');
  await page.keyboard.press('Enter');
  await page.waitForURL(/q=frieren/);
  check(true, `${name}: segunda búsqueda cambia la URL`);
  // Ctrl+K on /buscar focuses the field instead of opening another bar.
  if (name === 'escritorio') {
    await page.locator('body').click({ position: { x: 5, y: 400 } });
    await page.keyboard.press('Control+k');
    check((await page.locator('.palette').count()) === 0, `${name}: Ctrl+K en /buscar no abre otra barra`);
    check(await page.locator('.page input[type=search]').evaluate((e) => e === document.activeElement), `${name}: Ctrl+K enfoca el campo`);
  }
  // Back goes to the previous query and searches it again.
  await page.goto(B + '/buscar?q=iruma');
  await page.waitForTimeout(300);
  check((await page.locator('.page input[type=search]').inputValue()) === 'iruma', `${name}: entrar por ?q= busca esa consulta`);
  await ctx.close();
}
await browser.close();
if (fails.length) { console.log(`\n${fails.length} fallos`); process.exit(1); }
console.log('\nTodo bien');
