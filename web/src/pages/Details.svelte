<script>
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, navigate, back, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { features, session, sourceById, inLibrary, toggleLibrary, notify, seenItem, READING_STATUSES, setReadingStatus, statusOf, categories, setCategories, editCategory } from '../lib/store.svelte.js';
  import { downloadChapters } from '../lib/api.js';
  import { coverSeed, themeFor, styleOf } from '../lib/cover.js';
  import { TYPE_NAMES, TYPE_COLORS, parseStatus, statusName, score, monthYear, chapterNoun } from '../lib/format.js';
  import { progressMap, continueTarget, markThrough, markOne } from '../lib/progress.js';
  import { openRelated } from '../lib/open.svelte.js';
  import Icon from '../components/Icon.svelte';
  import MediaCard from '../components/MediaCard.svelte';
  import Sheet from '../components/Sheet.svelte';
  import Row from '../components/Row.svelte';
  import WorkSocial from '../components/WorkSocial.svelte';
  import ForumThread from '../components/ForumThread.svelte';
  import { themeState } from '../lib/theme.svelte.js';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const source = $derived(sourceById(sourceId));
  const anime = $derived(source?.kind === 'anime');

  // What the list already knew, shown at once; the full page replaces it when it arrives.
  let item = $state(seenItem(sourceId, mediaId));
  let error = $state('');
  let list = $state({ loading: true, chapters: [], issues: [], abandoned: false, lastUpdate: null, error: '' });
  let progress = $state({});
  let meta = $state(null);
  let metaLoading = $state(true);
  let similar = $state(null);
  let tab = $state(Number(sessionStorage.getItem('details.tab')) || 0);
  /** The work's forum thread: undefined while unknown, null when nobody has written yet. */
  let forumThread = $state(undefined);
  $effect(() => {
    if (tab !== 2 || forumThread !== undefined || !work?.key) return;
    api.forumWork(sourceId, mediaId).then((r) => (forumThread = r.thread ?? null), () => (forumThread = null));
  });
  let descending = $state(true);
  let expanded = $state(false);
  let theme = $state({});
  let menu = $state(false);
  let altOpen = $state(false);
  let alternatives = $state(null);
  let issuesOpen = $state(false);
  /** Once the page moves, the top bar turns solid: chapters must never show through it. */
  let scrolled = $state(false);
  /** 0 at the top, 1 once the page has moved a little: the bar fills in gradually, never in one jump. */
  let fill = $state(0);
  function onScroll() {
    fill = Math.min(1, Math.max(0, scrollY / 100));
    scrolled = fill >= 1;
  }

  let chapterQuery = $state('');
  let catsOpen = $state(false);
  const onPhone = /iPhone|iPad|Android/i.test(navigator.userAgent);
  let newCat = $state('');
  let dlOpen = $state(false);
  let dlFrom = $state(0);
  let dlTo = $state(0);
  let marksOpen = $state(false);
  let marks = $state(null);
  const maxDownload = $derived(session.user?.guest ? 3 : 100);

  // ---------- The work behind this page (AniList): banner, other names, fillers, where to continue ----------
  let work = $state(null);
  /** Episode number -> 'FILLER' | 'MIXED' (AnimeFillerList); only for anime. */
  let fillers = $state({});
  let hideFiller = $state(false);
  /** Names of chapters ahead of where you are stay blurred until hovered or focused. */
  let shield = $state(safeGet('spoilerShield') !== '0');
  let where = $state(null);
  let pointOpen = $state(false);
  let point = $state({ target: '', episode: '', chapter: '', volume: '' });
  function safeGet(k) { try { return localStorage.getItem(k); } catch { return null; } }
  function toggleShield() { shield = !shield; try { localStorage.setItem('spoilerShield', shield ? '1' : '0'); } catch { /* private mode */ } }
  const fillerOf = (c) => (anime && c.number != null ? fillers[Math.floor(c.number)] : undefined);
  const fillerCount = $derived(Object.values(fillers).filter((k) => k === 'FILLER').length);
  /** Last watched/read chapter number (by number, not position). */
  const lastDone = $derived(list.chapters.reduce((m, c) => (progress[c.id]?.read && c.number > m ? c.number : m), 0));
  /** Related works on the other side (manga/novel for an anime and the other way round) that AniList can name. */
  const counterparts = $derived((meta?.relations || []).filter((r) => r.anilistId && (anime ? r.type !== 'ANIME' : r.type === 'ANIME')));
  const relationByKey = (key) => (meta?.relations || []).find((r) => 'al:' + r.anilistId === key);
  async function loadWhere() {
    if (!work?.key) return;
    where = await api.workContinue(work.key, anime && lastDone ? Math.floor(lastDone) : undefined).catch(() => null);
  }
  async function savePoint(e) {
    e.preventDefault();
    const target = 'al:' + point.target;
    const chapter = point.chapter === '' ? null : +point.chapter;
    const volume = point.volume === '' ? null : +point.volume;
    const body = anime
      ? { animeKey: work.key, targetKey: target, untilEpisode: +point.episode, chapter, volume }
      : { animeKey: target, targetKey: work.key, untilEpisode: +point.episode, chapter, volume };
    try { await api.setAdaptation(body); notify('Guardado; lo verán todos los usuarios'); pointOpen = false; loadWhere(); } catch (err) { notify(err.message); }
  }
  function openPoint() {
    point = { target: String(counterparts[0]?.anilistId ?? ''), episode: anime && lastDone ? String(Math.floor(lastDone)) : '', chapter: '', volume: '' };
    pointOpen = true;
  }

  function toggleCat(name) {
    const current = saved?.categories || [];
    setCategories(sourceId, mediaId, current.includes(name) ? current.filter((c) => c !== name) : [...current, name]);
  }
  async function createCat(e) {
    e.preventDefault();
    const name = newCat.trim();
    if (!name) return;
    await editCategory({ new: name, sortOrder: categories.list.length });
    toggleCat(name);
    newCat = '';
  }

  function openDownloads() {
    menu = false;
    dlFrom = 0;
    dlTo = Math.min(list.chapters.length, maxDownload) - 1;
    dlOpen = true;
  }
  function download(chapters) {
    if (!chapters.length) { notify('No hay nada que descargar'); return; }
    if (chapters.length > maxDownload) { notify(`Como mucho ${maxDownload} capítulos por descarga`); return; }
    downloadChapters({ sourceId, mediaId, title: item?.title, chapterIds: chapters.map((c) => c.id) }, (msg) => notify('No se pudo descargar: ' + msg));
    notify(chapters.length === 1 ? 'Descargando...' : `Preparando ${chapters.length} capítulos; tu navegador lo guardará al terminar de llegar`);
    dlOpen = false;
  }
  const unreadChapters = $derived(list.chapters.filter((c) => !progress[c.id]?.read));

  async function openMarks() {
    menu = false;
    marksOpen = true;
    marks = await api.bookmarks(sourceId, mediaId).catch(() => []);
  }
  const saved = $derived(inLibrary(sourceId, mediaId));
  /** "120", "cap 120" or part of a chapter's name. */
  const matches = (c, q) => {
    const digits = q.replace(/[^\d.]/g, '');
    return (digits && String(c.number) === digits) || (c.label + ' ' + (c.name || '')).toLowerCase().includes(q);
  };
  const shown = $derived.by(() => {
    const q = chapterQuery.trim().toLowerCase();
    const all = descending ? [...list.chapters].reverse() : list.chapters;
    const visible = hideFiller ? all.filter((c) => fillerOf(c) !== 'FILLER') : all;
    return q ? visible.filter((c) => matches(c, q)) : visible;
  });
  const altNames = $derived([...new Set([...(work?.altTitles || []), ...(item?.altTitles || [])])].filter((n) => n && n.toLowerCase() !== item?.title?.toLowerCase()));
  /** Chapters after this number are "ahead of you": their names may spoil. */
  const ahead = $derived(Math.max(lastDone, target ? (target.chapter.number ?? 0) : 0));
  const whereTarget = $derived(where?.next?.length ? relationByKey(where.next[0].targetKey) : null);
  const whereText = $derived.by(() => {
    if (!where) return '';
    if (anime && where.next?.length) {
      const n = where.next[0];
      const name = whereTarget?.title || 'la obra original';
      const at = n.chapter != null ? `el capítulo ${Math.floor(n.chapter)}` : `el volumen ${n.volume}`;
      return `Después del episodio ${Math.floor(lastDone)}, la historia sigue en ${at} de ${name}.`;
    }
    if (!anime && where.animatedUpTo != null) {
      const upTo = Math.floor(where.animatedUpTo);
      return lastDone >= upTo ? `Ya has pasado lo que adapta el anime (hasta el capítulo ${upTo}).` : `El anime adapta hasta el capítulo ${upTo}.`;
    }
    return '';
  });
  const gapAfter = $derived(Object.fromEntries(list.issues.filter((i) => i.afterChapterId).map((i) => [i.afterChapterId, i])));
  const target = $derived(continueTarget(list.chapters, progress));
  const readCount = $derived(list.chapters.filter((c) => progress[c.id]?.read).length);
  const status = $derived.by(() => {
    const own = parseStatus(item?.status);
    return own || meta?.officialStatus || null;
  });
  const genres = $derived(meta?.genres?.length ? meta.genres : item?.genres || []);
  const synopsis = $derived(item?.description || meta?.synopsis || '');
  /** Width of the source's own cover once loaded: anime sites give ~250 px ones, blurry at poster size. */
  let sourceCoverWidth = $state(0);
  /** Anime: the database's big cover is waited for (briefly) instead of showing the small one and swapping. */
  let coverWaitOver = $state(false);
  const poster = $derived.by(() => {
    if (anime) return metaLoading && !coverWaitOver ? null : meta?.cover || item?.cover;
    return meta?.cover && sourceCoverWidth && sourceCoverWidth < 400 ? meta.cover : item?.cover;
  });
  let posterReady = $state(false);
  function posterLoaded(e) {
    posterReady = true;
    if (e.currentTarget.getAttribute('src') === item?.cover) sourceCoverWidth = e.currentTarget.naturalWidth;
  }

  onMount(async () => {
    setTimeout(() => (coverWaitOver = true), 2500);
    // Everything at once: the chapter list and the database answer do not need the title page first.
    api.chapters(sourceId, mediaId).then(
      (r) => (list = { ...r, loading: false, error: '' }),
      (e) => (list = { ...list, loading: false, error: e.message })
    );
    progressMap(sourceId, mediaId).then((p) => (progress = p));
    api.metadata(sourceId, mediaId).then((m) => (meta = m), () => {}).finally(() => (metaLoading = false));
    api.work(sourceId, mediaId).then((w) => {
      work = w;
      if (anime && w?.title) api.fillers(w.title).then((f) => (fillers = f || {}), () => {});
    }, () => {});
    const early = item?.cover;
    if (early) coverSeed(early).then((seed) => (theme = themeFor(seed)));
    try {
      item = await api.media(sourceId, mediaId);
      if (item.cover !== early) coverSeed(item.cover).then((seed) => (theme = themeFor(seed)));
    } catch (e) {
      error = e.message;
    }
  });

  // Where to continue is asked again when the work arrives and whenever what you have seen moves on.
  $effect(() => { if (work?.key) { void lastDone; loadWhere(); } });

  $effect(() => {
    sessionStorage.setItem('details.tab', String(tab));
    if (tab === 1 && !similar) {
      similar = { loading: true, items: [] };
      api.similar(sourceId, mediaId).then((items) => (similar = { loading: false, items }), () => (similar = { loading: false, items: [] }));
    }
  });

  function open(chapter) { navigate(reader(source?.kind, sourceId, mediaId, chapter.id)); }

  async function toggleRead(chapter) {
    const read = !progress[chapter.id]?.read;
    progress[chapter.id] = { ...(progress[chapter.id] || {}), read, updatedAt: Date.now() };
    await markOne(item, chapter, read).catch((e) => notify(e.message));
  }

  async function markAllBefore(chapter, read) {
    menu = false;
    const rows = await markThrough(item, list.chapters, chapter, read).catch((e) => notify(e.message));
    rows?.forEach((r) => (progress[r.chapterId] = r));
  }

  async function openAlternatives() {
    menu = false;
    altOpen = true;
    if (alternatives) return;
    alternatives = { loading: true, items: [] };
    alternatives = { loading: false, items: await api.alternatives(sourceId, mediaId).catch(() => []) };
  }

  async function library() {
    if (!item) return;
    const added = await toggleLibrary(item);
    notify(added ? 'Añadida a tu biblioteca' : 'Quitada de tu biblioteca');
  }
