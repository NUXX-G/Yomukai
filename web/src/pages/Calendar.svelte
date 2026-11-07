<script>
  import { t, locale } from '../lib/i18n.svelte.js';
  // Calendario: this week, day by day. Your library first (anime from AniList's schedule, manga and novels from the
  // rhythm their sources actually keep), then what else airs this week.
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { openDiscover, sized } from '../lib/ui.js';

  let mine = $state(null);
  let airing = $state([]);

  const days = Array.from({ length: 7 }, (_, i) => { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + i); return d; });
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const dayName = (d, i) => (i === 0 ? t('Hoy') : i === 1 ? t('Mañana') : d.toLocaleDateString(locale(), { weekday: 'long' }));

  onMount(async () => {
    api.calendar(7).then((l) => (mine = l), () => (mine = []));
    try {
      const rows = (await api.discover('anime')).rows;
      const seen = new Set();
      airing = rows.flatMap((r) => r.items).filter((d) => d.nextAiringAt && !seen.has(d.anilistId) && seen.add(d.anilistId))
        .map((d) => ({ ...d, date: iso(new Date(d.nextAiringAt * 1000)) }));
    } catch { airing = []; }
  });

  const mineOn = (d) => (mine || []).filter((e) => e.date === iso(d));
  const airingOn = (d) => airing.filter((e) => e.date === iso(d) && !(mine || []).some((m) => m.title === e.title));
  const time = (s) => new Date(s * 1000).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' });
</script>

<div class="page">
  <header class="head">
    <h1>{t('Calendario')}</h1>
    <p class="muted">{t('Lo que sale esta semana. Tus obras primero: el anime según AniList y el manga y las novelas según cada cuánto publica de verdad su fuente.')}</p>
  </header>

  <div class="week">
    {#each days as d, i (iso(d))}
      <section class="day" class:today={i === 0}>
        <h2><span class="name">{dayName(d, i)}</span> <span class="date">{d.getDate()}</span></h2>
        {#each mineOn(d) as e (e.workKey + e.label)}
          <div class="entry mine" style:--bg="url('{sized(e.cover, 160)}')">
            <span class="t">{e.title}</span>
            <span class="l">{e.label}{#if e.estimated} {t('· estimado')}{/if}</span>
          </div>
        {/each}
        {#each airingOn(d) as e (e.anilistId)}
          <button class="entry" style:--bg="url('{sized(e.cover, 160)}')" onclick={() => openDiscover(e)}>
            <span class="t">{e.title}</span>
            <span class="l">{t('Ep. {0} · {1}', [e.nextEpisode, time(e.nextAiringAt)])}</span>
          </button>
        {/each}
        {#if mine && !mineOn(d).length && !airingOn(d).length}<p class="empty">{t('Nada')}</p>{/if}
      </section>
    {/each}
  </div>
  {#if mine && !mine.length}
    <p class="muted note">{t('Cuando tengas obras en emisión en la biblioteca aparecerán aquí con su próximo episodio o capítulo.')}</p>
  {/if}
</div>

<style>
  .head h1 { margin: 12px 0 4px; font-size: var(--fs-title); font-weight: 800; letter-spacing: -0.02em; }
  .head p { margin: 0 0 24px; max-width: 720px; }
  .week { display: grid; grid-template-columns: 1fr; gap: 14px; }
  @media (min-width: 900px) { .week { grid-template-columns: repeat(7, minmax(0, 1fr)); border: 1px solid var(--c-border); border-radius: var(--r-xl); padding: 14px; gap: 10px; background: var(--c-surface); } }
  .day { display: flex; flex-direction: column; gap: 8px; min-height: 120px; }
  h2 { margin: 0 0 4px; font-size: 0.95rem; font-weight: 700; display: flex; justify-content: space-between; text-transform: capitalize; }
  .date { color: var(--c-text-faint); }
  .today h2 { color: var(--c-accent); }
  .entry {
    position: relative; display: flex; flex-direction: column; justify-content: flex-end; min-height: 92px; padding: 10px; border-radius: var(--r-md);
    overflow: hidden; text-align: left; isolation: isolate; border: 1px solid var(--c-border);
  }
  .entry::before { content: ''; position: absolute; inset: -12px; z-index: -2; background: var(--bg) center / cover; filter: blur(6px) brightness(0.45); }
  .entry::after { content: ''; position: absolute; inset: 0; z-index: -1; background: linear-gradient(to top, rgba(0, 0, 0, 0.85), transparent); }
  .entry.mine { border-color: color-mix(in srgb, var(--c-accent) 55%, transparent); }
  .t { font-weight: 700; font-size: 0.85rem; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .l { color: var(--c-text-dim); font-size: var(--fs-tiny); margin-top: 2px; }
  .empty { color: var(--c-text-faint); font-size: var(--fs-small); margin: 0; }
  .note { margin-top: 20px; }
</style>
