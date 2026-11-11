// Opening a recommendation: find where it can be read, and go there.
import { api } from './api.js';
import { navigate, media } from './router.svelte.js';
import { i18n } from './i18n.svelte.js';

export const locating = $state({ open: false, title: '', searching: false, options: [], related: null });

/**
 * Pedido 3 / O1: of AniList titles (related, similar…), only those in the user's sources, each with its work key so
 * it opens at once. Without the index (switched off, old server) the list is kept as it was.
 */
export async function keepInSources(list) {
  const items = (list || []).filter((r) => r.anilistId || r.item);
  const ids = items.filter((r) => r.anilistId && !r.item).map((r) => r.anilistId);
  if (!ids.length) return items;
  try {
    const { present } = await api.obrasPresentes(ids.slice(0, 200));
    return items.filter((r) => r.item || present[r.anilistId]).map((r) => (r.item ? r : { ...r, workKey: present[r.anilistId] }));
  } catch {
    return list;
  }
}

export async function openRelated(related) {
  if (related.item) return navigate(media(related.item.sourceId, related.item.id));
  if (related.workKey) return navigate('/obras/' + encodeURIComponent(related.workKey) + '?lang=' + i18n.lang);
  // The server's works index usually knows it already (by AniList id or by any of its names): open the work at
  // once, no search across sources.
  const found = await api.obraEncontrar(related, i18n.lang).catch(() => null);
  if (found?.key) return navigate('/obras/' + encodeURIComponent(found.key) + '?lang=' + i18n.lang);
  locating.open = true;
  locating.title = related.title;
  locating.searching = true;
  locating.options = [];
  locating.related = related;
  try {
    // The first source sure to have the work opens at once: nothing to choose (its page gathers the others).
    const found = await api.locateFirst(related);
    if (locating.related !== related) return;
    if (found.open) {
      locating.open = false;
      navigate(media(found.open.item.sourceId, found.open.item.id));
      return;
    }
    const options = found.options || [];
    locating.options = options;
    // A single sure place needs no question.
    if (options.length === 1 && !options[0].approximate) {
      locating.open = false;
      navigate(media(options[0].item.sourceId, options[0].item.id));
    }
  } catch {
    locating.options = [];
  } finally {
    if (locating.related === related) locating.searching = false;
  }
}
