<script>
  import { t } from '../lib/i18n.svelte.js';
  // On a title page: friends who have it and how far they are, your rating (1-10 + short review) with the group's
  // average, and «Recomendar» to send it to a friend with a message.
  import { api } from '../lib/api.js';
  import { notify, session } from '../lib/store.svelte.js';
  import Sheet from './Sheet.svelte';
  import Icon from './Icon.svelte';

  let { sourceId, mediaId, title = '', cover = '' } = $props();
  let friends = $state([]);
  let ratings = $state({ average: null, count: 0, ratings: [] });
  let myScore = $state('');
  let review = $state('');
  let rateOpen = $state(false);
  let recOpen = $state(false);
  let users = $state([]);
  let to = $state('');
  let message = $state('');

  const STATUS = { READING: t('va por ella'), PLAN_TO_READ: t('la tiene pendiente'), COMPLETED: t('la terminó'), ON_HOLD: t('la tiene en pausa'), DROPPED: t('la dejó'), NONE: t('la guardó') };

  async function load() {
    if (!session.user || session.user.guest) return;
    api.friendsOn(sourceId, mediaId).then((f) => (friends = f), () => {});
    try {
      ratings = await api.ratings(sourceId, mediaId);
      const mine = ratings.ratings.find((r) => r.me);
      myScore = mine ? String(mine.score) : '';
      review = mine?.review || '';
    } catch { /* ratings are optional */ }
  }
  load();

  async function saveRating(e) {
    e.preventDefault();
    try {
      await api.rate({ sourceId, mediaId, title, cover, score: myScore === '' ? null : Number(myScore), review });
      rateOpen = false;
      notify(myScore === '' ? t('Valoración quitada') : t('Valoración guardada'));
      load();
    } catch (err) { notify(err.message); }
  }

  async function openRecommend() {
    recOpen = true;
    users = (await api.users().catch(() => [])).filter((u) => !u.me);
    to = users[0]?.name || '';
  }

  async function sendRecommendation(e) {
    e.preventDefault();
    try {
      await api.recommend(to, { sourceId, mediaId, title, cover, message });
      recOpen = false;
      message = '';
      notify(t('Recomendación enviada a ') + to);
    } catch (err) { notify(err.message); }
  }

  const score = (n) => (n == null ? '' : n.toFixed(1).replace('.', ','));
</script>

{#if session.user && !session.user.guest}
  <section class="social" aria-label={t('Con tus amigos')}>
    <div class="actions">
      <button class="chip" onclick={() => (rateOpen = true)}>
        <Icon name="star" size={16} /> {myScore ? t('Tu nota: ') + myScore : 'Valorar'}
      </button>
      {#if ratings.count}<span class="avg">{t('Media del grupo')} <b>{score(ratings.average)}</b> · {ratings.count} {ratings.count === 1 ? 'nota' : 'notas'}</span>{/if}
      <button class="chip" onclick={openRecommend}><Icon name="open" size={16} /> {t('Recomendar')}</button>
    </div>
    {#if friends.length}
      <div class="friends">
        {#each friends as f (f.name)}
          <a class="friend" href="/u/{encodeURIComponent(f.name)}"><b>{f.name}</b> {STATUS[f.status] || t('la tiene')}{#if f.last} · {f.last}{/if}</a>
        {/each}
      </div>
    {/if}
    {#if ratings.ratings.some((r) => r.review)}
      <div class="reviews">
        {#each ratings.ratings.filter((r) => r.review) as r (r.name)}
          <p class="review"><b>{r.name}</b> <span class="muted">{r.score}/10</span><br />{r.review}</p>
        {/each}
      </div>
    {/if}
  </section>

  <Sheet bind:open={rateOpen} title={t('Tu valoración')}>
    <form class="form" onsubmit={saveRating}>
      <div class="scores" role="radiogroup" aria-label={t('Nota del 1 al 10')}>
        {#each Array.from({ length: 10 }, (_, i) => String(i + 1)) as n (n)}
          <button type="button" class="score" class:on={myScore === n} role="radio" aria-checked={myScore === n} onclick={() => (myScore = n)}>{n}</button>
        {/each}
      </div>
      <textarea class="field" rows="3" maxlength="500" placeholder={t('Una reseña corta (opcional)')} bind:value={review}></textarea>
      <div class="row">
        {#if myScore}<button type="button" class="btn text" onclick={() => (myScore = '')}>{t('Quitar nota')}</button>{/if}
        <button class="btn primary">{t('Guardar')}</button>
      </div>
    </form>
  </Sheet>

  <Sheet bind:open={recOpen} title="Recomendar «{title}»">
    {#if !users.length}
      <p class="muted">{t('Aún no hay nadie más en este servidor.')}</p>
    {:else}
      <form class="form" onsubmit={sendRecommendation}>
        <select class="field" bind:value={to} aria-label={t('A quién')}>
          {#each users as u (u.name)}<option value={u.name}>{u.name}</option>{/each}
        </select>
        <textarea class="field" rows="3" maxlength="280" placeholder={t('¿Por qué le va a gustar? (opcional)')} bind:value={message}></textarea>
        <button class="btn primary block">{t('Enviar')}</button>
      </form>
    {/if}
  </Sheet>
{/if}

<style>
  .social { position: relative; margin-top: 14px; display: flex; flex-direction: column; gap: 10px; }
  .actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .avg { font-size: var(--fs-small); color: var(--c-text-dim); }
  .avg b { color: var(--c-text); }
  .friends { display: flex; flex-wrap: wrap; gap: 6px; }
  .friend { font-size: 0.8rem; padding: 4px 10px; border-radius: 999px; background: var(--c-surface-high); color: var(--c-text-dim); }
  .friend b { color: var(--c-text); }
  .reviews { display: flex; flex-direction: column; gap: 8px; }
  .review { margin: 0; font-size: var(--fs-small); white-space: pre-wrap; overflow-wrap: anywhere; }
  .form { display: flex; flex-direction: column; gap: 12px; }
  .scores { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; }
  .score { height: 44px; border-radius: 12px; background: var(--c-surface-high); font-weight: 700; }
  .score.on { background: var(--c-accent); color: var(--c-on-accent); }
  .row { display: flex; justify-content: flex-end; gap: 8px; }
</style>
