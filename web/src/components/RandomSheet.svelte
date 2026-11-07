<script>
  import { t } from '../lib/i18n.svelte.js';
  // Random pick that some source always has, with those sources: one tap opens it there.
  import Sheet from './Sheet.svelte';
  import Icon from './Icon.svelte';
  import { api } from '../lib/api.js';
  import { RANDOM_KINDS, RANDOM_GENRES, TYPE_NAMES, TYPE_COLORS } from '../lib/format.js';
  import { navigate, media } from '../lib/router.svelte.js';

  let { open = $bindable(false) } = $props();
  let kind = $state('ANY');
  let genre = $state('');
  let rolling = $state(false);
  let pick = $state(null);
  let places = $state([]);
  let error = $state('');
  let ticket = 0;

  async function roll() {
    const mine = ++ticket;
    rolling = true;
    error = '';
    try {
      const found = await api.random(kind, genre);
      if (mine !== ticket) return;
      pick = found.pick;
      places = found.places;
    } catch (e) {
      if (mine === ticket) error = e.message;
    } finally {
      if (mine === ticket) rolling = false;
    }
  }

  $effect(() => { if (open && !pick && !rolling) roll(); });

  function choose(k) { if (kind !== k) { kind = k; roll(); } }
  function chooseGenre(g) { if (genre !== g) { genre = g; roll(); } }
  function go(place) { open = false; navigate(media(place.item.sourceId, place.item.id)); }
</script>

<Sheet bind:open title={t('Descubrir al azar')}>
  <div class="chips">
    {#each RANDOM_KINDS as k}<button class="chip" class:on={kind === k.key} onclick={() => choose(k.key)}>{k.label}</button>{/each}
  </div>
  <div class="chips" style="margin-top:8px">
    <button class="chip" class:on={!genre} onclick={() => chooseGenre('')}>{t('Cualquier género')}</button>
    {#each RANDOM_GENRES as [value, label]}<button class="chip" class:on={genre === value} onclick={() => chooseGenre(value)}>{label}</button>{/each}
  </div>

  <div class="stage">
    {#if rolling}
      <div class="center small-center"><div class="spinner"></div><span class="small muted">{t('Buscando uno que esté en tus fuentes...')}</span></div>
    {:else if pick}
      <button class="pick" onclick={() => places[0] && go(places[0])}>
        <img src={pick.cover} alt="" />
        <div>
          <div class="pick-title">{pick.title}</div>
          <div class="small muted type"><span class="dot" style:background={TYPE_COLORS[pick.type]}></span>{pick.format || TYPE_NAMES[pick.type]}</div>
          {#if pick.genres?.length}<div class="small muted">{pick.genres.slice(0, 3).join(' · ')}</div>{/if}
        </div>
      </button>
    {:else if error}
      <p class="error-text">{error}</p>
    {/if}
  </div>

  {#if !rolling && pick && places.length}
    <div class="small muted">{t('Disponible en')}</div>
    <div class="chips" style="margin:6px 0 12px">
      {#each places as place}<button class="chip" onclick={() => go(place)}>{place.sourceName} <span class="tag">{place.language}</span></button>{/each}
    </div>
  {/if}

  <div class="actions">
    <button class="btn primary" onclick={roll} disabled={rolling}><Icon name="shuffle" size={18} /> {t('Otro')}</button>
    {#if pick && places[0] && !rolling}<button class="btn outline" onclick={() => go(places[0])}>{t('Abrir en {0}', [places[0].sourceName])}</button>{/if}
  </div>
</Sheet>

<style>
  .stage { min-height: 196px; display: flex; align-items: center; margin: 12px 0; }
  .small-center { min-height: 160px; width: 100%; }
  .pick { display: flex; gap: 16px; align-items: center; text-align: left; width: 100%; }
  .pick img { width: 118px; height: 168px; object-fit: cover; border-radius: 12px; background: var(--surface-variant); flex: none; }
  .pick-title { font-weight: 600; font-size: 1.05rem; margin-bottom: 6px; }
  .type { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
  .dot { width: 6px; height: 6px; border-radius: 50%; }
  .actions { display: flex; gap: 12px; flex-wrap: wrap; }
</style>
