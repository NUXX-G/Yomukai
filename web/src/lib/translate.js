// Translation in every reader (F6.3): the chain of translate-chain.js wired to the dictionary, this browser's
// translator and the server.
import { api } from './api.js';
import { makeChain, baseLang } from './translate-chain.js';

export { baseLang, isWord, webTranslatorUrl } from './translate-chain.js';

const browserTranslators = new Map();

/** True when this browser translates by itself (and so translation works even without the home worker). */
export const browserCanTranslate = () => typeof self !== 'undefined' && 'Translator' in self;

/** The language of [text] as the browser guesses it (Chrome's LanguageDetector), or ''. */
async function detect(text) {
  try {
    if (typeof self === 'undefined' || !('LanguageDetector' in self)) return '';
    const d = await self.LanguageDetector.create();
    const [best] = await d.detect(text);
    return best && best.confidence > 0.5 ? baseLang(best.detectedLanguage) : '';
  } catch { return ''; }
}

/** [promise], or [fallback] after [ms] (the browser's translator may sit forever downloading its model). */
const within = (ms, promise, fallback) => Promise.race([promise, new Promise((r) => setTimeout(() => r(fallback), ms))]);

async function inBrowser(texts, from, to) {
  if (!browserCanTranslate() || !from) return null;
  const key = from + '>' + to;
  let entry = browserTranslators.get(key);
  if (!entry) {
    const available = await within(3000, self.Translator.availability({ sourceLanguage: from, targetLanguage: to }).catch(() => 'unavailable'), 'unavailable');
    if (available === 'unavailable') return null;
    // 'downloadable' / 'downloading': the model downloads in the background; meanwhile the chain goes on.
    entry = { ready: false, promise: self.Translator.create({ sourceLanguage: from, targetLanguage: to }) };
    entry.promise.then(() => (entry.ready = true), () => browserTranslators.delete(key));
    browserTranslators.set(key, entry);
  }
  const t = entry.ready ? await entry.promise : await within(4000, entry.promise, null);
  if (!t) return null;
  return Promise.all(texts.map((x) => (x.trim() ? within(15000, t.translate(x), '').catch(() => '') : '')));
}

const chain = makeChain({
  dictionary: (lang, word) => api.dictLookup(lang, word),
  browser: inBrowser,
  server: (texts, from, to) => api.translate(texts, from, to),
  guess: detect
});

/** One word or phrase through the whole chain (see [makeChain]). */
export const translateOne = (text, from, to) => chain.one(text, from, to);

/** Bubbles of a page: { translations } when done, { pending: true } while the worker works on it. */
export const translateTexts = (texts, from, to) => chain.many(texts, from, to);
