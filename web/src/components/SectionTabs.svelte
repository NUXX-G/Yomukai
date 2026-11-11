<script>
  // The kinds of work, always in the same place (F3.3): Todo · Anime · Manga · Manhwa y manhua · Cómics · Novelas.
  import { t } from '../lib/i18n.svelte.js';
  import { route } from '../lib/router.svelte.js';

  const TABS = [
    { name: 'home', href: '/', label: 'Todo' },
    { name: 'anime', href: '/anime', label: 'Anime' },
    { name: 'manga', href: '/manga', label: 'Manga' },
    { name: 'manhwa', href: '/manhwa', label: 'Manhwa y manhua' },
    { name: 'comics', href: '/comics', label: 'Cómics' },
    { name: 'novels', href: '/novelas', label: 'Novelas' }
  ];
</script>

<nav class="section-tabs" aria-label={t('Tipo de obra')}>
  {#each TABS as tab (tab.name)}
    <a href={tab.href} class="tab" class:on={route.name === tab.name} aria-current={route.name === tab.name ? 'page' : undefined}>{t(tab.label)}</a>
  {/each}
</nav>

<style>
  .section-tabs { display: flex; gap: 8px; overflow-x: auto; padding: 4px 16px 12px; scrollbar-width: none; }
  .section-tabs::-webkit-scrollbar { display: none; }
  /* Tabs as ink-bordered labels; the open one is printed in white with a hard shadow, like a stamped page tab. */
  .tab { flex: none; padding: 7px 16px; border-radius: 4px; border: 2px solid color-mix(in srgb, var(--c-ink) 35%, transparent); background: var(--c-surface); color: var(--c-text-dim); text-decoration: none; font-weight: 750; font-size: 0.92rem; white-space: nowrap; transition: border-color 90ms linear, color 90ms linear; }
  .tab:hover { border-color: var(--c-ink); color: var(--c-text); }
  .tab.on { background: var(--c-ink); color: var(--c-canvas); border-color: var(--c-ink); box-shadow: 3px 3px 0 var(--c-accent); }
  .tab:focus-visible { outline: 3px solid var(--c-accent); outline-offset: 3px; }
</style>
