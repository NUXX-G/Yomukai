<script>
  import { t } from '../lib/i18n.svelte.js';
  // /repaso: today's spaced-repetition session (FSRS on the server). The word, then the answer and four buttons.
  import { api } from '../lib/api.js';
  import { notify } from '../lib/store.svelte.js';

  let due = $state(null);
  let total = $state(0);
  let shown = $state(false);
  let done = $state(0);
  const LEVELS = { NEW: 'Nueva', LEARNING: 'Aprendiendo', KNOWN: 'Sabida' };

  async function load() {
    try {
      due = await api.wordsDue();
      total = (await api.words()).length;
    } catch (e) { due = []; notify(e.message); }
  }
  load();

  // Shared decks (snapshots of someone's words, to copy) and this week's ranking of reviews, among this server's people.
  let decks = $state([]);
  let ranking = $state([]);
  let deckName = $state('');
  let deckLang = $state('en');
  async function loadShared() {
    [decks, ranking] = await Promise.all([api.decks().catch(() => []), api.wordRanking().catch(() => [])]);
  }
  loadShared();
  async function publish() {
    try { await api.publishDeck(deckName, deckLang); deckName = ''; notify(t('Mazo compartido')); loadShared(); } catch (e) { notify(e.message); }
  }
  async function copyDeck(d) {
    try { const r = await api.importDeck(d.id); notify(t('{0} palabras nuevas para repasar', [r.added])); load(); } catch (e) { notify(e.message); }
  }
  async function removeDeck(d) {
    try { await api.deleteDeck(d.id); loadShared(); } catch (e) { notify(e.message); }
  }

  const card = $derived(due?.[0]);

  async function grade(g) {
    try {
      await api.reviewWord(card.id, g);
      done++;
      shown = false;
      due = due.slice(1);
    } catch (e) { notify(e.message); }
  }

  function key(e) {
    if (!card) return;
    if (!shown && (e.key === ' ' || e.key === 'Enter')) { e.preventDefault(); shown = true; return; }
    if (shown && ['1', '2', '3', '4'].includes(e.key)) grade(['again', 'hard', 'good', 'easy'][Number(e.key) - 1]);
  }
</script>

<svelte:window onkeydown={key} />

