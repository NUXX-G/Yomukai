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
  .tab { flex: none; padding: 8px 16px; border-radius: 999px; border: 1px solid var(--c-border); background: var(--c-surface-high); color: inherit; text-decoration: none; font-weight: 600; font-size: 0.92rem; white-space: nowrap; }
  .tab.on { background: var(--c-accent); color: var(--c-on-accent); border-color: transparent; }
  .tab:focus-visible { outline: 2px solid var(--c-accent); outline-offset: 2px; }
</style>
