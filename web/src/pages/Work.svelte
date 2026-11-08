<script>
  // /obras/:key?lang=es — a work, never a source: the index (built ahead by the server) says where it is in that
  // language, and its page opens at once with every source of the language joined; nothing to choose.
  import { route, navigate, media, back } from '../lib/router.svelte.js';
  import { api } from '../lib/api.js';
  import { t, i18n } from '../lib/i18n.svelte.js';
  import Icon from '../components/Icon.svelte';

  const LANG_NAMES = { es: 'Español', en: 'English', zh: '中文', ja: '日本語', ko: '한국어', fr: 'Français', de: 'Deutsch', it: 'Italiano', pt: 'Português', ru: 'Русский' };
  let error = $state('');
  let other = $state([]);

  $effect(() => {
    const key = route.params.key;
    const lang = route.query.get('lang') || i18n.lang;
    error = '';
    other = [];
    api.obraFicha(key, lang, true).then(
      (r) => {
        const first = r.sources?.[0];
        if (first) navigate(media(first.sourceId, first.mediaId) + '?obra=' + encodeURIComponent(key) + '&lang=' + r.lang, { replace: true });
        else { other = r.langs || []; error = t('Esta obra aún no tiene fuentes en este idioma.'); }
      },
      (e) => (error = e.message)
    );
  });
</script>

<div class="page work">
  {#if error}
    <div class="center">
      <p>{error}</p>
      {#each other as l (l)}
        <a class="btn tonal" href={'/obras/' + encodeURIComponent(route.params.key) + '?lang=' + l}>{t('Ver en {0}', [LANG_NAMES[l] || l.toUpperCase()])}</a>
      {/each}
      <button class="btn text" onclick={() => back('/')}><Icon name="back" size={18} /> {t('Volver')}</button>
    </div>
  {:else}
    <div class="center"><div class="spinner"></div></div>
  {/if}
</div>

<style>
  .center { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; min-height: 60vh; text-align: center; padding: 16px; }
</style>
