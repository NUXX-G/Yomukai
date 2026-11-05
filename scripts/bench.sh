#!/usr/bin/env bash
# Medición del presupuesto de rendimiento (ver TRABAJO.md).
#   scripts/bench.sh [URL_BASE] [PID_DEL_SERVER]
# Sin argumentos arranca el server compilado (./gradlew :server:installDist) en un puerto libre con datos temporales.
# Mide: RAM (RSS) en reposo, RAM y CPU tras la carga, y el p95 de la API local (sin red externa)
# y, aparte, el de unas novedades reales de fuentes (dependen de internet, solo orientativo).
set -euo pipefail
cd "$(dirname "$0")/.."

BASE=${1:-}
PID=${2:-}
N=${BENCH_REQUESTS:-300}
TMP=$(mktemp -d)
JAR=$TMP/cookies

WIN=0; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) WIN=1 ;; esac
rss_mb() { # RSS en MB del proceso $1 (Linux /proc, o PowerShell en Windows)
  if [ $WIN = 0 ]; then awk '/VmRSS/ {printf "%.0f", $2/1024}' "/proc/$1/status"
  else powershell -NoProfile -Command "[math]::Round((Get-Process -Id $1).WorkingSet64/1MB)" | tr -d '\r'; fi
}
cpu_s() { # segundos de CPU acumulados
  if [ $WIN = 0 ]; then awk -v hz="$(getconf CLK_TCK)" '{printf "%.1f", ($14+$15)/hz}' "/proc/$1/stat"
  else powershell -NoProfile -Command "[math]::Round((Get-Process -Id $1).TotalProcessorTime.TotalSeconds,1)" | tr -d '\r'; fi
}
p95() { sort -n | awk '{a[NR]=$1} END {i=int(NR*0.95); if (i<1) i=1; printf "%.0f ms", a[i]*1000}'; }

if [ -z "$BASE" ]; then
  PORT=${BENCH_PORT:-8197}
  BASE="http://127.0.0.1:$PORT"
  export PORT DATA_DIR="$TMP/data" ADMIN_USER=bench ADMIN_PASSWORD=bench-password-123 WEB_DIR="web/dist"
  export JAVA_OPTS=${JAVA_OPTS:-"-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"}
  if [ -x server/build/install/server/bin/server ]; then server/build/install/server/bin/server >"$TMP/server.log" 2>&1 &
  else echo "Compila antes: ./gradlew :server:installDist" >&2; exit 1; fi
  LAUNCHER=$!
  STARTED=1
  for _ in $(seq 60); do curl -s -o /dev/null "$BASE/auth/me" && break; sleep 1; done
  if [ -z "$PID" ]; then
    if [ $WIN = 1 ]; then
      PID=$(powershell -NoProfile -Command "(Get-NetTCPConnection -LocalPort $PORT -State Listen).OwningProcess" | tr -d '\r' | head -1)
    else PID=$(pgrep -f "com.nelson.yomukai.server.MainKt" | head -1); fi
  fi
fi
USER_NAME=${BENCH_USER:-bench}; PASS=${BENCH_PASSWORD:-bench-password-123}
curl -s -c "$JAR" -H 'Content-Type: application/json' -d "{\"name\":\"$USER_NAME\",\"password\":\"$PASS\"}" "$BASE/auth/login" >/dev/null

sleep 15
echo "RAM en reposo: $(rss_mb "$PID") MB"
CPU0=$(cpu_s "$PID")

for path in /auth/me /api/library /api/history /api/settings /api/sources /api/categories; do
  for _ in $(seq $((N / 6))); do curl -s -b "$JAR" -o /dev/null -w '%{time_total}\n' "$BASE$path"; done
done > "$TMP/local.txt"
echo "API local p95 ($N peticiones): $(p95 < "$TMP/local.txt")"

for s in mangadex marmota animeflv olympus; do
  curl -s -b "$JAR" -o /dev/null -w '%{time_total}\n' --max-time 60 "$BASE/api/sources/$s/latest?page=1"
done > "$TMP/remote.txt"
echo "Novedades de fuentes p95 (red externa, orientativo): $(p95 < "$TMP/remote.txt")"

echo "RAM tras la carga: $(rss_mb "$PID") MB"
CPU1=$(cpu_s "$PID")
echo "CPU usada durante la carga: $(awk -v a="$CPU0" -v b="$CPU1" 'BEGIN {printf "%.1f s", b-a}')"

if [ "${STARTED:-0}" = 1 ]; then
  kill "$PID" 2>/dev/null || powershell -NoProfile -Command "Stop-Process -Id $PID -Force" >/dev/null 2>&1 || true
fi
rm -rf "$TMP"
