# Voces y lectura en voz alta

## Cómo elige Yomukai la voz
De mejor a peor (`shared/…/data/speech/TtsPlan.kt`):

1. **Worker (servidor de casa)**: calidad alta. El VPS solo encola el trabajo y guarda el resultado, que se reutiliza si otra persona lee lo mismo. Voces `w:<id>` en `/api/tts/voices`.
2. **Dispositivo**: en la app, sherpa-onnx (los modelos se descargan solo si activas el modo sin conexión); en la web, Kokoro o Supertonic con WebGPU/WASM (pendiente).
3. **Piper en el VPS**: respaldo ligero (pocas voces, 1-2 a la vez).
4. **Voz del sistema** (Android TextToSpeech / Web Speech API): siempre está, la lectura nunca se queda muda.

## Worker
Por defecto usa **Pocket TTS** (Kyutai, va bien en CPU), multilingüe desde abril de 2026 (`pocket-tts` 4.x, la que lleva la imagen `yomukai-worker-full`). Si tu `pocket-tts` entiende `--language`, el worker ofrece:

- **Español (por defecto en los textos en español)**: `lola` (la voz española nativa) y `es_alba`, `es_marius`, `es_jean`, `es_fantine`, `es_george`, `es_vera` (voces de Pocket hablando en español).
- Francés `estelle`, alemán `juergen`, portugués `rafael`, italiano `giovanni` e inglés (`alba`, `marius`, `javert`…).

Con una versión antigua sin `--language` solo ofrece las inglesas (`pip install -U pocket-tts`). La web y la app eligen solas las voces del worker del idioma del texto si no has elegido otra (antes caían en Piper).

Cada trozo sale con el mismo volumen (`loudnorm` de ffmpeg, −16 LUFS) para que no haya saltos entre frases.

| Variable | Qué hace |
|---|---|
| `WORKER_TTS_COMMAND` | Otro motor: plantilla con `{text}`, `{voice}`, `{language}` y `{out}` (WAV). Sin shell: el texto nunca se ejecuta. Ej.: `python /opt/kokoro/say.py --voice {voice} --text {text} --out {out}` |
| `WORKER_TTS_VOICES` | Voces que ofrece: `id:idioma:género:Nombre,...` (ej. `ef_dora:es:f:Dora,em_alex:es:m:Álex`). Por defecto, las de Pocket TTS de arriba |
| `WORKER_CONCURRENCY` | Cuántos trabajos a la vez (decídelo con la comparación de abajo) |

## Texto que se lee
Antes de generar la voz (servidor, web y app, mismas reglas: `shared/…/speech/SpeechText.kt` y `web/src/lib/speech-text.js`, casos en `shared/src/test/resources/speech_text.json`): números en palabras («1990» → «mil novecientos noventa», «3,5» → «tres coma cinco», «50 %» → «cincuenta por ciento», «1.º» → «primero»), abreviaturas («Sr.», «Dra.», «Cap.», «pág.», «EE. UU.», «Mr.», «Dr.»…), pausas («...» → «…», «!!!» → «!», la raya de diálogo no se lee) y troceo en frases enteras de unos 400 caracteres (`TtsPlan.chunks`).

Si el motor cambia sus opciones de línea de comandos, basta con cambiar `WORKER_TTS_COMMAND`.

## Comparar motores y elegir
En el equipo del worker (Linux): `bash scripts/tts-compare.sh`. Genera el mismo párrafo con cada voz española de Pocket TTS (`pocket-lola-es.wav`, `pocket-alba-es.wav`…), en inglés, y con cada otro motor instalado (Piper, Kokoro y Supertonic si los configuras) y escribe `informe.md` con tiempo, factor de tiempo real y RAM, más los `.wav` para escucharlos. Hazlo también en el worker con GPU si lo tienes.

En el móvil (gama media) y en el navegador no se puede automatizar desde aquí: anota a mano el tiempo hasta oír la primera frase y si se entrecorta.

## Pregeneración
Mientras escuchas un capítulo, la web y la app piden `POST /api/tts/prefetch` con los párrafos del siguiente (prioridad baja, sin repetir lo que ya está hecho). La pregeneración nocturna de la biblioteca está pendiente.

## Licencias
Voces por personaje con clonación solo con muestras de licencia libre o grabadas por nosotros.
