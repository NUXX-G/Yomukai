<script>
  import { t } from '../lib/i18n.svelte.js';
  // Poster card, 2:3, as a manga panel (ESTILO.md): ink border, the cover cropped the same at every size, small badges
  // on it, title and a grey line below. Black and white with one accent: each type of work is told apart by a shape
  // in ink, not a color: anime a play corner, manga a folded corner, manhwa a strip on the side, comic a hard shadow
  // at rest and a starburst mark, novel a book spine. On a computer, hovering lifts it with a hard shadow in the
  // accent and shows more; on a phone, a long press does the same.
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

  const KIND = { ANIME: 'anime', MANGA: 'manga', MANHWA: 'manhwa', MANHUA: 'manhwa', COMIC: 'comic', NOVEL: 'novel' };
  let kind = $derived(KIND[type] || '');

  let failed = $state(false);
  let loaded = $state(false);
  let peek = $state(false);
  let timer;
  function pressStart() { timer = setTimeout(() => (peek = true), 450); }
  function pressEnd() { clearTimeout(timer); }
</script>

<svelte:element
  this={href ? 'a' : 'button'} {href} class="poster-card" class:peek {onclick} type={href ? undefined : 'button'} data-kind={kind || undefined}
  ontouchstart={pressStart} ontouchend={pressEnd} ontouchmove={pressEnd} oncontextmenu={(e) => { if (peek) e.preventDefault(); }}
  onmouseleave={() => (peek = false)} aria-label={title}
