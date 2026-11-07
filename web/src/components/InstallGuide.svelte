<script>
  import { t } from '../lib/i18n.svelte.js';
  // iPhone/iPad: Safari does not let web pages go full screen, but Yomukai opened from the home
  // screen has no Safari bars at all. This explains how, step by step.
  import Sheet from './Sheet.svelte';
  import Icon from './Icon.svelte';
  import { installGuide } from '../lib/fullscreen.svelte.js';
  import { isIPhone } from '../lib/platform.js';
</script>

<Sheet bind:open={installGuide.open} title="Pantalla completa en {isIPhone ? 'iPhone' : 'iPad'}">
  <p class="lead">
    {#if isIPhone}Safari no deja que las webs se pongan a pantalla completa en el iPhone (solo los vídeos).{:else}En el iPad, Safari no siempre deja ponerse a pantalla completa.{/if}
    Ya está puesto el <strong>{t('modo inmersivo')}</strong> {t('de Yomukai: sin barras de la app, solo la página. Para quitar también las barras de Safari,')} <strong>{t('abre Yomukai como una app')}</strong>:
  </p>
  <ol class="steps">
    <li><span class="badge"><Icon name="share" size={20} /></span><span>{t('Pulsa')} <strong>{t('Compartir')}</strong> {t('en la barra de Safari (el cuadrado con la flecha hacia arriba; en iOS 26 está en el menú «···»).')}</span></li>
    <li><span class="badge"><Icon name="addBox" size={20} /></span><span>{t('Elige')} <strong>{t('Añadir a pantalla de inicio')}</strong> {t('y pulsa')} <strong>{t('Añadir')}</strong>.</span></li>
    <li><span class="badge app">{t('Y')}</span><span>{t('Abre Yomukai desde su icono: ocupa toda la pantalla. La primera vez tendrás que volver a entrar con tu usuario (o como invitado).')}</span></li>
  </ol>
  <p class="small muted">
    La hora y la batería de arriba no se pueden ocultar: Apple no se lo permite a ninguna web.
    {#if isIPhone}Si giras el móvil en horizontal, desaparecen.{/if}
  </p>
  <button class="btn primary block" onclick={() => (installGuide.open = false)}>{t('Entendido')}</button>
</Sheet>

<style>
  .lead { margin: 0 0 14px; line-height: 1.5; }
  .lead strong, .steps strong { color: var(--primary); }
  .steps { list-style: none; margin: 0 0 14px; padding: 0; display: flex; flex-direction: column; gap: 12px; }
  .steps li { display: flex; gap: 12px; align-items: center; line-height: 1.4; }
  .badge { flex: none; width: 40px; height: 40px; border-radius: 12px; display: grid; place-items: center; background: var(--surface-high); color: var(--primary); }
  .badge.app { background: var(--primary); color: var(--on-primary); font-weight: 900; font-size: 1.2rem; }
</style>
