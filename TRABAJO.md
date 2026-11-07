# TRABAJO — traspaso de la tanda actual

Léelo primero si retomas esto. `PLAN.md` es el histórico de fases; este fichero es lo que se está haciendo ahora.

## ▶ DÓNDE ESTOY EXACTAMENTE (actualizar en cada commit grande)
Última actualización: 2026-11-07 (noche), commit `docs(trabajo): estado al cerrar la sesión`. Versión de la app: **2.0.0** (versionCode 26).

**ESTADO AL CERRAR ESTA SESIÓN**: todo lo pedido el 2026-11-07 está hecho y subido (ver la lista de abajo). El usuario va a instalarlo en el VPS y en el móvil, probarlo un día y traer correcciones. **Siguiente paso**: esperar su lista de fallos; mientras, lo pendiente (por prioridad): app Android con Jellyfin/salas/foro/perfil nativos; voces en el dispositivo (sherpa-onnx, Kokoro en el navegador); ML Kit en la app (OCR y traducción); diccionario offline (kaikki.org); mazos compartidos y ranking; voces por personaje con clonación; pregeneración nocturna de voces; más metadatos (TMDB para series no anime con clave opcional); pasar más fuentes Kotlin a JSON; espacio del NAS en el panel; 3.6 avisar de cambios de dominio (ya hay `onDomainChange`, falta revisarlo).
**Release**: `.github/workflows/release.yml` publica la APK al subir la etiqueta `v2.0.0` (no pude crearla yo: publicar necesita el permiso del usuario). APK firmada de prueba en el scratchpad de la sesión.
**Bug importante encontrado y arreglado**: en Android, una regex con `}` sin escapar falla (ICU) aunque en el JVM vaya; por eso las fuentes JSON (Marmota) no cargaban en la app. `AndroidRegexTest` lo vigila.

### PEDIDO DEL USUARIO EL 2026-11-07 (2ª parte, también hecho)
- [x] App: el inicio ya no tiene filas por fuente: «Recién actualizado» + Anime / Manga, manhwa y manhua / Cómics / Novelas, por obra (`WorkGrouper`, «En N fuentes»), y «Explorar por fuente» plegado al final.
- [x] Portadas a la mayor resolución: `HighResCovers` (MangaDex original en vez de .512, WordPress sin -WxH) con respaldo a la miniatura (param `o` firmado); la web pide su ancho real hasta 3× (teles 4K) en vez del tope fijo de 720.
- [x] Comprobado en vivo (2026-11-07): canario 15/18 OK; asura, lectortmo y animeav1 fallan SOLO en la red de casa (el operador intercepta con un certificado falso «Packetland»: bloqueo, no fallo de código). Descargas web probadas: CBZ (MangaDex, Olympus), EPUB (SkyNovels), MP4 (AnimeFLV, Anitaku).

### PEDIDO DEL USUARIO EL 2026-11-07 (sesión nueva, modo autónomo total; no está mirando)
Al acabar TODO avisarle: lo instalará en el VPS y en su móvil, lo probará un día y dirá qué corregir. Orden elegido por mí:
1. [x] Separar idiomas: MangaDex era una fuente con ES+EN mezclados en la misma obra → `mangadex` (ES, conserva el id),
   `mangadex_en` (EN, activa por defecto) y `mangadex_zh` (ZH, apagada). Filtro de idioma con «Chino» en web y app.
   No olvidar el chino en ningún sitio (filtros, voz, OCR, aprendizaje).
2. [x] Fase 10 HECHA en servidor + web (obras seguidas, Jellyfin como fuente, progreso en las dos direcciones, salas con NAS). Pendiente solo la app Android (ver la fuente Jellyfin desde el móvil).
3. [~] **Interfaz en español e inglés** — WEB HECHA: diccionario único `shared/src/main/resources/i18n/en.json` (español → inglés, ~960 textos; el build lo copia a `web/src/i18n/`, ignorado en git), `web/src/lib/i18n.svelte.js` (`t('texto', [params]|{params})`, `tk` para marcar claves en listas, `tMap/tLabels/tPairs` para etiquetas que se leen traducidas, `locale()` para fechas; idioma por navegador o Ajustes › Idioma; cambiar recarga la página), errores del servidor traducidos si están en el diccionario (`ApiError`), textos que manda el servidor (logros, temas de serie, interruptores, filas de Explorar) traducidos al mostrarlos. Test `web/tests/i18n.test.mjs`: falla si un `t('…')`/`tk('…')` o un texto del servidor no tiene traducción, o si cambian los {huecos}. Para textos nuevos: escribirlos en español con `t('…')` y añadir la línea a en.json. APP ANDROID HECHA: `shared/…/i18n/I18n.kt` (`tr("texto", args…)` con {0}…, mismo en.json), `I18n.use()` al arrancar (`YomukaiApplication`, prefs `yomukai_ui`), Ajustes › Idioma (`LanguagePicker.kt`, reinicia la app), etiquetas de enums traducidas (se calculan una vez: por eso el reinicio), test `app/src/test/…/i18n/TranslationsTest.kt` exige traducción de cada tr(). Nombres de archivos descargados y carpetas siguen en español a propósito (no romper descargas). Idioma por defecto: español si el dispositivo/navegador está en español, inglés para cualquier otro. Original del pedido: (solo el idioma del sistema; el contenido de cualquier idioma se ve igual):
   web (diccionario por clave, lazy por idioma, detecta el del navegador, Ajustes › Idioma) y app (strings.xml + values-en).
4. [ ] **Todo por obras, no por fuentes** (ya acordado en la Fase 5): revisar que ninguna pantalla vuelva a organizarse por
   fuente. **Más páginas de metadatos** además de AniList/MAL/Kitsu/MangaUpdates/Open Library (p. ej. TMDB para series y
   películas no anime, NovelUpdates para novelas, Comick/MangaUpdates para manhwa/manhua), con caché y prioridad.
5. [x] HECHO (`server/…/SourceCatalog.kt`, migración 14 `source_settings`, `SourceManager.hidden/applyExtra/origins`, panel `SourceCatalogPanel.svelte`, tests `SourceCatalogTest` y `SourceCatalogRoutesTest`): el admin quita/pone fuentes para todos, ve origen/versión/salud, «Buscar actualizaciones»; `SOURCE_REPOS=https://…/bundle.json#CLAVE` añade repos firmados de terceros que nunca pueden sustituir una oficial. Pendiente: pasar a JSON más fuentes Kotlin que encajen. Original: **Catálogo de fuentes tipo plugin, SOLO JSON firmado** (nunca código descargado: decidido con el usuario por
   seguridad): pasar a JSON las fuentes que encajen, pantalla de catálogo (instalar/quitar con un toque, versión nueva,
   caída), y que cada instalación pueda aceptar **claves públicas extra** (`SOURCE_KEYS`) para sus propias fuentes. El
   código es público (portfolio y para quien lo quiera montar): seguro y privado para él y para los demás.
6. [~] Fase 11. HECHO 11.6 en parte: **modo «todo en uno»** (servicio `worker` con perfil `todo-en-uno` en docker-compose.yml, `LOCAL_WORKER_TOKEN` que el servidor registra solo al arrancar con `WorkerRegistry.ensureLocal`, el worker acepta nombres de servicio Docker sin punto, instalador con `YOMUKAI_MODE=todo-en-uno`) y guía `docs/INSTALACION.md` (3 formas, Tailscale paso a paso, variables). HECHO también: motores opcionales del worker (Dockerfile.worker con WITH_OCR por defecto, WITH_TTS/WITH_WHISPER en la imagen `yomukai-worker-full`, LibreTranslate como perfil `traduccion` en docker-compose.worker.yml; install-worker.sh con WORKER_FULL y WORKER_TRANSLATION). PENDIENTE de comprobar que CI construye la imagen completa (Pocket TTS 4.0.1 + whisper.cpp v1.9.5). Original:: servidor + worker en la misma máquina con un comando (red de Docker
   compartida; el worker apunta al servidor por su nombre) y guía con las 3 formas: solo servidor / todo en uno / VPS + worker.
