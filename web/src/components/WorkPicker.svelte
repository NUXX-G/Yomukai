<script>
  import { t } from '../lib/i18n.svelte.js';
  // Find a work without leaving the chat or the forum editor (F4.3): the index's instant search, one tap to pick it.
  import { api } from '../lib/api.js';
  import Icon from './Icon.svelte';

  let { onpick, onclose = undefined } = $props();
  let query = $state('');
  let results = $state([]);
  let busy = $state(false);
  let timer;
  let asked = 0;

  function search() {
    clearTimeout(timer);
    const q = query.trim();
    if (q.length < 2) { results = []; return; }
    timer = setTimeout(async () => {
      const mine = ++asked;
      busy = true;
      try {
        const r = await api.obrasBuscar(q);
        if (mine === asked) results = (r?.works || []).slice(0, 12);
      } catch { if (mine === asked) results = []; } finally { if (mine === asked) busy = false; }
    }, 250);
  }

  function pick(w) {
    onpick({ kind: 'work', workKey: w.key, sourceId: '', mediaId: '', title: w.title, cover: w.cover || '', type: w.type || '', lang: (w.langs || [])[0] || '' });
  }
</script>

<div class="picker">
  <div class="field">
    <Icon name="search" size={18} />
    <!-- svelte-ignore a11y_autofocus -->
    <input bind:value={query} oninput={search} placeholder={t('Buscar una obra para compartir...')} aria-label={t('Buscar una obra para compartir')} autofocus maxlength="100" />
    {#if onclose}<button class="icon-btn" onclick={onclose} aria-label={t('Cerrar')}><Icon name="close" size={18} /></button>{/if}
  </div>
  {#if busy}<div class="muted small pad">{t('Buscando...')}</div>{/if}
  <ul>
    {#each results as w (w.key)}
      <li>
        <button onclick={() => pick(w)}>
          <span class="cover">{#if w.cover}<img src={w.cover} alt="" loading="lazy" />{/if}</span>
          <span class="name">{w.title}</span>
          <span class="muted small">{w.type || ''}</span>
        </button>
      </li>
    {:else}
      {#if query.trim().length >= 2 && !busy}<li class="muted small pad">{t('Sin resultados')}</li>{/if}
    {/each}
  </ul>
</div>

<style>
  .picker { display: flex; flex-direction: column; gap: 6px; background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--r-lg, 12px); padding: 8px; max-height: 320px; }
  .field { display: flex; align-items: center; gap: 8px; padding: 0 6px; }
  .field input { flex: 1; min-width: 0; background: transparent; border: 0; color: inherit; font: inherit; padding: 8px 0; outline: none; }
  ul { list-style: none; margin: 0; padding: 0; overflow-y: auto; }
  li button { display: flex; align-items: center; gap: 10px; width: 100%; padding: 6px; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; border-radius: 8px; cursor: pointer; }
  li button:hover, li button:focus-visible { background: var(--c-surface-high); }
  .cover { width: 32px; height: 44px; border-radius: 6px; overflow: hidden; background: var(--c-surface-high); flex: none; }
  .cover img { width: 100%; height: 100%; object-fit: cover; }
  .name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .pad { padding: 6px; }
  .small { font-size: var(--fs-small, 0.85rem); }
</style>
