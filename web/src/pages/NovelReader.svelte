<script>
  import { onMount, onDestroy, tick } from 'svelte';
  import { startPresence } from '../lib/presence.js';
  // Friends see "reading now" (unless the title is private or the profile hidden).
  const stopPresence = startPresence('reading', () => ({ title: item?.title, label: current?.label, sourceId: route.params.source, mediaId: route.params.id }));
  import { api, downloadChapters } from '../lib/api.js';
  import { route, back, navigate, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { settings, saveSettings, sourceById, notify } from '../lib/store.svelte.js';
  import { track, markThrough, flush, progressMap } from '../lib/progress.js';
  import { clock } from '../lib/format.js';
  import Icon from '../components/Icon.svelte';
  import WordSheet from '../components/WordSheet.svelte';
  import Sheet from '../components/Sheet.svelte';
  import SpeedControl from '../components/SpeedControl.svelte';
  import { SpeechReader, speechVoices, loadVoices, voiceSettings, setVoice } from '../lib/speech.svelte.js';
  import { screen, canFullscreen, toggleFullscreen, enterFullscreen, enterFullscreenQuietly, leaveImmersive } from '../lib/fullscreen.svelte.js';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const chapterId = route.params.chapter;

  const THEMES = { oled: ['#000', '#c9c9cf', 'OLED'], dark: ['#16161a', '#d8d8de', 'Noche'], sepia: ['#f4ecd8', '#3b3023', 'Sepia'], light: ['#fafaf9', '#18181b', 'Claro'] };
  const WIDTHS = { narrow: 560, normal: 720, wide: 900 };

  let item = $state(null);
  let chapters = $state([]);
  /** Chapters on screen, in order: { chapter, paragraphs, error } */
  let loaded = $state([]);
  let current = $state(null);
  let error = $state('');
  let ui = $state(true);
  let now = $state(clock());
  let options = $state(false);
  let indexOpen = $state(false);
  let indexTab = $state('chapters');
  let indexQuery = $state('');
  let bookmarks = $state([]);
  let findOpen = $state(false);
  let findQuery = $state('');
  let findAt = $state(0);
  let speaking = $state(null);
  let speechPaused = $state(false);
  let scrolling = $state(false);
  let scrollFrame = 0;
  let clockTimer;
  let article;
  const finished = new Set();
  let loadingNext = false;

  const infinite = $derived(settings.novel.infinite !== false);
  const colors = $derived(THEMES[settings.novel.theme] || THEMES.dark);
  const canSpeak = typeof window !== 'undefined' && ('speechSynthesis' in window || 'Audio' in window);
  const voice = new SpeechReader();
  voice.onFallback = (msg) => notify('Voces naturales no disponibles ahora (' + msg + '): sigo con las del navegador');
  const speechLang = $derived({ EN: 'en', ZH: 'zh' }[sourceById(sourceId)?.language] ?? 'es');
  const nextOf = (c) => c && chapters.find((x) => x.index === c.index + 1);
  const prevOf = (c) => c && chapters.find((x) => x.index === c.index - 1);
  const indexList = $derived.by(() => {
    const q = indexQuery.trim().toLowerCase();
    const all = [...chapters].reverse();
    return q ? all.filter((c) => (c.label + ' ' + (c.name || '')).toLowerCase().includes(q)) : all;
  });
  /** Every paragraph that contains the search, as [chapter position, paragraph index]. */
  const hits = $derived.by(() => {
    const q = findQuery.trim().toLowerCase();
    if (q.length < 2) return [];
    const out = [];
    loaded.forEach((l, li) => l.paragraphs.forEach((p, i) => { if (p.toLowerCase().includes(q)) out.push([li, i]); }));
    return out;
  });

  async function loadChapter(chapter) {
    loaded = [...loaded, { chapter, paragraphs: [], error: '' }];
    try {
      const { text } = await api.text(sourceId, chapter.url);
      const paragraphs = text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
      loaded = loaded.map((l) => (l.chapter.id === chapter.id ? { ...l, paragraphs } : l));
    } catch (e) {
      loaded = loaded.map((l) => (l.chapter.id === chapter.id ? { ...l, error: e.message } : l));
    }
  }

  /** Which chapter is being read, how far into it, and whether to load the next one. */
  function onScroll() {
    if (!item || !article) return;
    const sections = [...article.querySelectorAll('section[data-c]')];
    let section = sections[0];
    for (const s of sections) if (s.getBoundingClientRect().top < innerHeight * 0.4) section = s;
    if (!section) return;
    const chapter = loaded[Number(section.dataset.c)]?.chapter;
    if (!chapter) return;
    if (current?.id !== chapter.id) {
      current = chapter;
      history.replaceState({}, '', reader('novel', sourceId, mediaId, chapter.id));
    }
    const r = section.getBoundingClientRect();
    const span = r.height - innerHeight;
    const fraction = span > 0 ? Math.min(1, Math.max(0, -r.top / span)) : 1;
    track(item, chapter, { fraction, read: false });
    if (fraction > 0.97 && !finished.has(chapter.id)) {
      finished.add(chapter.id);
      markThrough(item, chapters, chapter, true).catch(() => {});
    }
    // Close to the end of what is loaded: the next chapter joins below.
    const last = loaded.at(-1);
    const bottom = article.getBoundingClientRect().bottom;
    if (infinite && last?.paragraphs.length && bottom < innerHeight * 2.5 && !loadingNext) {
      const next = nextOf(last.chapter);
      if (next) { loadingNext = true; loadChapter(next).finally(() => (loadingNext = false)); }
    }
  }

  function go(target) { stopSpeech(); flush(); navigate(reader('novel', sourceId, mediaId, target.id), { replace: true }); }

  function visibleParagraph() {
    for (const p of article?.querySelectorAll('p[data-i]') || []) {
      if (p.getBoundingClientRect().bottom > 80) return { c: Number(p.dataset.c), i: Number(p.dataset.i), el: p };
    }
    return null;
  }

  // Reading aloud, paragraph by paragraph, on into the next chapters.
  function speak(c, i) {
    voice.stop();
    const l = loaded[c];
    if (!l) { speaking = null; return; }
    if (i >= l.paragraphs.length) {
      const next = nextOf(l.chapter);
      if (loaded[c + 1]) return speak(c + 1, 0);
      if (next && settings.novel.speechNext) {
        loadChapter(next).then(() => tick()).then(() => speak(c + 1, 0));
        return;
      }
      speaking = null;
      return;
    }
    speaking = { c, i };
    const lang = speechLang;
    voice.read(l.paragraphs[i], lang).then(
      () => { if (speaking?.c === c && speaking?.i === i) speak(c, i + 1); },
      (e) => { if (e.message !== 'stopped' && speaking?.c === c && speaking?.i === i) speak(c, i + 1); }
    );
    // Every 20 paragraphs, the home worker is asked to get the next 30 ready (high-quality voices only).
    if (i % 20 === 0) prefetchAhead(c, i, lang);
    showInSystem(l.chapter);
    // The next two paragraphs are prepared while this one is read: no silence between them.
    [l.paragraphs[i + 1], l.paragraphs[i + 2] ?? loaded[c + 1]?.paragraphs[0]].forEach((p) => voice.prepare(p, lang));
    article?.querySelector(`p[data-c="${c}"][data-i="${i}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  function prefetchAhead(c, i, lang) {
    if (voice.engine() !== 'natural') return;
    const rate = settings.novel.speechRate || 1;
    const byVoice = {};
    for (const p of (loaded[c]?.paragraphs || []).slice(i + 3, i + 33)) {
      for (const piece of voice.plan(p, lang)) {
        if (piece.voice?.startsWith('w:')) (byVoice[piece.voice] ||= []).push(piece.text);
      }
    }
    for (const [v, texts] of Object.entries(byVoice)) api.ttsPrefetch(v, texts, rate).catch(() => {});
  }

  /** Lock screen, headphones and media keys: play/pause, next and previous chapter. */
  let systemChapter = null;
  function showInSystem(chapter) {
    if (!('mediaSession' in navigator) || systemChapter === chapter?.id) return;
    systemChapter = chapter?.id;
    try {
      navigator.mediaSession.metadata = new MediaMetadata({ title: chapter?.label || '', artist: item?.title || '', album: 'Yomukai',
        artwork: item?.cover ? [{ src: item.cover, sizes: '512x512' }] : [] });
      navigator.mediaSession.setActionHandler('play', () => { if (speechPaused) toggleSpeech(); });
      navigator.mediaSession.setActionHandler('pause', () => { if (speaking && !speechPaused) toggleSpeech(); });
      navigator.mediaSession.setActionHandler('stop', () => stopSpeech());
      navigator.mediaSession.setActionHandler('nexttrack', () => { if (speaking) speak(speaking.c, loaded[speaking.c]?.paragraphs.length ?? 0); });
      navigator.mediaSession.setActionHandler('previoustrack', () => { if (speaking) speak(speaking.c, Math.max(0, speaking.i - 1)); });
    } catch { /* older browsers: the page controls still work */ }
  }

  // ---------- Learning mode: long press (or right click) on a word ----------
  let wordPick = $state(null);
  let pressTimer;
  let pressAt = null;
  function wordAt(x, y) {
    const range = document.caretRangeFromPoint?.(x, y) || (() => { const p = document.caretPositionFromPoint?.(x, y); if (!p) return null; const r = document.createRange(); r.setStart(p.offsetNode, p.offset); return r; })();
    const node = range?.startContainer;
    if (!node || node.nodeType !== Node.TEXT_NODE) return null;
    const text = node.textContent;
    let a = range.startOffset;
    let b = a;
    const letter = /[\p{L}\p{N}'’-]/u;
    while (a > 0 && letter.test(text[a - 1])) a--;
    while (b < text.length && letter.test(text[b])) b++;
    const word = text.slice(a, b).replace(/^['’-]+|['’-]+$/g, '');
    if (!word || !/\p{L}/u.test(word)) return null;
    const para = node.parentElement?.closest('p')?.textContent || text;
    const sentence = para.split(/(?<=[.!?…])\s+/).find((x) => x.toLowerCase().includes(word.toLowerCase())) || para;
    return { word: word.toLowerCase(), sentence: sentence.trim().slice(0, 500), paragraph: para.slice(0, 2_000), lang: speechLang, source: item?.title || '' };
  }
  // Words being learnt are underlined with the CSS Highlight API (the text itself is never touched).
  let levels = $state({});
  let learningHere = $state(0);
  $effect(() => {
    if (!settings.learning?.enabled) return;
    api.wordLevels(speechLang).then((l) => (levels = l || {}), () => {});
  });
  $effect(() => {
    void loaded.length;
    if (!settings.learning?.enabled || !article || typeof CSS === 'undefined' || !CSS.highlights) return;
    const learning = new Set(Object.entries(levels).filter(([, v]) => v !== 'KNOWN').map(([w]) => w));
    const ranges = [];
    let count = 0;
    if (learning.size) {
      const walker = document.createTreeWalker(article, NodeFilter.SHOW_TEXT);
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        for (const m of n.textContent.matchAll(/\p{L}+/gu)) {
          if (!learning.has(m[0].toLowerCase())) continue;
          const r = new Range();
          r.setStart(n, m.index);
          r.setEnd(n, m.index + m[0].length);
          ranges.push(r);
          count++;
        }
      }
    }
    learningHere = count;
    CSS.highlights.set('yk-learning', new Highlight(...ranges));
    return () => CSS.highlights.delete('yk-learning');
  });

  function pressDown(e) {
    if (!settings.learning?.enabled || e.button > 0) return;
    pressAt = { x: e.clientX, y: e.clientY };
    clearTimeout(pressTimer);
    pressTimer = setTimeout(() => { const w = wordAt(pressAt.x, pressAt.y); if (w) wordPick = w; }, 500);
  }
  function pressMove(e) { if (pressAt && Math.hypot(e.clientX - pressAt.x, e.clientY - pressAt.y) > 10) clearTimeout(pressTimer); }
  function pressEnd() { clearTimeout(pressTimer); pressAt = null; }
  function rightClick(e) {
    if (!settings.learning?.enabled) return;
    const w = wordAt(e.clientX, e.clientY);
    if (w) { e.preventDefault(); wordPick = w; }
  }

  function toggleSpeech() {
    if (!speaking) {
      speechPaused = false;
      // Phones stop web pages when the screen goes off; the app keeps reading (said once).
      if (/Android|iPhone|iPad/i.test(navigator.userAgent) && !sessionStorage.getItem('speech.tip')) {
        sessionStorage.setItem('speech.tip', '1');
        notify('Con la pantalla apagada la lectura es más fiable en la app de Android');
      }
      loadVoices().then(() => { const v = visibleParagraph(); speak(v?.c ?? 0, v?.i ?? 0); });
      return;
    }
    if (speechPaused) { voice.resume(); speechPaused = false; }
    else { voice.pause(); speechPaused = true; }
  }

  function stopSpeech() {
    if (!canSpeak) return;
    speaking = null;
    speechPaused = false;
    voice.stop();
  }

  /** A short sample of a voice, from the settings. */
  async function tryVoice(id) {
    stopSpeech();
    const sample = speechLang === 'en' ? 'This is how I will read your novels. Do you like my voice?' : 'Así leeré tus novelas. ¿Te gusta mi voz?';
    try {
      voice.naturalFailed = false;
      await voice.playNatural({ text: sample, voice: id }, voice.token);
    } catch (e) { if (e.message !== 'stopped') notify('No se pudo probar la voz: ' + e.message); }
  }
  const genderName = (g) => (g === 'f' ? 'mujer' : g === 'm' ? 'hombre' : '');
  const voiceLabel = (v) => v.label + ' · ' + v.region + (v.gender ? ' · ' + genderName(v.gender) : '');

  // Auto-scroll: a steady glide, stopped by a tap.
  function toggleScroll() {
    scrolling = !scrolling;
    if (!scrolling) { cancelAnimationFrame(scrollFrame); return; }
    ui = false;
    let last = performance.now();
    let carry = 0;
    const step = (t) => {
      if (!scrolling) return;
      carry += ((t - last) / 1000) * (settings.novel.autoSpeed || 30);
      last = t;
      const px = Math.floor(carry);
      if (px > 0) { window.scrollBy(0, px); carry -= px; }
      if (innerHeight + scrollY >= document.documentElement.scrollHeight - 2 && !loadingNext) { scrolling = false; return; }
      scrollFrame = requestAnimationFrame(step);
    };
    scrollFrame = requestAnimationFrame(step);
  }

  // Bookmarks: a paragraph of a chapter, with its first words as a reminder.
  async function toggleBookmark() {
    const v = visibleParagraph();
    if (!v || !item) return;
    const chapter = loaded[v.c].chapter;
    const existing = bookmarks.find((b) => b.chapterId === chapter.id && b.position === v.i);
    const row = existing
      ? { ...existing, deleted: true }
      : { sourceId, mediaId, chapterId: chapter.id, chapterTitle: chapter.label, position: v.i, label: loaded[v.c].paragraphs[v.i].slice(0, 120), createdAt: Date.now(), deleted: false };
    bookmarks = existing ? bookmarks.filter((b) => b !== existing) : [row, ...bookmarks];
    notify(existing ? 'Marcador quitado' : 'Marcador guardado');
    await api.saveBookmark(row).catch((e) => notify(e.message));
  }

  function openBookmark(b) {
    indexOpen = false;
    const c = loaded.findIndex((l) => l.chapter.id === b.chapterId);
    if (c >= 0) { article?.querySelector(`p[data-c="${c}"][data-i="${b.position}"]`)?.scrollIntoView({ block: 'start' }); return; }
    stopSpeech(); flush();
    navigate(reader('novel', sourceId, mediaId, b.chapterId) + '?p=' + b.position, { replace: true });
  }

  // Search in what is loaded.
  function showHit(delta = 0) {
    if (!hits.length) return;
    findAt = (findAt + delta + hits.length) % hits.length;
    const [c, i] = hits[findAt];
    article?.querySelector(`p[data-c="${c}"][data-i="${i}"]`)?.scrollIntoView({ block: 'center' });
  }
  $effect(() => { findQuery; findAt = 0; if (hits.length) tick().then(() => showHit(0)); });

  /** A paragraph split around the searched text, for highlighting. */
  function parts(text) {
    const q = findQuery.trim();
    if (q.length < 2) return [[text, false]];
    const lower = text.toLowerCase(), needle = q.toLowerCase();
    const out = [];
    let from = 0, at;
    while ((at = lower.indexOf(needle, from)) >= 0) {
      if (at > from) out.push([text.slice(from, at), false]);
      out.push([text.slice(at, at + q.length), true]);
      from = at + q.length;
    }
    if (from < text.length) out.push([text.slice(from), false]);
    return out;
  }

  function key(e) {
    if (e.target.closest?.('input, select, textarea')) return;
    if ((e.ctrlKey || e.metaKey) && e.key === 'f') { e.preventDefault(); findOpen = true; return; }
    if (e.key === 'Escape' && !options && !indexOpen && !findOpen) back(mediaHref(sourceId, mediaId));
    if (e.key === 'ArrowRight' && e.altKey && nextOf(current)) go(nextOf(current));
    if (e.key === 'ArrowLeft' && e.altKey && prevOf(current)) go(prevOf(current));
  }

  function tap() {
    if (scrolling) { scrolling = false; return; }
    ui = !ui;
  }

  onMount(async () => {
    loadVoices();
    clockTimer = setInterval(() => (now = clock()), 15_000);
    try {
      const [m, list, progress] = await Promise.all([api.media(sourceId, mediaId), api.chapters(sourceId, mediaId), progressMap(sourceId, mediaId)]);
      item = m;
      chapters = list.chapters;
      current = chapters.find((c) => c.id === chapterId) || chapters[0];
      if (!current) { error = 'No hay capítulos'; return; }
      api.bookmarks(sourceId, mediaId).then((b) => (bookmarks = b)).catch(() => {});
      await loadChapter(current);
      if (loaded[0]?.error) { error = loaded[0].error; return; }
      await tick();
      const asked = parseInt(route.query.get('p') || '', 10);
      if (asked >= 0) {
        article?.querySelector(`p[data-c="0"][data-i="${asked}"]`)?.scrollIntoView({ block: 'start' });
      } else {
        // Back where the user left it.
        const fraction = progress[current.id]?.read ? 0 : progress[current.id]?.fraction || 0;
        const section = article?.querySelector('section[data-c="0"]');
        if (section && fraction > 0) window.scrollTo(0, fraction * (section.getBoundingClientRect().height - innerHeight));
      }
      if (sessionStorage.getItem('novel.autospeak') && canSpeak) { sessionStorage.removeItem('novel.autospeak'); speak(0, 0); }
      setTimeout(() => (ui = false), 2500);
    } catch (e) {
      error = e.message;
    }
  });

  onDestroy(() => { stopPresence(); leaveImmersive(); clearInterval(clockTimer); cancelAnimationFrame(scrollFrame); scrolling = false; flush(); stopSpeech(); });

  function set(key, value) { settings.novel[key] = value; saveSettings(); }
  function size(delta) { set('size', Math.min(32, Math.max(13, settings.novel.size + delta))); }
  function line(delta) { set('line', Math.round(Math.min(2.4, Math.max(1.2, settings.novel.line + delta)) * 10) / 10); }
</script>

<svelte:window onscroll={onScroll} onkeydown={key} onpointerup={() => { if (settings.reader.fullscreen) enterFullscreenQuietly(); }} />

<div class="novel" style:background={colors[0]} style:color={colors[1]} onclick={tap} role="presentation">
  {#if error}
    <div class="center"><p>{error}</p><button class="btn tonal" onclick={() => back(mediaHref(sourceId, mediaId))}>Volver</button></div>
  {:else if !loaded[0]?.paragraphs.length}
    <div class="center"><div class="spinner"></div></div>
  {:else}
    <article bind:this={article} onpointerdown={pressDown} onpointermove={pressMove} onpointerup={pressEnd} onpointercancel={pressEnd} oncontextmenu={rightClick} style:font-size="{settings.novel.size}px" style:line-height={settings.novel.line} style:max-width="{WIDTHS[settings.novel.width] || 720}px" class:serif={settings.novel.font === 'serif'} class:justify={settings.novel.justify !== false}>
      {#each loaded as l, c (l.chapter.id)}
        <section data-c={c}>
          <h1>{l.chapter.label}{l.chapter.name ? ' · ' + l.chapter.name : ''}</h1>
          {#if l.error}<p class="error-text">{l.error}</p>{/if}
          {#if !l.paragraphs.length && !l.error}<div class="center"><div class="spinner"></div></div>{/if}
          {#each l.paragraphs as p, i}
            <p data-c={c} data-i={i} class:speaking={speaking?.c === c && speaking?.i === i} class:hit-now={hits[findAt]?.[0] === c && hits[findAt]?.[1] === i}>
              {#each parts(p) as [text, match]}{#if match}<mark>{text}</mark>{:else}{text}{/if}{/each}
            </p>
          {/each}
        </section>
      {/each}
      <nav class="end">
        {#if !infinite || !nextOf(loaded.at(-1).chapter)}
          {#if prevOf(loaded[0].chapter)}<button class="btn outline" onclick={(e) => { e.stopPropagation(); go(prevOf(loaded[0].chapter)); }}>Anterior</button>{:else}<span></span>{/if}
          {#if nextOf(loaded.at(-1).chapter)}<button class="btn primary" onclick={(e) => { e.stopPropagation(); go(nextOf(loaded.at(-1).chapter)); }}>Siguiente: {nextOf(loaded.at(-1).chapter).label}</button>{:else}<span class="muted">Último capítulo</span>{/if}
        {:else}
          <span class="muted small">Cargando el siguiente capítulo...</span>
        {/if}
      </nav>
    </article>
  {/if}
</div>

{#if ui}
  <header class="top">
    <button class="icon-btn" onclick={() => back(mediaHref(sourceId, mediaId))} aria-label="Volver"><Icon name="back" /></button>
    <div class="titles"><div class="t1">{item?.title ?? ''}</div><div class="t2">{current?.label ?? ''}{#if settings.learning?.enabled && learningHere} · {learningHere} {learningHere === 1 ? 'palabra' : 'palabras'} que estás aprendiendo{/if}</div></div>
    <button class="icon-btn" onclick={() => (findOpen = !findOpen)} aria-label="Buscar en el capítulo" title="Buscar (Ctrl+F)"><Icon name="search" /></button>
    <button class="icon-btn" onclick={toggleBookmark} aria-label="Marcador" title="Marcador en este párrafo"><Icon name="bookmark" /></button>
    {#if canSpeak}<button class="icon-btn" class:active={speaking} onclick={toggleSpeech} aria-label="Leer en voz alta" title="Leer en voz alta"><Icon name="speech" /></button>{/if}
    <button class="icon-btn" onclick={() => (indexOpen = true)} aria-label="Índice" title="Índice"><Icon name="list" /></button>
    {#if canFullscreen}<button class="icon-btn" class:active={screen.full} onclick={toggleFullscreen} aria-label="Pantalla completa" title="Pantalla completa"><Icon name="fullscreen" /></button>{/if}
    <button class="icon-btn" onclick={() => (options = true)} aria-label="Texto" title="Texto"><Icon name="text" /></button>
  </header>
{:else}
  <div class="mini-clock">{now}</div>
{/if}

{#if findOpen}
  <div class="find">
    <!-- svelte-ignore a11y_autofocus -->
    <input type="search" placeholder="Buscar en el texto" bind:value={findQuery} autofocus onkeydown={(e) => { if (e.key === 'Enter') showHit(e.shiftKey ? -1 : 1); if (e.key === 'Escape') findOpen = false; }} />
    <span class="small">{hits.length ? `${findAt + 1}/${hits.length}` : findQuery.trim().length > 1 ? '0' : ''}</span>
    <button class="icon-btn" onclick={() => showHit(-1)} aria-label="Anterior" disabled={!hits.length}>‹</button>
    <button class="icon-btn" onclick={() => showHit(1)} aria-label="Siguiente" disabled={!hits.length}>›</button>
    <button class="icon-btn" onclick={() => { findOpen = false; findQuery = ''; }} aria-label="Cerrar"><Icon name="close" size={20} /></button>
  </div>
{/if}

{#if scrolling && !speaking}
  <SpeedControl value={settings.novel.autoSpeed || 30} base={30} min={5} max={300} onchange={(v) => set('autoSpeed', v)} onstop={toggleScroll} />
{:else if speaking}
  <div class="speech-bar">
    {#if speaking}
      <button class="icon-btn" onclick={toggleSpeech} aria-label={speechPaused ? 'Seguir' : 'Pausar'}><Icon name={speechPaused ? 'play' : 'pause'} /></button>
      <span class="small grow">Leyendo en voz alta · {loaded[speaking.c]?.chapter.label} · párrafo {speaking.i + 1}</span>
      <button class="icon-btn" onclick={stopSpeech} aria-label="Parar"><Icon name="close" /></button>
    {:else}
      <span class="small grow">Desplazamiento automático · toca el texto para parar</span>
      <button class="icon-btn" onclick={toggleScroll} aria-label="Parar"><Icon name="pause" /></button>
    {/if}
  </div>
{/if}

<Sheet bind:open={indexOpen} title="Índice">
  <div class="opt-row wrap" style="margin-top:0">
    <button class="chip" class:on={indexTab === 'chapters'} onclick={() => (indexTab = 'chapters')}>Capítulos</button>
    <button class="chip" class:on={indexTab === 'marks'} onclick={() => (indexTab = 'marks')}>Marcadores · {bookmarks.length}</button>
  </div>
  {#if indexTab === 'chapters'}
    <input class="field" type="search" placeholder="Buscar capítulo" bind:value={indexQuery} />
    <div style="margin-top:8px">
      {#each indexList as c (c.id)}
        <button class="list-item idx" class:here={c.id === current?.id} onclick={() => { indexOpen = false; if (c.id !== current?.id) go(c); }}>
          <span class="grow">{c.label}{#if c.name} <span class="muted small">{c.name}</span>{/if}</span>
        </button>
      {/each}
    </div>
  {:else if !bookmarks.length}
    <p class="muted">Sin marcadores. El botón del marcador guarda el párrafo que tienes arriba en pantalla.</p>
  {:else}
    {#each bookmarks as b (b.chapterId + '|' + b.position)}
      <button class="list-item idx" onclick={() => openBookmark(b)}>
        <span class="grow">{b.chapterTitle} · párrafo {b.position + 1}{#if b.label}<span class="small muted mark">{b.label}</span>{/if}</span>
      </button>
    {/each}
  {/if}
  {#if current && item}
    <button class="btn outline block" style="margin-top:14px" onclick={() => { indexOpen = false; downloadChapters({ sourceId, mediaId, title: item.title, chapterIds: [current.id] }, (m) => notify('No se pudo descargar: ' + m)); }}>
      <Icon name="download" size={18} /> Descargar {current.label} como EPUB
    </button>
  {/if}
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
  <div class="small muted">Letra</div>
  <div class="opt-row wrap">
    <button class="chip" class:on={settings.novel.font === 'serif'} onclick={() => set('font', 'serif')}>Serif</button>
    <button class="chip" class:on={settings.novel.font !== 'serif'} onclick={() => set('font', 'sans')}>Sans</button>
  </div>
  <div class="small muted">Ancho del texto</div>
  <div class="opt-row wrap">
    {#each [['narrow', 'Estrecho'], ['normal', 'Normal'], ['wide', 'Ancho']] as [k, label]}
      <button class="chip" class:on={(settings.novel.width || 'normal') === k} onclick={() => set('width', k)}>{label}</button>
    {/each}
  </div>
  {#if canFullscreen}<label class="toggle"><span class="grow">Pantalla completa al leer</span><input type="checkbox" checked={!!settings.reader.fullscreen} onchange={(e) => { settings.reader.fullscreen = e.target.checked; saveSettings(); if (e.target.checked) enterFullscreen(); }} /></label>{/if}
  <label class="toggle"><span class="grow">Texto justificado</span><input type="checkbox" checked={settings.novel.justify !== false} onchange={(e) => set('justify', e.target.checked)} /></label>
  <label class="toggle"><span class="grow">Seguir con el siguiente capítulo al llegar al final</span><input type="checkbox" checked={infinite} onchange={(e) => set('infinite', e.target.checked)} /></label>
  <div class="opt-row"><span class="grow">Desplazamiento automático</span><button class="btn outline" onclick={() => { options = false; toggleScroll(); }}>{scrolling ? 'Parar' : 'Empezar'}</button></div>
  <input class="range" type="range" min="5" max="300" step="5" value={settings.novel.autoSpeed || 30} oninput={(e) => set('autoSpeed', +e.target.value)} aria-label="Velocidad del desplazamiento" />
  {#if canSpeak}
    <div class="small muted" style="margin-top:12px">Leer en voz alta</div>
    {#if speechVoices.natural}
      <div class="opt-row wrap">
        <button class="chip" class:on={settings.novel.ttsEngine !== 'browser'} onclick={() => { set('ttsEngine', 'natural'); voice.naturalFailed = false; }}>Voces naturales</button>
        <button class="chip" class:on={settings.novel.ttsEngine === 'browser'} onclick={() => set('ttsEngine', 'browser')}>Voces del navegador</button>
      </div>
    {/if}
    {#if speechVoices.natural && settings.novel.ttsEngine !== 'browser'}
      {@const mine = speechVoices.server.filter((v) => v.lang === speechLang)}
      {@const vs = voiceSettings(speechLang)}
      {#each [['narrator', 'Narrador'], ['female', 'Personajes femeninos'], ['male', 'Personajes masculinos']] as [role, label]}
        <div class="voice-row">
          <span class="small grow">{label}</span>
          <select class="field voice-select" value={vs[role]} onchange={(e) => { setVoice(speechLang, role, e.target.value); saveSettings(); }}>
            {#each mine as v (v.id)}<option value={v.id}>{voiceLabel(v)}</option>{/each}
          </select>
          <button class="icon-btn" onclick={() => tryVoice(vs[role])} aria-label="Probar la voz" title="Probar"><Icon name="play" size={20} /></button>
        </div>
      {/each}
      <p class="small muted">Voces neuronales que funcionan en tu servidor. La primera vez que se usa una, el servidor la descarga (unos segundos).</p>
    {:else}
      {@const mine = speechVoices.browser.filter((v) => v.lang.toLowerCase().startsWith(speechLang))}
      {@const bv = settings.novel.browserVoices?.[speechLang] || {}}
      {#each [['narrator', 'Narrador'], ['dialogue', 'Diálogos']] as [role, label]}
        <div class="voice-row">
          <span class="small grow">{label}</span>
          <select class="field voice-select" value={bv[role] || ''} onchange={(e) => { settings.novel.browserVoices = { ...(settings.novel.browserVoices || {}), [speechLang]: { ...bv, [role]: e.target.value } }; saveSettings(); }}>
            <option value="">{role === 'narrator' ? 'La mejor disponible' : 'La misma que el narrador'}</option>
            {#each mine as v (v.name)}<option value={v.name}>{v.name}</option>{/each}
          </select>
        </div>
      {/each}
      <p class="small muted">Las más naturales salen primero. En Edge, las «Natural»; en iPhone, descarga voces «Mejorada» o «Premium» en Ajustes › Accesibilidad › Contenido leído.</p>
    {/if}
    <label class="toggle"><span class="grow">Voces por personaje (los diálogos con otra voz, de mujer o de hombre según quién habla)</span><input type="checkbox" checked={settings.novel.ttsCharacters !== false} onchange={(e) => set('ttsCharacters', e.target.checked)} /></label>
    <div class="opt-row"><span class="grow">Velocidad de la voz</span>
      {#each [0.8, 1, 1.15, 1.3, 1.5, 1.75] as r}<button class="chip" class:on={(settings.novel.speechRate || 1) === r} onclick={() => set('speechRate', r)}>{r}x</button>{/each}
    </div>
    <label class="toggle"><span class="grow">Seguir leyendo en voz alta el siguiente capítulo</span><input type="checkbox" checked={!!settings.novel.speechNext} onchange={(e) => set('speechNext', e.target.checked)} /></label>
  {/if}
</Sheet>

<WordSheet bind:pick={wordPick} />

<style>
  :global(::highlight(yk-learning)) { text-decoration: underline dotted color-mix(in srgb, var(--c-accent) 70%, transparent); text-underline-offset: 3px; }
  .novel { min-height: 100vh; transition: background 0.3s, color 0.3s; }
  article { margin: 0 auto; padding: calc(var(--safe-top) + 72px) 22px calc(var(--safe-bottom) + 96px); }
  article.serif { font-family: Georgia, 'Iowan Old Style', 'Palatino Linotype', serif; }
  section + section { margin-top: 3em; padding-top: 2em; border-top: 1px solid color-mix(in srgb, currentColor 15%, transparent); }
  h1 { font-size: 1.3em; margin: 0 0 1em; }
  p { margin: 0 0 1em; hyphens: auto; border-radius: 6px; transition: background 0.3s; }
  .justify p { text-align: justify; }
  p.speaking { background: color-mix(in srgb, var(--primary) 18%, transparent); }
  p.hit-now { outline: 2px solid var(--primary); outline-offset: 4px; }
  mark { background: color-mix(in srgb, var(--primary) 45%, transparent); color: inherit; border-radius: 3px; }
  .end { display: flex; justify-content: space-between; gap: 12px; margin-top: 2em; font-size: 16px; font-family: var(--font); }
  .top { position: fixed; top: 0; left: 0; right: 0; z-index: 20; display: flex; align-items: center; gap: 2px; padding: calc(var(--safe-top) + 4px) 8px 6px; background: #0c0c0f; color: #fff; border-bottom: 1px solid #ffffff14; }
  .top .icon-btn { color: #fff; }
  .top .active { color: var(--primary); }
  .titles { flex: 1; min-width: 0; }
  .t1 { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .t2 { font-size: 0.8rem; color: var(--text-dim); }
  .mini-clock { position: fixed; top: calc(var(--safe-top) + 8px); right: 12px; z-index: 20; padding: 2px 8px; border-radius: 999px; background: #0006; color: #fffd; font-size: 0.75rem; pointer-events: none; }
  .find { position: fixed; top: calc(var(--safe-top) + 60px); left: 50%; transform: translateX(-50%); z-index: 25; width: min(560px, calc(100vw - 24px)); display: flex; align-items: center; gap: 4px; padding: 4px 4px 4px 14px; border-radius: 16px; background: #111e; color: #fff; box-shadow: 0 6px 20px #0008; }
  .find input { flex: 1; min-width: 0; background: none; border: 0; outline: none; color: #fff; font-size: 1rem; height: 40px; }
  .find .icon-btn { color: #fff; font-size: 1.4rem; }
  .voice-row { display: flex; align-items: center; gap: 8px; margin: 6px 0; }
  .voice-select { flex: 0 1 60%; min-width: 0; margin: 0; }
  .speech-bar { position: fixed; left: 50%; transform: translateX(-50%); bottom: calc(var(--safe-bottom) + 12px); z-index: 25; width: min(560px, calc(100vw - 24px)); display: flex; align-items: center; gap: 8px; padding: 4px 8px 4px 12px; border-radius: 16px; background: #111d; color: #fff; backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); box-shadow: 0 6px 20px #0008; }
  .speech-bar .icon-btn { color: #fff; }
  .grow { flex: 1; min-width: 0; }
  .opt-row { display: flex; align-items: center; gap: 10px; margin: 10px 0 14px; }
  .opt-row.wrap { flex-wrap: wrap; gap: 8px; }
  .val { min-width: 32px; text-align: center; }
  .swatch { display: inline-grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; margin-right: 6px; font-size: 0.7rem; font-weight: 700; border: 1px solid var(--outline); }
  .toggle { display: flex; align-items: center; gap: 12px; padding: 8px 0; }
  .range { width: 100%; accent-color: var(--primary); margin: 0 0 14px; }
  .idx { width: 100%; text-align: left; }
  .idx.here { color: var(--primary); font-weight: 600; }
  .mark { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  @media (max-width: 420px) { .top { gap: 0; } .top .icon-btn { width: 40px; } }
</style>
