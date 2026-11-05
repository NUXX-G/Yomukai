#!/usr/bin/env bash
# Detekt + ktlint sobre app/, shared/ y server/ (y worker/ cuando exista). Igual en local y en la CI.
#   scripts/lint-kotlin.sh            comprueba (detekt falla solo con problemas nuevos, no con los de su baseline;
#                                     ktlint aplica las reglas activas en .editorconfig, sin baseline)
#   scripts/lint-kotlin.sh --format   corrige solo lo que ktlint sabe corregir
#   scripts/lint-kotlin.sh --baseline regenera la baseline de detekt (solo si de verdad se aceptan los problemas actuales)
set -euo pipefail
cd "$(dirname "$0")/.."

DETEKT_VERSION=1.23.8
DETEKT_SHA=2ce2ff952e150baf28a29cda70a363b0340b3e81a55f43e51ec5edffc3d066c1
KTLINT_VERSION=1.8.0
KTLINT_SHA=a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75
TOOLS=.lint-tools
mkdir -p "$TOOLS"

fetch() { # url destino sha
  if [ ! -f "$2" ] || ! echo "$3  $2" | sha256sum -c --status; then
    curl -sSfL -o "$2.part" "$1"
    echo "$3  $2.part" | sha256sum -c --status || { echo "SHA-256 incorrecto: $1" >&2; rm -f "$2.part"; exit 1; }
    mv "$2.part" "$2"
  fi
}
fetch "https://github.com/detekt/detekt/releases/download/v$DETEKT_VERSION/detekt-cli-$DETEKT_VERSION-all.jar" \
  "$TOOLS/detekt-cli-$DETEKT_VERSION-all.jar" "$DETEKT_SHA"
fetch "https://github.com/pinterest/ktlint/releases/download/$KTLINT_VERSION/ktlint" "$TOOLS/ktlint-$KTLINT_VERSION" "$KTLINT_SHA"

SRC=$(for d in app/src shared/src server/src worker/src; do if [ -d "$d" ]; then printf '%s,' "$d"; fi; done)
SRC=${SRC%,}
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java

if [ "${1:-}" = "--baseline" ]; then
  "$JAVA" -jar "$TOOLS/detekt-cli-$DETEKT_VERSION-all.jar" --input "$SRC" --config config/detekt.yml \
    --build-upon-default-config --excludes '**/test/resources/**,**/build/**' --create-baseline --baseline config/detekt-baseline.xml
  echo "Baseline de detekt regenerada en config/"
  exit 0
fi
if [ "${1:-}" = "--format" ]; then
  "$JAVA" -jar "$TOOLS/ktlint-$KTLINT_VERSION" --relative --format \
    'app/src/**/*.kt' 'shared/src/**/*.kt' 'server/src/**/*.kt' 'worker/src/**/*.kt' '**/*.kts' '!**/build/**'
  exit $?
fi

status=0
echo "== detekt"
"$JAVA" -jar "$TOOLS/detekt-cli-$DETEKT_VERSION-all.jar" --input "$SRC" --config config/detekt.yml \
  --build-upon-default-config --excludes '**/test/resources/**,**/build/**' --baseline config/detekt-baseline.xml || status=1
echo "== ktlint"
"$JAVA" -jar "$TOOLS/ktlint-$KTLINT_VERSION" --relative \
  'app/src/**/*.kt' 'shared/src/**/*.kt' 'server/src/**/*.kt' 'worker/src/**/*.kt' '**/*.kts' '!**/build/**' || status=1
exit $status
