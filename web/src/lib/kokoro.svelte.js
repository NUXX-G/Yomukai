// Natural English voices made in the browser (Kokoro, see kokoro.worker.js). Voice ids are "k:af_heart"; the worker
// starts the first time one is used, and the model (~90 MB) downloads once from this server and stays cached.

export const KOKORO_PREFIX = 'k:';

/** The Kokoro voices offered, as entries of the voice list: English only, women "af_/bf_", men "am_/bm_". */
export async function kokoroVoices() {
  if (typeof WebAssembly === 'undefined' || typeof Worker === 'undefined') return [];
  const r = await fetch('/api/tts/kokoro/voices', { credentials: 'include' }).catch(() => null);
  if (!r?.ok) return [];
  const { voices = [] } = await r.json();
  return voices.map((id) => ({
    id: KOKORO_PREFIX + id,
    label: id.slice(3).replace(/^\w/, (c) => c.toUpperCase()) + (id[0] === 'b' ? ' (UK)' : ' (US)'),
    lang: 'en',
    gender: id[1] === 'f' ? 'f' : 'm',
    region: '',
    tier: 'browser'
  }));
}

let worker = null;
let next = 0;
const pending = new Map();
/** 0..1 while the model downloads the first time; null when not downloading. */
export const kokoroState = $state({ progress: null });

function start() {
  if (worker) return worker;
  worker = new Worker(new URL('./kokoro.worker.js', import.meta.url), { type: 'module' });
  worker.onmessage = (e) => {
    const m = e.data;
    if (m.type === 'progress') { kokoroState.progress = m.value >= 1 ? null : m.value; return; }
    const p = pending.get(m.id);
    if (!p) return;
    pending.delete(m.id);
    if (m.type === 'audio') p.resolve(URL.createObjectURL(new Blob([m.audio], { type: 'audio/wav' })));
    else p.reject(new Error(m.error));
  };
  worker.onerror = (e) => {
    for (const p of pending.values()) p.reject(new Error(e.message || 'Kokoro'));
    pending.clear();
    worker = null;
  };
  return worker;
}

/** An object URL of [text] read by Kokoro voice [voice] ("k:af_heart") at [speed]. */
export function kokoroSpeak(text, voice, speed = 1) {
  const id = ++next;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    start().postMessage({ id, text, voice: voice.slice(KOKORO_PREFIX.length), speed });
  });
}
