<script>
  // The first visit: what Yomukai is and where things are, once. Closing it remembers it in this browser.
  import { t } from '../lib/i18n.svelte.js';
  import Icon from './Icon.svelte';

  const KEY = 'yomukai.welcomed';
  let open = $state((() => { try { return !localStorage.getItem(KEY); } catch { return false; } })());
  function close() {
    open = false;
    try { localStorage.setItem(KEY, '1'); } catch { /* private mode */ }
  }
</script>

{#if open}
  <section class="welcome" aria-label={t('Bienvenida')}>
    <div class="text">
      <h2>{t('Bienvenido a Yomukai')}</h2>
      <ul>
        <li><Icon name="search" size={18} /> {t('Busca cualquier anime, manga, manhwa, novela o cómic: se busca en todas tus fuentes a la vez y sale una tarjeta por obra.')}</li>
        <li><Icon name="library" size={18} /> {t('Guarda lo que sigues en tu biblioteca: te avisa de capítulos nuevos y se sincroniza con la app.')}</li>
        <li><Icon name="download" size={18} /> {t('Descarga capítulos y episodios para verlos sin conexión, desde la ficha de cada obra.')}</li>
        <li><Icon name="settings" size={18} /> {t('En Ajustes: idioma, temas, importar tu lista de AniList/MAL/Mihon y la app de Android.')}</li>
      </ul>
    </div>
    <button class="btn primary" onclick={close}>{t('Entendido')}</button>
  </section>
{/if}

<style>
  .welcome {
    margin: 16px var(--s-page, 16px) 0; padding: 18px 20px; border-radius: var(--r-card, 16px);
    background: var(--c-surface-raised); border: 1px solid var(--c-border);
    display: flex; gap: 16px; align-items: flex-end; flex-wrap: wrap;
  }
  .text { flex: 1; min-width: 240px; }
  h2 { margin: 0 0 8px; font-size: var(--fs-section); }
  ul { margin: 0; padding: 0; list-style: none; display: grid; gap: 6px; color: var(--c-text-dim); font-size: var(--fs-small); }
  li { display: flex; gap: 8px; align-items: flex-start; }
  li :global(svg) { flex: none; margin-top: 1px; color: var(--c-accent); }
</style>
