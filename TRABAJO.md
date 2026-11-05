# TRABAJO — traspaso de la tanda actual

Léelo primero si retomas esto. `PLAN.md` es el histórico de fases; este fichero es lo que se está haciendo ahora.

## ENCARGO GRANDE (2026-11-05): fases 0 a 11
Texto íntegro y detalle de cada punto: `docs/ENCARGO.md` (léelo entero antes de seguir).
Reglas clave: fase a fase y en orden; al acabar cada fase PARAR, resumir (hecho, pendiente, decisiones propias, qué probar a mano) y esperar el OK del usuario.
Commits convencionales pequeños, build+tests verdes antes de cada commit, `git push origin main` a menudo (se pueden acabar los tokens: subir por tramos y, si se cortó, seguir donde indique este checklist).
Lo pesado nunca en el VPS (2 GB, compartido con el portfolio): va al worker de casa o al dispositivo. Feature flag por funcionalidad grande. Medir rendimiento antes/después de cada fase (tabla abajo).

### Extra pedido por el usuario
- [ ] Fuente Bilibili Manga: BLOQUEADA a la espera del usuario. Su API (twirp) cifra las respuestas con AES y clave calculada en WASM (buvid + plataforma + id) para impedir el acceso automatizado; sin eso devuelve code 99. No se elude (misma regla que el DRM de anime.nexus). Alternativa propuesta: variante «MangaDex 中文» (~9.600 capítulos en zh/zh-hk) reutilizando MangaDexSource

### Fase 0 — Limpieza y base
- [x] 0.1 `server/data/`, `t.html` y `.idea/` fuera del repo e ignorados; `secret.key` borrado (se regenera). Nota: la clave vieja quedó en el historial; el VPS usa la suya de `/data` (o `SECRET`), así que no le afecta
- [x] 0.2 AnimeFLV (`animeflv.or.at`) funciona → PLAN.md actualizado, no se borra
- [x] 0.3 Migraciones versionadas: `server/…/Migrations.kt` (`schema_version`, pasos numerados sin huecos e idempotentes, cada uno en su transacción; rechaza bases de una versión más nueva). La 1 es el esquema actual + columnas añadidas antes; lo nuevo se añade como migración 2, 3… al final de `Migrations.ALL`
- [x] 0.4 Copias nocturnas: `Backups.kt` (`VACUUM INTO` en caliente + gzip, `BACKUP_HOUR`=4, 14 días, la más nueva nunca se borra, `BACKUP_DIR`), copia a `BACKUP_COPY_DIR` (carpeta del NAS montada) con reintento cada hora; comandos `server backup | backups | restore <archivo|latest>`; `ServerLock` (`data/.lock`) impide restaurar con el server en marcha. Documentado en README
- [x] 0.5 CI (`.github/workflows/ci.yml`): 4 trabajos en cada push/PR — detekt 1.23.8 (baseline en `config/detekt-baseline.xml`) + ktlint 1.8.0 (reglas de maquetación desactivadas en `.editorconfig`, el resto aplicado con `scripts/lint-kotlin.sh --format`), shared+server (tests + installDist), app (assembleDebug + testDebugUnitTest + APK como artefacto), web (eslint + svelte-check + build). Dependabot semanal (Gradle, npm, Actions) y mensual (Docker). Primera ejecución en verde

### Fase 1 — VPS + worker + dispositivo
- [ ] 1.1 Docker con límite de RAM (1 GB por defecto) y CPU, JVM ajustada, cachés acotadas, medición en reposo y carga, guía de swapfile
- [ ] 1.2 Módulo `worker/` (token revocable, capacidades, conexión saliente). Incluir: el worker se trae las copias de seguridad del VPS al NAS (endpoint autenticado), como alternativa a `BACKUP_COPY_DIR`
- [ ] 1.3 Cola de trabajos en SQLite (tipos, prioridades, reintentos, caducidad, asignación), resultados cacheados con LRU
- [ ] 1.4 Worker: nice/límites, pausa si la máquina está ocupada, descarga de modelos inactivos
- [ ] 1.5 Estado del worker visible y funciones disponibles en web y app
- [ ] 1.6 Medición de tráfico del proxy con tope mensual y avisos 80/100 %

