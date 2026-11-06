# Voces y lectura en voz alta

## Cómo elige Yomukai la voz
De mejor a peor (`shared/…/data/speech/TtsPlan.kt`):

1. **Worker (servidor de casa)**: calidad alta. El VPS solo encola el trabajo y guarda el resultado, que se reutiliza si otra persona lee lo mismo. Voces `w:<id>` en `/api/tts/voices`.
2. **Dispositivo**: en la app, sherpa-onnx (los modelos se descargan solo si activas el modo sin conexión); en la web, Kokoro o Supertonic con WebGPU/WASM (pendiente).
3. **Piper en el VPS**: respaldo ligero (pocas voces, 1-2 a la vez).
4. **Voz del sistema** (Android TextToSpeech / Web Speech API): siempre está, la lectura nunca se queda muda.

## Worker
Por defecto usa **Pocket TTS** (Kyutai, va bien en CPU): `pip install pocket-tts` en el equipo del worker. Variables:

| Variable | Qué hace |
|---|---|
| `WORKER_TTS_COMMAND` | Otro motor: plantilla con `{text}`, `{voice}` y `{out}` (WAV). Sin shell: el texto nunca se ejecuta. Ej.: `python /opt/kokoro/say.py --voice {voice} --text {text} --out {out}` |
| `WORKER_TTS_VOICES` | Voces que ofrece: `id:idioma:género:Nombre,...` (ej. `ef_dora:es:f:Dora,em_alex:es:m:Álex`). Por defecto, las de Pocket TTS (inglés) |
| `WORKER_CONCURRENCY` | Cuántos trabajos a la vez (decídelo con la comparación de abajo) |

Si el motor cambia sus opciones de línea de comandos, basta con cambiar `WORKER_TTS_COMMAND`.

## Comparar motores y elegir
En el equipo del worker (Linux): `bash scripts/tts-compare.sh`. Genera el mismo párrafo en español e inglés con cada motor instalado (Pocket TTS, Piper, Kokoro y Supertonic si los configuras) y escribe `informe.md` con tiempo, factor de tiempo real y RAM, más los `.wav` para escucharlos. Hazlo también en el worker con GPU si lo tienes.

En el móvil (gama media) y en el navegador no se puede automatizar desde aquí: anota a mano el tiempo hasta oír la primera frase y si se entrecorta.

## Pregeneración
Mientras escuchas un capítulo, la web y la app piden `POST /api/tts/prefetch` con los párrafos del siguiente (prioridad baja, sin repetir lo que ya está hecho). La pregeneración nocturna de la biblioteca está pendiente.

## Licencias
Voces por personaje con clonación solo con muestras de licencia libre o grabadas por nosotros.
