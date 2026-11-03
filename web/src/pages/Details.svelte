<script>
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, navigate, back, media as mediaHref, reader } from '../lib/router.svelte.js';
  import { sourceById, inLibrary, toggleLibrary, notify } from '../lib/store.svelte.js';
  import { coverSeed, themeFor, styleOf } from '../lib/cover.js';
  import { TYPE_NAMES, TYPE_COLORS, parseStatus, statusName, score, monthYear, chapterNoun } from '../lib/format.js';
  import { progressMap, continueTarget, markThrough, markOne } from '../lib/progress.js';
  import { openRelated } from '../lib/open.svelte.js';
  import Icon from '../components/Icon.svelte';
  import MediaCard from '../components/MediaCard.svelte';
  import Sheet from '../components/Sheet.svelte';

  const sourceId = route.params.source;
  const mediaId = route.params.id;
  const source = $derived(sourceById(sourceId));
  const anime = $derived(source?.kind === 'anime');

  let item = $state(null);
  let error = $state('');
  let list = $state({ loading: true, chapters: [], issues: [], abandoned: false, lastUpdate: null, error: '' });
  let progress = $state({});
  let meta = $state(null);
  let metaLoading = $state(true);
  let similar = $state(null);
  let tab = $state(sessionStorage.getItem('details.tab') === '1' ? 1 : 0);
  let descending = $state(true);
  let expanded = $state(false);
  let theme = $state({});
  let menu = $state(false);
  let altOpen = $state(false);
  let alternatives = $state(null);
  let issuesOpen = $state(false);

  const saved = $derived(inLibrary(sourceId, mediaId));
  const shown = $derived(descending ? [...list.chapters].reverse() : list.chapters);
  const gapAfter = $derived(Object.fromEntries(list.issues.filter((i) => i.afterChapterId).map((i) => [i.afterChapterId, i])));
  const target = $derived(continueTarget(list.chapters, progress));
  const readCount = $derived(list.chapters.filter((c) => progress[c.id]?.read).length);
  const status = $derived.by(() => {
    const own = parseStatus(item?.status);
    return own || meta?.officialStatus || null;
  });
  const genres = $derived(meta?.genres?.length ? meta.genres : item?.genres || []);
  const synopsis = $derived(item?.description || meta?.synopsis || '');

  onMount(async () => {
    try {
      item = await api.media(sourceId, mediaId);
      coverSeed(item.cover).then((seed) => (theme = themeFor(seed)));
    } catch (e) {
      error = e.message;
    }
    api.chapters(sourceId, mediaId).then(
      (r) => (list = { ...r, loading: false, error: '' }),
      (e) => (list = { ...list, loading: false, error: e.message })
    );
    progressMap(sourceId, mediaId).then((p) => (progress = p));
    api.metadata(sourceId, mediaId).then((m) => (meta = m), () => {}).finally(() => (metaLoading = false));
  });

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

