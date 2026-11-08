// Copies ONNX Runtime's WebAssembly (MIT) into public/ort/, so the browser loads it from this site and never from a
// CDN (Kokoro voices in the browser, see src/lib/kokoro.worker.js). Run before dev and build; the copy is not in git.
import { copyFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const from = fileURLToPath(new URL('../node_modules/onnxruntime-web/dist/', import.meta.url));
const to = fileURLToPath(new URL('../public/ort/', import.meta.url));
mkdirSync(to, { recursive: true });
for (const f of ['ort-wasm-simd-threaded.wasm', 'ort-wasm-simd-threaded.mjs']) copyFileSync(from + f, to + f);
