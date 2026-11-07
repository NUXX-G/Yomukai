<script>
  // Bring a library from AniList, MyAnimeList or a Mihon/Tachiyomi backup. The server looks for each title in your
  // sources a little at a time (only a confirmed same work is added), with its status and what you already read.
  import { onDestroy } from 'svelte';
  import { api } from '../lib/api.js';
  import { notify } from '../lib/store.svelte.js';
  import { t } from '../lib/i18n.svelte.js';

  let origin = $state('anilist');
  let user = $state('');
  let busy = $state(false);
  let status = $state(null);
  let fileInput = $state();
  let timer;

  async function refresh() {
    try { status = await api.importStatus(); } catch { /* not important */ }
    clearTimeout(timer);
    if (status?.pending > 0) timer = setTimeout(refresh, 8000);
  }
  refresh();
  onDestroy(() => clearTimeout(timer));

  async function start(e) {
    e?.preventDefault();
    busy = true;
    try {
      const r = origin === 'mihon' ? await api.importMihon(fileInput.files[0]) : await api.importList(origin, user.trim());
      notify(t('{0} títulos en cola: aparecerán en tu biblioteca poco a poco', [r.queued]));
      refresh();
    } catch (err) { notify(err.message); } finally { busy = false; }
  }
</script>

<section class="card">
  <h3 class="sub">{t('Importar de otra app')}</h3>
  <p class="small muted" style="margin-top:0">{t('Trae tu lista de AniList o MyAnimeList (públicas, por nombre de usuario) o una copia de Mihon/Tachiyomi. Cada obra se busca en tus fuentes y se añade con su estado y lo que ya llevabas leído o visto.')}</p>
  <div class="wrap">
    <button class="chip" class:on={origin === 'anilist'} onclick={() => (origin = 'anilist')}>AniList</button>
    <button class="chip" class:on={origin === 'mal'} onclick={() => (origin = 'mal')}>MyAnimeList</button>
    <button class="chip" class:on={origin === 'mihon'} onclick={() => (origin = 'mihon')}>Mihon / Tachiyomi</button>
  </div>
  <form class="row" onsubmit={start}>
    {#if origin === 'mihon'}
      <input class="field" type="file" accept=".tachibk,.proto.gz,application/gzip" bind:this={fileInput} aria-label={t('Copia de seguridad')} />
    {:else}
      <input class="field" placeholder={t('Tu usuario de {0}', [origin === 'mal' ? 'MyAnimeList' : 'AniList'])} bind:value={user} maxlength="40" autocomplete="off" />
    {/if}
    <button class="btn primary" disabled={busy}>{busy ? t('Leyendo...') : t('Importar')}</button>
  </form>
  {#if status && (status.pending || status.done || status.notFound)}
    <p class="small">
      {t('Añadidas: {0} · en cola: {1} · no encontradas: {2}', [status.done, status.pending, status.notFound])}
    </p>
    {#if status.notFoundTitles?.length}
      <details class="small muted">
        <summary>{t('Las que no están en tus fuentes')}</summary>
        <p>{status.notFoundTitles.join(' · ')}</p>
      </details>
    {/if}
  {/if}
</section>

<style>
  .wrap { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
  .row { display: flex; gap: 8px; flex-wrap: wrap; }
  .row .field { flex: 1; min-width: 180px; }
</style>
