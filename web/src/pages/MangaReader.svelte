<script>
  import { onMount, onDestroy, tick } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, back, navigate, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { settings, saveSettings, notify } from '../lib/store.svelte.js';
  import { track, markThrough, flush, progressMap } from '../lib/progress.js';
  import { clock } from '../lib/format.js';
  import Icon from '../components/Icon.svelte';
  import Sheet from '../components/Sheet.svelte';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const startId = route.params.chapter;
  const WIDTHS = [['narrow', 'Estrecho'], ['normal', 'Normal'], ['wide', 'Ancho'], ['full', 'Toda la pantalla']];

  let item = $state(null);
  let chapters = $state([]);
  /** Chapters on screen, in order: { chapter, pages: [], error } */
  let loaded = $state([]);
  let current = $state(null);
  let page = $state(0);
  let ui = $state(true);
  let now = $state(clock());
  let error = $state('');
  let pagedIndex = $state(0);
  let indexOpen = $state(false);
  let optionsOpen = $state(false);
  let indexQuery = $state('');
  /** Pages that finished loading: only they count as seen. */
  const ready = $state({});
  let observer;
  let clockTimer;
  let loadingNext = false;
  /** Page to come back to in the first chapter, until it has been shown. */
  let resumePage = 0;

  const mode = $derived(settings.reader.mode);
  const rtl = $derived(settings.reader.direction === 'rtl');
  const width = $derived(settings.reader.width || 'normal');
  const flat = $derived(loaded.flatMap((l) => l.pages.map((src, i) => ({ src, i, chapter: l.chapter, total: l.pages.length }))));
  const currentPages = $derived(current ? loaded.find((l) => l.chapter.id === current.id)?.pages.length || 0 : 0);
  const indexList = $derived.by(() => {
    const q = indexQuery.trim().toLowerCase();
    const all = [...chapters].reverse();
    return q ? all.filter((c) => (c.label + ' ' + (c.name || '')).toLowerCase().includes(q) || String(c.number) === q.replace(/[^\d.]/g, '')) : all;
  });

  async function loadChapter(chapter) {
    const entry = { chapter, pages: [], error: '' };
    loaded = [...loaded, entry];
    try {
      const pages = await api.pages(sourceId, chapter.url);
      loaded = loaded.map((l) => (l.chapter.id === chapter.id ? { ...l, pages } : l));
      // The next pages are fetched ahead, so turning them never waits.
      pages.slice(0, 4).forEach((src) => { const img = new Image(); img.src = src; });
    } catch (e) {
      loaded = loaded.map((l) => (l.chapter.id === chapter.id ? { ...l, error: e.message } : l));
    }
  }

  function nextOf(chapter) { return chapters.find((c) => c.index === chapter.index + 1); }
  function prevOf(chapter) { return chapters.find((c) => c.index === chapter.index - 1); }
  function open(chapter) { flush(); navigate(reader('manga', sourceId, mediaId, chapter.id), { replace: true }); }

  async function finished(chapter) {
    if (!item) return;
    await markThrough(item, chapters, chapter, true).catch(() => {});
    const next = nextOf(chapter);
    if (next && settings.reader.infinite && !loaded.some((l) => l.chapter.id === next.id) && !loadingNext) {
      loadingNext = true;
      await loadChapter(next);
      loadingNext = false;
    }
  }

  function seen(chapter, index, total) {
    if (resumePage && chapter.id === startId && index < resumePage) return;
    if (chapter.id === startId) resumePage = 0;
    if (current?.id !== chapter.id) {
      current = chapter;
      history.replaceState({}, '', reader('manga', sourceId, mediaId, chapter.id));
    }
    page = index;
    if (item) track(item, chapter, { page: index, total, read: false });
    if (index >= total - 2) finished(chapter);
  }

  /** A page that arrives while already on screen counts as seen; the observer would not tell again. */
  function pageLoaded(e, chapter, index, total, src) {
    ready[src] = true;
    if (resumePage && chapter.id === startId) {
      // Back at the page where the user stopped, once the pages above it have their real height.
      const pages = loaded.find((l) => l.chapter.id === startId)?.pages || [];
      if (pages.slice(0, resumePage + 1).every((p) => ready[p])) {
        const target = resumePage;
        resumePage = 0;
        requestAnimationFrame(() => jumpTo(target));
      }
      return;
    }
    const r = e.currentTarget.getBoundingClientRect();
    if (r.top < innerHeight * 0.5 && r.bottom > innerHeight * 0.5) seen(chapter, index, total);
  }

  function observe() {
    observer?.disconnect();
    observer = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting || !e.target.classList.contains('ready')) continue;
        const el = e.target;
        const chapter = chapters.find((c) => c.id === el.dataset.chapter);
        if (chapter) seen(chapter, Number(el.dataset.index), Number(el.dataset.total));
      }
    }, { threshold: 0.5 });
    document.querySelectorAll('[data-page]').forEach((el) => observer.observe(el));
  }

  $effect(() => { flat.length; if (mode === 'vertical') tick().then(observe); });

  /** Vertical mode: jump to a page of the current chapter. */
  function jumpTo(index) {
    if (mode === 'paged') {
      const at = flat.findIndex((p) => p.chapter.id === current?.id && p.i === index);
      if (at >= 0) { pagedIndex = at; showPaged(); }
      return;
    }
    const el = document.querySelector(`[data-chapter="${CSS.escape(current.id)}"][data-index="${index}"]`);
    if (!el) return;
    el.scrollIntoView({ block: 'start' });
    seen(current, index, Number(el.dataset.total));
  }

  // Paged mode: one page at a time, arrows, taps on the sides and swipes.
  function turn(delta) {
    const target = pagedIndex + delta;
    if (target < 0) {
      const prev = current && prevOf(current);
      if (prev) open(prev);
      return;
    }
    if (target >= flat.length) {
      const next = current && nextOf(current);
      if (next && !loaded.some((l) => l.chapter.id === next.id)) loadChapter(next).then(() => { pagedIndex = target; showPaged(); });
      else if (!next) notify('Es el último capítulo');
      return;
    }
    pagedIndex = target;
    showPaged();
  }

  function showPaged() {
    const p = flat[pagedIndex];
    if (!p) return;
    seen(p.chapter, p.i, p.total);
    flat.slice(pagedIndex + 1, pagedIndex + 4).forEach((n) => { const img = new Image(); img.src = n.src; });
  }

  function key(e) {
    if (e.target.closest?.('input, select, textarea')) return;
    if (e.key === 'Escape' && !indexOpen && !optionsOpen) { back(mediaHref(sourceId, mediaId)); return; }
    if (mode !== 'paged') return;
    const forward = rtl ? 'ArrowLeft' : 'ArrowRight';
    const backward = rtl ? 'ArrowRight' : 'ArrowLeft';
    if (e.key === forward || e.key === 'PageDown' || e.key === ' ') { e.preventDefault(); turn(1); }
    if (e.key === backward || e.key === 'PageUp') { e.preventDefault(); turn(-1); }
  }

  let touchX = 0;
  function tapPaged(e) {
    const x = e.clientX / window.innerWidth;
    if (x < 0.3) turn(rtl ? 1 : -1);
    else if (x > 0.7) turn(rtl ? -1 : 1);
    else ui = !ui;
  }
  function swipe(e) {
    const dx = e.changedTouches[0].clientX - touchX;
    if (Math.abs(dx) > 60) turn((dx < 0) !== rtl ? 1 : -1);
  }

  function setReader(key, value) { settings.reader[key] = value; saveSettings(); }

  onMount(async () => {
    clockTimer = setInterval(() => (now = clock()), 15_000);
    try {
      const [m, list, progress] = await Promise.all([api.media(sourceId, mediaId), api.chapters(sourceId, mediaId), progressMap(sourceId, mediaId)]);
      item = m;
      chapters = list.chapters;
      const start = chapters.find((c) => c.id === startId) || chapters[0];
      if (!start) { error = 'No hay capítulos'; return; }
      const saved = progress[start.id];
      resumePage = saved && !saved.read && saved.page > 0 ? saved.page : 0;
      current = start;
      await loadChapter(start);
      if (mode === 'paged') {
        const at = resumePage ? flat.findIndex((p) => p.i === resumePage) : 0;
        pagedIndex = Math.max(0, at);
        resumePage = 0;
        showPaged();
      }
      setTimeout(() => (ui = false), 2500);
    } catch (e) {
      error = e.message;
    }
  });

  onDestroy(() => { observer?.disconnect(); clearInterval(clockTimer); flush(); });
