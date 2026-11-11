// Which episode "Siguiente" opens. A work can be spread over several sources (the user's main one plus its sisters,
// like in the work's page): the next episode is the next NUMBER of the work, from this source when it has it and
// from a sister source when only that one does.

const n = (x) => Math.round(Number(x) * 10) / 10;

/**
 * @param current  the episode playing ({ id, index, number })
 * @param own      this source's episodes ({ id, index, number, ... })
 * @param others   the same work's episodes in other sources ({ chapterId, sourceId, mediaId, number, ... })
 * @param skip     (episode) => true for episodes to skip (filler, when the user asked)
 * @returns { episode, sourceId?, mediaId? } or null; sourceId/mediaId only when it is in another source.
 */
export function nextEpisode(current, own = [], others = [], skip = () => false) {
  if (!current) return null;
  const at = n(current.number);
  const numbered = Number.isFinite(at) && at > 0;
  // Unnumbered lists (specials, movies) can only go by the source's order.
  if (!numbered) {
    const plain = own.filter((c) => c.index > current.index).sort((a, b) => a.index - b.index);
    const pick = plain.find((c) => !skip(c)) || plain[0];
    return pick ? { episode: pick } : null;
  }
  const ahead = own.filter((c) => n(c.number) > at || (n(c.number) === at && c.index > current.index));
  const ownNumbers = new Set(own.map((c) => n(c.number)));
  const foreign = others.filter((c) => c.number > 0 && n(c.number) > at && !ownNumbers.has(n(c.number)) && c.chapterId && c.mediaId);
  const all = [
    ...ahead.map((c) => ({ key: n(c.number), order: c.index, item: { episode: c } })),
    ...foreign.map((c) => ({ key: n(c.number), order: Infinity, item: { episode: { ...c, id: c.chapterId }, sourceId: c.sourceId, mediaId: c.mediaId } }))
  ].sort((a, b) => a.key - b.key || a.order - b.order);
  const pick = all.find((x) => !skip(x.item.episode)) || all[0];
  return pick ? pick.item : null;
}
