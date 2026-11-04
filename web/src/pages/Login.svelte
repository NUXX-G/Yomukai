<script>
  import { api } from '../lib/api.js';
  import { session, loadUserData } from '../lib/store.svelte.js';
  import Sheet from '../components/Sheet.svelte';

  /** Guests see what the trial mode means before anything loads. */
  let guestNotice = $state(false);

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
    guestNotice = false;
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
      <button class="btn outline block" onclick={() => (guestNotice = true)} disabled={busy}>Probar sin cuenta</button>
      <p class="small muted">
        Para echar un vistazo: puedes leer y ver lo que quieras, con algunos límites.
        Todo lo que hagas se borra {session.guestDays === 1 ? 'al cabo de un día' : `a los ${session.guestDays} días`}.
      </p>
    </div>
  {/if}
</div>

<Sheet bind:open={guestNotice} title="Modo invitado: solo para probar">
  <div class="notice">
    <p>Es una cuenta de prueba para ver si Yomukai te gusta, así que va <strong>muy limitada</strong>:</p>
    <ul>
      <li>Pocas peticiones por minuto. Si navegas muy deprisa, algunas páginas, capítulos o búsquedas pueden fallar o tardar: espera unos segundos y vuelve a intentarlo.</li>
      <li>Como mucho 3 capítulos por descarga y 2 vídeos a la vez.</li>
      <li>Sin importar, exportar ni sincronizar con la app.</li>
      <li>Todo lo que hagas se borra {session.guestDays === 1 ? 'al cabo de un día' : `a los ${session.guestDays} días`}.</li>
    </ul>
    <p class="small muted">Con una cuenta normal no hay nada de esto.</p>
  </div>
  <div class="notice-actions">
    <button class="btn text" onclick={() => (guestNotice = false)}>Cancelar</button>
    <button class="btn primary" onclick={guest} disabled={busy}>Entendido, entrar</button>
  </div>
</Sheet>

<style>
  .notice p { margin: 0 0 10px; line-height: 1.5; }
  .notice ul { margin: 0 0 12px; padding-left: 20px; display: flex; flex-direction: column; gap: 8px; line-height: 1.45; color: color-mix(in srgb, var(--text) 90%, transparent); }
  .notice strong { color: var(--primary); }
  .notice-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
  .wrap { min-height: 100vh; max-width: 400px; margin: 0 auto; padding: calc(var(--safe-top) + 48px) 20px 32px; display: flex; flex-direction: column; gap: 12px; text-align: center; }
  .logo { width: 72px; height: 72px; margin: 0 auto; border-radius: 50%; border: 11px solid var(--primary); display: grid; place-items: center; }
  .logo span { width: 20px; height: 20px; border-radius: 50%; background: var(--primary); }
  h1 { margin: 4px 0 0; font-size: 2rem; }
  form { display: flex; flex-direction: column; gap: 8px; text-align: left; margin-top: 12px; }
  form .btn { margin-top: 8px; }
  .guest { margin-top: 8px; }
  .guest p { margin-top: 8px; }
</style>