### Fase 2 — Seguridad
- [ ] 2.1 Cabeceras (CSP, HSTS, etc.) y cookies seguras
- [ ] 2.2 Rate limiting por usuario e IP; bloqueo progresivo de login
- [ ] 2.3 Auditoría de autorización con tests (usuarios, invitados, workers)
- [ ] 2.4 Sesiones visibles y cerrables; 2FA TOTP con códigos de recuperación
- [ ] 2.5 Invitaciones por enlace (caducidad, un solo uso)
- [ ] 2.6 Registro de auditoría de admin/moderación
- [ ] 2.7 Asistente de primera configuración (sin contraseña de admin por defecto)

### Fase 3 — Resistencia a cambios en las webs
- [ ] 3.1 Canario por cron en el VPS (Discord + ntfy)
- [ ] 3.2 Validación de resultados y fuentes degradadas ocultas solas
- [ ] 3.3 Fuentes declarativas en JSON firmadas con Ed25519
- [ ] 3.4 Motores genéricos Madara, WordPress, Next.js, SvelteKit
- [ ] 3.5 Cadena de respaldo de extracción
- [ ] 3.6 Dominios en config y seguimiento de redirecciones
- [ ] 3.7 yt-dlp en el VPS (solo extracción) + WebView oculto de respaldo en la app
- [ ] 3.8 Auto-reparación con Claude Code headless en el worker (abre PR, nunca merge)

### Fase 4 — Obra unificada y diferenciadores
- [ ] 4.1 Modelo OBRA (IDs AniList/MAL), capítulos unidos, migración sin pérdida, resultados progresivos
- [ ] 4.2 Deduplicación en todas partes (marcar la duda)
- [ ] 4.3 ¿Dónde sigo? anime ↔ manga ↔ novela, mapeo corregible con historial
- [ ] 4.4 Escudo anti-spoilers
- [ ] 4.5 Saltar opening/ending por huella de audio (worker)
- [ ] 4.6 Guía de relleno
- [ ] 4.7 Calendario de estrenos unificado

### Fase 5 — Rediseño (web y app)
- [ ] 5.0 Analizar `docs/referencias/` → `ANALISIS.md` (si no hay capturas, PARAR y pedirlas)
- [ ] 5.1 Inicio sin fuentes  - [ ] 5.2 Tokens de diseño en shared/  - [ ] 5.3 Navegación
- [ ] 5.4 Inicio tipo Netflix  - [ ] 5.5 Tarjetas  - [ ] 5.6 Ficha de obra
- [ ] 5.7 Páginas Anime/Manga/Novelas con explorador  - [ ] 5.8 Búsqueda unificada
- [ ] 5.9 Reproductor y lectores  - [ ] 5.10 Calidad/accesibilidad  - [ ] 5.11 Capturas Playwright antes/después en `docs/capturas/`

### Fase 6 — Social
- [ ] 6.1 Salas  - [ ] 6.2 Watch party WebSocket  - [ ] 6.3 Chat de sala  - [ ] 6.4 Perfiles
- [ ] 6.5 Bibliotecas visibles con privacidad  - [ ] 6.6 Feed y presencia  - [ ] 6.7 Recomendar y valorar
- [ ] 6.8 Foro  - [ ] 6.9 Moderación  - [ ] 6.10 Notificaciones (web, ntfy, Discord)  - [ ] 6.11 Logros

### Fase 7 — Temas
- [ ] 7.1 Tema = JSON de tokens validado  - [ ] 7.2 Temas de serie  - [ ] 7.3 Editor con contraste WCAG
- [ ] 7.4 Galería entre amigos  - [ ] 7.5 Sin CSS libre; fondos por ImageShrinker

### Fase 8 — Voces
- [ ] 8.1 Interfaz TTS con tres niveles  - [ ] 8.2 Script de comparación de motores (worker)  - [ ] 8.3 Voces por personaje
- [ ] 8.4 Pregeneración cacheada  - [ ] 8.5 Android con pantalla apagada (MediaSessionService)  - [ ] 8.6 Web MediaSession

