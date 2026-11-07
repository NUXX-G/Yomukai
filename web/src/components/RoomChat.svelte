<script>
  import { t, locale } from '../lib/i18n.svelte.js';
  // Room chat: messages (plain text, @mentions highlighted, spoilers hidden behind a tap), quick reactions that float
  // over the video, who is here and who hosts. Never renders HTML from messages.
  import Icon from './Icon.svelte';
  import { session, notify } from '../lib/store.svelte.js';
  import { roomLink, copyRoomLink } from '../lib/party.svelte.js';

  let { client, onclose = undefined } = $props();
  const party = $derived(client.party);
  let text = $state('');
  let spoiler = $state(false);
  let revealed = $state({});
  let list;

  function send(e) {
    e.preventDefault();
    const t = text.trim();
    if (!t) return;
    client.chat(t, spoiler);
    text = '';
    spoiler = false;
  }

  /** "hola @ana" -> [{ text: 'hola ' }, { mention: 'ana' }] */
  function parts(t) {
    const out = [];
    let last = 0;
    for (const m of t.matchAll(/(^|[^\w@])@([\p{L}\p{N}_.-]{2,40})/gu)) {
      const start = m.index + m[1].length;
      if (start > last) out.push({ text: t.slice(last, start) });
      out.push({ mention: m[2] });
      last = start + 1 + m[2].length;
    }
    if (last < t.length) out.push({ text: t.slice(last) });
    return out;
  }

  const time = (at) => new Date(at).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' });
  const me = $derived(session.user?.name);

  $effect(() => {
    void party.messages.length;
    party.unread = 0;
    queueMicrotask(() => list?.scrollTo({ top: list.scrollHeight }));
  });

  async function copy() { notify((await copyRoomLink(party.code)) ? t('Enlace copiado: pégalo en Discord o donde quieras') : roomLink(party.code)); }
</script>

<aside class="chat" aria-label={t('Chat de la sala')}>
  <header>
    <div class="grow">
      <div class="name">{party.room?.name ?? 'Sala'}</div>
      <div class="small muted">{t('{0} · código {1}', [party.connected ? `${party.members.length} ${party.members.length === 1 ? 'persona' : 'personas'}` : 'Reconectando...', party.code])}</div>
    </div>
    <button class="icon-btn" onclick={copy} aria-label={t('Copiar enlace de la sala')} title={t('Copiar enlace')}><Icon name="open" size={20} /></button>
    {#if onclose}<button class="icon-btn" onclick={onclose} aria-label={t('Cerrar el chat')}><Icon name="close" size={20} /></button>{/if}
  </header>

  <div class="members">
    {#each party.members as m (m.id)}
      <span class="member" class:host={m.id === party.host}>
        {m.name}{#if m.id === party.host} {t('· anfitrión')}{/if}
        {#if client.isHost && m.id !== party.you}<button class="give" onclick={() => client.giveHost(m.id)} title={t('Darle el mando')}>{t('ceder')}</button>{/if}
      </span>
    {/each}
    {#if client.isHost}
      <label class="allow"><input type="checkbox" checked={party.everyoneControls} onchange={(e) => client.allowAll(e.currentTarget.checked)} /> {t('Todos controlan')}</label>
    {/if}
  </div>

  <div class="messages" bind:this={list} aria-live="polite">
    {#each party.messages as m (m.id)}
      <div class="msg" class:mine={m.from === me} class:mentioned={me && (m.mentions || []).includes(me)}>
        <div class="meta"><b>{m.from}</b> <span class="muted">{time(m.at)}</span></div>
        {#if m.spoiler && !revealed[m.id]}
          <button class="spoiler" onclick={() => (revealed[m.id] = true)}>{t('Spoiler · toca para verlo')}</button>
        {:else}
          <div class="text">{#each parts(m.text) as p, i (i)}{#if p.mention}<span class="at">@{p.mention}</span>{:else}{p.text}{/if}{/each}</div>
        {/if}
      </div>
    {:else}
      <p class="muted small empty">{t('Aún no hay mensajes. Saluda.')}</p>
    {/each}
  </div>

  <div class="reactions" role="group" aria-label={t('Reacciones')}>
    {#each party.reactions as r (r)}<button onclick={() => client.react(r)} aria-label="Reaccionar {r}">{r}</button>{/each}
  </div>
  <form class="compose" onsubmit={send}>
    <input class="field" placeholder={t('Escribe... (@nombre para mencionar)')} bind:value={text} maxlength="500" enterkeyhint="send" />
    <button type="button" class="icon-btn" class:on={spoiler} aria-pressed={spoiler} onclick={() => (spoiler = !spoiler)} title={t('Marcar como spoiler')}><Icon name="eye" size={20} /></button>
    <button class="icon-btn" aria-label={t('Enviar')} disabled={!text.trim()}><Icon name="next" size={20} /></button>
  </form>
</aside>

<style>
  .chat { display: flex; flex-direction: column; height: 100%; background: rgb(12 12 13 / 0.94); color: #fff; -webkit-backdrop-filter: blur(16px); backdrop-filter: blur(16px); }
  header { display: flex; align-items: center; gap: 4px; padding: calc(var(--safe-top) + 10px) 8px 8px 14px; border-bottom: 1px solid rgb(255 255 255 / 0.08); }
  .grow { flex: 1; min-width: 0; }
  .name { font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .icon-btn { color: #fff; }
  .icon-btn.on { color: var(--c-accent); }
  .members { display: flex; flex-wrap: wrap; gap: 6px; padding: 8px 14px; font-size: 0.78rem; }
  .member { padding: 3px 9px; border-radius: 999px; background: rgb(255 255 255 / 0.08); display: inline-flex; gap: 6px; align-items: center; }
  .member.host { background: color-mix(in srgb, var(--c-accent) 25%, transparent); }
  .give { color: var(--c-accent); font-size: 0.72rem; font-weight: 700; }
  .allow { display: flex; align-items: center; gap: 6px; color: rgb(255 255 255 / 0.7); }
  .messages { flex: 1; overflow-y: auto; padding: 8px 14px; display: flex; flex-direction: column; gap: 10px; overscroll-behavior: contain; }
  .msg { font-size: 0.9rem; }
  .msg.mentioned { border-left: 3px solid var(--c-accent); padding-left: 8px; }
  .meta { font-size: 0.74rem; margin-bottom: 2px; }
  .muted { color: rgb(255 255 255 / 0.5); }
  .text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.4; }
  .at { color: var(--c-accent); font-weight: 600; }
  .spoiler { padding: 6px 10px; border-radius: 8px; background: rgb(255 255 255 / 0.1); font-size: 0.8rem; }
  .empty { text-align: center; margin-top: 24px; }
  .reactions { display: flex; gap: 2px; padding: 4px 8px; overflow-x: auto; scrollbar-width: none; border-top: 1px solid rgb(255 255 255 / 0.08); }
  .reactions::-webkit-scrollbar { display: none; }
  .reactions button { font-size: 1.35rem; width: 40px; height: 40px; border-radius: 10px; flex: none; }
  .reactions button:hover { background: rgb(255 255 255 / 0.1); }
  .compose { display: flex; gap: 4px; align-items: center; padding: 8px 8px calc(var(--safe-bottom) + 8px) 12px; }
  .compose .field { flex: 1; min-width: 0; height: 42px; background: rgb(255 255 255 / 0.08); border-color: transparent; color: #fff; }
</style>
