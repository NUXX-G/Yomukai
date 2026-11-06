# Yomukai — instrucciones para Claude

Responde al usuario en **español**. Este repo es un lector/visor de manga, manhwa, novelas, cómics y anime:
`app/` (Android, Kotlin + Compose), `shared/` (Kotlin puro: fuentes, lógica común, tests), `server/` (Ktor + SQLite,
sirve la web), `web/` (Svelte 5 runes), `worker/` (trabajos pesados en el PC de casa).

## Antes de nada
1. Lee **`TRABAJO.md`**, empezando por «▶ DÓNDE ESTOY EXACTAMENTE»: ahí está la fase en curso, el **siguiente paso
   concreto**, lo que el usuario pidió por el camino, lo que necesita de él y cómo se trabaja en este equipo.
2. El encargo completo (fases 0 a 11, con todos los requisitos) está **íntegro en `docs/ENCARGO.md`**. Léelo antes de
   empezar una fase nueva.
3. No hace falta revisar el historial ni todo el código: el checklist de `TRABAJO.md` dice qué está hecho y dónde.

## Modo de trabajo que pidió el usuario
- **Autónomo**: seguir fase tras fase sin esperar su confirmación. El usuario no está mirando.
- Commits convencionales pequeños en español, con build y tests en verde antes de cada uno, y `git push origin main`
  a menudo: se pueden acabar los tokens en cualquier momento. No reescribir el historial ni hacer force push.
- **Actualizar «▶ DÓNDE ESTOY EXACTAMENTE» de `TRABAJO.md` en cada commit grande** (qué se acaba de hacer y el
  siguiente paso concreto), para que la siguiente sesión retome sin perder tiempo.
- Que funcione, sea seguro y eficiente. Seguridad por defecto: validar y limitar entradas, autorización en cada
  endpoint, nunca `{@html}` con datos de usuario, el HTML scrapeado son datos no fiables.
- Lo pesado (TTS de calidad, OCR, traducción, whisper, ffmpeg, huellas de audio, descargas a Jellyfin) **nunca en el
  VPS** (2 GB compartidos): va al worker o al dispositivo, y si el worker está apagado se degrada con elegancia.
- Cada funcionalidad grande va detrás de un interruptor de admin (`server/…/Features.kt`) que respetan la web y la app.
- Interfaz en español, mobile-first, modo oscuro, paridad web/app. Debe verse bien en web de escritorio, web móvil y
  la app Android, en Safari (iOS/macOS) y en Chrome/Edge/Firefox (Android/Windows/Linux), también instalada como PWA.
- La lógica común va a `shared/` como funciones puras con tests; los parsers se prueban con HTML/JSON reales en
  `test/resources`.
- Medir rendimiento antes y después de cada fase (tabla al final de `TRABAJO.md`, `scripts/bench.sh`).
- Seanime es GPLv3: no copiar su código ni sus recursos, solo ideas.
