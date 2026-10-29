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
- [ ] ReadAllComics: se queda experimental/sin implementar (522 desde esta red). Reintento por Tor **no realizado** (ver Fase 7)
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
- [x] v1.2.0: el descargador prueba todos los servidores directos (el elegido primero), comprueba HLS antes de empezar y explica el fallo de cada servidor; al tocar un episodio fallido se elige servidor. Verificado en emuladores API 33 y 37: el HLS de AnimeAV1 (zilla-networks) responde 403 a clientes que no son navegador → se descarta; MP4Upload descarga y se reproduce sin red
- [ ] Sin verificar: descarga HLS completa con `VideoDownloadService` (el único servidor HLS bloquea esta red); las descargas MP4 no muestran notificación de progreso (siguen en segundo plano en el emulador API 37); descarga MP4 en API 26: `BAD_DECRYPT` de TLS en el emulador x86_64 con y sin Conscrypt, sin dispositivo real para comprobarlo

## Fase 7 — Tor
- [x] Librería elegida: **`info.guardianproject:tor-android`** (daemon Tor oficial empaquetado por Guardian Project) + `jtorctl`. Motivo: es la opción mantenida y madura (publicada en Maven Central, última versión 0.4.9.14 el 9‑oct‑2026) y expone TorService con SOCKS y túnel HTTP; Arti móvil de Guardian (`arti-mobile-ex`) no está publicado en Maven Central y Arti aún no iguala la compatibilidad de Tor C. Se fija **0.4.9.11** porque 0.4.9.12+ exigen compileSdk 37.1 (no instalado); subir cuando se actualice el SDK. Coste: +~23 MB de APK (ABIs arm64-v8a, armeabi-v7a, x86_64)
- [x] `TorManager`: arranca/para el daemon integrado según Ajustes, expone progreso de bootstrap y puertos; modo alternativo **Orbot** (SOCKS 127.0.0.1:9050 comprobado por socket)
- [x] OkHttp (fuentes, descargas, Coil, Media3): SOCKS de Tor con resolución DNS remota; **falla cerrado** (bloquea en vez de salir sin protección) mientras Tor no esté conectado. Opción «Fuentes, descargas y vídeo por Tor»
- [x] Navegador de captura por Tor con `androidx.webkit` `ProxyController` (túnel HTTP de Tor o SOCKS5; excluye los hosts de fuentes sin Tor); no carga ninguna página hasta confirmar el proxy; **indicador permanente** «Conectado a Tor / Conectando / Sin Tor»
- [x] Buscador/página de inicio del navegador configurable (por defecto DuckDuckGo .onion; alternativas DDG HTML, Brave Search, Ahmia)
- [x] Tor por fuente: interruptor «Usar Tor» en cada fuente (excluye su dominio, subdominios y CDN declarados en `extraHosts`). **Limitación documentada:** Cloudflare bloquea muchas salidas de Tor; esas fuentes suelen requerir conexión directa. El proxy del WebView es global del proceso: el WebView que resuelve Cloudflare también sale por Tor salvo los hosts excluidos
- [x] v1.2.0, verificado en emuladores API 33 y 37: Tor integrado llega al 100 %, el navegador sale por Tor (check.torproject.org → `IsTor: true`) y la .onion de DuckDuckGo carga (dirección correcta). Sin red muestra «Conectando a Tor (0 %)» y, a los 120 s, el error con motivo; al volver la red se recupera solo. Corregido el cierre al abrir la app con Tor activado (v1.1.x). En API 26 con WebView 69 avisa de que el WebView no admite proxy
- [ ] **Pendiente:** Tor en un móvil real (el navegador con Tor falló en el Android 17 del usuario con la v1.1.1 y no se reprodujo en emulador); (3) **ReadAllComics por Tor**: intenté descargar el Tor Expert Bundle oficial (SHA‑256 verificado) para capturar el sitio, pero el sistema denegó ejecutar `tor.exe`, así que no se reintentó. Para hacerlo: arranca Tor en tu equipo (`tor --SocksPort 9055`) y repite `curl -x socks5h://127.0.0.1:9055 https://readallcomics.com/` para capturar HTML real; con eso se implementa la fuente con tests

## Estado final
Fases 1–7 completadas salvo los puntos marcados con `[ ]`, todos explicados arriba. Build (`assembleDebug`, `assembleRelease`) y `testDebugUnitTest` en verde.

## Notas
- Sin acceso a sitios caídos o bloqueados desde el entorno de desarrollo no se inventan selectores (reglas 4 y 5).
