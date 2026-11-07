// The interface in Spanish or English (only the interface: content in any language shows the same either way).
// Texts are written in Spanish in the code and wrapped in t(): in Spanish they come out as they are, in English they
// are looked up in shared/src/main/resources/i18n/en.json (the same dictionary the Android app uses), loaded only
// when English is chosen. A text missing from it shows in Spanish rather than breaking anything; a test makes sure
// none is missing.

export const LANGS = [{ key: 'es', label: 'Español' }, { key: 'en', label: 'English' }];
const KEY = 'yomukai.lang';

function initial() {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === 'es' || saved === 'en') return saved;
  } catch { /* private mode */ }
  const langs = navigator.languages?.length ? navigator.languages : [navigator.language || 'es'];
  // Spanish for anyone whose browser puts it before English; English for everyone else.
  for (const l of langs) {
    const base = String(l).toLowerCase().slice(0, 2);
    if (base === 'es') return 'es';
    if (base === 'en') return 'en';
  }
  return 'en';
}

export const i18n = $state({ lang: initial(), dict: null, ready: false });

/**
 * The text in the interface language. [params] fill {name} slots: t('{n} capítulos', { n: 3 }). The braces are
 * kept in the dictionary so word order can change between languages.
 */
export function t(es, params) {
  let s = (i18n.lang !== 'es' && i18n.dict?.[es]) || es;
  // Named slots ({n}) from an object, or numbered ones ({0}, {1}) from an array.
  if (params) s = s.replace(/\{(\w+)\}/g, (m, k) => String(params[k] ?? m));
  return s;
}

/** Plural-aware: tn(n, '{n} capítulo', '{n} capítulos'). */
export const tn = (n, one, many, params = {}) => t(n === 1 ? one : many, { n, ...params });

async function load(lang) {
  if (lang === 'es') { i18n.dict = null; return; }
  const mod = await import('../i18n/en.json');
  i18n.dict = mod.default;
}

export async function setLang(lang) {
  if (lang !== 'es' && lang !== 'en') return;
  try { localStorage.setItem(KEY, lang); } catch { /* private mode */ }
  // Reloaded so every text, also those worked out once when a page opens, comes in the new language.
  location.reload();
}

/** Before the first paint: English users get their dictionary before any text shows. */
export async function startI18n() {
  document.documentElement.lang = i18n.lang;
  try { await load(i18n.lang); } catch { i18n.lang = 'es'; }
  i18n.ready = true;
}

/** Dates and numbers in the interface language. */
export const locale = () => (i18n.lang === 'en' ? 'en-GB' : 'es-ES');

/** Marks a text as a dictionary key without translating it yet (for lists defined before the language is known). */
export const tk = (es) => es;

/** {CODE: 'Texto'} whose values come out translated each time they are read. */
export const tMap = (obj) => new Proxy(obj, { get: (o, k) => (typeof o[k] === 'string' ? t(o[k]) : o[k]) });

/** [{…, label: 'Texto'}] whose labels come out translated each time they are read. */
export const tLabels = (list) => list.map((x) => {
  const copy = { ...x };
  Object.defineProperty(copy, 'label', { get: () => t(x.label), enumerable: true });
  return copy;
});

/** [[key, 'Texto']] whose second items come out translated each time they are read. */
export const tPairs = (list) => list.map((pair) => {
  const copy = [...pair];
  Object.defineProperty(copy, 1, { get: () => t(pair[1]), enumerable: true });
  return copy;
});
