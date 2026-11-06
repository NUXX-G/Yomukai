<script>
  import { onMount } from 'svelte';
  import { route, define, startRouter } from './lib/router.svelte.js';
  import { session, toast, sources, loadSession, resetUserState, updates } from './lib/store.svelte.js';
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
  import InstallGuide from './components/InstallGuide.svelte';
  import CommandPalette from './components/CommandPalette.svelte';
  import { exitFullscreen } from './lib/fullscreen.svelte.js';

  // Pages users rarely open are loaded only when they do (smaller first download).
  const Explore = () => import('./pages/Explore.svelte');
  const Calendar = () => import('./pages/Calendar.svelte');

  define('home', '/');
  define('anime', '/anime');
  define('manga', '/manga');
  define('novels', '/novelas');
  define('calendar', '/calendario');
  define('search', '/buscar');
  define('library', '/biblioteca');
  define('settings', '/ajustes');
  define('source', '/fuente/:id');
  define('details', '/obra/:source/:id');
  define('read', '/leer/:source/:id/:chapter');
  define('watch', '/ver/:source/:id/:chapter');
  startRouter();

  /** The site's sections: rail on computers, bottom bar on phones (the first five). */
  const sections = [
    { name: 'home', href: '/', label: 'Inicio', icon: 'home' },
    { name: 'anime', href: '/anime', label: 'Anime', icon: 'tv' },
    { name: 'manga', href: '/manga', label: 'Manga y manhwa', short: 'Manga', icon: 'book' },
    { name: 'novels', href: '/novelas', label: 'Novelas', icon: 'novel' },
    { name: 'library', href: '/biblioteca', label: 'Biblioteca', icon: 'library' },
    { name: 'calendar', href: '/calendario', label: 'Calendario', icon: 'calendar' }
  ];
  const mobileTabs = sections.slice(0, 5);

  /** Readers and the player take the whole screen. */
  const immersive = $derived(route.name === 'read' || route.name === 'watch');
  $effect(() => { if (!immersive) exitFullscreen(); });

  let palette = $state(false);
  /** The top bar is see-through over the hero and solid once the page scrolls. */
  let scrolled = $state(false);
  let menu = $state(false);
  let sourcesOpen = $state(false);
  /** Macs (and iPads with a keyboard) say ⌘K; everything else Ctrl K. */
  const searchKey = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent) ? "⌘K" : "Ctrl K";
  /** Ctrl+K / ⌘K or "/" open the search. */
  function keys(e) {
    const typing = e.target.closest?.('input, textarea, select, [contenteditable]');
    if ((e.key === 'k' || e.key === 'K') && (e.ctrlKey || e.metaKey)) { e.preventDefault(); palette = true; return; }
    if (e.key === '/' && !typing && !e.ctrlKey && !e.metaKey && !e.altKey) { e.preventDefault(); palette = true; }
  }

  const newCount = $derived(Object.values(updates.byKey || {}).filter((u) => u?.unread > 0).length);
  const initial = $derived((session.user?.guest ? 'I' : session.user?.name || '?').slice(0, 1).toUpperCase());

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
  const lazyPage = $derived(route.name === 'anime' || route.name === 'manga' || route.name === 'novels' ? Explore : route.name === 'calendar' ? Calendar : null);

  async function logout() {
    menu = false;
    await api.logout().catch(() => {});
    resetUserState();
  }

  onMount(() => {
    setUnauthorizedHandler(() => resetUserState());
    setSuspendedHandler((message) => { session.suspended = message; });
    loadSession();
  });
</script>