>
  <div class="cover" style:--placeholder={color || 'var(--c-surface-high)'}>
    {#if cover && !failed}
      <img src={sized(cover, 220)} alt="" loading="lazy" decoding="async" class:loaded onload={() => (loaded = true)} onerror={() => (failed = true)} />
    {/if}
    {#if !cover || failed || !loaded}
      <!-- The house placeholder: the type's color with screentone and the title in the display face. -->
      <span class="fallback halftone" aria-hidden="true"><span>{title}</span></span>
    {/if}
    {#if badges.length}
      <div class="badges">{#each badges as b, i (i)}<span class="badge {b.tone || 'dark'}">{b.text}</span>{/each}</div>
    {/if}
    {#if score}<span class="score">★ {score}</span>{/if}
    {#if kind === 'anime'}<span class="play" aria-hidden="true"></span>{/if}
    {#if progress != null}<span class="bar"><span style:width="{Math.round(Math.min(1, Math.max(0, progress)) * 100)}%"></span></span>{/if}
    {#if detail}
      <div class="more" aria-hidden="true"><p>{detail}</p></div>
    {/if}
  </div>
  <div class="title">{title}</div>
  {#if showType && type}
    <div class="meta"><span class="type-mark" aria-hidden="true"></span>{subtitle || TYPE_NAMES[type] || type}</div>
  {:else if subtitle}
    <div class="meta">{subtitle}</div>
  {/if}
  {#if doubt}<div class="meta doubt" title={t('Puede ser la misma obra que otra tarjeta: no se ha podido confirmar')}>{t('¿Misma obra?')}</div>{/if}
</svelte:element>

<style>
  .poster-card { --kind: var(--c-ink); display: block; text-align: left; min-width: 0; border-radius: var(--r-sm); outline-offset: 3px; }
  .poster-card[data-kind='anime'] { --kind: var(--c-type-anime); }
  .poster-card[data-kind='manga'] { --kind: var(--c-type-manga); }
  .poster-card[data-kind='manhwa'] { --kind: var(--c-type-manhwa); }
  .poster-card[data-kind='comic'] { --kind: var(--c-type-comic); }
  .poster-card[data-kind='novel'] { --kind: var(--c-type-novel); }
  .cover {
    position: relative; aspect-ratio: 2 / 3; border-radius: var(--r-sm); overflow: hidden;
    border: var(--nb-border) solid var(--nb-line);
    background: color-mix(in srgb, var(--placeholder) 45%, var(--c-surface));
    transition: transform var(--motion-fast) var(--nb-press), box-shadow var(--motion-fast) var(--nb-press);
  }
  /* Comics carry their yellow shadow at rest. */
  [data-kind='comic'] .cover { box-shadow: 3px 3px 0 var(--c-type-comic); }
  /* Manhwa: a strip down the side, like a vertical scroll. */
  [data-kind='manhwa'] .cover::before { content: ''; position: absolute; z-index: 2; top: 0; bottom: 0; right: 0; width: 5px; background: var(--c-type-manhwa); border-left: var(--nb-border) solid var(--nb-line); }
  /* Novel: a book spine on the left. */
  [data-kind='novel'] .cover::before { content: ''; position: absolute; z-index: 2; top: 0; bottom: 0; left: 0; width: 6px; background: var(--c-type-novel); border-right: var(--nb-border) solid var(--nb-line); }
  /* Manga: the bottom corner folded, like a page being turned. */
  [data-kind='manga'] .cover::after {
    content: ''; position: absolute; z-index: 2; right: -1px; bottom: -1px; width: 18px; height: 18px;
    background: linear-gradient(135deg, var(--c-type-manga) 50%, var(--c-canvas) 50%); border-top: var(--nb-border) solid var(--nb-line); border-left: var(--nb-border) solid var(--nb-line);
    border-top-left-radius: 3px;
  }
  .cover img { position: relative; z-index: 1; width: 100%; height: 100%; object-fit: cover; opacity: 0; transition: opacity var(--motion-base); }
  .cover img.loaded { opacity: 1; }
  .fallback {
    position: absolute; inset: 0; display: grid; place-items: center; padding: 10px; text-align: center;
    background-color: color-mix(in srgb, var(--kind) 22%, var(--c-surface));
  }
  .fallback span {
    font-family: var(--font-display); font-size: 0.9rem; line-height: 1.2; color: var(--c-text); opacity: 0.85;
    display: -webkit-box; -webkit-line-clamp: 5; line-clamp: 5; -webkit-box-orient: vertical; overflow: hidden; word-break: break-word;
  }
  .badges { position: absolute; z-index: 3; top: 6px; right: 6px; display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
  [data-kind='manhwa'] .badges { right: 10px; }
  .badge { font-size: var(--fs-tiny); font-weight: 800; padding: 1px 6px; border-radius: 3px; line-height: 1.5; border: 1.5px solid var(--c-canvas); }
  .badge.accent { background: var(--c-accent2); color: var(--c-on-accent2); }
  .badge.dark { background: var(--c-canvas); color: var(--c-text); }
  .score { position: absolute; z-index: 3; left: 6px; bottom: 8px; font-size: var(--fs-tiny); font-weight: 800; padding: 1px 6px; border-radius: 3px; background: var(--c-canvas); color: var(--c-accent2); }
  [data-kind='novel'] .score { left: 12px; }
  /* Anime: a small play button in the corner. */
  .play { position: absolute; z-index: 3; left: 6px; top: 6px; width: 22px; height: 22px; border-radius: 50%; background: var(--c-type-anime); border: 1.5px solid var(--c-canvas); }
  .play::after { content: ''; position: absolute; left: 7.5px; top: 5px; border-style: solid; border-width: 5px 0 5px 8px; border-color: transparent transparent transparent var(--c-on-type); }
  .bar { position: absolute; z-index: 3; left: 0; right: 0; bottom: 0; height: 5px; background: var(--c-canvas); }
  .bar span { display: block; height: 100%; background: var(--c-accent); }
  .more {
    position: absolute; z-index: 4; inset: 0; display: flex; align-items: flex-end; padding: 10px; opacity: 0;
    background: color-mix(in srgb, var(--c-canvas) 90%, transparent);
    transition: opacity var(--motion-base);
  }
  .more p { margin: 0; font-size: var(--fs-tiny); line-height: 1.45; color: var(--c-text); display: -webkit-box; -webkit-line-clamp: 9; line-clamp: 9; -webkit-box-orient: vertical; overflow: hidden; }
  .title { margin: 8px 2px 0; font-size: 0.86rem; font-weight: 650; line-height: 1.3; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .meta { margin: 2px; font-size: 0.76rem; color: var(--c-text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: flex; align-items: center; gap: 5px; }
  .type-mark { flex: none; width: 9px; height: 9px; background: var(--kind); border: 1.5px solid var(--nb-line); border-radius: 2px; }
  [data-kind='anime'] .type-mark { border-radius: 50%; }
  [data-kind='comic'] .type-mark { width: 11px; height: 11px; border: 0; clip-path: polygon(50% 0, 61% 32%, 98% 35%, 68% 57%, 79% 91%, 50% 70%, 21% 91%, 32% 57%, 2% 35%, 39% 32%); }
  .doubt { color: var(--c-warning); }
  .peek .more { opacity: 1; }
  @media (hover: hover) and (pointer: fine) {
    .poster-card:hover .cover { transform: translate(-2px, -2px); box-shadow: var(--nb-shadow-hover) var(--nb-shadow-hover) 0 var(--c-accent); }
    .poster-card:active .cover { transform: translate(1px, 1px); box-shadow: 0 0 0 var(--c-accent); }
    .poster-card:hover .more { opacity: 1; }
  }
  .poster-card:focus-visible { outline: none; }
  .poster-card:focus-visible .cover { outline: 3px solid var(--c-accent2); outline-offset: 2px; }
  @media (prefers-reduced-motion: reduce) {
    .cover, .cover img { transition: none; }
    .poster-card:hover .cover, .poster-card:active .cover { transform: none; }
  }
</style>
