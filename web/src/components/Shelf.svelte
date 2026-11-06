<script>
  // A titled row: big section title, optional "Ver todo", then a horizontal shelf of cards (posters or wide cards).
  import Row from './Row.svelte';
  import Icon from './Icon.svelte';
  let { title, subtitle = '', href = undefined, wide = false, loading = false, children, actions } = $props();
</script>

<section class="shelf-section" aria-label={title}>
  <header>
    <div class="titles">
      <h2>{title}</h2>
      {#if subtitle}<p>{subtitle}</p>{/if}
    </div>
    {@render actions?.()}
    {#if href}<a class="all" {href}>Ver todo <Icon name="chevronRight" size={18} /></a>{/if}
  </header>
  <Row class={wide ? 'wide' : 'posters'}>
    {#if loading}
      {#each Array(wide ? 4 : 8) as _, i (i)}<div class="skeleton" class:w={wide}></div>{/each}
    {:else}
      {@render children?.()}
    {/if}
  </Row>
</section>

<style>
  .shelf-section { margin-top: var(--s-section); }
  header { display: flex; align-items: flex-end; gap: 12px; margin-bottom: 14px; }
  .titles { flex: 1; min-width: 0; }
  h2 { margin: 0; font-size: var(--fs-section); font-weight: 750; letter-spacing: -0.01em; }
  p { margin: 2px 0 0; color: var(--c-text-dim); font-size: var(--fs-small); }
  .all { display: inline-flex; align-items: center; gap: 2px; color: var(--c-text-dim); font-size: var(--fs-small); font-weight: 600; padding: 4px 6px; border-radius: var(--r-sm); }
  .all:hover { color: var(--c-text); }
  .skeleton { aspect-ratio: 2 / 3; border-radius: var(--r-md); background: linear-gradient(100deg, var(--c-surface) 40%, var(--c-surface-high) 50%, var(--c-surface) 60%) 0 0 / 200% 100%; animation: shimmer 1.4s infinite linear; }
  .skeleton.w { aspect-ratio: 16 / 9; }
  @keyframes shimmer { to { background-position: -200% 0; } }
  @media (prefers-reduced-motion: reduce) { .skeleton { animation: none; } }
  :global(.row.posters), :global(.row.wide) { align-items: flex-start; }
  :global(.row.posters > *) { width: 136px; }
  :global(.row.wide > *) { width: 280px; }
  @media (min-width: 900px) {
    :global(.row.posters > *) { width: 168px; }
    :global(.row.wide > *) { width: 380px; }
  }
</style>