### Fase 9 — OCR, voz en cómics, autoscroll, traducción, idiomas
- [ ] 9.1 Pipeline OCR  - [ ] 9.2 Cómics en voz alta  - [ ] 9.3 Autoscroll inteligente
- [ ] 9.4 Traducción de bocadillos  - [ ] 9.5 Modo aprendizaje de idiomas

### Fase 10 — Jellyfin, Kavita/Komga
- [ ] 10.1 Config admin  - [ ] 10.2 Descargar a Jellyfin  - [ ] 10.3 Descarga automática  - [ ] 10.4 Jellyfin como fuente
- [ ] 10.5 Progreso bidireccional  - [ ] 10.6 Salas con Jellyfin  - [ ] 10.7 CBZ + ComicInfo  - [ ] 10.8 Tests con Jellyfin falso

### Fase 11 — Pulido, instalación y documentación
- [ ] 11.1 Panel de admin  - [ ] 11.2 Releases automáticas  - [ ] 11.3 Continuar en otro dispositivo
- [ ] 11.4 Importar/exportar  - [ ] 11.5 UX  - [ ] 11.6 Dos docker-compose + guía Tailscale  - [ ] 11.7 ARCHITECTURE.md, README

### Presupuesto de rendimiento
| Momento | RAM server reposo | RAM bajo carga | CPU | API p95 | Bundle web (JS+CSS gz) | APK release |
|---|---|---|---|---|---|---|
| Antes de Fase 0 (2026-11-05, local Windows, JVM SerialGC) | 185 MB | 257 MB | 4,0 s de CPU en 300 peticiones + 4 novedades | 5 ms local · 575 ms novedades reales | 84 KB (JS 74,9 + CSS 9,2) + hls.js 180,5 KB aparte | 34,7 MB (v1.13.0; ~23 MB son Tor) |
| Después de Fase 0 (mismo equipo) | 181 MB | 260 MB | 5,0 s | 5 ms local · 536 ms novedades | 84 KB (sin cambios) + hls.js 180,5 KB | sin cambios de código de la app (solo formato) |

Cómo medir: `./gradlew :server:installDist`, `cd web && npm run build`, `bash scripts/bench.sh` (o `scripts/bench.sh https://tu-vps PID` en el VPS con `BENCH_USER`/`BENCH_PASSWORD`). En el VPS real aún no se ha medido (no tengo acceso): pendiente de que el usuario lance el script allí.

## Versión web (en curso, pedida por el usuario el 2026-11-03)
Objetivo: lo mismo que la app Android (anime, manga, manhwa, novela) en el VPS del usuario, instalable con una línea de Docker, y en GitHub para que cualquiera lo monte.
- Cuentas con usuario y contraseña (sesión que no caduca) para amigos; el admin las crea.
- Invitados sin contraseña: pueden hacer de todo y sus datos se borran a los 7 días.
- Exportar e importar los datos (también de invitado a cuenta).
- Límites para invitados (peticiones/minuto, vídeos simultáneos) para no saturar el VPS.

Estructura:
- `shared/`: fuentes, búsqueda, descubrimiento y metadatos, Kotlin/JVM puro. La app y el servidor usan la misma copia. La red es la interfaz `HttpFetcher`.
- `server/`: Ktor + SQLite. API, usuarios, invitados, export/import, proxy de imágenes y vídeo (Range y HLS), FlareSolverr para Cloudflare.
- `web/`: SPA instalable (PWA) con el diseño de la app.
- Docker multi-stage + `docker-compose.yml` + `install.sh` (una línea).

Fases:
- [x] 1. Sacar la lógica a `shared/` sin romper Android (131 pruebas en verde, release probada)
- [x] 2. Servidor: API de fuentes, cuentas, invitados, export/import, proxy (probado en local con curl y 6 pruebas)
- [x] 3. Web: Inicio, Buscar, Biblioteca, ficha, lectores de manga y novela
- [x] 4. Reproductor de anime en la web
- [x] 5. Docker, instalación en una línea y README

