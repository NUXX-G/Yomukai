<script>
  import { t } from '../lib/i18n.svelte.js';
  // /amigos: everyone on this server (the group), what they are watching or reading right now, and their profiles.
  import { api } from '../lib/api.js';
  import { media } from '../lib/router.svelte.js';

  let users = $state(null);
  api.users().then((u) => (users = u), () => (users = []));
  const sorted = $derived([...(users || [])].sort((a, b) => (b.now ? 1 : 0) - (a.now ? 1 : 0)));
</script>

<div class="page">
  <h1>{t('Amigos')}</h1>
  <p class="muted">{t('Todos los que usan este servidor. Lo que tengan oculto en su perfil no aparece aquí.')}</p>
  {#if users === null}
    <div class="spinner"></div>
  {:else}
    <div class="list">
      {#each sorted as u (u.name)}
        <a class="friend" href="/u/{encodeURIComponent(u.name)}" style:--accent={u.accent || 'var(--c-accent)'}>
          <span class="avatar" aria-hidden="true">{#if u.avatar}<img src={u.avatar} alt="" />{:else}{u.name.slice(0, 1).toUpperCase()}{/if}</span>
          <span class="grow">
            <span class="name">{u.name}{#if u.me} <span class="muted">{t('(tú)')}</span>{/if}</span>
            {#if u.now}
              <span class="now"><span class="dot"></span>{u.now.kind === 'watching' ? 'Viendo' : 'Leyendo'} {u.now.title}{#if u.now.label} · {u.now.label}{/if}</span>
            {/if}
          </span>
        </a>
        {#if u.now?.sourceId}<a class="sr-only" href={media(u.now.sourceId, u.now.mediaId)}>{t('Abrir {0}', [u.now.title])}</a>{/if}
      {/each}
    </div>
  {/if}
</div>

<style>
  h1 { margin: 12px 0 4px; font-size: var(--fs-title); font-weight: 800; }
  .list { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; margin-top: 18px; }
  .friend { display: flex; gap: 12px; align-items: center; padding: 12px; border-radius: var(--r-lg); background: var(--c-surface); border: 1px solid var(--c-border); }
  .friend:hover { border-color: var(--accent); }
  .avatar { width: 48px; height: 48px; border-radius: 50%; overflow: hidden; display: grid; place-items: center; font-weight: 800; background: color-mix(in srgb, var(--accent) 40%, var(--c-surface-high)); flex: none; }
  .avatar img { width: 100%; height: 100%; object-fit: cover; }
  .grow { flex: 1; min-width: 0; display: flex; flex-direction: column; }
  .name { font-weight: 700; }
  .now { display: flex; align-items: center; gap: 6px; font-size: var(--fs-small); color: var(--c-text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-success); flex: none; }
  .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
</style>
