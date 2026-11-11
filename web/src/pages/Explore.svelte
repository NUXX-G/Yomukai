<script>
  import { t, tk, i18n } from '../lib/i18n.svelte.js';
  // Anime / Manga y manhwa / Novelas: a hero, rows by category and an explorer with filters. Everything comes from
  // AniList; cards that are already in your sources say so and open directly, the rest are looked for when opened.
  import { onMount } from 'svelte';
  import { route } from '../lib/router.svelte.js';
  import { api } from '../lib/api.js';
  import { audioBadges } from '../lib/format.js';
  import { openDiscover, score10, genreName, GENRES, formatName, seasonName } from '../lib/ui.js';
  import Hero from '../components/Hero.svelte';
  import Shelf from '../components/Shelf.svelte';
  import MediaCard from '../components/MediaCard.svelte';
  import SectionTabs from '../components/SectionTabs.svelte';

  // Each kind of work has its section (F3.3): manga (Japan) apart from manhwa and manhua (Korea, China), and comics
  // from the user's sources (AniList hardly knows western comics).
  const section = route.name;
  const kind = section === 'anime' ? 'anime' : section === 'novels' ? 'novelas' : section === 'comics' ? 'comics' : 'manga';
  const TITLE = { anime: t('Anime'), manga: t('Manga'), manhwa: t('Manhwa y manhua'), comics: t('Cómics'), novels: t('Novelas') }[section];
  /** Rows from AniList keep only the section's kind (its kind comes from the country of origin). */
  const inSection = (d) => section === 'manga' ? d.type === 'MANGA' : section === 'manhwa' ? d.type === 'MANHWA' || d.type === 'MANHUA' : true;
  const YEARS = Array.from({ length: 36 }, (_, i) => new Date().getFullYear() + 1 - i);
  const STATUS = [['', 'Cualquier estado'], ['RELEASING', kind === 'anime' ? t('En emisión') : t('En publicación')], ['FINISHED', 'Terminado'], ['NOT_YET_RELEASED', t('Próximamente')], ['HIATUS', t('En pausa')]];
  const FORMATS = kind === 'anime'
    ? [['', 'Cualquier formato'], ['TV', 'Serie'], ['MOVIE', t('Película')], ['OVA', 'OVA'], ['ONA', 'ONA'], ['SPECIAL', 'Especial']]
    : kind === 'manga' ? [['', 'Cualquier formato'], ['MANGA', 'Serie'], ['ONE_SHOT', 'One-shot']] : [];
  const COUNTRIES = section === 'manhwa' ? [['KR', 'Manhwa'], ['CN', 'Manhua'], ['TW', t('Manhua (Taiwán)')]] : [];
  const SORTS = [['TRENDING_DESC', 'Tendencia'], ['POPULARITY_DESC', 'Popularidad'], ['SCORE_DESC', 'Nota'], ['START_DATE_DESC', t('Más recientes')]];

  let rows = $state([]);
  let loadingRows = $state(true);
  // Dubs (F3.1): Spain's Spanish, Latin American, English, and the subtitled original, apart from each other.
  const DUB_TABS = [
    { audio: 'CASTELLANO', label: tk('Castellano') }, { audio: 'LATINO', label: tk('Latino') },
    { audio: 'INGLES', label: tk('Doblaje en inglés') }, { audio: 'ORIGINAL', label: tk('Subtitulado') }
  ];
  const dubLang = (audio) => (audio === 'INGLES' ? 'en' : audio === 'ORIGINAL' ? i18n.lang : 'es');
  let dubTab = $state('CASTELLANO');
  let dubs = $state([]);
  let dubsLoading = $state(false);
  async function loadDubs(audio) {
    dubTab = audio;
    dubsLoading = true;
    try { dubs = (await api.obras({ lang: dubLang(audio), type: 'ANIME', audio, sort: 'updated' })).works || []; } catch { dubs = []; } finally { dubsLoading = false; }
  }
  if (kind === 'anime') loadDubs('CASTELLANO');

  let filters = $state({ genre: '', year: '', status: '', format: '', country: section === 'manga' ? 'JP' : section === 'manhwa' ? 'KR' : '', sort: 'TRENDING_DESC', minScore: '' });

  // Comics: what the user's sources have, in the language chosen, newest first or by name.
  let comicsLang = $state(i18n.lang === 'en' ? 'en' : 'es');
  let comicsSort = $state('updated');
  let comics = $state([]);
  let comicsPage = $state(1);
  let comicsMore = $state(false);
  let comicsLoading = $state(false);
  async function loadComics(next = false) {
    comicsLoading = true;
    comicsPage = next ? comicsPage + 1 : 1;
    try {
      const works = (await api.obras({ lang: comicsLang, type: 'COMIC', sort: comicsSort, page: comicsPage })).works || [];
      comics = next ? [...comics, ...works] : works;
      comicsMore = works.length >= 40;
    } catch { if (!next) comics = []; comicsMore = false; } finally { comicsLoading = false; }
  }
  let onlyAvailable = $state(false);
  let results = $state([]);
  let page = $state(1);
  let searching = $state(false);
  let more = $state(true);

  onMount(async () => {
    if (kind === 'comics') { loadingRows = false; loadComics(); return; }
    try { rows = (await api.discover(kind)).rows.map((r) => ({ ...r, items: r.items.filter(inSection) })).filter((r) => r.items.length); } catch { rows = []; } finally { loadingRows = false; }
    runSearch();
  });

  async function runSearch(next = false) {
    searching = true;
    page = next ? page + 1 : 1;
    try {
      const { items } = await api.discoverBrowse(kind, { ...filters, page, available: onlyAvailable ? '1' : '' });
      results = next ? [...results, ...items] : items;
      more = items.length >= 20;
    } catch { if (!next) results = []; more = false; } finally { searching = false; }
  }

  const slides = $derived((rows.find((r) => r.id === 'trending')?.items || []).filter((d) => d.banner).slice(0, 6).map((d) => ({
    kicker: d.available ? t('Disponible en tus fuentes') : t('Tendencia'), title: d.title, banner: d.banner, cover: d.cover, genres: d.genres,
    score: d.score, synopsis: d.synopsis, format: d.format, primaryLabel: kind === 'anime' ? t('Ver') : t('Leer'), onPrimary: () => openDiscover(d)
  })));
  const line = (d) => [kind === 'anime' ? formatName(d.format) : (d.chapters ? `${d.chapters} cap.` : formatName(d.format)), seasonName(d.season, d.year)].filter(Boolean).join(' · ');
  const badges = (d) => (d.available ? [{ text: t('En tus fuentes'), tone: 'accent' }] : []);
  const detail = (d) => [d.genres?.slice(0, 3).map(genreName).join(' · '), d.synopsis].filter(Boolean).join('\n\n');
