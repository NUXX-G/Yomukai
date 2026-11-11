<script>
  import { t } from '../lib/i18n.svelte.js';
  // A shared work, episode or chapter (F4.3), drawn from data: cover, type, language, episode/chapter and a link that
  // only ever leads inside the app. A spoiler stays covered until the reader taps it.
  import { media, reader } from '../lib/router.svelte.js';
  import Icon from './Icon.svelte';

  let { card, mine = false } = $props();
  let shown = $state(false);

  const TYPES = { ANIME: 'Anime', MANGA: 'Manga', MANHWA: 'Manhwa', MANHUA: 'Manhua', COMIC: 'Cómic', NOVEL: 'Novela' };
  const href = $derived(
    card.kind === 'work'
      ? (card.workKey ? '/obras/' + encodeURIComponent(card.workKey) : media(card.sourceId, card.mediaId))
      : reader(card.kind === 'episode' ? 'anime' : 'read', card.sourceId, card.mediaId, card.chapterId)
  );
  const what = $derived(card.kind === 'work' ? '' : card.label || (card.number != null ? (card.kind === 'episode' ? t('Ep. {0}', [card.number]) : t('Cap. {0}', [card.number])) : ''));
  const covered = $derived(card.spoiler && !mine && !shown);
</script>

{#if covered}
  <button class="card shield" onclick={() => (shown = true)} aria-label={t('Spoiler de {0}: toca para verlo', [card.title])}>
    <Icon name="eye" size={22} />
    <span><b>{t('Spoiler')}</b> · {card.title}{#if what} · {what}{/if}<br /><span class="muted small">{t('Toca para verlo')}</span></span>
  </button>
{:else}
  <a class="card" {href}>
    <span class="cover">{#if card.cover}<img src={card.cover} alt="" loading="lazy" referrerpolicy="no-referrer" />{:else}<Icon name={card.type === 'ANIME' ? 'tv' : card.type === 'NOVEL' ? 'novel' : 'book'} />{/if}</span>
    <span class="info">
      <span class="title">{card.title}</span>
      {#if what}<span class="what">{what}</span>{/if}
      <span class="tags">
        {#if card.type}<span class="tag">{t(TYPES[card.type] || card.type)}</span>{/if}
        {#if card.lang}<span class="tag">{card.lang}</span>{/if}
        {#if card.spoiler}<span class="tag warn">{t('Spoiler')}</span>{/if}
      </span>
    </span>
    <Icon name="chevronRight" size={20} />
  </a>
{/if}

<style>
  .card { display: flex; align-items: center; gap: 10px; padding: 8px; border-radius: var(--r-lg, 12px); background: var(--c-surface-high, #222); border: 1px solid var(--c-border, #333); color: inherit; text-decoration: none; max-width: 340px; width: 100%; text-align: left; font: inherit; cursor: pointer; }
  .card:hover { border-color: var(--c-accent); }
  .cover { width: 48px; height: 68px; border-radius: 8px; overflow: hidden; background: var(--c-surface, #111); display: grid; place-items: center; flex: none; }
  .cover img { width: 100%; height: 100%; object-fit: cover; }
  .info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
  .title { font-weight: 700; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; }
  .what { font-size: var(--fs-small, 0.85rem); color: var(--c-text-dim); }
  .tags { display: flex; gap: 4px; flex-wrap: wrap; }
  .tag { font-size: 0.7rem; font-weight: 700; padding: 1px 6px; border-radius: 6px; background: var(--c-surface, #111); color: var(--c-text-dim); }
  .tag.warn { color: var(--c-warning, #f5a524); }
  .shield { gap: 12px; min-height: 60px; background: repeating-linear-gradient(135deg, var(--c-surface-high, #222) 0 10px, var(--c-surface, #1a1a1a) 10px 20px); }
  .small { font-size: var(--fs-small, 0.85rem); }
</style>
