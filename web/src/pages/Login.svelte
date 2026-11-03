<script>
  import { api } from '../lib/api.js';
  import { session, loadUserData } from '../lib/store.svelte.js';

  let name = $state('');
  let password = $state('');
  let error = $state('');
  let busy = $state(false);

  async function login(e) {
    e.preventDefault();
    busy = true;
    error = '';
    try {
      session.user = await api.login(name, password);
      await loadUserData();
    } catch (err) {
      error = err.message;
    } finally {
      busy = false;
    }
  }

  async function guest() {
    busy = true;
    error = '';
    try {
      session.user = await api.guest();
      await loadUserData();
    } catch (err) {
      error = err.message;
    } finally {
      busy = false;
    }
  }
</script>

<div class="wrap">
  <div class="logo" aria-hidden="true"><span></span></div>
  <h1 class="brand">Yomukai</h1>
  <p class="muted">Manga, manhwa, novelas, cómics y anime de tus fuentes, en un solo sitio.</p>

  <form class="card" onsubmit={login}>
    <label for="u" class="small muted">Usuario</label>
    <input id="u" class="field" autocomplete="username" autocapitalize="none" bind:value={name} required />
    <label for="p" class="small muted">Contraseña</label>
    <input id="p" class="field" type="password" autocomplete="current-password" bind:value={password} required />
    {#if error}<p class="error-text small">{error}</p>{/if}
    <button class="btn primary block" disabled={busy || !name || !password}>Entrar</button>
    <p class="small muted">La sesión se queda guardada en este navegador: solo tendrás que entrar una vez.</p>
  </form>

  {#if session.guestsEnabled}
    <div class="guest">
      <button class="btn outline block" onclick={guest} disabled={busy}>Probar sin cuenta</button>
      <p class="small muted">
        Como invitado puedes hacer de todo. Tus datos se borran a los {session.guestDays} días;
        antes puedes exportarlos desde Ajustes y llevártelos a una cuenta.
      </p>
    </div>
  {/if}
</div>

<style>
  .wrap { min-height: 100vh; max-width: 400px; margin: 0 auto; padding: calc(var(--safe-top) + 48px) 20px 32px; display: flex; flex-direction: column; gap: 12px; text-align: center; }
  .logo { width: 72px; height: 72px; margin: 0 auto; border-radius: 50%; border: 11px solid var(--primary); display: grid; place-items: center; }
  .logo span { width: 20px; height: 20px; border-radius: 50%; background: var(--primary); }
  h1 { margin: 4px 0 0; font-size: 2rem; }
  form { display: flex; flex-direction: column; gap: 8px; text-align: left; margin-top: 12px; }
  form .btn { margin-top: 8px; }
  .guest { margin-top: 8px; }
  .guest p { margin-top: 8px; }
</style>
