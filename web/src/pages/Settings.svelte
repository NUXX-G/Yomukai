<script>
  import { t, i18n, LANGS, setLang } from '../lib/i18n.svelte.js';
  import { isIOS, isIPhone, isStandalone } from '../lib/platform.js';
  import { installGuide } from '../lib/fullscreen.svelte.js';
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { session, settings, sources, saveSettings, notify, resetUserState, loadUserData, features } from '../lib/store.svelte.js';
  import Icon from '../components/Icon.svelte';
  import Sheet from '../components/Sheet.svelte';
  import ImportPanel from '../components/ImportPanel.svelte';

  const COMMON_GENRES = ['BL', 'GL', 'Ecchi', 'Gore', 'Harem', 'Terror', 'Adulto', 'Violencia sexual', 'Romance', 'Isekai', 'Mecha', 'Deportes'];

  let users = $state(null);
  let guests = $state(0);
  let newName = $state('');
  let newPassword = $state('');
  let created = $state(null);
  let customGenre = $state('');
  let importing = $state(false);
  let fileInput;

  const hoursLeft = $derived(session.user?.expiresAt ? Math.max(0, Math.ceil((session.user.expiresAt - Date.now()) / 3_600_000)) : 0);
  const enabledIds = $derived(new Set(sources.list.filter((s) => s.enabled).map((s) => s.id)));

  onMount(() => { if (session.user?.admin) loadUsers(); });

  async function loadUsers() {
    const r = await api.adminUsers().catch((e) => { notify(e.message); return null; });
    if (r) { users = r.users; guests = r.guests; }
  }

  function toggleSource(id) {
    const next = new Set(enabledIds);
    next.has(id) ? next.delete(id) : next.add(id);
    settings.enabledSources = [...next];
    // Sources added to the app later start switched on: the server only keeps off what was seen and turned off.
    settings.knownSources = sources.list.map((s) => s.id);
    sources.list = sources.list.map((s) => ({ ...s, enabled: next.has(s.id) }));
    saveSettings({ reloadSources: true });
  }

  function toggleGenre(g) {
    const list = settings.exclusions.global;
    settings.exclusions.global = list.includes(g) ? list.filter((x) => x !== g) : [...list, g];
    saveSettings();
  }

  // Genres hidden in one source only (the app has the same setting).
  let perSourceFor = $state(null);
  let perSourceOpen = $state(false);
  let perSourceGenre = $state('');
  const perSourceList = $derived(perSourceFor ? settings.exclusions.perSource?.[perSourceFor.id] || [] : []);
  function togglePerSource(g) {
    const id = perSourceFor.id;
    const all = { ...(settings.exclusions.perSource || {}) };
    const list = all[id] || [];
    all[id] = list.includes(g) ? list.filter((x) => x !== g) : [...list, g];
    if (!all[id].length) delete all[id];
    settings.exclusions.perSource = all;
    saveSettings();
  }
  function addPerSource(e) {
    e.preventDefault();
    const g = perSourceGenre.trim();
    if (g && !perSourceList.includes(g)) togglePerSource(g);
    perSourceGenre = '';
  }

  function addGenre(e) {
    e.preventDefault();
    const g = customGenre.trim();
    if (g && !settings.exclusions.global.includes(g)) toggleGenre(g);
    customGenre = '';
  }

  /** sourceId -> { state: 'run' | 'ok' | 'fail', ms, count, error } */
  let checks = $state({});
  let checking = $state(false);
  async function checkSources() {
    checking = true;
    checks = Object.fromEntries(sources.list.map((s) => [s.id, { state: 'run' }]));
    await Promise.all(sources.list.map(async (s) => {
      const t = performance.now();
      try {
        const items = await api.latest(s.id);
        checks[s.id] = { state: items.length ? 'ok' : 'fail', ms: Math.round(performance.now() - t), count: items.length, error: items.length ? '' : t('Respondió sin obras') };
      } catch (e) {
        checks[s.id] = { state: 'fail', ms: Math.round(performance.now() - t), error: e.message };
      }
    }));
    checking = false;
  }

  async function logout() {
    await api.logout().catch(() => {});
    resetUserState();
  }

  async function importFile(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    importing = true;
    try {
      const r = await api.importData(await file.text(), 'merge');
      await loadUserData();
      notify(t('Importado: {0} obras, {1} capítulos y {2} entradas de historial', [r.library, r.progress, r.history]));
    } catch (err) {
      notify(err.message);
    } finally {
      importing = false;
      e.target.value = '';
    }
  }

  async function createUser(e) {
    e.preventDefault();
    try {
      created = await api.adminCreate(newName.trim(), newPassword || undefined, false);
      newName = ''; newPassword = '';
      loadUsers();
    } catch (err) { notify(err.message); }
  }

  async function reset(u) {
    if (!confirm(t('¿Nueva contraseña para {0}? Se cerrarán sus sesiones.', [u.name]))) return;
    const r = await api.adminReset(u.id).catch((e) => notify(e.message));
    if (r) created = { name: u.name, password: r.password };
  }

  async function reactivate(u) {
    await api.adminReactivate(u.id).catch((e) => notify(e.message));
    notify(t('Cuenta {0} reactivada', [u.guest ? t('de invitado') : u.name]));
    loadUsers();
  }

  async function remove(u) {
    if (!confirm(t('¿Borrar la cuenta {0} y todos sus datos?', [u.name]))) return;
    await api.adminDelete(u.id).catch((e) => notify(e.message));
    loadUsers();
  }
