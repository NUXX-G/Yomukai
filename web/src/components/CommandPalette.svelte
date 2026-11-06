<script>
  // Global search (Ctrl+K, "/" or the search bar): type and press Enter; recent searches and sections a key away.
  import { onMount } from 'svelte';
  import { navigate } from '../lib/router.svelte.js';
  import { settings } from '../lib/store.svelte.js';
  import Icon from './Icon.svelte';

  let { onclose } = $props();
  let query = $state('');
  let input = $state();
  let active = $state(0);

  const jumps = [
    { label: 'Anime', href: '/anime', icon: 'tv' }, { label: 'Manga y manhwa', href: '/manga', icon: 'book' },
    { label: 'Novelas', href: '/novelas', icon: 'novel' }, { label: 'Biblioteca', href: '/biblioteca', icon: 'library' },
    { label: 'Calendario', href: '/calendario', icon: 'calendar' }, { label: 'Ajustes', href: '/ajustes', icon: 'settings' }
  ];
  const recent = $derived((settings.searchHistory || []).filter((h) => !query || h.toLowerCase().includes(query.toLowerCase())).slice(0, 6));
  const options = $derived([
    ...(query.trim() ? [{ label: `Buscar «${query.trim()}»`, run: () => go(query) }] : []),
    ...recent.map((h) => ({ label: h, icon: 'hourglass', run: () => go(h) })),
    ...(query.trim() ? [] : jumps.map((j) => ({ label: j.label, icon: j.icon, run: () => { onclose(); navigate(j.href); } })))
  ]);

  function go(q) {
    const text = q.trim();
    if (!text) return;
    onclose();
    navigate('/buscar?q=' + encodeURIComponent(text));
  }

  function key(e) {
    if (e.key === 'Escape') onclose();
    else if (e.key === 'ArrowDown') { e.preventDefault(); active = Math.min(active + 1, options.length - 1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); active = Math.max(active - 1, 0); }
    else if (e.key === 'Enter') { e.preventDefault(); options[active]?.run(); }
  }
  $effect(() => { query; active = 0; });
  onMount(() => input?.focus());
</script>

<div class="scrim" role="presentation" onclick={onclose}></div>
<div class="palette" role="dialog" aria-modal="true" aria-label="Buscar">
  <div class="field-row">
    <Icon name="search" />
    <input bind:this={input} bind:value={query} onkeydown={key} placeholder="Buscar anime, manga, manhwa, novelas..." aria-label="Buscar" enterkeyhint="search" />
    <kbd>Esc</kbd>
  </div>
  <ul role="listbox" aria-label="Sugerencias">
    {#each options as o, i (o.label)}
      <li role="option" aria-selected={i === active}>
        <button class:on={i === active} onmouseenter={() => (active = i)} onclick={o.run}>
          <Icon name={o.icon || 'search'} size={18} /><span>{o.label}</span>
        </button>
      </li>
    {/each}
  </ul>
</div>

<style>
  .scrim { position: fixed; inset: 0; z-index: 90; background: color-mix(in srgb, black 60%, transparent); backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px); animation: fade var(--motion-fast); }
  .palette {
    position: fixed; z-index: 91; left: 50%; top: max(10vh, calc(var(--safe-top) + 12px)); transform: translateX(-50%); width: min(640px, calc(100vw - 24px));
    background: var(--c-surface-raised); border: 1px solid var(--c-border); border-radius: var(--r-xl); box-shadow: var(--shadow-panel); overflow: hidden;
    animation: rise var(--motion-base) var(--motion-easing);
  }
  .field-row { display: flex; align-items: center; gap: 12px; padding: 0 16px; height: 60px; border-bottom: 1px solid var(--c-border); color: var(--c-text-dim); }
  input { flex: 1; min-width: 0; background: none; border: 0; outline: none; font-size: 1.05rem; color: var(--c-text); }
  kbd { font: inherit; font-size: 0.72rem; padding: 2px 7px; border-radius: var(--r-sm); border: 1px solid var(--c-border-strong); color: var(--c-text-faint); }
  ul { list-style: none; margin: 0; padding: 6px; max-height: 50vh; overflow-y: auto; }
  li button { width: 100%; display: flex; align-items: center; gap: 12px; padding: 11px 12px; border-radius: var(--r-md); text-align: left; color: var(--c-text-dim); }
  li button.on { background: var(--c-surface-high); color: var(--c-text); }
  @keyframes fade { from { opacity: 0; } }
  @keyframes rise { from { opacity: 0; transform: translate(-50%, 8px); } }
  @media (prefers-reduced-motion: reduce) { .scrim, .palette { animation: none; } }
</style>
