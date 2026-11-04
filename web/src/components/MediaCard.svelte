<script>
  // Poster card: the cover and at most two lines of title, nothing painted over the cover.
  import { TYPE_COLORS, TYPE_NAMES } from '../lib/format.js';
  let { title, cover, href = undefined, type = undefined, subtitle = undefined, showType = false, onclick = undefined } = $props();
  let failed = $state(false);
  let loaded = $state(false);
</script>

<svelte:element this={href ? 'a' : 'button'} {href} class="card-link" {onclick} type={href ? undefined : 'button'}>
  <div class="cover">
    {#if cover && !failed}
      <img src={cover} alt="" loading="lazy" decoding="async" class:loaded onload={() => (loaded = true)} onerror={() => (failed = true)} />
    {/if}
  </div>
  <div class="title">{title}</div>
  {#if showType && type}
    <div class="meta"><span class="dot" style:background={TYPE_COLORS[type]}></span>{subtitle || TYPE_NAMES[type] || type}</div>
  {:else if subtitle}
    <div class="meta">{subtitle}</div>
  {/if}
</svelte:element>

<style>
  .card-link { display: block; text-align: left; border-radius: 14px; min-width: 0; }
  .cover { aspect-ratio: 0.7; border-radius: 14px; overflow: hidden; background: var(--surface-variant); }
  /* Covers fade in as they arrive instead of popping in all at once. */
  .cover img { width: 100%; height: 100%; object-fit: cover; opacity: 0; transition: transform 0.25s, opacity 0.22s ease-out; }
  .cover img.loaded { opacity: 1; }
  .card-link:hover .cover img { transform: scale(1.03); }
  @media (hover: hover) { .title { transition: color 0.15s; } .card-link:hover .title { color: var(--primary); } }
  .title { margin: 6px 2px 0; font-size: 0.84rem; font-weight: 600; line-height: 1.25; min-height: 2.5em; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .meta { margin: 2px; font-size: 0.74rem; color: var(--text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: flex; align-items: center; gap: 5px; }
  .dot { width: 6px; height: 6px; border-radius: 50%; flex: none; }
</style>
