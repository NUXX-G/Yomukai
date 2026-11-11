<script>
  // 16:9 panel for "continue": the work's banner or the episode's thumbnail; without a landscape image, a composition
  // (the poster on one side, title and what comes next on flat color with screentone), never a blurred stretch.
  import { sized } from '../lib/ui.js';
  let { href, image = '', fallback = '', title, line = '', aside = '', progress = null } = $props();
  let loaded = $state(false);
  let failed = $state(false);
</script>

<a class="wide" {href}>
  <div class="frame">
    {#if image && !failed}
      <img src={sized(image, 400)} alt="" loading="lazy" decoding="async" class:loaded onload={() => (loaded = true)} onerror={() => (failed = true)} />
    {:else}
      <div class="compose halftone">
        {#if fallback}<img class="poster" class:loaded src={sized(fallback, 200)} alt="" loading="lazy" decoding="async" onload={() => (loaded = true)} />{/if}
        <span class="big">{title}</span>
      </div>
    {/if}
    {#if progress != null}<span class="bar"><span style:width="{Math.round(Math.min(1, Math.max(0, progress)) * 100)}%"></span></span>{/if}
  </div>
  <div class="text">
    <div class="title">{title}</div>
    <div class="line"><span class="what">{line}</span>{#if aside}<span class="aside">{aside}</span>{/if}</div>
  </div>
</a>

<style>
  .wide { display: block; border-radius: var(--r-sm); }
  .frame {
    position: relative; aspect-ratio: 16 / 9; border-radius: var(--r-sm); overflow: hidden; background: var(--c-surface-high);
    border: var(--nb-border) solid var(--nb-line);
    transition: transform var(--motion-fast) var(--nb-press), box-shadow var(--motion-fast) var(--nb-press);
  }
  img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; opacity: 0; transition: opacity var(--motion-base); }
  img.loaded { opacity: 1; }
  .compose { position: absolute; inset: 0; display: flex; align-items: center; gap: 12px; background-color: var(--c-surface-high); }
  .compose .poster { position: relative; inset: auto; flex: none; width: auto; height: 100%; aspect-ratio: 2 / 3; border-right: var(--nb-border) solid var(--nb-line); }
  .big {
    font-family: var(--font-display); font-size: 0.95rem; line-height: 1.2; padding-right: 12px; color: var(--c-text);
    display: -webkit-box; -webkit-line-clamp: 3; line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
  }
  .compose:not(:has(.poster)) .big { padding-left: 14px; }
  .bar { position: absolute; left: 0; right: 0; bottom: 0; height: 5px; background: var(--c-canvas); }
  .bar span { display: block; height: 100%; background: var(--c-accent); }
  .text { padding: 8px 2px 0; }
  .title { font-weight: 650; font-size: 0.92rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .line { display: flex; gap: 8px; color: var(--c-text-dim); font-size: var(--fs-small); margin-top: 2px; }
  .what { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .aside { flex: none; }
  @media (hover: hover) and (pointer: fine) {
    .wide:hover .frame { transform: translate(-2px, -2px); box-shadow: var(--nb-shadow-hover) var(--nb-shadow-hover) 0 var(--c-accent); }
  }
  .wide:focus-visible { outline: none; }
  .wide:focus-visible .frame { outline: 3px solid var(--c-accent2); outline-offset: 2px; }
  @media (prefers-reduced-motion: reduce) { .frame, img { transition: none; } .wide:hover .frame { transform: none; } }
</style>