</script>

<svelte:window onscroll={onScroll} />

<div class="details" class:has-banner={!!work?.banner} style={themeState.coverAccent ? styleOf(theme) : ''}>
  {#if work?.banner}
    <div class="banner"><img src={work.banner} alt="" decoding="async" /></div>
  {:else if item?.cover}<div class="backdrop" style:background-image="url('{item.cover}')"></div>{/if}
  <div class="wrap">
  <header class="bar" style:--fill={fill}>
    <button class="icon-btn" onclick={() => back('/')} aria-label="Volver"><Icon name="back" /></button>
    <span class="bar-title" aria-hidden={!scrolled}>{item?.title ?? ''}</span>
    <button class="icon-btn" onclick={() => (menu = true)} aria-label="Más opciones"><Icon name="more" /></button>
  </header>

  {#if error && !item}
    <div class="center"><p>{error}</p><button class="btn tonal" onclick={() => location.reload()}>Reintentar</button></div>
  {:else if !item}
    <div class="center"><div class="spinner"></div></div>
  {:else}
   <div class="layout">
    <section class="hero">
      <div class="hero-inner">
        {#if poster}
          {#key poster}<img class="poster" class:shown={posterReady} src={poster} alt="" onload={posterLoaded} onerror={() => (posterReady = true)} />{/key}
        {:else}
          <div class="poster skeleton" aria-hidden="true"></div>
        {/if}
        <div class="facts">
          <h1>{item.title}</h1>
          {#if altNames.length}<div class="alt-names" title={altNames.join(' · ')}>{altNames.slice(0, 2).join(' · ')}</div>{/if}
          {#if item.author}<div class="muted author">{item.author}</div>{/if}
          <div class="line small">
            <span class="dot" style:background={TYPE_COLORS[item.type]}></span>{TYPE_NAMES[item.type]}
            {#if status}<span class="sep">·</span>{statusName(status)}{/if}
          </div>
          <div class="line small">
            <span>{source?.name ?? sourceId}</span>
            {#if meta?.score != null}<span class="sep">·</span><span class="score"><Icon name="star" size={14} /> {score(meta.score)} <span class="muted">{meta.scoreSource}</span></span>{/if}
            {#if meta?.malScore != null}<span class="sep">·</span><span class="score"><Icon name="star" size={14} /> {score(meta.malScore)} <span class="muted">MAL</span></span>{/if}
          </div>
        </div>
      </div>

      {#if work?.sources?.length > 1}
        <button class="in-sources" onclick={openAlternatives}>
          <Icon name="grid" size={16} /> En {work.sources.length} fuentes: {work.sources.map((x) => x.name).join(', ')}
        </button>
      {/if}

      {#if list.abandoned}
        <div class="notice"><Icon name="hourglass" size={16} /> Posible abandono o hiato prolongado{#if list.lastUpdate} · último capítulo en {monthYear(list.lastUpdate)}{/if}</div>
      {/if}

      {#if target}
        <button class="btn primary block start" onclick={() => open(target.chapter)}>
          <Icon name="play" size={20} /> {target.started ? 'Continuar' : anime ? 'Empezar a ver' : 'Empezar a leer'} · {target.chapter.label}
        </button>
      {/if}
      <button class="btn tonal block lib" onclick={library}>
        <Icon name={saved ? 'heart' : 'heartOutline'} size={18} /> {saved ? 'En tu biblioteca' : 'Añadir a la biblioteca'}
      </button>
      {#if saved}
        <div class="statuses" role="radiogroup" aria-label="Estado de lectura">
          {#each READING_STATUSES as [key, label]}
            <button class="chip" role="radio" aria-checked={statusOf(saved) === key} class:on={statusOf(saved) === key} onclick={() => setReadingStatus(sourceId, mediaId, key)}>{anime && key === 'READING' ? 'Viendo' : label}</button>
          {/each}
        </div>
        <button class="cats" onclick={() => (catsOpen = true)}>
          <Icon name="library" size={16} />
          {saved.categories?.length ? saved.categories.join(' · ') : 'Añadir a una categoría'}
        </button>
      {/if}

      {#if synopsis}
        <p class="synopsis" class:open={expanded} onclick={() => (expanded = !expanded)}>{synopsis}</p>
        <button class="more" onclick={() => (expanded = !expanded)}>{expanded ? 'Mostrar menos' : 'Mostrar más'}</button>
        {#if !item.description && meta?.synopsis}<div class="small muted">Sinopsis de {meta.scoreSource || 'AniList'}</div>{/if}
      {/if}

      <WorkSocial {sourceId} {mediaId} title={item.title} cover={item.cover} />

      {#if genres.length}
        <div class="genres">{#each genres as g}<span class="pill">{g}</span>{/each}</div>
        {#if meta?.inferredGenres}<div class="small muted">Etiquetas deducidas de la sinopsis</div>{/if}
      {/if}
    </section>

    <div class="main">
    <div class="tabs" role="tablist">
      <button role="tab" class:on={tab === 0} aria-selected={tab === 0} onclick={() => (tab = 0)}>{anime ? 'Episodios' : 'Capítulos'} · {list.chapters.length}</button>
      <button role="tab" class:on={tab === 1} aria-selected={tab === 1} onclick={() => (tab = 1)}>Relacionadas</button>
      {#if features.flags.forum !== false && session.user && !session.user.guest}
        <button role="tab" class:on={tab === 2} aria-selected={tab === 2} onclick={() => (tab = 2)}>Foro</button>
      {/if}
    </div>

    {#if tab === 0}
      {#if whereText || (work?.key && counterparts.length && !session.user?.guest)}
        <div class="where">
          <Icon name="info" size={18} />
          <div class="grow">
            <div class="where-title">¿Dónde sigo?</div>
            {#if whereText}
              <div class="small">{whereText}</div>
            {:else}
              <div class="small muted">Nadie ha dicho todavía hasta dónde llega {anime ? 'este anime en el manga o la novela' : 'el anime en esta obra'}.</div>
            {/if}
            {#if whereTarget}<button class="link" onclick={() => openRelated(whereTarget)}>Abrir {whereTarget.title}</button>{/if}
          </div>
          {#if counterparts.length && !session.user?.guest}<button class="btn text" onclick={openPoint}>{whereText ? 'Corregir' : 'Añadir'}</button>{/if}
        </div>
      {/if}
      <div class="chapter-bar">
        <span class="small muted">{readCount ? `${readCount} ${anime ? 'vistos' : 'leídos'} de ${list.chapters.length}` : ''}</span>
        <span class="bar-actions">
          {#if list.chapters.length}<button class="btn text" onclick={openDownloads}><Icon name="download" size={18} /> <span class="lbl">Descargar</span></button>{/if}
          {#if fillerCount}<button class="btn text" class:on={hideFiller} aria-pressed={hideFiller} onclick={() => (hideFiller = !hideFiller)}>{hideFiller ? 'Mostrar relleno' : 'Ocultar relleno'}</button>{/if}
          <button class="btn text" aria-pressed={shield} onclick={toggleShield} title="Difumina los títulos de lo que aún no has visto"><Icon name="eye" size={18} /> <span class="lbl">{shield ? 'Sin spoilers' : 'Títulos visibles'}</span></button>
          <button class="btn text" onclick={() => (descending = !descending)}><Icon name="sort" size={18} /> <span class="lbl">{descending ? 'Más recientes' : 'Desde el principio'}</span></button>
        </span>
      </div>
      {#if list.chapters.length > 20}
        <div class="ch-search">
          <Icon name="search" size={18} />
          <input type="search" inputmode="search" placeholder={anime ? 'Ir al episodio...' : 'Ir al capítulo...'} bind:value={chapterQuery} aria-label="Buscar capítulo" />
        </div>
      {/if}
      {#if list.issues.length}
        <button class="integrity" onclick={() => (issuesOpen = !issuesOpen)}>
          <span class="warn"><Icon name="warning" size={16} /></span>
          <span>{list.issues.length === 1 ? list.issues[0].text : `La lista de la fuente tiene ${list.issues.length} irregularidades`}</span>
          {#if issuesOpen}
            <span class="issues small">
              {#if list.issues.length > 1}{#each list.issues as i}<span>• {i.text}</span>{/each}{/if}
              <span>Viene de la fuente, no de la app. Prueba «Buscar en otras fuentes» si falta algo.</span>
            </span>
          {/if}
        </button>
      {/if}
      {#if list.loading}
        <div class="center"><div class="spinner"></div></div>
      {:else if list.error && !list.chapters.length}
        <p class="pad error-text">{list.error}</p>
      {:else if !list.chapters.length}
        <p class="pad muted">No se encontraron {chapterNoun(2, anime)}.</p>
      {/if}
      <ol class="chapters">
        {#each shown as c (c.id)}
          {#if descending && gapAfter[c.id]}<li class="gap"><span>{gapAfter[c.id].text}</span></li>{/if}
          {@const p = progress[c.id]}
          <li class="chapter" class:read={p?.read}>
            <button class="ch-main" onclick={() => open(c)}>
              <span class="ch-label">{c.label}{#if c.name}<span class="ch-name" class:veil={shield && !p?.read && c.number > ahead}>{c.name}</span>{/if}{#if fillerOf(c) === 'FILLER'}<span class="kind filler">Relleno</span>{:else if fillerOf(c) === 'MIXED'}<span class="kind mixed">Mixto</span>{/if}</span>
              <span class="ch-meta">
                {#if c.date}<span class="date">{c.date}</span>{/if}
                {#if p && !p.read && anime && p.page > 0}<span class="accent">· Vas por el minuto {Math.floor(p.page / 60)}</span>
                {:else if p && !p.read && !anime && p.total > 0}<span class="accent">· Página {p.page + 1} de {p.total}</span>
                {:else if p && !p.read && p.fraction > 0.01}<span class="accent">· {Math.round(p.fraction * 100)}% leído</span>{/if}
              </span>
            </button>
            <button class="read-toggle" onclick={() => toggleRead(c)} aria-label={p?.read ? 'Marcar como no leído' : 'Marcar como leído'}>
              {#if p?.read}<Icon name="check" size={20} />{:else}<span class="ring"></span>{/if}
            </button>
          </li>
          {#if !descending && gapAfter[c.id]}<li class="gap"><span>{gapAfter[c.id].text}</span></li>{/if}
        {/each}
      </ol>
    {:else if tab === 2}
      <div class="forum-tab">
        {#if !work?.key || forumThread === undefined}
          <div class="spinner"></div>
        {:else if forumThread}
          <ForumThread threadId={forumThread.id} upTo={lastDone} {anime} />
        {:else}
          <ForumThread newFor={{ sourceId, mediaId, title: item.title }} upTo={lastDone} {anime} />
        {/if}
      </div>
    {:else}
      <div class="related">
        {#if metaLoading}
          <div class="spinner" style="margin:16px"></div>
        {:else if meta?.relations?.length}
          <div class="section-title"><h2>Universo de la obra</h2></div>
          <Row>
            {#each meta.relations as r, i (i)}
              <MediaCard title={r.title} cover={r.cover} type={r.type} showType subtitle={[r.relation, r.format].filter(Boolean).join(' · ')} onclick={() => openRelated(r)} />
            {/each}
          </Row>
        {/if}
        {#if similar?.loading}
          <div class="section-title"><h2>Similares</h2></div><div class="spinner" style="margin:8px 0"></div>
        {:else if similar?.items.length}
          <div class="section-title"><h2>Similares</h2></div>
          <Row>
            {#each similar.items as r, i (i)}
              <MediaCard title={r.title} cover={r.cover} type={r.type} showType subtitle={r.format} onclick={() => openRelated(r)} />
            {/each}
          </Row>
        {/if}
        <button class="chip" style="margin-top:16px" onclick={openAlternatives}><Icon name="search" size={18} /> Buscar en otras fuentes</button>
        {#if !metaLoading && !meta?.relations?.length && similar && !similar.loading && !similar.items.length}
          <p class="muted">No se conocen obras relacionadas.</p>
        {/if}
      </div>
    {/if}
    <div style="height:120px"></div>
    </div>
   </div>
  {/if}
  </div>
</div>

{#if target && item}
  <button class="fab" style={themeState.coverAccent ? styleOf(theme) : ''} onclick={() => open(target.chapter)}>
    <Icon name="play" />
    {target.started ? 'Continuar' : anime ? 'Empezar a ver' : 'Empezar a leer'} · {target.chapter.label}
  </button>
{/if}

<Sheet bind:open={menu} title="Opciones">
  <button class="list-item opt" onclick={openAlternatives}><Icon name="search" /> Buscar en otras fuentes</button>
  {#if list.chapters.length}
    <button class="list-item opt" onclick={openDownloads}><Icon name="download" /> Descargar en este dispositivo</button>
    {#if !anime}<button class="list-item opt" onclick={openMarks}><Icon name="bookmark" /> Marcadores</button>{/if}
    <button class="list-item opt" onclick={() => markAllBefore(list.chapters[list.chapters.length - 1], true)}><Icon name="check" /> Marcar todo como {anime ? 'visto' : 'leído'}</button>
    <button class="list-item opt" onclick={() => markAllBefore(list.chapters[list.chapters.length - 1], false)}><Icon name="close" /> Quitar todas las marcas</button>
  {/if}
</Sheet>

<Sheet bind:open={catsOpen} title="Categorías">
  {#if !categories.list.length}<p class="small muted" style="margin-top:0">Aún no tienes categorías. Crea una:</p>{/if}
  <div class="chipwrap">
    {#each categories.list as c (c.name)}
      <button class="chip" class:on={saved?.categories?.includes(c.name)} onclick={() => toggleCat(c.name)}>{c.name}</button>
    {/each}
  </div>
  <form class="row-form" onsubmit={createCat}>
    <input class="field" placeholder="Nueva categoría" bind:value={newCat} maxlength="100" />
    <button class="btn tonal" disabled={!newCat.trim()}>Crear y añadir</button>
  </form>
</Sheet>

<Sheet bind:open={dlOpen} title={anime ? 'Descargar episodios' : 'Descargar capítulos'}>
  <p class="small muted" style="margin-top:0">
    Se guarda en este {onPhone ? 'móvil' : 'ordenador'}, no en el servidor.
    {anime ? 'Los episodios se descargan como vídeo, de uno en uno.' : source?.kind === 'novel' ? 'Las novelas se descargan como libro EPUB (Libros del iPhone, Google Play Libros, Kindle...).' : 'Los cómics se descargan como CBZ, que abre cualquier lector de cómics; varios capítulos van juntos en un ZIP.'}
  </p>
  {#if anime}
    <div class="dl-list">
      {#each [...list.chapters].reverse() as c (c.id)}
        <div class="list-item">
          <span class="grow">{c.label}{#if c.name} <span class="small muted">{c.name}</span>{/if}</span>
          <button class="icon-btn" onclick={() => download([c])} aria-label="Descargar {c.label}"><Icon name="download" size={20} /></button>
        </div>
      {/each}
    </div>
  {:else}
    <div class="dl-actions">
      {#if unreadChapters.length && unreadChapters.length <= maxDownload}
        <button class="btn tonal" onclick={() => download(unreadChapters)}>No leídos · {unreadChapters.length}</button>
      {/if}
      {#if list.chapters.length <= maxDownload}
        <button class="btn tonal" onclick={() => download(list.chapters)}>Todos · {list.chapters.length}</button>
      {/if}
    </div>
    <div class="small muted">Desde y hasta (máximo {maxDownload} de una vez)</div>
    <div class="range">
      <select class="field" bind:value={dlFrom}>{#each list.chapters as c, i}<option value={i}>{c.label}</option>{/each}</select>
      <select class="field" bind:value={dlTo}>{#each list.chapters as c, i}<option value={i}>{c.label}</option>{/each}</select>
    </div>
    <button class="btn primary block" style="margin-top:12px" onclick={() => download(list.chapters.slice(Math.min(dlFrom, dlTo), Math.max(dlFrom, dlTo) + 1))}>
      <Icon name="download" size={18} /> Descargar {Math.abs(dlTo - dlFrom) + 1} {chapterNoun(Math.abs(dlTo - dlFrom) + 1, anime)}
    </button>
  {/if}
</Sheet>

<Sheet bind:open={pointOpen} title="Hasta dónde llega el anime">
  <form class="point" onsubmit={savePoint}>
    <p class="small muted" style="margin-top:0">Lo ven todos los usuarios y queda en el historial, así que nada se pierde si alguien se equivoca.</p>
    <label>{anime ? 'Obra original' : 'Anime'}
      <select class="field" bind:value={point.target} required>
        {#each counterparts as r (r.anilistId)}<option value={String(r.anilistId)}>{r.title}{r.format ? ' · ' + r.format : ''}</option>{/each}
      </select>
    </label>
    <label>Hasta el episodio <input class="field" type="number" min="1" max="10000" inputmode="numeric" bind:value={point.episode} required /></label>
    <div class="range">
      <label>Capítulo <input class="field" type="number" min="0" max="100000" step="0.1" inputmode="decimal" bind:value={point.chapter} /></label>
      <label>o volumen <input class="field" type="number" min="0" max="1000" inputmode="numeric" bind:value={point.volume} /></label>
    </div>
    <button class="btn primary block" disabled={!point.target || !point.episode || (point.chapter === '' && point.volume === '')}>Guardar</button>
  </form>
</Sheet>

<Sheet bind:open={marksOpen} title="Marcadores">
  {#if marks === null}
    <div class="spinner"></div>
  {:else if !marks.length}
    <p class="muted">Sin marcadores. Ponlos desde el lector con el botón del marcador.</p>
  {:else}
    {#each marks as m (m.chapterId + m.position)}
      <a class="list-item opt" href={reader(source?.kind, sourceId, mediaId, m.chapterId) + '?p=' + m.position} onclick={() => (marksOpen = false)}>
        <Icon name="bookmark" size={20} />
        <span class="grow">
          {m.chapterTitle} · {source?.kind === 'novel' ? 'párrafo' : 'página'} {m.position + 1}
          {#if m.label}<span class="small muted mark-label">{m.label}</span>{/if}
        </span>
      </a>
    {/each}
  {/if}
</Sheet>

<Sheet bind:open={altOpen} title="Otras fuentes">
  <p class="small muted">Aquí, en {source?.name}: {list.chapters.length} {chapterNoun(list.chapters.length, anime)}</p>
  {#if alternatives?.loading}
    <div class="searching"><div class="spinner"></div> Buscando en tus fuentes...</div>
  {:else if alternatives && !alternatives.items.length}
    <p>No está en ninguna otra de tus fuentes activas.</p>
  {:else if alternatives}
    {#each alternatives.items as a}
      <button class="list-item opt alt" onclick={() => { altOpen = false; navigate(mediaHref(a.item.sourceId, a.item.id)); }}>
        <div class="grow">
          <div>{a.sourceName} <span class="tag">{a.language}</span>{#if a.confidence === 'SAME_WORK'} <span class="sure">✓ misma obra</span>{/if}</div>
          <div class="small muted">{a.item.title}</div>
          <div class="small accent">{a.chapterCount != null ? `${a.chapterCount} ${chapterNoun(a.chapterCount, anime)}${a.lastChapter ? ' · hasta el ' + a.lastChapter : ''}` : 'No se pudo leer la lista'}</div>
          {#if a.confidence === 'DIFFERENT_AUTHOR'}<div class="small error-text">Mismo título, otro autor: puede ser otra obra</div>
          {:else if a.confidence !== 'SAME_WORK'}<div class="small error-text">Mismo título, sin confirmar que sea la misma obra</div>{/if}
        </div>
      </button>
    {/each}
  {/if}
</Sheet>

<style>
  .details { position: relative; background: var(--bg); min-height: 100vh; transition: background 0.45s; overflow-x: clip; }
  .wrap { position: relative; max-width: 1200px; margin: 0 auto; }
  /* The bar's colour follows the scroll (--fill from 0 to 1) instead of switching on and off. */
  .bar { --fill: 0; position: sticky; top: 0; z-index: 10; display: flex; align-items: center; gap: 4px; padding: calc(var(--safe-top) + 4px) 4px 4px; background: color-mix(in srgb, var(--bg) calc(var(--fill) * 100%), transparent); box-shadow: 0 1px 0 color-mix(in srgb, var(--outline-variant) calc(var(--fill) * 100%), transparent); -webkit-backdrop-filter: blur(calc(var(--fill) * 18px)); backdrop-filter: blur(calc(var(--fill) * 18px)); }
  /* Over a bright cover the white icons vanished: while the bar is see-through they sit on a dark disc. */
  /* Over a bright cover the white icons vanished: they sit on a dark disc that fades as the bar fills. */
  .bar .icon-btn { background: rgb(0 0 0 / calc((1 - var(--fill)) * 42%)); }
  .bar-title { flex: 1; min-width: 0; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; opacity: calc((var(--fill) - 0.6) * 2.5); }
  .start { display: none; }
  .grow { flex: 1; }
  .hero { position: relative; padding: 0 16px; }
  .backdrop { position: absolute; left: 0; right: 0; top: 0; height: 360px; background-size: cover; background-position: center 30%; filter: blur(36px); opacity: 0.45; transform: scale(1.1); }
  .backdrop::after { content: ''; position: absolute; inset: 0; background: linear-gradient(transparent, var(--bg)); }
  .banner { position: absolute; left: 0; right: 0; top: 0; height: 300px; overflow: hidden; }
  .banner img { width: 100%; height: 100%; object-fit: cover; object-position: center 30%; opacity: 0.75; }
  .banner::after { content: ''; position: absolute; inset: 0; background: linear-gradient(to bottom, color-mix(in srgb, var(--bg) 25%, transparent), var(--bg) 96%); }
  .has-banner .hero { padding-top: 120px; }
  .alt-names { color: var(--text-dim); font-size: 0.85rem; margin: -2px 0 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .in-sources { position: relative; display: flex; align-items: center; gap: 6px; margin-top: 10px; font-size: 0.82rem; color: var(--text-dim); text-align: left; }
  .in-sources:hover { color: var(--text); }
  .where { display: flex; gap: 12px; align-items: flex-start; margin: 14px 16px 6px; padding: 12px 14px; border-radius: 14px; background: var(--surface-high); border: 1px solid color-mix(in srgb, var(--primary) 30%, transparent); }
  .where > :global(svg) { color: var(--primary); flex: none; margin-top: 2px; }
  .where-title { font-weight: 700; margin-bottom: 2px; }
  .link { color: var(--primary); font-weight: 600; font-size: 0.85rem; margin-top: 4px; padding: 0; }
  .veil { filter: blur(5px); transition: filter 0.2s; }
  .chapter:hover .veil, .chapter:focus-within .veil { filter: none; }
  .kind { margin-left: 8px; font-size: 0.68rem; font-weight: 700; padding: 1px 7px; border-radius: 999px; vertical-align: 2px; }
  .kind.filler { background: color-mix(in srgb, var(--tertiary) 22%, transparent); color: var(--tertiary); }
  .kind.mixed { background: var(--surface-high); color: var(--text-dim); }
  .btn.text.on { color: var(--primary); }
  .point { display: flex; flex-direction: column; gap: 12px; }
  .point label { display: flex; flex-direction: column; gap: 4px; font-size: 0.85rem; color: var(--text-dim); flex: 1; }
  .point .field { height: 44px; }
  @media (prefers-reduced-motion: reduce) { .veil { transition: none; } }
  .statuses { position: relative; display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
  .statuses .chip { height: 30px; padding: 0 12px; font-size: 0.8rem; }
  .ch-search { display: flex; align-items: center; gap: 8px; margin: 4px 16px 8px; padding: 0 12px; height: 40px; border-radius: 20px; background: var(--surface-high); color: var(--text-dim); }
  .ch-search input { flex: 1; min-width: 0; background: none; border: 0; outline: none; color: var(--text); font-size: 0.95rem; }
  .hero-inner { position: relative; display: flex; gap: 16px; align-items: flex-end; }
  .poster { width: 116px; height: 166px; object-fit: cover; border-radius: 14px; box-shadow: 0 10px 30px #000a; background: var(--surface-variant); flex: none; opacity: 0; transition: opacity 0.35s ease; }
  .poster.shown, .poster.skeleton { opacity: 1; }
  @media (min-width: 760px) { .poster { width: 160px; height: 228px; } }
  .facts { min-width: 0; }
  h1 { font-size: 1.4rem; margin: 0 0 4px; line-height: 1.2; overflow-wrap: anywhere; text-wrap: balance; }
  .author { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .line { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; color: var(--text-dim); margin-top: 6px; }
  .sep { margin: 0 2px; }
  .dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; margin-right: 4px; }
  .score { display: inline-flex; align-items: center; gap: 3px; color: var(--text); font-weight: 600; }
  .score :global(svg) { color: var(--primary); }
  .notice { position: relative; margin-top: 12px; display: flex; gap: 6px; align-items: center; padding: 8px 12px; border-radius: 10px; background: var(--tertiary-container); color: var(--tertiary); font-size: 0.85rem; }
  .lib { position: relative; margin-top: 14px; }
  .synopsis { position: relative; margin: 14px 0 4px; line-height: 1.5; color: color-mix(in srgb, var(--text) 88%, transparent); display: -webkit-box; -webkit-line-clamp: 4; line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; cursor: pointer; white-space: pre-line; }
  .synopsis.open { -webkit-line-clamp: unset; line-clamp: unset; }
  .more { color: var(--primary); font-size: 0.85rem; font-weight: 600; }
  .genres { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
  .tabs { display: flex; margin-top: 18px; border-bottom: 1px solid var(--outline-variant); position: sticky; top: calc(var(--safe-top) + 52px); background: var(--bg); z-index: 5; transition: background 0.45s; }
  .tabs button { flex: 1; padding: 14px 0; color: var(--text-dim); font-weight: 600; border-bottom: 3px solid transparent; }
  .tabs button.on { color: var(--primary); border-bottom-color: var(--primary); }
  .bar-actions { display: flex; align-items: center; flex-wrap: nowrap; }
  .bar-actions .btn { white-space: nowrap; }
  /* Phones: the chapter tools keep only their icons (their names stay for screen readers). */
  @media (max-width: 599px) { .bar-actions .btn :global(.lbl) { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); } .bar-actions .btn { padding: 0 10px; } }
  .chapter-bar { display: flex; align-items: center; justify-content: space-between; padding: 4px 4px 4px 16px; }
  .integrity { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; text-align: left; margin: 4px 16px; padding: 8px 12px; border-radius: 10px; background: var(--surface-high); color: var(--text-dim); font-size: 0.85rem; width: calc(100% - 32px); }
  .warn { color: var(--tertiary); display: flex; }
  .issues { display: flex; flex-direction: column; gap: 2px; width: 100%; }
  .pad { padding: 16px; }
  .chapters { list-style: none; margin: 0; padding: 0; }
  .chapter { display: flex; align-items: center; }
  .ch-main { flex: 1; text-align: left; padding: 10px 8px 10px 16px; min-width: 0; }
  .ch-label { display: block; font-size: 1.02rem; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .ch-name { color: var(--text-dim); font-size: 0.9rem; font-weight: 400; margin-left: 8px; }
  .ch-meta { display: block; font-size: 0.74rem; margin-top: 2px; }
  .date { color: color-mix(in srgb, var(--text-dim) 60%, transparent); }
  .accent { color: var(--primary); }
  .chapter.read .ch-label, .chapter.read .ch-name, .chapter.read .date { opacity: 0.5; }
  .read-toggle { width: 48px; height: 48px; display: grid; place-items: center; color: color-mix(in srgb, var(--primary) 80%, transparent); }
  .ring { width: 18px; height: 18px; border-radius: 50%; background: color-mix(in srgb, var(--text-dim) 15%, transparent); }
  .gap { display: flex; align-items: center; gap: 8px; padding: 2px 16px; color: var(--tertiary); font-size: 0.74rem; }
  .gap::before, .gap::after { content: ''; flex: 1; height: 1px; background: color-mix(in srgb, var(--tertiary) 35%, transparent); }
  .related { padding: 0 16px; }
  .forum-tab { padding: 14px 16px; }
  @media (min-width: 1024px) { .forum-tab { padding: 14px 0; } }
  .fab { position: fixed; right: max(16px, calc((100vw - 960px) / 2 + 16px)); bottom: calc(16px + var(--layout-bottom-bar) + var(--safe-bottom)); z-index: 30; height: 56px; padding: 0 22px 0 18px; border-radius: 18px; display: flex; align-items: center; gap: 10px; background: var(--primary-container); color: var(--on-primary-container); font-weight: 600; box-shadow: 0 6px 20px #0008; }
  .opt { width: 100%; text-align: left; gap: 14px; }
  .cats { position: relative; display: flex; align-items: center; gap: 6px; margin-top: 8px; font-size: 0.82rem; color: var(--text-dim); text-align: left; }
  .chipwrap { display: flex; flex-wrap: wrap; gap: 8px; }
  .row-form { display: flex; gap: 8px; margin-top: 14px; }
  .row-form .field { flex: 1; min-width: 0; height: 44px; }
  .dl-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; }
  .range { display: flex; gap: 8px; margin-top: 6px; }
  .range .field { flex: 1; min-width: 0; height: 44px; }
  .mark-label { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .alt { align-items: flex-start; }
  .sure { color: var(--primary); font-size: 0.8rem; }
  .searching { display: flex; gap: 12px; align-items: center; padding: 12px 0; }
  /* Computers: the title's card stays on the left while the chapter list scrolls on the right.
     Last in the file, so these win over the phone styles above. */
  @media (min-width: 1024px) {
    /* Under the site's top bar: no floating bar of its own, the browser's Back (or Esc) returns. */
    .bar { position: static; background: none; box-shadow: none; padding: 16px 16px 8px; }
    .bar-title { visibility: hidden; }
    .layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 40px; padding: 0 32px; align-items: start; }
    .hero { position: sticky; top: calc(var(--top-h) + 20px); padding: 0 0 24px; max-height: calc(100vh - var(--top-h) - 40px); overflow-y: auto; scrollbar-width: none; }
    .hero::-webkit-scrollbar { display: none; }
    .hero-inner { flex-direction: column; align-items: flex-start; }
    .facts { width: 100%; }
    /* Small enough that the whole card usually fits the screen beside the list. */
    .poster { width: min(100%, calc(50vh * 0.7)); height: auto; aspect-ratio: 0.7; }
    h1 { font-size: 1.7rem; }
    .start { display: flex; margin-top: 16px; height: 48px; font-size: 1rem; }
    .lib { margin-top: 10px; }
    .fab { display: none; }
    .main .tabs { margin-top: 0; top: var(--top-h); }
    .has-banner .hero { padding-top: 0; }
    .has-banner .layout { padding-top: 190px; }
    .banner { height: 460px; }
    .banner img { opacity: 0.6; }
    .poster { width: min(100%, 240px); }
    .where { margin: 14px 0 6px; }
    .tabs button { flex: none; padding: 16px 22px; }
    .tabs button:hover:not(.on) { color: var(--text); }
    .chapter-bar { padding: 10px 0 6px 4px; }
    .ch-search { margin: 4px 0 10px; max-width: 420px; }
    .integrity { margin: 4px 0; width: 100%; }
    .chapters { padding-top: 4px; }
    .chapter { border-radius: 12px; transition: background 0.15s; }
    .chapter:hover { background: var(--surface); }
    .ch-main { display: flex; align-items: center; gap: 16px; padding: 12px 8px 12px 14px; }
    .ch-label { flex: 1; min-width: 0; }
    .ch-meta { margin: 0; font-size: 0.82rem; white-space: nowrap; }
    .related { padding: 0; }
    .gap { padding: 4px 14px; }
    .pad { padding: 16px 0; }
  }
  @media (min-width: 1600px) {
    .wrap { max-width: 1560px; }
    .layout { grid-template-columns: 380px minmax(0, 1fr); gap: 56px; padding: 0 32px; }
    .backdrop { height: 460px; }
    .banner { height: 480px; }
    h1 { font-size: 1.9rem; }
  }
</style>
