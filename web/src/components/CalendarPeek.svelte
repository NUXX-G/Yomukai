<script>
  // What a calendar entry shows on hover (computer) or long press (phone): cover, score, genres, synopsis, what the
  // group says about it, a button to open its page and, for this season's anime, recommend or comment it.
  import { t, tn } from '../lib/i18n.svelte.js';
  import { api } from '../lib/api.js';
  import { sized, score10, genreName } from '../lib/ui.js';
  import { session, featureOn } from '../lib/store.svelte.js';

  let { item, pick = null, onopen, onpicks = () => {}, compact = false } = $props();

  let editing = $state(false);
  let recommend = $state(true);
  let comment = $state('');
  let busy = $state(false);
  let error = $state('');

  const canPick = $derived(featureOn('season_social') && item.anilistId && session.user && !session.user.guest);

  function edit() {
    recommend = pick?.mine ? pick.mine.recommend : true;
    comment = pick?.mine?.comment || '';
    error = '';
    editing = true;
  }

  async function save() {
    if (!recommend && !comment.trim()) { error = t('Escribe un comentario o recomiéndalo'); return; }
    busy = true; error = '';
    try {
      onpicks(await api.seasonPick(item.anilistId, { recommend, comment: comment.trim() }));
      editing = false;
    } catch (e) { error = e.message || t('No se ha podido guardar'); }
    busy = false;
  }

  async function remove() {
    busy = true; error = '';
    try { onpicks(await api.seasonUnpick(item.anilistId)); editing = false; } catch (e) { error = e.message || t('No se ha podido guardar'); }
    busy = false;
  }

  const when = (ms) => new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
</script>

<div class="peek" class:compact>
  <div class="top">
    {#if item.cover}<img class="cover" src={sized(item.cover, 160)} alt="" loading="lazy" decoding="async" />{/if}
    <div class="facts">
      <strong class="title">{item.title}</strong>
      {#if item.label}<span class="label">{item.label}</span>{/if}
      <div class="chips">
        {#if item.score}<span class="score">★ {score10(item.score)}</span>{/if}
        {#each (item.genres || []).slice(0, 4) as g (g)}<span class="chip">{genreName(g)}</span>{/each}
      </div>
      {#if pick?.recommends}<span class="group">{tn(pick.recommends, 'Lo recomienda {n} del grupo', 'Lo recomiendan {n} del grupo')}</span>{/if}
    </div>
  </div>
  {#if item.synopsis}<p class="synopsis">{item.synopsis}</p>{/if}

  {#if pick?.comments?.length}
    <ul class="comments">
      {#each pick.comments.slice(0, compact ? 2 : 20) as c (c.name + c.at)}
        <li><b>{c.name}</b>{#if c.recommend} <span class="rec">{t('lo recomienda')}</span>{/if} <span class="when">{when(c.at)}</span><p>{c.comment}</p></li>
      {/each}
    </ul>
  {/if}

  {#if editing}
    <div class="form">
      <label class="check"><input type="checkbox" bind:checked={recommend} /> {t('Lo recomiendo al grupo')}</label>
      <textarea bind:value={comment} maxlength="500" rows="3" placeholder={t('Un comentario para el grupo (opcional)')}></textarea>
      {#if error}<p class="error">{error}</p>{/if}
      <div class="actions">
        <button class="btn primary" onclick={save} disabled={busy}>{t('Guardar')}</button>
        {#if pick?.mine}<button class="btn outline" onclick={remove} disabled={busy}>{t('Quitar lo mío')}</button>{/if}
        <button class="btn text" onclick={() => (editing = false)} disabled={busy}>{t('Cancelar')}</button>
      </div>
    </div>
  {:else}
    <div class="actions">
      <button class="btn primary" onclick={onopen}>{t('Abrir ficha')}</button>
      {#if canPick}
        <button class="btn tonal" onclick={edit}>{pick?.mine ? t('Cambiar mi recomendación') : t('Recomendar o comentar')}</button>
      {/if}
    </div>
    {#if error}<p class="error">{error}</p>{/if}
  {/if}
</div>

<style>
  .peek { display: flex; flex-direction: column; gap: 10px; }
  .top { display: flex; gap: 12px; align-items: flex-start; }
  .cover { width: 84px; aspect-ratio: 2 / 3; object-fit: cover; border-radius: var(--r-md, 10px); flex: none; background: var(--c-surface-high); }
  .compact .cover { width: 64px; }
  .facts { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
  .title { font-size: 1rem; line-height: 1.25; }
  .label { color: var(--c-text-dim); font-size: var(--fs-small); }
  .chips { display: flex; flex-wrap: wrap; gap: 4px; }
  .chip, .score { font-size: var(--fs-tiny); padding: 2px 8px; border-radius: 999px; background: var(--c-surface-high); color: var(--c-text-dim); }
  .score { color: var(--c-text); font-weight: 700; }
  .group { color: var(--c-accent); font-size: var(--fs-small); font-weight: 600; }
  .synopsis { margin: 0; font-size: var(--fs-small); color: var(--c-text-dim); line-height: 1.45; display: -webkit-box; -webkit-line-clamp: 6; line-clamp: 6; -webkit-box-orient: vertical; overflow: hidden; }
  .compact .synopsis { -webkit-line-clamp: 4; line-clamp: 4; }
  .comments { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
  .comments li { font-size: var(--fs-small); }
  .comments p { margin: 2px 0 0; white-space: pre-line; overflow-wrap: anywhere; }
  .rec { color: var(--c-accent); }
  .when { color: var(--c-text-faint); font-size: var(--fs-tiny); }
  .actions { display: flex; flex-wrap: wrap; gap: 8px; }
  .form { display: flex; flex-direction: column; gap: 8px; }
  .check { display: flex; gap: 8px; align-items: center; font-size: var(--fs-small); }
  textarea { width: 100%; box-sizing: border-box; resize: vertical; font: inherit; padding: 8px 10px; border-radius: var(--r-md, 10px); border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text); }
  .error { margin: 0; color: var(--c-danger); font-size: var(--fs-small); }
</style>
