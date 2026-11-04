<script>
  import { onMount } from 'svelte';
  import { route, define, startRouter } from './lib/router.svelte.js';
  import { session, toast, sources, loadSession, resetUserState } from './lib/store.svelte.js';
  import { api, setUnauthorizedHandler, setSuspendedHandler } from './lib/api.js';
  import Icon from './components/Icon.svelte';
  import Login from './pages/Login.svelte';
  import Home from './pages/Home.svelte';
  import Search from './pages/Search.svelte';
  import Library from './pages/Library.svelte';
  import Settings from './pages/Settings.svelte';
  import Source from './pages/Source.svelte';
  import Details from './pages/Details.svelte';
  import MangaReader from './pages/MangaReader.svelte';
  import NovelReader from './pages/NovelReader.svelte';
  import Player from './pages/Player.svelte';
  import LocateSheet from './components/LocateSheet.svelte';

  define('home', '/');
  define('search', '/buscar');
  define('library', '/biblioteca');
  define('settings', '/ajustes');
  define('source', '/fuente/:id');
  define('details', '/obra/:source/:id');
  define('read', '/leer/:source/:id/:chapter');
  define('watch', '/ver/:source/:id/:chapter');
  startRouter();

  const tabs = [
    { name: 'home', href: '/', label: 'Inicio', icon: 'home' },
    { name: 'search', href: '/buscar', label: 'Buscar', icon: 'search' },
    { name: 'library', href: '/biblioteca', label: 'Biblioteca', icon: 'library' },
    { name: 'settings', href: '/ajustes', label: 'Ajustes', icon: 'settings' }
  ];

  const showNav = $derived(tabs.some((t) => t.name === route.name));

  /** The page for the current address; chapters of novel sources open the text reader. */
  const Page = $derived.by(() => {
    switch (route.name) {
      case 'search': return Search;
      case 'library': return Library;
      case 'settings': return Settings;
      case 'source': return Source;
      case 'details': return Details;
      case 'watch': return Player;
      case 'read': return sources.list.find((s) => s.id === route.params.source)?.kind === 'novel' ? NovelReader : MangaReader;
      default: return Home;
    }
  });

  onMount(() => {
    setUnauthorizedHandler(() => resetUserState());
    setSuspendedHandler((message) => { session.suspended = message; });
    loadSession();
  });
</script>

{#if !session.ready}
  <div class="center" style="min-height:100vh">
    <div class="spinner"></div>
    {#if session.offline}<p class="muted small">Conectando con el servidor...</p>{/if}
  </div>
{:else if session.suspended}
  <div class="center suspended">
    <h2>Cuenta suspendida</h2>
    <p class="muted">{session.suspended}</p>
    <button class="btn outline" onclick={async () => { await api.logout().catch(() => {}); session.suspended = ''; resetUserState(); }}>Salir</button>
  </div>
{:else if !session.user}
  <Login />
{:else}
  <div class="shell" class:nav={showNav}>
    {#if showNav}
      <nav class="tabs" aria-label="Secciones">
        <span class="brand side">Yomukai</span>
        {#each tabs as tab}
          <a href={tab.href} class="tab" class:on={route.name === tab.name} aria-current={route.name === tab.name ? 'page' : undefined}>
            <span class="pill-icon"><Icon name={tab.icon} /></span>
            <span>{tab.label}</span>
          </a>
        {/each}
      </nav>
    {/if}
    <main>
      {#if route.name === 'read' && !sources.loaded}
        <!-- Which reader a chapter needs depends on its source: wait for the list when a chapter link is opened directly. -->
        <div class="center" style="min-height:100vh"><div class="spinner"></div></div>
      {:else}
        {#key route.path}
          <Page />
        {/key}
      {/if}
    </main>
  </div>
{/if}

{#if session.user}<LocateSheet />{/if}
{#if toast.text}<div class="toast" role="status">{toast.text}</div>{/if}

<style>
  .suspended { min-height: 100vh; padding: 24px; text-align: center; gap: 8px; max-width: 520px; margin: 0 auto; }
  .tabs { position: fixed; left: 0; right: 0; bottom: 0; z-index: 40; display: flex; height: calc(var(--nav-h) + var(--safe-bottom)); padding-bottom: var(--safe-bottom); background: color-mix(in srgb, var(--surface) 92%, transparent); backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px); border-top: 1px solid var(--outline-variant); }
  .tab { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px; font-size: 0.74rem; color: var(--text-dim); }
  .pill-icon { width: 56px; height: 30px; border-radius: 15px; display: grid; place-items: center; transition: background 0.2s; }
  .tab.on { color: var(--text); font-weight: 600; }
  .tab.on .pill-icon { background: var(--secondary-container); color: var(--on-secondary-container); }
  .brand.side { display: none; }
  @media (min-width: 900px) {
    .tabs { top: 0; right: auto; width: 96px; height: auto; flex-direction: column; justify-content: flex-start; gap: 10px; padding: 18px 0; border-top: 0; border-right: 1px solid var(--outline-variant); }
    .tab { flex: none; height: 64px; }
    .brand.side { display: block; font-size: 0.95rem; text-align: center; margin-bottom: 18px; }
    .shell.nav main { margin-left: 96px; }
    .shell.nav :global(.page) { padding-bottom: 32px; }
  }
</style>
