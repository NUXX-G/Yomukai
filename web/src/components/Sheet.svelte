<script>
  // Bottom sheet on phones, centered panel on wide screens. Closes with Escape or a tap outside.
  let { open = $bindable(false), title = '', children } = $props();

  function key(e) { if (e.key === 'Escape') open = false; }
</script>

<svelte:window onkeydown={open ? key : undefined} />

{#if open}
  <div class="scrim" onclick={() => (open = false)} role="presentation"></div>
  <div class="sheet" role="dialog" aria-modal="true" aria-label={title}>
    <div class="handle"></div>
    {#if title}<h3>{title}</h3>{/if}
    <div class="body">{@render children?.()}</div>
  </div>
{/if}

<style>
  .scrim { position: fixed; inset: 0; background: #000a; z-index: 60; animation: fade 0.2s; }
  .sheet { position: fixed; left: 0; right: 0; bottom: 0; z-index: 61; max-height: 88vh; max-height: 88dvh; overflow-y: auto; overscroll-behavior: contain; background: var(--surface-low); border-radius: 24px 24px 0 0; padding: 8px 20px calc(20px + var(--safe-bottom)); animation: up 0.25s cubic-bezier(0.2, 0.8, 0.2, 1); }
  @media (min-width: 760px) { .sheet { left: 50%; right: auto; bottom: 50%; transform: translate(-50%, 50%); width: 560px; border-radius: 24px; animation: fade 0.2s; } }
  .handle { width: 36px; height: 4px; border-radius: 2px; background: var(--outline); margin: 6px auto 12px; }
  h3 { margin: 0 0 12px; font-size: 1.1rem; }
  @keyframes up { from { transform: translateY(40px); opacity: 0; } }
  @keyframes fade { from { opacity: 0; } }
</style>
