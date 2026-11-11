// Where "Continuar" goes in a work's page. Pure, so it is tested without the browser.

/** The chapter the main button opens and whether reading already started; same rule as the app. */
export function continueTarget(chapters, progress) {
  if (!chapters.length) return null;
  // Read in another source or language of the work (no date here) counts as touched too, the furthest first.
  const at = (c) => progress[c.id]?.updatedAt || (progress[c.id]?.read ? 1 : 0);
  const touched = chapters.filter((c) => at(c)).sort((a, b) => at(b) - at(a) || b.index - a.index);
  const last = touched[0];
  if (!last) return { chapter: chapters[0], started: false };
  if (!progress[last.id].read) return { chapter: last, started: true };
  const next = chapters.find((c) => c.index > last.index && !progress[c.id]?.read);
  return { chapter: next || last, started: true };
}
