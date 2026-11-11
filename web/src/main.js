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
async function waitForCovers(maxMs = 4000) {
  if (location.pathname !== '/') return;
  const start = performance.now();
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  while (performance.now() - start < maxMs) {
    const onScreen = [...document.querySelectorAll('#app img')]
      .filter((img) => img.getBoundingClientRect().top < window.innerHeight)
      .slice(0, 16);
    if (onScreen.length && onScreen.every((img) => img.complete)) return;
    if (!onScreen.length && performance.now() - start > 1500) return;
    await sleep(100);
  }
}
// Never more than 8 s of loading screen, whatever happens: what is ready shows and the rest fills in afterwards.
setTimeout(hideSplash, 8000);

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
