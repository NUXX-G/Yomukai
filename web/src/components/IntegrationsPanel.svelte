<script>
  import { t } from '../lib/i18n.svelte.js';
  // Admin › Integraciones: Jellyfin (on the tailnet) and Kavita/Komga. Keys are written here but never shown again:
  // the server keeps them sealed and only says whether one is set. Folders are as the home worker sees them.
  import { api } from '../lib/api.js';
  import { notify } from '../lib/store.svelte.js';

  let form = $state(null);
  let keys = $state({ jellyfinKey: '', comicsKey: '' });
  let testing = $state(false);

  let follows = $state(null);
  api.integrations().then((v) => (form = v), (e) => notify(e.message));
  const loadFollows = () => api.follows().then((v) => (follows = v), () => (follows = []));
  loadFollows();

  // Who is who in Jellyfin: watched state and positions go both ways for paired accounts.
  let pairs = $state(null);
  const loadPairs = () => api.jellyfinUsers().then((v) => (pairs = v), () => (pairs = { jellyfin: [], users: [], online: false }));
  loadPairs();
  async function pair(user, jellyfinUser) {
    try { await api.linkJellyfin(user.id, jellyfinUser || null); user.jellyfinUser = jellyfinUser; notify(t('Guardado')); } catch (err) { notify(err.message); }
  }

  async function unfollow(f) {
    try { await api.unfollow(f.id); loadFollows(); } catch (err) { notify(err.message); }
  }
  async function checkNow() {
    try { const r = await api.checkFollows(); notify(r.queued ? t('{0} nuevos en cola', [r.queued]) : t('Nada nuevo por ahora')); loadFollows(); } catch (err) { notify(err.message); }
  }

  async function save(e) {
    e.preventDefault();
    try {
      form = await api.saveIntegrations({ ...form, ...keys });
      keys = { jellyfinKey: '', comicsKey: '' };
      notify(t('Guardado'));
    } catch (err) { notify(err.message); }
  }

  async function test() {
    testing = true;
    try {
      const r = await api.testJellyfin();
      notify(r.ok ? t('Conectado con {0} (Jellyfin {1})', [r.server, r.version]) : r.error);
    } catch (err) { notify(err.message); } finally { testing = false; }
  }
</script>

<section class="card">
  <h3 class="sub">{t('Integraciones (Jellyfin y cómics)')}</h3>
  {#if !form}
    <div class="spinner"></div>
  {:else}
    <form class="grid-form" onsubmit={save}>
      <p class="small muted span">{t('El VPS habla con Jellyfin por Tailscale; las descargas y el NAS los toca el servidor de casa. Las claves se guardan cifradas y no vuelven a mostrarse.')}</p>
      <label>{t('Dirección de Jellyfin')} <input class="field" placeholder={t('http://jellyfin.tu-tailnet.ts.net:8096')} bind:value={form.jellyfinUrl} /></label>
      <label>{t('Clave de API {0}', [form.jellyfinKeySet ? '(guardada)' : ''])} <input class="field" type="password" autocomplete="off" placeholder={form.jellyfinKeySet ? t('Déjalo vacío para mantenerla') : t('Panel de Jellyfin › Claves de API')} bind:value={keys.jellyfinKey} /></label>
      <label>{t('Carpeta de anime (en el worker)')} <input class="field" placeholder="/media/anime" bind:value={form.animePath} /></label>
      <label>{t('Carpeta de series')} <input class="field" placeholder="/media/series" bind:value={form.seriesPath} /></label>
      <label>{t('Carpeta de películas')} <input class="field" placeholder="/media/peliculas" bind:value={form.moviesPath} /></label>
      <label>{t('Carpeta de cómics (Kavita/Komga)')} <input class="field" placeholder="/media/comics" bind:value={form.comicsPath} /></label>
      <label>{t('Lector de cómics')}
        <select class="field" bind:value={form.comicsServer}><option value="">{t('Ninguno')}</option><option value="kavita">{t('Kavita')}</option><option value="komga">{t('Komga')}</option></select>
      </label>
      {#if form.comicsServer}
        <label>{t('Dirección de {0}', [form.comicsServer])} <input class="field" bind:value={form.comicsUrl} /></label>
        <label>{t('Clave {0}', [form.comicsKeySet ? '(guardada)' : ''])} <input class="field" type="password" autocomplete="off" bind:value={keys.comicsKey} /></label>
      {/if}
      <div class="span row">
        <button class="btn primary">{t('Guardar')}</button>
        <button type="button" class="btn tonal" disabled={testing || !form.jellyfinKeySet} onclick={test}>{testing ? 'Probando...' : t('Probar Jellyfin desde el VPS')}</button>
      </div>
    </form>
  {/if}
</section>

<section class="card">
  <h3 class="sub">{t('Cuentas de Jellyfin')}</h3>
  <p class="small muted">{t('Empareja cada cuenta con su usuario de Jellyfin: lo visto y por dónde vas se sincroniza en los dos sentidos (lo de Jellyfin llega cada 15 minutos).')}</p>
  {#if pairs === null}
    <div class="spinner"></div>
  {:else if !pairs.online}
    <p class="small muted">{t('Tu Jellyfin no responde ahora mismo (o falta configurarlo arriba).')}</p>
  {:else}
    <ul class="follows">
      {#each pairs.users as u (u.id)}
        <li>
          <span class="who">{u.name}</span>
          <select class="field pair" value={u.jellyfinUser || ''} onchange={(e) => pair(u, e.currentTarget.value)} aria-label={t('Usuario de Jellyfin de {0}', [u.name])}>
            <option value="">{t('Sin vincular')}</option>
            {#each pairs.jellyfin as j (j.id)}<option value={j.id}>{j.name}</option>{/each}
          </select>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<section class="card">
  <h3 class="sub">{t('Descarga automática')}</h3>
  <p class="small muted">{t('Obras seguidas: lo nuevo va solo al Jellyfin o a la carpeta de cómics (cada 3 horas). Se sigue una obra desde su ficha › Opciones.')}</p>
  {#if follows === null}
    <div class="spinner"></div>
  {:else if !follows.length}
    <p class="small muted">{t('Ninguna todavía.')}</p>
  {:else}
    <ul class="follows">
      {#each follows as f (f.id)}
        <li>
          <a href={`/obra/${encodeURIComponent(f.sourceId)}/${encodeURIComponent(f.mediaId)}`}>{f.title}</a>
          <span class="small muted">{t('desde el {0}{1} · hasta {2} cada vez', [f.lastNumber, f.rules.preferredServer ? ` · ${f.rules.preferredServer}` : '', f.rules.maxPerRun])}</span>
          <button class="btn tonal small-btn" onclick={() => unfollow(f)}>{t('Dejar de seguir')}</button>
        </li>
      {/each}
    </ul>
    <button class="btn tonal" onclick={checkNow}>{t('Mirar ahora')}</button>
  {/if}
</section>

<style>
  .grid-form { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
  .grid-form label { display: flex; flex-direction: column; gap: 4px; font-size: var(--fs-small); color: var(--c-text-dim); }
  .span { grid-column: 1 / -1; }
  .row { display: flex; gap: 8px; flex-wrap: wrap; }
  .follows { list-style: none; margin: 8px 0 12px; padding: 0; display: flex; flex-direction: column; gap: 8px; }
  .follows li { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; }
  .follows a { font-weight: 600; color: var(--c-text); }
  .small-btn { margin-left: auto; min-height: 32px; padding: 0 12px; }
  .who { font-weight: 600; min-width: 120px; }
  .pair { max-width: 260px; }
</style>
