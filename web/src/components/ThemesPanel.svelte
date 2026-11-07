<script>
  import { t } from '../lib/i18n.svelte.js';
  // Ajustes › Temas: built-in themes and the group's gallery (use with one tap), your own themes, an editor with live
  // preview and WCAG contrast warnings, background upload, publish, and export/import codes.
  import { api } from '../lib/api.js';
  import { notify } from '../lib/store.svelte.js';
  import { applyTheme, rememberTheme, themeState, exportCode, importCode, EDITABLE, FONT_NAMES } from '../lib/theme.svelte.js';
  import Sheet from './Sheet.svelte';

  let data = $state(null);
  let editing = $state(false);
  let draft = $state(null);
  let draftId = $state(null);
  let published = $state(false);
  let issues = $state([]);
  let importing = $state(false);
  let code = $state('');
  let checkTimer;

  const base = { canvas: '#050505', surface: '#0f0f10', surfaceHigh: '#1d1d20', border: '#232326', text: '#f4f4f5', textDim: '#a1a1aa', accent: '#ff8a5b', onAccent: '#1f0c03', accentSoft: '#3a1d10', onAccentSoft: '#ffd9c7' };
  const NAMES = { text: t('Texto'), textDim: 'Texto secundario', textFaint: 'Texto tenue', canvas: 'fondo', surface: 'paneles', surfaceHigh: 'paneles destacados', accent: 'acento', onAccent: 'texto sobre acento', accentSoft: 'acento suave', onAccentSoft: 'texto sobre acento suave' };

  async function load() { try { data = await api.themes(); } catch (e) { notify(e.message); data = { builtIn: [], gallery: [] }; } }
  load();

  async function use(body, theme) {
    try {
      const r = await api.useTheme(body);
      applyTheme(r.theme || null);
      rememberTheme(r.theme || null);
      notify(theme ? t('Tema «{0}» puesto', [theme.name]) : t('Tema por defecto'));
    } catch (e) { notify(e.message); }
  }

  function edit(theme = null, id = null, isPublished = false) {
    draft = JSON.parse(JSON.stringify(theme || { name: 'Mi tema', colors: {}, font: 'inter', radius: 'normal', density: 'normal', coverAccent: true }));
    draft.colors = { ...draft.colors };
    draftId = id;
    published = isPublished;
    editing = true;
    preview();
  }

  /** Live preview while editing, plus the server's contrast check (a moment after the last change). */
  function preview() {
    if (!draft) return;
    applyTheme(draft);
    clearTimeout(checkTimer);
    checkTimer = setTimeout(async () => {
      try { issues = (await api.checkTheme(draft)).issues; } catch (e) { issues = [{ error: e.message }]; }
    }, 350);
  }

  // Closing the editor without saving puts back the theme in use (the preview changed the page).
  let wasEditing = false;
  $effect(() => {
    if (editing) { wasEditing = true; return; }
    if (!wasEditing) return;
    wasEditing = false;
    if (!saved) load().then(() => applyTheme(data?.current || null));
    saved = false;
  });
  let saved = false;

  async function save() {
    try {
      const r = await api.saveTheme(draftId, draft, published);
      const id = draftId || r.id;
      saved = true;
      await use({ id }, draft);
      editing = false;
      await load();
    } catch (e) { notify(e.message); }
  }

  async function remove(id) {
    if (!confirm(t('¿Borrar este tema?'))) return;
    try { await api.deleteTheme(id); await load(); } catch (e) { notify(e.message); }
  }

  async function background(e) {
    const file = e.currentTarget.files?.[0];
    e.currentTarget.value = '';
    if (!file) return;
    try { draft.background = await api.uploadBackground(file); preview(); } catch (err) { notify(err.message); }
  }

  async function copyCode(theme) {
    const c = exportCode(theme);
    try { await navigator.clipboard.writeText(c); notify(t('Código copiado: pásaselo a quien quieras')); } catch { code = c; importing = true; }
  }

  function doImport() {
    try { const t = importCode(code); importing = false; code = ''; edit(t); } catch (e) { notify(e.message); }
  }

  const swatch = (t) => ({ ...base, ...(t.colors || {}) });
  const issueText = (i) => i.error || t('{0} sobre {1}: contraste {2} (mínimo {3})', [NAMES[i.foreground] || i.foreground, NAMES[i.background] || i.background, String(i.ratio).replace('.', ','), String(i.needed).replace('.', ',')]);
