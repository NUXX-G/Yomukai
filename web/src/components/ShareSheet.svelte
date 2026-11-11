<script>
  import { t } from '../lib/i18n.svelte.js';
  // Compartir (F4.3): a work, an episode or a chapter to a person, a room or the work's forum thread in two taps
  // (open this, tap where). Optional note; for episodes and chapters a spoiler shield, on by default.
  import { api } from '../lib/api.js';
  import { notify, features, session } from '../lib/store.svelte.js';
  import Sheet from './Sheet.svelte';
  import ShareCard from './ShareCard.svelte';

  /** card: { kind, sourceId, mediaId, chapterId?, title, cover?, type?, lang?, number?, label? } */
  let { open = $bindable(false), card } = $props();

  let note = $state('');
  let shield = $state(true);
  let people = $state(null);
  let rooms = $state([]);
  let sending = $state('');

  const canShield = $derived(card && card.kind !== 'work' && card.number != null);
  const final = $derived(card ? { ...card, spoiler: canShield && shield } : null);
  const forumOn = $derived(features.flags.forum !== false && card?.sourceId && card?.mediaId);

  $effect(() => { if (open) load(); });

  async function load() {
    note = ''; shield = true; sending = '';
    const [convos, users] = await Promise.all([
      features.flags.dms !== false ? api.dms().catch(() => []) : [],
      api.users().catch(() => [])
    ]);
    // People you talk to first, then everyone else of the group.
    const seen = new Set();
    const list = [];
    for (const c of convos || []) { if (!c.blocked && !seen.has(c.peer.name)) { seen.add(c.peer.name); list.push({ name: c.peer.name, avatar: c.peer.avatar }); } }
    for (const u of users || []) { if (!u.me && !seen.has(u.name)) { seen.add(u.name); list.push(u); } }
    people = list;
    if (features.flags.rooms !== false) {
      const [mine, active, common] = await Promise.all([
        api.roomMine().catch(() => null), api.rooms().catch(() => []),
        features.flags.open_rooms !== false ? api.roomsOpen().catch(() => []) : []
      ]);
      const byCode = new Map();
      if (mine) byCode.set(mine.code, { code: mine.code, name: t('Mi sala') });
      for (const r of [...(active || []), ...(common || [])]) if (!byCode.has(r.code)) byCode.set(r.code, { code: r.code, name: r.name });
      rooms = [...byCode.values()];
    }
  }

  async function run(key, fn, done) {
    if (sending) return;
    sending = key;
    try { await fn(); notify(done); open = false; } catch (e) { notify(e.message); } finally { sending = ''; }
  }

  const toPerson = (u) => run('u:' + u.name, () => api.dmSend(u.name, note.trim(), final), t('Enviado a {0}', [u.name]));
  const toRoom = (r) => run('r:' + r.code, () => api.roomChat(r.code, note.trim(), final), t('Compartido en {0}', [r.name]));
  const toForum = () => run('forum', async () => {
    const body = note.trim();
    const spoilerUntil = final.spoiler ? final.number : undefined;
    const w = await api.forumWork(card.sourceId, card.mediaId);
    if (w?.thread) await api.forumReply(w.thread.id, { body, card: final, spoilerUntil });
    else await api.forumNew({ category: 'work', sourceId: card.sourceId, mediaId: card.mediaId, title: card.title, body, card: final, spoilerUntil });
  }, t('Publicado en el hilo de {0}', [card.title]));

  const initial = (n) => (n || '?').slice(0, 1).toUpperCase();
</script>

<Sheet bind:open title={t('Compartir')}>
  {#if final}
    <div class="preview"><ShareCard card={final} mine /></div>
    <input class="field" bind:value={note} maxlength="1000" placeholder={t('Añade un mensaje (opcional)')} aria-label={t('Mensaje')} />
    {#if canShield}
      <label class="shield"><input type="checkbox" bind:checked={shield} /> {t('Escudo anti-spoilers: se tapa hasta que lo toquen')}</label>
    {/if}

    {#if features.flags.dms !== false && !session.user?.guest}
      <h4>{t('Mensaje directo')}</h4>
      {#if people === null}<div class="spinner"></div>{:else}
        <div class="targets">
          {#each people as u (u.name)}
            <button class="target" disabled={!!sending} onclick={() => toPerson(u)}>
              <span class="avatar">{#if u.avatar}<img src={u.avatar} alt="" />{:else}{initial(u.name)}{/if}</span>
              <span class="label">{sending === 'u:' + u.name ? t('Enviando...') : u.name}</span>
            </button>
          {:else}<p class="muted small">{t('No hay nadie más en el servidor todavía.')}</p>{/each}
        </div>
      {/if}
    {/if}

    {#if rooms.length}
      <h4>{t('Sala')}</h4>
      <div class="chips">
        {#each rooms as r (r.code)}
          <button class="chip" disabled={!!sending} onclick={() => toRoom(r)}>{sending === 'r:' + r.code ? t('Enviando...') : r.name}</button>
        {/each}
      </div>
    {/if}

    {#if forumOn}
      <h4>{t('Foro')}</h4>
      <div class="chips"><button class="chip" disabled={!!sending} onclick={toForum}>{sending === 'forum' ? t('Publicando...') : t('Hilo de {0}', [card.title])}</button></div>
    {/if}
  {/if}
</Sheet>

<style>
  .preview { margin-bottom: 10px; }
  .field { width: 100%; }
  .shield { display: flex; gap: 8px; align-items: center; margin: 10px 0 0; font-size: var(--fs-small); }
  h4 { margin: 16px 0 8px; font-size: 0.9rem; color: var(--c-text-dim); }
  .targets { display: flex; gap: 10px; overflow-x: auto; padding-bottom: 4px; scrollbar-width: thin; }
  .target { display: flex; flex-direction: column; align-items: center; gap: 4px; width: 68px; flex: none; background: none; border: 0; color: inherit; font: inherit; cursor: pointer; }
  .avatar { width: 52px; height: 52px; border-radius: 50%; overflow: hidden; display: grid; place-items: center; font-weight: 800; background: var(--c-surface-high); }
  .avatar img { width: 100%; height: 100%; object-fit: cover; }
  .target:hover .avatar, .target:focus-visible .avatar { outline: 2px solid var(--c-accent); }
  .label { font-size: 0.75rem; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .chips { display: flex; flex-wrap: wrap; gap: 8px; }
  .chip { padding: 8px 14px; border-radius: 999px; background: var(--c-surface-high); border: 1px solid var(--c-border); color: inherit; font: inherit; cursor: pointer; }
  .chip:hover { border-color: var(--c-accent); }
  .small { font-size: var(--fs-small); }
</style>
