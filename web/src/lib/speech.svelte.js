// Reading novels aloud.
//  - Natural voices: Piper neural voices running on the Yomukai server (woman, man, accents). The
//    next paragraphs are prepared while one is read, so there are no gaps between them.
//  - Browser voices: the system's own, best ones first (Edge's "Natural", Safari's "Premium/Mejorada",
//    Google's). Used when the server has no natural voices, or if one fails to answer.
// With "voices by character", dialogue is read by another voice than the narration: a woman's when
// the text says a woman speaks ("—dijo ella", "she said"...), a man's when a man does.

import { settings } from './store.svelte.js';
import { t } from './i18n.svelte.js';
import { kokoroVoices, kokoroSpeak, KOKORO_PREFIX } from './kokoro.svelte.js';

export const speechVoices = $state({ loaded: false, natural: false, server: [], browser: [] });

let loading;
/** Loads which voices exist: the server's natural ones and the browser's. */
export function loadVoices() {
  loading ??= (async () => {
    try {
      const r = await fetch('/api/tts/voices', { credentials: 'include' });
      if (r.ok) {
        const j = await r.json();
        speechVoices.natural = !!j.available;
        speechVoices.server = j.voices || [];
      }
    } catch {}
    // Natural English voices made in this browser (Kokoro): they need neither the home server nor the VPS's. They are
    // slower than real time on most devices, so they are offered to pick (great for hearing English words), never
    // chosen by themselves.
    try {
      const k = await kokoroVoices();
      if (k.length) {
        speechVoices.server = [...speechVoices.server, ...k];
        const chosen = Object.values(settings.novel.voices || {}).some((roles) => Object.values(roles || {}).some((v) => String(v).startsWith(KOKORO_PREFIX)));
        if (chosen) speechVoices.natural = true;
      }
    } catch {}
    const browser = () => (speechVoices.browser = typeof speechSynthesis === 'undefined' ? [] : [...speechSynthesis.getVoices()].sort((a, b) => browserScore(b) - browserScore(a)));
    browser();
    if (typeof speechSynthesis !== 'undefined') speechSynthesis.addEventListener?.('voiceschanged', browser);
    speechVoices.loaded = true;
  })();
  return loading;
}

/** How natural a browser voice usually sounds, from its name: neural/online ones first. */
export function browserScore(v) {
  const n = v.name.toLowerCase();
  let s = 0;
  if (/natural|neural|online/.test(n)) s += 5;
  if (/premium|enhanced|mejorad|siri/.test(n)) s += 4;
  if (/google/.test(n)) s += 3;
  if (!v.localService) s += 1;
  if (/espeak|compact|eloquence|novelty|bad news|bells|bubbles|cellos|whisper|zarvox|trinoids|albert|jester|organ|superstar|wobble|boing/.test(n)) s -= 6;
  return s;
}

export const DEFAULT_NATURAL = {
  es: { narrator: 'es-david', female: 'es-lucia', male: 'es-sergio' },
  en: { narrator: 'en-lessac', female: 'en-amy', male: 'en-ryan' }
};

/** The voice settings for a language, with the defaults filled in. */
export function voiceSettings(lang) {
  const saved = settings.novel.voices?.[lang] || {};
  return { ...DEFAULT_NATURAL[lang], ...saved };
}

export function setVoice(lang, role, value) {
  settings.novel.voices = { ...(settings.novel.voices || {}), [lang]: { ...(settings.novel.voices?.[lang] || {}), [role]: value } };
  // Picking a browser-made voice turns natural voices on even without a server that has any.
  if (String(value).startsWith(KOKORO_PREFIX)) speechVoices.natural = true;
}

// The text rules (who speaks, what is heard, pieces) live in dialogue.js: plain functions shared with the tests and
// kept identical to shared/…/speech/SpeechPlan.kt, so audio prepared by the server is the audio the reader asks for.
export { genderHint, splitDialogue, speakable, chunks, planRoles } from './dialogue.js';
import { planRoles } from './dialogue.js';

// --- Playing --------------------------------------------------------------------------------

const audioCache = new Map();
const AUDIO_KEEP = 24;

function audioKey(text, voice, rate) { return voice + '|' + rate + '|' + text; }