## Cosas que mejorar (sin prioridad, el usuario lo dirá)
- Voces de las novelas: Piper suena robótico y las voces no gustan. Opciones: Kokoro/XTTS en el servidor (más CPU) o un servicio de pago con estilos (ElevenLabs, Azure). También falta un control de volumen propio.

## Tanda v1.13.0 (release completa, pedida por el usuario el 2026-11-05)
- [x] Web: descargas de anime (el marco oculto se borraba al primer load vacío y cancelaba la descarga a 1 s)
- [x] iOS: botón de pantalla completa siempre; en iPhone, modo inmersivo propio (Safari no deja a las webs)
- [x] Velocidad del desplazamiento automático a mano mientras se desplaza (web y Android; Android lo estrena en novela)
- [x] Obras sin capítulos ocultas y las bien numeradas primero (`ChapterHealth`, `NoChaptersException`); el servidor sondea en segundo plano
- [x] Fuentes: NovelCool ES, EN y Novels EN (UA propio: con UA de navegador da enlaces de anuncios); fuera WEBTOON ES/EN y TuNovelaLigera
- [x] LectorXD: probado y descartado (capítulos y páginas tras Cloudflare; el usuario pidió dejarlo)
- [x] Voces naturales: Piper en el Docker (`/api/tts`, voces descargadas a `/data/voices` al primer uso), selector de voz y voces por personaje (web y Android); sin Piper, voces del navegador/móvil
- [x] Velocidad: precarga de páginas en servidor, una descarga por imagen, páginas enormes reducidas al ancho de pantalla, stale-while-revalidate, capítulos en paralelo (Olympus, YSK), descargas con más trozos a la vez; Android precarga 8 páginas a disco

Sin probar: Piper de verdad (aquí no se pudo ejecutar el binario); si falla, la web y la app caen solas a las voces del navegador/móvil.

## Tanda v1.8.0 (release completa, autorizada por el usuario)
- [x] Aleatorio siempre con fuente: solo obras que están en tus fuentes, «Disponible en» y abrir directo; cambiar tipo/género tira otra vez
- [x] Reloj minimalista también en el lector de manga/manhwa y de novela
- [x] Reproductor sin barras del sistema y en horizontal sin rotación automática (botón para vertical)
- [x] Notas para cómics y novelas: MangaUpdates (manga/manhwa/manhua/novela asiática) y Open Library (cómic occidental, novela publicada), sin cuenta
- [x] Interfaz negra «sumi y brasa», tema oscuro por defecto, barras del sistema según el tema de la app
- [x] Botón «Empezar a leer» de tamaño fijo (antes parpadeaba al hacer scroll)
- [x] Probado en Android 8, 13 y 17: 13/14 fuentes OK en los tres (LectorTMO: título de prueba sin capítulos)

Notas de v1.8.0: Google Books ya no admite uso sin clave. En Android 8 con 3 botones la barra de navegación lleva un fondo casi negro. El primer vídeo a pantalla completa muestra el aviso de Android «Viewing full screen»: es del sistema y sale una sola vez.

## Tanda v1.7.0 a v1.7.2 (publicadas como pre-release)
- [x] Explorar y Descubrir unidos en Inicio: «Para ti» dentro y botón de aleatorio en la cabecera
- [x] Tarjetas sin etiquetas grandes sobre la portada; títulos a 2 líneas, sin estado debajo
- [x] Ficha: color de la portada como acento (Material You propio, `CoverTheme`), pestañas Capítulos/Relacionadas
- [x] Capítulos como «Cap. 133», fechas e iconos atenuados, botón flotante que no tapa la lista y dice desde qué capítulo sigue
- [x] Metadatos: AniList (nota, estado, sinopsis, relaciones) con Kitsu de respaldo, taxonomía de géneros y etiquetas por palabras clave
- [x] Avisos de saltos/duplicados/desorden en la lista y de posible abandono (en emisión y más de 2 años sin capítulos)
- [x] Relacionadas entre formatos: precuelas, secuelas, adaptaciones (anime, novela, manga...)
- [x] Reproductor: HUD oculto con reloj, pantalla encendida, foco de audio (pausa Spotify); los lectores no tocan el audio
- [x] Recomendadas «no encontradas»: más nombres, coincidencia cercana marcada, caché y opción de buscar en todas
- [x] Géneros excluidos: espera corta antes de mostrar y caché de Inicio guardada sin excluidas

