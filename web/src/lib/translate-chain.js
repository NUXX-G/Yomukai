// Translation in every reader (F6.3). One chain, tried in order until one answers:
//   1. the offline dictionary (single words; Wiktionary kept by the server, no worker needed once built),
//   2. the translator built into the browser (Chrome/Edge 138+: on the device, nothing leaves it),
//   3. the home worker's translator (LibreTranslate through the server; may be off),
// and when none can, a link to open the text in a web translator (the reader's own choice, never automatic).
// The Android app runs the same order with ML Kit on the phone instead of the browser's translator.

/** Two-letter language code from 'es', 'es-ES', 'ES'…; '' when unknown. */
export const baseLang = (l) => (/^[a-z]{2}($|[-_])/i.test(l || '') ? l.slice(0, 2).toLowerCase() : '');

/** One word (letters, apostrophes, hyphens), short: the dictionary can answer it. */
export const isWord = (text) => /^[\p{L}\p{M}'’-]{1,40}$/u.test((text || '').trim());

/** The address of a web translator with [text] (last resort, opened by the reader). */
export const webTranslatorUrl = (text, from, to) =>
  `https://translate.google.com/?sl=${encodeURIComponent(baseLang(from) || 'auto')}&tl=${encodeURIComponent(baseLang(to) || 'es')}&text=${encodeURIComponent((text || '').slice(0, 2000))}&op=translate`;

/**
 * What the dictionary says, as a translation: in Spanish its Spanish words; in English the first English meaning
 * (Wiktionary's meanings are written in English). Null when it has nothing useful.
 */
export function fromDictionary(lookup, to) {
  const entries = lookup?.entries || [];
  if (!entries.length) return null;
  const target = baseLang(to);
  if (target === 'es') {
    const words = [...new Set(entries.flatMap((e) => e.es || []))].slice(0, 6);
    return words.length ? words.join(', ') : null;
  }
  if (target === 'en') {
    const gloss = entries.flatMap((e) => e.glosses || []).find(Boolean);
    return gloss || null;
  }
  return null;
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Builds the chain with its pieces (tests pass fakes). [server] answers { translations } or { pending: true } and
 * throws { status: 503 } when the home worker is off.
 */
export function makeChain({ dictionary, browser, server, guess = async () => '', pendingTries = 6, pendingWaitMs = 2500 }) {
  async function viaServer(texts, from, to) {
    for (let i = 0; i < pendingTries; i++) {
      let r;
      try { r = await server(texts, from || 'auto', to); } catch { return null; }
      if (r?.translations) return { list: r.translations, via: r.via === 'online' ? 'online' : 'server' };
      if (!r?.pending) return null;
      await wait(pendingWaitMs);
    }
    return null;
  }

  /**
   * Translates one text. { text, via: 'same' | 'dictionary' | 'browser' | 'server', entries } or
   * { text: null, via: null, url } when nothing could (url = the web translator).
   */
  async function one(raw, from, to) {
    const text = (raw || '').trim();
    to = baseLang(to) || 'es';
    from = baseLang(from) || (await guess(text));
    if (!text) return { text: '', via: 'same' };
    if (from && from === to) return { text, via: 'same' };
    let entries = [];
    if (isWord(text) && from) {
      const lookup = await dictionary(from, text.toLowerCase()).catch(() => null);
      entries = lookup?.entries || [];
      const found = fromDictionary(lookup, to);
      if (found) return { text: found, via: 'dictionary', entries };
    }
    const local = await browser([text], from, to).catch(() => null);
    if (local?.[0]) return { text: local[0], via: 'browser', entries };
    const remote = await viaServer([text], from, to);
    if (remote?.list?.[0]) return { text: remote.list[0], via: remote.via, entries };
    return { text: null, via: null, entries, url: webTranslatorUrl(text, from, to) };
  }

  /** Many short texts (bubbles): the browser first, then the server; same order and length as [texts]. */
  async function many(texts, from, to) {
    to = baseLang(to) || 'es';
    from = baseLang(from);
    if (from && from === to) return { translations: texts };
    const local = await browser(texts, from, to).catch(() => null);
    if (local && local.some(Boolean)) return { translations: local };
    try {
      return await server(texts, from || 'auto', to);
    } catch (e) {
      if (e?.status === 503) e.message = 'No hay traductor disponible: ni el del navegador ni el del servidor de casa';
      throw e;
    }
  }

  return { one, many };
}
