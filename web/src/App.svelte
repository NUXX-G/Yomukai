<script>
  import { t } from './lib/i18n.svelte.js';
  import { onMount } from 'svelte';
  import { route, define, startRouter } from './lib/router.svelte.js';
  import { session, toast, sources, loadSession, resetUserState, updates, features, notify, settings } from './lib/store.svelte.js';
  import { api, setUnauthorizedHandler, setSuspendedHandler } from './lib/api.js';
  import Icon from './components/Icon.svelte';
  import Login from './pages/Login.svelte';
  import Home from './pages/Home.svelte';
  import Search from './pages/Search.svelte';
  import Library from './pages/Library.svelte';
  import Settings from './pages/Settings.svelte';
  import Source from './pages/Source.svelte';
  import Details from './pages/Details.svelte';
  import Work from './pages/Work.svelte';
  import Guide from './pages/Guide.svelte';
  import MangaReader from './pages/MangaReader.svelte';
  import NovelReader from './pages/NovelReader.svelte';
  import Player from './pages/Player.svelte';
  import LocateSheet from './components/LocateSheet.svelte';
  import InstallGuide from './components/InstallGuide.svelte';
  import CommandPalette from './components/CommandPalette.svelte';
  import { exitFullscreen } from './lib/fullscreen.svelte.js';
  import { startLive, stopLive, on as onLive, dm, refreshDmUnread } from './lib/live.svelte.js';

  // Pages users rarely open are loaded only when they do (smaller first download).
  const Explore = () => import('./pages/Explore.svelte');
  const Calendar = () => import('./pages/Calendar.svelte');
  const Room = () => import('./pages/Room.svelte');
  const Profile = () => import('./pages/Profile.svelte');
  const Friends = () => import('./pages/Friends.svelte');
  const ForumPage = () => import('./pages/Forum.svelte');
  const Review = () => import('./pages/Review.svelte');
  const Messages = () => import('./pages/Messages.svelte');
  const Rooms = () => import('./pages/Rooms.svelte');

  define('home', '/');
  define('anime', '/anime');
  define('manga', '/manga');
  define('novels', '/novelas');
  define('manhwa', '/manhwa');
  define('comics', '/comics');
  define('calendar', '/calendario');
  define('search', '/buscar');
  define('library', '/biblioteca');
  define('settings', '/ajustes');
  define('source', '/fuente/:id');
  define('details', '/obra/:source/:id');
  define('work', '/obras/:key');
  define('guide', '/guia');
  define('read', '/leer/:source/:id/:chapter');
  define('watch', '/ver/:source/:id/:chapter');
  define('room', '/sala/:code');
  define('profile', '/u/:name');
  define('friends', '/amigos');
  define('forum', '/foro');
  define('review', '/repaso');
  define('forumThread', '/foro/:id');
  define('messages', '/mensajes');
  define('conversation', '/mensajes/:name');
  define('rooms', '/salas');
  startRouter();

  /** The site's sections: rail on computers, bottom bar on phones (the first five). */
  const sections = [
    { name: 'home', href: '/', label: t('Inicio'), icon: 'home' },
    { name: 'anime', href: '/anime', label: t('Anime'), icon: 'tv' },
    { name: 'manga', href: '/manga', label: t('Manga'), short: 'Manga', icon: 'book' },
    { name: 'manhwa', href: '/manhwa', label: t('Manhwa y manhua'), short: 'Manhwa', icon: 'book' },
    { name: 'comics', href: '/comics', label: t('Cómics'), icon: 'book' },
    { name: 'novels', href: '/novelas', label: t('Novelas'), icon: 'novel' },
    { name: 'library', href: '/biblioteca', label: t('Biblioteca'), icon: 'library' },
    { name: 'calendar', href: '/calendario', label: t('Calendario'), icon: 'calendar' }
  ];
  // Phones: home, anime, manga, the library and the calendar; the other kinds are in the tabs of each section.
  const mobileTabs = sections.filter((s) => ['home', 'anime', 'manga', 'library', 'calendar'].includes(s.name));

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

  // ---------- Notifications: the bell's list, live over SSE while the page is open ----------
  let notes = $state({ items: [], unread: 0 });
  let bellOpen = $state(false);
  const bellCount = $derived(notes.unread + newCount);

  async function loadNotes() {
    try { notes = await api.notifications(); } catch { /* offline: try later */ }
  }
  // Notifications and direct messages arrive over one live stream (lib/live.svelte.js).
  onLive('notification', (n) => {
    notes = { items: [n, ...notes.items].slice(0, 50), unread: notes.unread + 1 };
    // A direct message is already heard where it is read; elsewhere a short notice.
    if (n.kind !== 'dm' || route.name !== 'conversation') notify(n.text);
  });
  $effect(() => {
    if (session.user && !session.user.guest) { loadNotes(); startLive(session.user); refreshDmUnread(); }
    return () => stopLive();
  });
  const dmsOn = $derived(features.flags.dms !== false && session.user && !session.user.guest);
  async function toggleBell() {
    bellOpen = !bellOpen;
    if (bellOpen && notes.unread) {
      await api.notificationsRead().catch(() => {});
      notes = { items: notes.items.map((n) => ({ ...n, read: true })), unread: 0 };
    }
  }
  const ago = (at) => {
    const m = Math.round((Date.now() - at) / 60_000);
    return m < 60 ? (m <= 1 ? 'ahora' : m + ' min') : m < 1440 ? Math.round(m / 60) + ' h' : Math.round(m / 1440) + ' d';
  };
  const initial = $derived((session.user?.guest ? 'I' : session.user?.name || '?').slice(0, 1).toUpperCase());

  const Page = $derived.by(() => {
    switch (route.name) {
      case 'search': return Search;
      case 'library': return Library;
      case 'settings': return Settings;
      case 'source': return Source;
      case 'details': return Details;
      case 'work': return Work;
      case 'guide': return Guide;
      case 'watch': return Player;
      case 'read': return sources.list.find((s) => s.id === route.params.source)?.kind === 'novel' ? NovelReader : MangaReader;
      default: return Home;
    }
  });
  const lazyPage = $derived(['anime', 'manga', 'manhwa', 'comics', 'novels'].includes(route.name) ? Explore : route.name === 'calendar' ? Calendar : route.name === 'room' ? Room : route.name === 'profile' ? Profile : route.name === 'friends' ? Friends : route.name === 'forum' || route.name === 'forumThread' ? ForumPage : route.name === 'review' ? Review : route.name === 'messages' || route.name === 'conversation' ? Messages : route.name === 'rooms' ? Rooms : null);

  async function logout() {
    menu = false;
    await api.logout().catch(() => {});
    resetUserState();
  }

  onMount(() => {
    setUnauthorizedHandler(() => resetUserState());
    setSuspendedHandler((message) => { session.suspended = message; });
    loadSession();
    // New achievements, shown once as a little celebration.
    const poll = async () => {
      if (!session.user || session.user.guest || document.hidden) return;
      try { const fresh = await api.newAchievements(); if (fresh?.length) trophies = [...trophies, ...fresh]; } catch { /* offline: next time */ }
    };
    const first = setTimeout(poll, 8_000);
    const t = setInterval(poll, 60_000);
    return () => { clearTimeout(first); clearInterval(t); };
  });

  let trophies = $state([]);
  const trophy = $derived(trophies[0]);
  $effect(() => {
    if (!trophy) return;
    const t = setTimeout(() => (trophies = trophies.slice(1)), 5_000);
    return () => clearTimeout(t);
  });