Regla v1.7.2: manhwa/manga y novela del mismo nombre son obras distintas en todo (búsqueda, «Para ti», notas de MAL/Kitsu, relaciones). Usar `TitleMatcher.familyKey` y `MalClient.sameKind`. AniList no tiene la novela de Solo Leveling: es correcto que su ficha no tenga nota de AniList.

Notas de v1.7.0: los metadatos se guardan una semana en `metadata_cache.json` (sin migración de Room). MAL (v1.7.1): la Client ID de la app «Yomukai» (cuenta NUXX-A, tipo android) está en `local.properties` como `mal.clientId`, que no se sube; sin ella se usa Jikan, que no responde desde la red de desarrollo. Hay que tenerla en local.properties al compilar cada release. Arranque en frío igual que v1.6.0 (≈4,1 s debug en emulador; 2 s release). AniList pide ~30 peticiones/min; la ficha y «Similares» comparten una sola. Probado en el emulador API 33 (Inicio, ficha, relacionadas, aleatorio, abrir recomendada). Reproductor probado con un episodio real: foco de audio GAIN y KEEP_SCREEN_ON confirmados con dumpsys. Sin probar: una app de música real sonando a la vez.

## Tanda v1.6.0 (publicada como release completa con autorización del usuario)
- [x] «Recomiéndame uno» falla: nunca dar error si hay algo que mostrar
- [x] Para ti: portadas centradas y usar también el historial
- [x] TuNovelaLigera da 404
- [x] Rendimiento: lo ideal es 1-3 s al abrir una fuente
- [x] Interfaz bonita e intuitiva
- [x] Buscar más errores

## Tanda v1.5.0
- [x] Carpeta pública `Download/Yomukai` con metadatos y formato a elegir
- [x] Descargas más rápidas (anime y WEBTOON) y el fallo puntual de AnimeFLV
- [x] Al leer un capítulo se marcan los anteriores; marcar y desmarcar a mano
- [x] Búsqueda: títulos que están en varias fuentes y no salen
- [x] Buscador dentro de cada fuente
- [x] Excluir géneros, global y por fuente
- [x] Apartado Descubrir: recomendaciones y aleatorio con filtros
- [x] Filtros en todas las fuentes que lo permitan (AnimeFLV no tiene parámetros en su web)

Notas de v1.5.0: en emuladores con poca memoria el WebView puede morir; todo WebView debe tener `onRenderProcessGone`. En Android 9 o anterior el permiso de archivos hay que aceptarlo en pantalla: `pm grant` no sirvió en las pruebas. los vídeos HLS no se copian a la carpeta pública porque viven como segmentos en la caché de Media3 (Voe y MP4Upload sí, son MP4). Borrar en la app no borra la copia pública.
- [x] Publicar v1.5.0 (release completa, no pre-release)

## Tanda v1.4.0
- [x] Episodios de anime que no cargan (unos sí y otros no)
- [x] Títulos similares (mismo tipo/temática: Evangelion -> Gridman, Gurren Lagann)
- [x] Alternativas: el mismo título en otras fuentes, verificando que es la misma obra
- [x] WEBTOON en español además del inglés
- [x] Navegador: pantalla completa tipo F11
- [x] Quitar LectorManga y SurtrScans
- [x] Fuente animeflv.or.at
- [x] Indicador de carga que se queda como un punto
- [x] Buscar errores y mejorar rendimiento
- [ ] Publicar pre-release v1.4.0

