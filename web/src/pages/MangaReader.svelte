<script>
  import { onMount, onDestroy, tick } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, back, navigate, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { settings, saveSettings, notify } from '../lib/store.svelte.js';
  import { track, markThrough, flush } from '../lib/progress.js';
  import { clock } from '../lib/format.js';
  import Icon from '../components/Icon.svelte';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const startId = route.params.chapter;

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
  /** Pages that finished loading: only they count as seen. */
  const ready = $state({});
  let observer;
  let clockTimer;
  let loadingNext = false;

  const mode = $derived(settings.reader.mode);
  const flat = $derived(loaded.flatMap((l) => l.pages.map((src, i) => ({ src, i, chapter: l.chapter, total: l.pages.length }))));

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

  // Paged mode: one page at a time, arrows, taps on the sides and swipes.
  function turn(delta) {
    const target = pagedIndex + delta;
    if (target < 0) {
      const prev = current && prevOf(current);
      if (prev) navigate(reader('manga', sourceId, mediaId, prev.id), { replace: true });
      return;
    }
    if (target >= flat.length) {
      const next = current && nextOf(current);
      if (next && !loaded.some((l) => l.chapter.id === next.id)) loadChapter(next).then(() => { pagedIndex = target; showPaged(); });
      else notify('Es el último capítulo');
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
    if (mode !== 'paged') return;
    if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') { e.preventDefault(); turn(1); }
    if (e.key === 'ArrowLeft' || e.key === 'PageUp') { e.preventDefault(); turn(-1); }
  }

  let touchX = 0;
  function tapPaged(e) {
    const x = e.clientX / window.innerWidth;
    if (x < 0.3) turn(-1);
    else if (x > 0.7) turn(1);
    else ui = !ui;
  }

  onMount(async () => {
    clockTimer = setInterval(() => (now = clock()), 15_000);
    try {
      const [m, list] = await Promise.all([api.media(sourceId, mediaId), api.chapters(sourceId, mediaId)]);
      item = m;
      chapters = list.chapters;
      const start = chapters.find((c) => c.id === startId) || chapters[0];
      if (!start) { error = 'No hay capítulos'; return; }
      current = start;
      await loadChapter(start);
      if (mode === 'paged') showPaged();
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
    <div class="strip" onclick={() => (ui = !ui)} role="presentation">
      {#each loaded as l, li (l.chapter.id)}
        {#if li > 0}<div class="divider">{l.chapter.label}{l.chapter.name ? ' · ' + l.chapter.name : ''}</div>{/if}
        {#if l.error}<p class="center error-text">{l.error}</p>{/if}
        {#if !l.pages.length && !l.error}<div class="center"><div class="spinner"></div></div>{/if}
        {#each l.pages as src, i (src)}
          <img {src} alt="Página {i + 1}" loading={i < 3 ? 'eager' : 'lazy'} decoding="async" class:ready={ready[src]} onload={(e) => pageLoaded(e, l.chapter, i, l.pages.length, src)} data-page data-chapter={l.chapter.id} data-index={i} data-total={l.pages.length} />
        {/each}
      {/each}
      {#if current && !nextOf(current) && loaded.at(-1)?.pages.length}<div class="divider">Fin. No hay más capítulos.</div>{/if}
      {#if current && nextOf(current) && !settings.reader.infinite}
        <div class="center"><button class="btn primary" onclick={(e) => { e.stopPropagation(); navigate(reader('manga', sourceId, mediaId, nextOf(current).id), { replace: true }); }}>Siguiente: {nextOf(current).label}</button></div>
      {/if}
    </div>
  {:else}
    <div class="single" onclick={tapPaged} role="presentation" ontouchstart={(e) => (touchX = e.touches[0].clientX)} ontouchend={(e) => { const dx = e.changedTouches[0].clientX - touchX; if (Math.abs(dx) > 60) turn(dx < 0 ? 1 : -1); }}>
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
      <button class="icon-btn" onclick={() => { settings.reader.mode = mode === 'vertical' ? 'paged' : 'vertical'; saveSettings(); }} aria-label="Cambiar modo"><Icon name={mode === 'vertical' ? 'list' : 'fullscreen'} /></button>
    </header>
    <footer class="bottom">
      <button class="btn text" disabled={!current || !prevOf(current)} onclick={() => navigate(reader('manga', sourceId, mediaId, prevOf(current).id), { replace: true })}>Anterior</button>
      <span class="small">{current ? `${page + 1} / ${loaded.find((l) => l.chapter.id === current.id)?.pages.length || '…'}` : ''}</span>
      <button class="btn text" disabled={!current || !nextOf(current)} onclick={() => navigate(reader('manga', sourceId, mediaId, nextOf(current).id), { replace: true })}>Siguiente</button>
    </footer>
  {:else}
    <div class="mini-clock">{now}</div>
  {/if}
</div>

<style>
  .reader { background: #000; min-height: 100vh; color: #fff; }
  .strip { max-width: 900px; margin: 0 auto; }
  /* Until a page arrives it holds a tall space: otherwise every unloaded page would be "on screen" at once. */
  .strip img { width: 100%; height: auto; min-height: 80vh; background: #0a0a0a; }
  .strip img.ready { min-height: 0; background: none; }
  .divider { text-align: center; padding: 28px 12px; color: var(--text-dim); font-weight: 600; }
  .single { height: 100vh; display: grid; place-items: center; overflow: hidden; touch-action: pan-y; }
  .single img { max-width: 100%; max-height: 100vh; object-fit: contain; user-select: none; -webkit-user-drag: none; }
  .top, .bottom { position: fixed; left: 0; right: 0; z-index: 20; display: flex; align-items: center; gap: 8px; background: #000d; backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); animation: in 0.2s; }
  .top { top: 0; padding: calc(var(--safe-top) + 4px) 8px 6px; }
  .bottom { bottom: 0; justify-content: space-between; padding: 6px 8px calc(var(--safe-bottom) + 6px); }
  .titles { flex: 1; min-width: 0; }
  .t1 { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .t2 { font-size: 0.8rem; color: var(--text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mini-clock { position: fixed; top: calc(var(--safe-top) + 8px); right: 12px; z-index: 20; padding: 2px 8px; border-radius: 999px; background: #0006; color: #fffd; font-size: 0.75rem; font-weight: 500; pointer-events: none; }
  @keyframes in { from { opacity: 0; } }
</style>
