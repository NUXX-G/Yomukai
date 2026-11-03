# TRABAJO — traspaso de la tanda actual

Léelo primero si retomas esto. `PLAN.md` es el histórico de fases; este fichero es lo que se está haciendo ahora.

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
- [ ] 2. Servidor: API de fuentes, cuentas, invitados, export/import, proxy
- [ ] 3. Web: Inicio, Buscar, Biblioteca, ficha, lectores de manga y novela
- [ ] 4. Reproductor de anime en la web
- [ ] 5. Docker, instalación en una línea y README

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