### Hallazgos de v1.4.0
- Anime: el HLS de zilla-networks da 403 en los segmentos (bloqueo duro de Cloudflare, no saltárselo). MP4Upload pierde muchas conexiones en su nodo `a3` y responde al instante cuando conecta: por eso el cliente de vídeo reintenta 8 veces con 3 s. Algunos vídeos de Voe están borrados en origen (404).
- Indicador: el de Compose se congela si Android tiene las animaciones reducidas. `AppSpinner` cuenta tiempo real de fotogramas; hay que usarlo siempre.
- En Android una `}` sin escapar en una regex cierra la app al arrancar, y las pruebas JVM no lo ven. Pasar siempre `LiveSourcesTest` en un emulador.
- Otras fuentes: un nombre corto no prueba nada («Leviathan» son varias obras). `MatchConfidence.SAME_WORK` exige autores iguales, nombre largo o anime.
- Las claves de `LazyRow`/`LazyColumn` duplicadas cierran la app: nunca usar el título como clave.
- `MigrationTest` no arranca: falla `kotlinx.serialization` en la herramienta de Room (AbstractMethodError), pendiente de arreglar. La migración 6 a 7 se comprobó actualizando de verdad de 1.3.0 a 1.4.0.

## Cómo publicar una release
Mismo formato que las anteriores, para que se instale encima y conserve los datos:
`assembleRelease`, firmar `app-release-unsigned.apk` con `~/.android/debug.keystore` (`pass:android`; certificado `db17e934...`), subir `Yomukai-vX.Y.Z.apk` y su `.sha256` con `gh release create --prerelease`. Subir antes `versionCode` y `versionName`.

## Reglas de trabajo
- Nada de `colmena`: se trabaja en este repo y se sube a `main`. Commits pequeños y push frecuente.
- Esta máquina **sí compila**: JDK en `C:\jdk-21.0.7`, SDK en `%LOCALAPPDATA%\Android\Sdk`.
- Emuladores: `Medium_Phone_API_37.0`, `Yomu_API33`, `Yomu_API26`.

```bash
export JAVA_HOME=/c/jdk-21.0.7 ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew testDebugUnitTest assembleDebug --console=plain
```

## Encargo del usuario (en orden)
1. Arreglar los fallos detectados en la revisión (lista abajo).
2. Dos fuentes nuevas: `m.webtoons.com` y `zonatmo.org`.
3. Comprobar que todas las fuentes funcionan y que el scraping va fino en todos los Android.
4. Scraping más rápido y fiable: tarda mucho y a veces no carga con la web bien.
5. Anime: quitar el «página 145 de 14124242».
6. Navegador integrado: atrás/adelante, más opciones, que vaya bien.
7. Cuando todo eso esté, buscar errores y dejar la app bien del todo.

## Estado
- [x] Fuga de Tor por el WebView de Cloudflare
- [x] Posición del episodio anterior guardada en el siguiente (se marca visto solo)
- [x] Pantalla negra del reproductor: sin `onPlayerError`
- [x] Búsqueda: al borrar el texto no vuelve historial/sugerencias
- [x] Búsqueda: anime escondido bajo el manga del mismo título
- [x] Anime: texto «Página X de Y»
- [x] Favoritos: «Leyendo» se guarda como estado de publicación
- [x] «Solo Wi-Fi» no frena un MP4 en curso
- [x] Fallback de Cloudflare devuelve HTML en APIs JSON
- [x] Dependencias sin usar y README
- [x] Fuente WEBTOON
- [x] Fuente ZonaTMO
- [x] Rendimiento de red
- [x] Navegador integrado
- [x] Diagnóstico real de fuentes en emuladores API 26/33/37
- [x] Revisión final de errores

Descartado: fallo de notificaciones con la app cerrada; el usuario confirma que funcionan.

## Resultado de la tanda (v1.3.0)
- Diagnóstico real (`LiveSourcesTest`, ver README) en API 26, 33 y 37: 12 de 14 fuentes OK, con portada y página descargadas.
- LectorManga y SurtrScans: sus dominios ya no resuelven (siguen desactivadas).
- LectorTMO sale FAIL solo si el primer título de la lista no tiene capítulos; con otros títulos sí hay capítulos, lo normal.

