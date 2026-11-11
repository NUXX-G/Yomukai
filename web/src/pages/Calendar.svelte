<script>
  import { t, tn, locale } from '../lib/i18n.svelte.js';
  // Calendario: this week, day by day, each entry with its cover. Your library first (anime from AniList's schedule,
  // manga and novels from the rhythm their sources actually keep), then every other anime that airs this week.
  // Hover (computer) or long press / right click (phone, keyboard) shows the synopsis, genres, score and what the
  // group says; below, the group's recommendations of the season and what AniList's community rates (F5.1-F5.3).
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { navigate, media } from '../lib/router.svelte.js';
  import { openDiscover, sized, score10, seasonName } from '../lib/ui.js';
  import { featureOn } from '../lib/store.svelte.js';
  import Sheet from '../components/Sheet.svelte';
  import Row from '../components/Row.svelte';
  import MediaCard from '../components/MediaCard.svelte';
  import CalendarPeek from '../components/CalendarPeek.svelte';

  const VIEW_KEY = 'yomukai.calendar.view';
  let view = $state(readView());
  let sel = $state(0);
  let mine = $state(null);
  let airing = $state(null);
  let picks = $state(null);
  let community = $state(null);

  const days = Array.from({ length: 7 }, (_, i) => { const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + i); return d; });
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const dayName = (d, i) => (i === 0 ? t('Hoy') : i === 1 ? t('Mañana') : d.toLocaleDateString(locale(), { weekday: 'long' }));
  const time = (s) => new Date(s * 1000).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' });

  function readView() { try { return localStorage.getItem(VIEW_KEY) === 'mine' ? 'mine' : 'all'; } catch { return 'all'; } }
  function setView(v) { view = v; try { localStorage.setItem(VIEW_KEY, v); } catch { /* private mode */ } }

  onMount(() => {
    api.calendar(7).then((l) => (mine = l || []), () => (mine = []));
    api.calendarAiring().then((r) => (airing = r?.items || []), () => (airing = []));
    if (featureOn('season_social')) {
      api.seasonPicks().then((p) => (picks = p), () => (picks = null));
      api.seasonCommunity().then((c) => (community = c), () => (community = null));
    }
    return () => clearTimeout(hoverTimer);
  });

  // One shape for both lists: what the entry, the peek and "open" need.
  const fromMine = (e) => ({
    key: 'm:' + e.workKey + e.label, mine: true, title: e.title, cover: e.cover, date: e.date, anilistId: e.anilistId,
    label: e.airingAt ? t('Ep. {0}', [e.episode ?? '?']) : e.label + (e.estimated ? ' ' + t('· estimado') : ''), at: e.airingAt ? time(e.airingAt) : '',
    synopsis: e.synopsis, genres: e.genres, score: e.score, sourceId: e.sourceId, mediaId: e.mediaId, type: e.type, sort: e.airingAt || 0
  });
  const fromAiring = (e) => ({
    ...e, key: 'a:' + e.anilistId + ':' + e.nextEpisode, mine: false, date: iso(new Date(e.nextAiringAt * 1000)),
    label: t('Ep. {0}', [e.nextEpisode ?? '?']), at: time(e.nextAiringAt), sort: e.nextAiringAt
  });

  const mineEntries = $derived((mine || []).map(fromMine));
  const mineIds = $derived(new Set(mineEntries.map((e) => e.anilistId).filter(Boolean)));
  const mineTitles = $derived(new Set(mineEntries.map((e) => e.title)));
  const airingEntries = $derived((airing || []).map(fromAiring).filter((e) => !mineIds.has(e.anilistId) && !mineTitles.has(e.title)));
  const pickById = $derived(new Map((picks?.items || []).map((p) => [p.anilistId, p])));

  function entriesOn(d) {
    const day = iso(d);
    const own = mineEntries.filter((e) => e.date === day).sort((a, b) => a.sort - b.sort);
    if (view === 'mine') return own;
    return own.concat(airingEntries.filter((e) => e.date === day).sort((a, b) => a.sort - b.sort));
  }
  const loading = $derived(mine === null || (view === 'all' && airing === null));

  function open(item) {
    peek = null; sheet = null;
    if (item.sourceId && item.mediaId) return navigate(media(item.sourceId, item.mediaId));
    openDiscover(item);
  }

  // ----- Peek: hover on a computer, long press or right click elsewhere -----
  let peek = $state(null); // { item, x, y, side }
  let sheet = $state(null);
  let sheetOpen = $state(false);
  let hoverTimer;
  let pressTimer;
  let pressed = false;
  const canHover = typeof window !== 'undefined' && window.matchMedia?.('(hover: hover) and (pointer: fine)').matches;

  function hoverIn(item, ev) {
    if (!canHover) return;
    clearTimeout(hoverTimer);
    const r = ev.currentTarget.getBoundingClientRect();
    hoverTimer = setTimeout(() => {
      const width = 340;
      const right = r.right + 12 + width < window.innerWidth;
      peek = { item, x: right ? r.right + 10 : Math.max(8, r.left - width - 10), y: Math.max(8, Math.min(r.top, window.innerHeight - 420)) };
    }, 280);
  }
  function hoverOut() {
    clearTimeout(hoverTimer);
    hoverTimer = setTimeout(() => (peek = null), 220);
  }
  function keepPeek() { clearTimeout(hoverTimer); }

  function showSheet(item) { peek = null; sheet = item; sheetOpen = true; }
  function pressStart(item) {
    pressed = false;
    clearTimeout(pressTimer);
    pressTimer = setTimeout(() => { pressed = true; navigator.vibrate?.(10); showSheet(item); }, 450);
  }
  function pressEnd() { clearTimeout(pressTimer); }
  function click(item, ev) {
    // The tap that ended a long press must not also open the page.
    if (pressed) { ev.preventDefault(); pressed = false; return; }
    open(item);
  }
  function context(item, ev) { ev.preventDefault(); clearTimeout(pressTimer); showSheet(item); }

  function picksChanged(p) { picks = p; }

  // Group picks shown as items the peek understands.
  const pickItem = (p) => {
    const known = (airing || []).find((a) => a.anilistId === p.anilistId) || (community?.top || []).concat(community?.trending || []).find((a) => a.anilistId === p.anilistId);
    return known ? { ...known, label: '' } : { anilistId: p.anilistId, title: p.title, cover: p.cover, type: 'ANIME', names: [], genres: [] };
  };