<div class="details" style={styleOf(theme)}>
  <header class="bar">
    <button class="icon-btn" onclick={() => back('/')} aria-label="Volver"><Icon name="back" /></button>
    <span class="grow"></span>
    <button class="icon-btn" onclick={() => (menu = true)} aria-label="Más opciones"><Icon name="more" /></button>
  </header>

  {#if error && !item}
    <div class="center"><p>{error}</p><button class="btn tonal" onclick={() => location.reload()}>Reintentar</button></div>
  {:else if !item}
    <div class="center"><div class="spinner"></div></div>
  {:else}
    <section class="hero">
      {#if item.cover}<div class="backdrop" style:background-image="url('{item.cover}')"></div>{/if}
      <div class="hero-inner">
        <img class="poster" src={item.cover} alt="" />
        <div class="facts">
          <h1>{item.title}</h1>
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

      {#if list.abandoned}
        <div class="notice"><Icon name="hourglass" size={16} /> Posible abandono o hiato prolongado{#if list.lastUpdate} · último capítulo en {monthYear(list.lastUpdate)}{/if}</div>
      {/if}

      <button class="btn tonal block lib" onclick={library}>
        <Icon name={saved ? 'heart' : 'heartOutline'} size={18} /> {saved ? 'En tu biblioteca' : 'Añadir a la biblioteca'}
      </button>

      {#if synopsis}
        <p class="synopsis" class:open={expanded} onclick={() => (expanded = !expanded)}>{synopsis}</p>
        <button class="more" onclick={() => (expanded = !expanded)}>{expanded ? 'Mostrar menos' : 'Mostrar más'}</button>
        {#if !item.description && meta?.synopsis}<div class="small muted">Sinopsis de {meta.scoreSource || 'AniList'}</div>{/if}
      {/if}

      {#if genres.length}
        <div class="genres">{#each genres as g}<span class="pill">{g}</span>{/each}</div>
        {#if meta?.inferredGenres}<div class="small muted">Etiquetas deducidas de la sinopsis</div>{/if}
      {/if}
    </section>

    <div class="tabs" role="tablist">
      <button role="tab" class:on={tab === 0} aria-selected={tab === 0} onclick={() => (tab = 0)}>{anime ? 'Episodios' : 'Capítulos'} · {list.chapters.length}</button>
      <button role="tab" class:on={tab === 1} aria-selected={tab === 1} onclick={() => (tab = 1)}>Relacionadas</button>
    </div>

    {#if tab === 0}
      <div class="chapter-bar">
        <span class="small muted">{readCount ? `${readCount} ${anime ? 'vistos' : 'leídos'} de ${list.chapters.length}` : ''}</span>
        <button class="btn text" onclick={() => (descending = !descending)}><Icon name="sort" size={18} /> {descending ? 'Más recientes' : 'Desde el principio'}</button>
      </div>
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
              <span class="ch-label">{c.label}{#if c.name}<span class="ch-name">{c.name}</span>{/if}</span>
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
    {:else}
      <div class="related">
        {#if metaLoading}
          <div class="spinner" style="margin:16px"></div>
        {:else if meta?.relations?.length}
          <div class="section-title"><h2>Universo de la obra</h2></div>
          <div class="row">
            {#each meta.relations as r, i (i)}
              <MediaCard title={r.title} cover={r.cover} type={r.type} showType subtitle={[r.relation, r.format].filter(Boolean).join(' · ')} onclick={() => openRelated(r)} />
            {/each}
          </div>
        {/if}
        {#if similar?.loading}
          <div class="section-title"><h2>Similares</h2></div><div class="spinner" style="margin:8px 0"></div>
        {:else if similar?.items.length}
          <div class="section-title"><h2>Similares</h2></div>
          <div class="row">
            {#each similar.items as r, i (i)}
              <MediaCard title={r.title} cover={r.cover} type={r.type} showType subtitle={r.format} onclick={() => openRelated(r)} />
            {/each}
          </div>
        {/if}
        <button class="chip" style="margin-top:16px" onclick={openAlternatives}><Icon name="search" size={18} /> Buscar en otras fuentes</button>
        {#if !metaLoading && !meta?.relations?.length && similar && !similar.loading && !similar.items.length}
          <p class="muted">No se conocen obras relacionadas.</p>
        {/if}
      </div>
    {/if}
    <div style="height:120px"></div>
  {/if}
</div>

{#if target && item}
  <button class="fab" onclick={() => open(target.chapter)}>
    <Icon name="play" />
    {target.started ? 'Continuar' : anime ? 'Empezar a ver' : 'Empezar a leer'} · {target.chapter.label}
  </button>
{/if}

<Sheet bind:open={menu} title="Opciones">
  <button class="list-item opt" onclick={openAlternatives}><Icon name="search" /> Buscar en otras fuentes</button>
  {#if list.chapters.length}
    <button class="list-item opt" onclick={() => markAllBefore(list.chapters[list.chapters.length - 1], true)}><Icon name="check" /> Marcar todo como {anime ? 'visto' : 'leído'}</button>
    <button class="list-item opt" onclick={() => markAllBefore(list.chapters[list.chapters.length - 1], false)}><Icon name="close" /> Quitar todas las marcas</button>
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
  .details { background: var(--bg); min-height: 100vh; transition: background 0.45s; max-width: 960px; margin: 0 auto; }
  .bar { position: sticky; top: 0; z-index: 10; display: flex; padding: calc(var(--safe-top) + 4px) 4px 4px; background: linear-gradient(var(--bg), transparent); }
  .grow { flex: 1; }
  .hero { position: relative; padding: 0 16px; }
  .backdrop { position: absolute; inset: -60px 0 auto; height: 300px; background-size: cover; background-position: center; filter: blur(32px); opacity: 0.5; z-index: 0; }
  .backdrop::after { content: ''; position: absolute; inset: 0; background: linear-gradient(transparent, var(--bg)); }
  .hero-inner { position: relative; display: flex; gap: 16px; align-items: flex-end; }
  .poster { width: 116px; height: 166px; object-fit: cover; border-radius: 14px; box-shadow: 0 10px 30px #000a; background: var(--surface-variant); flex: none; }
  @media (min-width: 760px) { .poster { width: 160px; height: 228px; } }
  .facts { min-width: 0; }
  h1 { font-size: 1.4rem; margin: 0 0 4px; line-height: 1.2; }
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
  .tabs { display: flex; margin-top: 18px; border-bottom: 1px solid var(--outline-variant); position: sticky; top: calc(var(--safe-top) + 52px); background: var(--bg); z-index: 5; }
  .tabs button { flex: 1; padding: 14px 0; color: var(--text-dim); font-weight: 600; border-bottom: 3px solid transparent; }
  .tabs button.on { color: var(--primary); border-bottom-color: var(--primary); }
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
  .fab { position: fixed; right: max(16px, calc((100vw - 960px) / 2 + 16px)); bottom: calc(16px + var(--safe-bottom)); z-index: 30; height: 56px; padding: 0 22px 0 18px; border-radius: 18px; display: flex; align-items: center; gap: 10px; background: var(--primary-container); color: var(--on-primary-container); font-weight: 600; box-shadow: 0 6px 20px #0008; }
  .opt { width: 100%; text-align: left; gap: 14px; }
  .alt { align-items: flex-start; }
  .sure { color: var(--primary); font-size: 0.8rem; }
  .searching { display: flex; gap: 12px; align-items: center; padding: 12px 0; }
</style>
