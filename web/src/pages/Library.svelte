<script>
  import { t, locale } from '../lib/i18n.svelte.js';
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { library, sources, updates, loadUpdates, READING_STATUSES, statusOf, categories, editCategory } from '../lib/store.svelte.js';
  import Sheet from '../components/Sheet.svelte';
  import { media, reader } from '../lib/router.svelte.js';
  import { TYPE_FILTERS } from '../lib/format.js';
  import MediaCard from '../components/MediaCard.svelte';
  import Icon from '../components/Icon.svelte';

  const SORTS = [['added', t('Añadidas')], ['updated', 'Novedades'], ['title', t('Título')]];

  let tab = $state(sessionStorage.getItem('library.tab') || 'library');
  let status = $state(sessionStorage.getItem('library.status') || 'ALL');
  let sort = $state(sessionStorage.getItem('library.sort') || 'added');
  let typeKey = $state('ALL');
  let category = $state(sessionStorage.getItem('library.category') || '');
  let manageOpen = $state(false);
  let newCategory = $state('');
  let renaming = $state(null);
  let renameTo = $state('');
  let filter = $state('');
  let history = $state(null);

  $effect(() => {
    sessionStorage.setItem('library.tab', tab);
    sessionStorage.setItem('library.status', status);
    sessionStorage.setItem('library.sort', sort);
    sessionStorage.setItem('library.category', category);
  });
  // A category deleted elsewhere stops filtering.
  $effect(() => { if (category && categories.loaded && !categories.list.some((c) => c.name === category)) category = ''; });

  async function addCategory(e) {
    e.preventDefault();
    const name = newCategory.trim();
    if (!name) return;
    await editCategory({ new: name, sortOrder: categories.list.length });
    newCategory = '';
  }
  async function rename(e) {
    e.preventDefault();
    const name = renameTo.trim();
    if (name && name !== renaming) await editCategory({ old: renaming, new: name });
    if (category === renaming) category = name;
    renaming = null;
  }
  async function removeCategory(name) {
    if (!confirm(t('¿Borrar la categoría «{0}»? Las obras se quedan en tu biblioteca.', [name]))) return;
    await editCategory({ old: name });
  }

  const keyOf = (r) => r.sourceId + '|' + r.mediaId;
  const unread = (r) => updates.byKey[keyOf(r)]?.unread || 0;
  const typeFilter = $derived(TYPE_FILTERS.find((f) => f.key === typeKey));
  // One card per work: the server marks the same work saved from another source (it stays saved, just not shown twice).
  const works = $derived(library.items.filter((r) => !r.sameWorkShown));
  const counts = $derived(Object.fromEntries(READING_STATUSES.map(([k]) => [k, works.filter((r) => statusOf(r) === k).length])));

  const items = $derived.by(() => {
    const list = works
      .filter((r) => status === 'ALL' || statusOf(r) === status)
      .filter((r) => !category || (r.categories || []).includes(category))
      .filter((r) => !typeFilter.types || typeFilter.types.includes(r.type))
      .filter((r) => !filter || r.title.toLowerCase().includes(filter.toLowerCase()));
    if (sort === 'title') return [...list].sort((a, b) => a.title.localeCompare(b.title, 'es'));
    if (sort === 'updated') {
      const last = (r) => updates.byKey[keyOf(r)]?.lastUpdate || 0;
      return [...list].sort((a, b) => (unread(b) > 0) - (unread(a) > 0) || last(b) - last(a));
    }
    return list;
  });

  const withNews = $derived(works.filter((r) => unread(r) > 0)
    .sort((a, b) => (updates.byKey[keyOf(b)]?.lastUpdate || 0) - (updates.byKey[keyOf(a)]?.lastUpdate || 0)));

  onMount(() => {
    api.library().then((l) => (library.items = l)).catch(() => {});
    loadUpdates();
  });
  $effect(() => { if (tab === 'history' && !history) api.history().then((h) => (history = h)).catch(() => (history = [])); });

  const kind = (id) => sources.list.find((s) => s.id === id)?.kind;
  async function forget(h) {
    history = history.filter((x) => x !== h);
    await api.removeHistory(h.sourceId, h.mediaId).catch(() => {});
  }
  const when = (ms) => new Date(ms).toLocaleDateString(locale(), { day: 'numeric', month: 'short' });
  const newsLabel = (n, r) => `${n} ${kind(r.sourceId) === 'anime' ? (n === 1 ? t('episodio nuevo') : t('episodios nuevos')) : (n === 1 ? t('capítulo nuevo') : t('capítulos nuevos'))}`;