</script>

{#snippet card(theme, onUse, extra)}
  {@const c = swatch(theme)}
  <div class="theme" class:on={themeState.current?.name === theme.name}>
    <button class="preview" onclick={onUse} style:background={c.canvas} style:border-color={c.border} aria-label="Usar {theme.name}">
      <span class="panel" style:background={c.surfaceHigh}></span>
      <span class="line" style:background={c.text}></span>
      <span class="line short" style:background={c.textDim}></span>
      <span class="pill" style:background={c.accent}></span>
    </button>
    <div class="name">{theme.name}</div>
    {@render extra?.()}
  </div>
{/snippet}

<div class="themes">
  {#if !data}
    <div class="spinner"></div>
  {:else}
    <div class="actions">
      <button class="btn primary" onclick={() => edit(themeState.current)}>{t('Crear tema')}</button>
      <button class="btn tonal" onclick={() => (importing = true)}>{t('Importar código')}</button>
      <button class="btn text" onclick={() => use({}, null)}>{t('Volver al de serie')}</button>
    </div>
    <h3>{t('De serie')}</h3>
    <div class="grid-themes">
      {#each data.builtIn as b (b.id)}{@render card(b.theme, () => use({ builtIn: b.id }, b.theme))}{/each}
    </div>
    {#if data.gallery.length}
      <h3>{t('De tus amigos y tuyos')}</h3>
      <div class="grid-themes">
        {#each data.gallery as g (g.id)}
          {#snippet extra()}
            <div class="small muted">{g.mine ? (g.published ? 'Tuyo · publicado' : 'Tuyo · privado') : t('De ') + g.owner}{#if g.uses} · {g.uses} {g.uses === 1 ? 'lo usa' : 'lo usan'}{/if}</div>
            <div class="mini">
              <button class="btn text" onclick={() => copyCode(g.theme)}>{t('Código')}</button>
              {#if g.mine}<button class="btn text" onclick={() => edit(g.theme, g.id, g.published)}>{t('Editar')}</button><button class="btn text" onclick={() => remove(g.id)}>{t('Borrar')}</button>{/if}
            </div>
          {/snippet}
          {@render card(g.theme, () => use({ id: g.id }, g.theme), extra)}
        {/each}
      </div>
    {/if}
  {/if}
</div>

<Sheet bind:open={editing} title={t('Editor de tema')}>
  {#if draft}
    <div class="editor">
      <label>{t('Nombre')} <input class="field" maxlength="40" bind:value={draft.name} oninput={preview} /></label>
      <div class="colors">
        {#each EDITABLE as [key, label] (key)}
          <label class="color"><input type="color" value={(draft.colors[key] || base[key] || '#000000').slice(0, 7)} oninput={(e) => { draft.colors[key] = e.currentTarget.value; preview(); }} /> {label}</label>
        {/each}
      </div>
      <div class="selects">
        <label>{t('Letra')} <select class="field" bind:value={draft.font} onchange={preview}>{#each Object.entries(FONT_NAMES) as [k, v] (k)}<option value={k}>{v}</option>{/each}</select></label>
        <label>{t('Esquinas')} <select class="field" bind:value={draft.radius} onchange={preview}><option value="sharp">{t('Rectas')}</option><option value="normal">{t('Normales')}</option><option value="round">{t('Redondas')}</option></select></label>
        <label>{t('Densidad')} <select class="field" bind:value={draft.density} onchange={preview}><option value="compact">{t('Compacta')}</option><option value="normal">{t('Normal')}</option><option value="comfy">{t('Holgada')}</option></select></label>
      </div>
      <label class="row"><input type="checkbox" bind:checked={draft.coverAccent} onchange={preview} /> {t('Teñir cada ficha con el color de su portada')}</label>
      <div class="row">
        <label class="btn tonal">{t('Imagen de fondo')}<input type="file" accept="image/*" hidden onchange={background} /></label>
        {#if draft.background}<button class="btn text" onclick={() => { draft.background = undefined; preview(); }}>{t('Quitar fondo')}</button>{/if}
      </div>
      {#if issues.length}
        <div class="issues" role="alert">
          <b>{t('Cuidado, puede leerse mal:')}</b>
          <ul>{#each issues as i, n (n)}<li>{issueText(i)}</li>{/each}</ul>
        </div>
      {:else}
        <p class="ok small">{t('Contraste correcto (WCAG AA).')}</p>
      {/if}
      <label class="row"><input type="checkbox" bind:checked={published} /> {t('Publicarlo para mis amigos')}</label>
      <div class="row end">
        <button class="btn text" onclick={() => copyCode(draft)}>{t('Copiar código')}</button>
        <button class="btn primary" onclick={save}>{t('Guardar y usar')}</button>
      </div>
    </div>
  {/if}
</Sheet>

<Sheet bind:open={importing} title={t('Importar un tema')}>
  <textarea class="field" rows="4" placeholder={t('Pega aquí el código (empieza por YMKT1:)')} bind:value={code}></textarea>
  <button class="btn primary block" style="margin-top:10px" disabled={!code.trim()} onclick={doImport}>{t('Abrir en el editor')}</button>
</Sheet>

<style>
  .themes h3 { margin: 18px 0 10px; font-size: 1rem; }
  .actions { display: flex; flex-wrap: wrap; gap: 8px; }
  .grid-themes { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; }
  .theme { display: flex; flex-direction: column; gap: 4px; }
  .preview { position: relative; height: 92px; border-radius: var(--r-lg); border: 2px solid; overflow: hidden; display: block; width: 100%; }
  .theme.on .preview { outline: 3px solid var(--c-accent); outline-offset: 2px; }
  .panel { position: absolute; left: 10px; right: 10px; top: 10px; height: 34px; border-radius: 6px; }
  .line { position: absolute; left: 18px; top: 18px; width: 60%; height: 6px; border-radius: 3px; }
  .line.short { top: 30px; width: 40%; }
  .pill { position: absolute; left: 10px; bottom: 10px; width: 46px; height: 16px; border-radius: 999px; }
  .name { font-weight: 650; font-size: 0.9rem; }
  .mini { display: flex; flex-wrap: wrap; }
  .mini .btn { height: 28px; padding: 0 6px; font-size: 0.78rem; }
  .editor { display: flex; flex-direction: column; gap: 14px; }
  .editor label { display: flex; flex-direction: column; gap: 4px; font-size: var(--fs-small); color: var(--c-text-dim); }
  .colors { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 8px; }
  .color { flex-direction: row !important; align-items: center; gap: 8px !important; }
  .color input { width: 40px; height: 32px; border: 0; padding: 0; background: none; }
  .selects { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 8px; }
  .row { display: flex; flex-direction: row !important; align-items: center; gap: 10px; flex-wrap: wrap; }
  .row.end { justify-content: flex-end; }
  label.btn { cursor: pointer; }
  .issues { padding: 10px 12px; border-radius: var(--r-md); background: color-mix(in srgb, var(--c-warning) 16%, transparent); color: var(--c-text); font-size: var(--fs-small); }
  .issues ul { margin: 6px 0 0; padding-left: 1.2em; }
  .ok { color: var(--c-success); margin: 0; }
</style>
