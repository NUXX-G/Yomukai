<script>
  import { onDestroy } from 'svelte';
  import { searchStream } from '../lib/api.js';
  import { settings, rememberSearch, saveSettings } from '../lib/store.svelte.js';
  import { route, navigate, media } from '../lib/router.svelte.js';
  import { TYPE_FILTERS, LANGUAGES, TYPE_NAMES, TYPE_COLORS } from '../lib/format.js';
  import Icon from '../components/Icon.svelte';

  let query = $state(route.query.get('q') || '');
  let typeKey = $state('ALL');
  let lang = $state('');
  let groups = $state([]);
  let pending = $state([]);
  let errors = $state({});
  let searching = $state(false);
  let searched = $state(false);
  let stop = null;

  function run(save = true) {
    const q = query.trim();
    if (!q) return;
    stop?.();
    if (save) rememberSearch(q);
    navigate('/buscar?q=' + encodeURIComponent(q), { replace: true });
    groups = []; pending = []; errors = {}; searching = true; searched = true;
    const types = TYPE_FILTERS.find((f) => f.key === typeKey).types;
    stop = searchStream(q, { types, lang }, (update) => {
      groups = update.groups; pending = update.pending; errors = update.errors;
      if (update.done) searching = false;
    }, () => (searching = false));
  }

  $effect(() => { if (route.query.get('q') && !searched) run(false); });
  onDestroy(() => stop?.());

  function setType(k) { typeKey = k; if (searched) run(false); }
  function setLang(k) { lang = k; if (searched) run(false); }
  function clear() { query = ''; groups = []; searched = false; stop?.(); searching = false; }
  function forget(q) { settings.searchHistory = settings.searchHistory.filter((x) => x !== q); saveSettings(); }
</script>

<div class="page">
  <form class="searchbar" onsubmit={(e) => { e.preventDefault(); run(); }}>
    <Icon name="search" />
    <input type="search" placeholder="Busca en todas tus fuentes" bind:value={query} enterkeyhint="search" autocomplete="off" />
    {#if query}<button type="button" class="icon-btn" onclick={clear} aria-label="Borrar"><Icon name="close" size={20} /></button>{/if}
  </form>

  <div class="chips">{#each TYPE_FILTERS as f}<button class="chip" class:on={typeKey === f.key} onclick={() => setType(f.key)}>{f.label}</button>{/each}</div>
  <div class="chips" style="margin-top:8px">{#each LANGUAGES as l}<button class="chip" class:on={lang === l.key} onclick={() => setLang(l.key)}>{l.label}</button>{/each}</div>

  {#if !searched}
    {#if settings.searchHistory.length}
      <div class="section-title"><h2>Recientes</h2></div>
      {#each settings.searchHistory as q}
        <div class="list-item">
          <button class="grow left" onclick={() => { query = q; run(); }}>{q}</button>
          <button class="icon-btn" onclick={() => forget(q)} aria-label="Quitar"><Icon name="close" size={18} /></button>
        </div>
      {/each}
    {/if}
  {:else}
    {#if searching}
      <p class="small muted status"><span class="spinner tiny"></span> Buscando en {pending.join(', ') || 'tus fuentes'}...</p>
    {/if}
    {#if !searching && !groups.length}
      <p class="muted" style="margin-top:24px">Sin resultados para «{query}».</p>
    {/if}
    {#each groups as g, i (i)}
      <div class="result">
        <a href={media(g.primary.sourceId, g.primary.id)} class="hit">
          <img src={g.primary.cover} alt="" loading="lazy" />
          <div class="info">
            <div class="title">{g.primary.title}</div>
            <div class="small muted type"><span class="dot" style:background={TYPE_COLORS[g.primary.type]}></span>{TYPE_NAMES[g.primary.type]} {g.primary.status ? '· ' + g.primary.status : ''}</div>
          </div>
        </a>
        {#if g.others.length}
          <div class="small muted also">También en:</div>
          <div class="chips">
            {#each g.others as o}<a class="chip" href={media(o.sourceId, o.id)}>{o.sourceId}</a>{/each}
          </div>
        {/if}
      </div>
    {/each}
    {#if Object.keys(errors).length && !searching}
      <p class="small muted">Sin respuesta de: {Object.keys(errors).join(', ')}</p>
    {/if}
  {/if}
</div>

<style>
  .searchbar { display: flex; align-items: center; gap: 10px; height: 52px; padding: 0 6px 0 16px; border-radius: 26px; background: var(--surface-high); margin: 8px 0 14px; }
  .searchbar input { flex: 1; background: none; border: 0; outline: none; font-size: 1rem; min-width: 0; }
  .grow { flex: 1; } .left { text-align: left; }
  .status { display: flex; align-items: center; gap: 8px; margin-top: 16px; }
  .tiny { width: 14px; height: 14px; border-width: 2px; }
  .result { padding: 12px 0; border-bottom: 1px solid var(--outline-variant); }
  .hit { display: flex; gap: 12px; align-items: center; }
  .hit img { width: 56px; height: 80px; border-radius: 6px; object-fit: cover; background: var(--surface-variant); flex: none; }
  .info { min-width: 0; }
  .title { font-weight: 600; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .type { display: flex; align-items: center; gap: 6px; margin-top: 4px; }
  .dot { width: 6px; height: 6px; border-radius: 50%; }
  .also { margin: 8px 0 4px 68px; }
  .result .chips { margin-left: 68px; }
</style>
