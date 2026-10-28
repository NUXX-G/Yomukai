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
- [x] Room: índices (media inLibrary+dateAdded, chapters sourceId+mediaId+sortIndex, history readAt, downloads status+createdAt) con MIGRATION_2_3 y esquema v3 exportado
- [x] Compose: `collectAsStateWithLifecycle` en todas las pantallas, `@Immutable` en modelos y estados de UI, `rememberImageRequest` memoizado (las keys de listas ya eran estables)
- [x] Coil: precarga de las 3 páginas siguientes en el lector de manga; caché 25 % RAM / 512 MB disco ya dimensionadas; Coil limita la decodificación al tamaño de la vista
- [x] StrictMode solo en debug (log); R8 + reglas en release (`assembleRelease` verificado, APK 4,8 MB); perfil base escrito a mano (`baseline-prof.txt`, reglas comodín) + profileinstaller
- [ ] No verificable aquí: medir fps/jank en dispositivo y generar el perfil base automático con Macrobenchmark (requiere dispositivo/emulador). El parseo/red ya corre en `Dispatchers.IO` (fuentes) y los ViewModels lanzan en IO/Default

## Fase 3 — Explorar por fuente (scroll infinito + filtros genéricos)
- [x] `SourceFilter` genérico (`SelectFilter`/`MultiSelectFilter`, `FilterValues`) + `CatalogueSource.filters` y `browse(page, values)`; hoja de filtros genérica en la pantalla de catálogo de cada fuente (icono de filtros)
- [x] Filtros reales: MangaDex (orden, estado, tipo por idioma original, género por tag, idioma de capítulos), Asura (orden, estado, tipo), Marmota (orden, estado, editorial), TuNovelaLigera (orden). URLs verificadas con curl y cubiertas por tests
- [x] Scroll infinito automático (el pie dispara la página siguiente, sin botón); LectorTMO ahora pagina con `/manga/page/N/`
- [ ] Limitado: Olympus, Flame y SkyNovels no exponen paginación/filtros verificables (solo listado reciente, sin scroll); Royal Road y AnimeAV1 pagian pero sin filtros declarados todavía; el filtro de género de Asura no tiene parámetro de API conocido

## Fase 4 — Buscador inteligente
- [x] Motor `SmartSearch` (puro, con tests): normalización Normalizer NFD + minúsculas + sin puntuación, tolerancia a erratas (Levenshtein por palabra + trigramas), coincidencia parcial/prefijo, títulos alternativos (`MediaItem.altTitles`: MangaDex, Asura, Marmota), puntuación de relevancia
- [x] Las fuentes solo buscan literalmente, así que se consultan variantes relajadas (palabra más larga / recortada) si hay <5 resultados y luego se reordena en cliente
- [x] Resultados ordenados por relevancia y agrupados entre fuentes (clave sin sufijos «(2026)»); chips «También en»; aviso de fuentes sin respuesta
- [x] Historial de búsquedas (20, persistente) y sugerencias (historial + títulos de la biblioteca); debounce 400 ms y cancelación de la búsqueda anterior

## Fase 5 — Lector/reproductor
- [x] Lector continuo (manga vertical, paginado y novela): el capítulo siguiente se añade al quedar ~3 elementos, con separador «Capítulo X»; progreso e historial siguen al capítulo visible; en modo paginado pasar de la última página entra en el siguiente. Opción para desactivarlo
- [x] Índice de capítulos/episodios (hoja inferior): búsqueda sin tildes, actual resaltado, leídos marcados, salto directo — en manga, novela y reproductor
- [x] Manga/cómic: marcadores (Room v4), auto-scroll con velocidad, recorte de bordes blancos (Coil Transformation + algoritmo con tests), filtros gris/sepia/invertir, bloqueo de orientación, ajuste pantalla/ancho/alto
- [x] Novela: lectura en voz alta (TextToSpeech, continúa entre capítulos, velocidad ajustable), marcadores, búsqueda en el capítulo con coincidencias
- [x] Anime: siguiente episodio automático (misma fuente de vídeo si existe, directa), índice de episodios, velocidad 0,5–2x, salto +85 s, imagen en imagen (manual + automática en Android 12+), posición recordada por episodio y marcado como visto al 90 %
- [ ] No verificable aquí (sin dispositivo): comportamiento táctil real del scroll continuo, TTS con voces instaladas, PiP; compilan y la lógica pura tiene tests

## Fase 6 — Descargas seguras (incl. anime)
- [x] Anime offline: HLS con Media3 `DownloadManager` + `VideoDownloadService` (primer plano) y MP4 directo con reanudación por HTTP Range; integrado en la cola persistente de Room (migración 4→5); el reproductor usa la copia descargada si existe
- [x] «Descargar todo», «no leídos/no vistos» y «un rango» desde el detalle (también en anime)
- [x] Seguridad (`DownloadSecurity`, con tests): solo HTTPS (o .onion http por Tor), Content-Type de imagen/vídeo, límites de tamaño, nombres saneados + rutas hash, comprobación anti path-traversal, almacenamiento privado, longitud declarada = escrita, cabecera MP4 válida, imagen decodificable, todas las páginas presentes antes de COMPLETED
- [x] Límite de descargas simultáneas (1–4) y «solo Wi-Fi» en Ajustes; reanudación al reiniciar la app; todo el tráfico (también Media3 y reproductor) usa el cliente OkHttp de la app → respeta DNS seguro y proxy/Tor
- [ ] No verificable aquí: descarga real de HLS/MP4 de AnimeAV1 y el servicio en primer plano en Android 14 (sin dispositivo)

## Fase 7 — Tor
- [ ]

## Notas
- Sin acceso a sitios caídos o bloqueados desde el entorno de desarrollo no se inventan selectores (reglas 4 y 5).
