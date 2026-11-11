// Inter (OFL-1.1), latin and latin-ext only, served from this site like everything else.
import '@fontsource-variable/inter/wght.css';
// Dela Gothic One (OFL-1.1), the display face of «Tinta y viñeta»: titles, big numbers, sound effects. Latin only.
import '@fontsource/dela-gothic-one/latin-400.css';
import '@fontsource/dela-gothic-one/latin-ext-400.css';
import './tokens.css';
import './app.css';
import { bootTheme } from './lib/theme.svelte.js';

bootTheme();
import { mount } from 'svelte';
import App from './App.svelte';
import { adaptToIOS } from './lib/platform.js';
import { startI18n } from './lib/i18n.svelte.js';
import { CURIOSITIES } from './lib/curiosities.js';

adaptToIOS();

// English users get their dictionary before the first text is drawn.
startI18n().finally(() => {
  try {
    mount(App, { target: document.getElementById('app') });
  } finally {
    waitForCovers().finally(hideSplash);
  }
});

/**
 * V1: the loading screen is there so the covers are ready when it goes: on Home it waits for the covers on screen
 * (at most [maxMs]); a page without covers (signing in, an empty library) lets it go at once.
 */
/**
 * P5: the first visit (nothing cached yet) waits up to 15 s for Home's covers, with a curiosity to read that changes
 * every few seconds; later visits wait at most 4 s as before (the covers are in the browser's cache by then).
 */
function firstVisit() {
  try {
    if (localStorage.getItem('yk-seen')) return false;
    localStorage.setItem('yk-seen', '1');
  } catch {
    return false;
  }
  return true;
}

function showCuriosities() {
  const tip = document.getElementById('splash-tip');
  if (!tip) return () => {};
  const list = [...CURIOSITIES].sort(() => Math.random() - 0.5);
  let i = 0;
  const show = () => { tip.innerHTML = ''; const b = document.createElement('b'); b.textContent = '¿Sabías que…?'; tip.append(b, list[i++ % list.length]); };
  show();
  const timer = setInterval(show, 5000);
  return () => clearInterval(timer);
}

async function waitForCovers(maxMs = 4000) {
  if (location.pathname !== '/') return;
  if (firstVisit()) {
    maxMs = 15000;
    const stop = showCuriosities();
    try { await waitForCoversUntil(maxMs, 6000); } finally { stop(); }
    return;
  }
  await waitForCoversUntil(maxMs, 0);
}

/** Until the covers on screen are loaded (at least [minMs], so a curiosity can be read), at most [maxMs]. */
async function waitForCoversUntil(maxMs, minMs) {
  const start = performance.now();
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  while (performance.now() - start < maxMs) {
    const onScreen = [...document.querySelectorAll('#app img')]
      .filter((img) => img.getBoundingClientRect().top < window.innerHeight)
      .slice(0, 16);
    if (onScreen.length && onScreen.every((img) => img.complete) && performance.now() - start >= minMs) return;
    if (!onScreen.length && performance.now() - start > 1500) return;
    await sleep(100);
  }
}
// Never more than 16 s of loading screen, whatever happens (15 s only the first visit): the rest fills in afterwards.
setTimeout(hideSplash, 16000);

/** Fades the loading screen of index.html out once the app has drawn its first frame. */
function hideSplash() {
  const splash = document.getElementById('splash');
  if (!splash || splash.classList.contains('out')) return;
  requestAnimationFrame(() => {
    splash.classList.add('out');
    setTimeout(() => splash.remove(), 300);
  });
}

// Installable and quick to reopen; never in development, where it would cache stale code.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}));
}
