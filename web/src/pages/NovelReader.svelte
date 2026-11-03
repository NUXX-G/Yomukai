<script>
  import { onMount, onDestroy } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, back, navigate, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { settings, saveSettings } from '../lib/store.svelte.js';
  import { track, markThrough, flush, progressMap } from '../lib/progress.js';
  import { clock } from '../lib/format.js';
  import Icon from '../components/Icon.svelte';
  import Sheet from '../components/Sheet.svelte';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const chapterId = route.params.chapter;

  const THEMES = { dark: ['#000', '#d8d8de'], sepia: ['#f4ecd8', '#3b3023'], light: ['#fafaf9', '#18181b'] };

  let item = $state(null);
  let chapters = $state([]);
  let chapter = $state(null);
  let paragraphs = $state([]);
  let error = $state('');
  let ui = $state(true);
  let now = $state(clock());
  let options = $state(false);
  let done = false;
  let clockTimer;

  const next = $derived(chapter && chapters.find((c) => c.index === chapter.index + 1));
  const prev = $derived(chapter && chapters.find((c) => c.index === chapter.index - 1));
  const colors = $derived(THEMES[settings.novel.theme] || THEMES.dark);

  function onScroll() {
    if (!item || !chapter) return;
    const max = document.documentElement.scrollHeight - innerHeight;
    const fraction = max > 0 ? Math.min(1, scrollY / max) : 1;
    track(item, chapter, { fraction, read: false });
    if (fraction > 0.97 && !done) {
      done = true;
      markThrough(item, chapters, chapter, true).catch(() => {});
    }
  }

  function go(target) { flush(); navigate(reader('novel', sourceId, mediaId, target.id), { replace: true }); }

  onMount(async () => {
    clockTimer = setInterval(() => (now = clock()), 15_000);
    try {
      const [m, list] = await Promise.all([api.media(sourceId, mediaId), api.chapters(sourceId, mediaId)]);
      item = m;
      chapters = list.chapters;
      chapter = chapters.find((c) => c.id === chapterId) || chapters[0];
      const [{ text }, progress] = await Promise.all([api.text(sourceId, chapter.url), progressMap(sourceId, mediaId)]);
      paragraphs = text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
      // Back where the user left it.
      const fraction = progress[chapter.id]?.read ? 0 : progress[chapter.id]?.fraction || 0;
      requestAnimationFrame(() => window.scrollTo(0, fraction * (document.documentElement.scrollHeight - innerHeight)));
      setTimeout(() => (ui = false), 2500);
    } catch (e) {
      error = e.message;
    }
  });

  onDestroy(() => { clearInterval(clockTimer); flush(); });

  function size(delta) { settings.novel.size = Math.min(30, Math.max(13, settings.novel.size + delta)); saveSettings(); }
</script>

<svelte:window onscroll={onScroll} />

<div class="novel" style:background={colors[0]} style:color={colors[1]} onclick={() => (ui = !ui)} role="presentation">
  {#if error}
    <div class="center"><p>{error}</p></div>
  {:else if !paragraphs.length}
    <div class="center"><div class="spinner"></div></div>
  {:else}
    <article style:font-size="{settings.novel.size}px" style:line-height={settings.novel.line} class:serif={settings.novel.font === 'serif'}>
      <h1>{chapter.label}{chapter.name ? ' · ' + chapter.name : ''}</h1>
      {#each paragraphs as p}<p>{p}</p>{/each}
      <nav class="end">
        {#if prev}<button class="btn outline" onclick={(e) => { e.stopPropagation(); go(prev); }}>Anterior</button>{/if}
        {#if next}<button class="btn primary" onclick={(e) => { e.stopPropagation(); go(next); }}>Siguiente: {next.label}</button>{:else}<span class="muted">Último capítulo</span>{/if}
      </nav>
    </article>
  {/if}
</div>

{#if ui}
  <header class="top">
    <button class="icon-btn" onclick={() => back(mediaHref(sourceId, mediaId))} aria-label="Volver"><Icon name="back" /></button>
    <div class="titles"><div class="t1">{item?.title ?? ''}</div><div class="t2">{chapter?.label ?? ''}</div></div>
    <button class="icon-btn" onclick={() => (options = true)} aria-label="Texto"><Icon name="text" /></button>
  </header>
{:else}
  <div class="mini-clock">{now}</div>
{/if}

<Sheet bind:open={options} title="Texto">
  <div class="opt-row"><span>Tamaño</span><button class="btn outline" onclick={() => size(-1)}>A−</button><span>{settings.novel.size}</span><button class="btn outline" onclick={() => size(1)}>A+</button></div>
  <div class="opt-row">
    {#each [['dark', 'Noche'], ['sepia', 'Sepia'], ['light', 'Claro']] as [k, label]}
      <button class="chip" class:on={settings.novel.theme === k} onclick={() => { settings.novel.theme = k; saveSettings(); }}>{label}</button>
    {/each}
  </div>
  <div class="opt-row">
    <button class="chip" class:on={settings.novel.font === 'serif'} onclick={() => { settings.novel.font = 'serif'; saveSettings(); }}>Serif</button>
    <button class="chip" class:on={settings.novel.font !== 'serif'} onclick={() => { settings.novel.font = 'sans'; saveSettings(); }}>Sans</button>
  </div>
</Sheet>

<style>
  .novel { min-height: 100vh; transition: background 0.3s, color 0.3s; }
  article { max-width: 720px; margin: 0 auto; padding: calc(var(--safe-top) + 72px) 22px calc(var(--safe-bottom) + 48px); }
  article.serif { font-family: Georgia, 'Iowan Old Style', 'Palatino Linotype', serif; }
  h1 { font-size: 1.3em; margin: 0 0 1em; }
  p { margin: 0 0 1em; text-align: justify; hyphens: auto; }
  .end { display: flex; justify-content: space-between; gap: 12px; margin-top: 2em; font-size: 16px; font-family: var(--font); }
  .top { position: fixed; top: 0; left: 0; right: 0; z-index: 20; display: flex; align-items: center; gap: 8px; padding: calc(var(--safe-top) + 4px) 8px 6px; background: #000d; color: #fff; backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); }
  .titles { flex: 1; min-width: 0; }
  .t1 { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .t2 { font-size: 0.8rem; color: var(--text-dim); }
  .mini-clock { position: fixed; top: calc(var(--safe-top) + 8px); right: 12px; z-index: 20; padding: 2px 8px; border-radius: 999px; background: #0006; color: #fffd; font-size: 0.75rem; pointer-events: none; }
  .opt-row { display: flex; align-items: center; gap: 10px; margin: 12px 0; }
</style>
