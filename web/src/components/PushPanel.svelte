<script>
  import { t } from '../lib/i18n.svelte.js';
  // Notifications on the phone through ntfy (free app for Android and iOS): the server gives each person a private,
  // random topic; subscribing to it in the app is all it takes. Mentions, replies, recommendations and achievements.
  import { api } from '../lib/api.js';
  import { notify } from '../lib/store.svelte.js';

  let status = $state(null);
  api.pushStatus().then((s) => (status = s), () => (status = { enabled: false }));

  async function enable() { try { status = await api.pushEnable(); } catch (e) { notify(e.message); } }
  async function disable() { try { status = await api.pushDisable(); } catch (e) { notify(e.message); } }
  async function copy() { try { await navigator.clipboard.writeText(status.url); notify(t('Dirección copiada')); } catch { notify(status.url); } }
</script>

<div class="push">
  <p class="small muted">{t('Recibe en el móvil las menciones, respuestas, recomendaciones y logros con la app gratuita')} <b>{t('ntfy')}</b> {t('(Android e iOS). Tu tema es privado y aleatorio.')}</p>
  {#if status === null}
    <div class="spinner"></div>
  {:else if status.enabled}
    <ol class="small">
      <li>{t('Instala ntfy desde la tienda de tu móvil.')}</li>
      <li>{t('Pulsa «+» y suscríbete a esta dirección (cópiala):')}</li>
    </ol>
    <div class="url"><code>{status.url}</code><button class="btn tonal" onclick={copy}>{t('Copiar')}</button></div>
    <button class="btn text" onclick={disable}>{t('Desactivar los avisos en el móvil')}</button>
  {:else}
    <button class="btn primary" onclick={enable}>{t('Activar avisos en el móvil')}</button>
  {/if}
</div>

<style>
  .push { display: flex; flex-direction: column; gap: 10px; align-items: flex-start; }
  ol { margin: 0; padding-left: 1.2em; }
  .url { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; max-width: 100%; }
  code { padding: 8px 10px; border-radius: var(--r-md); background: var(--c-surface-high); overflow-wrap: anywhere; font-size: 0.8rem; }
</style>
