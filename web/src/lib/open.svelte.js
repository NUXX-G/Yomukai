// Opening a recommendation: find where it can be read, and go there.
import { api } from './api.js';
import { navigate, media } from './router.svelte.js';

export const locating = $state({ open: false, title: '', searching: false, options: [], related: null });

export async function openRelated(related) {
  if (related.item) return navigate(media(related.item.sourceId, related.item.id));
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
