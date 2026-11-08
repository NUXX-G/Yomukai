<script>
  import { t, locale } from '../lib/i18n.svelte.js';
  // A forum thread: posts (markdown drawn safely), reply with quote, reactions, spoilers "up to ep./ch. N" hidden from
  // whoever is behind, images uploaded to the server, and the admin's tools (pin, close, delete, mute).
  import { api } from '../lib/api.js';
  import { navigate } from '../lib/router.svelte.js';
  import { session, notify } from '../lib/store.svelte.js';
  import { quoteOf } from '../lib/markdown.js';
  import Markdown from './Markdown.svelte';
  import Icon from './Icon.svelte';

  /** threadId: an existing thread. Or newFor: { sourceId, mediaId, title } to start a work's thread on first post. */
  /** ondeleted: called once the whole thread is gone (deleted by the admin, or its last post removed). */
  let { threadId = null, newFor = null, upTo = null, anime = false, ondeleted = () => navigate('/foro') } = $props();
  const REACTIONS = ['😂', '😮', '😭', '😡', '❤️', '👏', '🔥', '💀', '🤯', '👀'];

  let thread = $state(null);
  let posts = $state([]);
  let loading = $state(false);
  let body = $state('');
  let spoiler = $state('');
  let sending = $state(false);
  let revealed = $state({});
  let editingId = $state(null);
  let editText = $state('');
  let input;
  let id = $state(threadId);

  async function load(afterDelete = false) {
    if (!id) return;
    loading = true;
    try {
      const r = await api.forumThread(id, upTo ?? undefined);
      thread = r.thread;
      posts = r.posts;
      if (afterDelete && (!r.thread || r.thread.deleted || !r.posts?.length)) gone();
    } catch (e) { if (afterDelete) gone(); else notify(e.message); } finally { loading = false; }
  }

  // The thread no longer exists: leave it (it must not look alive until the page is left).
  function gone() {
    thread = null;
    posts = [];
    id = null;
    notify(t('Hilo borrado'));
    ondeleted();
  }
  load();

  async function send(e) {
    e.preventDefault();
    if (!body.trim()) return;
    sending = true;
    const spoilerUntil = spoiler === '' ? undefined : Number(spoiler);
    try {
      if (!id && newFor) {
        const r = await api.forumNew({ category: 'work', sourceId: newFor.sourceId, mediaId: newFor.mediaId, title: newFor.title, body, spoilerUntil });
        id = r.id;
      } else {
        await api.forumReply(id, { body, spoilerUntil });
      }
      body = '';
      spoiler = '';
      await load();
    } catch (err) { notify(err.message); } finally { sending = false; }
  }

  function quote(p) {
    body = quoteOf(p.author, p.body) + body;
    input?.focus();
  }

  async function addImage(e) {
    const file = e.currentTarget.files?.[0];
    e.currentTarget.value = '';
    if (!file) return;
    try { const imageId = await api.forumImage(file); body += (body && !body.endsWith('\n') ? '\n' : '') + `![imagen](/api/forum/images/${imageId})\n`; } catch (err) { notify(err.message); }
  }

  async function react(p, emoji) {
    try { await api.forumReact(p.id, emoji); await load(); } catch (err) { notify(err.message); }
  }

  async function saveEdit(p) {
    try { await api.forumEdit(p.id, editText); editingId = null; await load(); } catch (err) { notify(err.message); }
  }

  async function remove(p) {
    if (!confirm(t('¿Borrar este mensaje?'))) return;
    try { await api.forumDelete(p.id); await load(true); } catch (err) { notify(err.message); }
  }

  async function moderate(change) {
    try {
      await api.adminThread(id, change);
      if (change.deleted) return gone();
      notify(t('Hecho'));
      await load();
    } catch (err) { notify(err.message); }
  }

  async function mute(name) {
    const minutes = Number(prompt(`¿Cuántos minutos silenciar a ${name}? (0 lo quita)`, '60'));
    if (!Number.isFinite(minutes)) return;
    try { await api.adminMute(name, minutes, t('Desde el foro')); notify(minutes ? `${name} silenciado` : t('{0} ya puede escribir', [name])); } catch (err) { notify(err.message); }
  }

  const when = (at) => new Date(at).toLocaleString(locale(), { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  const unit = $derived(anime ? 'episodio' : t('capítulo'));
</script>

<div class="thread">
  {#if thread}
    <header class="head">
      <h2>{#if thread.pinned}📌 {/if}{thread.title}</h2>
      {#if thread.locked}<span class="tag">{t('Cerrado')}</span>{/if}
      {#if session.user?.admin}
        <span class="admin">
          <button class="btn text" onclick={() => moderate({ pinned: !thread.pinned })}>{thread.pinned ? 'Desfijar' : 'Fijar'}</button>
          <button class="btn text" onclick={() => moderate({ locked: !thread.locked })}>{thread.locked ? 'Abrir' : 'Cerrar'}</button>
          <button class="btn text danger" onclick={() => confirm(t('¿Borrar el hilo entero?')) && moderate({ deleted: true })}>{t('Borrar hilo')}</button>
        </span>
      {/if}
    </header>
  {/if}

  {#if loading && !posts.length}<div class="spinner"></div>{/if}
  {#if !id && newFor}<p class="muted">{t('Aún no hay conversación sobre esta obra. Empieza tú.')}</p>{/if}

  {#each posts as p (p.id)}
    <article class="post" id="p{p.id}">
      <a class="avatar" href="/u/{encodeURIComponent(p.author)}" aria-label={p.author}>{#if p.authorAvatar}<img src={p.authorAvatar} alt="" />{:else}{p.author.slice(0, 1).toUpperCase()}{/if}</a>
      <div class="content">
        <div class="meta"><a href="/u/{encodeURIComponent(p.author)}"><b>{p.author}</b></a> <span class="muted">{when(p.createdAt)}{#if p.editedAt} {t('· editado')}{/if}</span>
          {#if p.spoilerUntil != null}<span class="tag warn">{t('Spoiler hasta el {0} {1}', [unit, p.spoilerUntil])}</span>{/if}</div>
        {#if p.hidden}
          <p class="muted hidden-note">{t('Oculto: habla de lo que pasa hasta el {0} {1} y tú aún no has llegado.', [unit, p.spoilerUntil])}</p>
        {:else if p.spoilerUntil != null && !p.own && !revealed[p.id]}
          <button class="reveal" onclick={() => (revealed[p.id] = true)}>{t('Contiene spoilers · toca para verlo')}</button>
        {:else if editingId === p.id}
          <textarea class="field" rows="5" bind:value={editText} maxlength="5000"></textarea>
          <div class="row"><button class="btn text" onclick={() => (editingId = null)}>{t('Cancelar')}</button><button class="btn primary" onclick={() => saveEdit(p)}>{t('Guardar')}</button></div>
        {:else}
          <Markdown text={p.body} />
        {/if}
        <div class="tools">
          {#each Object.entries(p.reactions) as [emoji, count] (emoji)}
            <button class="reaction" class:mine={p.mine.includes(emoji)} onclick={() => react(p, emoji)} aria-label="{emoji} {count}">{emoji} {count}</button>
          {/each}
          <details class="add-reaction">
            <summary aria-label={t('Reaccionar')}>＋</summary>
            <div class="picker">{#each REACTIONS as r (r)}<button onclick={(e) => { react(p, r); e.currentTarget.closest('details').open = false; }}>{r}</button>{/each}</div>
          </details>
          {#if !thread?.locked && !p.hidden}<button class="btn text small" onclick={() => quote(p)}>{t('Citar')}</button>{/if}
          {#if p.own || session.user?.admin}
            <button class="btn text small" onclick={() => { editingId = p.id; editText = p.body; }}>{t('Editar')}</button>
            <button class="btn text small" onclick={() => remove(p)}>{t('Borrar')}</button>
          {/if}
          {#if session.user?.admin && !p.own}<button class="btn text small" onclick={() => mute(p.author)}>{t('Silenciar')}</button>{/if}
        </div>
      </div>
    </article>
  {/each}

  {#if session.user && !session.user.guest && (!thread?.locked || session.user.admin)}
    <form class="compose" onsubmit={send}>
      <textarea class="field" rows="4" maxlength="5000" bind:this={input} bind:value={body}
        placeholder={t('Escribe... **negrita**, *cursiva*, > cita, - lista, @nombre')}></textarea>
      <div class="row">
        <label class="btn text small" title={t('Subir una imagen')}><Icon name="add" size={16} /> {t('Imagen')}<input type="file" accept="image/*" hidden onchange={addImage} /></label>
        <label class="spoiler">{t('Spoiler hasta el {0}', [unit])}
          <input class="field num" type="number" min="0" max="100000" step="0.1" inputmode="decimal" bind:value={spoiler} placeholder="—" />
        </label>
        <span class="grow"></span>
        <button class="btn primary" disabled={sending || !body.trim()}>{sending ? 'Enviando...' : id ? 'Responder' : 'Publicar'}</button>
      </div>
    </form>
  {:else if thread?.locked}
    <p class="muted">{t('Este hilo está cerrado.')}</p>
  {/if}
</div>

<style>
  .thread { display: flex; flex-direction: column; gap: 14px; }
  .head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
  .head h2 { margin: 0; font-size: var(--fs-section); font-weight: 750; flex: 1; min-width: 200px; }
  .admin { display: flex; gap: 2px; }
  .danger { color: var(--c-danger); }
  .tag { font-size: var(--fs-tiny); font-weight: 700; padding: 2px 8px; border-radius: 999px; background: var(--c-surface-high); color: var(--c-text-dim); }
  .tag.warn { background: color-mix(in srgb, var(--c-warning) 20%, transparent); color: var(--c-warning); margin-left: 6px; }
  .post { display: flex; gap: 12px; padding: 14px; border-radius: var(--r-lg); background: var(--c-surface); border: 1px solid var(--c-border); scroll-margin-top: 90px; }
  .post:target { border-color: var(--c-accent); }
  .avatar { width: 40px; height: 40px; border-radius: 50%; overflow: hidden; flex: none; display: grid; place-items: center; font-weight: 800; background: var(--c-accent-soft); color: var(--c-on-accent-soft); }
  .avatar img { width: 100%; height: 100%; object-fit: cover; }
  .content { flex: 1; min-width: 0; }
  .meta { font-size: var(--fs-small); margin-bottom: 6px; }
  .hidden-note { font-style: italic; margin: 0; }
  .reveal { padding: 8px 12px; border-radius: var(--r-md); background: var(--c-surface-high); font-size: var(--fs-small); }
  .tools { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin-top: 8px; }
  .reaction { padding: 2px 8px; border-radius: 999px; background: var(--c-surface-high); font-size: 0.85rem; border: 1px solid transparent; }
  .reaction.mine { border-color: var(--c-accent); }
  .add-reaction { position: relative; }
  .add-reaction summary { list-style: none; cursor: pointer; padding: 2px 9px; border-radius: 999px; background: var(--c-surface-high); color: var(--c-text-dim); }
  .add-reaction summary::-webkit-details-marker { display: none; }
  .picker { position: absolute; z-index: 5; bottom: 110%; left: 0; display: flex; gap: 2px; padding: 6px; border-radius: var(--r-lg); background: var(--c-surface-raised); border: 1px solid var(--c-border); box-shadow: var(--shadow-panel); }
  .picker button { font-size: 1.3rem; width: 36px; height: 36px; border-radius: 8px; }
  .picker button:hover { background: var(--c-surface-high); }
  .small { font-size: 0.8rem; height: 30px; padding: 0 8px; }
  .compose { display: flex; flex-direction: column; gap: 8px; }
  .row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
  .grow { flex: 1; }
  .spoiler { display: flex; align-items: center; gap: 6px; font-size: var(--fs-small); color: var(--c-text-dim); }
  .num { width: 90px; height: 36px; }
  label.btn { cursor: pointer; display: inline-flex; align-items: center; gap: 4px; }
</style>