</script>

<div class="page">
  <header class="topbar">
    <h1>{t('Biblioteca')}</h1>
    <span class="grow"></span>
    <button class="refresh" onclick={() => loadUpdates(true)} disabled={updates.loading} aria-label={t('Buscar capítulos nuevos')} title={t('Buscar capítulos nuevos')}>
      {#if updates.loading}<span class="spinner tiny"></span>{:else}<Icon name="refresh" size={20} />{/if}
      <span class="refresh-label">{t('Buscar capítulos nuevos')}</span>
    </button>
  </header>

  <div class="lib">
  <aside class="side" aria-label={t('Filtros de la biblioteca')}>
    <div class="group">
      <div class="group-label">{t('Ver')}</div>
      <div class="chips">
        <button class="chip" class:on={tab === 'library'} onclick={() => (tab = 'library')}><span>{t('Guardadas')}</span><span class="n">{library.items.length}</span></button>
        <button class="chip" class:on={tab === 'news'} onclick={() => (tab = 'news')}><span>{t('Novedades')}</span>{#if withNews.length}<span class="n hot">{withNews.length}</span>{/if}</button>
        <button class="chip" class:on={tab === 'history'} onclick={() => (tab = 'history')}><span>{t('Historial')}</span></button>
      </div>
    </div>
    {#if tab === 'library'}
      <div class="group">
        <div class="group-label">{t('Estado')}</div>
        <div class="chips">
          <button class="chip" class:on={status === 'ALL'} onclick={() => (status = 'ALL')}><span>{t('Todas')}</span></button>
          {#each READING_STATUSES as [k, label]}
            {#if counts[k]}<button class="chip" class:on={status === k} onclick={() => (status = k)}><span>{label}</span><span class="n">{counts[k]}</span></button>{/if}
          {/each}
        </div>
      </div>
      <div class="group">
        <div class="group-label">{t('Categorías')} <button class="manage" onclick={() => (manageOpen = true)}>{t('Gestionar')}</button></div>
        <div class="chips">
          <button class="chip" class:on={!category} onclick={() => (category = '')}><span>{t('Todas las categorías')}</span></button>
          {#each categories.list as c (c.name)}
            <button class="chip" class:on={category === c.name} onclick={() => (category = c.name)}><span>{c.name}</span><span class="n">{library.items.filter((r) => (r.categories || []).includes(c.name)).length}</span></button>
          {/each}
          <button class="chip phone-only" onclick={() => (manageOpen = true)}><Icon name="tune" size={16} /> {t('Categorías')}</button>
        </div>
      </div>
      <div class="group">
        <div class="group-label">{t('Tipo')}</div>
        <div class="chips">{#each TYPE_FILTERS as f}<button class="chip" class:on={typeKey === f.key} onclick={() => (typeKey = f.key)}><span>{f.label}</span></button>{/each}</div>
      </div>
    {/if}
  </aside>

  <section class="content">
  {#if tab === 'library'}
    {#if library.items.length}
      <div class="tools">
        {#if library.items.length > 12}<input class="field" placeholder={t('Filtrar por título')} bind:value={filter} />{:else}<span class="grow"></span>{/if}
        <select class="field sort" bind:value={sort} aria-label={t('Ordenar')}>
          {#each SORTS as [k, label]}<option value={k}>{label}</option>{/each}
        </select>
      </div>
    {/if}
    {#if !library.items.length}
      <div class="center"><Icon name="library" size={48} /><p class="muted">{t('Aún no has guardado nada. Pulsa «Añadir a la biblioteca» en cualquier obra.')}</p></div>
    {:else if !items.length}
      <p class="muted" style="margin-top:24px">{t('Nada con estos filtros.')}</p>
    {:else}
      <div class="grid" style="margin-top:16px">
        {#each items as r (keyOf(r))}
          <div class="slot">
            <MediaCard title={r.title} cover={r.cover} type={r.type} showType href={media(r.sourceId, r.mediaId)} />
            {#if unread(r)}<span class="badge" title={newsLabel(unread(r), r)}>{unread(r) > 99 ? '99+' : unread(r)}</span>{/if}
          </div>
        {/each}
      </div>
    {/if}
  {:else if tab === 'news'}
    {#if updates.loading && !withNews.length}
      <div class="center"><div class="spinner"></div><p class="muted small">{t('Mirando tus obras guardadas...')}</p></div>
    {:else if !withNews.length}
      <div class="center"><p class="muted">{t('Estás al día. Aquí salen las obras guardadas con capítulos nuevos desde el último que leíste.')}</p></div>
    {:else}
      <div class="rows">
      {#each withNews as r (keyOf(r))}
        {@const u = updates.byKey[keyOf(r)]}
        <div class="list-item">
          <a class="hist" href={u.next ? reader(kind(r.sourceId), r.sourceId, r.mediaId, u.next) : media(r.sourceId, r.mediaId)}>
            <img src={r.cover} alt="" loading="lazy" />
            <div class="grow">
              <div class="title">{r.title}</div>
              <div class="small accent">{newsLabel(u.unread, r)}</div>
              <div class="small muted">{t('Hasta el {0}{1}', [u.latestLabel, u.lastUpdate ? ' · ' + when(u.lastUpdate) : ''])}</div>
            </div>
          </a>
          <a class="icon-btn" href={media(r.sourceId, r.mediaId)} aria-label={t('Ficha')}><Icon name="list" size={20} /></a>
        </div>
      {/each}
      </div>
    {/if}
  {:else if history === null}
    <div class="center"><div class="spinner"></div></div>
  {:else if !history.length}
    <div class="center"><p class="muted">{t('Lo que leas y veas aparecerá aquí.')}</p></div>
  {:else}
    <div class="rows">
    {#each history as h (h.sourceId + h.mediaId)}
      <div class="list-item">
        <a class="hist" href={reader(kind(h.sourceId), h.sourceId, h.mediaId, h.chapterId)}>
          <img src={h.cover} alt="" loading="lazy" />
          <div class="grow">
            <div class="title">{h.title}</div>
            <div class="small accent">{h.chapterTitle}</div>
            <div class="small muted">{when(h.readAt)}</div>
          </div>
        </a>
        <a class="icon-btn" href={media(h.sourceId, h.mediaId)} aria-label={t('Ficha')}><Icon name="list" size={20} /></a>
        <button class="icon-btn" onclick={() => forget(h)} aria-label={t('Quitar del historial')}><Icon name="close" size={20} /></button>
      </div>
    {/each}
    </div>
  {/if}
  </section>
  </div>
</div>

<Sheet bind:open={manageOpen} title={t('Categorías')}>
  <p class="small muted" style="margin-top:0">{t('Agrupa tus obras como quieras. Una obra puede estar en varias; se eligen desde su ficha.')}</p>
  <form class="tools" onsubmit={addCategory}>
    <input class="field" placeholder={t('Nueva categoría')} bind:value={newCategory} maxlength="100" />
    <button class="btn primary" disabled={!newCategory.trim()}>{t('Crear')}</button>
  </form>
  {#each categories.list as c (c.name)}
    <div class="list-item">
      {#if renaming === c.name}
        <form class="tools grow" style="margin:0" onsubmit={rename}>
          <input class="field" bind:value={renameTo} maxlength="100" />
          <button class="btn tonal">{t('Guardar')}</button>
        </form>
      {:else}
        <span class="grow">{c.name}</span>
        <button class="icon-btn" onclick={() => { renaming = c.name; renameTo = c.name; }} aria-label={t('Renombrar')}><Icon name="text" size={20} /></button>
        <button class="icon-btn" onclick={() => removeCategory(c.name)} aria-label={t('Borrar')}><Icon name="delete" size={20} /></button>
      {/if}
    </div>
  {/each}
</Sheet>

<style>
  .grow { flex: 1; min-width: 0; }
  .tiny { width: 18px; height: 18px; border-width: 2px; }
  .tools { display: flex; gap: 8px; margin-top: 12px; }
  .tools .field:first-child { flex: 1; min-width: 0; }
  .tools .field { height: 40px; }
  .sort { width: auto; flex: none; }
  .slot { position: relative; min-width: 0; }
  .badge { position: absolute; top: 6px; right: 6px; min-width: 24px; height: 24px; padding: 0 7px; border-radius: 12px; display: grid; place-items: center; background: var(--primary); color: var(--on-primary); font-size: 0.75rem; font-weight: 700; box-shadow: 0 2px 8px #0008; }
  .hist { display: flex; gap: 12px; align-items: center; flex: 1; min-width: 0; }
  .hist img { width: 48px; height: 68px; object-fit: cover; border-radius: 6px; background: var(--surface-variant); flex: none; }
  .title { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .accent { color: var(--primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .refresh { width: 44px; height: 44px; display: inline-grid; place-items: center; border-radius: 50%; }
  .refresh-label, .group-label { display: none; }
  .group + .group { margin-top: 8px; }
  .n::before { content: ' · '; }
  .n.hot { color: var(--primary); font-weight: 700; }
  /* Computers: the filters become a side panel and the titles fill the rest. */
  @media (min-width: 1024px) {
    .refresh { width: auto; height: 40px; display: inline-flex; align-items: center; gap: 8px; padding: 0 16px; border-radius: 20px; border: 1px solid var(--outline); color: var(--primary); font-weight: 600; transition: background 0.15s; }
    .refresh:hover:not(:disabled) { background: color-mix(in srgb, var(--primary) 10%, transparent); }
    .refresh-label { display: inline; }
    .lib { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 36px; align-items: start; margin-top: 8px; }
    .side { position: sticky; top: calc(var(--top-h) + 24px); max-height: calc(100vh - var(--top-h) - 48px); overflow-y: auto; scrollbar-width: none; padding: 14px 10px; border-radius: 18px; background: var(--surface-low); border: 1px solid var(--outline-variant); }
    .side::-webkit-scrollbar { display: none; }
    .group + .group { margin-top: 18px; }
    .group-label { display: flex; align-items: center; justify-content: space-between; padding: 0 10px 6px; font-size: 0.72rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--text-dim); }
    .manage { font-size: 0.75rem; letter-spacing: 0; text-transform: none; color: var(--primary); font-weight: 600; }
    .manage:hover { text-decoration: underline; }
    .side .chips { flex-direction: column; gap: 2px; overflow: visible; }
    .side .chip { width: 100%; height: 38px; justify-content: space-between; border: 0; border-radius: 10px; padding: 0 12px; color: var(--text); background: none; font-size: 0.92rem; }
    .side .chip:hover:not(.on) { background: var(--surface-high); color: var(--text); }
    .side .chip.on { background: var(--secondary-container); color: var(--on-secondary-container); font-weight: 600; }
    .n { color: var(--text-dim); font-size: 0.82rem; font-variant-numeric: tabular-nums; }
    .n::before { content: none; }
    .chip.on .n { color: inherit; }
    .phone-only { display: none !important; }
    .content .tools { margin-top: 0; }
    .content .tools .field:first-child { max-width: 420px; }
    .rows { display: grid; grid-template-columns: repeat(auto-fill, minmax(380px, 1fr)); gap: 10px; }
    .rows .list-item { border: 0; border-radius: 14px; padding: 10px 8px 10px 10px; background: var(--surface); }
    .rows .list-item:hover { background: var(--surface-high); }
    .hist img { width: 56px; height: 80px; }
  }
</style>
