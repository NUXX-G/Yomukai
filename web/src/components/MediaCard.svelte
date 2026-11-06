<script>
  // Poster card, 2:3: the cover takes the space; small badges sit on it; title and a grey line below.
  // On a computer, hovering lifts it and shows more; on a phone, a long press does the same.
  import { TYPE_NAMES } from '../lib/format.js';
  import { sized } from '../lib/ui.js';

  let {
    title, cover, href = undefined, type = undefined, subtitle = undefined, showType = false, onclick = undefined,
    /** Accent color of the cover (AniList), shown while it loads. */
    color = '',
    /** Small badges over the cover: { text, tone: 'accent' | 'dark' } */
    badges = [],
    /** 0..1 progress bar at the bottom of the cover. */
    progress = null,
    /** "8,4" */
    score = '',
    /** Lines for the hover panel: genres, synopsis. */
    detail = '',
    /** Set when it may be the same work as another card. */
    doubt = false
  } = $props();

  let failed = $state(false);
  let loaded = $state(false);
  let peek = $state(false);
  let timer;
  function pressStart() { timer = setTimeout(() => (peek = true), 450); }
  function pressEnd() { clearTimeout(timer); }
</script>

<svelte:element
  this={href ? 'a' : 'button'} {href} class="poster-card" class:peek {onclick} type={href ? undefined : 'button'}
  ontouchstart={pressStart} ontouchend={pressEnd} ontouchmove={pressEnd} oncontextmenu={(e) => { if (peek) e.preventDefault(); }}
  onmouseleave={() => (peek = false)} aria-label={title}
>
  <div class="cover" style:--placeholder={color || 'var(--c-surface-high)'}>
    {#if cover && !failed}
      <img src={sized(cover, 220)} alt="" loading="lazy" decoding="async" class:loaded onload={() => (loaded = true)} onerror={() => (failed = true)} />
    {:else}
      <span class="fallback">{title?.slice(0, 1)}</span>
    {/if}
    {#if badges.length}
      <div class="badges">{#each badges as b, i (i)}<span class="badge {b.tone || 'dark'}">{b.text}</span>{/each}</div>
    {/if}
    {#if score}<span class="score">★ {score}</span>{/if}
    {#if progress != null}<span class="bar"><span style:width="{Math.round(Math.min(1, Math.max(0, progress)) * 100)}%"></span></span>{/if}
    {#if detail}
      <div class="more" aria-hidden="true"><p>{detail}</p></div>
    {/if}
  </div>
  <div class="title">{title}</div>
  {#if showType && type}
    <div class="meta">{subtitle || TYPE_NAMES[type] || type}</div>
  {:else if subtitle}
    <div class="meta">{subtitle}</div>
  {/if}
  {#if doubt}<div class="meta doubt" title="Puede ser la misma obra que otra tarjeta: no se ha podido confirmar">¿Misma obra?</div>{/if}
</svelte:element>

<style>
  .poster-card { display: block; text-align: left; min-width: 0; border-radius: var(--r-md); outline-offset: 3px; }
  .cover {
    position: relative; aspect-ratio: 2 / 3; border-radius: var(--r-md); overflow: hidden;
    background: linear-gradient(160deg, color-mix(in srgb, var(--placeholder) 55%, var(--c-surface)), var(--c-surface));
    box-shadow: inset 0 0 0 1px color-mix(in srgb, white 4%, transparent);
    transition: transform var(--motion-base) var(--motion-easing), box-shadow var(--motion-base);
  }
  .cover img { width: 100%; height: 100%; object-fit: cover; opacity: 0; filter: blur(8px); transform: scale(1.04); transition: opacity var(--motion-slow), filter var(--motion-slow), transform var(--motion-slow); }
  .cover img.loaded { opacity: 1; filter: none; transform: none; }
  .fallback { position: absolute; inset: 0; display: grid; place-items: center; font-size: 2.4rem; font-weight: 800; color: color-mix(in srgb, white 25%, transparent); }
  .badges { position: absolute; top: 6px; right: 6px; display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
  .badge { font-size: var(--fs-tiny); font-weight: 700; padding: 2px 7px; border-radius: var(--r-pill); line-height: 1.5; }
  .badge.accent { background: var(--c-accent); color: var(--c-on-accent); }
  .badge.dark { background: color-mix(in srgb, black 70%, transparent); color: var(--c-text); backdrop-filter: blur(6px); }
  .score { position: absolute; left: 6px; bottom: 8px; font-size: var(--fs-tiny); font-weight: 700; padding: 2px 7px; border-radius: var(--r-pill); background: color-mix(in srgb, black 70%, transparent); }
  .bar { position: absolute; left: 0; right: 0; bottom: 0; height: 3px; background: color-mix(in srgb, white 18%, transparent); }
  .bar span { display: block; height: 100%; background: var(--c-accent); }
  .more {
    position: absolute; inset: 0; display: flex; align-items: flex-end; padding: 10px; opacity: 0;
    background: linear-gradient(to top, rgba(0, 0, 0, 0.92) 30%, rgba(0, 0, 0, 0.2));
    transition: opacity var(--motion-base);
  }
  .more p { margin: 0; font-size: var(--fs-tiny); line-height: 1.45; color: var(--c-text); display: -webkit-box; -webkit-line-clamp: 7; line-clamp: 7; -webkit-box-orient: vertical; overflow: hidden; }
  .title { margin: 8px 2px 0; font-size: 0.86rem; font-weight: 600; line-height: 1.3; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .meta { margin: 2px; font-size: 0.76rem; color: var(--c-text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .doubt { color: var(--c-warning); }
  .peek .more { opacity: 1; }
  @media (hover: hover) and (pointer: fine) {
    .poster-card:hover .cover { transform: translateY(-3px) scale(1.03); box-shadow: var(--shadow-card); }
    .poster-card:hover .more { opacity: 1; }
    .poster-card:hover .title { color: var(--c-accent); }
  }
  .poster-card:focus-visible .cover { box-shadow: 0 0 0 2px var(--c-accent); }
  @media (prefers-reduced-motion: reduce) {
    .cover, .cover img { transition: none; }
    .poster-card:hover .cover { transform: none; }
  }
</style>