<svelte:window onkeydown={keys} onscroll={() => (scrolled = window.scrollY > 24)} />

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
  <div class="shell" class:immersive class:own-bar={route.name === "details"}>
    {#if !immersive}
      <aside class="rail" aria-label="Secciones">
        <a href="/" class="logo" aria-label="Yomukai, inicio"><span class="mark">読</span><span class="word">Yomukai</span></a>
        <nav>
          {#each sections as s (s.name)}
            <a href={s.href} class="nav-item" class:on={route.name === s.name} aria-current={route.name === s.name ? 'page' : undefined}>
              <Icon name={s.icon} size={22} /><span>{s.label}</span>
            </a>
          {/each}
        </nav>
        <div class="rail-foot">
          <button class="nav-item" onclick={() => (sourcesOpen = !sourcesOpen)} aria-expanded={sourcesOpen}>
            <Icon name="grid" size={22} /><span>Explorar por fuente</span>
          </button>
          {#if sourcesOpen}
            <div class="source-list">
              {#each sources.list.filter((x) => x.enabled && !x.degraded) as src (src.id)}
                <a href={`/fuente/${encodeURIComponent(src.id)}`} onclick={() => (sourcesOpen = false)}>{src.name}</a>
              {/each}
            </div>
          {/if}
          <a href="/ajustes" class="nav-item" class:on={route.name === 'settings'}><Icon name="settings" size={22} /><span>Ajustes</span></a>
        </div>
      </aside>

      <header class="top" class:solid={scrolled}>
        <a href="/" class="top-logo" aria-label="Yomukai, inicio">Yomukai</a>
        <button class="search" onclick={() => (palette = true)} aria-label="Buscar ({searchKey})">
          <Icon name="search" size={20} /><span>Buscar anime, manga, novelas...</span><kbd>{searchKey}</kbd>
        </button>
        <span class="grow"></span>
        <button class="icon-btn mobile-only" onclick={() => (palette = true)} aria-label="Buscar"><Icon name="search" /></button>
        <a class="icon-btn mobile-only" href="/calendario" aria-label="Calendario"><Icon name="calendar" /></a>
        <a class="icon-btn bell" href="/biblioteca" aria-label={newCount ? `${newCount} obras con novedades` : 'Novedades'}>
          <Icon name="bell" />{#if newCount}<span class="count">{newCount > 9 ? '9+' : newCount}</span>{/if}
        </a>
        <div class="account">
          <button class="avatar" onclick={() => (menu = !menu)} aria-haspopup="menu" aria-expanded={menu} aria-label="Tu cuenta">{initial}</button>
          {#if menu}
            <div class="menu" role="menu">
              <div class="who">{session.user.guest ? 'Invitado' : session.user.name}{#if session.user.admin} · admin{/if}</div>
              <a role="menuitem" href="/ajustes" onclick={() => (menu = false)}><Icon name="settings" size={18} /> Ajustes</a>
              <a role="menuitem" href="/calendario" onclick={() => (menu = false)}><Icon name="calendar" size={18} /> Calendario</a>
              <button role="menuitem" onclick={logout}><Icon name="logout" size={18} /> Salir</button>
            </div>
          {/if}
        </div>
      </header>
    {/if}

    <main id="main">
      {#if route.name === 'read' && !sources.loaded}
        <div class="center" style="min-height:100vh"><div class="spinner"></div></div>
      {:else if lazyPage}
        {#key route.path}
          {#await lazyPage() then m}<m.default />{/await}
        {/key}
      {:else}
        {#key route.path}
          <div class="page-enter"><Page /></div>
        {/key}
      {/if}
    </main>

    {#if !immersive}
      <nav class="tabs" aria-label="Secciones">
        {#each mobileTabs as s (s.name)}
          <a href={s.href} class="tab" class:on={route.name === s.name} aria-current={route.name === s.name ? 'page' : undefined}>
            <span class="pill-icon"><Icon name={s.icon} /></span><span>{s.short || s.label}</span>
          </a>
        {/each}
      </nav>
    {/if}
  </div>
{/if}

{#if session.user}<LocateSheet />{/if}
{#if palette}<CommandPalette onclose={() => (palette = false)} />{/if}
<InstallGuide />
{#if toast.text}<div class="toast" role="status">{toast.text}</div>{/if}

<style>
  .suspended { min-height: 100vh; padding: 24px; text-align: center; gap: 8px; max-width: 520px; margin: 0 auto; }
  .shell { --page-pad: 16px; min-height: 100vh; }
  .rail { display: none; }
  .top {
    position: sticky; top: 0; z-index: 40; display: flex; align-items: center; gap: 6px; height: calc(var(--layout-top-bar) + var(--safe-top));
    padding: var(--safe-top) 8px 0 var(--page-pad);
    background: linear-gradient(to bottom, color-mix(in srgb, var(--c-canvas) 92%, transparent), color-mix(in srgb, var(--c-canvas) 55%, transparent));
    backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
  }
  .top.solid { background: color-mix(in srgb, var(--c-canvas) 94%, transparent); border-bottom: 1px solid var(--c-border); }
  .top-logo { font-weight: 850; font-size: 1.3rem; letter-spacing: -0.02em; color: var(--c-accent); }
  .grow { flex: 1; }
  .search { display: none; }
  .bell { position: relative; }
  .count { position: absolute; top: 6px; right: 4px; min-width: 17px; height: 17px; padding: 0 4px; border-radius: var(--r-pill); background: var(--c-danger); color: white; font-size: 0.65rem; font-weight: 800; display: grid; place-items: center; }
  .account { position: relative; }
  .avatar { width: 34px; height: 34px; margin: 0 6px; border-radius: 50%; background: var(--c-accent-soft); color: var(--c-on-accent-soft); font-weight: 800; display: grid; place-items: center; }
  .menu { position: absolute; right: 4px; top: 44px; min-width: 200px; padding: 6px; border-radius: var(--r-lg); background: var(--c-surface-raised); border: 1px solid var(--c-border); box-shadow: var(--shadow-panel); display: flex; flex-direction: column; }
  .menu .who { padding: 8px 10px; color: var(--c-text-dim); font-size: var(--fs-small); border-bottom: 1px solid var(--c-border); margin-bottom: 4px; }
  .menu a, .menu button { display: flex; align-items: center; gap: 10px; padding: 10px; border-radius: var(--r-md); text-align: left; font-size: 0.92rem; }
  .menu a:hover, .menu button:hover { background: var(--c-surface-high); }
  main { min-height: 60vh; overflow-x: clip; }
  /* Phones: the work page brings its own bar (back, title, options); two bars would eat the banner. */
  @media (max-width: 1023px) { .shell.own-bar .top { display: none; } }
  .page-enter { animation: enter var(--motion-base) var(--motion-easing); }
  @keyframes enter { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }

  .tabs {
    position: fixed; left: 0; right: 0; bottom: 0; z-index: 40; display: flex; height: calc(var(--layout-bottom-bar) + var(--safe-bottom));
    padding-bottom: var(--safe-bottom); background: color-mix(in srgb, var(--c-surface) 94%, transparent);
    backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px); border-top: 1px solid var(--c-border);
  }
  .tab { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px; font-size: 0.7rem; color: var(--c-text-dim); }
  .pill-icon { width: 52px; height: 28px; border-radius: var(--r-pill); display: grid; place-items: center; transition: background var(--motion-base); }
  .tab.on { color: var(--c-text); font-weight: 650; }
  .tab.on .pill-icon { background: var(--c-accent-soft); color: var(--c-on-accent-soft); }
  .shell:not(.immersive) main { padding-bottom: calc(var(--layout-bottom-bar) + var(--safe-bottom)); }

  @media (min-width: 1024px) {
    .shell { --page-pad: 40px; display: grid; grid-template-columns: var(--layout-rail) 1fr; grid-template-rows: auto 1fr; }
    .shell.immersive { display: block; }
    .rail {
      display: flex; flex-direction: column; grid-row: 1 / span 2; position: sticky; top: 0; height: 100vh; padding: 18px 12px;
      background: var(--c-canvas); border-right: 1px solid var(--c-border); overflow-y: auto;
    }
    .logo { display: flex; align-items: center; gap: 10px; padding: 6px 10px 22px; }
    .mark { width: 34px; height: 34px; border-radius: 10px; background: var(--c-accent); color: var(--c-on-accent); display: grid; place-items: center; font-weight: 900; }
    .word { font-weight: 850; font-size: 1.25rem; letter-spacing: -0.02em; }
    nav { display: flex; flex-direction: column; gap: 2px; }
    .nav-item { display: flex; align-items: center; gap: 14px; height: 44px; padding: 0 12px; border-radius: var(--r-md); color: var(--c-text-dim); font-weight: 600; font-size: 0.93rem; width: 100%; text-align: left; transition: background var(--motion-fast), color var(--motion-fast); }
    .nav-item:hover { background: var(--c-surface-high); color: var(--c-text); }
    .nav-item.on { background: var(--c-surface-high); color: var(--c-text); box-shadow: inset 3px 0 0 var(--c-accent); }
    .rail-foot { margin-top: auto; display: flex; flex-direction: column; gap: 2px; padding-top: 16px; }
    .source-list { display: flex; flex-direction: column; padding: 4px 0 8px 48px; max-height: 40vh; overflow-y: auto; }
    .source-list a { padding: 6px 0; color: var(--c-text-dim); font-size: var(--fs-small); }
    .source-list a:hover { color: var(--c-text); }
    .top { grid-column: 2; padding-left: var(--page-pad); padding-right: 24px; gap: 10px; }
    .top-logo, .mobile-only { display: none; }
    .search {
      display: flex; align-items: center; gap: 10px; width: min(520px, 42vw); height: 42px; padding: 0 10px 0 14px; border-radius: var(--r-pill);
      background: var(--c-surface-high); color: var(--c-text-dim); border: 1px solid var(--c-border); font-size: 0.92rem;
    }
    .search span { flex: 1; text-align: left; }
    .search:hover { border-color: var(--c-border-strong); }
    kbd { font: inherit; font-size: 0.72rem; padding: 2px 7px; border-radius: var(--r-sm); border: 1px solid var(--c-border-strong); color: var(--c-text-faint); }
    main { grid-column: 2; min-width: 0; }
    .tabs { display: none; }
    .shell:not(.immersive) main { padding-bottom: 48px; }
  }
  @media (min-width: 1024px) and (max-width: 1279px) {
    .shell { grid-template-columns: var(--layout-rail-collapsed) 1fr; }
    .rail .word, .rail .nav-item span, .source-list { display: none; }
    .nav-item { justify-content: center; padding: 0; }
  }
  @media (prefers-reduced-motion: reduce) { .page-enter { animation: none; } }
</style>
