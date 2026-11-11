<script>
  import { t, locale } from '../lib/i18n.svelte.js';
  // /foro (categories and threads) and /foro/:id (one thread). Works' own threads live on their title pages too.
  import { api } from '../lib/api.js';
  import { route, navigate } from '../lib/router.svelte.js';
  import { session, notify, features } from '../lib/store.svelte.js';
  import ShareCard from '../components/ShareCard.svelte';
  import WorkPicker from '../components/WorkPicker.svelte';
  import Icon from '../components/Icon.svelte';
  import ForumThread from '../components/ForumThread.svelte';
  import Sheet from '../components/Sheet.svelte';

  const threadId = route.params.id ? Number(route.params.id) : null;
  const GENERAL = [['recomendaciones', t('Recomendaciones')], ['offtopic', t('Off-topic')], ['quejas', t('Quejas al admin')], ['work', t('Obras')]];
  let category = $state(route.query.get('c') || 'recomendaciones');
  let threads = $state(null);
  let creating = $state(false);
  let form = $state({ title: '', body: '', card: null });
  let picking = $state(false);

  async function load() {
    threads = null;
    try { threads = await api.forumList(category); } catch (e) { threads = []; notify(e.message); }
  }
  if (!threadId) load();

  function pick(c) {
    category = c;
    history.replaceState(history.state, '', '/foro?c=' + c);
    load();
  }

  async function create(e) {
    e.preventDefault();
    try {
      const r = await api.forumNew({ category, title: form.title, body: form.body, card: form.card || undefined });
      creating = false;
      form = { title: '', body: '', card: null };
      navigate('/foro/' + r.id);
    } catch (err) { notify(err.message); }
  }

  const when = (at) => new Date(at).toLocaleDateString(locale(), { day: 'numeric', month: 'short' });
</script>

<div class="page forum">
  {#if threadId}
    <a class="back" href="/foro">{t('← Foro')}</a>
    <ForumThread {threadId} />
  {:else}
    <header class="head">
      <h1>{t('Foro')}</h1>
      {#if session.user && !session.user.guest && category !== 'work'}<button class="btn primary" onclick={() => (creating = true)}>{t('Nuevo hilo')}</button>{/if}
    </header>
    <div class="chips" role="tablist">
      {#each GENERAL as [key, label] (key)}
        <button class="chip" role="tab" class:on={category === key} aria-selected={category === key} onclick={() => pick(key)}>{label}</button>
      {/each}
    </div>
    {#if category === 'work'}<p class="small muted">{t('Cada obra tiene su hilo en su ficha (pestaña «Foro»). Aquí salen los que tienen conversación.')}</p>{/if}
    {#if threads === null}
      <div class="spinner"></div>
    {:else if !threads.length}
      <p class="muted">{t('Aún no hay hilos aquí.')}</p>
    {:else}
      <ul class="list">
        {#each threads as t (t.id)}
          <li><a class="thread" href="/foro/{t.id}">
            <span class="title">{#if t.pinned}📌 {/if}{t.title}{#if t.locked} <span class="tag">{t('cerrado')}</span>{/if}</span>
            <span class="small muted">{t.author} · {t.posts} {t.posts === 1 ? 'mensaje' : 'mensajes'} · {when(t.lastPostAt)}</span>
          </a></li>
        {/each}
      </ul>
    {/if}
  {/if}
</div>

<Sheet bind:open={creating} title={t('Nuevo hilo')}>
  <form class="new" onsubmit={create}>
    <input class="field" placeholder={t('Título')} maxlength="120" bind:value={form.title} required />
    <textarea class="field" rows="6" maxlength="5000" placeholder={t('Escribe... **negrita**, *cursiva*, > cita, - lista, @nombre')} bind:value={form.body}></textarea>
    {#if features.flags.sharing !== false}
      {#if picking}<WorkPicker onpick={(c) => { form.card = c; picking = false; }} onclose={() => (picking = false)} />
      {:else if form.card}<div class="attached"><ShareCard card={form.card} mine /><button type="button" class="icon-btn" onclick={() => (form.card = null)} aria-label={t('Quitar la obra')}><Icon name="close" size={18} /></button></div>
      {:else}<button type="button" class="btn text" onclick={() => (picking = true)}><Icon name="search" size={16} /> {t('Compartir una obra')}</button>{/if}
    {/if}
    <button class="btn primary block" disabled={form.title.trim().length < 3 || (!form.body.trim() && !form.card)}>{t('Publicar')}</button>
  </form>
</Sheet>

<style>
  .forum { max-width: 980px; }
  .head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
  h1 { margin: 12px 0; font-size: var(--fs-title); font-weight: 800; }
  .chips { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
  .list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; }
  .thread { display: flex; flex-direction: column; gap: 4px; padding: 14px 16px; border-radius: var(--r-lg); background: var(--c-surface); border: 1px solid var(--c-border); }
  .thread:hover { border-color: var(--c-border-strong); }
  .title { font-weight: 700; }
  .tag { font-size: var(--fs-tiny); font-weight: 700; padding: 1px 7px; border-radius: 999px; background: var(--c-surface-high); color: var(--c-text-dim); }
  .back { display: inline-block; margin: 8px 0 14px; color: var(--c-text-dim); }
  .new { display: flex; flex-direction: column; gap: 10px; }
  .attached { display: flex; align-items: center; gap: 6px; }
</style>