</script>

<svelte:window onkeydown={keys} onscroll={() => (scrolled = window.scrollY > 24)} />

{#if !session.ready}
  <div class="center" style="min-height:100vh">
    <div class="spinner"></div>
    {#if session.offline}<p class="muted small">{t('Conectando con el servidor...')}</p>{/if}
  </div>
{:else if session.suspended}
  <div class="center suspended">
    <h2>{t('Cuenta suspendida')}</h2>
    <p class="muted">{session.suspended}</p>
    <button class="btn outline" onclick={async () => { await api.logout().catch(() => {}); session.suspended = ''; resetUserState(); }}>{t('Salir')}</button>
  </div>
{:else if !session.user}
  <Login />
{:else}
  <div class="shell" class:immersive class:own-bar={route.name === "details"}>
    {#if !immersive}
      <aside class="rail" aria-label={t('Secciones')}>
        <a href="/" class="logo" aria-label={t('Yomukai, inicio')}><span class="mark">読</span><span class="word">{t('Yomukai')}</span></a>
        <nav>
          {#each sections as s (s.name)}
            <a href={s.href} class="nav-item" class:on={route.name === s.name} aria-current={route.name === s.name ? 'page' : undefined}>
              <Icon name={s.icon} size={22} /><span>{s.label}</span>
            </a>
          {/each}
        </nav>
        <div class="rail-foot">
          <button class="nav-item" onclick={() => (sourcesOpen = !sourcesOpen)} aria-expanded={sourcesOpen}>
            <Icon name="grid" size={22} /><span>{t('Explorar por fuente')}</span>
          </button>
          {#if sourcesOpen}
            <div class="source-list">
              {#each sources.list.filter((x) => x.enabled && !x.degraded) as src (src.id)}
                <a href={`/fuente/${encodeURIComponent(src.id)}`} onclick={() => (sourcesOpen = false)}>{src.name}</a>
              {/each}
            </div>
          {/if}
          <a href="/ajustes" class="nav-item" class:on={route.name === 'settings'}><Icon name="settings" size={22} /><span>{t('Ajustes')}</span></a>
        </div>
      </aside>

      <header class="top" class:solid={scrolled}>
        <a href="/" class="top-logo" aria-label={t('Yomukai, inicio')}>{t('Yomukai')}</a>
        <button class="search" onclick={() => (palette = true)} aria-label="Buscar ({searchKey})">
          <Icon name="search" size={20} /><span>{t('Buscar anime, manga, novelas...')}</span><kbd>{searchKey}</kbd>
        </button>
        <span class="grow"></span>
        <button class="icon-btn mobile-only" onclick={() => (palette = true)} aria-label={t('Buscar')}><Icon name="search" /></button>
        <a class="icon-btn mobile-only" href="/calendario" aria-label={t('Calendario')}><Icon name="calendar" /></a>
        {#if dmsOn}
          <a class="icon-btn bell" href="/mensajes" aria-label={dm.unread ? t('Mensajes: {0} sin leer', [dm.unread]) : t('Mensajes')} title={t('Mensajes')}>
            <Icon name="chat" />{#if dm.unread}<span class="count">{dm.unread > 9 ? '9+' : dm.unread}</span>{/if}
          </a>
        {/if}
        <div class="bell-wrap">
          <button class="icon-btn bell" onclick={toggleBell} aria-haspopup="true" aria-expanded={bellOpen} aria-label={bellCount ? `${bellCount} avisos` : 'Avisos'}>
            <Icon name="bell" />{#if bellCount}<span class="count">{bellCount > 9 ? '9+' : bellCount}</span>{/if}
          </button>
          {#if bellOpen}
            <div class="menu notes" role="dialog" aria-label={t('Avisos')}>
              {#if newCount}<a class="note lib" href="/biblioteca" onclick={() => (bellOpen = false)}><b>{newCount}</b> {t('{0} de tu biblioteca con capítulos nuevos', [newCount === 1 ? 'obra' : 'obras'])}</a>{/if}
              {#each notes.items as n (n.id)}
                <a class="note" class:unread={!n.read} href={n.link || '/'} onclick={() => (bellOpen = false)}><span>{n.text}</span><span class="when">{ago(n.at)}</span></a>
              {:else}
                {#if !newCount}<p class="empty">{t('Nada nuevo por aquí.')}</p>{/if}
              {/each}
            </div>
          {/if}
        </div>
        <div class="account">
          <button class="avatar" onclick={() => (menu = !menu)} aria-haspopup="menu" aria-expanded={menu} aria-label={t('Tu cuenta')}>{initial}</button>
          {#if menu}
            <div class="menu" role="menu">
              <div class="who">{session.user.guest ? t('Invitado') : session.user.name}{#if session.user.admin} {t('· admin')}{/if}</div>
              {#if !session.user.guest}
                <a role="menuitem" href="/u/{encodeURIComponent(session.user.name)}" onclick={() => (menu = false)}><Icon name="library" size={18} /> {t('Mi perfil')}</a>
                <a role="menuitem" href="/amigos" onclick={() => (menu = false)}><Icon name="explore" size={18} /> {t('Amigos')}</a>
                {#if dmsOn}<a role="menuitem" href="/mensajes" onclick={() => (menu = false)}><Icon name="chat" size={18} /> {t('Mensajes')}</a>{/if}
                {#if features.flags.rooms !== false}<a role="menuitem" href="/salas" onclick={() => (menu = false)}><Icon name="tv" size={18} /> {t('Salas')}</a>{/if}
                {#if settings.learning?.enabled && features.flags.learning !== false}<a role="menuitem" href="/repaso" onclick={() => (menu = false)}><Icon name="book" size={18} /> {t('Repaso')}</a>{/if}
                {#if features.flags.forum !== false}<a role="menuitem" href="/foro" onclick={() => (menu = false)}><Icon name="list" size={18} /> {t('Foro')}</a>{/if}
              {/if}
              <a role="menuitem" href="/guia" onclick={() => (menu = false)}><Icon name="info" size={18} /> {t('Guía rápida')}</a>
              <a role="menuitem" href="/ajustes" onclick={() => (menu = false)}><Icon name="settings" size={18} /> {t('Ajustes')}</a>
              <a role="menuitem" href="/calendario" onclick={() => (menu = false)}><Icon name="calendar" size={18} /> {t('Calendario')}</a>
              <button role="menuitem" onclick={logout}><Icon name="logout" size={18} /> {t('Salir')}</button>
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
      <nav class="tabs" aria-label={t('Secciones')}>
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
{#if trophy}
  {#key trophy.id}
    <button class="trophy {trophy.rarity}" data-sfx={t('¡BAM!')} role="status" onclick={() => (trophies = trophies.slice(1))} aria-label={t('Logro desbloqueado: {0}', [t(trophy.name)])}>
      <span class="t-emoji" aria-hidden="true">{trophy.emoji}</span>
      <span class="t-text"><span class="t-kicker">{t('Logro desbloqueado')}</span><span class="t-name">{t(trophy.name)}</span><span class="t-desc">{t(trophy.description)}</span></span>
    </button>
  {/key}
{/if}

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
  .top.solid { background: color-mix(in srgb, var(--c-canvas) 94%, transparent); border-bottom: var(--nb-border) solid var(--nb-soft-line); }
  .top-logo { font-family: var(--font-display); font-size: 1.3rem; color: var(--c-accent); }
  .grow { flex: 1; }
  .search { display: none; }
  .bell { position: relative; }
  .count { position: absolute; top: 6px; right: 4px; min-width: 17px; height: 17px; padding: 0 4px; border-radius: var(--r-pill); background: var(--c-danger); color: white; font-size: 0.65rem; font-weight: 800; display: grid; place-items: center; }
  .account { position: relative; }
  .avatar { width: 34px; height: 34px; margin: 0 6px; border-radius: 50%; background: var(--c-accent2); color: var(--c-on-accent2); font-weight: 800; display: grid; place-items: center; border: var(--nb-border) solid var(--nb-line); }
  .menu { position: absolute; right: 4px; top: 44px; min-width: 200px; padding: 6px; border-radius: var(--r-sm); background: var(--c-surface-raised); border: var(--nb-border) solid var(--nb-line); box-shadow: var(--nb-shadow) var(--nb-shadow) 0 var(--nb-line); display: flex; flex-direction: column; }
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
    padding-bottom: var(--safe-bottom); background: color-mix(in srgb, var(--c-surface) 96%, transparent);
    backdrop-filter: blur(16px); -webkit-backdrop-filter: blur(16px); border-top: var(--nb-border) solid var(--nb-line);
  }
  .tab { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px; font-size: 0.7rem; color: var(--c-text-dim); }
  .pill-icon { width: 52px; height: 28px; border-radius: var(--r-sm); display: grid; place-items: center; border: var(--nb-border) solid transparent; transition: background var(--motion-base), transform var(--motion-fast) var(--nb-press); }
  .tab.on { color: var(--c-text); font-weight: 700; }
  .tab.on .pill-icon { background: var(--c-accent); color: var(--c-on-accent); border-color: var(--nb-line); box-shadow: 2px 2px 0 var(--nb-line); }
  .tab:active .pill-icon { transform: translate(1px, 1px); }
  .shell:not(.immersive) main { padding-bottom: calc(var(--layout-bottom-bar) + var(--safe-bottom)); }

  @media (min-width: 1024px) {
    .shell { --page-pad: 40px; display: grid; grid-template-columns: var(--layout-rail) 1fr; grid-template-rows: auto 1fr; }
    .shell.immersive { display: block; }
    .rail {
      display: flex; flex-direction: column; grid-row: 1 / span 2; position: sticky; top: 0; height: 100vh; padding: 18px 12px;
      background: var(--c-canvas); border-right: var(--nb-border) solid var(--nb-soft-line); overflow-y: auto;
    }
    .logo { display: flex; align-items: center; gap: 10px; padding: 6px 10px 22px; }
    /* The 読 seal: a stamp with an ink border and a hard shadow. */
    .mark { width: 36px; height: 36px; border-radius: 6px; background: var(--c-accent); color: var(--c-on-accent); display: grid; place-items: center; font-weight: 900; font-size: 1.15rem; border: var(--nb-border) solid var(--nb-line); box-shadow: 3px 3px 0 var(--nb-line); transform: rotate(-4deg); }
    .word { font-family: var(--font-display); font-size: 1.3rem; }
    nav { display: flex; flex-direction: column; gap: 2px; }
    .nav-item { display: flex; align-items: center; gap: 14px; height: 44px; padding: 0 12px; border-radius: var(--r-md); color: var(--c-text-dim); font-weight: 650; font-size: 0.93rem; width: 100%; text-align: left; border: var(--nb-border) solid transparent; transition: background var(--motion-fast), color var(--motion-fast), transform var(--motion-fast) var(--nb-press), box-shadow var(--motion-fast) var(--nb-press); }
    .nav-item:hover { background: var(--c-surface-high); color: var(--c-text); }
    .nav-item.on { background: var(--c-accent); color: var(--c-on-accent); border-color: var(--nb-line); box-shadow: 3px 3px 0 var(--nb-line); transform: translate(-1px, -1px); }
    .rail-foot { margin-top: auto; display: flex; flex-direction: column; gap: 2px; padding-top: 16px; }
    .source-list { display: flex; flex-direction: column; padding: 4px 0 8px 48px; max-height: 40vh; overflow-y: auto; }
    .source-list a { padding: 6px 0; color: var(--c-text-dim); font-size: var(--fs-small); }
    .source-list a:hover { color: var(--c-text); }
    .top { grid-column: 2; padding-left: var(--page-pad); padding-right: 24px; gap: 10px; }
    .top-logo, .mobile-only { display: none; }
    .search {
      display: flex; align-items: center; gap: 10px; width: min(520px, 42vw); height: 42px; padding: 0 10px 0 14px; border-radius: var(--r-md);
      background: var(--c-surface-high); color: var(--c-text-dim); border: var(--nb-border) solid var(--nb-soft-line); font-size: 0.92rem;
      transition: border-color var(--motion-fast), box-shadow var(--motion-fast);
    }
    .search span { flex: 1; text-align: left; }
    .search:hover { border-color: var(--nb-line); box-shadow: 3px 3px 0 var(--nb-line); }
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
  .trophy { position: fixed; z-index: 90; left: 50%; top: calc(var(--safe-top) + 14px); transform: translateX(-50%); display: flex; gap: 14px; align-items: center;
    width: min(440px, calc(100% - 24px)); padding: 14px 18px; border-radius: var(--r-sm); text-align: left; color: var(--c-text);
    background: var(--c-surface-raised); border: var(--nb-border) solid var(--nb-line); box-shadow: 5px 5px 0 var(--c-accent2);
    animation: trophy-in 0.5s cubic-bezier(0.2, 1.4, 0.4, 1); }
  /* The hit of an achievement: a sound effect stamped on the corner, with speed lines behind the emoji. */
  .trophy::before { content: attr(data-sfx); position: absolute; right: 10px; top: -14px; padding: 0 8px; font-family: var(--font-display); font-size: 1.05rem; color: var(--c-on-accent2); background: var(--c-accent2); border: var(--nb-border) solid var(--nb-line); transform: rotate(6deg); animation: trophy-pop 0.6s 0.25s both; }
  .t-emoji { position: relative; }
  .t-emoji::before { content: ''; position: absolute; inset: -10px; z-index: -1; border-radius: 50%; background: repeating-conic-gradient(var(--c-accent2) 0 4deg, transparent 4deg 18deg); opacity: 0.35; }
  .trophy.epic { box-shadow: 5px 5px 0 var(--c-type-novel); } .trophy.legendary { box-shadow: 5px 5px 0 var(--c-accent); }
  .t-emoji { font-size: 2.4rem; animation: trophy-pop 0.7s 0.2s both; }
  .t-text { display: flex; flex-direction: column; min-width: 0; }
  .t-kicker { font-size: var(--fs-tiny); text-transform: uppercase; letter-spacing: 0.08em; color: var(--c-accent); font-weight: 800; }
  .t-name { font-family: var(--font-display); font-size: 1.05rem; }
  .t-desc { font-size: var(--fs-small); color: var(--c-text-dim); }
  @keyframes trophy-in { from { transform: translate(-50%, -140%); opacity: 0; } to { transform: translate(-50%, 0); opacity: 1; } }
  @keyframes trophy-pop { from { transform: scale(0.2) rotate(-30deg); } 70% { transform: scale(1.25) rotate(8deg); } to { transform: none; } }
  @media (prefers-reduced-motion: reduce) { .trophy, .t-emoji, .trophy::before { animation: none; } }
  .bell-wrap { position: relative; }
  .notes { width: min(360px, calc(100vw - 24px)); max-height: 70vh; overflow-y: auto; right: -40px; }
  .note { display: flex !important; justify-content: space-between; gap: 10px; align-items: flex-start; font-size: 0.88rem !important; }
  .note.unread { background: color-mix(in srgb, var(--c-accent) 10%, transparent); }
  .note.lib { color: var(--c-accent); }
  .note .when { color: var(--c-text-faint); font-size: var(--fs-tiny); white-space: nowrap; }
  .empty { margin: 10px; color: var(--c-text-dim); font-size: var(--fs-small); }
</style>
