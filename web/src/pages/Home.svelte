<script>
  // Inicio: a hero carousel and rows of works (never of sources): continue, new in the library, airing, trending,
  // for you, recently updated everywhere (one card per work) and genres. Rows can be hidden and reordered.
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { settings, saveSettings, library, updates, loadUpdates, home, features } from '../lib/store.svelte.js';
  import { media, reader, navigate } from '../lib/router.svelte.js';
  import { sourceById } from '../lib/store.svelte.js';
  import { openRelated } from '../lib/open.svelte.js';
  import { openDiscover, score10, genreName, seasonName, formatName } from '../lib/ui.js';
  import Hero from '../components/Hero.svelte';
  import Shelf from '../components/Shelf.svelte';
  import MediaCard from '../components/MediaCard.svelte';
  import WideCard from '../components/WideCard.svelte';
  import Sheet from '../components/Sheet.svelte';
  import Icon from '../components/Icon.svelte';

  const ROWS = [
    ['rooms', 'Salas activas'], ['continueWatching', 'Continuar viendo'], ['continueReading', 'Continuar leyendo'], ['newInLibrary', 'Nuevo en tu biblioteca'],
    ['season', 'En emisión esta temporada'], ['trendingAnime', 'Tendencias en anime'], ['trendingManga', 'Tendencias en manga y manhwa'],
    ['forYou', 'Para ti'], ['unified', 'Recién actualizado'], ['action', 'Acción'], ['romance', 'Romance'], ['fantasy', 'Fantasía'], ['comedy', 'Comedia']
  ];
  const TITLES = Object.fromEntries(ROWS);

  // Kept in the store between visits, so coming back is instant.
  const data = home.sections;
  let editing = $state(false);

  /** The user's order and hidden rows, with rows added in later versions appended. */
  const layout = $derived.by(() => {
    const saved = (settings.homeRows || []).filter((r) => TITLES[r.id]);
    return [...saved, ...ROWS.filter(([id]) => !saved.some((r) => r.id === id)).map(([id]) => ({ id, hidden: false }))];
  });

  function load(key, fn, maxAgeMs = 5 * 60_000) {
    const known = data[key];
    if (known && Date.now() - known.at < maxAgeMs) return;
    if (!known) data[key] = { loading: true, items: [], at: 0 };
    fn().then((items) => (data[key] = { loading: false, items, at: Date.now() }), () => (data[key] = { loading: false, items: known?.items || [], at: Date.now() }));
  }

  onMount(() => {
    load('continue', () => api.continueList(), 30_000);
    load('anime', () => api.discover('anime').then((r) => r.rows), 30 * 60_000);
    load('manga', () => api.discover('manga').then((r) => r.rows), 30 * 60_000);
    load('unified', () => api.unified());
    load('forYou', () => api.forYou(), 15 * 60_000);
    loadUpdates();
    // Who is watching together right now, refreshed while the page is open.
    const rooms = () => { if (features.flags.rooms !== false) load('rooms', () => api.rooms(), 0); };
    rooms();
    const t = setInterval(rooms, 30_000);
    return () => clearInterval(t);
  });

  const items = (key) => data[key]?.items || [];
  const loading = (key) => data[key]?.loading !== false;
  const row = (key, id) => items(key).find((r) => r.id === id)?.items || [];

  const continueWatching = $derived(dedupeWork(items('continue').filter((c) => c.type === 'ANIME' && !c.finished)));
  const continueReading = $derived(dedupeWork(items('continue').filter((c) => c.type !== 'ANIME')));
  const newInLibrary = $derived(library.items.filter((r) => (updates.byKey[r.sourceId + '|' + r.mediaId]?.unread || 0) > 0)
    .sort((a, b) => (updates.byKey[b.sourceId + '|' + b.mediaId]?.lastUpdate || 0) - (updates.byKey[a.sourceId + '|' + a.mediaId]?.lastUpdate || 0)));

  function dedupeWork(list) {
    const seen = new Set();
    return list.filter((c) => { const k = c.workKey || c.sourceId + '|' + c.mediaId; if (seen.has(k)) return false; seen.add(k); return true; });
  }
  const kindOf = (sourceId) => sourceById(sourceId)?.kind;
  const continueHref = (c) => reader(kindOf(c.sourceId), c.sourceId, c.mediaId, c.chapterId);

  // Hero: what you were watching (if its work has a banner), then the most talked-about titles you can open.
  const slides = $derived.by(() => {
    const out = [];
    const last = items('continue').find((c) => c.banner && !c.finished);
    if (last) out.push({
      kicker: last.type === 'ANIME' ? 'Continuar viendo' : 'Continuar leyendo', title: last.title, banner: last.banner, cover: last.cover,
      synopsis: last.chapterTitle, primaryLabel: last.type === 'ANIME' ? 'Continuar' : 'Seguir leyendo', onPrimary: () => navigate(continueHref(last)),
      onSecondary: () => navigate(media(last.sourceId, last.mediaId)), secondaryLabel: 'Ficha'
    });
    const pool = [...row('anime', 'trending'), ...row('manga', 'trending')].filter((d) => d.banner);
    pool.sort((a, b) => (b.available === true) - (a.available === true));
    for (const d of pool.slice(0, 6 - out.length)) {
      out.push({
        kicker: d.available ? 'Disponible en tus fuentes' : 'Tendencia', title: d.title, banner: d.banner, cover: d.cover, genres: d.genres,
        score: d.score, synopsis: d.synopsis, format: d.format, primaryLabel: d.type === 'ANIME' ? 'Ver' : 'Leer',
        onPrimary: () => openDiscover(d)
      });
    }
    return out;
  });

  const discoverLine = (d) => [d.type === 'ANIME' ? formatName(d.format) : null, seasonName(d.season, d.year) || (d.genres?.[0] && genreName(d.genres[0]))].filter(Boolean).join(' · ');
  const discoverBadges = (d) => [
    ...(d.nextEpisode ? [{ text: `Ep. ${d.nextEpisode - 1}`, tone: 'dark' }] : []),
    ...(!d.available ? [] : [{ text: 'En tus fuentes', tone: 'accent' }])
  ];

  function move(i, dir) {
    const list = layout.map((r) => ({ ...r }));
    const j = i + dir;
    if (j < 0 || j >= list.length) return;
    [list[i], list[j]] = [list[j], list[i]];
    settings.homeRows = list;
    saveSettings();
  }
  function toggle(i) {
    const list = layout.map((r) => ({ ...r }));
    list[i].hidden = !list[i].hidden;
    settings.homeRows = list;
    saveSettings();
  }
