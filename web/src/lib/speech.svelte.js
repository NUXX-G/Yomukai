// Reading novels aloud.
//  - Natural voices: Piper neural voices running on the Yomukai server (woman, man, accents). The
//    next paragraphs are prepared while one is read, so there are no gaps between them.
//  - Browser voices: the system's own, best ones first (Edge's "Natural", Safari's "Premium/Mejorada",
//    Google's). Used when the server has no natural voices, or if one fails to answer.
// With "voices by character", dialogue is read by another voice than the narration: a woman's when
// the text says a woman speaks ("—dijo ella", "she said"...), a man's when a man does.

import { settings } from './store.svelte.js';

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
}

// --- Who is speaking -------------------------------------------------------------------------

// Whole words, accents included (\b does not see "él" as a word in JavaScript).
const FEMALE = /(?<!\p{L})(ella|ellas|la\s+(?:chica|mujer|joven|niña|muchacha|señora|señorita|princesa|reina|madre|hermana|maga|doncella|dama|anciana|emperatriz|santa|bruja|diosa|maestra|profesora|doctora|capitana)|she|her|the\s+(?:girl|woman|lady|princess|queen|mother|sister|witch|goddess|maid))(?!\p{L})/iu;
const MALE = /(?<!\p{L})(él|ellos|el\s+(?:chico|hombre|joven|niño|muchacho|señor|príncipe|rey|padre|hermano|mago|caballero|anciano|emperador|santo|brujo|dios|maestro|profesor|doctor|capitán|duque)|he|him|his|the\s+(?:boy|man|lord|prince|king|father|brother|wizard|god|knight))(?!\p{L})/iu;

/** "f", "m" or "" from the words around a line of dialogue. */
export function genderHint(narration) {
  const f = FEMALE.test(narration);
  const m = MALE.test(narration);
  return f && !m ? 'f' : m && !f ? 'm' : '';
}

/**
 * A paragraph split into narration and dialogue: [{ text, dialogue, gender }]. Spanish dialogue
 * starts the paragraph with a dash and is cut by the narrator's "—dijo ella—"; English and many
 * translations put it between quotes.
 */
export function splitDialogue(paragraph) {
  const text = paragraph.trim();
  const parts = [];
  const add = (t, dialogue) => { t = t.trim(); if (t && /[\p{L}\p{N}]/u.test(t)) parts.push({ text: t, dialogue, gender: '' }); };
  if (/^[—–―-]\s*\S/.test(text)) {
    // —Line —narration—. Line again.
    text.replace(/^[—–―-]\s*/, '').split(/\s*[—–―]\s*/).forEach((piece, i) => add(piece, i % 2 === 0));
  } else if (/[«“"]/.test(text)) {
    const re = /«([^»]+)»|“([^”]+)”|"([^"]+)"/g;
    let last = 0;
    for (const m of text.matchAll(re)) {
      add(text.slice(last, m.index), false);
      add(m[1] ?? m[2] ?? m[3], true);
      last = m.index + m[0].length;
    }
    add(text.slice(last), false);
  } else {
    add(text, false);
  }
  if (!parts.length) return [{ text, dialogue: false, gender: '' }];
  const narration = parts.filter((p) => !p.dialogue).map((p) => p.text).join(' ');
  const g = genderHint(narration);
  parts.forEach((p) => { if (p.dialogue) p.gender = g; });
  return parts;
}

/** Text as it should be heard: no invisible watermark characters, no markdown escapes, no "***". */
export function speakable(text) {
  return (text || '')
    .replace(/[\u200B-\u200F\u2060-\u2064\uFEFF]/g, '')
    .replace(/\\([[\]()*_~`#>-])/g, '$1')
    .replace(/[*_~#=]{3,}/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Long text in pieces of whole sentences (~400 characters): the first one sounds sooner. */
export function chunks(text, max = 400) {
  if (text.length <= max) return [text];
  const sentences = text.match(/[^.!?…]+[.!?…]+["»”')\]]*\s*|[^.!?…]+$/g) || [text];
  const out = [];
  let current = '';
  for (const s of sentences) {
    if (current && (current + s).length > max) { out.push(current.trim()); current = ''; }
    current += s;
    while (current.length > max * 2) { out.push(current.slice(0, max).trim()); current = current.slice(max); }
  }
  if (current.trim()) out.push(current.trim());
  return out;
}

// --- Playing --------------------------------------------------------------------------------

const audioCache = new Map();
const AUDIO_KEEP = 24;

function audioKey(text, voice, rate) { return voice + '|' + rate + '|' + text; }

/** The server's audio for [text], fetched once (also ahead of time) and kept for a while. */
function naturalAudio(text, voice, rate) {
  const key = audioKey(text, voice, rate);
  let p = audioCache.get(key);
  if (!p) {
    p = fetch('/api/tts', {
      method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, voice, rate })
    }).then(async (r) => {
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || 'Sin voz');
      return URL.createObjectURL(await r.blob());
    });
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
    paragraph = speakable(paragraph);
    if (!/[\p{L}\p{N}]/u.test(paragraph)) return [];
    const characters = settings.novel.ttsCharacters !== false;
    const parts = characters ? splitDialogue(paragraph) : [{ text: paragraph, dialogue: false, gender: '' }];
    const v = voiceSettings(lang);
    return parts.flatMap((p) => chunks(p.text).map((text) => {
      if (this.engine() === 'natural') {
        // A line whose speaker the text does not tell keeps the narrator's voice: better than a wrong one.
        const voice = !p.dialogue ? v.narrator : p.gender === 'f' ? v.female : p.gender === 'm' ? v.male : v.narrator;
        return { text, voice };
      }
      const b = settings.novel.browserVoices?.[lang] || {};
      return { text, browser: (p.dialogue && b.dialogue) || b.narrator || '' };
    }));
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
      a.onerror = () => reject(new Error('No se pudo reproducir la voz'));
      this.cancel = () => reject(new Error('stopped'));
    });
  }

  playBrowser(text, voiceName, lang, token) {
    return new Promise((resolve, reject) => {
      if (typeof speechSynthesis === 'undefined') return reject(new Error('Sin voz'));
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
