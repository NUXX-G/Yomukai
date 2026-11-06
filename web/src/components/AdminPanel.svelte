<script>
  // Administración del servidor: estado (memoria, tráfico), workers del servidor de casa y funcionalidades.
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { notify, loadFeatures } from '../lib/store.svelte.js';
  import Icon from './Icon.svelte';

  let status = $state(null);
  let workers = $state(null);
  let flags = $state([]);
  let newWorker = $state('');
  let invites = $state([]);
  let audit = $state([]);
  let inviteDays = $state(7);
  let inviteNote = $state('');
  let inviteLink = $state('');
  let showAudit = $state(false);
  let health = $state([]);
  let testing = $state('');
  const statusText = { OK: 'bien', WARNING: 'con avisos', DEGRADED: 'caída', UNKNOWN: 'sin probar' };
  let createdToken = $state(null);

  const mb = (b) => (b == null ? '—' : `${Math.round(b / 1048576)} MB`);
  const gb = (b) => `${(b / 1073741824).toFixed(1)} GB`;
  const ago = (t) => {
    if (!t) return 'nunca';
    const s = Math.round((Date.now() - t) / 1000);
    if (s < 90) return 'ahora';
    if (s < 3600) return `hace ${Math.round(s / 60)} min`;
    if (s < 86400) return `hace ${Math.round(s / 3600)} h`;
    return `hace ${Math.round(s / 86400)} d`;
  };
  const engineNames = {
    tts: 'voces', ocr: 'OCR', translate: 'traducción', whisper: 'subtítulos', audio_fingerprint: 'huellas de audio',
    jellyfin_download: 'Jellyfin', cbz_export: 'CBZ', echo: 'prueba'
  };
  const queued = $derived(workers ? Object.values(workers.queue.queued).reduce((a, b) => a + b, 0) : 0);

  async function load() {
    const [st, w, f] = await Promise.all([
      api.adminStatus().catch(() => null),
      api.adminWorkers().catch((e) => { notify(e.message); return null; }),
      api.adminFeatures().catch(() => [])
    ]);
    status = st;
    workers = w;
    flags = f;
    invites = await api.adminInvites().catch(() => []);
    health = await api.adminCanary().catch(() => []);
    if (showAudit) audit = await api.adminAudit().catch(() => []);
  }
  async function invite(e) {
    e.preventDefault();
    try {
      const r = await api.adminCreateInvite(Number(inviteDays), inviteNote.trim());
      inviteLink = location.origin + r.path;
      inviteNote = '';
      try { await navigator.clipboard.writeText(inviteLink); notify('Enlace copiado'); } catch {}
      load();
    } catch (err) { notify(err.message); }
  }
  async function runCanary(id) {
    testing = id;
    try {
      const r = await api.adminRunCanary(id);
      notify(`${id}: ${statusText[r.status] || r.status}`);
      health = await api.adminCanary().catch(() => health);
    } catch (err) { notify(err.message); } finally { testing = ''; }
  }
  async function toggleAudit() {
    showAudit = !showAudit;
    if (showAudit) audit = await api.adminAudit().catch(() => []);
  }
  const inviteState = (i) => (i.usedAt ? `usada por ${i.usedBy || '—'}` : i.revoked ? 'anulada' : i.expiresAt < Date.now() ? 'caducada' : `caduca el ${new Date(i.expiresAt).toLocaleDateString('es')}`);
  onMount(load);

  async function create(e) {
    e.preventDefault();
    try {
      createdToken = await api.adminCreateWorker(newWorker.trim());
      newWorker = '';
      load();
    } catch (err) { notify(err.message); }
  }
  async function act(fn, msg) {
    try {
      await fn();
      if (msg) notify(msg);
      load();
      loadFeatures();
    } catch (err) { notify(err.message); }
  }
  function revoke(w) {
    if (confirm(`¿Revocar el worker «${w.name}»? Dejará de poder pedir trabajos.`)) act(() => api.adminRevokeWorker(w.id), 'Worker revocado');
  }
  async function test() {
    try {
      const { id } = await api.adminTestWorker();
      notify('Trabajo de prueba en la cola…');
      for (let i = 0; i < 30; i++) {
        await new Promise((r) => setTimeout(r, 2000));
        const j = await api.job(id);
        if (j.status === 'done') return notify('El worker respondió: todo bien');
        if (j.status === 'failed' || j.status === 'expired') return notify(`La prueba falló: ${j.error || j.status}`);
      }
      notify('Ningún worker ha cogido la prueba todavía');
    } catch (err) { notify(err.message); }
  }
</script>

