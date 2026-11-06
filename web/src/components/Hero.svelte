<script>
  // Full-width hero carousel: AniList banner faded into the page, poster, title, genres, score, short synopsis and the
  // two main actions. Changes every 8 s (never with reduced motion, never while the pointer or focus is on it).
  import Icon from './Icon.svelte';
  import { sized, score10, genreName, formatName, reducedMotion } from '../lib/ui.js';

  /** slides: { title, kicker, banner, cover, genres, score, synopsis, format, primaryLabel, onPrimary, onSecondary, secondaryLabel } */
  let { slides = [] } = $props();
  let index = $state(0);
  let paused = $state(false);
  const slide = $derived(slides[index % Math.max(slides.length, 1)]);

  $effect(() => {
    if (slides.length < 2 || paused || reducedMotion()) return;
    const t = setInterval(() => (index = (index + 1) % slides.length), 8000);
    return () => clearInterval(t);
  });
  $effect(() => { if (index >= slides.length) index = 0; });
</script>

{#if slide}
  <section class="hero" aria-roledescription="carrusel" aria-label="Destacados"
    onmouseenter={() => (paused = true)} onmouseleave={() => (paused = false)} onfocusin={() => (paused = true)} onfocusout={() => (paused = false)}>
    {#key index}
      <div class="backdrop">
        {#if slide.banner}<img src={slide.banner} alt="" decoding="async" />
        {:else if slide.cover}<img class="soft" src={sized(slide.cover, 300)} alt="" decoding="async" />{/if}
      </div>
    {/key}
    <div class="fade"></div>
    <div class="content">
      {#if slide.cover}<img class="poster" src={sized(slide.cover, 220)} alt="" />{/if}
      <div class="info">
        {#if slide.kicker}<div class="kicker">{slide.kicker}</div>{/if}
        <h1>{slide.title}</h1>
        <div class="meta">
          {#if slide.score}<span class="score">★ {score10(slide.score)}</span>{/if}
          {#if slide.format}<span>{formatName(slide.format)}</span>{/if}
          {#each (slide.genres || []).slice(0, 3) as g (g)}<span>{genreName(g)}</span>{/each}
        </div>
        {#if slide.synopsis}<p class="synopsis">{slide.synopsis}</p>{/if}
        <div class="actions">
          <button class="btn primary" onclick={slide.onPrimary}><Icon name="play" size={20} /> {slide.primaryLabel || 'Ver'}</button>
          {#if slide.onSecondary}<button class="btn glass" onclick={slide.onSecondary}><Icon name="add" size={20} /> {slide.secondaryLabel || 'Añadir a la biblioteca'}</button>{/if}
        </div>
      </div>
    </div>
    {#if slides.length > 1}
      <div class="dots" role="tablist" aria-label="Destacados">
        {#each slides as s, i (i)}
          <button class="dot" class:on={i === index} role="tab" aria-selected={i === index} aria-label={s.title} onclick={() => (index = i)}></button>
        {/each}
      </div>
    {/if}
  </section>
{/if}

<style>
  /* Under the see-through top bar, edge to edge. */
  .hero { position: relative; min-height: min(72vh, 620px); display: flex; align-items: flex-end; overflow: hidden; margin: calc(-1 * (var(--layout-top-bar) + var(--safe-top))) calc(-1 * var(--page-pad)) 0; }
  .backdrop { position: absolute; inset: 0; animation: fadein var(--motion-slow) ease-out; }
  .backdrop img { width: 100%; height: 100%; object-fit: cover; object-position: center 25%; }
  .backdrop img.soft { filter: blur(24px) brightness(0.6); transform: scale(1.2); }
  .fade { position: absolute; inset: 0; background:
    linear-gradient(to top, var(--c-canvas) 4%, color-mix(in srgb, var(--c-canvas) 55%, transparent) 38%, transparent 70%),
    linear-gradient(to right, color-mix(in srgb, var(--c-canvas) 92%, transparent) 0%, color-mix(in srgb, var(--c-canvas) 45%, transparent) 45%, transparent 75%); }
  .content { position: relative; display: flex; align-items: flex-end; gap: 28px; padding: 0 var(--page-pad) 56px; width: 100%; }
  .poster { display: none; width: 180px; aspect-ratio: 2 / 3; object-fit: cover; border-radius: var(--r-lg); box-shadow: var(--shadow-panel); }
  .info { max-width: 640px; }
  .kicker { color: var(--c-text-dim); font-weight: 650; font-size: 1rem; margin-bottom: 4px; }
  h1 { margin: 0; font-size: clamp(1.8rem, 4.2vw, var(--fs-hero)); font-weight: 800; line-height: 1.05; letter-spacing: -0.02em; text-wrap: balance; }
  .meta { display: flex; flex-wrap: wrap; gap: 6px 14px; margin-top: 12px; color: var(--c-text-dim); font-size: var(--fs-small); font-weight: 600; }
  .score { color: var(--c-accent); }
  .synopsis { margin: 12px 0 0; color: color-mix(in srgb, var(--c-text) 80%, transparent); font-size: 0.95rem; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 3; line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
  .actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
  .btn.glass { background: color-mix(in srgb, white 14%, transparent); backdrop-filter: blur(10px); color: var(--c-text); }
  .btn.glass:hover { background: color-mix(in srgb, white 22%, transparent); }
  .dots { position: absolute; right: var(--page-pad); bottom: 22px; display: flex; gap: 6px; }
  .dot { width: 8px; height: 8px; border-radius: var(--r-pill); background: color-mix(in srgb, white 30%, transparent); transition: width var(--motion-base), background var(--motion-base); }
  .dot.on { width: 22px; background: var(--c-text); }
  @keyframes fadein { from { opacity: 0; } to { opacity: 1; } }
  @media (min-width: 900px) { .poster { display: block; } .content { padding-bottom: 72px; } }
  @media (max-width: 600px) { .hero { min-height: 62vh; } .synopsis { -webkit-line-clamp: 2; line-clamp: 2; } }
  @media (prefers-reduced-motion: reduce) { .backdrop { animation: none; } .dot { transition: none; } }
</style>
