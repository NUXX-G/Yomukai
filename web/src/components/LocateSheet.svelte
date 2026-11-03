<script>
  import Sheet from './Sheet.svelte';
  import { locating } from '../lib/open.svelte.js';
  import { navigate, media } from '../lib/router.svelte.js';
  import Icon from './Icon.svelte';

  function go(option) {
    locating.open = false;
    navigate(media(option.item.sourceId, option.item.id));
  }
</script>

<Sheet bind:open={locating.open} title={locating.title}>
  {#if locating.searching}
    <div class="searching"><div class="spinner"></div><span>Buscando en tus fuentes...</span></div>
  {:else if !locating.options.length}
    <p>Ninguna de tus fuentes activas la tiene con este nombre.</p>
    <button class="btn text" onclick={() => { locating.open = false; navigate('/buscar?q=' + encodeURIComponent(locating.title)); }}>
      <Icon name="search" size={18} /> Buscar «{locating.title}» en todas
    </button>
  {:else}
    {#each locating.options as option}
      <button class="list-item option" onclick={() => go(option)}>
        <div class="grow">
          <div>{option.sourceName}</div>
          <div class="small muted">{option.item.title}</div>
          {#if option.approximate}<div class="small muted">Nombre parecido: comprueba que es la misma obra</div>{/if}
        </div>
        <span class="tag">{option.language}</span>
      </button>
    {/each}
  {/if}
</Sheet>

<style>
  .searching { display: flex; align-items: center; gap: 12px; padding: 12px 0; }
  .searching .spinner { width: 20px; height: 20px; border-width: 2px; }
  .option { width: 100%; text-align: left; }
  .grow { flex: 1; min-width: 0; }
</style>
