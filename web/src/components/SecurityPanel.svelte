<script>
  // Seguridad de la cuenta: dónde tienes la sesión abierta y la verificación en dos pasos.
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { notify } from '../lib/store.svelte.js';
  import Icon from './Icon.svelte';

  let sessions = $state([]);
  let twoFactor = $state(null);
  let setup = $state(null);
  let code = $state('');
  let recovery = $state(null);
  let disabling = $state(false);
  let password = $state('');
  let busy = $state(false);

  const when = (t) => new Date(t).toLocaleString('es', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

  async function load() {
    [sessions, twoFactor] = await Promise.all([api.sessions().catch(() => []), api.twoFactorStatus().catch(() => null)]);
  }
  onMount(load);

  async function act(fn) {
    busy = true;
    try { await fn(); } catch (e) { notify(e.message); } finally { busy = false; }
  }
  const close = (s) => act(async () => { await api.closeSession(s.id); notify('Sesión cerrada'); await load(); });
  const closeOthers = () => act(async () => { const r = await api.closeOtherSessions(); notify(`${r.closed} sesión(es) cerrada(s)`); await load(); });
  let starting = $state(false);
  const start = (e) => {
    e.preventDefault();
    act(async () => { setup = await api.twoFactorSetup(password); code = ''; password = ''; starting = false; });
  };
  const enable = (e) => {
    e.preventDefault();
    act(async () => {
      const r = await api.twoFactorEnable(code.trim());
      recovery = r.recoveryCodes;
      setup = null;
      await load();
    });
  };
  const disable = (e) => {
    e.preventDefault();
    act(async () => {
      await api.twoFactorDisable(password, code.trim());
      disabling = false; password = ''; code = '';
      notify('Verificación en dos pasos desactivada');
      await load();
    });
  };
</script>

<section class="card">
  <h3 class="sub">Sesiones abiertas</h3>
  {#each sessions as s (s.id)}
    <div class="list-item">
      <span class="grow">
        <span class="block">{s.device}{#if s.current} <span class="tag">esta</span>{/if}</span>
        <span class="small muted block">{s.ip || 'IP desconocida'} · última vez {when(s.lastSeen)} · desde {when(s.createdAt)}</span>
      </span>
      {#if !s.current}<button class="icon-btn" onclick={() => close(s)} disabled={busy} aria-label="Cerrar esta sesión"><Icon name="logout" size={20} /></button>{/if}
    </div>
  {/each}
  {#if sessions.length > 1}<button class="btn tonal" onclick={closeOthers} disabled={busy}>Cerrar todas las demás</button>{/if}
</section>

<section class="card">
  <h3 class="sub">Verificación en dos pasos</h3>
  {#if recovery}
    <p class="small">Activada. Guarda estos códigos de recuperación en un sitio seguro: cada uno sirve una vez si pierdes el móvil.
      <strong>No se volverán a mostrar.</strong></p>
    <div class="codes">{#each recovery as c (c)}<code>{c}</code>{/each}</div>
    <button class="btn tonal" onclick={() => (recovery = null)}>Ya los he guardado</button>
  {:else if twoFactor?.enabled}
    <p class="small">Activada. Te quedan {twoFactor.recoveryLeft} códigos de recuperación.</p>
    {#if disabling}
      <form class="add" onsubmit={disable}>
        <input class="field" type="password" placeholder="Tu contraseña" autocomplete="current-password" bind:value={password} required />
        <input class="field" inputmode="numeric" placeholder="Código o código de recuperación" autocomplete="one-time-code" bind:value={code} required />
        <button class="btn primary" disabled={busy}>Desactivar</button>
      </form>
    {:else}
      <button class="btn tonal" onclick={() => (disabling = true)}>Desactivar</button>
    {/if}
  {:else if setup}
    <ol class="small steps">
      <li>Abre tu app de autenticación (Aegis, 2FAS, Google Authenticator…) y añade una cuenta con esta clave:
        <code class="secret">{setup.secret}</code>
        <a class="small" href={setup.uri}>o ábrela directamente en la app</a></li>
      <li>Escribe el código de 6 cifras que te muestra:</li>
    </ol>
    <form class="add" onsubmit={enable}>
      <input class="field" inputmode="numeric" autocomplete="one-time-code" placeholder="123456" bind:value={code} required />
      <button class="btn primary" disabled={busy || code.trim().length < 6}>Activar</button>
      <button class="btn" type="button" onclick={() => (setup = null)}>Cancelar</button>
    </form>
  {:else if twoFactor}
    <p class="small muted" style="margin-top:0">Además de la contraseña, se pedirá un código de tu móvil al entrar desde un sitio nuevo.</p>
    {#if starting}
      <form class="add" onsubmit={start}>
        <input class="field" type="password" placeholder="Tu contraseña" autocomplete="current-password" bind:value={password} required />
        <button class="btn primary" disabled={busy || !password}>Seguir</button>
      </form>
    {:else}
      <button class="btn tonal" onclick={() => (starting = true)} disabled={busy}>Activar</button>
    {/if}
  {/if}
</section>

<style>
  .card { margin-top: 12px; }
  .sub { margin: 0 0 8px; font-size: 1rem; }
  .block { display: block; }
  .grow { flex: 1; min-width: 0; }
  .tag { margin-left: 6px; }
  .add { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
  .add .field { flex: 1; min-width: 140px; }
  .codes { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 6px; margin: 8px 0 12px; }
  .codes code { padding: 6px 8px; border-radius: 8px; background: var(--surface-high); user-select: all; }
  .secret { display: block; margin: 6px 0; font-size: 1rem; letter-spacing: 0.08em; user-select: all; overflow-wrap: anywhere; }
  .steps { padding-left: 18px; }
</style>
