// Kokoro-82M (Apache-2.0) speaking English in the browser, inside a worker so the page never stutters. Based on the
// approach of kokoro-js (Apache-2.0, github.com/hexgrad/kokoro): text → phonemes (espeak-ng compiled to WebAssembly,
// the phonemizer package) → ids → the ONNX model with the voice's style row → 24 kHz audio. Every file comes from this
// server (/api/tts/kokoro/…), which fetched and checked it once; nothing is loaded from third parties.
import * as ort from 'onnxruntime-web/wasm';
import { phonemize } from 'phonemizer';

ort.env.wasm.wasmPaths = '/ort/';
// Several threads when the page is cross-origin isolated (the server sends COOP/COEP); one otherwise.
ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.max(1, Math.min(4, (navigator.hardwareConcurrency || 2) - 1)) : 1;

const BASE = '/api/tts/kokoro/';
const CACHE = 'yomukai-kokoro-v2';
const STYLE = 256;
const MAX_TOKENS = 510;
const SAMPLE_RATE = 24000;

let ready = null;
let session = null;
let vocab = null;
const voices = new Map();

/** A file of this server, kept in the browser's cache so the ~90 MB model downloads once. */
async function fetchCached(path, onProgress) {
  const url = BASE + path;
  let cache = null;
  try {
    cache = await caches.open(CACHE);
    const hit = await cache.match(url);
    if (hit) return await hit.arrayBuffer();
  } catch { /* no Cache Storage (private mode): just download */ }
  const r = await fetch(url, { credentials: 'include' });
  if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || 'HTTP ' + r.status);
  const total = Number(r.headers.get('Content-Length')) || 0;
  const reader = r.body.getReader();
  const chunks = [];
  let got = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    got += value.length;
    if (onProgress && total) onProgress(got / total);
  }
  const bytes = new Uint8Array(got);
  let at = 0;
  for (const c of chunks) { bytes.set(c, at); at += c.length; }
  try { await cache?.put(url, new Response(bytes, { headers: { 'Content-Type': 'application/octet-stream' } })); } catch { /* quota: fine */ }
  return bytes.buffer;
}

function init() {
  ready ??= (async () => {
    const tokenizer = JSON.parse(new TextDecoder().decode(await fetchCached('tokenizer.json')));
    vocab = tokenizer.model.vocab;
    const model = await fetchCached('model', (p) => postMessage({ type: 'progress', value: p }));
    session = await ort.InferenceSession.create(new Uint8Array(model), { executionProviders: ['wasm'] });
    postMessage({ type: 'progress', value: 1 });
  })();
  ready.catch(() => { ready = null; });
  return ready;
}

async function voice(id) {
  if (!/^[ab][fm]_[a-z]+$/.test(id)) throw new Error('Voz no válida');
  if (!voices.has(id)) voices.set(id, new Float32Array(await fetchCached('voices/' + id + '.bin')));
  return voices.get(id);
}

/** Text the phonemizer reads well: plain quotes, a few spelled-out abbreviations, no doubled spaces. */
function normalize(text) {
  return text
    .replace(/[‘’]/g, "'").replace(/[«»“”]/g, '"')
    .replace(/\bD[Rr]\.(?= [A-Z])/g, 'Doctor').replace(/\b(?:Mr\.|MR\.(?= [A-Z]))/g, 'Mister')
    .replace(/\b(?:Ms\.|MS\.(?= [A-Z]))/g, 'Miss').replace(/\b(?:Mrs\.|MRS\.(?= [A-Z]))/g, 'Mrs')
    .replace(/(?<=\d),(?=\d)/g, '').replace(/(?<=\d)-(?=\d)/g, ' to ')
    .replace(/\s+/g, ' ').trim();
}

const PUNCTUATION = /(\s*[;:,.!?¡¿—…"«»“”(){}[\]]+\s*)+/g;

/** Phonemes of [text]: punctuation kept as it is (it shapes the pauses), words through espeak-ng. */
async function phonemes(text, british) {
  const lang = british ? 'en' : 'en-us';
  const out = [];
  let last = 0;
  for (const m of text.matchAll(PUNCTUATION)) {
    if (m.index > last) out.push((await phonemize(text.slice(last, m.index), lang)).join(' '));
    out.push(m[0]);
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push((await phonemize(text.slice(last), lang)).join(' '));
  let p = out.join('')
    .replace(/ʲ/g, 'j').replace(/r/g, 'ɹ').replace(/x/g, 'k').replace(/ɬ/g, 'l')
    .replace(/(?<=[a-zɹː])(?=hˈʌndɹɪd)/g, ' ').replace(/ z(?=[;:,.!?¡¿—…"«»“” ]|$)/g, 'z');
  if (!british) p = p.replace(/(?<=nˈaɪn)ti(?!ː)/g, 'di');
  return p.trim();
}

function wav(samples) {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const v = new DataView(buffer);
  const str = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  str(0, 'RIFF'); v.setUint32(4, 36 + samples.length * 2, true); str(8, 'WAVE'); str(12, 'fmt ');
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, SAMPLE_RATE, true);
  v.setUint32(28, SAMPLE_RATE * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true); str(36, 'data');
  v.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) v.setInt16(44 + i * 2, Math.max(-1, Math.min(1, samples[i])) * 0x7fff, true);
  return buffer;
}

async function speak(text, voiceId, speed) {
  await init();
  const style = await voice(voiceId);
  const ph = await phonemes(normalize(text), voiceId.startsWith('b'));
  const ids = [0];
  for (const ch of ph) { const id = vocab[ch]; if (id !== undefined && ids.length < MAX_TOKENS + 1) ids.push(id); }
  ids.push(0);
  const row = Math.min(Math.max(ids.length - 2, 0), 509) * STYLE;
  const out = await session.run({
    input_ids: new ort.Tensor('int64', BigInt64Array.from(ids.map(BigInt)), [1, ids.length]),
    style: new ort.Tensor('float32', style.slice(row, row + STYLE), [1, STYLE]),
    speed: new ort.Tensor('float32', [Math.min(2, Math.max(0.5, speed || 1))], [1])
  });
  return wav(out.waveform.data);
}

onmessage = async (e) => {
  const { id, text, voice: v, speed } = e.data;
  try {
    const audio = await speak(String(text).slice(0, 2000), String(v), Number(speed));
    postMessage({ type: 'audio', id, audio }, [audio]);
  } catch (err) {
    postMessage({ type: 'error', id, error: err?.message || String(err) });
  }
};
