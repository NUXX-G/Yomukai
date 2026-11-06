// Inter (OFL-1.1), latin and latin-ext only, served from this site like everything else.
import '@fontsource-variable/inter/wght.css';
import './tokens.css';
import './app.css';
import { mount } from 'svelte';
import App from './App.svelte';
import { adaptToIOS } from './lib/platform.js';

adaptToIOS();

mount(App, { target: document.getElementById('app') });

// Installable and quick to reopen; never in development, where it would cache stale code.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}));
}
