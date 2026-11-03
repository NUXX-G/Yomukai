<script>
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { library, sources, updates, loadUpdates, READING_STATUSES } from '../lib/store.svelte.js';
  import { media, reader } from '../lib/router.svelte.js';
  import { TYPE_FILTERS } from '../lib/format.js';
  import MediaCard from '../components/MediaCard.svelte';
  import Icon from '../components/Icon.svelte';

  const SORTS = [['added', 'Añadidas'], ['updated', 'Novedades'], ['title', 'Título']];

  let tab = $state(sessionStorage.getItem('library.tab') || 'library');
  let status = $state(sessionStorage.getItem('library.status') || 'ALL');
  let sort = $state(sessionStorage.getItem('library.sort') || 'added');
  let typeKey = $state('ALL');
  let filter = $state('');
  let history = $state(null);

  $effect(() => {
    sessionStorage.setItem('library.tab', tab);
    sessionStorage.setItem('library.status', status);
    sessionStorage.setItem('library.sort', sort);
  });

  const keyOf = (r) => r.sourceId + '|' + r.mediaId;
  const unread = (r) => updates.byKey[keyOf(r)]?.unread || 0;
  const typeFilter = $derived(TYPE_FILTERS.find((f) => f.key === typeKey));
  const counts = $derived(Object.fromEntries(READING_STATUSES.map(([k]) => [k, library.items.filter((r) => (r.readingStatus || 'READING') === k).length])));

  const items = $derived.by(() => {
    const list = library.items
      .filter((r) => status === 'ALL' || (r.readingStatus || 'READING') === status)
      .filter((r) => !typeFilter.types || typeFilter.types.includes(r.type))
      .filter((r) => !filter || r.title.toLowerCase().includes(filter.toLowerCase()));
    if (sort === 'title') return [...list].sort((a, b) => a.title.localeCompare(b.title, 'es'));
    if (sort === 'updated') {
      const last = (r) => updates.byKey[keyOf(r)]?.lastUpdate || 0;
      return [...list].sort((a, b) => (unread(b) > 0) - (unread(a) > 0) || last(b) - last(a));
    }
    return list;
  });

  const withNews = $derived(library.items.filter((r) => unread(r) > 0)
    .sort((a, b) => (updates.byKey[keyOf(b)]?.lastUpdate || 0) - (updates.byKey[keyOf(a)]?.lastUpdate || 0)));

  onMount(() => {
    api.library().then((l) => (library.items = l)).catch(() => {});
    loadUpdates();
  });
  $effect(() => { if (tab === 'history' && !history) api.history().then((h) => (history = h)).catch(() => (history = [])); });

  const kind = (id) => sources.list.find((s) => s.id === id)?.kind;
  async function forget(h) {
    history = history.filter((x) => x !== h);
    await api.removeHistory(h.sourceId, h.mediaId).catch(() => {});
  }
  const when = (ms) => new Date(ms).toLocaleDateString('es-ES', { day: 'numeric', month: 'short' });
  const newsLabel = (n, r) => `${n} ${kind(r.sourceId) === 'anime' ? (n === 1 ? 'episodio nuevo' : 'episodios nuevos') : (n === 1 ? 'capítulo nuevo' : 'capítulos nuevos')}`;
</script>