## Pendiente
- Sin verificar: Tor en un móvil real y la imagen del vídeo en un móvil real (en el emulador sin ventana se oye y avanza, pero la captura sale negra).
- El servidor HLS de AnimeAV1 da 403; se salta solo a MP4Upload tras unos segundos.
- La firma de release no existe: CI solo genera APK de debug.
- Migración a web: el usuario la pedirá más adelante.

## Hallazgos técnicos
- MangaDex: con User-Agent de navegador la API redirige a `/edge/challenge`; hay que usar el UA propio de la fuente.
- Royal Road: 403 «Just a moment» a clientes simples; depende del WebView.
- LectorManga y SurtrScans: sin respuesta desde esta red (`000`).
- WEBTOON:
  - episodios en `/api/v1/webtoon/{titleNo}/episodes?pageSize=30&cursor=N`
  - páginas en `var imageList` del visor, que exigen `Referer` (403 sin él)
  - búsqueda y catálogo por géneros en `www.webtoons.com/...?webtoon-platform-redirect=true`
- ZonaTMO:
  - catálogo y búsqueda en `/biblioteca?title=&_pg=N`
  - capítulos en `li.upload-link` dentro de la ficha
  - páginas en `.reader-img-wrap img`

## Causas de lentitud identificadas
- El limitador de 300 ms por servidor también frena las imágenes (Coil usa el mismo cliente).
- `CloudflareResolver` toma `challenge-platform` como desafío, y ese script sale en páginas normales: espera los 15 s.
- Timeouts largos con reintentos: un sitio caído tarda casi un minuto en fallar.
- DNS sobre HTTPS sin caché y con 10 s de espera si 1.1.1.1 no responde.
- LectorTMO parsea su HTML de 1,6 MB dos veces.
- Explorar no guarda nada entre aperturas.

## v1.9.0 (pre-release): sincronización app ↔ web, descargas en la web, paridad

- Sincronización opcional (Ajustes › Sincronizar con servidor): biblioteca con estados y categorías, progreso, historial,
  marcadores, fuentes activas y géneros excluidos. El servidor numera cada cambio (`rev`); el móvil pide "lo posterior a N"
  y gana el `updatedAt` más reciente. Los borrados viajan como filas `deleted` (servidor) o `sync_tombstones` (móvil).
  Progreso de capítulos que el móvil aún no listó: `pending_progress`, se aplica en `replaceForMedia`.
- Room v8 (MIGRATION_7_8), probada de v2 a v8 en API 26, 33 y 37. Prueba en vivo: `SyncLiveTest` con `-e live true`
  contra un servidor en 10.0.2.2:8099 (admin / prueba123); solo las builds debug permiten HTTP a 10.0.2.2.
- La app solo sincroniza por HTTPS. Android 17: permiso ACCESS_LOCAL_NETWORK, pedido solo para servidores en la red de casa.
- La sesión del servidor no entra en las copias de seguridad de Android (`yomukai_sync.xml` excluido).
- Web: descargas al dispositivo (CBZ/EPUB/MP4 montados al vuelo), categorías, marcadores, desplazamiento automático,
  recorte de bordes, brillo y filtros, géneros por fuente, novela continua y búsqueda en el capítulo.
- No posible en web: Tor, navegador de captura, avisos con la web cerrada, bloquear orientación del lector.

## Pendiente web (feedback del usuario tras desplegar en yomukai.nelsonffkarlsson.com, 2026-11-04)

Funciona: sincronización app ↔ web, cuentas, móvil por web.

