<script>
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { library, sources } from '../lib/store.svelte.js';
  import { media, reader } from '../lib/router.svelte.js';
  import { TYPE_FILTERS } from '../lib/format.js';
  import MediaCard from '../components/MediaCard.svelte';
  import Icon from '../components/Icon.svelte';

  let tab = $state('library');
  let typeKey = $state('ALL');
  let filter = $state('');
  let history = $state(null);

  const typeFilter = $derived(TYPE_FILTERS.find((f) => f.key === typeKey));
  const items = $derived(
    library.items
      .filter((r) => !typeFilter.types || typeFilter.types.includes(r.type))
      .filter((r) => !filter || r.title.toLowerCase().includes(filter.toLowerCase()))
  );

  onMount(() => { api.library().then((l) => (library.items = l)).catch(() => {}); });
  $effect(() => { if (tab === 'history' && !history) api.history().then((h) => (history = h)).catch(() => (history = [])); });

  const kind = (id) => sources.list.find((s) => s.id === id)?.kind;
  async function forget(h) {
    history = history.filter((x) => x !== h);
    await api.removeHistory(h.sourceId, h.mediaId).catch(() => {});
  }
  const when = (ms) => new Date(ms).toLocaleDateString('es-ES', { day: 'numeric', month: 'short' });
</script>

<div class="page">
  <header class="topbar"><h1>Biblioteca</h1></header>
  <div class="chips">
    <button class="chip" class:on={tab === 'library'} onclick={() => (tab = 'library')}>Guardadas · {library.items.length}</button>
    <button class="chip" class:on={tab === 'history'} onclick={() => (tab = 'history')}>Historial</button>
  </div>

  {#if tab === 'library'}
    <div class="chips" style="margin-top:8px">{#each TYPE_FILTERS as f}<button class="chip" class:on={typeKey === f.key} onclick={() => (typeKey = f.key)}>{f.label}</button>{/each}</div>
    {#if library.items.length > 12}<input class="field" style="margin-top:12px" placeholder="Filtrar por título" bind:value={filter} />{/if}
    {#if !library.items.length}
      <div class="center"><Icon name="library" size={48} /><p class="muted">Aún no has guardado nada. Pulsa «Añadir a la biblioteca» en cualquier obra.</p></div>
    {:else}
      <div class="grid" style="margin-top:16px">
        {#each items as r (r.sourceId + r.mediaId)}
          <MediaCard title={r.title} cover={r.cover} type={r.type} showType href={media(r.sourceId, r.mediaId)} />
        {/each}
      </div>
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
  .hist { display: flex; gap: 12px; align-items: center; flex: 1; min-width: 0; }
  .hist img { width: 48px; height: 68px; object-fit: cover; border-radius: 6px; background: var(--surface-variant); flex: none; }
  .grow { min-width: 0; }
  .title { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .accent { color: var(--primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