<div class="page review">
  <header class="head">
    <h1>{t('Repaso')}</h1>
    <a class="btn text" href="/api/words/anki" download>{t('Exportar a Anki')}</a>
  </header>
  {#if due === null}
    <div class="spinner"></div>
  {:else if !card}
    <div class="empty">
      <p class="big">{done ? t('¡Hecho! {0} {1} hoy.', [done, done === 1 ? 'palabra repasada' : 'palabras repasadas']) : t('Nada que repasar ahora mismo.')}</p>
      <p class="muted">{t('Tienes {0} {1}. Guarda más con una pulsación larga mientras lees (con el modo aprendizaje activado en Ajustes).', [total, total === 1 ? 'palabra guardada' : 'palabras guardadas'])}</p>
    </div>
  {:else}
    <p class="small muted">{t('Quedan {0} · {1}', [due.length, LEVELS[card.level] || ''])}</p>
    <div class="card" role="group" aria-label={t('Tarjeta')}>
      <div class="word">{card.word}</div>
      {#if card.context}<p class="context">«{card.context}»</p>{/if}
      {#if shown}
        <div class="answer">{card.meaning || t('Sin traducción guardada')}</div>
        {#if card.source}<div class="small muted">{t('De {0}', [card.source])}</div>{/if}
      {/if}
    </div>
    {#if !shown}
      <button class="btn primary block" onclick={() => (shown = true)}>{t('Ver respuesta')}</button>
    {:else}
      <div class="grades">
        <button class="btn grade again" onclick={() => grade('again')}>{t('Otra vez')}<span>1</span></button>
        <button class="btn grade" onclick={() => grade('hard')}>{t('Difícil')}<span>2</span></button>
        <button class="btn grade good" onclick={() => grade('good')}>{t('Bien')}<span>3</span></button>
        <button class="btn grade" onclick={() => grade('easy')}>{t('Fácil')}<span>4</span></button>
      </div>
    {/if}
  {/if}

  <section class="shared">
    <h2>{t('Ranking de la semana')}</h2>
    {#if ranking.length}
      <ol class="rank">{#each ranking as r, i (r.name)}<li><span class="pos">{i + 1}</span><span class="who">{r.name}</span><span class="muted">{t('{0} repasos', [r.reviews])}</span></li>{/each}</ol>
    {:else}<p class="muted">{t('Nadie ha repasado esta semana todavía.')}</p>{/if}

    <h2>{t('Mazos compartidos')}</h2>
    <form class="publish" onsubmit={(e) => { e.preventDefault(); publish(); }}>
      <input class="input" bind:value={deckName} maxlength="80" placeholder={t('Nombre del mazo')} required />
      <select class="input" bind:value={deckLang} aria-label={t('Idioma')}>
        <option value="en">{t('Inglés')}</option><option value="zh">{t('Chino')}</option><option value="ja">{t('Japonés')}</option>
        <option value="ko">{t('Coreano')}</option><option value="fr">{t('Francés')}</option><option value="de">{t('Alemán')}</option>
        <option value="it">{t('Italiano')}</option><option value="pt">{t('Portugués')}</option><option value="ru">{t('Ruso')}</option>
      </select>
      <button class="btn tonal">{t('Compartir mis palabras')}</button>
    </form>
    {#each decks as d (d.id)}
      <div class="deck">
        <div class="grow"><strong>{d.name}</strong><div class="small muted">{d.owner} · {d.lang.toUpperCase()} · {t('{0} palabras', [d.size])}</div></div>
        {#if d.mine}<button class="btn text" onclick={() => removeDeck(d)}>{t('Quitar')}</button>{:else}<button class="btn primary" onclick={() => copyDeck(d)}>{t('Copiar')}</button>{/if}
      </div>
    {:else}<p class="muted">{t('Aún no hay mazos compartidos.')}</p>{/each}
  </section>
</div>

<style>
  .review { max-width: 640px; }
  .head { display: flex; align-items: center; justify-content: space-between; }
  h1 { margin: 12px 0; font-size: var(--fs-title); font-weight: 800; }
  .card { padding: 28px 22px; border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border); text-align: center; margin: 12px 0 16px; }
  .word { font-size: 2.2rem; font-weight: 800; }
  .context { color: var(--c-text-dim); font-style: italic; margin: 12px 0 0; }
  .answer { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--c-border); font-size: 1.3rem; font-weight: 650; color: var(--c-accent); }
  .grades { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
  .grade { flex-direction: column; height: 56px; background: var(--c-surface-high); }
  .grade span { font-size: var(--fs-tiny); color: var(--c-text-faint); }
  .grade.again { color: var(--c-danger); }
  .grade.good { color: var(--c-success); }
  .empty { padding: 30px 0; }
  .big { font-size: 1.2rem; font-weight: 700; }
  .shared { margin-top: 36px; display: flex; flex-direction: column; gap: 10px; }
  h2 { font-size: 1.1rem; font-weight: 750; margin: 14px 0 4px; }
  .rank { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
  .rank li { display: flex; gap: 12px; align-items: center; padding: 8px 12px; border-radius: var(--r-md); background: var(--c-surface); }
  .pos { font-weight: 800; color: var(--c-accent); width: 1.5em; }
  .who { flex: 1; font-weight: 650; }
  .publish { display: flex; flex-wrap: wrap; gap: 8px; }
  .publish .input:first-child { flex: 1; min-width: 160px; }
  .deck { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: var(--r-md); background: var(--c-surface); }
  .grow { flex: 1; min-width: 0; }
</style>
