<script>
  import { t, i18n } from '../lib/i18n.svelte.js';
  import { onDestroy } from 'svelte';
  import { searchStream, api } from '../lib/api.js';
  import { settings, rememberSearch, saveSettings, rememberItems, sourceById } from '../lib/store.svelte.js';
  import { route, navigate, media } from '../lib/router.svelte.js';
  import { TYPE_FILTERS, LANGUAGES, groupBadges } from '../lib/format.js';
  import Icon from '../components/Icon.svelte';
  import MediaCard from '../components/MediaCard.svelte';

  /** Results grouped by kind, anime first, then the comics, then novels. */
  const SECTIONS = [['Anime', ['ANIME']], ['Manga', ['MANGA']], [t('Manhwa y manhua'), ['MANHWA', 'MANHUA']], [t('Cómics'), ['COMIC']], [t('Novelas'), ['NOVEL']]];

  let query = $state(route.query.get('q') || '');
  let typeKey = $state('ALL');
  let lang = $state('');
  let groups = $state([]);
  /** Works from the server's index: one card per work in the chosen language, answered at once. */
  let works = $state([]);
  let pending = $state([]);
  let errors = $state({});
  let searching = $state(false);
  let searched = $state(false);
  let stop = null;

  function run(save = true) {
    const q = query.trim();
    if (!q) return;
    stop?.();
    if (save) rememberSearch(q);
    navigate('/buscar?q=' + encodeURIComponent(q), { replace: true });
    groups = []; works = []; pending = []; errors = {}; searching = true; searched = true;
    const types = TYPE_FILTERS.find((f) => f.key === typeKey).types;
    const wanted = (lang || i18n.lang).toLowerCase();
    api.obrasBuscar(q, wanted, types?.join(',')).then((r) => { if (query.trim() === q) works = (r.works || []).map((w) => ({ ...w, lang: wanted })); }, () => {});
    stop = searchStream(q, { types, lang }, (update) => {
      groups = update.groups; pending = update.pending; errors = update.errors;
      rememberItems(update.groups.flatMap((g) => [g.primary, ...g.others]));
      if (update.done) searching = false;
    }, () => (searching = false));
  }

  $effect(() => { if (route.query.get('q') && !searched) run(false); });
  onDestroy(() => stop?.());

  function setType(k) { typeKey = k; if (searched) run(false); }
  function setLang(k) { lang = k; if (searched) run(false); }
  function clear() { query = ''; groups = []; works = []; searched = false; stop?.(); searching = false; }
  const sections = $derived(SECTIONS.map(([title, types]) => [title, groups.filter((g) => types.includes(g.primary.type))]).filter(([, list]) => list.length));
  const sourceName = (id) => sourceById(id)?.name ?? id;
  const line = (g) => [sourceName(g.primary.sourceId), g.others.length ? `+${g.others.length} ${g.others.length === 1 ? 'fuente' : 'fuentes'}` : ''].filter(Boolean).join(' · ');
  const allSources = (g) => [g.primary, ...g.others].map((x) => sourceName(x.sourceId)).join(', ');
  function forget(q) { settings.searchHistory = settings.searchHistory.filter((x) => x !== q); saveSettings(); }
</script>

<div class="page">
  <form class="searchbar" onsubmit={(e) => { e.preventDefault(); run(); }}>
    <Icon name="search" />
    <input type="search" placeholder={t('Busca en todas tus fuentes')} bind:value={query} enterkeyhint="search" autocomplete="off" />
    {#if query}<button type="button" class="icon-btn" onclick={clear} aria-label={t('Borrar')}><Icon name="close" size={20} /></button>{/if}
  </form>

  <div class="filters">
    <div class="chips">{#each TYPE_FILTERS as f}<button class="chip" class:on={typeKey === f.key} onclick={() => setType(f.key)}>{f.label}</button>{/each}</div>
    <div class="chips">{#each LANGUAGES as l}<button class="chip" class:on={lang === l.key} onclick={() => setLang(l.key)}>{l.label}</button>{/each}</div>
  </div>

  {#if !searched}
    {#if settings.searchHistory.length}
      <div class="section-title"><h2>{t('Recientes')}</h2></div>
      {#each settings.searchHistory as q}
        <div class="list-item">
          <button class="grow left" onclick={() => { query = q; run(); }}>{q}</button>
          <button class="icon-btn" onclick={() => forget(q)} aria-label={t('Quitar')}><Icon name="close" size={18} /></button>
        </div>
      {/each}
    {/if}
  {:else}
    {#if searching}
      <p class="small muted status"><span class="spinner tiny"></span> {t('Buscando en {0}...', [pending.join(', ') || t('tus fuentes')])}</p>
    {/if}
    {#if works.length}
      <section class="kind" aria-label={t('Obras')}>
        <h2>{t('Obras')} <span class="count">{works.length}</span></h2>
        <div class="grid results">
          {#each works as w (w.key)}
            <MediaCard title={w.title} cover={w.cover} href={'/obras/' + encodeURIComponent(w.key) + '?lang=' + w.lang} showType type={w.type}
              badges={w.sources > 1 ? [{ text: t('En {0} fuentes', [w.sources]), tone: 'dark' }] : []} />
          {/each}
        </div>
      </section>
    {/if}
    {#if !searching && !groups.length && !works.length}
      <p class="muted" style="margin-top:24px">{t('Sin resultados para «{0}».', [query])}</p>
    {/if}
    {#each sections as [title, list] (title)}
      <section class="kind" aria-label={title}>
        <h2>{title} <span class="count">{list.length}</span></h2>
        <div class="grid results">
          {#each list as g (g.primary.sourceId + g.primary.id)}
            <MediaCard
              title={g.primary.title} cover={g.primary.cover} href={media(g.primary.sourceId, g.primary.id)}
              subtitle={line(g)} doubt={g.doubt} detail={g.others.length ? t('En ') + allSources(g) : ''}
              badges={groupBadges(g)}
            />
          {/each}
        </div>
      </section>
    {/each}
    {#if Object.keys(errors).length && !searching}
      <p class="small muted">{t('Sin respuesta de: {0}', [Object.keys(errors).join(', ')])}</p>
    {/if}
  {/if}
</div>

<style>
  .searchbar { display: flex; align-items: center; gap: 10px; height: 52px; padding: 0 6px 0 16px; border-radius: 26px; background: var(--surface-high); margin: 8px 0 14px; }
  .searchbar input { flex: 1; background: none; border: 0; outline: none; font-size: 1rem; min-width: 0; }
  .grow { flex: 1; } .left { text-align: left; }
  .status { display: flex; align-items: center; gap: 8px; margin-top: 16px; }
  .tiny { width: 14px; height: 14px; border-width: 2px; }
  .kind { margin-top: 28px; }
  .kind h2 { font-size: var(--fs-section); font-weight: 750; margin: 0 0 14px; }
  .count { color: var(--c-text-faint); font-weight: 600; font-size: 0.9rem; margin-left: 6px; }
  .results { grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 18px 10px; }
  @media (min-width: 900px) { .results { grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 26px 16px; } }
  .filters { display: flex; flex-direction: column; gap: 8px; }
  @media (min-width: 1024px) {
    .searchbar { max-width: 760px; height: 56px; margin: 4px 0 16px; }
    .filters { flex-direction: row; align-items: center; gap: 12px; }
    .filters .chips + .chips { padding-left: 12px; border-left: 1px solid var(--outline-variant); }
  }
</style>