<section class="card">
  <h3 class="sub">Servidor</h3>
  {#if status}
    <div class="stats">
      <div><span class="small muted">Memoria (heap)</span><b>{mb(status.memory.heapUsed)} / {mb(status.memory.heapMax)}</b></div>
      <div><span class="small muted">Memoria del proceso</span><b>{mb(status.memory.rss)}</b></div>
      <div>
        <span class="small muted">Vídeo por el servidor ({status.bandwidth.month})</span>
        <b class:warn={status.bandwidth.level !== 'OK'}>{gb(status.bandwidth.usedBytes)}{status.bandwidth.capBytes > 0 ? ` / ${gb(status.bandwidth.capBytes)}` : ''}</b>
      </div>
    </div>
  {:else}
    <p class="small muted">Cargando…</p>
  {/if}
</section>

<section class="card">
  <h3 class="sub">Workers (servidor de casa)</h3>
  <p class="small muted" style="margin-top:0">
    Hacen lo pesado: voces, OCR, subtítulos, descargas a Jellyfin. Se conectan ellos al servidor; crea uno y usa su token al
    instalarlo (<code>install-worker.sh</code>).
  </p>
  {#if workers}
    <p class="small">
      En cola: {queued} · en curso: {workers.queue.running}
      {#if workers.queue.lastDone} · último terminado: {engineNames[workers.queue.lastDone.type] || workers.queue.lastDone.type}, {ago(workers.queue.lastDone.at)}{/if}
      · resultados guardados: {mb(workers.results.bytes)}
    </p>
    {#each workers.workers as w (w.id)}
      <div class="list-item worker">
        <span class="dot" class:on={w.online && !w.paused} title={w.online ? 'Conectado' : 'Desconectado'}></span>
        <span class="grow">
          <b>{w.name}</b>
          {#if w.revoked}<span class="tag">revocado</span>{:else if w.paused}<span class="tag">en pausa</span>{/if}
          <span class="small muted block">
            {w.online ? 'Conectado' : `Visto ${ago(w.lastSeen)}`}
            {#if w.capabilities.cpus} · {w.capabilities.cpus} núcleos, {Math.round((w.capabilities.ramMb || 0) / 1024)} GB{/if}
            {#if w.capabilities.gpu} · {w.capabilities.gpu}{/if}
            {#if w.capabilities.busy} · ocupado{/if}
          </span>
          {#if w.capabilities.engines?.length}
            <span class="small muted block">{w.capabilities.engines.map((e) => engineNames[e] || e).join(', ')}</span>
          {/if}
        </span>
        {#if !w.revoked}
          <label class="small mini">
            <input type="checkbox" checked={w.canBackup} onchange={(e) => act(() => api.adminWorkerBackup(w.id, e.currentTarget.checked))} /> copias
          </label>
          <button class="icon-btn" onclick={() => act(() => api.adminPauseWorker(w.id, !w.paused))} aria-label={w.paused ? 'Reanudar' : 'Pausar'}>
            <Icon name={w.paused ? 'play' : 'pause'} size={20} />
          </button>
          <button class="icon-btn" onclick={() => revoke(w)} aria-label="Revocar"><Icon name="delete" size={20} /></button>
        {/if}
      </div>
    {:else}
      <p class="small muted">Todavía no hay workers.</p>
    {/each}
  {/if}
  <form class="add" onsubmit={create}>
    <input class="field" placeholder="Nombre (p. ej. casa, portátil)" bind:value={newWorker} required />
    <button class="btn primary" disabled={newWorker.trim().length < 2}><Icon name="add" size={18} /> Nuevo worker</button>
    <button class="btn tonal" type="button" onclick={test}>Probar</button>
  </form>
  {#if createdToken}
    <div class="created">
      <div>Token de <b>{createdToken.name}</b>:</div>
      <code class="token">{createdToken.token}</code>
      <div class="small">Cópialo ahora: no se vuelve a mostrar (el servidor solo guarda su huella).</div>
    </div>
  {/if}
  <p class="small muted">«Copias» deja a ese worker llevarse la copia nocturna de la base de datos (para guardarla en el NAS).</p>
</section>

<section class="card">
  <h3 class="sub">Salud de las fuentes</h3>
  <p class="small muted" style="margin-top:0">
    El canario prueba cada fuente de vez en cuando (cron en el servidor, ver <code>docs/VPS.md</code>). Las caídas se ocultan solas
    y vuelven cuando funcionan.
  </p>
  {#each health as h (h.id)}
    <div class="list-item">
      <span class="dot" class:on={h.status === 'OK'} class:warn-dot={h.status === 'WARNING'} class:bad={h.status === 'DEGRADED'}></span>
      <span class="grow">
        <span class="block">{h.name} <span class="small muted">· {statusText[h.status] || h.status}{h.checkedAt ? `, ${ago(h.checkedAt)}` : ''}</span></span>
        {#if h.status !== 'OK' && h.summary}<span class="small muted block">{h.summary}</span>{/if}
      </span>
      <button class="btn tonal small-btn" onclick={() => runCanary(h.id)} disabled={!!testing}>{testing === h.id ? 'Probando…' : 'Probar'}</button>
    </div>
  {/each}
</section>

<section class="card">
  <h3 class="sub">Invitaciones</h3>
  <p class="small muted" style="margin-top:0">Un enlace para que un amigo cree su cuenta: sirve una sola vez y caduca.</p>
  <form class="add" onsubmit={invite}>
    <input class="field" placeholder="Para quién (opcional)" bind:value={inviteNote} maxlength="100" />
    <select class="field days" bind:value={inviteDays} aria-label="Caducidad">
      <option value={1}>1 día</option><option value={3}>3 días</option><option value={7}>7 días</option><option value={30}>30 días</option>
    </select>
    <button class="btn primary"><Icon name="share" size={18} /> Crear enlace</button>
  </form>
  {#if inviteLink}<div class="created"><code class="token">{inviteLink}</code><div class="small">Pásaselo; solo se muestra ahora.</div></div>{/if}
  {#each invites.slice(0, 10) as i (i.id)}
    <div class="list-item">
      <span class="grow"><span class="block">{i.note || `Invitación ${i.id}`}</span><span class="small muted block">{inviteState(i)}</span></span>
      {#if !i.usedAt && !i.revoked && i.expiresAt > Date.now()}
        <button class="icon-btn" onclick={() => act(() => api.adminRevokeInvite(i.id), 'Invitación anulada')} aria-label="Anular"><Icon name="close" size={20} /></button>
      {/if}
    </div>
  {/each}
</section>

<section class="card">
  <h3 class="sub">Registro de actividad de administración</h3>
  <button class="btn tonal" onclick={toggleAudit}>{showAudit ? 'Ocultar' : 'Ver'}</button>
  {#if showAudit}
    {#each audit as a (a.id)}
      <div class="audit small"><span class="muted">{new Date(a.at).toLocaleString('es')}</span> <b>{a.actor}</b> {a.action} {a.detail}<span class="muted"> · {a.ip}</span></div>
    {:else}
      <p class="small muted">Sin actividad todavía.</p>
    {/each}
  {/if}
</section>

<section class="card">
  <h3 class="sub">Funcionalidades</h3>
  <p class="small muted" style="margin-top:0">Apaga cualquiera si da problemas: desaparece en la web y en la app.</p>
  {#each flags as f (f.name)}
    <label class="toggle">
      <span class="grow"><span class="block">{f.title}</span><span class="small muted block">{f.description}</span></span>
      <input type="checkbox" checked={f.enabled} onchange={(e) => act(() => api.adminSetFeature(f.name, e.currentTarget.checked))} />
    </label>
  {/each}
</section>

<style>
  .card { margin-top: 12px; }
  .sub { margin: 0 0 8px; font-size: 1rem; }
  .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
  .stats div { display: flex; flex-direction: column; gap: 2px; }
  .warn { color: var(--error, #ff8a80); }
  .block { display: block; }
  .grow { flex: 1; min-width: 0; }
  .worker { align-items: flex-start; gap: 10px; }
  .dot { width: 10px; height: 10px; border-radius: 50%; margin-top: 6px; background: var(--text-dim); flex: none; }
  .dot.on { background: #6fcf8a; }
  .dot.warn-dot { background: #e8c26a; }
  .dot.bad { background: var(--error, #ff8a80); }
  .small-btn { padding: 4px 12px; min-height: 0; }
  .mini { display: flex; align-items: center; gap: 4px; white-space: nowrap; }
  .add { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
  .add .field { flex: 1; min-width: 140px; }
  .created { margin: 12px 0; padding: 12px; border-radius: 10px; background: var(--primary-container); color: var(--on-primary-container); }
  .token { display: block; margin: 6px 0; overflow-wrap: anywhere; user-select: all; font-size: 0.9rem; }
  .toggle { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--outline-variant); }
  .toggle:last-child { border-bottom: 0; }
  .tag { margin-left: 6px; }
  .days { flex: 0 0 auto; min-width: 0; width: auto; }
  .audit { padding: 6px 0; border-bottom: 1px solid var(--outline-variant); overflow-wrap: anywhere; }
</style>
