<script>
  // Admin › Voces por personaje: short WAV samples (3-30 s) that the home worker clones with Pocket TTS, so narrators,
  // women and men in novels can each have their own voice. Only samples with a free licence or recorded by you: the
  // licence is required and shown. They appear in the readers' voice lists as "(voz clonada)".
  import { api } from '../lib/api.js';
  import { notify } from '../lib/store.svelte.js';
  import { t } from '../lib/i18n.svelte.js';

  let samples = $state([]);
  let file = $state(null);
  let name = $state('');
  let lang = $state('es');
  let gender = $state('f');
  let license = $state('');
  let source = $state('');
  let busy = $state(false);
  const load = () => api.voiceSamples().then((v) => (samples = v), (e) => notify(e.message));
  load();

  async function upload(e) {
    e.preventDefault();
    if (!file) return;
    busy = true;
    try {
      await api.addVoiceSample(file, { name, lang, gender, license, source });
      notify(t('Muestra añadida'));
      name = ''; license = ''; source = ''; file = null;
      e.target.reset();
      await load();
    } catch (err) { notify(err.message); } finally { busy = false; }
  }

  async function remove(s) {
    try { await api.deleteVoiceSample(s.id); await load(); } catch (e) { notify(e.message); }
  }
</script>

<section class="card">
  <h3 class="sub">{t('Voces por personaje')}</h3>
  <p class="muted small">{t('Muestras cortas (WAV de 3 a 30 segundos) que el servidor de casa clona para dar voz propia al narrador, a las mujeres y a los hombres. Solo con licencia libre o grabadas por vosotros.')}</p>
  <form class="add" onsubmit={upload}>
    <input class="input" type="file" accept="audio/wav,.wav" required onchange={(e) => (file = e.target.files?.[0] || null)} />
    <input class="input" bind:value={name} maxlength="40" required placeholder={t('Nombre (p. ej. Narradora)')} />
    <select class="input" bind:value={lang} aria-label={t('Idioma')}>
      <option value="es">{t('Español')}</option><option value="en">{t('Inglés')}</option><option value="zh">{t('Chino')}</option>
      <option value="fr">{t('Francés')}</option><option value="de">{t('Alemán')}</option><option value="it">{t('Italiano')}</option>
      <option value="pt">{t('Portugués')}</option>
    </select>
    <select class="input" bind:value={gender} aria-label={t('Voz')}>
      <option value="f">{t('mujer')}</option><option value="m">{t('hombre')}</option>
    </select>
    <input class="input wide" bind:value={license} maxlength="200" required placeholder={t('Licencia (p. ej. CC0, CC BY 4.0 o «grabada por nosotros»)')} />
    <input class="input wide" bind:value={source} maxlength="300" placeholder={t('De dónde sale (enlace o autor), opcional')} />
    <button class="btn primary" disabled={busy}>{t('Añadir muestra')}</button>
  </form>
  <ul class="list">
    {#each samples as s (s.id)}
      <li>
        <div class="grow">
          <strong>{s.name}</strong>
          <span class="small muted block">{s.lang.toUpperCase()} · {s.gender === 'f' ? t('mujer') : t('hombre')} · {Math.round(s.seconds)} s · {s.license}{s.source ? ' · ' + s.source : ''}</span>
        </div>
        <button class="btn text" onclick={() => remove(s)}>{t('Quitar')}</button>
      </li>
    {:else}
      <li class="muted small">{t('Aún no hay muestras.')}</li>
    {/each}
  </ul>
</section>

<style>
  .add { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0; }
  .add .input { flex: 1; min-width: 140px; }
  .add .wide { flex-basis: 100%; }
  .list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
  .list li { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-top: 1px solid var(--c-border); }
  .grow { flex: 1; min-width: 0; }
  .block { display: block; }
</style>