- [x] Ficha en PC: en monitores grandes (27", 2560 px) la columna de 1200 px parecía de móvil → layout más ancho y escalado.
- [x] Descargas: la opción estaba escondida en el menú ⋮ → botón visible «Descargar» junto a la lista de capítulos.
- [x] Calidad de imagen (el lector estiraba las páginas a 900 px; ahora «Tamaño original»): en PC las portadas y páginas se ven como ampliadas. Revisar fuente por fuente qué URL de imagen
      se usa (miniatura vs original: Asura, Olympus, Flame, Webtoons, ZonaTMO, LectorTMO); MangaDex ya usa original y portada .512.
      Comprobar también que el proxy no recorta (MAX_IMAGE_BYTES 20 MB) y probar en pantalla 2560 px con devicePixelRatio 1 y 2.
- [ ] Errores en fuentes y en «Para ti»: reproducir en el VPS (docker compose logs yomukai) y anotar qué fuente falla y con qué error.
- [ ] TuNovelaLigera no carga o tarda muchísimo desde el VPS (¿Cloudflare? ¿bloqueo por IP del VPS?). Mirar logs y FlareSolverr.
- [x] Rendimiento: la ficha esperaba 10-20 s a las notas (peticiones no cancelables y MAL/Jikan sin responder) → menos de 1 s.
- [ ] Rendimiento general (resto): medir tiempos de /api/sources/*/latest, /api/foryou y /api/metadata en el VPS; cachear más agresivo
      y cargar las filas del Inicio de forma progresiva (solo las visibles).
- [x] Móvil: el botón «Empezar a leer» no tomaba el color de la portada; barra del lector con la barra de páginas aplastada;
      título del reproductor cortado. Arreglados y comprobados a 390 px.
- [x] Una línea de código salía como texto encima del lector de manga (edición con perl rota). Cuidado: en perl con `|` como
      delimitador, un `\|\|` en el patrón empareja vacío al principio del fichero.

## v1.10.0: MegaBanana, suspensión por abuso, invitados de 1 día

- Fuentes de cómics evaluadas: CBRcomics y MegaComicsTV3 (descargas Mega/Mediafire), ZonaComics (enlaces ouo.io):
  no tienen lector, no se pueden integrar. MegaBanana sí (API `wp-json/megabanana/v1`: catalog, reader/{post}?chapter=N).
- El CDN de MegaBanana (hcdn) da 403 a OkHttp por HTTP/2 y 200 por HTTP/1.1: servidor reintenta imágenes por HTTP/1.1;
  la app usa HTTP/1.1 para `HTTP1_IMAGE_HOSTS`.
- Abuso: más de ABUSE_LIMIT (1500) / GUEST_ABUSE_LIMIT (300) peticiones a fuentes en 10 min → cuenta suspendida hasta que el
  admin la reactiva. Invitados contados también por conexión;
  máximo 5 invitados por hora por conexión. Pasarse del límite compartido de una conexión solo rechaza la petición (429) y
  bloquea nuevos invitados 1 h; nunca suspende a nadie por sí solo (puede ser una red compartida).
- Invitados: 1 día, 120 peticiones/min, sin exportar/importar/sincronizar, 3 capítulos por descarga.
- En el VPS, el `.env` ya creado conserva GUEST_DAYS=7: cambiarlo a mano (sed) al actualizar.

## Pendiente v1.11.0 (2026-11-04)

- [x] YSK Comics (inglés): API `api.ysk-comics.com/api/v1` (home/latest-comics, search-comics-home?name=, comics/{slug},
      comics/{slug}/chapters?page=, chapters/{slug}/images). Probada en web y en Android (LiveSourcesTest OK).
- [x] Anitaku (anime en inglés), la mejor de las tres propuestas: HTML de WordPress (tema AnimeStream).
      Lista: `/series/?order=update&page=N` (article.bs); búsqueda: POST `/wp-admin/admin-ajax.php`
      action=ts_ac_do_search&ts_ac_query=…; ficha: .entry-content, .spe, .genxed; episodios: .eplister li a (.epl-num);
      episodio: `<option value=base64(iframe)>` → Blogger `video.g?token=…`; del HTML de Blogger (&quot; y &) sacar
      `"https://…googlevideo.com/videoplayback?…",[22]` (720p MP4, itag 18 = 360p). Las URLs van ligadas a la IP que las pide.
- Descartadas: anime.nexus (DRM Widevine/ClearKey + verificación del dispositivo), CompartiComics (solo RAR de ~200 MB,
  5 descargas al día por conexión), AniZone (Livewire, frágil), AniSuge (AJAX cifrado).
- Falta publicar la release 1.11.0 (versionCode 21 ya puesto) tras añadir Anitaku.
