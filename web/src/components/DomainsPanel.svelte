<script>
  // Sites that moved to a new domain: the admin writes old → new and the source works again, no update needed.
  import { t } from '../lib/i18n.svelte.js';
  import { onMount } from 'svelte';
  import { api } from '../lib/api.js';
  import { notify } from '../lib/store.svelte.js';
  import Icon from './Icon.svelte';

  let list = $state([]);
  /** Domains the canary guessed (the old one no longer answers and the source works there): to confirm or dismiss. */
  let suggestions = $state([]);
  let from = $state('');
  let to = $state('');
  onMount(async () => {
    [list, suggestions] = await Promise.all([api.adminDomains().catch(() => []), api.adminDomainSuggestions().catch(() => [])]);
  });
  async function accept(s) {
    try { list = await api.adminDomainSet(s.from, s.to); suggestions = suggestions.filter((x) => x.from !== s.from); notify(t('Hecho')); } catch (err) { notify(err.message); }
  }
  async function dismiss(s) {
    try { suggestions = await api.adminDomainDismiss(s.from); } catch (err) { notify(err.message); }
  }

  async function add(e) {
    e.preventDefault();
    try { list = await api.adminDomainSet(from, to); from = ''; to = ''; notify(t('Hecho')); } catch (err) { notify(err.message); }
  }
  async function remove(host) {
    try { list = await api.adminDomainRemove(host); } catch (err) { notify(err.message); }
  }
</script>

<section class="card">
  <h3 class="sub">{t('Webs que se han mudado de dominio')}</h3>
  <p class="small muted">{t('Si una fuente deja de funcionar porque su web cambió de dirección (pasa a menudo), escribe el dominio viejo y el nuevo: el servidor irá al nuevo para todo, sin esperar a una actualización.')}</p>
  {#if suggestions.length}
    <p class="small"><strong>{t('Posibles dominios nuevos')}</strong> — {t('el viejo ya no responde y la fuente funciona en el nuevo. Compruébalo antes de aplicarlo: cualquiera puede registrar un nombre parecido.')}</p>
    {#each suggestions as s (s.from)}
      <div class="row suggestion">
        <span class="grow"><code>{s.from}</code> → <code>{s.to}</code></span>
        <a class="btn text small" href={'https://' + s.to + '/'} target="_blank" rel="noopener noreferrer">{t('Ver')}</a>
        <button class="btn tonal small" onclick={() => accept(s)}>{t('Aplicar')}</button>
        <button class="icon-btn" onclick={() => dismiss(s)} aria-label={t('Descartar')}><Icon name="close" size={18} /></button>
      </div>
    {/each}
  {/if}
  {#each list as a (a.from)}
    <div class="row">
      <span class="grow"><code>{a.from}</code> → <code>{a.to}</code></span>
      <button class="icon-btn" onclick={() => remove(a.from)} aria-label={t('Quitar')}><Icon name="delete" size={18} /></button>
    </div>
  {/each}
  <form class="add" onsubmit={add}>
    <input class="field" placeholder={t('Dominio viejo (p. ej. lectortmo.com)')} bind:value={from} autocapitalize="none" required />
    <input class="field" placeholder={t('Dominio nuevo')} bind:value={to} autocapitalize="none" required />
    <button class="btn primary" disabled={!from.trim() || !to.trim()}>{t('Guardar')}</button>
  </form>
</section>

<style>
  .row { display: flex; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px solid var(--outline-variant); }
  .suggestion { background: color-mix(in srgb, var(--primary) 8%, transparent); border-radius: 8px; padding: 6px 8px; }
  .add { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
  .add .field { flex: 1 1 180px; }
  code { font-size: 0.85rem; }
</style>