<div class="page">
  <header class="topbar">
    <h1>Biblioteca</h1>
    <span class="grow"></span>
    <button class="icon-btn" onclick={() => loadUpdates(true)} disabled={updates.loading} aria-label="Buscar capítulos nuevos" title="Buscar capítulos nuevos">
      {#if updates.loading}<span class="spinner tiny"></span>{:else}<Icon name="refresh" />{/if}
    </button>
  </header>
  <div class="chips">
    <button class="chip" class:on={tab === 'library'} onclick={() => (tab = 'library')}>Guardadas · {library.items.length}</button>
    <button class="chip" class:on={tab === 'news'} onclick={() => (tab = 'news')}>Novedades{withNews.length ? ' · ' + withNews.length : ''}</button>
    <button class="chip" class:on={tab === 'history'} onclick={() => (tab = 'history')}>Historial</button>
  </div>

  {#if tab === 'library'}
    <div class="chips" style="margin-top:8px">
      <button class="chip" class:on={status === 'ALL'} onclick={() => (status = 'ALL')}>Todas</button>
      {#each READING_STATUSES as [k, label]}
        {#if counts[k]}<button class="chip" class:on={status === k} onclick={() => (status = k)}>{label} · {counts[k]}</button>{/if}
      {/each}
    </div>
    <div class="chips" style="margin-top:8px">{#each TYPE_FILTERS as f}<button class="chip" class:on={typeKey === f.key} onclick={() => (typeKey = f.key)}>{f.label}</button>{/each}</div>
    {#if library.items.length}
      <div class="tools">
        {#if library.items.length > 12}<input class="field" placeholder="Filtrar por título" bind:value={filter} />{:else}<span class="grow"></span>{/if}
        <select class="field sort" bind:value={sort} aria-label="Ordenar">
          {#each SORTS as [k, label]}<option value={k}>{label}</option>{/each}
        </select>
      </div>
    {/if}
    {#if !library.items.length}
      <div class="center"><Icon name="library" size={48} /><p class="muted">Aún no has guardado nada. Pulsa «Añadir a la biblioteca» en cualquier obra.</p></div>
    {:else if !items.length}
      <p class="muted" style="margin-top:24px">Nada con estos filtros.</p>
    {:else}
      <div class="grid" style="margin-top:16px">
        {#each items as r (keyOf(r))}
          <div class="slot">
            <MediaCard title={r.title} cover={r.cover} type={r.type} showType href={media(r.sourceId, r.mediaId)} />
            {#if unread(r)}<span class="badge" title={newsLabel(unread(r), r)}>{unread(r) > 99 ? '99+' : unread(r)}</span>{/if}
          </div>
        {/each}
      </div>
    {/if}
  {:else if tab === 'news'}
    {#if updates.loading && !withNews.length}
      <div class="center"><div class="spinner"></div><p class="muted small">Mirando tus obras guardadas...</p></div>
    {:else if !withNews.length}
      <div class="center"><p class="muted">Estás al día. Aquí salen las obras guardadas con capítulos nuevos desde el último que leíste.</p></div>
    {:else}
      {#each withNews as r (keyOf(r))}
        {@const u = updates.byKey[keyOf(r)]}
        <div class="list-item">
          <a class="hist" href={u.next ? reader(kind(r.sourceId), r.sourceId, r.mediaId, u.next) : media(r.sourceId, r.mediaId)}>
            <img src={r.cover} alt="" loading="lazy" />
            <div class="grow">
              <div class="title">{r.title}</div>
              <div class="small accent">{newsLabel(u.unread, r)}</div>
              <div class="small muted">Hasta el {u.latestLabel}{u.lastUpdate ? ' · ' + when(u.lastUpdate) : ''}</div>
            </div>
          </a>
          <a class="icon-btn" href={media(r.sourceId, r.mediaId)} aria-label="Ficha"><Icon name="list" size={20} /></a>
        </div>
      {/each}
    {/if}
  {:else if history === null}
    <div class="center"><div class="spinner"></div></div>
  {:else if !history.length}
    <div class="center"><p class="muted">Lo que leas y veas aparecerá aquí.</p></div>
  {:else}
    {#each history as h (h.sourceId + h.mediaId)}
      <div class="list-item">
        <a class="hist" href={reader(kind(h.sourceId), h.sourceId, h.mediaId, h.chapterId)}>
          <img src={h.cover} alt="" loading="lazy" />
          <div class="grow">
            <div class="title">{h.title}</div>
            <div class="small accent">{h.chapterTitle}</div>
            <div class="small muted">{when(h.readAt)}</div>
          </div>
        </a>
        <a class="icon-btn" href={media(h.sourceId, h.mediaId)} aria-label="Ficha"><Icon name="list" size={20} /></a>
        <button class="icon-btn" onclick={() => forget(h)} aria-label="Quitar del historial"><Icon name="close" size={20} /></button>
      </div>
    {/each}
  {/if}
</div>

<style>
  .grow { flex: 1; min-width: 0; }
  .tiny { width: 18px; height: 18px; border-width: 2px; }
  .tools { display: flex; gap: 8px; margin-top: 12px; }
  .tools .field:first-child { flex: 1; min-width: 0; }
  .tools .field { height: 40px; }
  .sort { width: auto; flex: none; }
  .slot { position: relative; min-width: 0; }
  .badge { position: absolute; top: 6px; right: 6px; min-width: 24px; height: 24px; padding: 0 7px; border-radius: 12px; display: grid; place-items: center; background: var(--primary); color: var(--on-primary); font-size: 0.75rem; font-weight: 700; box-shadow: 0 2px 8px #0008; }
  .hist { display: flex; gap: 12px; align-items: center; flex: 1; min-width: 0; }
  .hist img { width: 48px; height: 68px; object-fit: cover; border-radius: 6px; background: var(--surface-variant); flex: none; }
  .title { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .accent { color: var(--primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
