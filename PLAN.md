# PLAN — Yomukai

Estado: retomar leyendo este fichero. Marcar `[x]` al cerrar. Cada fase: build + tests verdes, commits convencionales, `git push origin main`.

## Fase 1 — Fuentes  ✅ (con excepciones documentadas)
- [x] Eliminar Reaper Scans y AnimeFLV (clase, registro, tests, README). `VideoExtractors` se conserva: lo usa AnimeAV1
- [x] TuNovelaLigera: causa = ids con `?lcp_page0=1` rompían la URL `/ajax/chapters/`. Arreglado + paginación + tests con capturas reales
- [x] Marmota Comics (marmota.me): Madara, capturado y probado (novedades, búsqueda con títulos alternativos, detalle, capítulos AJAX, páginas http→https). Activa por defecto
- [x] LectorTMO: el tema cambió (`a.chapter-card` → `/manga-chapter/…`, páginas en `#reader-strip`). Arreglado con tests; ya no es experimental
- [x] MangaDex, Asura (API), SkyNovels (API), Flame, Olympus, AnimeAV1: endpoints responden con la forma esperada desde esta red; tests existentes en verde
- [ ] Royal Road: devuelve el desafío «Just a moment» a clientes simples; depende del puente WebView en el dispositivo. Sin verificar aquí → usar Ajustes › Diagnóstico
- [ ] LectorManga y SurtrScans: sin respuesta desde esta red (dominio caído/bloqueado). Siguen *experimentales* y desactivadas
- [ ] ReadAllComics: se queda experimental/sin implementar (522 desde esta red). **Fase 7: reintentar capturarla a través de Tor**
- [x] Pantalla de diagnóstico de fuentes (Ajustes › Diagnóstico de fuentes): novedades, búsqueda, detalle, capítulos y páginas/texto/servidores
- [x] Filtro «Cómics» propio en Explorar/Buscar (`MediaType.COMIC` ya existía)

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
