<script>
  import { onMount } from 'svelte';
  import { route, define, startRouter, navigate } from './lib/router.svelte.js';
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
  /** Readers and the player take the whole screen; every other page gets the computer's top bar. */
  const immersive = $derived(route.name === 'read' || route.name === 'watch');
  let headerQuery = $state('');
  function headerSearch(e) {
    e.preventDefault();
    const q = headerQuery.trim();
    if (!q) return;
    headerQuery = '';
    e.currentTarget.querySelector('input')?.blur();
    navigate('/buscar?q=' + encodeURIComponent(q));
  }
  /** "/" jumps to the search box, as on most sites. */
  function slash(e) {
    if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey || e.target.closest?.('input, textarea, select, [contenteditable]')) return;
    const input = document.querySelector('.find input');
    if (input?.offsetParent) { e.preventDefault(); input.focus(); }
  }

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

<svelte:window onkeydown={slash} />

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
  <div class="shell" class:nav={showNav} class:desk={!immersive}>
    {#if !immersive}
      <header class="topnav">
        <div class="topnav-inner">
          <a href="/" class="brand">Yomukai</a>
          <nav class="links" aria-label="Secciones">
            {#each tabs.filter((t) => t.name !== 'search') as tab}
              <a href={tab.href} class="link" class:on={route.name === tab.name} aria-current={route.name === tab.name ? 'page' : undefined}>{tab.label}</a>
            {/each}
          </nav>
          <span class="spacer"></span>
          {#if route.name !== 'search'}
            <form class="find" role="search" onsubmit={headerSearch}>
              <Icon name="search" size={20} />
              <input type="search" placeholder="Buscar manga, manhwa, novelas, anime..." bind:value={headerQuery} aria-label="Buscar en todas tus fuentes" />
              <kbd>/</kbd>
            </form>
          {/if}
          <span class="user" title={session.user.name}>{session.user.guest ? 'Invitado' : session.user.name}</span>
        </div>
      </header>
    {/if}
    {#if showNav}
      <nav class="tabs" aria-label="Secciones">
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
  .topnav { display: none; }
  /* Computers get a real website header (sections, search, account) instead of the phone's tab bar. */
  @media (min-width: 1024px) {
    .tabs { display: none; }
    .topnav { display: block; position: sticky; top: 0; z-index: 40; height: var(--top-h); background: var(--surface-low); border-bottom: 1px solid var(--outline-variant); }
    .topnav-inner { height: 100%; max-width: var(--page-max); margin: 0 auto; padding: 0 32px; display: flex; align-items: center; gap: 28px; }
    .topnav .brand { font-size: 1.45rem; }
    .links { display: flex; gap: 4px; }
    .link { padding: 8px 14px; border-radius: 10px; color: var(--text-dim); font-weight: 600; font-size: 0.95rem; transition: color 0.15s, background 0.15s; }
    .link:hover { color: var(--text); background: var(--surface-high); }
    .link.on { color: var(--text); background: var(--secondary-container); }
    .spacer { flex: 1; }
    .find { display: flex; align-items: center; gap: 10px; width: min(440px, 34vw); height: 42px; padding: 0 10px 0 14px; border-radius: 12px; background: var(--surface-high); border: 1px solid transparent; color: var(--text-dim); transition: border-color 0.15s, background 0.15s; }
    .find:focus-within { border-color: var(--primary); background: var(--surface); }
    .find input { flex: 1; min-width: 0; background: none; border: 0; outline: none; font-size: 0.95rem; }
    kbd { font: inherit; font-size: 0.75rem; padding: 1px 7px; border-radius: 6px; border: 1px solid var(--outline); color: var(--text-dim); }
    .find:focus-within kbd { display: none; }
    .user { max-width: 160px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--text-dim); font-size: 0.9rem; }
    .shell.nav :global(.page) { padding-bottom: 48px; }
  }
</style>
