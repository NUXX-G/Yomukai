<script>
  import { t, i18n } from '../lib/i18n.svelte.js';
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, navigate, back, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { features, session, sourceById, inLibrary, toggleLibrary, notify, seenItem, READING_STATUSES, setReadingStatus, statusOf, categories, setCategories, editCategory } from '../lib/store.svelte.js';
  import { downloadChapters } from '../lib/api.js';
  import { coverSeed, themeFor, styleOf } from '../lib/cover.js';
  import { TYPE_NAMES, TYPE_COLORS, parseStatus, statusName, score, monthYear, chapterNoun, AUDIO } from '../lib/format.js';
  import { progressMap, continueTarget, markThrough, markOne } from '../lib/progress.js';
  import { openRelated } from '../lib/open.svelte.js';
  import { copyRoomLink } from '../lib/party.svelte.js';
  import Icon from '../components/Icon.svelte';
  import MediaCard from '../components/MediaCard.svelte';
  import Sheet from '../components/Sheet.svelte';
  import Row from '../components/Row.svelte';
  import WorkSocial from '../components/WorkSocial.svelte';
  import ForumThread from '../components/ForumThread.svelte';
  import ShareSheet from '../components/ShareSheet.svelte';
  import { themeState } from '../lib/theme.svelte.js';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const source = $derived(sourceById(sourceId));
  const anime = $derived(source?.kind === 'anime');

  // What the list already knew, shown at once; the full page replaces it when it arrives.
  let item = $state(seenItem(sourceId, mediaId));
  // Compartir (F4.3): this work as a card for a person, a room or its forum thread.
  let shareOpen = $state(false);
  const shareCard = $derived(item ? { kind: 'work', sourceId, mediaId, title: item.title, cover: item.cover || '', type: item.type || '', lang: sourceById(sourceId)?.language || '' } : null);
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
  // The work in other languages (from the works index): chips to switch, e.g. from the Spanish edition to the English one.
  const askedKey = route.query.get('obra');
  const askedLang = route.query.get('lang') || i18n.lang;
  let obraKey = $state(askedKey);
  let obraLang = $state(askedLang);
  let obraLangs = $state([]);
  if (askedKey) api.obraFicha(askedKey, askedLang, true).then((r) => (obraLangs = r.langs || []), () => {});
  // Opened from the library, a search or "Continuar" (no ?obra=): the index says which work this page is (F1.5).
  else api.obraDe(sourceId, mediaId).then((r) => { obraKey = r.key; obraLang = r.lang || obraLang; obraLangs = r.langs || []; }, () => {});
  const LANG_NAMES = { es: 'Español', en: 'English', zh: '中文', ja: '日本語', ko: '한국어', fr: 'Français', de: 'Deutsch', it: 'Italiano', pt: 'Português', ru: 'Русский' };
  /** Episode number -> 'FILLER' | 'MIXED' (AnimeFillerList); only for anime. */
  let fillers = $state({});
  let hideFiller = $state(false);

  let where = $state(null);
  let pointOpen = $state(false);
  let point = $state({ target: '', episode: '', chapter: '', volume: '' });
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
    try { await api.setAdaptation(body); notify(t('Guardado; lo verán todos los usuarios')); pointOpen = false; loadWhere(); } catch (err) { notify(err.message); }
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
    if (!chapters.length) { notify(t('No hay nada que descargar')); return; }
    if (chapters.length > maxDownload) { notify(t('Como mucho {0} capítulos por descarga', [maxDownload])); return; }
    downloadChapters({ sourceId, mediaId, title: item?.title, chapterIds: chapters.map((c) => c.id) }, (msg) => notify(t('No se pudo descargar: ') + msg));
    notify(chapters.length === 1 ? t('Descargando...') : t('Preparando {0} capítulos; tu navegador lo guardará al terminar de llegar', [chapters.length]));
    dlOpen = false;
  }
  const unreadChapters = $derived(list.chapters.filter((c) => !progress[c.id]?.read));

  /** Admin: the home worker downloads these to Jellyfin (anime) or exports them as CBZ for Kavita/Komga (comics). */
  async function toLibrary(chapters) {
    menu = false;
    if (!chapters.length) { notify(t('No hay nada que enviar')); return; }
    try {
      const ids = chapters.map((c) => c.id);
      const r = anime ? await api.jellyfinDownload(sourceId, mediaId, ids) : await api.cbzExport(sourceId, mediaId, ids);
      notify(t('{0} en cola para el servidor de casa{1}', [r.queued, anime ? ' (Jellyfin)' : ' (Kavita/Komga)']));
    } catch (e) { notify(e.message); }
  }

  /** Admin: follow the work so new episodes/chapters go to the NAS on their own (checked every 3 h). */
  let follow = $state(null);
  let followOpen = $state(false);
  let followRules = $state({ preferredServer: '', maxPerRun: 5 });
  async function openFollow() {
    menu = false;
    followOpen = true;
    try {
      follow = (await api.followOf(sourceId, mediaId)).follow;
      if (follow) followRules = { ...follow.rules };
    } catch (e) { notify(e.message); }
  }
  async function saveFollow() {
    try {
      follow = await api.follow(sourceId, mediaId, { ...followRules, maxPerRun: Number(followRules.maxPerRun) || 5 });
      notify(anime ? t('Seguida: los episodios nuevos irán solos al Jellyfin') : t('Seguida: los capítulos nuevos irán solos a Kavita/Komga'));
      followOpen = false;
    } catch (e) { notify(e.message); }
  }
  async function stopFollow() {
    try { await api.unfollow(follow.id); follow = null; notify(t('Ya no se descarga sola')); followOpen = false; } catch (e) { notify(e.message); }
  }

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
  // The same work in the user's other sources: what this source lacks comes from them, so this is one list for the
  // work (one One Piece, not several) and nothing is lost if this source fails or stops.
  let extras = $state([]);
  /** Chapter numbers read in another source or language of the work. */
  let readElsewhere = new Set();
  function applyElsewhere() {
    for (const c of list.chapters) {
      if (!progress[c.id]?.read && readElsewhere.has(String(Math.round(c.number * 10) / 10))) progress[c.id] = { chapterId: c.id, read: true, elsewhere: true };
    }
  }
  async function loadWhole() {
    try {
      const whole = await api.workWhole(sourceId, mediaId);
      const own = new Set(list.chapters.map((c) => String(Math.round(c.number * 10) / 10)));
      extras = (whole.chapters?.chapters || [])
        .filter((c) => c.sourceId !== sourceId && c.number > 0 && c.mediaId && !own.has(String(Math.round(c.number * 10) / 10)))
        .map((c) => ({
          id: c.sourceId + '|' + c.chapterId, chapterId: c.chapterId, sourceId: c.sourceId, mediaId: c.mediaId, number: c.number,
          label: (anime ? t('Ep. {0}', [c.key]) : t('Cap. {0}', [c.key])), name: c.title, date: '', foreign: true, read: c.read,
          sourceName: sourceById(c.sourceId)?.name || c.sourceId
        }));
      // Read in any source or language of the work counts here too: the same episode is not new again (F1.5).
      readElsewhere = new Set((whole.chapters?.chapters || []).filter((c) => c.read).map((c) => String(Math.round(c.number * 10) / 10)));
      applyElsewhere();
    } catch { /* the source's own list stays */ }
  }
  const allChapters = $derived(extras.length ? [...list.chapters, ...extras].sort((a, b) => a.number - b.number) : list.chapters);
  const shown = $derived.by(() => {
    const q = chapterQuery.trim().toLowerCase();
    const all = descending ? [...allChapters].reverse() : allChapters;
    const visible = hideFiller ? all.filter((c) => fillerOf(c) !== 'FILLER') : all;
    return q ? visible.filter((c) => matches(c, q)) : visible;
  });
  const altNames = $derived([...new Set([...(work?.altTitles || []), ...(item?.altTitles || [])])].filter((n) => n && n.toLowerCase() !== item?.title?.toLowerCase()));
  const whereTarget = $derived(where?.next?.length ? relationByKey(where.next[0].targetKey) : null);
  const whereText = $derived.by(() => {
    if (!where) return '';
    if (anime && where.next?.length) {
      const n = where.next[0];
      const name = whereTarget?.title || t('la obra original');
      const at = n.chapter != null ? t('el capítulo {0}', [Math.floor(n.chapter)]) : t('el volumen {0}', [n.volume]);
      return t('Después del episodio {0}, la historia sigue en {1} de {2}.', [Math.floor(lastDone), at, name]);
    }
    if (!anime && where.animatedUpTo != null) {
      const upTo = Math.floor(where.animatedUpTo);
      return lastDone >= upTo ? t('Ya has pasado lo que adapta el anime (hasta el capítulo {0}).', [upTo]) : t('El anime adapta hasta el capítulo {0}.', [upTo]);
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
      (r) => { list = { ...r, loading: false, error: '' }; loadWhole(); },
      (e) => { list = { ...list, loading: false, error: e.message }; loadWhole(); }
    );
    progressMap(sourceId, mediaId).then((p) => { progress = p; applyElsewhere(); });
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

  /** «Ver con amigos» from the work's page: your room, its link copied, and the episode opens inside it. */
  async function watchWithFriends(chapter) {
    try {
      const room = await api.roomMine();
      const copied = await copyRoomLink(room.code).catch(() => false);
      notify(copied ? t('Enlace de la sala copiado: pásalo a tus amigos') : t('Sala ') + room.code);
      const base = chapter.foreign ? reader(source?.kind, chapter.sourceId, chapter.mediaId, chapter.chapterId) : reader(source?.kind, sourceId, mediaId, chapter.id);
      navigate(base + '?sala=' + encodeURIComponent(room.code));
    } catch (e) { notify(e.message); }
  }

  function open(chapter) {
    if (chapter.foreign) navigate(reader(source?.kind, chapter.sourceId, chapter.mediaId, chapter.chapterId));
    else navigate(reader(source?.kind, sourceId, mediaId, chapter.id));
  }

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
    notify(added ? t('Añadida a tu biblioteca') : t('Quitada de tu biblioteca'));
  }
</script>

<svelte:window onscroll={onScroll} />

<div class="details" class:has-banner={!!work?.banner} style={themeState.coverAccent ? styleOf(theme) : ''}>
  {#if work?.banner}
    <div class="banner"><img src={work.banner} alt="" decoding="async" /></div>
  {:else if item?.cover}<div class="backdrop" style:background-image="url('{item.cover}')"></div>{/if}
  <div class="wrap">
  <header class="bar" style:--fill={fill}>
    <button class="icon-btn" onclick={() => back('/')} aria-label={t('Volver')}><Icon name="back" /></button>
    <span class="bar-title" aria-hidden={!scrolled}>{item?.title ?? ''}</span>
    <button class="icon-btn" onclick={() => (menu = true)} aria-label={t('Más opciones')}><Icon name="more" /></button>
  </header>

  {#if error && !item}
    <div class="center"><p>{error}</p><button class="btn tonal" onclick={() => location.reload()}>{t('Reintentar')}</button></div>
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
            {#if meta?.malScore != null}<span class="sep">·</span><span class="score"><Icon name="star" size={14} /> {score(meta.malScore)} <span class="muted">{t('MAL')}</span></span>{/if}
          </div>
        </div>
      </div>

      {#if obraLangs.length > 1}
        {#if item?.audio && item.audio !== 'ORIGINAL' && AUDIO[item.audio]}<span class="audio-chip">{t(AUDIO[item.audio].label)}</span>{/if}
        <div class="obra-langs" role="group" aria-label={t('Idioma')}>
          {#each obraLangs as l (l)}
            <a class="chip" class:on={l === obraLang} aria-current={l === obraLang ? 'true' : undefined}
              href={'/obras/' + encodeURIComponent(obraKey) + '?lang=' + l}>{LANG_NAMES[l] || l.toUpperCase()}</a>
          {/each}
        </div>
      {/if}

      {#if work?.sources?.length > 1}
        <button class="in-sources" onclick={openAlternatives}>
          <Icon name="grid" size={16} /> En {work.sources.length} fuentes: {work.sources.map((x) => x.name).join(', ')}
        </button>
      {/if}

      {#if list.abandoned}
        <div class="notice"><Icon name="hourglass" size={16} /> {t('Posible abandono o hiato prolongado')}{#if list.lastUpdate} {t('· último capítulo en {0}', [monthYear(list.lastUpdate)])}{/if}</div>
      {/if}

      {#if target}
        <button class="btn primary block start" onclick={() => open(target.chapter)}>
          <Icon name="play" size={20} /> {target.started ? 'Continuar' : anime ? t('Empezar a ver') : t('Empezar a leer')} · {target.chapter.label}
        </button>
        {#if anime && session.user && !session.user.guest}
          <button class="btn outline block lib" onclick={() => watchWithFriends(target.chapter)} title={t('Abre tu sala y copia el enlace')}>
            <Icon name="share" size={18} /> {t('Ver con amigos')}
          </button>
        {/if}
      {/if}
      {#if features.flags.sharing !== false && session.user && !session.user.guest && item}
        <button class="btn outline block lib" onclick={() => (shareOpen = true)}><Icon name="send" size={18} /> {t('Compartir')}</button>
      {/if}
      <button class="btn tonal block lib" onclick={library}>
        <Icon name={saved ? 'heart' : 'heartOutline'} size={18} /> {saved ? t('En tu biblioteca') : t('Añadir a la biblioteca')}
      </button>
      {#if saved}
        <div class="statuses" role="radiogroup" aria-label={t('Estado de lectura')}>
          {#each READING_STATUSES as [key, label]}
            <button class="chip" role="radio" aria-checked={statusOf(saved) === key} class:on={statusOf(saved) === key} onclick={() => setReadingStatus(sourceId, mediaId, key)}>{anime && key === 'READING' ? 'Viendo' : label}</button>
          {/each}
        </div>
        <button class="cats" onclick={() => (catsOpen = true)}>
          <Icon name="library" size={16} />
          {saved.categories?.length ? saved.categories.join(' · ') : t('Añadir a una categoría')}
        </button>
      {/if}

      {#if synopsis}
        <p class="synopsis" class:open={expanded} onclick={() => (expanded = !expanded)}>{synopsis}</p>
        <button class="more" onclick={() => (expanded = !expanded)}>{expanded ? 'Mostrar menos' : t('Mostrar más')}</button>
        {#if !item.description && meta?.synopsis}<div class="small muted">{t('Sinopsis de {0}', [meta.scoreSource || 'AniList'])}</div>{/if}
      {/if}

      <WorkSocial {sourceId} {mediaId} title={item.title} cover={item.cover} />

      {#if genres.length}
        <div class="genres">{#each genres as g}<span class="pill">{t(g)}</span>{/each}</div>
        {#if meta?.inferredGenres}<div class="small muted">{t('Etiquetas deducidas de la sinopsis')}</div>{/if}
      {/if}
    </section>

    <div class="main">
    <div class="tabs" role="tablist">
      <button role="tab" class:on={tab === 0} aria-selected={tab === 0} onclick={() => (tab = 0)}>{anime ? 'Episodios' : t('Capítulos')} · {list.chapters.length}</button>
      <button role="tab" class:on={tab === 1} aria-selected={tab === 1} onclick={() => (tab = 1)}>{t('Relacionadas')}</button>
      {#if features.flags.forum !== false && session.user && !session.user.guest}
        <button role="tab" class:on={tab === 2} aria-selected={tab === 2} onclick={() => (tab = 2)}>{t('Foro')}</button>
      {/if}
    </div>

    {#if tab === 0}
      {#if whereText || (work?.key && counterparts.length && !session.user?.guest)}
        <div class="where">
          <Icon name="info" size={18} />
          <div class="grow">
            <div class="where-title">{t('¿Dónde sigo?')}</div>
            {#if whereText}
              <div class="small">{whereText}</div>
            {:else}
              <div class="small muted">{t('Nadie ha dicho todavía hasta dónde llega {0}.', [anime ? t('este anime en el manga o la novela') : t('el anime en esta obra')])}</div>
            {/if}
            {#if whereTarget}<button class="link" onclick={() => openRelated(whereTarget)}>{t('Abrir {0}', [whereTarget.title])}</button>{/if}
          </div>
          {#if counterparts.length && !session.user?.guest}<button class="btn text" onclick={openPoint}>{whereText ? 'Corregir' : t('Añadir')}</button>{/if}
        </div>
      {/if}
      <div class="chapter-bar">
        <span class="small muted">{readCount ? t('{0} {1} de {2}', [readCount, anime ? t('vistos') : t('leídos'), list.chapters.length]) : ''}</span>
        <span class="bar-actions">
          {#if list.chapters.length}<button class="btn text" onclick={openDownloads}><Icon name="download" size={18} /> <span class="lbl">{t('Descargar')}</span></button>{/if}
          {#if fillerCount}<button class="btn text" class:on={hideFiller} aria-pressed={hideFiller} onclick={() => (hideFiller = !hideFiller)}>{hideFiller ? 'Mostrar relleno' : 'Ocultar relleno'}</button>{/if}
          <button class="btn text" onclick={() => (descending = !descending)}><Icon name="sort" size={18} /> <span class="lbl">{descending ? t('Más recientes') : t('Desde el principio')}</span></button>
        </span>
      </div>
      {#if list.chapters.length > 20}
        <div class="ch-search">
          <Icon name="search" size={18} />
          <input type="search" inputmode="search" placeholder={anime ? t('Ir al episodio...') : t('Ir al capítulo...')} bind:value={chapterQuery} aria-label={t('Buscar capítulo')} />
        </div>
      {/if}
      {#if list.issues.length}
        <button class="integrity" onclick={() => (issuesOpen = !issuesOpen)}>
          <span class="warn"><Icon name="warning" size={16} /></span>
          <span>{list.issues.length === 1 ? list.issues[0].text : t('La lista de la fuente tiene {0} irregularidades', [list.issues.length])}</span>
          {#if issuesOpen}
            <span class="issues small">
              {#if list.issues.length > 1}{#each list.issues as i}<span>• {i.text}</span>{/each}{/if}
              <span>{t('Viene de la fuente, no de la app. Prueba «Buscar en otras fuentes» si falta algo.')}</span>
            </span>
          {/if}
        </button>
      {/if}
      {#if list.loading}
        <div class="center"><div class="spinner"></div></div>
      {:else if list.error && !list.chapters.length}
        <p class="pad error-text">{list.error}</p>
      {:else if !list.chapters.length}
        <p class="pad muted">{t('No se encontraron {0}.', [chapterNoun(2, anime)])}</p>
      {/if}
      <ol class="chapters">
        {#each shown as c (c.id)}
          {#if descending && gapAfter[c.id] && !extras.length}<li class="gap"><span>{gapAfter[c.id].text}</span></li>{/if}
          {@const p = c.foreign ? (c.read ? { read: true } : null) : progress[c.id]}
          <li class="chapter" class:read={p?.read}>
            <button class="ch-main" onclick={() => open(c)}>
              <span class="ch-label">{c.label}{#if fillerOf(c) === 'FILLER'}<span class="kind filler">{t('Relleno')}</span>{:else if fillerOf(c) === 'MIXED'}<span class="kind mixed">{t('Mixto')}</span>{/if}</span>
              <span class="ch-meta">
                {#if c.foreign}<span class="accent">{t('De {0}', [c.sourceName])}</span>{/if}
                {#if c.date}<span class="date">{c.date}</span>{/if}
                {#if p && !p.read && anime && p.page > 0}<span class="accent">{t('· Vas por el minuto {0}', [Math.floor(p.page / 60)])}</span>
                {:else if p && !p.read && !anime && p.total > 0}<span class="accent">{t('· Página {0} de {1}', [p.page + 1, p.total])}</span>
                {:else if p && !p.read && p.fraction > 0.01}<span class="accent">{t('· {0}% leído', [Math.round(p.fraction * 100)])}</span>{/if}
              </span>
            </button>
            <button class="read-toggle" disabled={c.foreign} onclick={() => toggleRead(c)} aria-label={p?.read ? t('Marcar como no leído') : t('Marcar como leído')}>
              {#if p?.read}<Icon name="check" size={20} />{:else}<span class="ring"></span>{/if}
            </button>
          </li>
          {#if !descending && gapAfter[c.id] && !extras.length}<li class="gap"><span>{gapAfter[c.id].text}</span></li>{/if}
        {/each}
      </ol>
    {:else if tab === 2}
      <div class="forum-tab">
        {#if !work?.key || forumThread === undefined}
          <div class="spinner"></div>
        {:else if forumThread}
          <ForumThread threadId={forumThread.id} upTo={lastDone} {anime} ondeleted={() => (forumThread = null)} />
        {:else}
          <ForumThread newFor={{ sourceId, mediaId, title: item.title }} upTo={lastDone} {anime} />
        {/if}
      </div>
    {:else}
      <div class="related">
        {#if metaLoading}
          <div class="spinner" style="margin:16px"></div>
        {:else if meta?.relations?.length}
          <div class="section-title"><h2>{t('Universo de la obra')}</h2></div>
          <Row>
            {#each meta.relations as r, i (i)}
              <MediaCard title={r.title} cover={r.cover} type={r.type} showType subtitle={[r.relation, r.format].filter(Boolean).join(' · ')} onclick={() => openRelated(r)} />
            {/each}
          </Row>
        {/if}
        {#if similar?.loading}
          <div class="section-title"><h2>{t('Similares')}</h2></div><div class="spinner" style="margin:8px 0"></div>
        {:else if similar?.items.length}
          <div class="section-title"><h2>{t('Similares')}</h2></div>
          <Row>
            {#each similar.items as r, i (i)}
              <MediaCard title={r.title} cover={r.cover} type={r.type} showType subtitle={r.format} onclick={() => openRelated(r)} />
            {/each}
          </Row>
        {/if}
        <button class="chip" style="margin-top:16px" onclick={openAlternatives}><Icon name="search" size={18} /> {t('Buscar en otras fuentes')}</button>
        {#if !metaLoading && !meta?.relations?.length && similar && !similar.loading && !similar.items.length}
          <p class="muted">{t('No se conocen obras relacionadas.')}</p>
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
    {target.started ? 'Continuar' : anime ? t('Empezar a ver') : t('Empezar a leer')} · {target.chapter.label}
  </button>
{/if}

<Sheet bind:open={followOpen} title={t('Descarga automática')}>
  <div class="follow">
    <p class="small muted">{t('Cada 3 horas el servidor mira si hay {0} nuevos y el servidor de casa los guarda en {1}. Te avisa cuando lleguen o si algo falla.', [anime ? 'episodios' : t('capítulos'), anime ? t('tu Jellyfin') : t('tu carpeta de cómics')])}</p>
    {#if anime}
      <label>{t('Servidor preferido')} <input class="field" placeholder={t('p. ej. MP4Upload (vacío = el primero que funcione)')} bind:value={followRules.preferredServer} maxlength="40" /></label>
    {/if}
    <label>{t('Como mucho cada vez')} <input class="field" type="number" min="1" max="20" bind:value={followRules.maxPerRun} /></label>
    {#if follow}<p class="small muted">{t('Siguiendo desde el {0} {1}.', [anime ? 'episodio' : t('capítulo'), follow.lastNumber])}</p>{/if}
    <div class="row">
      <button class="btn primary" onclick={saveFollow}>{follow ? 'Guardar' : 'Seguir'}</button>
      {#if follow}<button class="btn tonal" onclick={stopFollow}>{t('Dejar de seguir')}</button>{/if}
    </div>
  </div>
</Sheet>

<Sheet bind:open={menu} title={t('Opciones')}>
  <button class="list-item opt" onclick={openAlternatives}><Icon name="search" /> {t('Buscar en otras fuentes')}</button>
  {#if list.chapters.length}
    <button class="list-item opt" onclick={openDownloads}><Icon name="download" /> {t('Descargar en este dispositivo')}</button>
    {#if session.user?.admin && features.flags.jellyfin !== false}
      <button class="list-item opt" onclick={() => toLibrary(unreadChapters)}><Icon name="library" /> {anime ? t('Al Jellyfin: episodios sin ver') : t('A Kavita/Komga: capítulos sin leer')} · {unreadChapters.length}</button>
      <button class="list-item opt" onclick={() => toLibrary(list.chapters)}><Icon name="library" /> {anime ? t('Al Jellyfin: todos') : t('A Kavita/Komga: todos')} · {list.chapters.length}</button>
      <button class="list-item opt" onclick={openFollow}><Icon name="bell" /> {t('Descargar lo nuevo automáticamente')}</button>
    {/if}
    {#if !anime}<button class="list-item opt" onclick={openMarks}><Icon name="bookmark" /> {t('Marcadores')}</button>{/if}
    <button class="list-item opt" onclick={() => markAllBefore(list.chapters[list.chapters.length - 1], true)}><Icon name="check" /> {t('Marcar todo como {0}', [anime ? 'visto' : t('leído')])}</button>
    <button class="list-item opt" onclick={() => markAllBefore(list.chapters[list.chapters.length - 1], false)}><Icon name="close" /> {t('Quitar todas las marcas')}</button>
  {/if}
</Sheet>

<Sheet bind:open={catsOpen} title={t('Categorías')}>
  {#if !categories.list.length}<p class="small muted" style="margin-top:0">{t('Aún no tienes categorías. Crea una:')}</p>{/if}
  <div class="chipwrap">
    {#each categories.list as c (c.name)}
      <button class="chip" class:on={saved?.categories?.includes(c.name)} onclick={() => toggleCat(c.name)}>{c.name}</button>
    {/each}
  </div>
  <form class="row-form" onsubmit={createCat}>
    <input class="field" placeholder={t('Nueva categoría')} bind:value={newCat} maxlength="100" />
    <button class="btn tonal" disabled={!newCat.trim()}>{t('Crear y añadir')}</button>
  </form>
</Sheet>

<Sheet bind:open={dlOpen} title={anime ? t('Descargar episodios') : t('Descargar capítulos')}>
  <p class="small muted" style="margin-top:0">
    {t('Se guarda en este {0}, no en el servidor. {1}', [onPhone ? t('móvil') : 'ordenador', anime ? t('Los episodios se descargan como vídeo, de uno en uno.') : source?.kind === 'novel' ? t('Las novelas se descargan como libro EPUB (Libros del iPhone, Google Play Libros, Kindle...).') : t('Los cómics se descargan como CBZ, que abre cualquier lector de cómics; varios capítulos van juntos en un ZIP.')])}
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
        <button class="btn tonal" onclick={() => download(unreadChapters)}>{t('No leídos · {0}', [unreadChapters.length])}</button>
      {/if}
      {#if list.chapters.length <= maxDownload}
        <button class="btn tonal" onclick={() => download(list.chapters)}>{t('Todos · {0}', [list.chapters.length])}</button>
      {/if}
    </div>
    <div class="small muted">{t('Desde y hasta (máximo {0} de una vez)', [maxDownload])}</div>
    <div class="range">
      <select class="field" bind:value={dlFrom}>{#each list.chapters as c, i}<option value={i}>{c.label}</option>{/each}</select>
      <select class="field" bind:value={dlTo}>{#each list.chapters as c, i}<option value={i}>{c.label}</option>{/each}</select>
    </div>
    <button class="btn primary block" style="margin-top:12px" onclick={() => download(list.chapters.slice(Math.min(dlFrom, dlTo), Math.max(dlFrom, dlTo) + 1))}>
      <Icon name="download" size={18} /> {t('Descargar {0} {1}', [Math.abs(dlTo - dlFrom) + 1, chapterNoun(Math.abs(dlTo - dlFrom) + 1, anime)])}
    </button>
  {/if}
</Sheet>

<Sheet bind:open={pointOpen} title={t('Hasta dónde llega el anime')}>
  <form class="point" onsubmit={savePoint}>
    <p class="small muted" style="margin-top:0">{t('Lo ven todos los usuarios y queda en el historial, así que nada se pierde si alguien se equivoca.')}</p>
    <label>{anime ? t('Obra original') : 'Anime'}
      <select class="field" bind:value={point.target} required>
        {#each counterparts as r (r.anilistId)}<option value={String(r.anilistId)}>{r.title}{r.format ? ' · ' + r.format : ''}</option>{/each}
      </select>
    </label>
    <label>{t('Hasta el episodio')} <input class="field" type="number" min="1" max="10000" inputmode="numeric" bind:value={point.episode} required /></label>
    <div class="range">
      <label>{t('Capítulo')} <input class="field" type="number" min="0" max="100000" step="0.1" inputmode="decimal" bind:value={point.chapter} /></label>
      <label>{t('o volumen')} <input class="field" type="number" min="0" max="1000" inputmode="numeric" bind:value={point.volume} /></label>
    </div>
    <button class="btn primary block" disabled={!point.target || !point.episode || (point.chapter === '' && point.volume === '')}>{t('Guardar')}</button>
  </form>
</Sheet>

<Sheet bind:open={marksOpen} title={t('Marcadores')}>
  {#if marks === null}
    <div class="spinner"></div>
  {:else if !marks.length}
    <p class="muted">{t('Sin marcadores. Ponlos desde el lector con el botón del marcador.')}</p>
  {:else}
    {#each marks as m (m.chapterId + m.position)}
      <a class="list-item opt" href={reader(source?.kind, sourceId, mediaId, m.chapterId) + '?p=' + m.position} onclick={() => (marksOpen = false)}>
        <Icon name="bookmark" size={20} />
        <span class="grow">
          {m.chapterTitle} · {source?.kind === 'novel' ? t('párrafo') : t('página')} {m.position + 1}
          {#if m.label}<span class="small muted mark-label">{m.label}</span>{/if}
        </span>
      </a>
    {/each}
  {/if}
</Sheet>

<Sheet bind:open={altOpen} title={t('Otras fuentes')}>
  <p class="small muted">{t('Aquí, en {0}: {1} {2}', [source?.name, list.chapters.length, chapterNoun(list.chapters.length, anime)])}</p>
  {#if alternatives?.loading}
    <div class="searching"><div class="spinner"></div> {t('Buscando en tus fuentes...')}</div>
  {:else if alternatives && !alternatives.items.length}
    <p>{t('No está en ninguna otra de tus fuentes activas.')}</p>
  {:else if alternatives}
    {#each alternatives.items as a}
      <button class="list-item opt alt" onclick={() => { altOpen = false; navigate(mediaHref(a.item.sourceId, a.item.id)); }}>
        <div class="grow">
          <div>{a.sourceName} <span class="tag">{a.language}</span>{#if a.confidence === 'SAME_WORK'} <span class="sure">{t('✓ misma obra')}</span>{/if}</div>
          <div class="small muted">{a.item.title}</div>
          <div class="small accent">{a.chapterCount != null ? `${a.chapterCount} ${chapterNoun(a.chapterCount, anime)}${a.lastChapter ? ' · hasta el ' + a.lastChapter : ''}` : t('No se pudo leer la lista')}</div>
          {#if a.confidence === 'DIFFERENT_AUTHOR'}<div class="small error-text">{t('Mismo título, otro autor: puede ser otra obra')}</div>
          {:else if a.confidence !== 'SAME_WORK'}<div class="small error-text">{t('Mismo título, sin confirmar que sea la misma obra')}</div>{/if}
        </div>
      </button>
    {/each}
  {/if}
</Sheet>

{#if shareCard}<ShareSheet bind:open={shareOpen} card={shareCard} />{/if}

<style>
  .obra-langs { display: flex; flex-wrap: wrap; gap: 8px; margin: 4px 0 12px; }
  .obra-langs .chip { padding: 6px 12px; border-radius: 999px; background: var(--surface-2, rgba(127,127,127,.15)); color: inherit; text-decoration: none; font-size: .9rem; }
  .obra-langs .chip.on { background: var(--accent); color: var(--on-accent, #fff); }
  .details { position: relative; background: var(--bg); min-height: 100vh; transition: background 0.45s; overflow-x: clip; }
  /* The space the layout keeps under the page (for the bottom bar) belongs to the work page, so its colour reaches
     the very bottom instead of a black band. */
  :global(.shell:not(.immersive) main:has(.details)) { padding-bottom: 0 !important; }
  .details { padding-bottom: calc(var(--layout-bottom-bar) + var(--safe-bottom)); }
  @media (min-width: 1024px) { .details { padding-bottom: 48px; } }
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
  .kind { margin-left: 8px; font-size: 0.68rem; font-weight: 700; padding: 1px 7px; border-radius: 999px; vertical-align: 2px; }
  .kind.filler { background: color-mix(in srgb, var(--tertiary) 22%, transparent); color: var(--tertiary); }
  .kind.mixed { background: var(--surface-high); color: var(--text-dim); }
  .btn.text.on { color: var(--primary); }
  .point { display: flex; flex-direction: column; gap: 12px; }
  .point label { display: flex; flex-direction: column; gap: 4px; font-size: 0.85rem; color: var(--text-dim); flex: 1; }
  .point .field { height: 44px; }
  .statuses { position: relative; display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
  .statuses .chip { height: 30px; padding: 0 12px; font-size: 0.8rem; }
  .ch-search { display: flex; align-items: center; gap: 8px; margin: 4px 16px 8px; padding: 0 12px; height: 42px; border-radius: 4px; background: var(--surface); color: var(--text-dim); border: 2px solid var(--nb-soft-line); }
  .ch-search:focus-within { border-color: var(--nb-line); box-shadow: 3px 3px 0 var(--primary); }
  .ch-search input { flex: 1; min-width: 0; background: none; border: 0; outline: none; color: var(--text); font-size: 0.95rem; }
  .hero-inner { position: relative; display: flex; gap: 16px; align-items: flex-end; }
  .poster { width: 116px; height: 166px; object-fit: cover; border-radius: 4px; border: 3px solid var(--nb-line); box-shadow: 5px 5px 0 var(--nb-line); background: var(--surface-variant); flex: none; opacity: 0; transition: opacity 0.35s ease; }
  .poster.shown, .poster.skeleton { opacity: 1; }
  @media (min-width: 760px) { .poster { width: 160px; height: 228px; } }
  .facts { min-width: 0; }
  h1 { font-family: var(--font-display); font-weight: 400; font-size: 1.4rem; margin: 0 0 6px; line-height: 1.2; overflow-wrap: anywhere; text-wrap: balance; }
  .author { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .line { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; color: var(--text-dim); margin-top: 6px; }
  .sep { margin: 0 2px; }
  .dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; margin-right: 4px; }
  .score { display: inline-flex; align-items: center; gap: 3px; color: var(--text); font-weight: 600; }
  .score :global(svg) { color: var(--primary); }
  .notice { position: relative; margin-top: 12px; display: flex; gap: 6px; align-items: center; padding: 8px 12px; border-radius: 4px; background: var(--surface); color: var(--text); border: 2px dashed var(--primary); font-size: 0.85rem; }
  .lib { position: relative; margin-top: 14px; }
  .synopsis { position: relative; margin: 14px 0 4px; line-height: 1.5; color: color-mix(in srgb, var(--text) 88%, transparent); display: -webkit-box; -webkit-line-clamp: 4; line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; cursor: pointer; white-space: pre-line; }
  .synopsis.open { -webkit-line-clamp: unset; line-clamp: unset; }
  .more { color: var(--primary); font-size: 0.85rem; font-weight: 600; }
  .genres { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
  .tabs { display: flex; margin-top: 18px; border-bottom: 3px solid var(--nb-line); position: sticky; top: calc(var(--safe-top) + 52px); background: var(--bg); z-index: 5; transition: background 0.45s; }
  .tabs button { flex: 1; padding: 14px 0; color: var(--text-dim); font-weight: 750; }
  /* The open tab is printed in ink, like a page index tab. */
  .tabs button.on { color: var(--c-canvas); background: var(--c-ink); }
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
  .ch-meta { display: block; font-size: 0.74rem; margin-top: 2px; }
  .date { color: color-mix(in srgb, var(--text-dim) 60%, transparent); }
  .accent { color: var(--primary); }
  .chapter.read .ch-label, .chapter.read .date { opacity: 0.5; }
  .read-toggle { width: 48px; height: 48px; display: grid; place-items: center; color: color-mix(in srgb, var(--primary) 80%, transparent); }
  .ring { width: 18px; height: 18px; border-radius: 50%; background: color-mix(in srgb, var(--text-dim) 15%, transparent); }
  .gap { display: flex; align-items: center; gap: 8px; padding: 2px 16px; color: var(--tertiary); font-size: 0.74rem; }
  .gap::before, .gap::after { content: ''; flex: 1; height: 1px; background: color-mix(in srgb, var(--tertiary) 35%, transparent); }
  .related { padding: 0 16px; }
  .forum-tab { padding: 14px 16px; }
  @media (min-width: 1024px) { .forum-tab { padding: 14px 0; } }
  .fab { position: fixed; right: max(16px, calc((100vw - 960px) / 2 + 16px)); bottom: calc(16px + var(--layout-bottom-bar) + var(--safe-bottom)); z-index: 30; height: 52px; padding: 0 22px 0 18px; border-radius: 4px; display: flex; align-items: center; gap: 10px; background: var(--primary); color: var(--on-primary); font-weight: 800; border: 3px solid var(--nb-line); box-shadow: 4px 4px 0 var(--nb-line); }
  .fab:active { transform: translate(4px, 4px); box-shadow: none; }
  .opt { width: 100%; text-align: left; gap: 14px; }
  .cats { position: relative; display: flex; align-items: center; gap: 6px; margin-top: 8px; font-size: 0.82rem; color: var(--text-dim); text-align: left; }
  .chipwrap { display: flex; flex-wrap: wrap; gap: 8px; }
  .row-form { display: flex; gap: 8px; margin-top: 14px; }
  .follow { display: flex; flex-direction: column; gap: 12px; padding: 4px 0 8px; }
  .follow label { display: flex; flex-direction: column; gap: 4px; font-size: var(--fs-small); color: var(--c-text-dim); }
  .follow .row { display: flex; gap: 8px; flex-wrap: wrap; }
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
    .chapter { border-radius: 4px; border: 2px solid transparent; transition: background 0.1s, border-color 0.1s; }
    .chapter:hover { background: var(--surface); border-color: var(--nb-soft-line); }
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
  .audio-chip { display: inline-block; margin: 4px 0 8px; padding: 4px 12px; border-radius: 999px; font-size: .85rem; font-weight: 600; background: color-mix(in srgb, var(--c-accent) 18%, transparent); }
</style>
