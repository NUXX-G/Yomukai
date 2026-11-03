<script>
  import { onMount, onDestroy, tick } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, back, navigate, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { settings, saveSettings, sourceById } from '../lib/store.svelte.js';
  import { track, markThrough, flush, progressMap } from '../lib/progress.js';
  import { clock } from '../lib/format.js';
  import Icon from '../components/Icon.svelte';
  import Sheet from '../components/Sheet.svelte';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const chapterId = route.params.chapter;

  const THEMES = { oled: ['#000', '#c9c9cf', 'OLED'], dark: ['#16161a', '#d8d8de', 'Noche'], sepia: ['#f4ecd8', '#3b3023', 'Sepia'], light: ['#fafaf9', '#18181b', 'Claro'] };
  const WIDTHS = { narrow: 560, normal: 720, wide: 900 };

  let item = $state(null);
  let chapters = $state([]);
  let chapter = $state(null);
  let paragraphs = $state([]);
  let error = $state('');
  let ui = $state(true);
  let now = $state(clock());
  let options = $state(false);
  let indexOpen = $state(false);
  let indexQuery = $state('');
  let speaking = $state(-1);
  let speechPaused = $state(false);
  let done = false;
  let clockTimer;
  let article;

  const next = $derived(chapter && chapters.find((c) => c.index === chapter.index + 1));
  const prev = $derived(chapter && chapters.find((c) => c.index === chapter.index - 1));
  const colors = $derived(THEMES[settings.novel.theme] || THEMES.dark);
  const canSpeak = typeof window !== 'undefined' && 'speechSynthesis' in window;
  const indexList = $derived.by(() => {
    const q = indexQuery.trim().toLowerCase();
    const all = [...chapters].reverse();
    return q ? all.filter((c) => (c.label + ' ' + (c.name || '')).toLowerCase().includes(q)) : all;
  });

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

  function go(target) { stopSpeech(); flush(); navigate(reader('novel', sourceId, mediaId, target.id), { replace: true }); }

  // Reading aloud, paragraph by paragraph, from the first one on screen.
  function firstVisible() {
    const ps = article?.querySelectorAll('p[data-i]') || [];
    for (const p of ps) if (p.getBoundingClientRect().bottom > 80) return Number(p.dataset.i);
    return 0;
  }

  function speak(i) {
    if (i >= paragraphs.length) {
      speaking = -1;
      if (next && settings.novel.speechNext) { sessionStorage.setItem('novel.autospeak', '1'); go(next); }
      return;
    }
    speaking = i;
    const u = new SpeechSynthesisUtterance(paragraphs[i]);
    const lang = sourceById(sourceId)?.language === 'EN' ? 'en' : 'es';
    u.lang = lang === 'en' ? 'en-US' : 'es-ES';
    u.voice = speechSynthesis.getVoices().find((v) => v.lang.toLowerCase().startsWith(lang)) || null;
    u.rate = settings.novel.speechRate || 1;
    u.onend = () => { if (speaking === i) speak(i + 1); };
    speechSynthesis.speak(u);
    article?.querySelector(`p[data-i="${i}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  function toggleSpeech() {
    if (speaking < 0) { speechSynthesis.cancel(); speechPaused = false; speak(firstVisible()); return; }
    if (speechPaused) { speechSynthesis.resume(); speechPaused = false; }
    else { speechSynthesis.pause(); speechPaused = true; }
  }

  function stopSpeech() {
    if (!canSpeak) return;
    speaking = -1;
    speechPaused = false;
    speechSynthesis.cancel();
  }

  function key(e) {
    if (e.target.closest?.('input, select, textarea')) return;
    if (e.key === 'Escape' && !options && !indexOpen) back(mediaHref(sourceId, mediaId));
    if (e.key === 'ArrowRight' && e.altKey && next) go(next);
    if (e.key === 'ArrowLeft' && e.altKey && prev) go(prev);
  }

  onMount(async () => {
    clockTimer = setInterval(() => (now = clock()), 15_000);
    try {
      const [m, list] = await Promise.all([api.media(sourceId, mediaId), api.chapters(sourceId, mediaId)]);
      item = m;
      chapters = list.chapters;
      chapter = chapters.find((c) => c.id === chapterId) || chapters[0];
      const [{ text }, progress] = await Promise.all([api.text(sourceId, chapter.url), progressMap(sourceId, mediaId)]);
      paragraphs = text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
      await tick();
      // Back where the user left it.
      const fraction = progress[chapter.id]?.read ? 0 : progress[chapter.id]?.fraction || 0;
      requestAnimationFrame(() => window.scrollTo(0, fraction * (document.documentElement.scrollHeight - innerHeight)));
      if (sessionStorage.getItem('novel.autospeak') && canSpeak) { sessionStorage.removeItem('novel.autospeak'); speak(0); }
      setTimeout(() => (ui = false), 2500);
    } catch (e) {
      error = e.message;
    }
  });

  onDestroy(() => { clearInterval(clockTimer); flush(); stopSpeech(); });

  function set(key, value) { settings.novel[key] = value; saveSettings(); }
  function size(delta) { set('size', Math.min(32, Math.max(13, settings.novel.size + delta))); }
  function line(delta) { set('line', Math.round(Math.min(2.4, Math.max(1.2, settings.novel.line + delta)) * 10) / 10); }
</script>

<svelte:window onscroll={onScroll} onkeydown={key} />

<div class="novel" style:background={colors[0]} style:color={colors[1]} onclick={() => (ui = !ui)} role="presentation">
  {#if error}
    <div class="center"><p>{error}</p><button class="btn tonal" onclick={() => back(mediaHref(sourceId, mediaId))}>Volver</button></div>
  {:else if !paragraphs.length}
    <div class="center"><div class="spinner"></div></div>
  {:else}
    <article bind:this={article} style:font-size="{settings.novel.size}px" style:line-height={settings.novel.line} style:max-width="{WIDTHS[settings.novel.width] || 720}px" class:serif={settings.novel.font === 'serif'} class:justify={settings.novel.justify !== false}>
      <h1>{chapter.label}{chapter.name ? ' · ' + chapter.name : ''}</h1>
      {#each paragraphs as p, i}<p data-i={i} class:speaking={speaking === i}>{p}</p>{/each}
      <nav class="end">
        {#if prev}<button class="btn outline" onclick={(e) => { e.stopPropagation(); go(prev); }}>Anterior</button>{:else}<span></span>{/if}
        {#if next}<button class="btn primary" onclick={(e) => { e.stopPropagation(); go(next); }}>Siguiente: {next.label}</button>{:else}<span class="muted">Último capítulo</span>{/if}
      </nav>
    </article>
  {/if}
</div>

{#if ui}
  <header class="top">
    <button class="icon-btn" onclick={() => back(mediaHref(sourceId, mediaId))} aria-label="Volver"><Icon name="back" /></button>
    <div class="titles"><div class="t1">{item?.title ?? ''}</div><div class="t2">{chapter?.label ?? ''}</div></div>
    {#if canSpeak}<button class="icon-btn" class:active={speaking >= 0} onclick={toggleSpeech} aria-label="Leer en voz alta" title="Leer en voz alta"><Icon name="speech" /></button>{/if}
    <button class="icon-btn" onclick={() => (indexOpen = true)} aria-label="Índice" title="Índice"><Icon name="list" /></button>
    <button class="icon-btn" onclick={() => (options = true)} aria-label="Texto" title="Texto"><Icon name="text" /></button>
  </header>
{:else}
  <div class="mini-clock">{now}</div>
{/if}

{#if speaking >= 0}
  <div class="speech-bar">
    <button class="icon-btn" onclick={toggleSpeech} aria-label={speechPaused ? 'Seguir' : 'Pausar'}><Icon name={speechPaused ? 'play' : 'pause'} /></button>
    <span class="small grow">Leyendo en voz alta · párrafo {speaking + 1} de {paragraphs.length}</span>
    <button class="icon-btn" onclick={stopSpeech} aria-label="Parar"><Icon name="close" /></button>
  </div>
{/if}

<Sheet bind:open={indexOpen} title="Capítulos">
  <input class="field" type="search" placeholder="Buscar capítulo" bind:value={indexQuery} />
  <div style="margin-top:8px">
    {#each indexList as c (c.id)}
      <button class="list-item idx" class:here={c.id === chapter?.id} onclick={() => { indexOpen = false; if (c.id !== chapter?.id) go(c); }}>
        <span class="grow">{c.label}{#if c.name} <span class="muted small">{c.name}</span>{/if}</span>
      </button>
    {/each}
  </div>
</Sheet>

<Sheet bind:open={options} title="Texto">
  <div class="opt-row"><span class="grow">Tamaño</span><button class="btn outline" onclick={() => size(-1)}>A−</button><span class="val">{settings.novel.size}</span><button class="btn outline" onclick={() => size(1)}>A+</button></div>
  <div class="opt-row"><span class="grow">Interlineado</span><button class="btn outline" onclick={() => line(-0.1)}>−</button><span class="val">{settings.novel.line}</span><button class="btn outline" onclick={() => line(0.1)}>+</button></div>
  <div class="small muted">Tema</div>
  <div class="opt-row wrap">
    {#each Object.entries(THEMES) as [k, [bg, fg, label]]}
      <button class="chip" class:on={settings.novel.theme === k} onclick={() => set('theme', k)}><span class="swatch" style:background={bg} style:color={fg}>A</span>{label}</button>
    {/each}
  </div>
  <div class="small muted">Letra y ancho</div>
  <div class="opt-row wrap">
    <button class="chip" class:on={settings.novel.font === 'serif'} onclick={() => set('font', 'serif')}>Serif</button>
    <button class="chip" class:on={settings.novel.font !== 'serif'} onclick={() => set('font', 'sans')}>Sans</button>
    {#each [['narrow', 'Estrecho'], ['normal', 'Normal'], ['wide', 'Ancho']] as [k, label]}
      <button class="chip" class:on={(settings.novel.width || 'normal') === k} onclick={() => set('width', k)}>{label}</button>
    {/each}
  </div>
  <label class="toggle"><span class="grow">Texto justificado</span><input type="checkbox" checked={settings.novel.justify !== false} onchange={(e) => set('justify', e.target.checked)} /></label>
  {#if canSpeak}
    <div class="opt-row"><span class="grow">Velocidad de la voz</span>
      {#each [0.8, 1, 1.25, 1.5] as r}<button class="chip" class:on={(settings.novel.speechRate || 1) === r} onclick={() => set('speechRate', r)}>{r}x</button>{/each}
    </div>
    <label class="toggle"><span class="grow">Seguir leyendo en voz alta el siguiente capítulo</span><input type="checkbox" checked={!!settings.novel.speechNext} onchange={(e) => set('speechNext', e.target.checked)} /></label>
  {/if}
</Sheet>

<style>
  .novel { min-height: 100vh; transition: background 0.3s, color 0.3s; }
  article { margin: 0 auto; padding: calc(var(--safe-top) + 72px) 22px calc(var(--safe-bottom) + 96px); }
  article.serif { font-family: Georgia, 'Iowan Old Style', 'Palatino Linotype', serif; }
  h1 { font-size: 1.3em; margin: 0 0 1em; }
  p { margin: 0 0 1em; hyphens: auto; border-radius: 6px; transition: background 0.3s; }
  .justify p { text-align: justify; }
  p.speaking { background: color-mix(in srgb, var(--primary) 18%, transparent); }
  .end { display: flex; justify-content: space-between; gap: 12px; margin-top: 2em; font-size: 16px; font-family: var(--font); }
  .top { position: fixed; top: 0; left: 0; right: 0; z-index: 20; display: flex; align-items: center; gap: 4px; padding: calc(var(--safe-top) + 4px) 8px 6px; background: #000d; color: #fff; backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); }
  .top .icon-btn { color: #fff; }
  .top .active { color: var(--primary); }
  .titles { flex: 1; min-width: 0; }
  .t1 { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .t2 { font-size: 0.8rem; color: var(--text-dim); }
  .mini-clock { position: fixed; top: calc(var(--safe-top) + 8px); right: 12px; z-index: 20; padding: 2px 8px; border-radius: 999px; background: #0006; color: #fffd; font-size: 0.75rem; pointer-events: none; }
  .speech-bar { position: fixed; left: 50%; transform: translateX(-50%); bottom: calc(var(--safe-bottom) + 12px); z-index: 25; width: min(560px, calc(100vw - 24px)); display: flex; align-items: center; gap: 8px; padding: 4px 8px 4px 4px; border-radius: 16px; background: #111d; color: #fff; backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); box-shadow: 0 6px 20px #0008; }
  .speech-bar .icon-btn { color: #fff; }
  .grow { flex: 1; min-width: 0; }
  .opt-row { display: flex; align-items: center; gap: 10px; margin: 10px 0 14px; }
  .opt-row.wrap { flex-wrap: wrap; gap: 8px; }
  .val { min-width: 32px; text-align: center; }
  .swatch { display: inline-grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; margin-right: 6px; font-size: 0.7rem; font-weight: 700; border: 1px solid var(--outline); }
  .toggle { display: flex; align-items: center; gap: 12px; padding: 8px 0; }
  .idx { width: 100%; text-align: left; }
  .idx.here { color: var(--primary); font-weight: 600; }
</style>