7. [x] PRs de Dependabot RESUELTOS (2026-11-07): #2 cerrado (Node 24 LTS a mano), #6 cerrado (Tor 0.4.9.14 exige minSdk mayor: fuera Android 8; excluido en dependabot.yml), #4 y #5 incorporados a mano tras probar con LiveSourcesTest en el emulador (OkHttp 5.5.1, Compose BOM 2026.09.00), #7 falla por JSpecify (añadido `api(libs.jspecify)` en shared y pedido rebase), #1 Temurin 25: pedido rebase; las imágenes se construyen sin publicar en PRs (docker.yml) para validarlo. Antes: #6 Tor y #7 (30 menores) fallan en CI → arreglar; #4 Compose y #5 OkHttp 5 → probar en emulador;
   #1 Temurin 25 → probar Docker; #2 Node 25 → cerrar (no LTS) y subir a node 24.
8. [ ] Pendientes de Fases 8 y 9 (sherpa-onnx/Kokoro en dispositivo, ML Kit en la app, diccionario offline…).
Todo con HUD moderno tipo Netflix/Seanime, bonito y eficiente («que sea top de verdad»).
- El secreto `SOURCES_SIGNING_KEY` YA está puesto (2026-11-07).
- **Fase en curso: 6 (Social).** Hecho y probado en el SERVIDOR: salas + watch party + chat (6.1-6.3, también en la web), perfiles, estadísticas, privacidad, feed, presencia, valoraciones, recomendaciones y motor de logros (6.4-6.7 y 6.11) → `server/…/Social.kt`, `Achievements.kt`, `server/src/main/resources/achievements.json`, migración 8, tests `SocialTest` y `RoomsTest`.
- **Web de 6.4-6.7 y 6.11 HECHA** (commit «feat(social): perfiles, amigos, actividad, valoraciones...»): `Profile.svelte` (/u/nombre con edición, logros, estadísticas), `Friends.svelte` (/amigos), `WorkSocial.svelte` en la ficha (amigos que la tienen, nota 1-10 + reseña, media, recomendar), filas «Te recomiendan» y «Actividad de tus amigos» en Inicio, presencia desde reproductor y lectores (`lib/presence.js`), aviso animado de logros en `App.svelte`, evento skip_opening. Probado en vivo con dos usuarios.
- **Servidor de 6.8-6.10 HECHO** (`Forum.kt`, `Notifications.kt`, migración 9, `ForumTest`): API `/api/forum*`, `/api/notifications*` (SSE `/api/notifications/stream`, push ntfy `/api/notifications/push`), moderación `/api/admin/forum/threads/{id}` y `/api/admin/mute`, flag `forum`.
- **Web de 6.8-6.10 HECHA**: `lib/markdown.js` + `Markdown.svelte` (sin `{@html}`, con tests `npm test`, también en CI), `ForumThread.svelte`, página `/foro` y `/foro/:id`, pestaña «Foro» en la ficha, campana con avisos en vivo (SSE) en `App.svelte`, «Avisos en el móvil» (ntfy) en Ajustes (`PushPanel.svelte`). Probado en vivo con dos usuarios.
- **App Android de la Fase 6 (mínimo) HECHA**: `ServerSync.api()` (llamada JSON autenticada genérica), `WorkSocialSection` en la ficha (nota 1-10, media del grupo, amigos que la tienen, «Ver con amigos» abre `/sala/CODIGO` en el navegador), `NotificationsButton` (campana en Inicio). Compila y pasa lint/tests, **sin probar aún en el emulador con un servidor conectado** (para probar: servidor local, en el emulador Ajustes › Sincronizar con `http://10.0.2.2:8193` si `allowsPlainHttp` lo permite, o por Tailscale). Pendiente para más adelante: salas nativas por WebSocket, perfil y foro nativos.
- **Fase 7 en curso.** Hecho: `shared/…/data/theme/Themes.kt` (`ThemeSpec`, `ThemeRules` valida: solo claves de tokens.json › color con #rrggbb(aa), fuente de inter/system/serif/rounded/mono, radio sharp/normal/round, densidad compact/normal/comfy, fondo = id de imagen subida, coverAccent; `Contrast` WCAG), `design/themes.json` (8 temas de serie: default, oled, sepia, light, neon, manga, pastel, matcha; test de contraste AA), servidor `ThemesService.kt` + migración 10 + flag `themes` + `ThemesTest`. API: `GET /api/themes` (builtIn, gallery, current), `GET /api/themes/current`, `POST /api/themes/check` (issues de contraste), `POST /api/themes {theme, published}`, `PUT /api/themes/{id}`, `DELETE /api/themes/{id}`, `PUT /api/themes/use {builtIn|id|nada}`, `POST /api/themes/backgrounds` (bytes) y `GET /api/themes/backgrounds/{id}`.
- **Web de temas HECHA**: `web/src/lib/theme.svelte.js` (aplica solo tokens validados como variables en <html>, recuerda el último en localStorage para arrancar sin parpadeo, códigos YMKT1:), `ThemesPanel.svelte` en Ajustes (galería de serie y de amigos, usar con un toque, editor con vista previa en directo y avisos de contraste, fondo, publicar, importar/exportar), la ficha respeta `coverAccent`. Probado: Sepia y Neón.
- **Fase 7 HECHA** (también en la app: `ui/theme/CustomTheme.kt` convierte el ThemeSpec en ColorScheme/tipografía/esquinas; `AppSettings.customTheme`; al sincronizar trae `/api/themes/current`; Ajustes › Apariencia › Tema con los de serie, que si hay servidor se guarda allí). Probado Sepia en web y emulador.
- **Fase 8 en curso.** Hecho: `TtsPlan` en shared (orden worker → dispositivo → Piper VPS → sistema; troceado en frases estable para compartir caché; tests), `TtsHandler` en el worker (Pocket TTS por defecto o `WORKER_TTS_COMMAND` sin shell; voces anunciadas en las capacidades y saneadas en el servidor), `VoiceRouter.kt` en el servidor (`/api/tts/voices` con voces `w:<id>`, `POST /api/tts` que encola en el worker y espera ≤30 s o usa Piper, `POST /api/tts/prefetch`), test en `WorkerApiTest`, `scripts/tts-compare.sh` y `docs/VOCES.md`.
- Web HECHA: el lector de novelas usa las voces `w:` (salen en `/api/tts/voices`; la web ya separaba narrador/mujeres/hombres en los diálogos), pide al worker los 30 párrafos siguientes cada 20 y tiene MediaSession (bloqueo, auriculares, siguiente/anterior) con aviso de que con la pantalla apagada es más fiable la app.
- App HECHA (sin probar a fondo en el emulador con una novela real): `ui/reader/ReadAloudService.kt` (servicio en primer plano mediaPlayback con notificación «Parar», wake lock parcial con tope de 6 h y MediaSession de la plataforma para bloqueo/auriculares; se arranca/para según `NovelSpeech.current`; el paso al capítulo siguiente ya lo hacía `NovelSpeech` con `onNeedMore`), `BatteryOptimization.kt` (pregunta una vez explicando por qué). Se usó la MediaSession de la plataforma en vez de Media3 MediaSessionService porque la lectura no es un `Player` de Media3; si se quiere Media3, envolver `NovelSpeech` en un `SimpleBasePlayer`.
- **Fase 8 pendiente (para retomar más adelante)**: sherpa-onnx sin conexión en la app y Kokoro/Supertonic en el navegador (nivel DEVICE de `TtsPlan`, modelos solo si el usuario lo activa); voces por personaje con clonación (solo muestras libres; la web ya alterna narrador/mujer/hombre por diálogo); pregeneración nocturna de la biblioteca; ejecutar `scripts/tts-compare.sh` en el worker real.
- **Fase 9 en curso.** Hecho en shared con tests: `data/ocr/Ocr.kt` (TextBox en fracciones de página, ReadingOrder manhwa/manga/cómic, BubbleClassifier bocadillo/onomatopeya, TesseractTsv; el fixture TSV está escrito a mano con el formato real: sustituirlo por uno capturado en el worker), `data/ocr/ScrollPlanner.kt` (tiempo por franja según palabras/detalle/huecos, aprendizaje de la velocidad, plan B por análisis de imagen), `data/learning/Fsrs.kt` (FSRS 4.5, niveles nueva/aprendiendo/sabida, % de comprensión, exportación a Anki).
- **Servidor y worker de la Fase 9 HECHOS** (`Learning.kt`, `LanguageHandlers.kt`, migración 11, flags `ocr` y `learning`, tests `LearningTest`, `WorkerApiTest`, `LanguageHandlersTest`): OCR compartido `GET/PUT /api/ocr?s=&c=&p=` (saneado: ≤300 cajas, fracciones 0..1) y `POST /api/ocr/request` (el servidor saca las páginas de la fuente y encola `ocr` → Tesseract en el worker, solo direcciones públicas); palabras `GET /api/words`, `/words/due` (sesión diaria de 50), `/words/levels?lang=`, `POST /api/words`, `POST /api/words/{id}/review {grade: again|hard|good|easy}`, `DELETE /api/words/{id}`, `GET /api/words/anki`; subtítulos `GET /api/subtitles?s=&c=&lang=` (200 VTT / 202 generando / 204 sin worker; whisper.cpp con `WORKER_WHISPER_MODEL`); `POST /api/translate {texts, from, to}` (LibreTranslate en casa con `WORKER_LIBRETRANSLATE_URL`; 503 sin worker).
- **Web de aprendizaje HECHA**: Ajustes › «Aprender idiomas» (APAGADO por defecto, idioma destino), pulsación larga o clic derecho en una palabra del lector de novelas → `WordSheet.svelte` (traducción de palabra y frase por `/api/translate`, Escuchar, enlace a Wiktionary, Guardar), página `/repaso` (`Review.svelte`, sesión diaria FSRS con teclas 1-4, exportar a Anki), botón «Subtítulos» en el reproductor si hay worker con whisper (pista VTT). Probado el repaso en vivo.
- **Lector de manga (web) HECHO**: `web/src/lib/ocr.js` (orden de lectura, tiempos por franja, análisis de imagen; tests en `web/tests/ocr.test.mjs`), botón «Traducir» (OCR compartido + `/api/translate`, texto traducido en una caja blanca sobre cada bocadillo, «Ver original»; si no hay OCR ni worker, aviso), «Leer bocadillos» (voz del navegador, orden por tipo de obra, resalta y centra el bocadillo; onomatopeyas solo con `settings.reader.readSfx`), autoscroll inteligente por franjas de 300 px (palabras del OCR o análisis de la imagen en canvas; el control de velocidad escala el plan; `settings.reader.smartScroll` para apagarlo, `settings.reader.wpm`). Probado: varía de ~2000 px/s en huecos a ~250 px/s en viñetas.
- También HECHO: el autoscroll inteligente aprende la velocidad (el control pasa a ser palabras por minuto y se guarda en `settings.reader.wpm`); en novelas con el modo aprendizaje, las palabras que estás aprendiendo se subrayan con la CSS Highlight API (sin tocar el texto), la cabecera dice cuántas hay en el capítulo y la hoja de palabra traduce el párrafo entero (modo bilingüe). Decisión: no se muestra un «% de comprensión» porque sin una lista de frecuencias por idioma sería engañoso; `Comprehension.percent` en shared ya admite esa lista cuando haya diccionario.
- **Fase 9 pendiente (retomar más adelante)**: app con ML Kit (OCR y traducción en el dispositivo, sin incluir modelos en el APK: los de Play Services se descargan aparte) y las mismas funciones en Compose; diccionario offline de kaikki.org por idioma (solo al activarlo); mazos compartidos y ranking semanal de repasos.
- **Fase 10 en curso.** Hecho: `shared/…/data/media/MediaLibrary.kt` (nombres de Jellyfin «Serie (Año)/Season 01/Serie - S01E01.mkv», películas, .nfo de serie/temporada/episodio con ids de AniList/MAL/TVDB, ComicInfo.xml y rutas CBZ; tests), servidor `Integrations.kt` (config de Jellyfin y Kavita/Komga sellada con SecretBox, las claves nunca salen; `GET/PUT /api/admin/integrations`, `POST /api/admin/integrations/jellyfin/test` desde el VPS; `JellyfinClient.info()/scanPath()`), migración 12 (`integrations`, `follows`), flag `jellyfin`, `IntegrationsTest` con un Jellyfin falso (`FakeJellyfin`, servidor HTTP del JDK).
- **Descargas HECHAS**: `POST /worker/videos` (vídeo directo fresco al empezar), `POST /api/admin/jellyfin/download` y `POST /api/admin/cbz/export {sourceId, mediaId, chapterIds}` (solo admins, auditado, cola BACKGROUND, 30 días, deduplicado), `JellyfinDownloadHandler` (espacio libre ≥5 GB, ffmpeg -c copy a MKV, nombres de Jellyfin dentro de la biblioteca, tvshow/season/episodio .nfo con ids, poster, escaneo solo de esa carpeta) y `CbzExportHandler` (CBZ con ComicInfo.xml en la carpeta de cómics). Pendiente: evento `jellyfin_download` para el logro (registrar en el servidor cuando el trabajo termine) y aviso a Kavita/Komga para escanear.
- Web HECHA: `IntegrationsPanel.svelte` en el panel de admin (Jellyfin, carpetas, Kavita/Komga, probar desde el VPS; las claves no se muestran) y en el menú de la ficha, para admins, «Al Jellyfin» (anime) / «A Kavita/Komga» (cómics) con los no vistos o todos.
- **Obras seguidas HECHAS** (`server/…/MediaDownloads.kt`, test `MediaDownloadsTest`): las descargas manuales y las automáticas pasan por `MediaDownloads.queueEpisodes/queueChapters`; `/api/admin/follows` (GET, POST {sourceId, mediaId, rules{preferredServer, maxPerRun≤20}}, DELETE /{id}, GET /of?s=&id=, POST /check); bucle cada 3 h en Main (flag `jellyfin`), solo lo publicado después de seguirla, ≤20 por vez; aviso en la campana al encolar, al llegar (solo automáticas) y al fallar del todo; evento `jellyfin_download` al terminar (logro Coleccionista de discos); el worker usa el servidor preferido (`/worker/videos` con preferredServer) y avisa a Kavita (plugin/authenticate + scan-all) o Komga (X-API-Key, scan por biblioteca) tras cada CBZ. Web: ficha › Opciones › «Descargar lo nuevo automáticamente» y lista en Admin › Integraciones.
- **Jellyfin como fuente HECHO** (`server/…/JellyfinSource.kt`, test `JellyfinSourceTest` con `FakeJellyfin` ampliado): `JellyfinLink` la registra con `SourceManager.setExtra` solo si está configurada, el flag `jellyfin` encendido y responde (cada 5 min y al guardar Integraciones); series y películas como tipo ANIME, episodios numerados seguidos entre temporadas (especiales al final); portadas por `/api/jellyfin/image/{id}` y vídeo por `/api/jellyfin/stream/{id}?q=orig|8000|4000|1500` (Range, cuenta ancho de banda, la clave solo en cabecera; nunca invitados: `Services.prefs` la quita para ellos). Prioridad: «jellyfin» va primero en el orden de fuentes de la obra y el idioma MULTI cuenta como el preferido. Web: insignia «En tu NAS» (`groupBadges` en format.js) en búsqueda y recién actualizado. **Sin probar con un Jellyfin real**: la transcodificación (`/Videos/{id}/stream.mp4?VideoBitrate=…`) es la parte más dudosa; si falla, «Original» va siempre. La app Android aún no la ve (sus fuentes corren en el móvil): pendiente.
- **Progreso bidireccional HECHO** (`JellyfinSync.kt`, migración 13 `users.jellyfin_user`): Admin › Integraciones › «Cuentas de Jellyfin» empareja usuarios (`GET /api/admin/jellyfin/users`, `PUT /api/admin/jellyfin/link`); al guardar progreso de la fuente jellyfin se manda a Jellyfin en segundo plano (`/UserItems/{id}/UserData?userId=`, y la dirección vieja si no existe); cada 15 min se trae lo visto/empezado en las apps de Jellyfin como progreso (solo si es más nuevo; nunca historial → sin duplicados). Seguridad: plugin `GuestsOffNas` rechaza a invitados cualquier petición que nombre la fuente jellyfin.
- **Salas con Jellyfin HECHO**: el vídeo del NAS ya pasa por el VPS (`/api/jellyfin/stream`, cuenta en el tope de ancho de banda); `web/src/lib/nas.js` (`recommendedQuality(personas, NAS_UPLOAD_MBPS)`: 80 % de la subida de casa repartida, test en `web/tests/nas.test.mjs`) y el reproductor cambia solo de calidad según cuántos haya en la sala (salvo que alguien elija servidor a mano; entonces también conserva la posición), con aviso en «Servidores». `/api/features` lleva `nas {online, uploadMbps}` (nunca a invitados). Variable nueva `NAS_UPLOAD_MBPS` (20): documentarla en el README en la Fase 11.
- **SIGUIENTE PASO CONCRETO**: punto 3 del pedido de 2026-11-07 (interfaz en español e inglés). (Antiguo: 7) Jellyfin como fuente (biblioteca por su API, insignia «en tu NAS», prioridad en el modelo de obra), progreso en las dos direcciones, salas con Jellyfin.
  (Notas antiguas de la Fase 6, ya hecha:) Ideas ya decididas: hilo automático por obra (clave = work_key) + categorías Recomendaciones/Off-topic/Quejas al admin; markdown SANEADO en el servidor (o solo texto con formato mínimo; nunca `{@html}` con datos de usuario sin sanear); spoilers «hasta el ep./cap. N» ocultos a quien vaya por detrás (`SpoilerShield.hideComment` ya existe en shared); al revivir un hilo con >3 meses sin actividad registrar evento `thread_revived`; mensajes marcados spoiler → evento `forum_spoiler`. Moderación: borrar/editar/fijar/cerrar/silenciar con caducidad, todo en `AuditLog`. Notificaciones: canal en tiempo real (SSE ya instalado en Ktor: `install(SSE)`) para menciones, respuestas, recomendaciones y logros; ntfy (ya hay `Notifier.kt` con Discord/ntfy para avisos de admin) y webhook de Discord solo para salas abiertas e hilos nuevos (`DISCORD_WEBHOOK_URL`).
- Pendiente de la app Android de la Fase 6: salas nativas con el mismo protocolo WebSocket, perfil y feed.

### Lo que el usuario pidió por el camino (no olvidar)
- Todo autónomo, sin esperar confirmación entre fases; subir a GitHub a menudo (puede quedarse sin tokens). Si una sesión se corta, la siguiente sigue desde aquí.
- El VPS no se toca ahora: dejar todo listo para instalar con un par de comandos (`install.sh`, `install-worker.sh`, docker-compose). No probará nada de momento.
- Bilibili descartada; MangaDex chino sí (hecho).
- Que funcione, sea seguro y eficiente, y flexible si cae una fuente (sin complicarlo).
- Debe verse bien en **web escritorio, web móvil y la app Android**, y en navegadores de **iOS/macOS (Safari/WebKit) y Android/Windows/Linux (Chrome, Edge, Firefox)**, también como acceso directo instalado (PWA: `web/public/manifest.webmanifest` con atajos). Mínimo realista: Safari/iOS 16.2+ (usa `color-mix`). Comprobar con WebKit además de Chromium.
- Portadas grandes en baja resolución → hecho: el servidor usa la portada extraLarge de AniList cuando la página está unida a una obra (`Works.betterCover`, cola en segundo plano que identifica lo que sale en listas). Si vuelve a quejarse, ampliar la identificación o pedir `w=` mayores.

### Cosas que necesitan al usuario (decírselo al terminar)
- Medir en el VPS real con `scripts/bench.sh` (no tengo acceso).
- PRs de Dependabot: me encargo yo (el usuario no quiere revisarlos).
- Una revisión de seguridad automática no terminó por un 429: detalles en `C:\Users\nelso\.claude\security\log.txt`.

### Cómo trabajo aquí (para no perder tiempo)
- Windows + Git Bash; no hay python: para editar con scripts usar node con un .mjs en el scratchpad y reemplazos por `indexOf/slice` (no `String.replace` con `$`). Cuidado con `${` en plantillas JS al editar Kotlin: usar la herramienta Edit.
- Comprobar antes de cada commit: `./gradlew -q :shared:test :server:test`, `bash scripts/lint-kotlin.sh` (con `--format` arregla estilo; si cambia la firma de una función ya en `config/detekt-baseline.xml`, actualizar esa línea), `cd web && npm run lint && npm run check && npm run build`, y si se toca la app `./gradlew -q :app:assembleDebug :app:testDebugUnitTest`.
- Servidor de pruebas local: `./gradlew -q :server:installDist` y `DATA_DIR=<tmp> PORT=8193 WEB_DIR=web/dist server/build/install/server/bin/server` (en la primera ejecución sin ADMIN_PASSWORD pide configuración inicial; para pruebas usar `ADMIN_PASSWORD=admin-password-1`). Probar la web con el MCP de Playwright.
- Emulador Android: `~/AppData/Local/Android/Sdk/emulator/emulator.exe -avd Medium_Phone_API_37.0`, `adb install -r app/build/outputs/apk/debug/app-debug.apk`, capturas con `adb exec-out screencap -p`; en Git Bash usar `MSYS_NO_PATHCONV=1` con rutas `/sdcard`.
- Tokens de diseño: editar `shared/src/main/resources/design/tokens.json` y ejecutar `node scripts/tokens-android.mjs` (la web los genera sola en el build).

### API social (servidor, ya hecha y probada)
`GET/PUT /api/profile` (bio ≤300, accent #rrggbb, favorite{sourceId,mediaId,title,cover}, hidden, privateCategories[], shownTitle=id de logro propio) · `POST/DELETE /api/profile/avatar|banner` (cuerpo = bytes de la imagen, ≤4 MB; se re-dibuja JPEG 256 px cuadrado / 1500 px 3:1) · `GET /api/users` · `GET /api/users/{nombre}` (profile, stats, library, libraryHidden, now, achievements) · `GET /api/users/{nombre}/avatar|banner` · `POST /api/presence` · `GET /api/feed` · `GET /api/friends-on?s=&id=` · `GET/PUT /api/ratings` (score 1-10 o null para borrar, review) · `POST /api/recommend/{nombre}` · `GET /api/recommendations` · `POST /api/recommendations/seen` · `GET /api/achievements` · `GET /api/achievements/new` (los recién desbloqueados, una sola vez) · `POST /api/achievements/event {kind: skip_opening|word_saved|chapter_no_lookup}` · Salas: `GET /api/rooms`, `GET /api/rooms/mine`, `POST /api/rooms`, `GET /api/rooms/{code}`, WebSocket `/api/rooms/{code}/ws` (mensajes en `Rooms.kt`, cliente web en `web/src/lib/party.svelte.js`).
Eventos que alimentan feed y logros (`Events.record`): episode, chapter, rated, achievement, room_hosted, room_join_late, room_most_pauses, room_buffering, reaction, room_message, recommended, recommendation_followed, questionable_taste, skip_opening… Los que aún no se generan (forum_spoiler, thread_revived, theme_used, word_saved, chapter_no_lookup, jellyfin_download) los emitirán las fases 6.8, 7, 9 y 10.

## ENCARGO GRANDE (2026-11-05): fases 0 a 11
Texto íntegro y detalle de cada punto: `docs/ENCARGO.md` (léelo entero antes de seguir).
Reglas clave: fase a fase y en orden. **CAMBIO del usuario (2026-11-05): NO parar entre fases ni esperar su OK; seguir de forma autónoma con la siguiente y subirlo todo a GitHub.**
Commits convencionales pequeños, build+tests verdes antes de cada commit, `git push origin main` a menudo (se pueden acabar los tokens: subir por tramos y, si se cortó, seguir donde indique este checklist).
Lo pesado nunca en el VPS (2 GB, compartido con el portfolio): va al worker de casa o al dispositivo. Feature flag por funcionalidad grande. Medir rendimiento antes/después de cada fase (tabla abajo).

### Extra pedido por el usuario
- [x] Bilibili Manga descartada por el usuario (API cifrada). En su lugar: **MangaDex 中文** (`mangadex_zh`, `MangaDexProfile.CHINESE`): capítulos zh/zh-hk, títulos en chino; desactivada por defecto (`CatalogueSource.enabledByDefault`), se activa en Ajustes › Fuentes

### Fase 0 — Limpieza y base ✅
- [x] 0.1 `server/data/`, `t.html` y `.idea/` fuera del repo e ignorados; `secret.key` borrado (se regenera). Nota: la clave vieja quedó en el historial; el VPS usa la suya de `/data` (o `SECRET`), así que no le afecta
- [x] 0.2 AnimeFLV (`animeflv.or.at`) funciona → PLAN.md actualizado, no se borra
- [x] 0.3 Migraciones versionadas: `server/…/Migrations.kt` (`schema_version`, pasos numerados sin huecos e idempotentes, cada uno en su transacción; rechaza bases de una versión más nueva). La 1 es el esquema actual + columnas añadidas antes; lo nuevo se añade como migración 2, 3… al final de `Migrations.ALL`
- [x] 0.4 Copias nocturnas: `Backups.kt` (`VACUUM INTO` en caliente + gzip, `BACKUP_HOUR`=4, 14 días, la más nueva nunca se borra, `BACKUP_DIR`), copia a `BACKUP_COPY_DIR` (carpeta del NAS montada) con reintento cada hora; comandos `server backup | backups | restore <archivo|latest>`; `ServerLock` (`data/.lock`) impide restaurar con el server en marcha. Documentado en README
- [x] 0.5 CI (`.github/workflows/ci.yml`): 4 trabajos en cada push/PR — detekt 1.23.8 (baseline en `config/detekt-baseline.xml`) + ktlint 1.8.0 (reglas de maquetación desactivadas en `.editorconfig`, el resto aplicado con `scripts/lint-kotlin.sh --format`), shared+server (tests + installDist), app (assembleDebug + testDebugUnitTest + APK como artefacto), web (eslint + svelte-check + build). Dependabot semanal (Gradle, npm, Actions) y mensual (Docker). Primera ejecución en verde

### Fase 1 — VPS + worker + dispositivo ✅
- [x] 1.1 `docker-compose.yml`: `YOMUKAI_MEMORY` (1g), `YOMUKAI_CPUS` (1.0), memswap 1,5g, pids 512, `oom_score_adj` 500 (FlareSolverr 512m/0,5 CPU/800), logs 3×10 MB, no-new-privileges. JVM: SerialGC, heap 55 % del límite, metaspace 160m, ExitOnOutOfMemoryError. Cachés en memoria escaladas al heap (`MemoryBudget`); disco con tope (`IMAGE_CACHE_MB`, `RESULT_CACHE_MB`). Guía con swapfile en `docs/VPS.md`. Medido en Docker con 1 GB: 107 MB reposo / 165 MB tras carga
- [x] 1.2 `worker/` (Kotlin, reutiliza shared): `WorkerConfig` (YOMUKAI_URL, WORKER_TOKEN; http solo a Tailscale/LAN), `Capabilities` (CPU, RAM, GPU por nvidia-smi, motores instalados), `VpsClient`, bucle con huecos, latido, subida y fallos. Trae copias de la base solo con permiso `can_backup`. `Dockerfile.worker`, `docker-compose.worker.yml` (GPU opcional), `install-worker.sh`. Los motores reales (TTS, OCR...) se añaden como `JobHandler` en sus fases
- [x] 1.3 `Jobs.kt`: `JobQueue` (SQLite, prioridades INTERACTIVE/NEXT/BACKGROUND, dedup_key que une trabajos iguales y reutiliza resultados, GPU primero durante 60 s para tts/whisper, concesión de 10 min con latido, reintentos con espera creciente, caducidad, limpieza a 7 días) y `ResultCache` (disco con tope y LRU). API `/worker/*` (token Bearer, SHA-256 en BD, revocable). Tipos: tts, ocr, translate, whisper, audio_fingerprint, jellyfin_download, cbz_export, echo
- [x] 1.4 Procesos externos con `nice -n 19` + `ionice -c3`, sin shell; pausa si carga/núcleo > `WORKER_MAX_LOAD` (0,85) o existe `WORKER_PAUSE_FILE`, o si el admin lo pausa; `IdleResource` descarga modelos tras `WORKER_IDLE_UNLOAD_MIN` (10)
- [x] 1.5 `/api/features` (flags + worker online/GPU + tipos disponibles). Web: panel de admin (`AdminPanel.svelte`, trozo aparte: estado, workers con crear/pausar/revocar/copias/probar, flags) y «Servidor de casa» en Ajustes. App: lo mismo en Ajustes › Sincronizar (`ServerSync.features`). Flags en `Features.kt` (`FeatureCatalog`): cada fase añade los suyos
- [x] 1.6 `Bandwidth.kt`: cuenta /api/stream por mes (tabla bandwidth), `BANDWIDTH_CAP_GB`, avisos 80/100 % por `Notifier` (Discord/ntfy, sin repetir), corte a no-admins al 100 % (flag `bandwidth_cap`). Aviso también si todos los workers se apagan

### Fase 2 — Seguridad ✅
- [x] 2.1 `Security.kt`: CSP estricta (script-src self, sin inline scripts; blob: para audio y worker de hls.js), HSTS solo por HTTPS, X-Frame-Options DENY, Permissions-Policy, COOP, nosniff, no-referrer. Cookie HttpOnly + SameSite=Lax + Secure por HTTPS. Probado en navegador: lector, reproductor HLS, ajustes y asistente sin una sola violación de CSP
- [x] 2.2 `addressRateLimit` por conexión (ruta normalizada): `AUTH_RATE` 30/min en escrituras de /auth, `IP_RATE` 1200/min en la API; por usuario el limitador existente. Bloqueo de login progresivo (15 min, 30, 1 h… hasta 24 h). Fallos de código 2FA contados por cuenta a través de todos los retos (máx. 3 retos abiertos)
- [x] 2.3 Tests HTTP (`SecurityTest`, `WorkerApiTest`): sin sesión → 401 en toda la API; usuario no ve ni toca datos/sesiones de otro; usuarios/invitados → 403 en /api/admin; invitados sin 2FA ni exportar; solo token de worker válido en /worker, revocación inmediata, copias solo con permiso. Regla para lo nuevo: lo social se monta bajo un plugin que rechace invitados (Fase 6)
- [x] 2.4 Migración 4: `user_sessions` con SHA-256 del token (las sesiones existentes se migran sin cerrarse), dispositivo, IP y última actividad; listar/cerrar/cerrar las demás. 2FA TOTP (RFC 6238, secreto cifrado con `SecretBox` AES-GCM derivado de SECRET), activar pide contraseña y cierra las demás sesiones, 10 códigos de recuperación de un uso, desactivar pide contraseña + código. Web: `SecurityPanel.svelte`; app: el login de Sincronizar pide el código (la gestión de sesiones y 2FA se hace en la web: decisión para no duplicar pantallas)
- [x] 2.5 `InviteStore`: enlace `/invitacion/TOKEN` de un solo uso (transacción) con caducidad 1-30 días; admin crea/lista/anula; alta manual intacta
- [x] 2.6 `AuditLog` + plugin `auditAdminActions`: toda petición no-GET bajo /api/admin queda registrada (quién, qué, detalle, IP, resultado), más 2FA, invitaciones usadas y configuración inicial. Se guarda un año. Visible en el panel de admin
- [x] 2.7 Sin contraseña por defecto: sin `ADMIN_PASSWORD` el servidor genera un código de un uso (`data/setup-token`, permisos 600, y en el log) y la web muestra «Primera configuración» en `/configurar?token=…`. `install.sh` ya no genera contraseña e imprime ese enlace. Invitados desactivados por defecto (`GUESTS_ENABLED=false`; el VPS actual conserva su .env)

### Fase 3 — Resistencia a cambios en las webs ✅
- [x] 3.1 `server canary [fuente...]` (`Canary.kt`): recorre cada fuente con `SourceDiagnostics`, una a una con `CANARY_PAUSE_S` (20 s) entre ellas, guarda el resultado (migración 5, `source_health`) y avisa por Discord/ntfy al caer y al recuperarse. Cron en el VPS cada 6 h (lo instala `install.sh`, con -Xmx200m). Probado contra 4 fuentes reales: OK
- [x] 3.2 `SourceValidation` (shared, puro, con HTML real): ≥3 novedades, títulos no vacíos ni de relleno, sin duplicados, URLs http(s), capítulos > 0, imágenes válidas, texto ≥200 caracteres, servidores > 0. Paso crítico fallido → DEGRADED → `SourceManager.degraded` la quita de todas las listas en server, web (Inicio) y app (recibe la lista al sincronizar); Ajustes › Fuentes muestra el motivo; vuelve sola. Búsqueda/imagen fallida → solo aviso. Panel de admin: salud y «Probar»
- [x] 3.3 `sources/*.json` (formato en `sources/README.md`) → `java scripts/SignSources.java` → `shared/src/main/resources/sources/bundle.json` + `.sig` (Ed25519; verificación en Kotlin puro, `Ed25519.kt`, probada con vectores RFC 8032 y contra el JDK; clave pública en `SourceBundles.PUBLIC_KEY`, privada en `~/.yomukai/sources-signing.key`, fuera del repo). Server y app aplican el incluido, el cacheado o uno nuevo de GitHub (cada 6 h) solo si la firma es válida; el server lo sirve en /api/source-bundle. Workflow «Firmar fuentes» listo; **falta que el usuario ponga el secreto** (`gh secret set SOURCES_SIGNING_KEY < ~/.yomukai/sources-signing.key`; no tengo permiso para escribir secretos). R8: keep de `data.source.declarative.**` y bundle comprobado dentro del APK
- [x] 3.4 `DeclarativeSource`: motores html, madara (valores por defecto del tema), nextjs (`__NEXT_DATA__`) y sveltekit (`__data.json`, decodificador devalue). Marmota migrada a `sources/marmota.json` con sus mismas aserciones (tests en `DeclarativeSourceTest`); Next.js probado con el HTML real de Flame y SvelteKit con un `__data.json` real de AnimeAV1. Las demás fuentes siguen en Kotlin (API propias, Cloudflare, lógica especial); se migran cuando encajen
- [x] 3.5 Páginas: JSON configurado → selector → URLs de imagen en scripts → contenedor con más imágenes (probado: con el selector roto encuentra las 33 páginas). Ficha: JSON → selector → ld+json → og:. Decisión: lo configurado va antes que og: porque og: suele llevar el nombre del sitio
- [ ] 3.6 Dominios en config y seguimiento de redirecciones
- [x] 3.7 yt-dlp 2026.10.26 (Unlicense, binario fijado por SHA-256) en la imagen del VPS: `YtDlp.kt` con `-j` (sin descarga), 1 a la vez, 25 s, `--ignore-config`, URL tras `--` (no se puede inyectar opciones), solo cabeceras UA/Referer/Origin; gancho `VideoExtractors.preferred` (server) antes que los extractores Kotlin; host que falla 2 veces se salta 24 h; flag `ytdlp`. Sin el extractor «generic» (vector de SSRF) y solo si el host resuelve a IPs públicas: así solo usa los extractores de sitios conocidos (ok.ru, streamtape, dood…); mp4upload/zilla/voe los resuelven los extractores Kotlin. App: `EmbedSniffer` (WebView oculto, intercepta .m3u8/.mp4, sin salir del sitio, sin ventanas) cuando no queda servidor directo
- [x] 3.8 `scripts/auto-repair.sh` + `SourceRepairHandler` (worker fuera de Docker con git, gh, claude y un clon en `WORKER_REPAIR_REPO`): rama, HTML actual como fixture, Claude Code headless sin Bash ni red, solo puede editar `sources/<id>.json`, parsers de fuentes y tests; cualquier otro cambio aborta; tests en Docker `--network none` sin credenciales y aviso explícito de que el HTML son datos no fiables, tests, PR (borrador si fallan). Nunca fusiona. El canario lo encola (flag `source_repair`, apagado por defecto, una vez al día por fuente)

### Fase 4 — Obra unificada y diferenciadores ✅ (lógica y API; las pantallas van en el rediseño de la Fase 5)
- [x] 4.1 `shared/data/work`: `WorkKey` (al:/mal:/t:familia+nombre), `ChapterMerge` (números normalizados, todas las fuentes por capítulo, elección por idioma → fuente favorita → manual → la que más tiene, huecos), `WorkProgress` (lo leído en una fuente cuenta en todas). Server (migración 6): `works`, `work_sources`, `work_key` en library/history (las claves viejas siguen; relleno en segundo plano 6 cada 2 min por el límite de AniList), `work_choices`. API: /api/work, /api/works/{key}, /chapters (fuentes lentas no bloquean, 15 s), /choice, /sources (unir a mano). AniList ahora trae MAL id, banner, episodios, capítulos y próximo episodio. Probado en vivo: MangaDex → al:137942 con 83 capítulos y huecos detectados. Jellyfin entrará como una fuente más de `work_sources` con prioridad (Fase 10)
- [x] 4.2 `WorkGrouper`: una tarjeta por obra; IDs conocidos mandan; por nombre solo si `TitleMatcher` confirma; si no, tarjetas separadas con `doubt`/`uncertainWith`. Aplicado a la búsqueda (SSE, `SearchGroupDto.doubt`) y a /api/updates/unified (recién actualizado de todas las fuentes intercalado: 60 tarjetas, 7 con varias fuentes en la prueba)
- [x] 4.3 `WhereNext` + tablas `adaptation_points`/`adaptation_history`: cualquiera (no invitados) corrige «el anime hasta el ep. N adapta hasta el cap. C / vol. V», con historial. API /works/{key}/continue, /adaptations, /adaptations/history. Relaciones (adaptaciones) de AniList ya en metadatos
- [x] 4.4 `SpoilerShield` (puro, con tests): títulos/miniaturas más allá del siguiente capítulo, sinopsis de secuelas sin acabar la actual, comentarios con «spoiler hasta N». UI (difuminado con toque para revelar, por usuario y por obra) en el rediseño de la Fase 5
- [x] 4.5 `AudioFingerprint` (puro): el tramo común más largo entre las huellas Chromaprint de dos episodios (inicio → opening, final → ending). Worker: `FingerprintHandler` (ffmpeg solo audio con protocol_whitelist de red + fpcalc, baja prioridad). /api/skip encola el trabajo (BACKGROUND, cacheado) y responde 204 si no hay worker → el reproductor usa +85 s. Botón/auto-salto en el reproductor del rediseño (Fase 5)
- [x] 4.6 `FillerGuide` (AnimeFillerList, probado con la página real de Bleach: 366 episodios, 163 de relleno) + /api/fillers (caché 7 días en `fillers`). Marcas y «saltar relleno» en la UI de la Fase 5
- [x] 4.7 /api/calendar: anime de la biblioteca con el próximo episodio de AniList; manga/novela con `ReleaseCadence` (fechas reales de capítulos, solo si el ritmo es regular). Vista semanal en la sección Calendario del rediseño (Fase 5); avisos con las notificaciones de la Fase 6

### Fase 5 — Rediseño (web y app) — casi ✅ (falta la ficha de obra con banner en la app Android)
- [x] 5.0 `docs/referencias/ANALISIS.md` (10 capturas de Seanime analizadas: rail, héroe con degradado, pósters 2:3, tipografía tipo Inter, acento contenido). Seanime es GPLv3: no se ha copiado código ni recursos, solo ideas de composición
- [x] 5.1 Inicio útil sin fuentes: héroe y filas de AniList (tendencias, temporada, géneros) aunque no haya biblioteca
- [x] 5.2 Tokens en `shared/src/main/resources/design/tokens.json` → `web/src/tokens.css` (`web/scripts/tokens.mjs`) y `DesignTokens.kt` (`scripts/tokens-android.mjs`, test `DesignTokensTest` que impide que se desincronicen). Fuente Inter (OFL) empaquetada
- [x] 5.3 Navegación: rail con etiquetas (se pliega entre 1024 y 1279 px), barra superior con buscador (Ctrl K / ⌘K en Apple), campana y avatar; en móvil pestañas Inicio/Anime/Manga/Novelas/Biblioteca. Páginas cargadas bajo demanda
- [x] 5.4 Inicio tipo Netflix: héroe rotatorio (8 s, se para con el ratón/foco y con movimiento reducido), filas configurables (Seguir viendo, Seguir leyendo, Nuevo en tu biblioteca, Temporada, Tendencias, Para ti, Novedades unificadas, géneros)
- [x] 5.5 Tarjetas: póster 2:3, insignias, barra de progreso, nota, info al pasar el ratón (pulsación larga en móvil), aviso «¿Misma obra?»; tarjeta ancha 16:9 para episodios
- [x] 5.6 Ficha de obra (web): banner de AniList difuminado, títulos alternativos, «En N fuentes», «¿Dónde sigo?» (con corrección por cualquier usuario), marcas de relleno/mixto y «Ocultar relleno», anti-spoilers (títulos difuminados de lo que aún no has visto; se revelan al pasar o enfocar). En móvil la ficha usa su propia barra
- [x] 5.7 Anime / Manga y manhwa / Novelas con explorador (género, año, estado, formato, origen, nota, orden, «solo lo que está en mis fuentes»)
- [x] 5.8 Búsqueda unificada: pósters agrupados por tipo, «+N fuentes» y aviso de posible misma obra
- [x] 5.9 Reproductor: «Saltar opening/ending» con las marcas de audio del worker (si no hay, +85 s), cuenta atrás de 5 s al siguiente episodio con cancelar, «Saltar relleno». Lectores sin cambios visuales grandes (ya eran de pantalla completa)
- [x] 5.10 Calidad: esqueletos de carga, imágenes perezosas y a su tamaño (DPR), foco visible, movimiento reducido, sin desbordes horizontales (comprobado en Chromium y WebKit, iPhone y Mac). PWA con accesos directos (Seguir, Biblioteca, Calendario, Anime) para Android, Windows, macOS e iOS
- [x] 5.11 Capturas antes (`docs/capturas/antes`) y después (`docs/capturas/despues`: inicio, ficha, búsqueda y reproductor en escritorio y móvil, WebKit, Android)
- [x] Portadas nítidas: las miniaturas de las fuentes (200-300 px) se cambian por la portada extraLarge de AniList cuando la página ya está unida a una obra (listas, búsqueda, biblioteca); lo que sale en las listas se identifica poco a poco en segundo plano (4 cada 2 min, cola acotada a 300)
- [x] App Android: mismos colores y radios (tokens), inicio con destacado de AniList y filas de tendencias/temporada
- [x] App Android: ficha con banner de AniList y portada nítida
- [x] App Android: marcas de relleno/mixto y anti-spoilers (difuminado en Android 12+, texto sustituido en anteriores) en la ficha
- [x] App Android: tarjeta «¿Dónde sigo?» (`WhereNextSection.kt`, con servidor conectado)

### Fase 6 — Social
- [x] 6.1 Salas: una persistente por usuario (código de 6 sin caracteres ambiguos, enlace /sala/CODIGO) y hasta 3 temporales; fila «Salas activas» en Inicio con quién está y qué ve; botón «Ver con amigos» en el reproductor (abre tu sala y copia el enlace para Discord)
- [x] 6.2 Watch party (WebSocket con la sesión, Origin comprobado, límite por conexión, sesión revalidada cada 30 s): el anfitrión manda; posición con el reloj del servidor; deriva >2 s → salto, 0,3-2 s → 0,95x/1,05x; si alguien carga se pausa a todos hasta 10 s; episodio, servidor y velocidad sincronizados; siguiente episodio solo lo decide quien controla; anfitrión pasa al que lleva más tiempo y se puede ceder; «todos controlan»; latido y reconexión. Cada cliente reproduce su propio vídeo (nada pasa por la sala). Lógica pura en shared (PartySync) con tests y test con varios clientes reales. Probado en vivo con dos navegadores: 0,1 s de diferencia
- [x] 6.3 Chat de sala: menciones resaltadas, reacciones fijas que flotan sobre el vídeo, spoilers tras un toque, antispam, 7 días de historial. Solo texto (nunca HTML)
- [x] 6.4 Perfiles (servidor y web; falta la app Android)
- [x] 6.5 Privacidad: biblioteca oculta entera o por categorías (también fuera del feed, la presencia y «quién la tiene», incluida la misma obra desde otra fuente)
- [x] 6.6 Feed (ráfagas agrupadas, maratón ≥12) y presencia («viendo/leyendo ahora» en /amigos y el perfil)
- [x] 6.7 Valoraciones 1-10 con reseña y media del grupo; recomendaciones con mensaje (fila en Inicio)
- [x] 6.8 Foro: hilo por obra (pestaña en la ficha) y categorías, citas, menciones, reacciones, spoilers hasta N, imágenes re-dibujadas, markdown sin HTML, necromante
- [x] 6.9 Moderación: fijar, cerrar, borrar, silenciar con caducidad (también en el chat de salas y al editar o reaccionar), todo en auditoría; herramientas en el propio hilo para admins
- [x] 6.10 Notificaciones: campana con avisos en vivo (SSE), ntfy al móvil con tema privado aleatorio (Ajustes), Discord solo salas abiertas e hilos nuevos y sin poder mencionar a nadie
- [x] 6.11 Logros: `achievements.json` (32), incremental, retroactivo en silencio, secretos ???, % del grupo, aviso animado, en el perfil; eventos de cliente en lista blanca

### Fase 7 — Temas
- [x] 7.1 Tema = JSON de tokens validado, aplicado igual en web y app  - [x] 7.2 Temas de serie (8, todos AA)  - [x] 7.3 Editor con vista previa y contraste WCAG
- [x] 7.4 Galería entre amigos, usar con un toque, códigos de importar/exportar (logro Diseñador de interiores)  - [x] 7.5 Sin CSS libre; fondos re-dibujados y privados hasta publicarse

### Fase 8 — Voces
- [x] 8.1 Interfaz TTS con niveles y elección automática (servidor; falta el nivel dispositivo)  - [x] 8.2 Script de comparación (`scripts/tts-compare.sh`, pendiente de ejecutar en el worker real)  - [ ] 8.3 Voces por personaje
- [~] 8.4 Pregeneración cacheada (prefetch y caché compartida ✅; falta la nocturna)  - [x] 8.5 Android con pantalla apagada (servicio en primer plano + MediaSession + batería)  - [x] 8.6 Web MediaSession

### Fase 9 — OCR, voz en cómics, autoscroll, traducción, idiomas
- [~] 9.1 Pipeline OCR (shared + servidor + worker ✅; falta ML Kit en la app y usarlo en la web)  - [x] 9.2 Cómics en voz alta (web; falta la app)  - [~] 9.3 Autoscroll inteligente (web ✅ con aprendizaje de velocidad; falta la app)
- [x] 9.4 Traducción de bocadillos (web; falta ML Kit en la app)  - [~] 9.5 Modo aprendizaje (web ✅; falta la app, diccionario offline y mazos compartidos)

### Fase 10 — Jellyfin, Kavita/Komga
- [x] 10.1 Config admin (cifrada, prueba desde el VPS, panel web)  - [x] 10.2 Descargar a Jellyfin (botón en la ficha)  - [x] 10.3 Descarga automática (obras seguidas)  - [x] 10.4 Jellyfin como fuente (web; falta la app)
- [x] 10.5 Progreso bidireccional  - [x] 10.6 Salas con Jellyfin (calidad según los que estén)  - [x] 10.7 CBZ + ComicInfo + aviso de escaneo a Kavita/Komga  - [x] 10.8 Tests con Jellyfin falso (descarga, escaneo, fuente, vídeo, progreso)

### Fase 11 — Pulido, instalación y documentación
- [~] 11.1 Panel de admin (CPU, disco, memoria, ancho de banda, workers, catálogo de fuentes, integraciones; falta espacio del NAS)  - [x] 11.2 Releases automáticas (`.github/workflows/release.yml` al subir una etiqueta v*: APK firmada con DEBUG_KEYSTORE_B64, .sha256, changelog de commits; la app avisa de versiones nuevas con `AppUpdates` + `UpdateBanner`)  - [x] 11.3 Continuar en otro dispositivo (la sincronización ya lleva historial y progreso: «Seguir viendo/leyendo» sale igual en web y app)
- [x] 11.4 Importar/exportar (`server/…/Imports.kt`, migración 15 `imports`: AniList por usuario, MyAnimeList con MAL_CLIENT_ID, copias de Mihon/Tachiyomi .tachibk leídas a mano en protobuf; cola resuelta en segundo plano con `catalog.locate` (solo misma obra confirmada) que añade a la biblioteca con estado y marca lo leído; `/api/import/{origin}`, `/api/import/status`; web: `ImportPanel.svelte` en Ajustes › Tus datos. Probado en vivo con una lista pública de AniList)  - [ ] 11.5 UX  - [ ] 11.6 Dos docker-compose + guía Tailscale  - [ ] 11.7 ARCHITECTURE.md, README

### Presupuesto de rendimiento
| Momento | RAM server reposo | RAM bajo carga | CPU | API p95 | Bundle web (JS+CSS gz) | APK release |
|---|---|---|---|---|---|---|
| Antes de Fase 0 (2026-11-05, local Windows, JVM SerialGC) | 185 MB | 257 MB | 4,0 s de CPU en 300 peticiones + 4 novedades | 5 ms local · 575 ms novedades reales | 84 KB (JS 74,9 + CSS 9,2) + hls.js 180,5 KB aparte | 34,7 MB (v1.13.0; ~23 MB son Tor) |
| Después de Fase 0 (mismo equipo) | 181 MB | 260 MB | 5,0 s | 5 ms local · 536 ms novedades | 84 KB (sin cambios) + hls.js 180,5 KB | sin cambios de código de la app (solo formato) |
| Después de Fase 1 (Docker local, límite 1 GB / 1 CPU) | 107 MB | 165 MB | 21 % de 1 CPU en el pico | 8 ms local · 925 ms novedades | 85 KB + AdminPanel 3,9 KB (solo admins) | ~igual (+1 pantalla de texto) |
| Después de Fase 2 (local) | sin cambio apreciable (las comprobaciones son hashes y buckets en memoria) | — | — | 5-8 ms local | 77 KB + paneles de admin (4,4 KB) y seguridad (2,3 KB) bajo demanda | ~igual |
| Después de Fase 5 (local, arranque limpio con el heap de Docker: SerialGC, -Xmx550m) | 198 MB | 256 MB | 6,0 s en 300 peticiones + novedades | 7 ms local · 370 ms novedades | 99,6 KB (JS 86,7 + CSS 12,9) + Inter (woff2 latin ~48 KB, en caché) + hls.js 178 KB solo en el reproductor; Explorar 2,8 KB y Calendario 1,6 KB bajo demanda | 55 MB debug (release sin medir) |
| Después de Fases 6-10 + idiomas + catálogo (2026-11-07, local, scripts/bench.sh) | 208 MB | 240 MB | 6,0 s en 300 peticiones + novedades | 6 ms local · 851 ms novedades | inicial 91 KB gz (index) + api 25,5 KB; inglés 33,8 KB solo si se elige; hls.js 180 KB solo en el reproductor | ~igual (debug); OkHttp 5 y Compose 2026 |

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
