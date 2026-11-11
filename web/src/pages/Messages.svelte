<script>
  import { t, locale } from '../lib/i18n.svelte.js';
  // /mensajes and /mensajes/:name (F4.2): conversations one to one, live (new, typing, seen, deleted), with search,
  // older pages, mute and block. Messages are plain text (white-space keeps the lines) and shared cards; never HTML.
  import { onDestroy, tick } from 'svelte';
  import { api } from '../lib/api.js';
  import { route, navigate } from '../lib/router.svelte.js';
  import { session, notify, features } from '../lib/store.svelte.js';
  import { on, refreshDmUnread } from '../lib/live.svelte.js';
  import Icon from '../components/Icon.svelte';
  import ShareCard from '../components/ShareCard.svelte';
  import WorkPicker from '../components/WorkPicker.svelte';

  const peerName = $derived(route.params.name ? decodeURIComponent(route.params.name) : '');

  // ---------- Conversations ----------
  let list = $state(null);
  let listError = $state('');
  async function loadList() {
    try { list = await api.dms(); listError = ''; } catch (e) { list = list || []; listError = e.message; }
  }
  loadList();

  let members = $state([]);
  let picking = $state(false);
  async function startNew() {
    picking = !picking;
    if (picking && !members.length) members = ((await api.users().catch(() => [])) || []).filter((u) => !u.me);
  }

  // Search across every conversation (or only this one when open).
  let query = $state('');
  let found = $state(null);
  let searchTimer;
  function search() {
    clearTimeout(searchTimer);
    const q = query.trim();
    if (q.length < 2) { found = null; return; }
    searchTimer = setTimeout(async () => {
      try { found = await api.dmSearch(q, peerName || undefined); } catch (e) { found = []; notify(e.message); }
    }, 300);
  }

  // ---------- One conversation ----------
  let convo = $state(null);
  let messages = $state([]);
  let seenUpTo = $state(0);
  let more = $state(false);
  let typingUntil = $state(0);
  let now = $state(Date.now());
  let text = $state('');
  let card = $state(null);
  let pickerOpen = $state(false);
  let sending = $state(false);
  let scroller = $state();
  let loadedFor = '';

  async function open(name) {
    loadedFor = name;
    convo = null; messages = []; seenUpTo = 0; more = false; text = ''; card = null; found = null; query = '';
    if (!name) return;
    try {
      const h = await api.dmHistory(name);
      if (loadedFor !== name) return;
      convo = h; messages = h.messages; seenUpTo = h.seenUpTo; more = h.more;
      await tick();
      scroller?.scrollTo({ top: scroller.scrollHeight });
      markRead();
    } catch (e) { notify(e.message); navigate('/mensajes', { replace: true }); }
  }
  $effect(() => { const n = peerName; if (n !== loadedFor) open(n); });

  async function older() {
    if (!messages.length) return;
    const h = await api.dmHistory(peerName, messages[0].id).catch(() => null);
    if (!h) return;
    const before = scroller.scrollHeight;
    messages = [...h.messages, ...messages]; more = h.more;
    await tick();
    scroller.scrollTop = scroller.scrollHeight - before;
  }

  function markRead() {
    const last = [...messages].reverse().find((m) => !m.mine);
    if (!last || document.hidden) return;
    api.dmRead(peerName, last.id).then(() => { refreshDmUnread(); loadList(); }).catch(() => {});
  }

  let lastTyping = 0;
  function typed() {
    if (Date.now() - lastTyping > 3000 && text.trim()) { lastTyping = Date.now(); api.dmTyping(peerName).catch(() => {}); }
  }

  async function send(e) {
    e?.preventDefault();
    const body = text.trim();
    if ((!body && !card) || sending) return;
    sending = true;
    try {
      const m = await api.dmSend(peerName, body, card);
      if (!messages.some((x) => x.id === m.id)) messages = [...messages, m];
      text = ''; card = null; pickerOpen = false;
      await tick();
      scroller?.scrollTo({ top: scroller.scrollHeight, behavior: 'smooth' });
      loadList();
    } catch (err) { notify(err.message); } finally { sending = false; }
  }

  function keydown(e) {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) send(e);
  }

  async function remove(m) {
    try { await api.dmDelete(m.id); messages = messages.filter((x) => x.id !== m.id); loadList(); } catch (e) { notify(e.message); }
  }

  async function toggle(kind) {
    if (!convo) return;
    const next = !convo[kind];
    try {
      if (kind === 'muted') await api.dmMute(peerName, next); else await api.dmBlock(peerName, next);
      convo = { ...convo, [kind]: next };
      notify(kind === 'muted' ? (next ? t('Conversación silenciada') : t('Ya no está silenciada')) : (next ? t('Has bloqueado a {0}', [peerName]) : t('Has desbloqueado a {0}', [peerName])));
      refreshDmUnread(); loadList();
    } catch (e) { notify(e.message); }
  }

  // ---------- Live ----------
  const same = (a, b) => (a || '').toLowerCase() === (b || '').toLowerCase();
  const offs = [
    on('dm', async (d) => {
      loadList();
      if (!peerName || !same(d.peer, peerName) || messages.some((m) => m.id === d.message.id)) return;
      messages = [...messages, d.message];
      if (!d.message.mine) { typingUntil = 0; markRead(); }
      await tick();
      scroller?.scrollTo({ top: scroller.scrollHeight, behavior: 'smooth' });
    }),
    on('dm_typing', (d) => { if (same(d.peer, peerName)) typingUntil = Date.now() + 5000; }),
    on('dm_seen', (d) => { if (same(d.peer, peerName)) seenUpTo = Math.max(seenUpTo, d.upTo); }),
    on('dm_deleted', (d) => { if (same(d.peer, peerName)) messages = messages.filter((m) => m.id !== d.id); loadList(); }),
    on('reconnect', () => { loadList(); if (peerName) open(peerName); })
  ];
  const clock = setInterval(() => (now = Date.now()), 1000);
  const visible = () => { if (!document.hidden && peerName) markRead(); };
  document.addEventListener('visibilitychange', visible);
  onDestroy(() => { offs.forEach((off) => off()); clearInterval(clock); document.removeEventListener('visibilitychange', visible); });

  const lastMine = $derived([...messages].reverse().find((m) => m.mine));
  const time = (at) => {
    const d = new Date(at);
    return d.toDateString() === new Date().toDateString()
      ? d.toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' })
      : d.toLocaleDateString(locale(), { day: 'numeric', month: 'short' });
  };
  const preview = (m) => (m ? (m.mine ? t('Tú: ') : '') + (m.text || (m.card ? '📎 ' + m.card.title : '')) : '');
  const initial = (n) => (n || '?').slice(0, 1).toUpperCase();