/** The server's audio for [text], fetched once (also ahead of time) and kept for a while. */
function naturalAudio(text, voice, rate) {
  const key = audioKey(text, voice, rate);
  let p = audioCache.get(key);
  if (!p && voice?.startsWith(KOKORO_PREFIX)) {
    p = kokoroSpeak(text, voice, rate);
  } else if (!p) {
    p = fetch('/api/tts', {
      method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, voice, rate })
    }).then(async (r) => {
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || t('Sin voz'));
      return URL.createObjectURL(await r.blob());
    });
  }
  if (!audioCache.has(key)) {
    p.catch(() => audioCache.delete(key));
    audioCache.set(key, p);
    while (audioCache.size > AUDIO_KEEP) {
      const [oldKey, old] = audioCache.entries().next().value;
      audioCache.delete(oldKey);
      old.then((url) => setTimeout(() => URL.revokeObjectURL(url), 60_000)).catch(() => {});
    }
  }
  return p;
}

/**
 * Reads paragraphs one after another with the chosen voices. One reader per page; [stop] ends
 * whatever is playing, [pause]/[resume] hold it.
 */
export class SpeechReader {
  constructor() {
    this.audio = typeof Audio !== 'undefined' ? new Audio() : null;
    this.token = 0;
    this.naturalFailed = false;
  }

  engine() {
    return settings.novel.ttsEngine === 'browser' || !speechVoices.natural || this.naturalFailed ? 'browser' : 'natural';
  }

  /** The pieces a paragraph is read in, each with its voice; nothing for a paragraph with no words. */
  plan(paragraph, lang) {
    const characters = settings.novel.ttsCharacters !== false;
    const v = voiceSettings(lang);
    return planRoles(paragraph, characters).map(({ text, role, dialogue }) => {
      if (this.engine() === 'natural') return { text, voice: v[role] };
      const b = settings.novel.browserVoices?.[lang] || {};
      return { text, browser: (dialogue && b.dialogue) || b.narrator || '' };
    });
  }

  /** Starts preparing a paragraph that will be read soon (natural voices only). */
  prepare(paragraph, lang) {
    if (this.engine() !== 'natural' || !paragraph) return;
    const rate = settings.novel.speechRate || 1;
    for (const piece of this.plan(paragraph, lang)) naturalAudio(piece.text, piece.voice, rate).catch(() => {});
  }

  /** Reads one paragraph; resolves when it ends (or rejects with 'stopped'). */
  async read(paragraph, lang) {
    const token = ++this.token;
    for (const piece of this.plan(paragraph, lang)) {
      if (token !== this.token) throw new Error('stopped');
      if (piece.voice) {
        try {
          await this.playNatural(piece, token);
          continue;
        } catch (e) {
          if (token !== this.token) throw e;
          // The server did not answer with a voice: the browser's own reads instead, from now on.
          this.naturalFailed = true;
          this.onFallback?.(e.message);
        }
      }
      await this.playBrowser(piece.text, piece.browser, lang, token);
    }
  }

  async playNatural(piece, token) {
    const url = await naturalAudio(piece.text, piece.voice, settings.novel.speechRate || 1);
    if (token !== this.token) throw new Error('stopped');
    const a = this.audio;
    a.src = url;
    await a.play();
    await new Promise((resolve, reject) => {
      a.onended = () => resolve();
      a.onerror = () => reject(new Error(t('No se pudo reproducir la voz')));
      this.cancel = () => reject(new Error('stopped'));
    });
  }

  playBrowser(text, voiceName, lang, token) {
    return new Promise((resolve, reject) => {
      if (typeof speechSynthesis === 'undefined') return reject(new Error(t('Sin voz')));
      const u = new SpeechSynthesisUtterance(text);
      u.lang = lang === 'en' ? 'en-US' : 'es-ES';
      const all = speechVoices.browser.length ? speechVoices.browser : [...speechSynthesis.getVoices()].sort((a, b) => browserScore(b) - browserScore(a));
      u.voice = all.find((v) => v.name === voiceName) || all.find((v) => v.lang.toLowerCase().startsWith(lang)) || null;
      u.rate = settings.novel.speechRate || 1;
      u.onend = () => (token === this.token ? resolve() : reject(new Error('stopped')));
      u.onerror = (e) => (e.error === 'interrupted' || e.error === 'canceled' ? reject(new Error('stopped')) : resolve());
      this.cancel = () => reject(new Error('stopped'));
      speechSynthesis.speak(u);
    });
  }

  pause() {
    if (this.audio && !this.audio.paused) this.audio.pause();
    if (typeof speechSynthesis !== 'undefined') speechSynthesis.pause();
  }

  resume() {
    if (this.audio?.src && this.audio.paused && !this.audio.ended) this.audio.play().catch(() => {});
    if (typeof speechSynthesis !== 'undefined') speechSynthesis.resume();
  }

  stop() {
    this.token++;
    this.audio?.pause();
    if (typeof speechSynthesis !== 'undefined') speechSynthesis.cancel();
    this.cancel?.();
    this.cancel = null;
  }
}
