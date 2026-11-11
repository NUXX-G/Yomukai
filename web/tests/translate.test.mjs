import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeChain, fromDictionary, isWord, baseLang, webTranslatorUrl } from '../src/lib/translate-chain.js';

// F6.3: dictionary → browser → home worker → web translator link, never stuck on one that is off.
const off = async () => { const e = new Error('off'); e.status = 503; throw e; };
const none = async () => null;

test('words come from the offline dictionary first', async () => {
  const calls = [];
  const chain = makeChain({
    dictionary: async (lang, w) => { calls.push(['dict', lang, w]); return { entries: [{ es: ['perro', 'can'], glosses: ['dog'] }] }; },
    browser: async () => { calls.push(['browser']); return ['NO']; },
    server: off
  });
  const r = await chain.one('Dog', 'en-US', 'es');
  assert.equal(r.text, 'perro, can');
  assert.equal(r.via, 'dictionary');
  assert.deepEqual(calls, [['dict', 'en', 'dog']]);
});

test('phrases skip the dictionary and use the browser, then the worker', async () => {
  const chain = makeChain({ dictionary: async () => { throw new Error('not for phrases'); }, browser: async (t) => t.map(() => 'hola mundo'), server: off });
  assert.deepEqual(await chain.one('hello world', 'en', 'es'), { text: 'hola mundo', via: 'browser', entries: [] });
  const worker = makeChain({ dictionary: none, browser: none, server: async (t, from, to) => ({ translations: t.map((x) => `${x}:${from}>${to}`) }) });
  assert.equal((await worker.one('hello world', 'en', 'es')).text, 'hello world:en>es');
});

test('the worker still working is waited for a moment', async () => {
  let n = 0;
  const chain = makeChain({ dictionary: none, browser: none, server: async () => (++n < 3 ? { pending: true } : { translations: ['listo'] }), pendingWaitMs: 1 });
  assert.equal((await chain.one('ready now', 'en', 'es')).text, 'listo');
  assert.equal(n, 3);
});

test('when nothing can, a web translator link instead of an error', async () => {
  const chain = makeChain({ dictionary: async () => ({ entries: [] }), browser: none, server: off, pendingWaitMs: 1 });
  const r = await chain.one('こんにちは', 'ja', 'es');
  assert.equal(r.text, null);
  assert.ok(r.url.startsWith('https://translate.google.com/?sl=ja&tl=es&text=%E3%81%93'));
});

test('same language or unknown language guessed', async () => {
  const chain = makeChain({ dictionary: none, browser: async (t, from) => [from], server: off, guess: async () => 'fr' });
  assert.deepEqual(await chain.one('Hola', 'es', 'es'), { text: 'Hola', via: 'same' });
  assert.equal((await chain.one('bonjour tout le monde', '', 'es')).text, 'fr');
});

test('bubbles: browser first, then the server, which may answer pending', async () => {
  const browserOnly = makeChain({ dictionary: none, browser: async (t) => t.map((x) => x.toUpperCase()), server: off });
  assert.deepEqual(await browserOnly.many(['a', 'b'], 'en', 'es'), { translations: ['A', 'B'] });
  const pending = makeChain({ dictionary: none, browser: none, server: async () => ({ pending: true }) });
  assert.deepEqual(await pending.many(['a'], 'ja', 'es'), { pending: true });
  const nothing = makeChain({ dictionary: none, browser: none, server: off });
  await assert.rejects(nothing.many(['a'], 'ja', 'es'), (e) => e.status === 503);
});

test('helpers', () => {
  assert.equal(baseLang('PT-br'), 'pt');
  assert.equal(baseLang('auto'), '');
  assert.equal(baseLang('es_ES'), 'es');
  assert.ok(isWord("l'amour") && isWord('señor') && !isWord('two words') && !isWord('a1'));
  assert.equal(fromDictionary({ entries: [{ es: [], glosses: ['a dog'] }] }, 'en'), 'a dog');
  assert.equal(fromDictionary({ entries: [{ es: [], glosses: ['a dog'] }] }, 'es'), null);
  assert.ok(webTranslatorUrl('x'.repeat(5000), 'en', 'es').length < 2200);
});

test('the server says whether the home worker or the online translator answered', async () => {
  const chain = makeChain({ dictionary: none, browser: none, server: async () => ({ translations: ['hola mundo'], via: 'online' }) });
  assert.deepEqual(await chain.one('hello world', 'en', 'es'), { text: 'hola mundo', via: 'online', entries: [] });
});
