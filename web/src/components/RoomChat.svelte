<script>
  import { t, locale } from '../lib/i18n.svelte.js';
  // Room chat: messages (plain text, @mentions highlighted, spoilers hidden behind a tap), quick reactions that float
  // over the video, who is here and who hosts. Never renders HTML from messages.
  import Icon from './Icon.svelte';
  import { session, notify } from '../lib/store.svelte.js';
  import { roomLink, copyRoomLink } from '../lib/party.svelte.js';
  import { features } from '../lib/store.svelte.js';
  import ShareCard from './ShareCard.svelte';
  import WorkPicker from './WorkPicker.svelte';

  let { client, onclose = undefined } = $props();
  const party = $derived(client.party);
  let text = $state('');
  let spoiler = $state(false);
  let revealed = $state({});
  let list;
  let tab = $state('chat');
  let card = $state(null);
  let picking = $state(false);

  function send(e) {
    e.preventDefault();
    const t = text.trim();
    if (!t && !card) return;
    client.chat(t, spoiler, card);
    text = '';
    spoiler = false;
    card = null;
  }

  /** Queue (F4.1): the host reorders and plays; anyone removes what they added. */
  const canEdit = (item) => client.canControl || item.addedByName === me;
  function move(item, delta) {
    const i = party.queue.findIndex((q) => q.id === item.id);
    client.queueMove(item.id, Math.max(0, i + delta));
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

  <div class="tabs" role="tablist">
    <button role="tab" aria-selected={tab === 'chat'} class:on={tab === 'chat'} onclick={() => (tab = 'chat')}>{t('Chat')}</button>
    <button role="tab" aria-selected={tab === 'queue'} class:on={tab === 'queue'} onclick={() => (tab = 'queue')}>{t('Cola')}{#if party.queue.length} · {party.queue.length}{/if}</button>
    {#if party.state}
      <button class="skip" class:on={client.votedSkip} onclick={() => client.skip()} aria-pressed={client.votedSkip} title={t('Votar para saltar este episodio')}>
        <Icon name="skip" size={16} /> {t('Saltar')} {party.skip.votes.length}/{party.skip.needed}
      </button>
    {/if}
  </div>

  {#if tab === 'queue'}
    <div class="queue">
      {#each party.queue as q, i (q.id)}
        <div class="item">
          <span class="pos">{i + 1}</span>
          <span class="cover">{#if q.cover}<img src={q.cover} alt="" loading="lazy" />{/if}</span>
          <span class="grow"><b>{q.title}</b><br /><span class="muted">{q.episode}{#if q.addedByName} · {q.addedByName}{/if}</span></span>
          {#if client.canControl}
            <button class="icon-btn" onclick={() => client.queuePlay(q.id)} title={t('Poner ahora')} aria-label={t('Poner ahora {0}', [q.title])}><Icon name="play" size={18} /></button>
            <button class="icon-btn" disabled={i === 0} onclick={() => move(q, -1)} aria-label={t('Subir')}><Icon name="up" size={18} /></button>
            <button class="icon-btn" disabled={i === party.queue.length - 1} onclick={() => move(q, 1)} aria-label={t('Bajar')}><Icon name="down" size={18} /></button>
          {/if}
          {#if canEdit(q)}<button class="icon-btn" onclick={() => client.queueRemove(q.id)} aria-label={t('Quitar de la cola')}><Icon name="close" size={18} /></button>{/if}
        </div>
      {:else}
        <p class="muted small empty">{t('La cola está vacía. Añade episodios desde la lista de episodios del reproductor («+ Cola»); cuando acabe uno, empieza el siguiente solo.')}</p>
      {/each}
    </div>
  {:else}
  <div class="messages" bind:this={list} aria-live="polite">
    {#each party.messages as m (m.id)}
      <div class="msg" class:mine={m.from === me} class:mentioned={me && (m.mentions || []).includes(me)}>
        <div class="meta"><b>{m.from}</b> <span class="muted">{time(m.at)}</span></div>
        {#if m.spoiler && !revealed[m.id]}
          <button class="spoiler" onclick={() => (revealed[m.id] = true)}>{t('Spoiler · toca para verlo')}</button>
        {:else}
          {#if m.text}<div class="text">{#each parts(m.text) as p, i (i)}{#if p.mention}<span class="at">@{p.mention}</span>{:else}{p.text}{/if}{/each}</div>{/if}
          {#if m.card}<ShareCard card={m.card} mine={m.from === me} />{/if}
        {/if}
      </div>
    {:else}
      <p class="muted small empty">{t('Aún no hay mensajes. Saluda.')}</p>
    {/each}
  </div>

  {/if}

  {#if picking}<div class="pick"><WorkPicker onpick={(c) => { card = c; picking = false; tab = 'chat'; }} onclose={() => (picking = false)} /></div>{/if}
  {#if card}<div class="pick attached"><ShareCard {card} mine /><button class="icon-btn" onclick={() => (card = null)} aria-label={t('Quitar la obra')}><Icon name="close" size={18} /></button></div>{/if}
  <div class="reactions" role="group" aria-label={t('Reacciones')}>
    {#each party.reactions as r (r)}<button onclick={() => client.react(r)} aria-label="Reaccionar {r}">{r}</button>{/each}
  </div>
  <form class="compose" onsubmit={send}>
    <input class="field" placeholder={t('Escribe... (@nombre para mencionar)')} bind:value={text} maxlength="500" enterkeyhint="send" />
    {#if features.flags.sharing !== false}<button type="button" class="icon-btn" class:on={picking} onclick={() => (picking = !picking)} aria-label={t('Compartir una obra')} title={t('Compartir una obra')}><Icon name="add" size={20} /></button>{/if}
    <button type="button" class="icon-btn" class:on={spoiler} aria-pressed={spoiler} onclick={() => (spoiler = !spoiler)} title={t('Marcar como spoiler')}><Icon name="eye" size={20} /></button>
    <button class="icon-btn" aria-label={t('Enviar')} disabled={!text.trim() && !card}><Icon name="send" size={20} /></button>
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
  .tabs { display: flex; gap: 4px; padding: 6px 10px; border-bottom: 1px solid rgb(255 255 255 / 0.08); align-items: center; }
  .tabs button { padding: 6px 12px; border-radius: 999px; font-size: 0.82rem; font-weight: 700; color: rgb(255 255 255 / 0.7); display: inline-flex; align-items: center; gap: 4px; }
  .tabs button.on { background: rgb(255 255 255 / 0.12); color: #fff; }
  .tabs .skip { margin-left: auto; }
  .tabs .skip.on { color: var(--c-accent); }
  .queue { flex: 1; overflow-y: auto; padding: 6px 10px; display: flex; flex-direction: column; gap: 6px; }
  .item { display: flex; align-items: center; gap: 8px; font-size: 0.85rem; padding: 6px; border-radius: 10px; background: rgb(255 255 255 / 0.05); }
  .item .pos { width: 18px; text-align: center; color: rgb(255 255 255 / 0.5); font-weight: 700; }
  .item .cover { width: 34px; height: 48px; border-radius: 6px; overflow: hidden; background: rgb(255 255 255 / 0.08); flex: none; }
  .item .cover img { width: 100%; height: 100%; object-fit: cover; }
  .item .grow { overflow: hidden; text-overflow: ellipsis; }
  .item .icon-btn { width: 32px; height: 32px; }
  .pick { padding: 4px 8px; color: var(--c-text); }
  .attached { display: flex; align-items: center; gap: 6px; }
  .compose { display: flex; gap: 4px; align-items: center; padding: 8px 8px calc(var(--safe-bottom) + 8px) 12px; }
  .compose .field { flex: 1; min-width: 0; height: 42px; background: rgb(255 255 255 / 0.08); border-color: transparent; color: #fff; }
</style>
