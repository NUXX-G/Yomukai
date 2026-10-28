# PLAN — Yomukai

Estado: retomar leyendo este fichero. Marcar `[x]` al cerrar. Cada fase: build + tests verdes, commits convencionales, `git push origin main`.

## Fase 1 — Fuentes
- [x] Eliminar Reaper Scans (clase, registro, README)
- [x] TuNovelaLigera: causa = ids con `?lcp_page0=1` rompían la URL `/ajax/chapters/`. Arreglado + paginación + tests con capturas reales
- [ ] AnimeFLV: **bloqueado**. `www3.animeflv.net` devuelve 522 (origen caído) y las búsquedas indican que el dominio original dejó de funcionar; los espejos (animeflv.ar/.gy/.one…) son sitios de terceros sin verificar, con adware. Sin HTML real no se tocan selectores → queda *experimental*, documentado
- [ ] Marmota Comics: **bloqueado**, no se encontró dominio verificable ni respuesta de ningún candidato
- [ ] ReadAllComics: **bloqueado** desde esta red (522/sin respuesta). Sin captura real no se escriben selectores
- [ ] Revisar fuentes existentes (MangaDex, Olympus, Asura, Flame, Royal Road, SkyNovels, AnimeAV1, LectorTMO, LectorManga, SurtrScans)
- [ ] Pantalla de diagnóstico de fuentes en Ajustes
- (`MediaType.COMIC` ya existe; falta filtro propio en Explorar)

## Fase 2 — Rendimiento
- [ ] Perfilado, Compose (keys, @Immutable, collectAsStateWithLifecycle), Coil, dispatchers, índices Room + migración, R8 + Baseline Profile, StrictMode debug

## Fase 3 — Explorar por fuente (scroll infinito + filtros genéricos)
- [ ]

## Fase 4 — Buscador inteligente
- [ ]

## Fase 5 — Lector/reproductor
- [ ]

## Fase 6 — Descargas seguras (incl. anime)
- [ ]

## Fase 7 — Tor
- [ ]

## Notas
- Sin acceso a sitios caídos o bloqueados desde el entorno de desarrollo no se inventan selectores (reglas 4 y 5).
