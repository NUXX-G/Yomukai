<script>
  import { t } from '../lib/i18n.svelte.js';
  // A word or a phrase picked in any reader (selection, long press or right click): its translation through the
  // whole chain (offline dictionary, this browser, the home worker; a web translator link when none can), the
  // dictionary's meanings for single words, its sentence translated too, pronunciation with the browser's voice and,
  // in learning mode, «Guardar» with the sentence as context.
  import { api } from '../lib/api.js';
  import { translateOne } from '../lib/translate.js';
  import { settings, notify } from '../lib/store.svelte.js';
  import Sheet from './Sheet.svelte';
  import Icon from './Icon.svelte';

  /** pick: { word, sentence, paragraph, lang, source, phrase } or null. */
  let { pick = $bindable(null) } = $props();
  let open = $state(false);
  let main = $state(null);
  let sentence = $state(null);
  let paragraph = $state(null);
  // The offline dictionary (Wiktionary via kaikki.org, kept by the server): meanings, pronunciation and Spanish words.
  let dict = $state(null);

  const target = () => settings.learning?.target || 'es';
  const VIA = { dictionary: 'diccionario sin conexión', browser: 'traductor del navegador', server: 'servidor de casa', online: 'traductor en línea (MyMemory)', same: 'mismo idioma' };

  async function translateParagraph() {
    paragraph = { text: '…' };
    paragraph = await translateOne(pick.paragraph, pick.lang, target());
  }

  $effect(() => {
    if (!pick) return;
    open = true;
    main = null;
    sentence = null;
    paragraph = null;
    dict = null;
    const current = pick;
    if (!current.phrase) {
      api.dictLookup((current.lang || '').slice(0, 2).toLowerCase(), current.word).then(
        (r) => { if (pick === current) dict = r; },
        () => { if (pick === current) dict = { status: 'unavailable', entries: [] }; }
      );
    }
    translateOne(current.word, current.lang, target()).then((r) => { if (pick === current) main = r; });
    if (current.sentence && current.sentence !== current.word) {
      translateOne(current.sentence, current.lang, target()).then((r) => { if (pick === current) sentence = r; });
    }
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
      await api.saveWord({ word: pick.word, lang: pick.lang, meaning: main?.text || dict?.entries?.[0]?.es?.join(', ') || dict?.entries?.[0]?.glosses?.[0] || '', context: (pick.sentence || pick.word).slice(0, 500), source: pick.source });
      notify(t('Guardada para repasar'));
      open = false;
    } catch (e) { notify(e.message); }
  }

  const dictionary = $derived(pick && !pick.phrase ? `https://${(pick.lang || 'es').slice(0, 2)}.wiktionary.org/wiki/${encodeURIComponent(pick.word)}` : '');
</script>

<Sheet bind:open title={pick?.phrase ? t('Traducción') : (pick?.word ?? '')}>
  {#if pick}
    <div class="word">
      {#if pick.phrase}<blockquote>{pick.word}</blockquote>{/if}
      {#if !main}
        <div class="spinner" aria-label={t('Traduciendo…')}></div>
      {:else if main.text}
        <p class="meaning" lang={settings.learning?.target || 'es'}>{main.text}</p>
        <p class="via">{t('Con: {0}', [t(VIA[main.via] || main.via)])}</p>
      {:else}
        <p class="muted">{t('Aquí no hay traductor disponible: ni el diccionario, ni el del navegador, ni el servidor de casa.')}</p>
        <a class="btn tonal" href={main.url} target="_blank" rel="noopener noreferrer">{t('Abrir en el traductor de Google')}</a>
      {/if}
      <div class="row">
        <button class="btn tonal" onclick={say}><Icon name="speech" size={18} /> {t('Escuchar')}</button>
        {#if dictionary}<a class="btn text" href={dictionary} target="_blank" rel="noopener noreferrer">{t('Diccionario')}</a>{/if}
        <span class="grow"></span>
        {#if settings.learning?.enabled}<button class="btn primary" onclick={save}>{t('Guardar')}</button>{/if}
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
      {#if pick.sentence && pick.sentence !== pick.word}
        <blockquote>{pick.sentence}</blockquote>
        {#if sentence?.text}<p class="muted">{sentence.text}</p>{:else if !sentence}<p class="muted">…</p>{/if}
      {/if}
      {#if pick.paragraph && pick.paragraph !== pick.sentence && pick.paragraph !== pick.word}
        {#if paragraph?.text}<p class="para">{paragraph.text}</p>{:else if !paragraph}<button class="btn text" onclick={translateParagraph}>{t('Traducir el párrafo entero')}</button>{/if}
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
  .meaning { font-size: 1.3rem; font-weight: 700; margin: 0; white-space: pre-line; overflow-wrap: anywhere; }
  .via { margin: -6px 0 0; font-size: var(--fs-tiny); color: var(--c-text-faint); }
  .para { margin: 0; padding: 10px 12px; border-radius: var(--r-md); background: var(--c-surface-high); }
  blockquote { margin: 0; padding: 8px 12px; border-left: 3px solid var(--c-accent); background: var(--c-surface); border-radius: 0 var(--r-md) var(--r-md) 0; }
</style>
