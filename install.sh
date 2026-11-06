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

# Settings are kept between updates. There is no admin password here: the first visit creates the admin with a
# one-time setup code (printed below), so nobody can log in with a default password.
if [ ! -f .env ]; then
  cat > .env <<EOF
# Yomukai: change these and run "docker compose up -d" again.
PORT=${PORT:-8080}
# Optional: with these two set, the admin is created (or its password reset) at start-up instead of by the setup page.
ADMIN_USER=admin
ADMIN_PASSWORD=
# Guests (no account, limited, deleted after a day): off by default.
GUESTS_ENABLED=${GUESTS_ENABLED:-false}
GUEST_DAYS=1
# Behind nginx, Caddy or Traefik on this machine: true.
TRUST_PROXY=false
# Optional MyAnimeList Client ID (https://myanimelist.net/apiconfig, type "android").
MAL_CLIENT_ID=
# Límites en el VPS (es compartido): memoria y núcleos máximos de Yomukai.
YOMUKAI_MEMORY=1g
YOMUKAI_CPUS=1.0
# Tope mensual de vídeo que pasa por el VPS, en GB (0 = solo medir).
BANDWIDTH_CAP_GB=0
# Avisos al admin (opcional): webhook de Discord y/o tema de ntfy.
DISCORD_WEBHOOK_URL=
NTFY_URL=
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
# First run: wait for the setup code and print the page that creates the admin.
TOKEN=""
for _ in $(seq 60); do
  TOKEN="$(docker compose exec -T yomukai cat /data/setup-token 2>/dev/null || true)"
  if [ -n "$TOKEN" ]; then break; fi
  if docker compose logs yomukai 2>/dev/null | grep -q "escuchando"; then
    sleep 3
    TOKEN="$(docker compose exec -T yomukai cat /data/setup-token 2>/dev/null || true)"
    break
  fi
  sleep 2
done
if [ -n "$TOKEN" ]; then
  say "Abre esta dirección para crear tu cuenta de administrador (el código sirve una sola vez):"
  say "  http://$ADDRESS/configurar?token=$TOKEN"
  say "(si usas un dominio con HTTPS, cambia http://$ADDRESS por él)"
fi
say "Actualizar: vuelve a ejecutar esta misma línea. Parar: cd $DIR && docker compose down"
