#!/usr/bin/env bash
# Compares text-to-speech engines on the home worker (Linux): the same paragraph in Spanish and English with each
# engine that is installed, measuring time to audio, real-time factor (seconds of work per second of audio) and peak
# RAM. The WAV files are kept so you can listen and choose. Nothing here runs on the VPS.
#
#   bash scripts/tts-compare.sh [carpeta-de-salida]
#
# Engines (each is skipped when not installed or not configured):
#   pocket    Pocket TTS (Kyutai):   pip install pocket-tts            (default worker engine)
#   piper     Piper:                 PIPER_BIN y PIPER_MODEL_ES / PIPER_MODEL_EN (.onnx)
#   kokoro    Kokoro:                KOKORO_CMD  con {text} {out} {lang} (p. ej. un script propio de python)
#   supertonic Supertonic:           SUPERTONIC_CMD con {text} {out} {lang}
# Device engines (sherpa-onnx on a mid-range phone, Kokoro/Supertonic in the browser) are measured by hand: see
# docs/VOCES.md. When you have chosen, set WORKER_TTS_COMMAND / WORKER_TTS_VOICES and how many run at once
# (WORKER_CONCURRENCY) from these numbers: concurrency ≈ cores / (RTF × cores used by one run), and RAM must fit.
set -euo pipefail

OUT=${1:-tts-compare-$(date +%Y%m%d-%H%M)}
mkdir -p "$OUT"
REPORT="$OUT/informe.md"
ES="Era una noche tranquila en el pueblo. El viejo herrero apagó la fragua, miró las estrellas y pensó que mañana, por fin, terminaría la espada que llevaba meses forjando."
EN="It was a quiet night in the village. The old blacksmith put out the forge, looked at the stars and thought that tomorrow, at last, he would finish the sword he had been forging for months."

command -v /usr/bin/time >/dev/null || { echo "Falta /usr/bin/time (apt install time)"; exit 1; }
command -v ffprobe >/dev/null || { echo "Falta ffprobe (apt install ffmpeg)"; exit 1; }

{
  echo "# Comparación de voces — $(date '+%Y-%m-%d %H:%M')"
  echo
  echo "Equipo: $(nproc) núcleos, $(awk '/MemTotal/ {printf "%.1f GB", $2/1048576}' /proc/meminfo), GPU: $(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null || echo ninguna)"
  echo
  echo "| Motor | Idioma | Tiempo hasta el audio (s) | Duración del audio (s) | Factor de tiempo real | RAM máxima (MB) | Archivo |"
  echo "|---|---|---|---|---|---|---|"
} > "$REPORT"

# measure ENGINE LANG OUTFILE command args...   (args must already contain the text and output path)
measure() {
  local engine=$1 lang=$2 out=$3; shift 3
  local log="$OUT/$engine-$lang.time"
  if ! /usr/bin/time -v -o "$log" "$@" > "$OUT/$engine-$lang.log" 2>&1; then
    echo "| $engine | $lang | fallo (ver $engine-$lang.log) | | | | |" >> "$REPORT"; return
  fi
  local secs ram dur rtf
  secs=$(awk -F': ' '/Elapsed \(wall clock\)/ {n=split($2,a,":"); t=0; for(i=1;i<=n;i++) t=t*60+a[i]; print t}' "$log")
  ram=$(awk -F': ' '/Maximum resident set size/ {printf "%d", $2/1024}' "$log")
  dur=$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$out" 2>/dev/null || echo 0)
  rtf=$(awk -v s="$secs" -v d="$dur" 'BEGIN { if (d > 0) printf "%.2f", s/d; else print "?" }')
  echo "| $engine | $lang | $secs | $(printf '%.1f' "$dur") | $rtf | $ram | $(basename "$out") |" >> "$REPORT"
}

# run_template ENGINE TEMPLATE  — {text} {out} {lang} replaced in each argument (no shell evaluation of the text)
run_template() {
  local engine=$1 template=$2 lang text out
  for lang in es en; do
    text=$([ "$lang" = es ] && echo "$ES" || echo "$EN")
    out="$OUT/$engine-$lang.wav"
    local args=()
    for a in $template; do a=${a//\{text\}/$text}; a=${a//\{out\}/$out}; a=${a//\{lang\}/$lang}; args+=("$a"); done
    measure "$engine" "$lang" "$out" "${args[@]}"
  done
}

if command -v pocket-tts >/dev/null; then
  # Pocket TTS speaks English; the Spanish paragraph shows how it copes (useful to decide).
  for lang in es en; do
    text=$([ "$lang" = es ] && echo "$ES" || echo "$EN")
    measure pocket "$lang" "$OUT/pocket-$lang.wav" pocket-tts generate --voice "${POCKET_VOICE:-alba}" --text "$text" --output-path "$OUT/pocket-$lang.wav"
  done
else echo "pocket-tts no instalado: se salta (pip install pocket-tts)"; fi

if [ -n "${PIPER_BIN:-}" ] && [ -x "$PIPER_BIN" ]; then
  for lang in es en; do
    model_var="PIPER_MODEL_${lang^^}"; model=${!model_var:-}
    [ -n "$model" ] || { echo "Sin $model_var: Piper $lang se salta"; continue; }
    text=$([ "$lang" = es ] && echo "$ES" || echo "$EN")
    printf '%s' "$text" > "$OUT/piper-$lang.txt"
    measure piper "$lang" "$OUT/piper-$lang.wav" bash -c '"$0" --model "$1" --output_file "$2" < "$3"' "$PIPER_BIN" "$model" "$OUT/piper-$lang.wav" "$OUT/piper-$lang.txt"
  done
else echo "PIPER_BIN no configurado: Piper se salta"; fi

[ -n "${KOKORO_CMD:-}" ] && run_template kokoro "$KOKORO_CMD" || echo "KOKORO_CMD no configurado: Kokoro se salta"
[ -n "${SUPERTONIC_CMD:-}" ] && run_template supertonic "$SUPERTONIC_CMD" || echo "SUPERTONIC_CMD no configurado: Supertonic se salta"

{
  echo
  echo "Factor de tiempo real < 1 = más rápido que leer en voz alta. Con CLI sin streaming, «tiempo hasta el audio» es el tiempo total."
  echo "Escucha los .wav de esta carpeta y elige; luego pon WORKER_TTS_COMMAND/WORKER_TTS_VOICES en el worker (ver docs/VOCES.md)."
} >> "$REPORT"
echo "Listo: $REPORT"
