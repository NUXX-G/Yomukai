<script>
  import { t, locale } from '../lib/i18n.svelte.js';
  // /u/nombre: someone's profile. Banner and avatar, the title they picked (an achievement), bio, stats, favourite,
  // achievements and the part of their library they share. Your own profile has «Editar perfil».
  import { api } from '../lib/api.js';
  import { route, media } from '../lib/router.svelte.js';
  import { session, notify, categories } from '../lib/store.svelte.js';
  import { genreName } from '../lib/ui.js';
  import MediaCard from '../components/MediaCard.svelte';
  import Sheet from '../components/Sheet.svelte';
  import Icon from '../components/Icon.svelte';

  const name = route.params.name;
  let data = $state(null);
  let error = $state('');
  let editing = $state(false);
  let form = $state({});
  let saving = $state(false);
  let tab = $state('library');

  async function load() {
    try { data = await api.user(name); } catch (e) { error = e.message; }
  }
  load();

  const p = $derived(data?.profile);
  const mine = $derived(p && session.user?.name?.toLowerCase() === p.name.toLowerCase());
  const unlocked = $derived((data?.achievements || []).filter((a) => a.unlockedAt));
  const sortedAchievements = $derived([...(data?.achievements || [])].sort((a, b) => (b.unlockedAt ? 1 : 0) - (a.unlockedAt ? 1 : 0)));
  const shown = $derived(unlocked.find((a) => a.id === p?.shownTitle));
  const RARITY = { common: t('Común'), rare: t('Raro'), epic: t('Épico'), legendary: t('Legendario') };

  function edit() {
    form = { bio: p.bio, accent: p.accent || '#ff8a5b', hidden: p.hidden, privateCategories: [...p.privateCategories], shownTitle: p.shownTitle, favorite: p.favorite };
    editing = true;
  }

  async function save(e) {
    e.preventDefault();
    saving = true;
    try {
      await api.saveProfile(form);
      editing = false;
      notify(t('Perfil guardado'));
      await load();
    } catch (err) { notify(err.message); } finally { saving = false; }
  }

  async function upload(kind, e) {
    const file = e.currentTarget.files?.[0];
    e.currentTarget.value = '';
    if (!file) return;
    if (file.size > 4 * 1024 * 1024) return notify(t('La imagen pesa demasiado (máximo 4 MB)'));
    try { await api.uploadProfileImage(kind, file); notify(kind === 'avatar' ? t('Avatar cambiado') : t('Banner cambiado')); await load(); } catch (err) { notify(err.message); }
  }

  async function removeImage(kind) {
    try { await api.removeProfileImage(kind); await load(); } catch (err) { notify(err.message); }
  }

  function toggleCategory(c) {
    form.privateCategories = form.privateCategories.includes(c) ? form.privateCategories.filter((x) => x !== c) : [...form.privateCategories, c];
  }

  const since = (ms) => new Date(ms).toLocaleDateString(locale(), { month: 'long', year: 'numeric' });
</script>

