<script>
  // Admin › Catálogo de fuentes: each source with where it comes from (code in the repository, the official signed
  // JSON, an extra signed repository or the home NAS), its health, and a switch that takes it out for everyone.
  // Nothing here installs code: extra repositories are JSON descriptions signed with a key the admin trusts.
  import { api } from '../lib/api.js';
  import { notify } from '../lib/store.svelte.js';
  import { t } from '../lib/i18n.svelte.js';
  import { TYPE_NAMES } from '../lib/format.js';

  let data = $state(null);
  let busy = $state(false);
  let filter = $state('');
  const load = () => api.sourceCatalog().then((v) => (data = v), (e) => notify(e.message));
  load();

  const shown = $derived((data?.sources || []).filter((s) => !filter || s.name.toLowerCase().includes(filter.toLowerCase())));
  const installed = $derived((data?.sources || []).filter((s) => !s.hidden).length);

  async function toggle(s) {
    try {
      await api.setSourceHidden(s.id, !s.hidden);
      s.hidden = !s.hidden;
      notify(s.hidden ? t('{0} quitada para todos', [s.name]) : t('{0} instalada otra vez', [s.name]));
    } catch (e) { notify(e.message); }
  }

  async function refresh() {
    busy = true;
    try {
      const r = await api.refreshSources();
      notify(t('Fuentes al día (versión {0})', [r.officialVersion]));
      await load();
    } catch (e) { notify(e.message); } finally { busy = false; }
  }

  const healthText = (h) => (!h ? t('sin probar') : h.status === 'OK' ? t('funciona') : h.status === 'DEGRADED' ? t('caída') : t('con avisos'));
</script>

<section class="card">
  <h3 class="sub">{t('Catálogo de fuentes')}</h3>
  <p class="small muted" style="margin-top:0">{t('Quita una fuente para todos con un toque, o vuelve a ponerla. Las fuentes son código revisado en el repositorio o descripciones JSON firmadas: nunca se descarga ni se ejecuta código. Para añadir fuentes de otro repositorio firmado, pon su dirección y su clave en SOURCE_REPOS.')}</p>
  {#if !data}
    <div class="spinner"></div>
  {:else}
    <div class="bar">
      <input class="field" type="search" placeholder={t('Filtrar por nombre')} bind:value={filter} />
      <span class="small muted">{t('{0} de {1} instaladas · paquete oficial v{2}', [installed, data.sources.length, data.officialVersion])}</span>
      <button class="btn tonal" onclick={refresh} disabled={busy}>{busy ? t('Buscando...') : t('Buscar actualizaciones')}</button>
    </div>
    {#if data.rejected?.length}
      <p class="small warn">{t('Rechazadas:')} {data.rejected.join(' · ')}</p>
    {/if}
    <ul class="list">
      {#each shown as s (s.id)}
        <li class:off={s.hidden}>
          <span class="dot" class:ok={s.health?.status === 'OK'} class:bad={s.health?.status === 'DEGRADED'}></span>
          <span class="grow">
            <span class="block">{s.name} <span class="tag">{s.language}</span></span>
            <span class="small muted block">
              {s.types.map((x) => TYPE_NAMES[x] || x).join(', ')} · {t(s.format)} · {t(s.origin)}{s.version ? ` v${s.version}` : ''} · {healthText(s.health)}
            </span>
          </span>
          <label class="toggle-inline">
            <input type="checkbox" checked={!s.hidden} onchange={() => toggle(s)} aria-label={t('{0} instalada', [s.name])} />
          </label>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 10px; }
  .bar .field { flex: 1; min-width: 160px; }
  .list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
  .list li { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-top: 1px solid var(--c-border); }
  .list li.off { opacity: 0.55; }
  .grow { flex: 1; min-width: 0; }
  .block { display: block; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-text-faint); flex: none; }
  .dot.ok { background: var(--c-success); }
  .dot.bad { background: var(--c-danger); }
  .tag { font-size: var(--fs-tiny); padding: 1px 6px; border-radius: var(--r-pill); background: var(--c-surface-high); color: var(--c-text-dim); }
  .warn { color: var(--c-warning); }
  .toggle-inline input { width: 20px; height: 20px; accent-color: var(--c-accent); }
</style>
