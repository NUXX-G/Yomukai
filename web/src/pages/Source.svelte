<script>
  // One source's whole catalogue, with its own filters and infinite scroll.
  import { onMount, onDestroy } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, back, media } from '../lib/router.svelte.js';
  import { sourceById } from '../lib/store.svelte.js';
  import MediaCard from '../components/MediaCard.svelte';
  import Sheet from '../components/Sheet.svelte';
  import Icon from '../components/Icon.svelte';

  const id = route.params.id;
  const source = $derived(sourceById(id));
  let items = $state([]);
  let page = $state(0);
  let loading = $state(false);
  let ended = $state(false);
  let error = $state('');
  let filtersOpen = $state(false);
  let values = $state({});
  let sentinel;
  let observer;

  function defaults() {
    const v = {};
    for (const f of source?.filters || []) v[f.key] = f.multi ? [] : [f.default ?? f.options[0]?.value];
    return v;
  }

  async function more() {
    if (loading || ended) return;
    loading = true;
    try {
      const next = page + 1;
      const hasFilters = source?.filters?.length;
      const list = hasFilters ? await api.browse(id, next, $state.snapshot(values)) : await api.latest(id, next);
      const known = new Set(items.map((i) => i.id));
      const fresh = list.filter((i) => !known.has(i.id));
      items = [...items, ...fresh];
      page = next;
      if (!fresh.length) ended = true;
    } catch (e) {
      error = e.message;
      ended = true;
    } finally {
      loading = false;
    }
  }

  function apply() {
    filtersOpen = false;
    items = []; page = 0; ended = false; error = '';
    more();
  }

  function toggle(f, value) {
    const current = values[f.key] || [];
    values[f.key] = f.multi ? (current.includes(value) ? current.filter((v) => v !== value) : [...current, value]) : [value];
  }

  onMount(() => {
    values = defaults();
    more();
    observer = new IntersectionObserver((entries) => { if (entries[0].isIntersecting) more(); }, { rootMargin: '800px' });
    observer.observe(sentinel);
  });
  onDestroy(() => observer?.disconnect());
</script>

<div class="page">
  <header class="topbar">
    <button class="icon-btn" onclick={() => back('/')} aria-label="Volver"><Icon name="back" /></button>
    <h1>{source?.name ?? id}</h1>
    {#if source?.filters?.length}<button class="icon-btn" onclick={() => (filtersOpen = true)} aria-label="Filtros"><Icon name="tune" /></button>{/if}
  </header>
  <div class="grid">
    {#each items as item (item.id)}
      <MediaCard title={item.title} cover={item.cover} href={media(item.sourceId, item.id)} />
    {/each}
    {#if loading && !items.length}{#each Array(12) as _}<div class="skeleton" style="aspect-ratio:0.7"></div>{/each}{/if}
  </div>
  <div bind:this={sentinel} class="end">
    {#if loading && items.length}<div class="spinner"></div>
    {:else if error}<p class="error-text small">{error}</p>
    {:else if ended && !items.length}<p class="muted">Nada con estos filtros.</p>{/if}
  </div>
</div>

<Sheet bind:open={filtersOpen} title="Filtros">
  {#each source?.filters || [] as f}
    <div class="small muted" style="margin:12px 0 6px">{f.label}</div>
    <div class="wrap">
      {#each f.options as o}
        <button class="chip" class:on={(values[f.key] || []).includes(o.value)} onclick={() => toggle(f, o.value)}>{o.label}</button>
      {/each}
    </div>
  {/each}
  <div style="display:flex;gap:12px;margin-top:20px">
    <button class="btn primary" onclick={apply}>Aplicar</button>
    <button class="btn text" onclick={() => { values = defaults(); apply(); }}>Restablecer</button>
  </div>
</Sheet>

<style>
  .end { display: grid; place-items: center; min-height: 80px; }
  .wrap { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