</script>

<div class="page">
  <SectionTabs />
  {#if kind === 'comics'}
    <h1 class="page-title">{TITLE}</h1>
    <form class="filters" onsubmit={(e) => { e.preventDefault(); loadComics(); }}>
      <select class="field" bind:value={comicsLang} onchange={() => loadComics()} aria-label={t('Idioma')}>
        <option value="es">{t('Español')}</option>
        <option value="en">{t('Inglés')}</option>
      </select>
      <select class="field" bind:value={comicsSort} onchange={() => loadComics()} aria-label={t('Orden')}>
        <option value="updated">{t('Recién actualizado')}</option>
        <option value="popular">{t('Popularidad')}</option>
        <option value="az">{t('De la A a la Z')}</option>
      </select>
    </form>
    <div class="grid results">
      {#each comics as w (w.key)}
        <MediaCard href={'/obras/' + encodeURIComponent(w.key) + '?lang=' + comicsLang} title={w.title} cover={w.cover} showType type={w.type}
          badges={w.sources > 1 ? [{ text: t('En {0} fuentes', [w.sources]), tone: 'dark' }] : []} />
      {/each}
    </div>
    {#if !comicsLoading && !comics.length}<p class="muted center-text">{t('Todavía no hay cómics en este idioma en tus fuentes.')}</p>{/if}
    {#if comicsMore}<div class="center-text"><button class="btn tonal" onclick={() => loadComics(true)} disabled={comicsLoading}>{comicsLoading ? t('Cargando...') : t('Ver más')}</button></div>{/if}
  {:else}
  <Hero {slides} />
  <h1 class="page-title">{TITLE}</h1>

  {#each rows.filter((r) => r.id !== 'trending') as r (r.id)}
    <Shelf title={t(r.title)}>
      {#each r.items as d (d.anilistId)}
        <MediaCard title={d.title} cover={d.cover} color={d.color} onclick={() => openDiscover(d)} subtitle={line(d)} score={score10(d.score)} badges={badges(d)} detail={detail(d)} />
      {/each}
    </Shelf>
  {:else}
    {#if loadingRows}<Shelf title={t('Cargando...')} loading></Shelf>{/if}
  {/each}

  {#if kind === 'anime'}
    <section class="dubs" aria-label={t('Doblajes')}>
      <h2>{t('Doblajes')}</h2>
      <div class="dub-tabs" role="tablist">
        {#each DUB_TABS as d (d.audio)}
          <button role="tab" class="chip" class:on={dubTab === d.audio} aria-selected={dubTab === d.audio} onclick={() => loadDubs(d.audio)}>{t(d.label)}</button>
        {/each}
      </div>
      {#if dubsLoading}
        <Shelf title="" loading></Shelf>
      {:else if dubs.length}
        <div class="dub-grid">
          {#each dubs as w (w.key)}
            <MediaCard href={'/obras/' + encodeURIComponent(w.key) + '?lang=' + dubLang(dubTab) + '&audio=' + dubTab} title={w.title} cover={w.cover} badges={audioBadges(w.audios)} />
          {/each}
        </div>
      {:else}
        <p class="muted">{t('Todavía no hay anime con este audio en tus fuentes.')}</p>
      {/if}
    </section>
  {/if}

  <section class="explorer" aria-label={t('Explorar')}>
    <h2>{t('Explorar')}</h2>
    <form class="filters" onsubmit={(e) => { e.preventDefault(); runSearch(); }}>
      <select class="field" bind:value={filters.genre} onchange={() => runSearch()} aria-label={t('Género')}>
        <option value="">{t('Todos los géneros')}</option>
        {#each GENRES as g (g)}<option value={g}>{genreName(g)}</option>{/each}
      </select>
      <select class="field" bind:value={filters.year} onchange={() => runSearch()} aria-label={t('Año')}>
        <option value="">{t('Cualquier año')}</option>
        {#each YEARS as y (y)}<option value={y}>{y}</option>{/each}
      </select>
      <select class="field" bind:value={filters.status} onchange={() => runSearch()} aria-label={t('Estado')}>
        {#each STATUS as [v, l] (v)}<option value={v}>{l}</option>{/each}
      </select>
      {#if FORMATS.length}
        <select class="field" bind:value={filters.format} onchange={() => runSearch()} aria-label={t('Formato')}>
          {#each FORMATS as [v, l] (v)}<option value={v}>{l}</option>{/each}
        </select>
      {/if}
      {#if COUNTRIES.length}
        <select class="field" bind:value={filters.country} onchange={() => runSearch()} aria-label={t('Origen')}>
          {#each COUNTRIES as [v, l] (v)}<option value={v}>{l}</option>{/each}
        </select>
      {/if}
      <select class="field" bind:value={filters.minScore} onchange={() => runSearch()} aria-label={t('Nota mínima')}>
        <option value="">{t('Cualquier nota')}</option>
        {#each [60, 70, 80, 90] as n (n)}<option value={n}>{t('Más de {0}', [n / 10])}</option>{/each}
      </select>
      <select class="field" bind:value={filters.sort} onchange={() => runSearch()} aria-label={t('Orden')}>
        {#each SORTS as [v, l] (v)}<option value={v}>{l}</option>{/each}
      </select>
      <label class="only"><input type="checkbox" bind:checked={onlyAvailable} onchange={() => runSearch()} /> {t('Solo lo que está en mis fuentes')}</label>
    </form>
    <div class="grid results">
      {#each results as d (d.anilistId)}
        <MediaCard title={d.title} cover={d.cover} color={d.color} onclick={() => openDiscover(d)} subtitle={line(d)} score={score10(d.score)} badges={badges(d)} detail={detail(d)} />
      {/each}
    </div>
    {#if !searching && !results.length}
      <p class="muted center-text">{onlyAvailable ? t('Nada con esos filtros en tus fuentes todavía. Quita «Solo lo que está en mis fuentes» para ver todo.') : t('Nada con esos filtros.')}</p>
    {/if}
    {#if more && results.length}
      <div class="center-text"><button class="btn tonal" onclick={() => runSearch(true)} disabled={searching}>{searching ? 'Cargando...' : t('Ver más')}</button></div>
    {/if}
  </section>
  {/if}
</div>

<style>
  .page-title { margin: var(--s-xl) 0 0; font-size: var(--fs-title); font-weight: 800; letter-spacing: -0.02em; }
  .explorer { margin-top: 56px; }
  .explorer h2 { font-size: var(--fs-section); font-weight: 750; margin: 0 0 14px; }
  .filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 18px; }
  .filters .field { width: auto; min-width: 150px; height: 42px; border-radius: var(--r-pill); background: var(--c-surface-high); border-color: var(--c-border); padding: 0 14px; }
  .only { display: flex; align-items: center; gap: 8px; color: var(--c-text-dim); font-size: var(--fs-small); }
  .results { grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 22px 14px; }
  @media (min-width: 900px) { .results { grid-template-columns: repeat(auto-fill, minmax(168px, 1fr)); } }
  .center-text { text-align: center; margin: 24px 0; }
  .dubs { margin: 8px 0 28px; }
  .dub-tabs { display: flex; flex-wrap: wrap; gap: 8px; margin: 6px 0 14px; }
  .dub-tabs .chip { padding: 6px 14px; border-radius: 999px; border: 1px solid var(--c-border); background: var(--c-surface-high); color: inherit; }
  .dub-tabs .chip.on { background: var(--c-accent); color: var(--c-on-accent); border-color: transparent; }
  .dub-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 14px; }
</style>
