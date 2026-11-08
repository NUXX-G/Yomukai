// Translating short texts (manga bubbles): the home worker's translator when the server has one, else the translator
// built into the browser (Chrome/Edge 138+, on the device, no server needed). Same order and length as [texts].
import { api } from './api.js';

const browserTranslators = new Map();

/** True when this browser translates by itself (and so translation works even without the home worker). */
export const browserCanTranslate = () => typeof self !== 'undefined' && 'Translator' in self;

async function inBrowser(texts, from, to) {
  if (!browserCanTranslate() || !from || from === 'auto') return null;
  const key = from + '>' + to;
  let translator = browserTranslators.get(key);
  if (!translator) {
    const available = await self.Translator.availability({ sourceLanguage: from, targetLanguage: to }).catch(() => 'unavailable');
    if (available === 'unavailable') return null;
    translator = self.Translator.create({ sourceLanguage: from, targetLanguage: to });
    browserTranslators.set(key, translator);
  }
  const t = await translator;
  return Promise.all(texts.map((x) => (x.trim() ? t.translate(x).catch(() => '') : '')));
}

/**
 * { translations } when done, { pending: true } while the worker works on it. Throws { status: 503 } only when
 * neither the server nor this browser can translate.
 */
export async function translateTexts(texts, from, to) {
  if (from && from === to) return { translations: texts };
  try {
    return await api.translate(texts, from, to);
  } catch (e) {
    if (e.status !== 503) throw e;
    const local = await inBrowser(texts, from, to).catch(() => null);
    if (local) return { translations: local };
    throw e;
  }
}
