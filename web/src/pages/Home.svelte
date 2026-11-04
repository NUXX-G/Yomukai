<script>
  // Inicio: continue reading, recommendations, and the newest titles of every enabled source.
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { sources, rememberItems, home } from '../lib/store.svelte.js';
  import { media, reader } from '../lib/router.svelte.js';
  import { openRelated } from '../lib/open.svelte.js';
  import { TYPE_FILTERS, LANGUAGES } from '../lib/format.js';
  import MediaCard from '../components/MediaCard.svelte';
  import Row from '../components/Row.svelte';
  import RandomSheet from '../components/RandomSheet.svelte';
  import Icon from '../components/Icon.svelte';

  // Filters survive going to a title and coming back.
  let typeKey = $state(sessionStorage.getItem('home.type') || 'ALL');
  let lang = $state(sessionStorage.getItem('home.lang') || '');
  let shuffle = $state(false);
  // Kept in the store: back on this page, everything is already there and the scroll lands in place.
  const sections = home.sections;
  const history = $derived(home.history || []);
  const forYou = $derived(home.forYou || { loading: true, items: [] });
  const FRESH_MS = 5 * 60_000;

  const typeFilter = $derived(TYPE_FILTERS.find((f) => f.key === typeKey));
  const visible = $derived(
    sources.list.filter((s) => s.enabled)
      .filter((s) => !lang || s.language === 'MULTI' || s.language === lang)
      .filter((s) => !typeFilter.types || s.types.some((t) => typeFilter.types.includes(t)))
  );

  $effect(() => { sessionStorage.setItem('home.type', typeKey); sessionStorage.setItem('home.lang', lang); });

  function load(source) {
    const known = sections[source.id];
    if (known && (known.loading || Date.now() - known.at < FRESH_MS)) return;
    // An older list stays on screen while the new one comes: nothing blinks.
    if (!known) sections[source.id] = { loading: true, items: [], error: '', at: 0 };
    api.latest(source.id).then(
      (items) => { rememberItems(items); sections[source.id] = { loading: false, items, error: '', at: Date.now() }; },
      (e) => (sections[source.id] = known?.items.length ? { ...known, at: Date.now() } : { loading: false, items: [], error: e.message, at: Date.now() })
    );
  }

  $effect(() => { visible.forEach(load); });

  onMount(() => {
    api.history().then((h) => (home.history = h.slice(0, 12))).catch(() => {});
    if (!home.forYou || Date.now() - home.forYou.at > FRESH_MS) {
      api.forYou().then((items) => (home.forYou = { loading: false, items, at: Date.now() }), () => (home.forYou = { loading: false, items: home.forYou?.items || [], at: Date.now() }));
    }
  });

  const filtered = (items) => typeFilter.types ? items.filter((i) => typeFilter.types.includes(i.type)) : items;
  const continueHref = (h) => {
    const kind = sources.list.find((s) => s.id === h.sourceId)?.kind;
    return reader(kind, h.sourceId, h.mediaId, h.chapterId);
  };
</script>

<div class="page">
  <header class="topbar">
    <span class="brand">Yomukai</span>
    <h1 class="desk-title">Inicio</h1>
    <button class="icon-btn phone" onclick={() => (shuffle = true)} aria-label="Descubrir al azar"><Icon name="shuffle" /></button>
    <a class="icon-btn phone" href="/buscar" aria-label="Buscar"><Icon name="search" /></a>
    <button class="btn outline desk" onclick={() => (shuffle = true)}><Icon name="shuffle" size={18} /> Sorpréndeme</button>
  </header>

  <div class="filters">
    <div class="chips" role="group" aria-label="Tipo">
      {#each TYPE_FILTERS as f}<button class="chip" class:on={typeKey === f.key} onclick={() => (typeKey = f.key)}>{f.label}</button>{/each}
    </div>
    <div class="chips" role="group" aria-label="Idioma">
      {#each LANGUAGES as l}<button class="chip" class:on={lang === l.key} onclick={() => (lang = l.key)}>{l.label}</button>{/each}
    </div>
  </div>

  {#if history.length}
    <div class="section-title"><h2>Continuar</h2></div>
    <Row class="continue">
      {#each history as h (h.sourceId + h.mediaId)}
        <a class="cont" href={continueHref(h)}>
          <img src={h.cover} alt="" loading="lazy" />
          <div class="cont-text">
            <div class="cont-title">{h.title}</div>
            <div class="small accent">{h.chapterTitle}</div>
          </div>
        </a>
      {/each}
    </Row>
  {/if}

  {#if forYou.loading || forYou.items.length}
    <div class="section-title"><h2>Para ti</h2></div>
    {#if forYou.loading}
      <div class="row">{#each Array(6) as _}<div><div class="skeleton" style="aspect-ratio:0.7"></div></div>{/each}</div>
    {:else}
      <Row>
        {#each forYou.items as r, i (i)}
          <MediaCard title={r.title} cover={r.cover} type={r.type} subtitle={r.format} showType onclick={() => openRelated(r)} />
        {/each}
      </Row>
    {/if}
  {/if}

  {#if sources.loaded && !visible.length}
    <p class="muted" style="margin-top:24px">No hay fuentes activas para estos filtros. Actívalas en Ajustes.</p>
  {/if}

  {#each visible as source (source.id)}
    {@const section = sections[source.id]}
    <div class="section-title">
      <h2><a href="/fuente/{encodeURIComponent(source.id)}">{source.name}</a></h2>
      <div class="right"><span class="tag">{source.language}</span><a class="btn text" href="/fuente/{encodeURIComponent(source.id)}">Ver todo</a></div>
    </div>
    {#if !section || section.loading}
      <div class="row">{#each Array(6) as _}<div><div class="skeleton" style="aspect-ratio:0.7"></div><div class="skeleton" style="height:10px;margin-top:8px;width:80%"></div></div>{/each}</div>
    {:else if section.error && !section.items.length}
      <p class="small error-text">No disponible: {section.error}</p>
    {:else}
      <Row>
        {#each filtered(section.items) as item (item.id)}
          <MediaCard title={item.title} cover={item.cover} href={media(item.sourceId, item.id)} />
        {/each}
      </Row>
    {/if}
  {/each}
</div>

<RandomSheet bind:open={shuffle} />

<style>
  .right { display: flex; align-items: center; gap: 4px; }
  .brand { flex: 1; }
  .desk-title, .desk { display: none; }
  .filters { display: flex; flex-direction: column; gap: 8px; }
  /* Scoped class beats the shelf's generic item width. */
  .cont { width: 260px; display: flex; gap: 10px; align-items: center; padding: 8px; border-radius: 14px; background: var(--surface-high); }
  .cont img { width: 52px; height: 74px; object-fit: cover; border-radius: 6px; background: var(--surface-variant); flex: none; }
  .cont-text { min-width: 0; }
  .cont-title { font-size: 0.9rem; font-weight: 600; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .accent { color: var(--primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  @media (min-width: 1024px) {
    .brand, .phone { display: none; }
    .desk-title { display: block; }
    .desk { display: inline-flex; }
    .filters { flex-direction: row; align-items: center; gap: 12px; margin-top: 8px; }
    .filters .chips + .chips { padding-left: 12px; border-left: 1px solid var(--outline-variant); }
    .cont { width: 300px; }
    .cont { transition: background 0.15s; }
    .cont:hover { background: var(--surface-highest); }
  }
</style>
