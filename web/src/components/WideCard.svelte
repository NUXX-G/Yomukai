<script>
  // 16:9 card for "continue": the work's banner (or its cover, blurred), the progress, and what comes next.
  import { sized } from '../lib/ui.js';
  let { href, image = '', fallback = '', title, line = '', aside = '', progress = null } = $props();
  let loaded = $state(false);
</script>

<a class="wide" {href}>
  <div class="frame">
    {#if image}
      <img src={sized(image, 400)} alt="" loading="lazy" decoding="async" class:loaded onload={() => (loaded = true)} />
    {:else if fallback}
      <img class="blurred" class:loaded src={sized(fallback, 200)} alt="" loading="lazy" decoding="async" onload={() => (loaded = true)} />
      <img class="poster" src={sized(fallback, 200)} alt="" loading="lazy" decoding="async" />
    {/if}
    <span class="shade"></span>
    {#if progress != null}<span class="bar"><span style:width="{Math.round(Math.min(1, Math.max(0, progress)) * 100)}%"></span></span>{/if}
  </div>
  <div class="text">
    <div class="title">{title}</div>
    <div class="line"><span class="what">{line}</span>{#if aside}<span class="aside">{aside}</span>{/if}</div>
  </div>
</a>

<style>
  .wide { display: block; border-radius: var(--r-md); }
  .frame { position: relative; aspect-ratio: 16 / 9; border-radius: var(--r-md); overflow: hidden; background: var(--c-surface-high); transition: transform var(--motion-base) var(--motion-easing); }
  img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; opacity: 0; transition: opacity var(--motion-slow); }
  img.loaded { opacity: 1; }
  .blurred { filter: blur(18px) brightness(0.55); transform: scale(1.2); }
  .poster { left: 50%; transform: translateX(-50%); width: auto; height: 100%; aspect-ratio: 2 / 3; opacity: 1; box-shadow: var(--shadow-card); }
  .shade { position: absolute; inset: auto 0 0 0; height: 40%; background: linear-gradient(to top, rgba(0, 0, 0, 0.6), transparent); }
  .bar { position: absolute; left: 0; right: 0; bottom: 0; height: 4px; background: color-mix(in srgb, white 20%, transparent); }
  .bar span { display: block; height: 100%; background: var(--c-accent); }
  .text { padding: 8px 2px 0; }
  .title { font-weight: 650; font-size: 0.92rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .line { display: flex; gap: 8px; color: var(--c-text-dim); font-size: var(--fs-small); margin-top: 2px; }
  .what { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .aside { flex: none; }
  @media (hover: hover) and (pointer: fine) { .wide:hover .frame { transform: scale(1.02); } .wide:hover .title { color: var(--c-accent); } }
  .wide:focus-visible .frame { box-shadow: 0 0 0 2px var(--c-accent); }
  @media (prefers-reduced-motion: reduce) { .frame, img { transition: none; } .wide:hover .frame { transform: none; } }
</style>