{#if error}
  <div class="page center"><p>{error}</p><a class="btn tonal" href="/amigos">{t('Ver amigos')}</a></div>
{:else if !data}
  <div class="page center"><div class="spinner"></div></div>
{:else}
  <div class="profile" style:--accent={p.accent || 'var(--c-accent)'}>
    <div class="banner">
      {#if p.banner}<img src={p.banner} alt="" />{/if}
    </div>
    <div class="page">
      <header class="head">
        <div class="avatar" aria-hidden="true">
          {#if p.avatar}<img src={p.avatar} alt="" />{:else}<span>{p.name.slice(0, 1).toUpperCase()}</span>{/if}
        </div>
        <div class="who">
          <h1>{p.name}</h1>
          {#if shown}<div class="title">{shown.emoji} {shown.name}</div>{/if}
          <div class="small muted">{t('En Yomukai desde {0}', [since(p.since)])}{#if p.admin}&nbsp;{t('· admin')}{/if}</div>
          {#if data.now}<div class="now"><span class="dot"></span> {data.now.kind === 'watching' ? 'Viendo' : 'Leyendo'} {data.now.title}{#if data.now.label} · {data.now.label}{/if}</div>{/if}
        </div>
        {#if mine}<button class="btn tonal" onclick={edit}><Icon name="text" size={18} /> {t('Editar perfil')}</button>{/if}
      </header>
      {#if p.bio}<p class="bio">{p.bio}</p>{/if}

      <section class="stats" aria-label={t('Estadísticas')}>
        <div><b>{data.stats.episodes}</b><span>{t('episodios')}</span></div>
        <div><b>{data.stats.chapters}</b><span>{t('capítulos')}</span></div>
        <div><b>{data.stats.hours}</b><span>{t('horas aprox.')}</span></div>
        <div><b>{data.stats.streak}</b><span>{t('días de racha')}{#if data.stats.longestStreak > data.stats.streak} {t('(récord {0})', [data.stats.longestStreak])}{/if}</span></div>
        {#if data.stats.topGenre}<div><b class="genre">{genreName(data.stats.topGenre)}</b><span>{t('género favorito')}</span></div>{/if}
      </section>

      {#if p.favorite}
        <a class="favorite" href={media(p.favorite.sourceId, p.favorite.mediaId)}>
          {#if p.favorite.cover}<img src={p.favorite.cover} alt="" />{/if}
          <div><div class="small muted">{t('Su favorita')}</div><div class="fav-title">{p.favorite.title}</div></div>
        </a>
      {/if}

      <div class="tabs" role="tablist">
        <button role="tab" class:on={tab === 'library'} aria-selected={tab === 'library'} onclick={() => (tab = 'library')}>{t('Biblioteca · {0}', [data.library.length])}</button>
        <button role="tab" class:on={tab === 'achievements'} aria-selected={tab === 'achievements'} onclick={() => (tab = 'achievements')}>{t('Logros · {0}/{1}', [unlocked.length, data.achievements.length])}</button>
      </div>

      {#if tab === 'library'}
        {#if data.libraryHidden}
          <p class="muted">{t('{0} prefiere no enseñar su biblioteca.', [p.name])}</p>
        {:else if !data.library.length}
          <p class="muted">{t('Aún no hay nada aquí.')}</p>
        {:else}
          <div class="grid lib">
            {#each data.library as r (r.sourceId + r.mediaId)}
              <MediaCard title={r.title} cover={r.cover} type={r.type} showType href={media(r.sourceId, r.mediaId)} />
            {/each}
          </div>
        {/if}
      {:else}
        <p class="small muted">{t('Los logros se desbloquean solos al leer, ver anime, repasar palabras y participar. Puedes lucir uno como título bajo tu nombre desde «Editar perfil».')}</p>
        <div class="achievements">
          {#each sortedAchievements as a (a.id)}
            <div class="ach {a.rarity}" class:locked={!a.unlockedAt}>
              <span class="emoji" aria-hidden="true">{a.emoji}</span>
              <div class="grow">
                <div class="ach-name">{t(a.name)}</div>
                <div class="small muted">{t(a.description)}</div>
                <div class="tiny">{t('{0} · {1} % del grupo', [RARITY[a.rarity], a.percent])}{#if a.unlockedAt} · {new Date(a.unlockedAt).toLocaleDateString(locale())}{/if}</div>
              </div>
            </div>
          {/each}
        </div>
      {/if}
    </div>
  </div>
{/if}

<Sheet bind:open={editing} title={t('Editar perfil')}>
  {#if editing}
    <form class="edit" onsubmit={save}>
      <div class="images">
        <label class="btn tonal">{t('Cambiar avatar')}<input type="file" accept="image/*" hidden onchange={(e) => upload('avatar', e)} /></label>
        {#if p.avatar}<button type="button" class="btn text" onclick={() => removeImage('avatar')}>{t('Quitar')}</button>{/if}
        <label class="btn tonal">{t('Cambiar banner')}<input type="file" accept="image/*" hidden onchange={(e) => upload('banner', e)} /></label>
        {#if p.banner}<button type="button" class="btn text" onclick={() => removeImage('banner')}>{t('Quitar')}</button>{/if}
      </div>
      <label>{t('Sobre ti')} <textarea class="field" rows="3" maxlength="300" bind:value={form.bio}></textarea></label>
      <label class="row">{t('Color')} <input type="color" bind:value={form.accent} /></label>
      <label>{t('Título bajo tu nombre')}
        <select class="field" bind:value={form.shownTitle}>
          <option value="">{t('Ninguno')}</option>
          {#each unlocked as a (a.id)}<option value={a.id}>{a.emoji} {a.name}</option>{/each}
        </select>
      </label>
      <label class="row"><input type="checkbox" bind:checked={form.hidden} /> {t('Ocultar mi biblioteca y lo que estoy viendo')}</label>
      {#if categories.list.length}
        <div class="small muted">{t('Categorías privadas (no se ven ni salen en la actividad):')}</div>
        <div class="chips">
          {#each categories.list as c (c.name)}
            <button type="button" class="chip" class:on={form.privateCategories.includes(c.name)} onclick={() => toggleCategory(c.name)}>{c.name}</button>
          {/each}
        </div>
      {/if}
      <button class="btn primary block" disabled={saving}>{saving ? 'Guardando...' : 'Guardar'}</button>
    </form>
  {/if}
</Sheet>

<style>
  .profile { position: relative; }
  .banner { height: clamp(140px, 24vw, 280px); margin: calc(-1 * (var(--layout-top-bar) + var(--safe-top))) 0 0; background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 55%, var(--c-canvas)), var(--c-surface)); overflow: hidden; }
  .banner img { width: 100%; height: 100%; object-fit: cover; }
  .head { display: flex; align-items: flex-end; gap: 18px; margin-top: -56px; flex-wrap: wrap; }
  .avatar { width: 112px; height: 112px; border-radius: 50%; overflow: hidden; border: 4px solid var(--c-canvas); background: color-mix(in srgb, var(--accent) 40%, var(--c-surface-high)); display: grid; place-items: center; flex: none; }
  .avatar img { width: 100%; height: 100%; object-fit: cover; }
  .avatar span { font-size: 2.6rem; font-weight: 800; }
  .who { flex: 1; min-width: 200px; }
  h1 { margin: 0; font-size: var(--fs-title); font-weight: 800; }
  .title { color: var(--accent); font-weight: 700; margin: 2px 0; }
  .now { display: inline-flex; align-items: center; gap: 6px; margin-top: 6px; font-size: var(--fs-small); }
  .now .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-success); }
  .bio { max-width: 720px; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.5; }
  .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 10px; margin: 20px 0; }
  .stats div { padding: 14px; border-radius: var(--r-lg); background: var(--c-surface); border: 1px solid var(--c-border); display: flex; flex-direction: column; gap: 2px; }
  .stats b { font-size: 1.5rem; font-weight: 800; color: var(--accent); }
  .stats b.genre { font-size: 1.1rem; }
  .stats span { font-size: var(--fs-small); color: var(--c-text-dim); }
  .favorite { display: flex; gap: 12px; align-items: center; padding: 10px; border-radius: var(--r-lg); background: var(--c-surface); max-width: 420px; margin-bottom: 16px; }
  .favorite img { width: 48px; aspect-ratio: 2 / 3; object-fit: cover; border-radius: var(--r-md); }
  .fav-title { font-weight: 700; }
  .tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--c-border); margin: 12px 0 18px; }
  .tabs button { padding: 12px 16px; color: var(--c-text-dim); font-weight: 650; border-bottom: 3px solid transparent; }
  .tabs button.on { color: var(--c-text); border-bottom-color: var(--accent); }
  .lib { grid-template-columns: repeat(auto-fill, minmax(110px, 1fr)); gap: 18px 12px; }
  @media (min-width: 900px) { .lib { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); } }
  .achievements { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }
  .ach { display: flex; gap: 12px; align-items: flex-start; padding: 12px; border-radius: var(--r-lg); background: var(--c-surface); border: 1px solid var(--c-border); }
  .ach.locked { opacity: 0.45; filter: grayscale(1); }
  .ach.rare:not(.locked) { border-color: #5aa9ff66; } .ach.epic:not(.locked) { border-color: #b36bff77; } .ach.legendary:not(.locked) { border-color: #ffc64a99; box-shadow: 0 0 24px #ffc64a22; }
  .emoji { font-size: 1.9rem; line-height: 1; }
  .grow { flex: 1; min-width: 0; }
  .ach-name { font-weight: 700; }
  .tiny { font-size: var(--fs-tiny); color: var(--c-text-faint); margin-top: 4px; }
  .edit { display: flex; flex-direction: column; gap: 14px; }
  .edit label { display: flex; flex-direction: column; gap: 6px; font-size: var(--fs-small); color: var(--c-text-dim); }
  .edit label.row { flex-direction: row; align-items: center; gap: 10px; }
  .images { display: flex; flex-wrap: wrap; gap: 8px; }
  .images label { cursor: pointer; }
  .chips { display: flex; flex-wrap: wrap; gap: 8px; }
  .center { min-height: 50vh; display: grid; place-content: center; justify-items: center; gap: 12px; }
</style>