</script>

<div class="page narrow">
  <header class="topbar"><h1>{t('Ajustes')}</h1></header>

  <section class="card">
    <div class="row-flex">
      <Icon name="person" />
      <div class="grow">
        <div class="name">{session.user.guest ? t('Invitado') : session.user.name}{#if session.user.admin} <span class="tag">{t('admin')}</span>{/if}</div>
        {#if session.user.expiresAt}<div class="small muted">{t('Eres invitado: todo se borra {0}. Para guardar tu progreso, pide una cuenta.', [hoursLeft > 24 ? t('en {0} días', [Math.ceil(hoursLeft / 24)]) : hoursLeft <= 1 ? t('en menos de una hora') : t('en {0} horas', [hoursLeft])])}</div>{/if}
      </div>
      <button class="btn text" onclick={logout}><Icon name="logout" size={18} /> {t('Salir')}</button>
    </div>
  </section>

  <h2 class="h">{t('Idioma')} · Language</h2>
  <section class="card">
    <div class="small muted">{t('El idioma de la interfaz. Puedes leer y ver contenido en cualquier idioma igual.')}</div>
    <div class="wrap" style="margin-top:8px">
      {#each LANGS as l (l.key)}<button class="chip" class:on={i18n.lang === l.key} lang={l.key} onclick={() => setLang(l.key)}>{l.label}</button>{/each}
    </div>
  </section>

  {#if !session.user.guest}
  <h2 class="h">{t('Tus datos')}</h2>
  <section class="card">
    <p class="small muted" style="margin-top:0">{t('Una copia con tu biblioteca, lo que llevas leído, el historial y estos ajustes. Puedes importarla en otra cuenta o en otro servidor de Yomukai.')}</p>
    <div class="actions">
      <a class="btn tonal" href={api.exportUrl} download><Icon name="download" size={18} /> {t('Exportar')}</a>
      <button class="btn outline" onclick={() => fileInput.click()} disabled={importing}><Icon name="upload" size={18} /> {importing ? t('Importando...') : t('Importar')}</button>
      <input type="file" accept="application/json,.json" hidden bind:this={fileInput} onchange={importFile} />
    </div>
  </section>
  <ImportPanel />
  {/if}

  <h2 class="h">{t('Fuentes')}</h2>
  <section class="card list">
    {#each sources.list as s (s.id)}
      <label class="toggle">
        <span class="grow">
          {s.name} <span class="tag">{s.language}</span>{#if s.experimental} <span class="small muted">{t('experimental')}</span>{/if}
          {#if s.degraded}<span class="small error-text block-note">{t('Caída ahora mismo, oculta hasta que vuelva: {0}', [s.degraded])}</span>{/if}
          {#if checks[s.id]}
            {@const c = checks[s.id]}
            <span class="small check {c.state}">
              {#if c.state === 'run'}{t('Probando...')}{:else if c.state === 'ok'}{t('✓ Funciona · {0} obras · {1} s', [c.count, (c.ms / 1000).toFixed(1)])}{:else}✗ {c.error}{/if}
            </span>
          {/if}
        </span>
        <button class="icon-btn" onclick={(e) => { e.preventDefault(); perSourceFor = s; perSourceOpen = true; }} aria-label={t('Géneros ocultos en {0}', [s.name])} title={t('Géneros ocultos en esta fuente')}>
          <Icon name="tune" size={20} />{#if settings.exclusions.perSource?.[s.id]?.length}<span class="count">{settings.exclusions.perSource[s.id].length}</span>{/if}
        </button>
        <input type="checkbox" checked={enabledIds.has(s.id)} onchange={() => toggleSource(s.id)} />
      </label>
    {/each}
    <div class="diag">
      <button class="btn outline" onclick={checkSources} disabled={checking}>{checking ? t('Probando fuentes...') : t('Comprobar todas las fuentes')}</button>
    </div>
  </section>

  <h2 class="h">{t('Géneros excluidos')}</h2>
  <section class="card">
    <p class="small muted" style="margin-top:0">{t('Las obras con estos géneros no aparecen en listas, búsquedas ni recomendaciones.')}</p>
    <div class="wrap">
      {#each [...new Set([...COMMON_GENRES, ...settings.exclusions.global])] as g}
        <button class="chip" class:on={settings.exclusions.global.includes(g)} onclick={() => toggleGenre(g)}>{g}</button>
      {/each}
    </div>
    <form class="add" onsubmit={addGenre}><input class="field" placeholder={t('Otro género')} bind:value={customGenre} /><button class="btn tonal">{t('Añadir')}</button></form>
  </section>

  {#if !session.user.guest && !isIOS}
    <h2 class="h">{t('App de Android')}</h2>
    <section class="card">
      <p class="small muted" style="margin-top:0">
        {t('La app puede guardar lo mismo que ves aquí: biblioteca, categorías, progreso, historial, marcadores, fuentes y géneros excluidos. En la app ve a')} <b>{t('Ajustes › Sincronizar con servidor')}</b> {t('y pon:')}
      </p>
      <div class="sync-box"><span class="small muted">{t('Servidor')}</span><code>{location.origin}</code></div>
      <div class="sync-box"><span class="small muted">{t('Usuario')}</span><code>{session.user.name}</code></div>
      <p class="small muted">{t('y tu contraseña de aquí.')} {#if location.protocol !== 'https:'}<b>{t('Ojo:')}</b> {t('la app solo se conecta a servidores con HTTPS (para que la contraseña no viaje sin cifrar). Pon el servidor detrás de nginx o Caddy con un certificado; el README explica cómo.')}{/if}</p>
    </section>
  {/if}

  {#if isIOS && !isStandalone}
    <h2 class="h">{isIPhone ? 'iPhone' : 'iPad'}</h2>
    <section class="card">
      <p class="small muted" style="margin-top:0">{t('Añade Yomukai a tu pantalla de inicio: se abre como una app, a pantalla completa y sin las barras de Safari.')}</p>
      <button class="btn tonal" onclick={() => (installGuide.open = true)}><Icon name="share" size={18} /> {t('Cómo instalarla')}</button>
    </section>
  {/if}

  {#if features.loaded && features.flags.worker_jobs && !session.user.guest}
    <h2 class="h">{t('Servidor de casa')}</h2>
    <section class="card row-flex">
      <span class="home-dot" class:on={features.worker.online}></span>
      <span class="grow small">
        {#if features.worker.online}
          {t('Encendido{0}: voces de calidad, OCR y subtítulos disponibles.', [features.worker.gpu ? t(' (con GPU)') : ''])}
        {:else}
          {t('Apagado ahora mismo: lo que necesite el servidor de casa se hará cuando vuelva, o con tu dispositivo.')}
        {/if}
      </span>
    </section>
  {/if}

  <h2 class="h">{t('Lectura')}</h2>
  <section class="card">
    <div class="small muted">{t('Manga, manhwa y cómics')}</div>
    <div class="wrap" style="margin:8px 0 14px">
      <button class="chip" class:on={settings.reader.mode === 'vertical'} onclick={() => { settings.reader.mode = 'vertical'; saveSettings(); }}>{t('Vertical (webtoon)')}</button>
      <button class="chip" class:on={settings.reader.mode === 'paged'} onclick={() => { settings.reader.mode = 'paged'; saveSettings(); }}>{t('Páginas')}</button>
    </div>
    <label class="toggle"><span class="grow">{t('Pasar solo al siguiente capítulo')}</span><input type="checkbox" bind:checked={settings.reader.infinite} onchange={() => saveSettings()} /></label>
    <label class="toggle"><span class="grow">{t('Siguiente episodio automático')}</span><input type="checkbox" bind:checked={settings.player.autoNext} onchange={() => saveSettings()} /></label>
    <label class="toggle"><span class="grow">{t('Saltar opening y ending solos (cuando se conocen sus tiempos)')}</span><input type="checkbox" bind:checked={settings.player.autoSkip} onchange={() => saveSettings()} /></label>
  </section>

  {#if !session.user.guest}
    <h2 class="h">{t('Seguridad')}</h2>
    {#await import('../components/SecurityPanel.svelte') then m}<m.default />{/await}
  {/if}

  {#if !session.user.guest && features.flags.learning !== false}
    <h2 class="h">{t('Aprender idiomas')}</h2>
    <section class="card">
      <label class="toggle"><span class="grow">{t('Modo aprendizaje')}<br /><span class="small muted">{t('Apagado no cambia nada. Encendido, una pulsación larga (o clic derecho) en una palabra de una novela la traduce y la guarda para repasar.')}</span></span>
        <input type="checkbox" bind:checked={settings.learning.enabled} onchange={() => saveSettings()} /></label>
      {#if settings.learning.enabled}
        <label class="toggle"><span class="grow">{t('Traducir a')}</span>
          <select class="field" style="width:auto" bind:value={settings.learning.target} onchange={() => saveSettings()}>
            <option value="es">{t('Español')}</option><option value="en">{t('Inglés')}</option><option value="fr">{t('Francés')}</option><option value="de">{t('Alemán')}</option><option value="it">{t('Italiano')}</option><option value="pt">{t('Portugués')}</option>
          </select></label>
        <a class="btn tonal" href="/repaso">{t('Ir al repaso')}</a>
      {/if}
    </section>
  {/if}

  {#if !session.user.guest && features.flags.themes !== false}
    <h2 class="h">{t('Temas')}</h2>
    {#await import('../components/ThemesPanel.svelte') then m}<m.default />{/await}
  {/if}

  {#if !session.user.guest}
    <h2 class="h">{t('Avisos en el móvil')}</h2>
    {#await import('../components/PushPanel.svelte') then m}<m.default />{/await}
  {/if}

  {#if session.user.admin}
    <h2 class="h">{t('Administración')}</h2>
    <section class="card">
      <p class="small muted" style="margin-top:0">{t('Cuentas para tus amigos: la contraseña se genera sola si no pones una. Invitados activos ahora: {0}.', [guests])}</p>
      <form class="add" onsubmit={createUser}>
        <input class="field" placeholder={t('Usuario')} bind:value={newName} autocapitalize="none" required />
        <input class="field" placeholder={t('Contraseña (opcional, 8+)')} bind:value={newPassword} />
        <button class="btn primary" disabled={!newName.trim()}><Icon name="add" size={18} /> {t('Crear')}</button>
      </form>
      {#if created}
        <div class="created">
          <div>{t('Cuenta')} <b>{created.name}</b> {t('· contraseña')} <code>{created.password}</code></div>
          <div class="small muted">{t('Pásasela; solo se muestra ahora.')}</div>
        </div>
      {/if}
      {#each users || [] as u (u.id)}
        <div class="list-item">
          <span class="grow">
            {u.guest ? 'Invitado' : u.name}{#if u.admin} <span class="tag">{t('admin')}</span>{/if}
            {#if u.suspended}<span class="small error-text susp">{t('Suspendida: {0}', [u.suspendedReason || 'uso excesivo'])}</span>{/if}
          </span>
          {#if u.suspended}<button class="btn tonal" onclick={() => reactivate(u)}>{t('Reactivar')}</button>{/if}
          <button class="icon-btn" onclick={() => reset(u)} aria-label={t('Nueva contraseña')}><Icon name="key" size={20} /></button>
          {#if u.name !== session.user.name}<button class="icon-btn" onclick={() => remove(u)} aria-label={t('Borrar cuenta')}><Icon name="delete" size={20} /></button>{/if}
        </div>
      {/each}
    </section>
    {#await import('../components/AdminPanel.svelte') then m}<m.default />{/await}
  {/if}
</div>

<Sheet bind:open={perSourceOpen} title={perSourceFor ? t('Géneros ocultos en {0}', [perSourceFor.name]) : ''}>
  <p class="small muted" style="margin-top:0">{t('Solo para esta fuente; los de «Géneros excluidos» se ocultan en todas.')}</p>
  <div class="wrap">
    {#each [...new Set([...COMMON_GENRES, ...perSourceList])] as g}
      <button class="chip" class:on={perSourceList.includes(g)} onclick={() => togglePerSource(g)}>{g}</button>
    {/each}
  </div>
  <form class="add" onsubmit={addPerSource}><input class="field" placeholder={t('Otro género')} bind:value={perSourceGenre} /><button class="btn tonal">{t('Añadir')}</button></form>
</Sheet>

<style>
  .narrow { max-width: 720px; }
  .h { font-size: 0.85rem; color: var(--primary); margin: 24px 4px 8px; }
  .row-flex { display: flex; align-items: center; gap: 12px; }
  .grow { flex: 1; min-width: 0; }
  .name { font-weight: 600; }
  .actions { display: flex; gap: 12px; flex-wrap: wrap; }
  .list { padding: 4px 16px; }
  .toggle { display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--outline-variant); }
  .toggle:last-child { border-bottom: 0; }
  .toggle .icon-btn { position: relative; color: var(--text-dim); }
  .toggle .count { position: absolute; top: 4px; right: 2px; min-width: 16px; height: 16px; border-radius: 8px; background: var(--primary); color: var(--on-primary); font-size: 0.65rem; font-weight: 700; display: grid; place-items: center; }
  .wrap { display: flex; flex-wrap: wrap; gap: 8px; }
  .check { display: block; margin-top: 2px; color: var(--text-dim); }
  .check.ok { color: #6fcf8a; }
  .check.fail { color: var(--error, #ff8a80); }
  .diag { padding: 12px 0; }
  .susp { display: block; }
  .block-note { display: block; }
  .home-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--text-dim); flex: none; }
  .home-dot.on { background: #6fcf8a; }
  .sync-box { display: flex; align-items: center; gap: 10px; padding: 8px 12px; margin: 6px 0; border-radius: 10px; background: var(--surface-high); }
  .sync-box code { flex: 1; min-width: 0; overflow-wrap: anywhere; user-select: all; }
  .add { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
  .add .field { flex: 1; min-width: 140px; }
  .created { margin: 12px 0; padding: 12px; border-radius: 10px; background: var(--primary-container); color: var(--on-primary-container); }
  code { font-size: 1rem; letter-spacing: 0.04em; }
</style>
