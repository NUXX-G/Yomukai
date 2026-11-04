#!/usr/bin/env bash
# Yomukai web: install or update with one line.
#   curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.sh | bash
# Options (environment): YOMUKAI_DIR (default ~/yomukai), PORT (default 8080), GUESTS_ENABLED (true/false).
set -euo pipefail

REPO="https://github.com/NUXX-G/Yomukai"
RAW="https://raw.githubusercontent.com/NUXX-G/Yomukai/main"
DIR="${YOMUKAI_DIR:-$HOME/yomukai}"

say() { printf '\033[1;38;5;209m%s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "Necesitas Docker: https://docs.docker.com/engine/install/"
docker compose version >/dev/null 2>&1 || fail "Necesitas Docker Compose v2 (docker compose)."
docker info >/dev/null 2>&1 || fail "Docker no está en marcha, o tu usuario no puede usarlo (prueba con sudo)."

mkdir -p "$DIR"
cd "$DIR"
say "Instalando Yomukai en $DIR"

curl -fsSL "$RAW/docker-compose.yml" -o docker-compose.yml

# Settings are kept between updates; the admin password is generated only once.
if [ ! -f .env ]; then
  # head closes the pipe early on purpose: pipefail must not count that as a failure.
  RAW_PASSWORD="$(set +o pipefail; LC_ALL=C tr -dc 'a-km-z2-9' </dev/urandom | head -c 16 || true)"
  PASSWORD="$(printf '%s' "$RAW_PASSWORD" | sed 's/.\{4\}/&-/g; s/-$//')"
  cat > .env <<EOF
# Yomukai: change these and run "docker compose up -d" again.
PORT=${PORT:-8080}
ADMIN_USER=admin
ADMIN_PASSWORD=$PASSWORD
GUESTS_ENABLED=${GUESTS_ENABLED:-true}
GUEST_DAYS=1
# Behind nginx, Caddy or Traefik on this machine: true.
TRUST_PROXY=false
# Optional MyAnimeList Client ID (https://myanimelist.net/apiconfig, type "android").
MAL_CLIENT_ID=
EOF
  chmod 600 .env
  NEW=1
fi

# The ready-made image when it is published; otherwise it is built here from the code.
if ! docker compose pull --quiet yomukai 2>/dev/null; then
  say "Compilando la imagen (la primera vez tarda unos minutos)..."
  rm -rf src && git clone --depth 1 "$REPO" src >/dev/null 2>&1 || fail "No se pudo descargar el código de $REPO"
  docker build -t ghcr.io/nuxx-g/yomukai:latest src
  rm -rf src
fi
docker compose pull --quiet flaresolverr || true
docker compose up -d

PORT_IN_USE="$(grep -E '^PORT=' .env | cut -d= -f2)"
say ""
PORT_IN_USE="${PORT_IN_USE:-8080}"
case "$PORT_IN_USE" in *:*) ADDRESS="$PORT_IN_USE" ;; *) ADDRESS="localhost:$PORT_IN_USE" ;; esac
say "Yomukai está en marcha: http://$ADDRESS"
if [ "${NEW:-0}" = 1 ]; then
  say "Usuario: admin"
  say "Contraseña: $(grep -E '^ADMIN_PASSWORD=' .env | cut -d= -f2)"
  say "(guardada en $DIR/.env)"
fi
say "Actualizar: vuelve a ejecutar esta misma línea. Parar: cd $DIR && docker compose down"
