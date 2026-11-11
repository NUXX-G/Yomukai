<script>
  import { t, i18n } from '../lib/i18n.svelte.js';
  // Inicio: a hero carousel and rows of works (never of sources): continue, new in the library, airing, trending,
  // for you, recently updated everywhere (one card per work) and genres. Rows can be hidden and reordered.
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { settings, saveSettings, library, updates, loadUpdates, home, features, session } from '../lib/store.svelte.js';
  import { media, reader, navigate } from '../lib/router.svelte.js';
  import { sourceById } from '../lib/store.svelte.js';
  import { openRelated } from '../lib/open.svelte.js';
  import { openDiscover, score10, genreName, seasonName, formatName } from '../lib/ui.js';
  import { groupBadges, audioBadges } from '../lib/format.js';
  import Hero from '../components/Hero.svelte';
  import Welcome from '../components/Welcome.svelte';
  import Shelf from '../components/Shelf.svelte';
  import MediaCard from '../components/MediaCard.svelte';
  import WideCard from '../components/WideCard.svelte';
  import Sheet from '../components/Sheet.svelte';
  import Icon from '../components/Icon.svelte';

  const ROWS = [
    ['rooms', 'Salas activas'], ['recommended', 'Te recomiendan'], ['continueWatching', 'Continuar viendo'], ['continueReading', 'Continuar leyendo'], ['newInLibrary', t('Nuevo en tu biblioteca')],
    ['season', t('En emisión esta temporada')], ['trendingAnime', t('Tendencias en anime')], ['trendingManga', t('Tendencias en manga y manhwa')],
    ['friends', t('Actividad de tus amigos')], ['forYou', t('Para ti')], ['unified', t('Recién actualizado')], ['action', t('Acción')], ['romance', 'Romance'], ['fantasy', t('Fantasía')], ['comedy', 'Comedia']
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

  // LaLiga's blocks in Spain (F2.3): said plainly, so a slow source is not taken for a broken one.
  let blocked = $state(false);
  onMount(() => { api.blocks().then((b) => (blocked = !!b?.active), () => {}); });

  onMount(() => {
    load('continue', () => api.continueList(), 30_000);
    load('anime', () => api.discover('anime').then((r) => r.rows), 30 * 60_000);
    load('manga', () => api.discover('manga').then((r) => r.rows), 30 * 60_000);
    // From the works index in the interface's language (one card per work, ready at once); the old live merge only
    // while the index is empty or off.
    load('unified', async () => {
      const r = await api.obras({ lang: i18n.lang, sort: 'updated' }).catch(() => null);
      return r?.works?.length ? r.works.map((work) => ({ work })) : api.unified();
    });
    load('forYou', () => api.forYou(), 15 * 60_000);
    loadUpdates();
    // Who is watching together right now, refreshed while the page is open.
    const rooms = () => { if (features.flags.rooms !== false) load('rooms', () => api.rooms(), 0); };
    rooms();
    if (!session.user?.guest) {
      load('feed', () => api.feed(), 60_000);
      load('recommended', () => api.recommendations().then((l) => {
        // Seen once shown here; the «Nuevo» badge stays for this visit.
        if (l.some((r) => !r.seen)) setTimeout(() => api.recommendationsSeen().catch(() => {}), 4_000);
        return l.slice(0, 20);
      }), 60_000);
    }
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

  function feedText(e) {
    if (e.kind === 'achievement') return t('ha conseguido un logro');
    if (e.kind === 'rated') return t('ha valorado con un ') + e.text;
    const what = e.kind === 'episode' ? (e.count === 1 ? 'episodio' : 'episodios') : (e.count === 1 ? t('capítulo') : t('capítulos'));
    const verb = e.kind === 'episode' ? t('ha visto') : t('ha leído');
    if (e.count === 1) return verb + (e.last ? ' ' + e.last : t(' un ') + what);
    return (e.marathon ? t('maratón: ') : '') + verb + ' ' + e.count + ' ' + what + (e.first && e.last ? ' (' + e.first + ' → ' + e.last + ')' : '');
  }
  function ago(at) {
    const m = Math.round((Date.now() - at) / 60_000);
    if (m < 60) return m <= 1 ? 'ahora' : 'hace ' + m + ' min';
    const h = Math.round(m / 60);
    return h < 24 ? 'hace ' + h + ' h' : 'hace ' + Math.round(h / 24) + ' d';
  }

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
      kicker: last.type === 'ANIME' ? t('Continuar viendo') : t('Continuar leyendo'), title: last.title, banner: last.banner, cover: last.cover,
      synopsis: last.chapterTitle, primaryLabel: last.type === 'ANIME' ? t('Continuar') : t('Seguir leyendo'), onPrimary: () => navigate(continueHref(last)),
      onSecondary: () => navigate(media(last.sourceId, last.mediaId)), secondaryLabel: 'Ficha'
    });
    const pool = [...row('anime', 'trending'), ...row('manga', 'trending')].filter((d) => d.banner);
    pool.sort((a, b) => (b.available === true) - (a.available === true));
    for (const d of pool.slice(0, 6 - out.length)) {
      out.push({
        kicker: d.available ? t('Disponible en tus fuentes') : t('Tendencia'), title: d.title, banner: d.banner, cover: d.cover, genres: d.genres,
        score: d.score, synopsis: d.synopsis, format: d.format, primaryLabel: d.type === 'ANIME' ? t('Ver') : t('Leer'),
        onPrimary: () => openDiscover(d)
      });
    }
    return out;
  });

  const discoverLine = (d) => [d.type === 'ANIME' ? formatName(d.format) : null, seasonName(d.season, d.year) || (d.genres?.[0] && genreName(d.genres[0]))].filter(Boolean).join(' · ');
  const discoverBadges = (d) => [
    ...(d.nextEpisode ? [{ text: t('Ep. {0}', [d.nextEpisode - 1]), tone: 'dark' }] : []),
    ...(!d.available ? [] : [{ text: t('En tus fuentes'), tone: 'accent' }])
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
  {#if blocked}
    <p class="block-note" role="status">{t('Hay bloqueos de LaLiga ahora mismo: si una fuente va lenta o no carga, se pasa por el servidor. No es un fallo de la fuente.')}</p>
  {/if}
  <Hero {slides} />
  <Welcome />

  {#each layout.filter((r) => !r.hidden) as r (r.id)}
    {#if r.id === 'rooms' && items('rooms').length}
      <Shelf title={t('Salas activas')} subtitle={t('Tus amigos viendo algo juntos ahora mismo')} wide href={features.flags.open_rooms !== false ? '/salas' : undefined}>
        {#each items('rooms') as room (room.code)}
          <a class="room-card" href="/sala/{room.code}">
            <span class="live"><span class="pulse"></span> {room.playing ? t('En directo') : t('En pausa')}</span>
            <span class="room-title">{room.title || room.name}</span>
            {#if room.episode}<span class="room-line">{room.episode}</span>{/if}
            <span class="room-who">{room.members.join(', ')}</span>
            <span class="btn primary join">{t('Unirse')}</span>
          </a>
        {/each}
      </Shelf>
    {:else if r.id === 'recommended' && items('recommended').length}
      <Shelf title={t('Te recomiendan')} subtitle={t('Lo que tus amigos creen que te va a gustar')}>
        {#each items('recommended') as rec (rec.id)}
          <MediaCard href={media(rec.sourceId, rec.mediaId)} title={rec.title} cover={rec.cover} subtitle={t('De ') + rec.from}
            badges={rec.seen ? [] : [{ text: 'Nuevo', tone: 'accent' }]} detail={rec.message ? '«' + rec.message + '»' : ''} />
        {/each}
      </Shelf>
    {:else if r.id === 'friends' && items('feed').length}
      <Shelf title={t('Actividad de tus amigos')} href="/amigos">
        {#each items('feed').slice(0, 24) as e, i (i)}
          <a class="feed-card" href={e.sourceId ? media(e.sourceId, e.mediaId) : '/u/' + encodeURIComponent(e.user)}>
            <span class="feed-who"><span class="feed-avatar">{#if e.avatar}<img src={e.avatar} alt="" />{:else}{e.user.slice(0, 1).toUpperCase()}{/if}</span>{e.user}</span>
            <span class="feed-what">{feedText(e)}</span>
            <span class="feed-title">{e.title}</span>
            <span class="feed-when">{ago(e.at)}</span>
          </a>
        {/each}
      </Shelf>
    {:else if r.id === 'continueWatching' && continueWatching.length}
      <Shelf title={t('Continuar viendo')} wide>
        {#each continueWatching as c (c.sourceId + c.chapterId)}
          <WideCard href={continueHref(c)} image={c.banner} fallback={c.cover} title={c.title} line={c.chapterTitle}
            aside={c.remainingMin ? `quedan ${c.remainingMin} min` : ''} progress={c.fraction || null} />
        {/each}
      </Shelf>
    {:else if r.id === 'continueReading' && continueReading.length}
      <Shelf title={t('Continuar leyendo')}>
        {#each continueReading as c (c.sourceId + c.chapterId)}
          <MediaCard href={continueHref(c)} title={c.title} cover={c.cover} subtitle={c.chapterTitle} progress={c.fraction || null} />
        {/each}
      </Shelf>
    {:else if r.id === 'newInLibrary' && newInLibrary.length}
      <Shelf title={t('Nuevo en tu biblioteca')} href="/biblioteca">
        {#each newInLibrary as l (l.sourceId + l.mediaId)}
          <MediaCard href={media(l.sourceId, l.mediaId)} title={l.title} cover={l.cover}
            badges={[{ text: t('{0} nuevos', [updates.byKey[l.sourceId + '|' + l.mediaId].unread]), tone: 'accent' }]} />
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
      <Shelf title={t('Para ti')} subtitle={t('Según lo que lees y ves')}>
        {#each items('forYou') as rel, i (rel.title + i)}
          <MediaCard title={rel.title} cover={rel.cover} showType type={rel.type} onclick={() => openRelated(rel)} />
        {/each}
      </Shelf>
    {:else if r.id === 'unified'}
      <Shelf title={t('Recién actualizado')} subtitle={t('De todas tus fuentes, una tarjeta por obra')} loading={loading('unified')}>
        {#each items('unified') as u (u.work ? u.work.key : u.primary.sourceId + u.primary.id)}
          {#if u.work}
            <MediaCard href={'/obras/' + encodeURIComponent(u.work.key) + '?lang=' + i18n.lang} title={u.work.title} cover={u.work.cover} showType type={u.work.type}
              badges={[...(u.work.sources > 1 ? [{ text: t('En {0} fuentes', [u.work.sources]), tone: 'dark' }] : []), ...audioBadges(u.work.audios)]} />
          {:else}
            <MediaCard href={media(u.primary.sourceId, u.primary.id)} title={u.primary.title} cover={u.primary.cover} showType type={u.primary.type}
              badges={groupBadges(u)} />
          {/if}
        {/each}
      </Shelf>
    {/if}
  {/each}

  <div class="edit-row">
    <button class="btn text" onclick={() => (editing = true)}><Icon name="tune" size={18} /> {t('Personalizar el inicio')}</button>
  </div>
</div>

<Sheet bind:open={editing} title={t('Filas del inicio')}>
  <p class="small muted" style="margin-top:0">{t('Oculta las que no quieras y cambia el orden.')}</p>
  {#each layout as r, i (r.id)}
    <div class="list-item edit">
      <label class="grow"><input type="checkbox" checked={!r.hidden} onchange={() => toggle(i)} /> {TITLES[r.id]}</label>
      <button class="icon-btn" onclick={() => move(i, -1)} disabled={i === 0} aria-label={t('Subir')}><Icon name="chevronLeft" size={20} /></button>
      <button class="icon-btn" onclick={() => move(i, 1)} disabled={i === layout.length - 1} aria-label={t('Bajar')}><Icon name="chevronRight" size={20} /></button>
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
  .feed-card { display: flex; flex-direction: column; gap: 4px; width: 240px !important; min-height: 128px; padding: 14px; border-radius: var(--r-lg); background: var(--c-surface); border: 1px solid var(--c-border); }
  .feed-card:hover { border-color: var(--c-border-strong); }
  .feed-who { display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: var(--fs-small); }
  .feed-avatar { width: 26px; height: 26px; border-radius: 50%; overflow: hidden; display: grid; place-items: center; background: var(--c-accent-soft); color: var(--c-on-accent-soft); font-size: 0.75rem; }
  .feed-avatar img { width: 100%; height: 100%; object-fit: cover; }
  .feed-what { color: var(--c-text-dim); font-size: var(--fs-small); }
  .feed-title { font-weight: 650; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  .feed-when { margin-top: auto; color: var(--c-text-faint); font-size: var(--fs-tiny); }
  .block-note { margin: 8px 16px; padding: 10px 14px; border-radius: var(--r-md, 10px); background: color-mix(in srgb, var(--c-accent, #ff8a5b) 16%, transparent); border: 1px solid var(--c-border); font-size: 0.9rem; }
</style>