</script>

<div class="page">
  <header class="head">
    <h1>{t('Calendario')}</h1>
    <p class="muted">{t('Lo que sale esta semana. Tus obras primero: el anime según AniList y el manga y las novelas según cada cuánto publica de verdad su fuente.')}</p>
    <div class="views" role="tablist" aria-label={t('Qué mostrar')}>
      <button role="tab" class="chip-btn" class:on={view === 'all'} aria-selected={view === 'all'} onclick={() => setView('all')}>{t('Todo lo que se emite')}</button>
      <button role="tab" class="chip-btn" class:on={view === 'mine'} aria-selected={view === 'mine'} onclick={() => setView('mine')}>{t('Mi biblioteca')}</button>
    </div>
    <p class="hint">{canHover ? t('Pasa el ratón por un título para ver de qué va; clic derecho para recomendarlo.') : t('Mantén pulsado un título para ver de qué va y recomendarlo.')}</p>
  </header>

  <div class="days" role="tablist" aria-label={t('Día')}>
    {#each days as d, i (iso(d))}
      {@const n = loading ? null : entriesOn(d).length}
      <button role="tab" class="daytab" class:on={sel === i} class:today={i === 0} aria-selected={sel === i} onclick={() => (sel = i)}>
        <span class="name">{dayName(d, i)}</span>
        <span class="num">{d.getDate()}</span>
        {#if n != null}<span class="count">{n}</span>{/if}
      </button>
    {/each}
  </div>

  {#key sel}
    {@const list = entriesOn(days[sel])}
    <div class="grid">
      {#if loading}
        {#each [0, 1, 2, 3, 4, 5] as k (k)}<div class="entry skeleton" aria-hidden="true"><span class="poster"></span></div>{/each}
      {:else}
        {#each list as e (e.key)}
          {@const p = e.anilistId ? pickById.get(e.anilistId) : null}
          <button
            class="entry" class:mine={e.mine}
            onclick={(ev) => click(e, ev)} oncontextmenu={(ev) => context(e, ev)}
            onmouseenter={(ev) => hoverIn(e, ev)} onmouseleave={hoverOut} onfocus={(ev) => hoverIn(e, ev)} onblur={hoverOut}
            ontouchstart={() => pressStart(e)} ontouchend={pressEnd} ontouchmove={pressEnd} ontouchcancel={pressEnd}
          >
            <span class="poster">
              {#if e.cover}<img src={sized(e.cover, 160)} alt="" loading="lazy" decoding="async" />{:else}<span class="ph">{e.title.slice(0, 1)}</span>{/if}
              {#if e.at}<span class="badge time">{e.at}</span>{/if}
              {#if p?.recommends}<span class="badge rec">♥ {p.recommends}</span>{/if}
              {#if e.mine}<span class="badge own">{t('Tuyo')}</span>{/if}
            </span>
            <span class="t">{e.title}</span>
            <span class="l">{e.label}</span>
          </button>
        {/each}
      {/if}
    </div>
    {#if !loading && !list.length}
      <p class="empty">{view === 'mine' ? t('Nada de tu biblioteca este día.') : t('Nada este día.')}</p>
    {/if}
  {/key}
  {#if mine && !mine.length && view === 'mine'}
    <p class="muted note">{t('Cuando tengas obras en emisión en la biblioteca aparecerán aquí con su próximo episodio o capítulo.')}</p>
  {/if}

  {#if featureOn('season_social')}
    <section class="block">
      <h2 class="block-title">{t('Recomendado por el grupo')}{#if picks} · {seasonName(picks.season, picks.year)}{/if}</h2>
      {#if picks?.items?.length}
        <div class="picks">
          {#each picks.items as p (p.anilistId)}
            <button class="pick" onclick={() => showSheet(pickItem(p))}>
              <span class="thumb">{#if p.cover}<img src={sized(p.cover, 80)} alt="" loading="lazy" decoding="async" />{/if}</span>
              <span class="text">
                <span class="t">{p.title}</span>
                {#if p.recommends}<span class="l rec">{tn(p.recommends, 'Lo recomienda {n} del grupo', 'Lo recomiendan {n} del grupo')}</span>{/if}
                {#if p.comments?.[0]}<span class="quote"><b>{p.comments[0].name}:</b> {p.comments[0].comment}</span>{/if}
                {#if p.comments?.length > 1}<span class="l">{tn(p.comments.length, '{n} comentario', '{n} comentarios')}</span>{/if}
              </span>
            </button>
          {/each}
        </div>
      {:else if picks}
        <p class="muted">{t('Nadie ha recomendado nada todavía esta temporada. Abre un anime del calendario y pulsa «Recomendar o comentar».')}</p>
      {/if}
    </section>

    {#if community && (community.top?.length || community.trending?.length)}
      <section class="block">
        <h2 class="block-title">{t('Lo que más gusta esta temporada')}</h2>
        <p class="muted small">{t('Según la comunidad de AniList: nota media de quienes lo ven (solo con bastantes votos) y lo que más se comenta ahora.')}</p>
        {#each [['top', t('Mejor valorados')], ['trending', t('Tendencia ahora')]] as [k, label] (k)}
          {#if community[k]?.length}
            <h3 class="row-title">{label}</h3>
            <Row>
              {#each community[k] as e (e.anilistId)}
                <div class="card-slot" role="presentation" oncontextmenu={(ev) => context({ ...e, label: '' }, ev)}
                  ontouchstart={() => pressStart({ ...e, label: '' })} ontouchend={pressEnd} ontouchmove={pressEnd}>
                  <MediaCard title={e.title} cover={e.cover} color={e.color} score={score10(e.score)}
                    subtitle={pickById.get(e.anilistId)?.recommends ? '♥ ' + tn(pickById.get(e.anilistId).recommends, '{n} del grupo', '{n} del grupo') : ''}
                    onclick={(ev) => click({ ...e, label: '' }, ev)} />
                </div>
              {/each}
            </Row>
          {/if}
        {/each}
        <p class="credit">{t('Datos de')} <a href={community.sourceUrl} target="_blank" rel="noopener noreferrer">AniList</a></p>
      </section>
    {/if}
  {/if}
</div>

{#if peek}
  <div class="popover" style:left="{peek.x}px" style:top="{peek.y}px" onmouseenter={keepPeek} onmouseleave={hoverOut} role="dialog" aria-label={peek.item.title} tabindex="-1">
    <CalendarPeek item={peek.item} pick={peek.item.anilistId ? pickById.get(peek.item.anilistId) : null} onopen={() => open(peek.item)} onpicks={picksChanged} compact />
  </div>
{/if}

<Sheet bind:open={sheetOpen}>
  {#if sheet}
    <CalendarPeek item={sheet} pick={sheet.anilistId ? pickById.get(sheet.anilistId) : null} onopen={() => open(sheet)} onpicks={picksChanged} />
  {/if}
</Sheet>

<style>
  .head h1 { margin: 12px 0 4px; font-size: var(--fs-title); font-weight: 800; letter-spacing: -0.02em; }
  .head p { margin: 0 0 12px; max-width: 720px; }
  .hint { color: var(--c-text-faint); font-size: var(--fs-small); }
  .views { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
  .chip-btn { padding: 6px 14px; border-radius: 999px; border: 1px solid var(--c-border); font-size: var(--fs-small); font-weight: 600; color: var(--c-text-dim); }
  .chip-btn.on { background: var(--c-accent); border-color: var(--c-accent); color: var(--on-primary, #fff); }
  .days { display: flex; gap: 6px; overflow-x: auto; scrollbar-width: none; margin: 4px 0 14px; padding-bottom: 2px; }
  .days::-webkit-scrollbar { display: none; }
  .daytab {
    flex: 1 0 auto; min-width: 64px; display: grid; grid-template-columns: 1fr auto; align-items: baseline; gap: 0 6px; padding: 8px 12px;
    border-radius: var(--r-md); border: 1px solid var(--c-border); background: var(--c-surface); text-align: left;
  }
  .daytab .name { text-transform: capitalize; font-weight: 700; font-size: var(--fs-small); }
  .daytab .num { color: var(--c-text-faint); font-size: var(--fs-small); }
  .daytab .count { grid-column: 1 / -1; color: var(--c-text-dim); font-size: var(--fs-tiny); }
  .daytab.today .name { color: var(--c-accent); }
  .daytab.on { border-color: var(--c-accent); background: color-mix(in srgb, var(--c-accent) 14%, var(--c-surface)); }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(104px, 1fr)); gap: 14px 10px; }
  @media (min-width: 700px) { .grid { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 18px 14px; } }
  .entry { display: flex; flex-direction: column; gap: 4px; text-align: left; min-width: 0; -webkit-touch-callout: none; user-select: none; }
  .poster {
    position: relative; display: grid; place-items: center; aspect-ratio: 2 / 3; border-radius: var(--r-md); overflow: hidden;
    background: var(--c-surface-high, var(--c-surface)); border: 2px solid transparent; transition: border-color var(--motion-fast, 0.15s), transform var(--motion-fast, 0.15s);
  }
  .poster img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
  .entry:hover .poster, .entry:focus-visible .poster { border-color: var(--c-accent); transform: translateY(-2px); }
  .entry.mine .poster { border-color: color-mix(in srgb, var(--c-accent) 70%, transparent); }
  @media (prefers-reduced-motion: reduce) { .entry:hover .poster { transform: none; } }
  .ph { font-weight: 800; font-size: 1.6rem; color: var(--c-text-faint); }
  .badge { position: absolute; font-size: 0.7rem; font-weight: 700; padding: 2px 7px; border-radius: 999px; background: #000c; color: #fff; }
  .badge.time { top: 6px; left: 6px; }
  .badge.rec { top: 6px; right: 6px; color: #ff8fa3; }
  .badge.own { bottom: 6px; left: 6px; background: var(--c-accent); color: var(--on-primary, #fff); }
  .t { font-weight: 700; font-size: 0.85rem; line-height: 1.25; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
  .l { color: var(--c-text-dim); font-size: var(--fs-tiny); }
  .l.rec { color: var(--c-accent); font-weight: 600; }
  .skeleton .poster { animation: pulse 1.2s ease-in-out infinite; }
  @keyframes pulse { 50% { opacity: 0.5; } }
  @media (prefers-reduced-motion: reduce) { .skeleton .poster { animation: none; } }
  .pick { display: flex; gap: 10px; align-items: flex-start; text-align: left; padding: 8px; border-radius: var(--r-md); border: 1px solid var(--c-border); background: var(--c-surface); min-width: 0; }
  .pick:hover { border-color: var(--c-accent); }
  .thumb { flex: none; width: 58px; aspect-ratio: 2 / 3; border-radius: 6px; overflow: hidden; background: var(--c-surface-high, var(--c-surface)); }
  .thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
  .text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
  .empty { color: var(--c-text-faint); font-size: var(--fs-small); margin: 0; }
  .note { margin-top: 20px; }
  .block { margin-top: 32px; }
  .block-title { font-size: 1.2rem; font-weight: 800; margin: 0 0 10px; }
  .row-title { font-size: 1rem; margin: 16px 0 8px; }
  .small { font-size: var(--fs-small); margin: 0; }
  .picks { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 10px; }
  .quote { font-size: var(--fs-small); color: var(--c-text-dim); display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
  .credit { color: var(--c-text-faint); font-size: var(--fs-tiny); margin-top: 8px; }
  .credit a { color: inherit; text-decoration: underline; }
  .popover {
    position: fixed; z-index: 50; width: 340px; max-height: 420px; overflow-y: auto; padding: 14px; border-radius: var(--r-lg, 16px);
    background: var(--c-surface); border: 1px solid var(--c-border); box-shadow: 0 12px 40px #0008; animation: fade 0.15s;
  }
  @keyframes fade { from { opacity: 0; transform: translateY(4px); } }
  @media (prefers-reduced-motion: reduce) { .popover { animation: none; } }
</style>
