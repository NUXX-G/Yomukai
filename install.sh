#!/usr/bin/env bash
# Yomukai web: install or update with one line.
#   curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.sh | bash
# Options (environment): YOMUKAI_DIR (default ~/yomukai), PORT (default 8080), GUESTS_ENABLED (true/false),
#   YOMUKAI_MODE=todo-en-uno to run the worker on this same machine too (only if it has RAM and CPU to spare).
#   curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.sh | YOMUKAI_MODE=todo-en-uno bash
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

# All in one: the worker joins the same compose with a token both sides share (never leaves this machine).
if [ "${YOMUKAI_MODE:-}" = "todo-en-uno" ]; then
  if ! grep -q '^LOCAL_WORKER_TOKEN=ykw_' .env; then
    TOKEN_LOCAL="ykw_$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 40)"
    sed -i '/^LOCAL_WORKER_TOKEN=/d; /^COMPOSE_PROFILES=/d' .env
    printf '
# Todo en uno: el worker en esta misma máquina.
COMPOSE_PROFILES=todo-en-uno
LOCAL_WORKER_TOKEN=%s
# Límites del worker (memoria y núcleos).
WORKER_MEMORY=2g
WORKER_CPUS=1.5
' "$TOKEN_LOCAL" >> .env
  fi
  say "Modo todo en uno: el worker se instala en esta misma máquina."
fi

# The ready-made image when it is published; otherwise it is built here from the code.
if ! docker compose pull --quiet yomukai 2>/dev/null; then
  say "Compilando la imagen (la primera vez tarda unos minutos)..."
  rm -rf src && git clone --depth 1 "$REPO" src >/dev/null 2>&1 || fail "No se pudo descargar el código de $REPO"
  docker build -t ghcr.io/nuxx-g/yomukai:latest src
  if grep -q '^COMPOSE_PROFILES=todo-en-uno' .env; then docker build -f src/Dockerfile.worker -t ghcr.io/nuxx-g/yomukai-worker:latest src; fi
  rm -rf src
elif grep -q '^COMPOSE_PROFILES=todo-en-uno' .env; then
  docker compose pull --quiet worker || fail "No se pudo descargar la imagen del worker"
fi
docker compose pull --quiet flaresolverr || true
docker compose up -d

PORT_IN_USE="$(grep -E '^PORT=' .env | cut -d= -f2)"
say ""
PORT_IN_USE="${PORT_IN_USE:-8080}"
case "$PORT_IN_USE" in *:*) ADDRESS="$PORT_IN_USE" ;; *) ADDRESS="localhost:$PORT_IN_USE" ;; esac
say "Yomukai está en marcha: http://$ADDRESS"
# The source canary: every 6 hours, from this machine (GitHub's addresses are blocked by Cloudflare).
if command -v crontab >/dev/null 2>&1; then
  CRON="17 */6 * * * cd $DIR && docker compose exec -T -e JAVA_OPTS=-Xmx200m yomukai /app/bin/server canary >/dev/null 2>&1 # yomukai-canary"
  ( crontab -l 2>/dev/null | grep -v "# yomukai-canary" || true; echo "$CRON" ) | crontab - && say "Canario de fuentes programado cada 6 horas (crontab)."
else
  say "Sin crontab: programa a mano el canario de fuentes (ver docs/VPS.md)."
fi

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