</script>

<div class="page messages" class:has-convo={!!peerName}>
  {#if features.flags.dms === false}
    <p class="muted">{t('Los mensajes directos están desactivados en este servidor.')}</p>
  {:else if session.user?.guest}
    <p class="muted">{t('Los invitados no tienen mensajes directos.')}</p>
  {:else}
    <aside class="side" aria-label={t('Conversaciones')}>
      <header class="side-head">
        <h1>{t('Mensajes')}</h1>
        <button class="icon-btn" onclick={startNew} aria-expanded={picking} aria-label={t('Nuevo mensaje')} title={t('Nuevo mensaje')}><Icon name="add" /></button>
      </header>
      {#if picking}
        <div class="people">
          {#each members as u (u.name)}
            <a href="/mensajes/{encodeURIComponent(u.name)}" onclick={() => (picking = false)} class="person">
              <span class="avatar">{#if u.avatar}<img src={u.avatar} alt="" />{:else}{initial(u.name)}{/if}</span>{u.name}
            </a>
          {:else}<p class="muted small">{t('Cargando...')}</p>{/each}
        </div>
      {/if}
      <label class="search">
        <Icon name="search" size={18} />
        <input bind:value={query} oninput={search} placeholder={peerName ? t('Buscar en esta conversación') : t('Buscar en tus mensajes')} maxlength="100" />
      </label>
      {#if found}
        <ul class="found">
          {#each found as f (f.message.id)}
            <li><a href="/mensajes/{encodeURIComponent(f.peer)}"><b>{f.peer}</b> · <span class="muted">{time(f.message.at)}</span><br />{f.message.text || f.message.card?.title}</a></li>
          {:else}<li class="muted small">{t('Nada encontrado')}</li>{/each}
        </ul>
      {:else if list === null}
        <div class="spinner"></div>
      {:else}
        <ul class="convos">
          {#each list as c (c.peer.id)}
            <li>
              <a href="/mensajes/{encodeURIComponent(c.peer.name)}" class:on={same(c.peer.name, peerName)} class:unread={c.unread > 0}>
                <span class="avatar">{#if c.peer.avatar}<img src={c.peer.avatar} alt="" />{:else}{initial(c.peer.name)}{/if}</span>
                <span class="grow">
                  <span class="row"><b>{c.peer.name}</b>{#if c.muted}<Icon name="mute" size={14} />{/if}<span class="when">{time(c.lastAt)}</span></span>
                  <span class="last">{c.blocked ? t('Bloqueado') : preview(c.last)}</span>
                </span>
                {#if c.unread && !c.muted}<span class="badge" aria-label={t('{0} sin leer', [c.unread])}>{c.unread > 9 ? '9+' : c.unread}</span>{/if}
              </a>
            </li>
          {:else}
            <li class="empty muted">{listError || t('Aún no tienes conversaciones. Pulsa + o escribe a alguien desde su perfil o desde Amigos.')}</li>
          {/each}
        </ul>
      {/if}
    </aside>

    <section class="convo" aria-label={peerName ? t('Conversación con {0}', [peerName]) : t('Conversación')}>
      {#if !peerName}
        <div class="placeholder muted"><Icon name="send" size={40} /><p>{t('Elige una conversación')}</p></div>
      {:else}
        <header class="convo-head">
          <a class="icon-btn back" href="/mensajes" aria-label={t('Volver a las conversaciones')}><Icon name="back" /></a>
          <a class="who" href="/u/{encodeURIComponent(peerName)}">
            <span class="avatar">{#if convo?.peer.avatar}<img src={convo.peer.avatar} alt="" />{:else}{initial(peerName)}{/if}</span>
            <span><b>{convo?.peer.name || peerName}</b>{#if typingUntil > now}<span class="typing">{t('escribiendo...')}</span>{/if}</span>
          </a>
          <span class="grow"></span>
          {#if convo}
            <button class="icon-btn" onclick={() => toggle('muted')} aria-pressed={convo.muted} title={convo.muted ? t('Quitar silencio') : t('Silenciar')} aria-label={convo.muted ? t('Quitar silencio') : t('Silenciar')}><Icon name={convo.muted ? 'mute' : 'volume'} /></button>
            <button class="icon-btn" onclick={() => toggle('blocked')} aria-pressed={convo.blocked} title={convo.blocked ? t('Desbloquear') : t('Bloquear')} aria-label={convo.blocked ? t('Desbloquear') : t('Bloquear')}><Icon name="remove" /></button>
          {/if}
        </header>

        <div class="log" bind:this={scroller} aria-live="polite">
          {#if more}<button class="btn text older" onclick={older}>{t('Ver mensajes anteriores')}</button>{/if}
          {#each messages as m (m.id)}
            <div class="msg" class:mine={m.mine}>
              <div class="bubble">
                {#if m.card}<ShareCard card={m.card} mine={m.mine} />{/if}
                {#if m.text}<p>{m.text}</p>{/if}
                <span class="meta">{time(m.at)}{#if m.mine && lastMine?.id === m.id && seenUpTo >= m.id} · {t('Visto')}{/if}</span>
              </div>
              {#if m.mine}<button class="del" onclick={() => remove(m)} aria-label={t('Borrar este mensaje')} title={t('Borrar')}><Icon name="delete" size={16} /></button>{/if}
            </div>
          {:else}
            {#if convo}<p class="muted center">{t('Saluda a {0}. Solo texto y obras compartidas.', [convo.peer.name])}</p>{/if}
          {/each}
        </div>

        {#if convo?.blocked}
          <p class="muted blocked">{t('Has bloqueado a esta persona. Desbloquéala para escribirle.')}</p>
        {:else}
          {#if pickerOpen}<div class="pick"><WorkPicker onpick={(c) => { card = c; pickerOpen = false; }} onclose={() => (pickerOpen = false)} /></div>{/if}
          {#if card}
            <div class="attached"><ShareCard {card} mine /><button class="icon-btn" onclick={() => (card = null)} aria-label={t('Quitar la obra')}><Icon name="close" size={18} /></button></div>
          {/if}
          <form class="composer" onsubmit={send}>
            {#if features.flags.sharing !== false}
              <button type="button" class="icon-btn" onclick={() => (pickerOpen = !pickerOpen)} aria-expanded={pickerOpen} aria-label={t('Compartir una obra')} title={t('Compartir una obra')}><Icon name="add" /></button>
            {/if}
            <textarea bind:value={text} oninput={typed} onkeydown={keydown} rows="1" maxlength="2000" placeholder={t('Escribe un mensaje')} aria-label={t('Mensaje')}></textarea>
            <button class="icon-btn send" disabled={sending || (!text.trim() && !card)} aria-label={t('Enviar')}><Icon name="send" /></button>
          </form>
        {/if}
      {/if}
    </section>
  {/if}
</div>

<style>
  .messages { display: grid; grid-template-columns: minmax(260px, 340px) 1fr; gap: 16px; height: calc(100dvh - var(--top-h, 64px) - 32px); min-height: 420px; }
  @media (max-width: 760px) {
    .messages { grid-template-columns: 1fr; height: calc(100dvh - var(--top-h, 64px) - var(--bottom-h, 72px) - 16px); }
    .messages.has-convo .side { display: none; }
    .messages:not(.has-convo) .convo { display: none; }
  }
  @media (min-width: 761px) { .back { display: none; } }
  .side, .convo { display: flex; flex-direction: column; min-height: 0; background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--r-xl, 16px); overflow: hidden; }
  .side-head, .convo-head { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--c-border); }
  h1 { flex: 1; margin: 0; font-size: 1.3rem; font-weight: 800; }
  .search { display: flex; align-items: center; gap: 8px; margin: 10px 12px; padding: 0 10px; border-radius: 999px; background: var(--c-surface-high); }
  .search input { flex: 1; min-width: 0; border: 0; background: transparent; color: inherit; font: inherit; padding: 9px 0; outline: none; }
  ul { list-style: none; margin: 0; padding: 0 6px 8px; overflow-y: auto; }
  .convos a { display: flex; align-items: center; gap: 10px; padding: 8px; border-radius: 12px; color: inherit; text-decoration: none; }
  .convos a:hover, .convos a.on { background: var(--c-surface-high); }
  .convos a.unread b { color: var(--c-accent); }
  .row { display: flex; align-items: center; gap: 6px; }
  .when { margin-left: auto; font-size: 0.75rem; color: var(--c-text-dim); }
  .last { display: block; font-size: var(--fs-small, 0.85rem); color: var(--c-text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .unread .last { color: var(--c-text); font-weight: 600; }
  .badge { min-width: 20px; height: 20px; padding: 0 6px; border-radius: 10px; background: var(--c-accent); color: var(--c-on-accent, #000); font-size: 0.75rem; font-weight: 800; display: grid; place-items: center; }
  .grow { flex: 1; min-width: 0; }
  .avatar { width: 40px; height: 40px; border-radius: 50%; overflow: hidden; display: grid; place-items: center; font-weight: 800; background: var(--c-surface-high); flex: none; }
  .avatar img { width: 100%; height: 100%; object-fit: cover; }
  .people { display: flex; flex-wrap: wrap; gap: 6px; padding: 10px 12px 0; max-height: 160px; overflow-y: auto; }
  .person { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px 4px 4px; border-radius: 999px; background: var(--c-surface-high); color: inherit; text-decoration: none; font-size: var(--fs-small, 0.85rem); }
  .person .avatar { width: 26px; height: 26px; font-size: 0.75rem; }
  .found a { display: block; padding: 8px; border-radius: 10px; color: inherit; text-decoration: none; font-size: var(--fs-small, 0.85rem); white-space: pre-wrap; overflow-wrap: anywhere; }
  .found a:hover { background: var(--c-surface-high); }
  .empty { padding: 16px 10px; }
  .who { display: flex; align-items: center; gap: 10px; color: inherit; text-decoration: none; min-width: 0; }
  .who > span:last-child { display: flex; flex-direction: column; }
  .typing { font-size: 0.75rem; color: var(--c-accent); }
  .log { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 6px; overscroll-behavior: contain; }
  .older { align-self: center; }
  .msg { display: flex; align-items: flex-end; gap: 4px; max-width: 85%; }
  .msg.mine { align-self: flex-end; flex-direction: row-reverse; }
  .bubble { background: var(--c-surface-high); padding: 8px 10px; border-radius: 16px 16px 16px 4px; display: flex; flex-direction: column; gap: 6px; min-width: 0; }
  .mine .bubble { background: color-mix(in srgb, var(--c-accent) 26%, var(--c-surface-high)); border-radius: 16px 16px 4px 16px; }
  .bubble p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
  .meta { font-size: 0.7rem; color: var(--c-text-dim); align-self: flex-end; }
  .del { opacity: 0; border: 0; background: transparent; color: var(--c-text-dim); cursor: pointer; padding: 4px; border-radius: 50%; }
  .msg:hover .del, .del:focus-visible { opacity: 1; }
  @media (hover: none) { .del { opacity: 0.6; } }
  .composer { display: flex; align-items: flex-end; gap: 6px; padding: 8px; border-top: 1px solid var(--c-border); }
  textarea { flex: 1; min-width: 0; resize: none; max-height: 140px; field-sizing: content; border: 0; border-radius: 18px; padding: 10px 14px; background: var(--c-surface-high); color: inherit; font: inherit; }
  .send:not(:disabled) { color: var(--c-accent); }
  .pick, .attached { padding: 0 8px; }
  .attached { display: flex; align-items: center; gap: 6px; padding-top: 8px; }
  .placeholder { flex: 1; display: grid; place-content: center; justify-items: center; gap: 8px; }
  .blocked, .center { text-align: center; padding: 12px; }
  .small { font-size: var(--fs-small, 0.85rem); }
</style>