</script>

<svelte:window onkeydown={key} />

<div class="reader" class:paged={mode === 'paged'}>
  {#if error}
    <div class="center"><p>{error}</p><button class="btn tonal" onclick={() => back(mediaHref(sourceId, mediaId))}>Volver</button></div>
  {:else if mode === 'vertical'}
    <div class="strip w-{width}" onclick={() => (ui = !ui)} role="presentation">
      {#each loaded as l, li (l.chapter.id)}
        {#if li > 0}<div class="divider">{l.chapter.label}{l.chapter.name ? ' · ' + l.chapter.name : ''}</div>{/if}
        {#if l.error}<p class="center error-text">{l.error}</p>{/if}
        {#if !l.pages.length && !l.error}<div class="center"><div class="spinner"></div></div>{/if}
        {#each l.pages as src, i (src)}
          <img {src} alt="Página {i + 1}" loading={i < 3 || (li === 0 && i <= resumePage + 2) ? 'eager' : 'lazy'} decoding="async" class:ready={ready[src]} onload={(e) => pageLoaded(e, l.chapter, i, l.pages.length, src)} data-page data-chapter={l.chapter.id} data-index={i} data-total={l.pages.length} />
        {/each}
      {/each}
      {#if current && !nextOf(current) && loaded.at(-1)?.pages.length}<div class="divider">Fin. No hay más capítulos.</div>{/if}
      {#if current && nextOf(current) && !settings.reader.infinite}
        <div class="center"><button class="btn primary" onclick={(e) => { e.stopPropagation(); open(nextOf(current)); }}>Siguiente: {nextOf(current).label}</button></div>
      {/if}
    </div>
  {:else}
    <div class="single" onclick={tapPaged} role="presentation" ontouchstart={(e) => (touchX = e.touches[0].clientX)} ontouchend={swipe}>
      {#if flat[pagedIndex]}<img src={flat[pagedIndex].src} alt="Página {pagedIndex + 1}" />{:else}<div class="spinner"></div>{/if}
    </div>
  {/if}

  {#if ui}
    <header class="top">
      <button class="icon-btn" onclick={() => back(mediaHref(sourceId, mediaId))} aria-label="Volver"><Icon name="back" /></button>
      <div class="titles">
        <div class="t1">{item?.title ?? ''}</div>
        <div class="t2">{current?.label ?? ''}{current?.name ? ' · ' + current.name : ''}</div>
      </div>
      <button class="icon-btn" onclick={() => (indexOpen = true)} aria-label="Índice de capítulos" title="Índice"><Icon name="list" /></button>
      <button class="icon-btn" onclick={() => (optionsOpen = true)} aria-label="Ajustes de lectura" title="Ajustes de lectura"><Icon name="tune" /></button>
    </header>
    <footer class="bottom">
      <button class="btn text" disabled={!current || !prevOf(current)} onclick={() => open(prevOf(current))}>Anterior</button>
      {#if currentPages > 1}
        <input class="slider" type="range" min="0" max={currentPages - 1} value={page} dir={mode === 'paged' && rtl ? 'rtl' : 'ltr'} onchange={(e) => jumpTo(+e.target.value)} aria-label="Página" />
      {/if}
      <span class="small count">{current ? `${page + 1} / ${currentPages || '…'}` : ''}</span>
      <button class="btn text" disabled={!current || !nextOf(current)} onclick={() => open(nextOf(current))}>Siguiente</button>
    </footer>
  {:else}
    <div class="mini-clock">{now}</div>
  {/if}
</div>

<Sheet bind:open={indexOpen} title="Capítulos">
  <input class="field" type="search" placeholder="Buscar capítulo" bind:value={indexQuery} />
  <div class="index">
    {#each indexList as c (c.id)}
      <button class="list-item idx" class:here={c.id === current?.id} onclick={() => { indexOpen = false; if (c.id !== current?.id) open(c); }}>
        <span class="grow">{c.label}{#if c.name} <span class="muted small">{c.name}</span>{/if}</span>
        {#if c.date}<span class="small muted">{c.date}</span>{/if}
      </button>
    {/each}
  </div>
</Sheet>

<Sheet bind:open={optionsOpen} title="Lectura">
  <div class="small muted">Modo</div>
  <div class="wrap">
    <button class="chip" class:on={mode === 'vertical'} onclick={() => setReader('mode', 'vertical')}>Vertical (webtoon)</button>
    <button class="chip" class:on={mode === 'paged'} onclick={() => setReader('mode', 'paged')}>Por páginas</button>
  </div>
  {#if mode === 'paged'}
    <div class="small muted">Sentido</div>
    <div class="wrap">
      <button class="chip" class:on={!rtl} onclick={() => setReader('direction', 'ltr')}>Izquierda a derecha</button>
      <button class="chip" class:on={rtl} onclick={() => setReader('direction', 'rtl')}>Derecha a izquierda (manga)</button>
    </div>
  {:else}
    <div class="small muted">Ancho de las páginas</div>
    <div class="wrap">
      {#each WIDTHS as [k, label]}<button class="chip" class:on={width === k} onclick={() => setReader('width', k)}>{label}</button>{/each}
    </div>
  {/if}
  <label class="toggle"><span class="grow">Pasar solo al siguiente capítulo</span><input type="checkbox" checked={settings.reader.infinite} onchange={(e) => setReader('infinite', e.target.checked)} /></label>
  <p class="small muted">Teclado: ← → para pasar página, Esc para salir. En el móvil puedes pellizcar para hacer zoom.</p>
</Sheet>

<style>
  .reader { background: #000; min-height: 100vh; color: #fff; }
  .strip { margin: 0 auto; max-width: 900px; }
  .strip.w-narrow { max-width: 620px; }
  .strip.w-wide { max-width: 1240px; }
  .strip.w-full { max-width: none; }
  /* Until a page arrives it holds a tall space: otherwise every unloaded page would be "on screen" at once. */
  .strip img { display: block; width: 100%; height: auto; min-height: 80vh; background: #0a0a0a; }
  .strip img.ready { min-height: 0; background: none; }
  .divider { text-align: center; padding: 28px 12px; color: var(--text-dim); font-weight: 600; }
  .single { height: 100vh; height: 100dvh; display: grid; place-items: center; overflow: hidden; touch-action: pan-y pinch-zoom; }
  .single img { max-width: 100%; max-height: 100vh; max-height: 100dvh; object-fit: contain; user-select: none; -webkit-user-drag: none; }
  .top, .bottom { position: fixed; left: 0; right: 0; z-index: 20; display: flex; align-items: center; gap: 8px; background: #000d; backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); animation: in 0.2s; }
  .top { top: 0; padding: calc(var(--safe-top) + 4px) 8px 6px; }
  .bottom { bottom: 0; padding: 6px 8px calc(var(--safe-bottom) + 6px); }
  .slider { flex: 1; min-width: 0; accent-color: var(--primary); }
  .count { min-width: 64px; text-align: center; color: var(--text-dim); }
  .titles { flex: 1; min-width: 0; }
  .t1 { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .t2 { font-size: 0.8rem; color: var(--text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mini-clock { position: fixed; top: calc(var(--safe-top) + 8px); right: 12px; z-index: 20; padding: 2px 8px; border-radius: 999px; background: #0006; color: #fffd; font-size: 0.75rem; font-weight: 500; pointer-events: none; }
  .index { margin-top: 8px; }
  .idx { width: 100%; text-align: left; gap: 12px; }
  .idx.here { color: var(--primary); font-weight: 600; }
  .grow { flex: 1; min-width: 0; }
  .wrap { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0 16px; }
  .toggle { display: flex; align-items: center; gap: 12px; padding: 8px 0; }
  @keyframes in { from { opacity: 0; } }
</style>
