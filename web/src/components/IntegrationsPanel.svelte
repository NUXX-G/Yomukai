<script>
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

  async function unfollow(f) {
    try { await api.unfollow(f.id); loadFollows(); } catch (err) { notify(err.message); }
  }
  async function checkNow() {
    try { const r = await api.checkFollows(); notify(r.queued ? `${r.queued} nuevos en cola` : 'Nada nuevo por ahora'); loadFollows(); } catch (err) { notify(err.message); }
  }

  async function save(e) {
    e.preventDefault();
    try {
      form = await api.saveIntegrations({ ...form, ...keys });
      keys = { jellyfinKey: '', comicsKey: '' };
      notify('Guardado');
    } catch (err) { notify(err.message); }
  }

  async function test() {
    testing = true;
    try {
      const r = await api.testJellyfin();
      notify(r.ok ? `Conectado con ${r.server} (Jellyfin ${r.version})` : r.error);
    } catch (err) { notify(err.message); } finally { testing = false; }
  }
</script>

<section class="card">
  <h3 class="sub">Integraciones (Jellyfin y cómics)</h3>
  {#if !form}
    <div class="spinner"></div>
  {:else}
    <form class="grid-form" onsubmit={save}>
      <p class="small muted span">El VPS habla con Jellyfin por Tailscale; las descargas y el NAS los toca el servidor de casa. Las claves se guardan cifradas y no vuelven a mostrarse.</p>
      <label>Dirección de Jellyfin <input class="field" placeholder="http://jellyfin.tu-tailnet.ts.net:8096" bind:value={form.jellyfinUrl} /></label>
      <label>Clave de API {form.jellyfinKeySet ? '(guardada)' : ''} <input class="field" type="password" autocomplete="off" placeholder={form.jellyfinKeySet ? 'Déjalo vacío para mantenerla' : 'Panel de Jellyfin › Claves de API'} bind:value={keys.jellyfinKey} /></label>
      <label>Carpeta de anime (en el worker) <input class="field" placeholder="/media/anime" bind:value={form.animePath} /></label>
      <label>Carpeta de series <input class="field" placeholder="/media/series" bind:value={form.seriesPath} /></label>
      <label>Carpeta de películas <input class="field" placeholder="/media/peliculas" bind:value={form.moviesPath} /></label>
      <label>Carpeta de cómics (Kavita/Komga) <input class="field" placeholder="/media/comics" bind:value={form.comicsPath} /></label>
      <label>Lector de cómics
        <select class="field" bind:value={form.comicsServer}><option value="">Ninguno</option><option value="kavita">Kavita</option><option value="komga">Komga</option></select>
      </label>
      {#if form.comicsServer}
        <label>Dirección de {form.comicsServer} <input class="field" bind:value={form.comicsUrl} /></label>
        <label>Clave {form.comicsKeySet ? '(guardada)' : ''} <input class="field" type="password" autocomplete="off" bind:value={keys.comicsKey} /></label>
      {/if}
      <div class="span row">
        <button class="btn primary">Guardar</button>
        <button type="button" class="btn tonal" disabled={testing || !form.jellyfinKeySet} onclick={test}>{testing ? 'Probando...' : 'Probar Jellyfin desde el VPS'}</button>
      </div>
    </form>
  {/if}
</section>

<section class="card">
  <h3 class="sub">Descarga automática</h3>
  <p class="small muted">Obras seguidas: lo nuevo va solo al Jellyfin o a la carpeta de cómics (cada 3 horas). Se sigue una obra desde su ficha › Opciones.</p>
  {#if follows === null}
    <div class="spinner"></div>
  {:else if !follows.length}
    <p class="small muted">Ninguna todavía.</p>
  {:else}
    <ul class="follows">
      {#each follows as f (f.id)}
        <li>
          <a href={`/obra/${encodeURIComponent(f.sourceId)}/${encodeURIComponent(f.mediaId)}`}>{f.title}</a>
          <span class="small muted">desde el {f.lastNumber}{f.rules.preferredServer ? ` · ${f.rules.preferredServer}` : ''} · hasta {f.rules.maxPerRun} cada vez</span>
          <button class="btn tonal small-btn" onclick={() => unfollow(f)}>Dejar de seguir</button>
        </li>
      {/each}
    </ul>
    <button class="btn tonal" onclick={checkNow}>Mirar ahora</button>
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
</style>
