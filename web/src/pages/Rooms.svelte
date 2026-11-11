<script>
  import { t } from '../lib/i18n.svelte.js';
  // /salas (F4.1): the common rooms anyone in the group can walk into, what plays and what comes next in each queue;
  // making a new one, and opening your own room to everyone.
  import { onDestroy } from 'svelte';
  import { api } from '../lib/api.js';
  import { session, notify, features } from '../lib/store.svelte.js';
  import { navigate } from '../lib/router.svelte.js';
  import Icon from '../components/Icon.svelte';

  let rooms = $state(null);
  let mine = $state(null);
  let name = $state('');
  let lasting = $state(false);
  let busy = $state(false);

  async function load() {
    try { rooms = await api.roomsOpen(); } catch (e) { rooms = []; notify(e.message); }
  }
  load();
  if (!session.user?.guest) api.roomMine().then((r) => (mine = r), () => {});
  const timer = setInterval(() => { if (!document.hidden) load(); }, 15_000);
  onDestroy(() => clearInterval(timer));

  async function create(e) {
    e.preventDefault();
    busy = true;
    try {
      const r = await api.roomCreateOpen(name.trim(), lasting);
      navigate('/sala/' + r.code);
    } catch (err) { notify(err.message); } finally { busy = false; }
  }

  async function toggleMine() {
    if (!mine) return;
    try {
      mine = await api.roomSetOpen(mine.code, !mine.open);
      notify(mine.open ? t('Tu sala ya es común: cualquiera del grupo puede entrar') : t('Tu sala ya no aparece en las salas comunes'));
      load();
    } catch (e) { notify(e.message); }
  }

  async function remove(r) {
    if (!confirmCode || confirmCode !== r.code) { confirmCode = r.code; return; }
    try { await api.roomDelete(r.code); confirmCode = ''; load(); } catch (e) { notify(e.message); }
  }
  let confirmCode = $state('');
</script>

<div class="page salas">
  <h1>{t('Salas comunes')}</h1>
  <p class="muted lead">{t('Salas abiertas a todo el grupo: entra, mira la cola de episodios (de varios animes) y vota para saltar. Cuando acaba un episodio empieza el siguiente de la cola solo.')}</p>

  {#if features.flags.open_rooms === false}
    <p class="muted">{t('Las salas comunes están desactivadas en este servidor.')}</p>
  {:else}
    {#if !session.user?.guest}
      <form class="new" onsubmit={create}>
        <input class="field" bind:value={name} maxlength="60" placeholder={t('Nombre de la sala (p. ej. Viernes de anime)')} aria-label={t('Nombre de la sala')} />
        {#if session.user?.admin}<label class="small"><input type="checkbox" bind:checked={lasting} /> {t('Fija (no caduca)')}</label>{/if}
        <button class="btn primary" disabled={busy}><Icon name="add" size={18} /> {t('Crear sala común')}</button>
      </form>
      {#if mine}
        <p class="small muted mine">
          {t('Tu sala ({0})', [mine.code])}: {mine.open ? t('es común') : t('solo con enlace')}
          <button class="btn text" onclick={toggleMine}>{mine.open ? t('Quitar de las comunes') : t('Abrirla a todos')}</button>
        </p>
      {/if}
    {/if}

    {#if rooms === null}
      <div class="spinner"></div>
    {:else}
      <div class="grid">
        {#each rooms as r (r.code)}
          <article class="room">
            <a class="body" href="/sala/{r.code}">
              <span class="cover">{#if r.next?.cover}<img src={r.next.cover} alt="" loading="lazy" />{:else}<Icon name="tv" size={32} />{/if}</span>
              <span class="info">
                <span class="name">{r.name}{#if r.lasting} <span class="tag">{t('Fija')}</span>{/if}</span>
                {#if r.title}<span class="now"><span class="dot" class:live={r.playing}></span>{r.title}{#if r.episode} · {r.episode}{/if}</span>
                {:else}<span class="muted small">{t('No se está viendo nada')}</span>{/if}
                {#if r.next}<span class="muted small">{t('Después: {0}', [[r.next.title, r.next.episode].filter(Boolean).join(' · ')])}</span>{/if}
                <span class="muted small">{r.members.length ? t('{0} dentro: {1}', [r.members.length, r.members.join(', ')]) : t('Vacía')} · {t('{0} en cola', [r.queued])}</span>
              </span>
            </a>
            <div class="actions">
              <a class="btn tonal" href="/sala/{r.code}">{t('Entrar')}</a>
              {#if session.user?.admin || r.owner === session.user?.name}
                <button class="btn text" onclick={() => remove(r)}>{confirmCode === r.code ? t('¿Seguro? Pulsa otra vez') : t('Cerrar sala')}</button>
              {/if}
            </div>
          </article>
        {:else}
          <p class="muted">{t('Aún no hay salas comunes. Crea una y pásale el enlace al grupo.')}</p>
        {/each}
      </div>
    {/if}
  {/if}
</div>

<style>
  h1 { margin: 12px 0 4px; font-size: var(--fs-title); font-weight: 800; }
  .lead { max-width: 680px; margin: 0 0 16px; }
  .new { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 8px; }
  .new .field { flex: 1 1 240px; min-width: 0; }
  .mine { margin: 0 0 16px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr)); gap: 12px; }
  .room { display: flex; flex-direction: column; gap: 8px; padding: 12px; border-radius: var(--r-lg); background: var(--c-surface); border: 1px solid var(--c-border); }
  .body { display: flex; gap: 12px; color: inherit; text-decoration: none; }
  .cover { width: 64px; height: 90px; border-radius: 10px; overflow: hidden; background: var(--c-surface-high); display: grid; place-items: center; flex: none; }
  .cover img { width: 100%; height: 100%; object-fit: cover; }
  .info { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
  .name { font-weight: 800; font-size: 1.05rem; }
  .now { display: flex; align-items: center; gap: 6px; font-size: var(--fs-small); }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-text-dim); flex: none; }
  .dot.live { background: var(--c-success); }
  .tag { font-size: 0.7rem; padding: 1px 6px; border-radius: 6px; background: var(--c-surface-high); color: var(--c-text-dim); vertical-align: middle; }
  .actions { display: flex; gap: 8px; align-items: center; }
  .small { font-size: var(--fs-small); }
</style>
