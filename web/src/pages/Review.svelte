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
</style>
