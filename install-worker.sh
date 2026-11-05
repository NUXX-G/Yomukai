#!/usr/bin/env bash
# Worker de Yomukai (servidor de casa o portátil con GPU): instalar o actualizar con una línea.
#   curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install-worker.sh | YOMUKAI_URL=https://tu-vps WORKER_TOKEN=ykw_... bash
# El token se crea en la web: Ajustes › Administración › Workers › Nuevo worker.
# Opciones: YOMUKAI_WORKER_DIR (por defecto ~/yomukai-worker), WORKER_GPU=true para usar una GPU NVIDIA.
set -euo pipefail

REPO="https://github.com/NUXX-G/Yomukai"
RAW="https://raw.githubusercontent.com/NUXX-G/Yomukai/main"
DIR="${YOMUKAI_WORKER_DIR:-$HOME/yomukai-worker}"

say() { printf '\033[1;38;5;209m%s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "Necesitas Docker: https://docs.docker.com/engine/install/"
docker compose version >/dev/null 2>&1 || fail "Necesitas Docker Compose v2 (docker compose)."
docker info >/dev/null 2>&1 || fail "Docker no está en marcha, o tu usuario no puede usarlo (prueba con sudo)."

mkdir -p "$DIR"
cd "$DIR"
curl -fsSL "$RAW/docker-compose.worker.yml" -o docker-compose.worker.yml

if [ ! -f .env.worker ]; then
  [ -n "${YOMUKAI_URL:-}" ] || fail "Falta YOMUKAI_URL (la dirección de tu Yomukai, p. ej. https://yomukai.tudominio.com)"
  [ -n "${WORKER_TOKEN:-}" ] || fail "Falta WORKER_TOKEN (créalo en Ajustes › Administración › Workers)"
  cat > .env.worker <<ENV
# Worker de Yomukai: cambia esto y ejecuta "docker compose -f docker-compose.worker.yml up -d" otra vez.
YOMUKAI_URL=$YOMUKAI_URL
WORKER_TOKEN=$WORKER_TOKEN
# Trabajos a la vez (vacío = según núcleos y memoria) y carga por núcleo a partir de la cual se aparta.
WORKER_CONCURRENCY=
WORKER_MAX_LOAD=0.85
# Minutos sin uso tras los que descarga los modelos de memoria.
WORKER_IDLE_UNLOAD_MIN=10
# Solo estos motores (vacío = todos los instalados), p. ej. tts,whisper
WORKER_ENGINES=
# Copias de la base de datos del VPS (monta la carpeta del NAS en docker-compose.worker.yml)
WORKER_BACKUP_DIR=
ENV
  chmod 600 .env.worker
fi

if [ "${WORKER_GPU:-false}" = "true" ]; then
  # Uncomment the GPU block of the compose file.
  sed -i 's/^    # deploy:/    deploy:/; s/^    #   resources:/      resources:/; s/^    #     reservations:/        reservations:/; s/^    #       devices:/          devices:/; s/^    #         - driver: nvidia/            - driver: nvidia/; s/^    #           count: 1/              count: 1/; s/^    #           capabilities: \[gpu\]/              capabilities: [gpu]/' docker-compose.worker.yml
fi

if ! docker compose -f docker-compose.worker.yml pull --quiet worker 2>/dev/null; then
  say "Compilando la imagen del worker (la primera vez tarda unos minutos)..."
  rm -rf src && git clone --depth 1 "$REPO" src >/dev/null 2>&1 || fail "No se pudo descargar el código de $REPO"
  docker build -t ghcr.io/nuxx-g/yomukai-worker:latest -f src/Dockerfile.worker src
  rm -rf src
fi
docker compose -f docker-compose.worker.yml up -d
say "Worker en marcha. Míralo en Ajustes › Administración › Workers (tarda un minuto en salir como conectado)."
say "Registros: cd $DIR && docker compose -f docker-compose.worker.yml logs -f"
