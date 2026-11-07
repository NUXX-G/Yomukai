<script>
  import { t } from '../lib/i18n.svelte.js';
  // /sala/CODIGO: the link friends get. If something is already playing it goes straight to that episode (the player
  // keeps the room); if not, it waits here with the chat until the host picks one.
  import { onDestroy } from 'svelte';
  import { route, navigate, reader } from '../lib/router.svelte.js';
  import { createParty, copyRoomLink, roomLink } from '../lib/party.svelte.js';
  import { notify } from '../lib/store.svelte.js';
  import RoomChat from '../components/RoomChat.svelte';
  import Icon from '../components/Icon.svelte';

  const code = (route.params.code || '').toUpperCase();
  let client = $state(null);

  client = createParty(code, {
    onState: (state) => {
      if (!state?.chapterId) return;
      client?.close();
      navigate(reader('anime', state.sourceId, state.mediaId, state.chapterId) + '?sala=' + encodeURIComponent(code), { replace: true });
    },
    onNotice: (t) => notify(t)
  });
  onDestroy(() => client?.close());

  async function copy() { notify((await copyRoomLink(code)) ? t('Enlace copiado') : roomLink(code)); }
</script>

<div class="page room">
  {#if client?.party.closed}
    <div class="center">
      <Icon name="warning" size={40} />
      <p>{client.party.closed}</p>
      <a class="btn tonal" href="/">{t('Ir al inicio')}</a>
    </div>
  {:else}
    <header class="head">
      <h1>{client?.party.room?.name ?? t('Sala ') + code}</h1>
      <p class="muted">{t('Cuando el anfitrión ponga un episodio, entrarás a la vez que los demás. Si alguien se queda cargando, se espera a todos.')}</p>
      <div class="actions">
        <button class="btn primary" onclick={copy}><Icon name="open" size={18} /> {t('Copiar enlace')}</button>
        <span class="code" aria-label={t('Código de la sala')}>{code}</span>
      </div>
    </header>
    <div class="panel">{#if client}<RoomChat {client} />{/if}</div>
  {/if}
</div>

<style>
  .head h1 { margin: 12px 0 4px; font-size: var(--fs-title); font-weight: 800; }
  .head p { max-width: 640px; margin: 0 0 16px; }
  .actions { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
  .code { font: 700 1.3rem/1 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing: 0.18em; padding: 10px 14px; border-radius: var(--r-lg); background: var(--c-surface-high); }
  .panel { margin-top: 20px; height: min(70vh, 640px); border-radius: var(--r-xl); overflow: hidden; border: 1px solid var(--c-border); }
  .center { min-height: 50vh; display: grid; place-content: center; justify-items: center; gap: 12px; text-align: center; }
</style>
