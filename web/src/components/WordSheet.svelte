<script>
  import { t } from '../lib/i18n.svelte.js';
  // Learning mode only (long press on a word): translation of the word and of its sentence, pronunciation with the
  // browser's voice, and «Guardar» with the sentence as context and the work as source.
  import { api } from '../lib/api.js';
  import { settings, notify } from '../lib/store.svelte.js';
  import Sheet from './Sheet.svelte';
  import Icon from './Icon.svelte';

  /** pick: { word, sentence, lang, source } or null. */
  let { pick = $bindable(null) } = $props();
  let open = $state(false);
  let result = $state(null);
  let error = $state('');
  let paragraph = $state(null);
  // The offline dictionary (Wiktionary via kaikki.org, kept by the server): meanings, pronunciation and Spanish words,
  // even with the home worker off. status: ready | building | unavailable.
  let dict = $state(null);

  async function translateParagraph() {
    paragraph = '…';
    try { paragraph = (await api.translate([pick.paragraph], pick.lang, settings.learning.target)).translations[0]; } catch (e) { paragraph = e.message; }
  }

  $effect(() => {
    if (!pick) return;
    open = true;
    result = null;
    error = '';
    paragraph = null;
    dict = null;
    const current = pick;
    api.dictLookup((current.lang || '').slice(0, 2).toLowerCase(), current.word).then(
      (r) => { if (pick === current) dict = r; },
      () => { if (pick === current) dict = { status: 'unavailable', entries: [] }; }
    );
    api.translate([current.word, current.sentence], current.lang, settings.learning.target).then(
      (r) => { if (pick === current) result = r.translations; },
      (e) => { if (pick === current) error = e.status === 503 ? t('Traducir necesita el servidor de casa encendido.') : e.message; }
    );
  });
  $effect(() => { if (!open) pick = null; });

  function say() {
    if (!('speechSynthesis' in window) || !pick) return;
    const u = new SpeechSynthesisUtterance(pick.word);
    u.lang = pick.lang;
    speechSynthesis.cancel();
    speechSynthesis.speak(u);
  }

  async function save() {
    try {
      await api.saveWord({ word: pick.word, lang: pick.lang, meaning: result?.[0] || dict?.entries?.[0]?.es?.join(', ') || dict?.entries?.[0]?.glosses?.[0] || '', context: pick.sentence.slice(0, 500), source: pick.source });
      notify(t('Guardada para repasar'));
      open = false;
    } catch (e) { notify(e.message); }
  }

  const dictionary = $derived(pick ? `https://${pick.lang}.wiktionary.org/wiki/${encodeURIComponent(pick.word)}` : '#');
</script>

<Sheet bind:open title={pick?.word ?? ''}>
  {#if pick}
    <div class="word">
      <div class="row">
        <button class="btn tonal" onclick={say}><Icon name="speech" size={18} /> {t('Escuchar')}</button>
        <a class="btn text" href={dictionary} target="_blank" rel="noopener noreferrer">{t('Diccionario')}</a>
        <span class="grow"></span>
        <button class="btn primary" onclick={save}>{t('Guardar')}</button>
      </div>
      {#if dict?.entries?.length}
        <ul class="dict">
          {#each dict.entries as e, i (i)}
            <li>
              <span class="pos">{e.pos}</span>{#if e.pron}<span class="pron">{e.pron}</span>{/if}
              {#if e.es.length}<strong>{e.es.join(', ')}</strong>{/if}
              <ol>{#each e.glosses as g, j (j)}<li>{g}</li>{/each}</ol>
            </li>
          {/each}
        </ul>
      {:else if dict?.status === 'building'}
        <p class="muted">{t('Preparando el diccionario sin conexión en el servidor de casa; estará en unos minutos.')}</p>
      {/if}
      {#if error}
        <p class="muted">{error}</p>
      {:else if !result}
        <div class="spinner"></div>
      {:else}
        <p class="meaning">{result[0]}</p>
        <blockquote>{pick.sentence}</blockquote>
        <p class="muted">{result[1]}</p>
        {#if pick.paragraph && pick.paragraph !== pick.sentence}
          {#if paragraph}<p class="para">{paragraph}</p>{:else}<button class="btn text" onclick={translateParagraph}>{t('Traducir el párrafo entero')}</button>{/if}
        {/if}
      {/if}
    </div>
  {/if}
</Sheet>

<style>
  .word { display: flex; flex-direction: column; gap: 12px; }
  .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .grow { flex: 1; }
  .dict { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
  .dict ol { margin: 4px 0 0; padding-left: 20px; color: var(--c-text-muted); font-size: .92rem; }
  .pos { font-size: .75rem; text-transform: uppercase; letter-spacing: .04em; color: var(--c-accent); margin-right: 8px; }
  .pron { font-family: var(--font-mono, monospace); margin-right: 8px; }
  .meaning { font-size: 1.3rem; font-weight: 700; margin: 0; }
  .para { margin: 0; padding: 10px 12px; border-radius: var(--r-md); background: var(--c-surface-high); }
  blockquote { margin: 0; padding: 8px 12px; border-left: 3px solid var(--c-accent); background: var(--c-surface); border-radius: 0 var(--r-md) var(--r-md) 0; }
</style>