</script>

<div class="page home">
  <Hero {slides} />

  {#each layout.filter((r) => !r.hidden) as r (r.id)}
    {#if r.id === 'rooms' && items('rooms').length}
      <Shelf title="Salas activas" subtitle="Tus amigos viendo algo juntos ahora mismo" wide>
        {#each items('rooms') as room (room.code)}
          <a class="room-card" href="/sala/{room.code}">
            <span class="live"><span class="pulse"></span> {room.playing ? 'En directo' : 'En pausa'}</span>
            <span class="room-title">{room.title || room.name}</span>
            {#if room.episode}<span class="room-line">{room.episode}</span>{/if}
            <span class="room-who">{room.members.join(', ')}</span>
            <span class="btn primary join">Unirse</span>
          </a>
        {/each}
      </Shelf>
    {:else if r.id === 'continueWatching' && continueWatching.length}
      <Shelf title="Continuar viendo" wide>
        {#each continueWatching as c (c.sourceId + c.chapterId)}
          <WideCard href={continueHref(c)} image={c.banner} fallback={c.cover} title={c.title} line={c.chapterTitle}
            aside={c.remainingMin ? `quedan ${c.remainingMin} min` : ''} progress={c.fraction || null} />
        {/each}
      </Shelf>
    {:else if r.id === 'continueReading' && continueReading.length}
      <Shelf title="Continuar leyendo">
        {#each continueReading as c (c.sourceId + c.chapterId)}
          <MediaCard href={continueHref(c)} title={c.title} cover={c.cover} subtitle={c.chapterTitle} progress={c.fraction || null} />
        {/each}
      </Shelf>
    {:else if r.id === 'newInLibrary' && newInLibrary.length}
      <Shelf title="Nuevo en tu biblioteca" href="/biblioteca">
        {#each newInLibrary as l (l.sourceId + l.mediaId)}
          <MediaCard href={media(l.sourceId, l.mediaId)} title={l.title} cover={l.cover}
            badges={[{ text: `${updates.byKey[l.sourceId + '|' + l.mediaId].unread} nuevos`, tone: 'accent' }]} />
        {/each}
      </Shelf>
    {:else if r.id === 'season' || r.id === 'trendingAnime' || r.id === 'trendingManga' || ['action', 'romance', 'fantasy', 'comedy'].includes(r.id)}
      {@const source = r.id === 'trendingManga' ? row('manga', 'trending') : r.id === 'season' ? row('anime', 'season') : r.id === 'trendingAnime' ? row('anime', 'trending') : row('anime', r.id)}
      {#if loading('anime') || source.length}
        <Shelf title={TITLES[r.id]} loading={loading(r.id === 'trendingManga' ? 'manga' : 'anime') && !source.length}
          href={r.id === 'trendingManga' ? '/manga' : '/anime'}>
          {#each source as d (d.anilistId)}
            <MediaCard title={d.title} cover={d.cover} color={d.color} onclick={() => openDiscover(d)} subtitle={discoverLine(d)}
              score={score10(d.score)} badges={discoverBadges(d)} detail={[d.genres?.slice(0, 3).map(genreName).join(' · '), d.synopsis].filter(Boolean).join('\n\n')} />
          {/each}
        </Shelf>
      {/if}
    {:else if r.id === 'forYou' && items('forYou').length}
      <Shelf title="Para ti" subtitle="Según lo que lees y ves">
        {#each items('forYou') as rel, i (rel.title + i)}
          <MediaCard title={rel.title} cover={rel.cover} showType type={rel.type} onclick={() => openRelated(rel)} />
        {/each}
      </Shelf>
    {:else if r.id === 'unified'}
      <Shelf title="Recién actualizado" subtitle="De todas tus fuentes, una tarjeta por obra" loading={loading('unified')}>
        {#each items('unified') as u (u.primary.sourceId + u.primary.id)}
          <MediaCard href={media(u.primary.sourceId, u.primary.id)} title={u.primary.title} cover={u.primary.cover} showType type={u.primary.type}
            badges={u.others.length ? [{ text: `${u.others.length + 1} fuentes`, tone: 'dark' }] : []} />
        {/each}
      </Shelf>
    {/if}
  {/each}

  <div class="edit-row">
    <button class="btn text" onclick={() => (editing = true)}><Icon name="tune" size={18} /> Personalizar el inicio</button>
  </div>
</div>

<Sheet bind:open={editing} title="Filas del inicio">
  <p class="small muted" style="margin-top:0">Oculta las que no quieras y cambia el orden.</p>
  {#each layout as r, i (r.id)}
    <div class="list-item edit">
      <label class="grow"><input type="checkbox" checked={!r.hidden} onchange={() => toggle(i)} /> {TITLES[r.id]}</label>
      <button class="icon-btn" onclick={() => move(i, -1)} disabled={i === 0} aria-label="Subir"><Icon name="chevronLeft" size={20} /></button>
      <button class="icon-btn" onclick={() => move(i, 1)} disabled={i === layout.length - 1} aria-label="Bajar"><Icon name="chevronRight" size={20} /></button>
    </div>
  {/each}
</Sheet>

<style>
  .home { padding-top: 0; }
  .edit-row { display: flex; justify-content: center; margin: 48px 0 16px; }
  .edit { gap: 6px; }
  .edit label { display: flex; align-items: center; gap: 10px; cursor: pointer; }
  .edit .icon-btn :global(svg) { transform: rotate(90deg); }
  .grow { flex: 1; min-width: 0; }
  .room-card { position: relative; display: flex; flex-direction: column; gap: 4px; aspect-ratio: 16 / 9; padding: 14px; border-radius: var(--r-lg);
    background: radial-gradient(120% 120% at 100% 0%, color-mix(in srgb, var(--c-accent) 35%, transparent), var(--c-surface-high)); border: 1px solid var(--c-border); }
  .room-card:hover { border-color: var(--c-accent); }
  .live { display: inline-flex; align-items: center; gap: 6px; font-size: var(--fs-tiny); font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; color: var(--c-text-dim); }
  .pulse { width: 8px; height: 8px; border-radius: 50%; background: var(--c-danger); animation: pulse 1.6s infinite; }
  @keyframes pulse { 50% { opacity: 0.3; } }
  @media (prefers-reduced-motion: reduce) { .pulse { animation: none; } }
  .room-title { font-weight: 750; font-size: 1.05rem; margin-top: auto; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .room-line, .room-who { font-size: var(--fs-small); color: var(--c-text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .join { position: absolute; top: 12px; right: 12px; height: 32px; padding: 0 14px; font-size: 0.82rem; }
</style>
