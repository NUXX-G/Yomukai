<script>
  import { t } from '../lib/i18n.svelte.js';
  import { api } from '../lib/api.js';
  import { session, loadUserData } from '../lib/store.svelte.js';
  import Sheet from '../components/Sheet.svelte';

  /** Guests see what the trial mode means before anything loads. */
  let guestNotice = $state(false);

  let name = $state('');
  let password = $state('');
  let error = $state('');
  let busy = $state(false);
  /** After a right password with 2FA on: the challenge waiting for the code. */
  let challenge = $state('');
  let code = $state('');

  // Which form: sign-up from an invitation link, first-run setup, or the normal login.
  const inviteToken = location.pathname.startsWith('/invitacion/') ? decodeURIComponent(location.pathname.slice('/invitacion/'.length)) : '';
  const mode = inviteToken ? 'invite' : session.setupNeeded ? 'setup' : 'login';
  let setupToken = $state(new URLSearchParams(location.search).get('token') || '');
  let inviteValid = $state(null);
  if (inviteToken) api.inviteInfo(inviteToken).then(() => (inviteValid = true), () => (inviteValid = false));

  async function run(fn) {
    busy = true;
    error = '';
    try {
      await fn();
    } catch (err) {
      error = err.message;
    } finally {
      busy = false;
    }
  }

  async function enter(user) {
    session.user = user;
    session.setupNeeded = false;
    if (location.pathname !== '/') history.replaceState({}, '', '/');
    await loadUserData();
  }

  function login(e) {
    e.preventDefault();
    run(async () => {
      const r = await api.login(name, password);
      if (r.twoFactor) {
        challenge = r.challenge;
        code = '';
        return;
      }
      await enter(r);
    });
  }

  function sendCode(e) {
    e.preventDefault();
    run(async () => {
      const r = await api.twoFactor(challenge, code.trim());
      await enter(r.user);
    });
  }

  function register(e) {
    e.preventDefault();
    run(async () => enter((await api.redeemInvite(inviteToken, name.trim(), password)).user));
  }

  function configure(e) {
    e.preventDefault();
    run(async () => enter((await api.setup(setupToken.trim(), name.trim(), password)).user));
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
  <h1 class="brand">{t('Yomukai')}</h1>
  <p class="muted">{t('Manga, manhwa, novelas, cómics y anime de tus fuentes, en un solo sitio.')}</p>

  {#if mode === 'setup'}
    <form class="card" onsubmit={configure}>
      <h2 class="title">{t('Primera configuración')}</h2>
      <p class="small muted">{t('Crea la cuenta de administrador. El código de configuración sale en el registro del servidor (')}<code>{t('docker compose logs yomukai')}</code>{t(') y en')} <code>{t('data/setup-token')}</code>.</p>
      <label for="t" class="small muted">{t('Código de configuración')}</label>
      <input id="t" class="field" autocomplete="off" autocapitalize="none" bind:value={setupToken} required />
      <label for="u" class="small muted">{t('Tu usuario')}</label>
      <input id="u" class="field" autocomplete="username" autocapitalize="none" bind:value={name} required />
      <label for="p" class="small muted">{t('Contraseña (8 o más)')}</label>
      <input id="p" class="field" type="password" autocomplete="new-password" bind:value={password} minlength="8" required />
      {#if error}<p class="error-text small">{error}</p>{/if}
      <button class="btn primary block" disabled={busy || !setupToken || !name || password.length < 8}>{t('Crear administrador')}</button>
    </form>
  {:else if mode === 'invite'}
    <form class="card" onsubmit={register}>
      <h2 class="title">{t('Te han invitado')}</h2>
      {#if inviteValid === false}
        <p class="error-text small">{t('Esta invitación no existe, ya se usó o ha caducado. Pide otra.')}</p>
      {:else}
        <p class="small muted">{t('Elige tu usuario y contraseña. El enlace solo sirve una vez.')}</p>
        <label for="u" class="small muted">{t('Usuario')}</label>
        <input id="u" class="field" autocomplete="username" autocapitalize="none" bind:value={name} required />
        <label for="p" class="small muted">{t('Contraseña (8 o más)')}</label>
        <input id="p" class="field" type="password" autocomplete="new-password" bind:value={password} minlength="8" required />
        {#if error}<p class="error-text small">{error}</p>{/if}
        <button class="btn primary block" disabled={busy || !name || password.length < 8}>{t('Crear mi cuenta')}</button>
      {/if}
    </form>
  {:else if challenge}
    <form class="card" onsubmit={sendCode}>
      <h2 class="title">{t('Verificación en dos pasos')}</h2>
      <p class="small muted">{t('Escribe el código de tu app de autenticación, o uno de tus códigos de recuperación.')}</p>
      <!-- svelte-ignore a11y_autofocus -->
      <input class="field code" inputmode="numeric" autocomplete="one-time-code" autocapitalize="none" bind:value={code} autofocus required />
      {#if error}<p class="error-text small">{error}</p>{/if}
      <button class="btn primary block" disabled={busy || code.trim().length < 6}>{t('Entrar')}</button>
      <button class="btn block" type="button" onclick={() => { challenge = ''; error = ''; }}>{t('Volver')}</button>
    </form>
  {:else}
    <form class="card" onsubmit={login}>
      <label for="u" class="small muted">{t('Usuario')}</label>
      <input id="u" class="field" autocomplete="username" autocapitalize="none" bind:value={name} required />
      <label for="p" class="small muted">{t('Contraseña')}</label>
      <input id="p" class="field" type="password" autocomplete="current-password" bind:value={password} required />
      {#if error}<p class="error-text small">{error}</p>{/if}
      <button class="btn primary block" disabled={busy || !name || !password}>{t('Entrar')}</button>
      <p class="small muted">{t('La sesión se queda guardada en este navegador: solo tendrás que entrar una vez.')}</p>
    </form>
  {/if}

  {#if session.guestsEnabled && mode === 'login' && !challenge}
    <div class="guest">
      <button class="btn outline block" onclick={() => (guestNotice = true)} disabled={busy}>{t('Probar sin cuenta')}</button>
      <p class="small muted">
        {t('Para echar un vistazo: puedes leer y ver lo que quieras, con algunos límites. Todo lo que hagas se borra {0}.', [session.guestDays === 1 ? t('al cabo de un día') : t('a los {0} días', [session.guestDays])])}
      </p>
    </div>
  {/if}
</div>

<Sheet bind:open={guestNotice} title={t('Modo invitado: solo para probar')}>
  <div class="notice">
    <p>{t('Es una cuenta de prueba para ver si Yomukai te gusta, así que va')} <strong>{t('muy limitada')}</strong>:</p>
    <ul>
      <li>{t('Pocas peticiones por minuto. Si navegas muy deprisa, algunas páginas, capítulos o búsquedas pueden fallar o tardar: espera unos segundos y vuelve a intentarlo.')}</li>
      <li>{t('Como mucho 3 capítulos por descarga y 2 vídeos a la vez.')}</li>
      <li>{t('Sin importar, exportar ni sincronizar con la app.')}</li>
      <li>{t('Todo lo que hagas se borra {0}.', [session.guestDays === 1 ? t('al cabo de un día') : t('a los {0} días', [session.guestDays])])}</li>
    </ul>
    <p class="small muted">{t('Con una cuenta normal no hay nada de esto.')}</p>
  </div>
  <div class="notice-actions">
    <button class="btn text" onclick={() => (guestNotice = false)}>{t('Cancelar')}</button>
    <button class="btn primary" onclick={guest} disabled={busy}>{t('Entendido, entrar')}</button>
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
  .title { margin: 0 0 4px; font-size: 1.1rem; }
  .code { font-size: 1.4rem; letter-spacing: 0.2em; text-align: center; }
</style>
