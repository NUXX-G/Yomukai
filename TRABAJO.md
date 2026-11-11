# TRABAJO — traspaso de la tanda actual

Léelo primero si retomas esto. `PLAN.md` es el histórico de fases; este fichero es lo que se está haciendo ahora.

## ▶ DÓNDE ESTOY EXACTAMENTE (actualizar en cada commit grande)
**AHORA (2026-11-12): el usuario probó la v2.7.0 («no está mal, han mejorado cositas, pero sigue algo pocho»). PEDIDO 2026-11-12 (máxima prioridad, en este orden): ver la lista «PEDIDO 2026-11-12» abajo. Trabajando en ello.**

### ⚠️ PRIORIDADES DEL USUARIO 2026-11-11 (noche) — MANDAN SOBRE TODO LO DEMÁS, EN ESTE ORDEN
Tras probar la app 5 minutos: «hay muchos más fallos». Trabajar autónomo, commits pequeños y push a menudo.
1. **TODO POR OBRAS.** Hoy NO va por obras del todo, y sin eso la arquitectura no tiene sentido. Cualquier tarjeta
   (Inicio, secciones, búsqueda, calendario, relacionadas, «Para ti», explorador, biblioteca, compartidos) es una OBRA
   del índice que está en sus fuentes; si no está en ninguna fuente, no sale. Al tocarla se abre al instante (nunca
   «Buscando en tus fuentes…»). Ficha de obra con todas sus fuentes/idiomas. Web, servidor y app, con tests.
2. **MÁS FUENTES FUNCIONANDO: scrapear mejor.** Muchas fuentes no dan capítulos/páginas/vídeo. Hacer todo lo posible
   por conseguirlos (es scraping legal): FlareSolverr/Cloudflare, cabeceras, dominios nuevos, APIs internas de cada
   web, JS ofuscado, servidores de vídeo, reintentos. Medir con `scripts/source-timing.mjs` y sondeos; tests con HTML real.
3. **Velocidad para el usuario y calidad de imagen** (portadas nítidas, carga rápida).
**Fallos que vio el usuario (2026-11-11 noche, en 5 min; «hay muchos más»). Orden = prioridad:**
P1 — obras
- [x] O1 (solo obras confirmadas por AniList en anime/manga/manhwa/manhua — `IDENTIFIED`, cómics y novelas sí —; filas de AniList (Inicio, explorador, calendario) y relacionadas solo con obras presentes en fuentes que funcionan y con su obra (`Discover.decorate`, `/api/obras/presentes`, `keepInSources`); lo que AniList enseña y no está confirmado se confirma primero por id (`WorkIndex.want/adoptNext`): en local Inicio pasó de 0 a 8 filas en 5 min; «Título II» = temporada 2, migración 34; tests IdentifiedWorksTest, SeasonsTest) Una obra = UNA tarjeta en todo Yomukai (nada de «varias de la misma»). Si una obra no se puede identificar
      (fanfic/obra desconocida sin AniList/MangaDex), se queda fuera: prefiere catálogo profesional a cantidad de basura.
- [x] O2 (web y app: chips de versión en la ficha; `/api/videos` ordena por la versión elegida, que el servidor recuerda por usuario (`Services.audioChoice`); app: `ServerVideos` pide los servidores al servidor y reproduce el stream directo, relé de respaldo. Antes: WEB HECHA: chips de versión en la ficha —Subtítulos en español/inglés, Castellano, Latino, Doblaje EN— desde `versionsOf`; Inicio/explorador/calendario abren la obra en tu idioma, no la primera fuente; probado con One Piece. FALTA la app Android. Antes: servidor: `orderWorkPlaces` abre la subtitulada antes que un doblaje y la fuente que sigues primero, test WorkPlacesOrderTest; FALTA el selector de versión visible en la ficha web/app con SUB ES · SUB EN · LAT · CAST · DUB EN) Anime: pestaña/selector de versión dentro de la obra: JP + subs ES / JP + subs EN / latino / castellano / dub EN.
      Por defecto: japonés con subtítulos en el idioma de la interfaz del usuario (con la app en español NO abrir la
      versión con subs en inglés, que es lo que pasa ahora).
- [~] O3 (desde una sección de doblaje la ficha solo abre fuentes con ese audio; si no responde, mensaje claro. FALTA comprobar en el VPS qué fuentes marcan CASTELLANO: sobre todo AnimeOnlineNinja, que necesita FlareSolverr) Secciones de doblaje (Castellano, Latino, Inglés) muestran SOLO obras con ese audio (hoy «Castellano» enseña
      subtitulados en español). Aunque sean pocas.
- [x] O4 (biblioteca: insignia «Caída» con el motivo en obras de fuentes caídas. Listas y búsqueda ya excluyen las obras que solo están en fuentes caídas según el canario, `downSql()`; FALTA el aviso en la biblioteca) Obra cuyas fuentes con capítulos están todas caídas: no sale en Inicio/secciones; si está en la biblioteca de
      alguien, sale ahí con un aviso claro (le pasó con One Piece). Nada de entrar y encontrar un error.
P2 — fuentes (scrapear mejor: hacer lo que haga falta para conseguir capítulos/vídeo)
- [x] F1 (`parseJson` → `NotJsonException` con mensaje claro; BOM y prefijo )]}' aceptados; test NotJsonTest) Una fuente da `com.google.gson.stream.MalformedJsonException ... line 1 column 12` (la web devuelve HTML /
      Cloudflare en vez de JSON). Detectar respuesta no-JSON → reintentar por FlareSolverr / mensaje claro; nunca enseñar
      la excepción cruda.
- [x] F2 (FlareSolverr también para servidores de vídeo de la lista `EmbedProbe.VIDEO_HOSTS` y para comprobaciones genéricas y DDoS-Guard; `WebProtection.isGenericBotCheck` reconoce esa página → el servidor reintenta por FlareSolverr; FALTA descartar en el reproductor los servidores de vídeo (embeds) que la muestran) Un episodio redirige a una página «Verification · Security check · Checking your browser… Verification
      failed» (anti-bot de un servidor de vídeo). No mostrar esos servidores; resolver el vídeo real o saltar al siguiente.
- [~] F3 (Dood por dood.li en directo; cada stream directo se prueba —primer byte, 6 s— antes de ofrecerlo: `Catalog.workingFirst`. `EmbedProbe`: cada servidor se abre antes de ofrecerlo; borrado, «sandboxed iframe» o anti-bot fuera; stream sacado del JS empaquetado, Streamtape, Streamwish por su espejo swdyu.com; sin iframe duplicado al lado del directo; sin servidores de descarga. Probado: AnimeFLV, JKAnime, AnimeAV1, AnimeDLatino con casi todo en directo. FALTA: Filemoon nuevo (byse*, app cifrada), Dood (dsvplay), savefiles) Episodios que abren servidores con «WE ARE SORRY We can't find the video» y «This video is not available due to
      sandboxed iframe!». Comprobar el servidor antes de ofrecerlo, quitar sandbox donde sea seguro o extraer el
      m3u8/mp4 directo; ordenar servidores por los que funcionan; probar TODAS las fuentes de anime de punta a punta.
- [x] F4 (causa: las 3 MangaDex comparten IP y MangaDex limita ~5 pet/s → respuesta no-JSON; cola común `MangaDexThrottle` 4/s y reintentos. Antes: en local MangaDex va de punta a punta: listado, ficha, capítulos, imágenes; api.mangadex.org responde 200 con nuestro UA y 302 con UA de navegador. FALTA el texto de Administración › Salud de las fuentes del VPS para ver qué paso falla allí) MangaDex sale como caída y NO lo está (https://mangadex.org/). Revisar canario/salud y la API (api.mangadex.org).
P3 — velocidad e imagen
- [x] V1 (pantalla de carga espera a las portadas de Inicio, máx. 4 s; descargas con plazo total de 45 s y 24 a la vez. Causa: la cola de reducir imágenes tenía 1 hueco y lo ocupaba durante la descarga → portadas en fila; ahora descarga en paralelo, 2 huecos de CPU, original al momento si la cola está ocupada, 10 s máximo y luego la miniatura; medido 7,1 s → 0,65 s. FALTA: la pantalla de carga precargando las portadas de Inicio) Portadas: unas cargan y otras no, o tardan ~1 min; se ve horrible. La animación de entrada debe servir para
      precargar el máximo de portadas. Revisar el proxy /api/img (tiempos, caché, concurrencia, fallback a otra portada).

### PEDIDO 2026-11-12 (tras probar la v2.7.0) — MÁXIMA PRIORIDAD
- [ ] P1 **Doblaje: fuera como categoría.** Entró en un «castellano» y el audio era japonés con subtítulos (el audio de una
      página NO es fiable: Ninja marca toda la lista «anime-castellano»). Quitar las secciones/pestañas de doblaje
      (Explorar › Anime › Doblajes) y el selector de versión por doblaje; dejar SOLO una etiqueta (CAST/LAT) en
      tarjetas/servidores cuando la web lo diga por servidor. Si algún día se puede comprobar de verdad (metadatos del
      host, huella de audio en el worker), volver a ello; hoy no. Golden Boy (castellano) sí iba.
- [ ] P2 **Leviathan da error**: «HTTP 404 en https://api.manhwaweb.com/chapters/see/leviatan_1727741041911-9». Revisar
      ManhwaWeb (ids de capítulo / endpoint cambiado); test con JSON real.
- [ ] P3 **Manhwa y manhua casi vacío**: «2 en populares, 2 en romance…», queda feo y poco profesional, y un manhwa de
      romance que SÍ está en el buscador no sale en la portada de manhwas. Las filas de manga/manhwa deben llenarse
      desde el índice de obras (por tipo, popularidad, género), no solo AniList filtrado; si una fila tiene menos de N,
      rellenar o no enseñarla.
- [ ] P4 **Calidad máxima siempre** en manhwas y todo lo que se lee: el lector debe pedir la página a tamaño original
      (o el mayor disponible), nunca reducida por el proxy; comprobar `w=` del lector web y app.
- [ ] P5 **Pantalla de carga con curiosidades**: las portadas tardaron ~10 s la primera vez y luego todo «de puta
      madre». Alargar la carga SOLO la primera vez (10-15 s) mostrando curiosidades aleatorias de anime/manga para leer
      mientras cargan las portadas; después (caché) entrar rápido.

### PEDIDO DEL USUARIO 2026-11-11 (tarde) — NO PERDER
1. **Paleta**: blanco y negro tipo manga + UN solo color (brasa `#ff5a1f`). Máximo 3 colores en total. HECHO en
   tokens/web (commit 79298d3); los tipos de obra se distinguen por forma, no por color. Ver `docs/referencias/ESTILO.md`.
2. **Botones** neobrutalistas «finos» siguiendo las buenas prácticas (borde 3 px, esquinas rectas, sombra dura 4 px,
   hover = hundir a medias, pulsar = hundir del todo). HECHO en la web (`web/src/app.css`, sección `.btn`).
3. **Solo obras que estén en sus fuentes**: cualquier anime/manga/obra que salga (Inicio, secciones, búsqueda,
   calendario, relacionadas, «Para ti", explorador…) tiene que estar en sus fuentes; si no está, NO sale. Todo por
   OBRAS, y **nunca más «Buscando en tus fuentes…»**: al tocar una tarjeta se abre al instante porque ya se sabe
   dónde está (índice de obras del servidor). En la app igual. Tiene que quedar asegurado con tests.
4. Al terminar 1-2: **publicar release** y decirle exactamente dónde y cómo actualizar (VPS, worker, app). Después
   hacer el punto 3, comprobarlo de punta a punta y publicar otra vez.
5. Apuntar aquí todo lo que quede pendiente para que otra sesión de Claude pueda seguir.

### PENDIENTE (orden)
- [x] Bloque 7 en la app Android (InkButton/InkOutlinedButton en `ui/components/InkButtons.kt`, `Modifier.inkPanel`, tipo por forma; probado en el emulador): tokens ya regenerados (`DesignTokens.kt`), falta `MediaCard`/`TypeBadge` de
      `ui/components/Components.kt` con borde de tinta, sombra dura, tipo por forma (sin colores por tipo:
      `badgeColor()` aún usa 6 colores) y botones con el mismo estilo.
- [x] 7.6 capturas «después» en `docs/capturas/despues/` (escritorio y móvil) con Playwright.
- [ ] Release v2.6.0 (estilo) + instrucciones de actualización al usuario.
- [ ] Punto 3 del pedido (solo obras de sus fuentes, sin «buscando»): web, servidor y app, con tests.
- [x] Release v2.7.0 con el punto 3 (pre-release, docs/releases/v2.7.0.md).
- [ ] Menores: LectorTMO sin capítulos y ZonaTMO sin páginas (comprobar desde el VPS: en casa la operadora bloquea);
      F6.1 el usuario debe elegir voz con `scripts/tts-compare.sh` en el worker; F6.3 OCR en el navegador sin worker;
      espacio del NAS en el panel de admin (11.1); README/ARCHITECTURE sin las funciones de los bloques 3-6 (11.7).

## TRABAJO EN PARALELO (desde 2026-11-11)
Varias sesiones de Claude Code con cuentas distintas, cada una en su copia del repo, todas sobre main:
- **Sesión A** (Desktop/Yomukai): bloque 3, después bloque 7 (aspecto, al final y en solitario) y las releases.
- **Sesión B** (Desktop/Yomukai-2): bloque 4 — instrucciones en docs/sesiones/B.md. Migraciones 24-27, puerto 8194.
- **Sesión C** (Desktop/Yomukai-3): bloques 5 y 6 — instrucciones en docs/sesiones/C.md. Migraciones 28-31, puerto 8195.
- Sesión C: bloques 5 y 6 hechos (web, servidor, worker y app probada en el emulador). Solo falta que el usuario elija voz con el worker real (F6.1). Visto de paso (no es de C): SkyNovels muestra «**\[Misión…\]**» con el markdown sin limpiar en la app. Pruebas de punta a punta para repetirlas: `scripts/e2e-calendario.mjs`, `e2e-traduccion.mjs`, `e2e-bocadillos.mjs`, `e2e-autoscroll.mjs` (servidor en el 8195).
- Sesión A: migraciones 32-35, puerto 8193. Solo A publica releases. Emulador: avisar aquí antes de usarlo.
- **Lint pendiente en main (2026-11-11, visto por A; CI no pasará hasta arreglarlo):** sesión B — ARREGLADO (require(), other.cancel(), y @Suppress en las rutas y los Composables largos; scripts/lint-kotlin.sh ya no marca nada de B); sesión C — SeasonCalendarTest.kt:146 (i sin usar), app CalendarScreen.kt:89 (complejidad 23) y :303 (línea larga), app HomeScreen.kt:76 (LongMethod 154). Partid las funciones en otras más pequeñas; ejecutad bash scripts/lint-kotlin.sh antes de cada commit.
- Las migraciones admiten huecos (cada sesión su rango). Migrations.kt quedó con finales de línea LF (antes mezclados): si al hacer rebase choca, quédate con la versión de main y vuelve a añadir solo tus Migration(n, …) en su sitio.

## PEDIDO 2026-11-10 (feedback `docs/feedback/2026-11-10.pdf`, con capturas)
Autonomía total (solo preguntar si es irreversible y peligroso: borrar datos de usuarios, reescribir historial). Permiso
para push a main, ramas, PRs, mergear los míos con CI en verde (los de auto-reparación de fuentes solo si el diff toca
únicamente selectores/regex/dominios/fixtures), etiquetas y releases (semver, changelog en español para el usuario,
pre-release si toca obras/migraciones/salas; nunca con tests en rojo). Primero web, luego app. Cada fallo: reproducir →
arreglar → test que lo impida. **Nada se marca [x] sin probarlo de punta a punta como una persona** (traducción,
autoscroll y voces estaban marcados y no iban). Al cerrar cada bloque: qué hice, qué publiqué y qué tiene que probar él.

**Bloque 1 — fallos que rompen el uso**
- [x] F1.1 «Siguiente»: en sala el estado viejo devolvía al anfitrión al episodio anterior (arreglado y probado en local con sala real); en obra unificada elige el siguiente número entre todas las fuentes (`lib/next-episode.js`, tests); la página embed del servidor tiene «Siguiente →». Fuente normal probada (AnimeFLV). Jellyfin = fuente normal del servidor (sin NAS real para probar). App hecha.
- [x] F1.2 Barra: el relleno no se movía mientras el vídeo buscaba (`seeking` sin `timeupdate`); `lib/scrub.js` con tests; probado con clic y teclado (flechas = ±10 s, sin doble salto). Volumen con el mismo cálculo. App hecha.
- [x] F1.3 Botón «Ficha» + título enlazado en reproductor y lectores; router con `history.state.idx`; entrada directa mete la ficha debajo (probado: atrás del navegador → ficha). App hecha.
- [x] F1.4 (causa: >3 filas por envío = «masivo»; acabar el ep. 7 manda del 1 al 7 y la app sincroniza en lote. Ahora cuenta el capítulo con historial reciente; test con 3 usuarios que falla sin el arreglo; probado en la web con 2 usuarios). Original: Actividad de amigos: solo sale la mía. Revisar cadena completa (eventos web/app, `hidden` por defecto,
      categorías privadas, cliente). Probar con usuarios A, B, C.
- [x] F1.5 Causas: los chips solo salían con ?obra= (no desde biblioteca/búsqueda/Continuar) y el «leído» de la obra solo miraba las fuentes del idioma mostrado. Ahora GET /api/obras/de, leído entre todas las páginas de la obra, Continuar lo respeta y vuelve a tu fuente. Probado ES→EN→ES con Dragon Ball Super: Beerus (3 vistos y «Continuar · Ep. 5» en los dos). App hecha.
- [x] F1.6 (HardWorkCasesTest: temporadas escritas de cualquier forma, partes, spin-offs, películas, remakes de anime por año entre paréntesis —en manga el año que añaden las webs no separa—, «×», varios idiomas, novela/manga/anime; migración 22 reagrupa solo lo provisional afectado; biblioteca/progreso/historial van por fuente+id y no se tocan). Original: Obra unificada a prueba de fallos: batería de casos reales (temporadas, partes, títulos multi-idioma,
      spin-offs, remakes, novela/manga/anime con el mismo nombre); nunca perder biblioteca/progreso/historial.
- [x] F1.7 (tipo por AniList: formato + país, migración 23 corrige los confirmados de 50 en 50; filtro por tipo de la obra; búsqueda puntúa todos los nombres, erratas con prefijos cortos + Damerau, sin espacios, umbral contra basura; probado en local: «one pice», «dragon bal super», «onepiece», «qwzx» vacío). Original: Buscador: tipo por metadatos (AniList format + countryOfOrigin: JP manga, KR manhwa, CN manhua; cómic
      occidental aparte; fuente de respaldo) y relevancia (exacto/alternativos primero, erratas sin basura). Tests reales.
- [x] Extra del PDF: sinopsis en español desde una fuente española de la obra (servidor, en segundo plano; probado en local: One Piece y Bleach pasan a español tras un ciclo).

**Bloque 2 — velocidad y bloqueos**
- [x] F2.1 (medido con scripts/source-timing.mjs → docs/medidas/fuentes-2026-11-11.md; en caliente todo < 20 ms; lo lento es la primera vez: AnimeDLatino 1,5-3,5 s, NovelCool ~1-2 s, Marmota ~1 s. Arreglo: precarga del siguiente y listas en memoria; siguiente capítulo de ~1 s a ~10 ms. Pendiente: lectortmo sin capítulos y zonatmo_to sin páginas). Original: Medir cada fuente (listado, ficha, capítulos, páginas/vídeo), cuellos de botella, antes/después.
- [x] F2.2 (Prefetcher en el VPS, solo red ligera: siguiente capítulo/episodio y listas de lo que se sigue cada 30 min; worker: trabajo «covers» con portadas 240-720 y banners 1080-1920, test de ida y vuelta; metadatos de temporada ya los busca Discover cada 20 s. Sin worker, todo igual que antes). Original: Worker precarga (capítulos de bibliotecas, siguiente cap./ep., portadas/banners, metadatos de temporada),
      prioridad baja, sube a la caché del VPS; sin worker todo igual.
- [x] F2.3 Servidor (LaLiga.kt: hayahora.futbol cada 10 min, partido = 10 o más IPs, /api/bloqueos, error explicado, OUTBOUND_PROXY, /api/relay con tests de autorización), app (ServerRelay: reintento por el relé + aviso único, test con servidor local) y web (aviso en Inicio). Sin probar durante un partido real.
      proxy del VPS (o Tor si el VPS está en España) y aviso claro en vez de error genérico.

**Bloque 3 — contenido**
- [x] F3.1 (AudioTrack en servidores y títulos: lo que dice la web —AnimeAV1 SUB/LAT/DUB, MULTISERVER de Ninja SUB/LAT/ES— o título, dirección y géneros; el doblaje es la misma obra (migración 33); índice con audio (migración 32) y filtro ?audio=; Explorar › Anime › Doblajes; insignias CAST/LAT/DUB EN; audio en ficha y servidores, también en la app. Pocas webs lo marcan por título: Latino saldrá sobre todo de JKAnime y Ninja). Original: Doblajes castellano / latino / inglés: idioma de audio por episodio y fuente (aparte de subtítulos), visible
      en tarjetas y ficha, filtrable; secciones propias.
- [x] F3.2 (AnimeOnlineNinjaSource con tests de sus páginas reales guardadas con navegador; series y películas; servidores con audio. Cloudflare: en el VPS funciona con el FlareSolverr que ya trae docker-compose; NO probado de punta a punta aquí porque Docker no estaba arrancado → probar en el VPS). Original: Fuente animeonline.ninja (anime-castellano = doblaje castellano), tests de páginas reales, punta a punta.
- [x] F3.3 (web: pestañas Todo/Anime/Manga/Manhwa y manhua/Cómics/Novelas en Inicio y secciones, rutas /manhwa y /comics, Manga JP y Manhwa KR/CN/TW separados en filas y explorador, Cómics desde el índice con idioma y orden — probado en el navegador; app: mismas pestañas y filas separadas). Original: Sección Cómics y filtros correctos; pestañas Todo · Anime · Manga · Manhwa y manhua · Cómics · Novelas.

**Bloque 4 — salas, DMs y compartir**
- [x] F4.1 (sesión B) Salas comunes: `rooms.open` + salas fijas de admin (persistent = 2), migración 25 `room_queue`, `shared/…/social/RoomQueue.kt` (reglas puras con tests), WebSocket: queue_add/remove/move/play, skip (más de la mitad de los presentes), ended (con algo en cola el servidor pasa a todos al siguiente, una sola vez); sin cola, «Saltar» hace que el que manda vaya al siguiente episodio. Web: /salas, pestaña «Cola» y «Saltar» en el chat de sala, «+ Cola» en la lista de episodios del reproductor; app: pestaña Salas en Comunidad, cola y votos en la hoja de sala, «+ Siguiente». Interruptor `open_rooms`. Probado: RoomsTest con varios clientes WebSocket; en local por la API; app en el emulador (lista y entrar).
- [x] F4.2 (sesión B) `DirectMessages.kt` + migración 24 + `DmRules` (antispam: 5 sin respuesta, 15 conversaciones nuevas al día, ritmo de ChatGuard, silenciados por admin) + DirectMessagesTest (autorización entre 3 usuarios, invitados fuera, bloquear/silenciar, buscar con comodines literales). Vivo por el mismo SSE de avisos (dm, dm_typing, dm_seen, dm_deleted). Una sola notificación (campana + ntfy) por tanda sin leer. Web: /mensajes (icono con contador en la barra, Perfil «Mensaje», /amigos). App: pestaña Mensajes en Comunidad con SSE propio (`ServerSync.liveEvents`). Interruptor `dms`. Probado de punta a punta: web/API con 2 usuarios y app en el emulador (lista, no leídos, spoiler tapado, «escribiendo…», mensaje en vivo, visto, responder). Original: DMs 1 a 1 en tiempo real: lista, no leídos, campana + ntfy, escribiendo/visto, historial, buscar, borrar los
      míos, bloquear/silenciar; solo texto y tarjetas de obra; antispam; tests de autorización; sin invitados; accesos
      desde perfil, /amigos y icono en la barra.
- [x] F4.3 (sesión B) `ShareCard` en shared (una sola puerta de validación: tipos, ids, portada solo https o /api/img, enlace siempre dentro de la app; obra del índice por clave), columnas card en room_messages y forum_posts, POST /api/rooms/{code}/chat. Web: hoja «Compartir» (ficha, reproductor, lectores manga/novela) → persona, sala o hilo de la obra; `WorkPicker` en mensajes y chat de sala. App: «Compartir» en la ficha, el reproductor y los lectores (botón mínimo en archivos del bloque 6). Buscador de obras en mensajes, chat de sala y editor del foro (web) y en mensajes y sala (app). Portadas siempre por el proxy firmado (la CSP bloqueaba las externas). Si el servidor no conoce la obra, el foro la resuelve en su fuente al compartir. Interruptor `sharing`. Probado: web con Playwright (escritorio + iPhone, `scripts/e2e-social.mjs`, 14 comprobaciones) y app en el emulador (ficha → DM y sala; capítulo desde el lector con escudo; obra elegida con el buscador en un DM). Original: Compartir (ficha, episodio, capítulo) a DM / sala / hilo en dos toques; tarjeta con portada, tipo, idioma,
      ep./cap., escudo anti-spoilers; buscador de obra dentro de chat y editor del foro.
- [x] F4.4 (sesión B) Paridad app: ver F4.1-F4.3 (`ui/social/Messages.kt`, PartyClient/PartyUi con cola). Probado en el emulador: «+ Siguiente» encola el ep. 2 desde la hoja de sala y «Poner ahora» hace que el reproductor pase solo al ep. 2. Detalle menor: en la app, una tarjeta de obra del índice (sin fuente, elegida con el buscador) no se abre al tocarla.
- [x] F4.5 (sesión B) 9 logros: first_dm, chatterbox (300), networker (5 personas), first_share, town_crier (25), everywhere (DM+sala+foro), spoiler_shield (secreto), room_dj (20 en cola), democracy (secreto, 10 saltos por voto). Test en DirectMessagesTest.

**Bloque 5 — calendario**
- [x] F5.1 (sesión C) Causa de «solo el nombre»: /api/calendar devolvía portadas sin proxy y la CSP las bloqueaba (arreglado); además solo salían ~20 animes de las filas de Inicio. Ahora `/api/calendar/airing` (todos los episodios de la semana en 1 petición a AniList, caché 3 h), tira de días + cuadrícula de pósters con hora; ratón (escritorio) o pulsación larga/clic derecho: sinopsis, géneros, nota, comentarios, «Abrir ficha». App: `ui/calendar/` (icono en Inicio), sin servidor va a AniList directo (caché + reintento ante 429). Probado: web con Playwright (escritorio e iPhone) y app en el emulador sin servidor.
- [x] F5.2 (sesión C) `SeasonCalendar.kt`, migración 28 `season_picks`, `/api/calendar/picks` (recomendar y/o comentar, uno por persona y anime, 500 caracteres, 12 cambios/min, invitados no), interruptor `season_social`. Sección «Recomendado por el grupo» y ♥ en cada póster (web y app). Probado en web de punta a punta; en la app solo sin servidor (falta conectar el emulador a un servidor).
- [x] F5.3 (sesión C) `/api/calendar/community`: mejor valorados (con ≥3000 personas detrás) y tendencias de la temporada en AniList, caché 6 h. API de AniList gratuita para uso no comercial, límite 90/min (30 en modo degradado): se usa 4-8 veces al día; se cita «Datos de AniList». MAL descartado (su API pide registro y Jikan no es oficial).

**Bloque 6 — voces, autoscroll, traducción**
- [~] F6.1 (sesión C) Causa: el worker solo ofrecía las voces inglesas de Pocket TTS y la web/app elegían Piper por defecto. Ahora `TtsHandler` usa `--language` (Pocket TTS 4.x, la de la imagen): `lola` (española nativa) y `es_alba`, `es_marius`, `es_jean`, `es_fantine`, `es_george`, `es_vera`, más francés/alemán/portugués/italiano/inglés; sin `--language` siguen las inglesas. Web y app prefieren las voces del worker del idioma si el usuario no eligió otra. Texto para la voz igual en servidor, web y app (`SpeechText.kt` / `speech-text.js`, casos comunes en `speech_text.json`): números, ordinales, %, años en inglés, abreviaturas, pausas y raya de diálogo; troceo en frases (`TtsPlan.chunks`). Volumen igualado con `loudnorm` (−16 LUFS). `scripts/tts-compare.sh` genera `pocket-<voz>-es.wav` de cada voz. Tests: worker `TtsVoicesTest`, shared `SpeechTextTest`, web `speech-text.test.mjs`. SIN probar con el worker real (no tengo acceso): falta que el usuario ejecute el script y elija voz.
- [~] F6.2 (sesión C) Medido con Playwright (`scripts/e2e-autoscroll.mjs`): el modo normal clava 15-800 px/s; el inteligente usaba en secreto la velocidad del normal (de 31 a 900 px/s con el mismo ×1) y no había forma de elegir modo; al final del capítulo se paraba aunque hubiera siguiente. Web: motor común `lib/autoscroll.js` (tests a 30/60/120 fps), modos «Normal» e «Inteligente» separados (en la barra de velocidad y en Ajustes de lectura), espera y pide el siguiente capítulo, rueda/dedo = pausa breve y sigue, modo por páginas pasa página solo, novelas en inteligente = tu velocidad de lectura por párrafo (~8 px/s a 220 ppm). App: `AutoScrollLoop.kt` + `AutoScrollPace` (shared, tests): el bucle moría al tocar la lista (la cancelación del arrastre lo terminaba), no salta en fotogramas largos, espera al siguiente capítulo, inteligente separado del normal con selector en la barra. Web probada con Playwright; app probada en el emulador (Love Leveling, ZonaTMO): avanza en Inteligente, sigue tras arrastrar con el dedo, cambia a Normal ×1 sin pararse.
- [~] F6.3 (sesión C) Causas: la pulsación larga solo iba con el «modo aprendizaje» (apagado por defecto), la hoja solo preguntaba al servidor de casa (503 sin worker) y el traductor de Chrome se quedaba colgado descargando el modelo. Ahora cadena común (web `lib/translate-chain.js`, shared `TranslationChain.kt`): diccionario sin conexión → traductor del navegador / ML Kit en la app (con tiempo máximo) → servidor (worker o, si está apagado, MyMemory para frases cortas: `OnlineTranslator.kt`, interruptor `online_translate`, tope 4500 caracteres/día) → enlace al traductor de Google. Web: seleccionar en novelas → «Traducir» (probado con Playwright en escritorio con traductor del navegador simulado y en iPhone sin nada: traduce vía MyMemory); manga/cómic: mantener pulsado o clic derecho sobre un bocadillo con OCR (sin probar de punta a punta: falta un capítulo con OCR). App probada en el emulador: novela en español (SkyNovels) con la app en inglés, mantener pulsada «articulada» → «articulated» y la frase traducida con ML Kit, sin servidor ni modo aprendizaje. Bocadillos en la app: la pulsación larga va cuando el OCR está activo (Traducir bocadillos, Leer bocadillos o modo aprendizaje); leer todas las páginas siempre gastaba batería y sacaba «No se pudo traducir esta página» sin pedirlo (corregido). Web: bocadillos probados con Playwright (escritorio y iPhone, OCR simulado). Falta: traducción completa de bocadillos sin worker (OCR en el navegador).

**Bloque 7 — aspecto (neobrutalismo moderado + guiños al manga, estructura Netflix/Seanime)**
- [x] 7.0 Capturas de referencias en `docs/referencias/neobrutalismo/` + `docs/referencias/ESTILO.md`.
- [x] 7.1 Tema por defecto nuevo sobre tokens (+ variante clara y suave), contraste AA, display + texto legible.
- [x] 7.2 Tarjetas sin difuminado; vertical = póster, ancha = imagen horizontal o composición; mismo recorte en tamaños;
      placeholder de la casa.
- [x] 7.3 Identidad por tipo (por forma: el usuario pidió blanco y negro + 1 color) (color, insignia, detalle) en todas partes.
- [x] 7.4 Pantalla de carga animada con tiempo máximo; esqueletos en navegaciones internas.
- [x] 7.5 Animaciones (transform/opacity, prefers-reduced-motion).
- [x] 7.6 Capturas antes/después (`docs/capturas/despues/tinta-*.png`) en `docs/capturas/` (móvil y escritorio) y app Android con los mismos tokens.

**Bitácora del pedido** (qué hice, qué publiqué, qué probar):
- 2026-11-11 · **Bloque 6 (sesión C)**, sin release (la publica A). Sin migraciones nuevas; interruptor nuevo `online_translate`. Qué probar: (1) traducción: en una novela selecciona una palabra o una frase → «Traducir»; en el móvil sin servidor de casa debe traducir igual (MyMemory); en manga, mantener pulsado un bocadillo (necesita OCR); en la app, mantener pulsada una palabra o un bocadillo, también sin modo aprendizaje. (2) autoscroll: en manga y novela, ▶ y probar ×0,5 a ×5 en «Normal» e «Inteligente» (botón en la barra de velocidad), dejarlo llegar al final del capítulo (debe seguir con el siguiente), tocar/arrastrar (se pausa y sigue). (3) voces: con el worker actualizado, Ajustes de voz en una novela en español → voces «Lola», «Alba»… del servidor de casa; un texto con «Cap. 12», «1990», «Sr.» debe leerse bien.
- 2026-11-11 · **Bloque 4 cerrado (sesión B)**, sin release (la publica A; lleva migraciones 24 y 25 → pre-release). Qué probar: (1) con dos cuentas, icono de mensajes de la barra → escribir; el otro ve el contador, «escribiendo…», el mensaje al momento y «Visto»; borrar uno tuyo; silenciar y bloquear; buscar. (2) En una ficha, episodio o capítulo, «Compartir» → una persona / tu sala / el hilo; el escudo anti-spoilers tapa la tarjeta hasta tocarla. (3) /salas: crear una sala común, entrar con dos cuentas, añadir episodios con «+ Cola» (lista de episodios del reproductor), reordenar, «Saltar» con los dos, y dejar que acabe un episodio: el siguiente de la cola empieza solo para todos. (4) App: Comunidad › Mensajes y Salas; «Compartir» en la ficha y en el reproductor. Interruptores de admin: dms, open_rooms, sharing.
- 2026-11-11 · **Bloque 5 cerrado (sesión C)**, sin release (la publica A). Migración 28. Qué probar: Calendario en la web (escritorio: pasar el ratón por un póster; móvil: mantener pulsado) → sinopsis, nota, géneros, «Abrir ficha»; «Recomendar o comentar» y verlo en «Recomendado por el grupo» con otro usuario; «Lo que más gusta esta temporada» abajo; en la app, icono de calendario en Inicio (con y sin servidor). Ajustes de admin: interruptor «Temporada en el calendario».
- 2026-11-11 · **Bloque 2 cerrado**: precarga del siguiente capítulo y de listas (siguiente de ~1 s a ~10 ms), portadas en el worker, bloqueos de LaLiga (servidor, app y web). Release **v2.4.0** (normal: sin migraciones). Probar: lista «Qué probar» de docs/releases/v2.4.0.md.
- 2026-11-11 · **Bloque 1 cerrado** (web, servidor y app). App probada en el emulador: Siguiente 354→355 en Bleach (AnimeAV1) y «Ir a la ficha» desde el reproductor. Sin probar en el emulador: sala desde la app y Siguiente a una fuente hermana (necesitan servidor conectado; cubierto por tests de NextEpisode). Release **v2.3.0 (pre-release)** con notas en docs/releases/v2.3.0.md (el flujo de release usa ese archivo y marca pre-release si empieza por <!-- prerelease -->). Qué tiene que probar el usuario: lista «Qué probar» de esas notas.

## Estado anterior
Última actualización: 2026-11-08 (noche). Versión de la app: **2.2.0** (versionCode 28): un APK por procesador (arm64 29 MB) + universal; la release se publica sola al subir la etiqueta v2.1.0.

**PEDIDO 2026-11-08 (noche, tras probar la web) — PRIORIDAD MÁXIMA, dejarlo «de putísima madre». Autonomía total, subir a GitHub, NO release (se prueba en web y luego se migra a la app).**
1. [x] Relacionados mal (TitleMatcher entiende temporadas escritas de cualquier forma y nombres con o sin espacios; metadatos por id de AniList cuando el índice lo sabe; el índice deja cupo de AniList a las fichas). Muestra de 25 animes: de 0 a 18 con relacionadas (algunos bien, otros nada; p. ej. GATE parte 2 / «temporada 2» no muestra nada).
2. [x] Portadas (tamaños 240/360/480/720+ en el proxy; banner y póster grande de Kitsu —ahora kitsu.app— cuando AniList no tiene; al confirmar en el índice y al abrir la ficha). Pendiente si hace falta: SteamGridDB/TheTVDB (piden clave). Portadas: pequeña, mediana, grande y horizontal (banner) para el máximo de obras (AniList, TMDB, MangaDex, Kitsu…; ideas del usuario: SteamGridDB, TheTVDB, Zerochan, Wallhaven).
3. [x] Portadas que no cargan (la tarjeta cogía la portada de una fuente caída; ahora una sana y la misma fuente para portada y cabeceras). Portadas que no cargan en las tarjetas de fuera pero sí dentro de la obra.
4. [x] Quitar los títulos de capítulo censurados (difuminados) junto a «Cap. 194»: ruido.
5. [x] Pulir la web (ficha sin franja negra; barra del lector que cabe en el móvil): zona negra en el pie al bajar mucho dentro de una obra; HUD de herramientas saturado.
6. [x] Fuentes nuevas, todas con tests de páginas reales y probadas de punta a punta: ZonaTMO (zonatmo.to, API JSON), TmoManhwa (declarativa firmada), ManhwaWeb (API), JKAnime (HLS propio + servidores), AnimeDLatino, VerAnimeOnline (Blogger). AnimeAV1 caída. Anime: veranimeonline.co, animedlatino.com, jkanime.net (AnimeAV1 caído). Manga/manhwa: zonatmo.to, manhwaweb.com, tmomanhwa.com. Arreglar las que fallen por culpa nuestra (muchas caídas por bloqueos de LaLiga).
7. [x] Contenido sexual oculto (solo lo sexual, no ecchi; por usuario; también sin etiquetar: AdultHints por título y listas de adultos de cada web, migración 20) por defecto; solo si el usuario lo activa.
8. [x] Dominio nuevo: el canario sigue la redirección de la propia web si la fuente funciona allí; los dominios adivinados quedan como sugerencia para el admin (migraciones 19 y 21) de una fuente que se muda (reutilizar DomainAliases).
9. «Buscar en tus fuentes» solo le salió con JoJo Steel Ball Run: lo de obras ha mejorado mucho.

**ESTADO 2026-11-08 (noche) — fallos de la 2.1.0 y prioridad nº 1**
- [x] PRIORIDAD Nº 1, catálogo por OBRAS: `WorkIndex.kt` (migración 18, rastreo de 4 fuentes a la vez, novedades cada 2 h, confirmación con AniList), API `/api/obras*`, web (Inicio «Recién actualizado», buscador con «Obras» primero, `/obras/:key?lang=` abre la mejor fuente del idioma con `?obra=`, chips de idioma en la ficha) y app (recomendaciones por `/api/obras/anilist`). Medido en local: abrir una obra en ~0,5 s.
- [x] 1 Ajustes: las `.card` seguidas ya no se pegan (app.css global). La app usa listas: no le pasaba.
- [x] 2 Obras lentas: lo resuelve el índice (sin búsqueda en directo entre fuentes).
- [x] 3 Autoscroll: sin OCR, los bocadillos reducidos parecían paneles lisos (rápidos). `ScrollPlanner.look().textLike` y `ocr.js`; la web suaviza la velocidad y su barra de velocidad vuelve a contar.
- [x] 4 Portadas: `HighResCovers` en shared (MangaDex, WordPress, AniList, MAL), usado por el proxy y por `CoverImage` de la app, con vuelta a la miniatura si falla.
- [x] 5 Voz: voces «high» por defecto (es-claude, es-daniela, es-sergio), nunca una voz que no existe (`voiceSettings`), hoja «Voz» con botón en la barra de lectura.
- [x] 7 Chino: MangaDex 中文 activada por defecto. Quien ya guardó su lista la activa en Ajustes › Fuentes.
- [x] 9 Foro: al borrar el hilo te saca; un hilo sin mensajes se borra.
- [x] Nuevo pedido: reloj solo en lectores (quitado del reproductor web y app); anime a pantalla completa en el PC al empezar; episodios con solo el reproductor de la web (Anitaku/upbolt) en `/ver` (servidor, firmado, iframe aislado).
- Fuentes probadas en local con el script de sondeo (listado → ficha → capítulos → páginas, texto o vídeo):
  - OK: MangaDex ES/EN/ZH, Olympus, NovelCool x3, MegaBanana, YSK, SkyNovels, AnimeFLV, Marmota y Anitaku (vía /ver).
  - Flame: caída, su dominio redirige a Discord (el canario la oculta).
  - Asura, LectorTMO, ZonaTMO y AnimeAV1: certificado no fiable desde esta red, también con curl (bloqueo de la operadora en España, Cloudflare). Comprobar desde el VPS en Administración › Salud de las fuentes. Los errores ya se explican en palabras claras.
- [x] 8 Traducción de bocadillos: el servidor perdía la posición de los textos vacíos (traducciones en el bocadillo equivocado) y daba 503 si el worker tardaba más de 20 s; ahora 202 «pendiente» y la web reintenta cada 5 s. Selector «Traducir al» en el lector.
- [x] 6 Salas: «Ver con amigos» en la ficha de cada anime (web). Guía rápida (/guia en la web y en Ajustes de la app) y explicaciones en Repaso, mazos y logros.
- [x] Parar la voz con un toque (web y app, también con las barras ocultas).
- [x] Dominios mudados: Administración › «Webs que se han mudado de dominio» (migración 19, `DomainAliases.kt`). lectortmo.com y zonatmo.com no resuelven en el DNS público: hace falta su dominio nuevo (ponerlo ahí).
- [x] Release 2.2.0 (versionCode 28).
- [x] (2026-11-08, tras probar la 2.2.0) Abrir un anime se quedaba «buscando en tus fuentes»: el índice nunca llegaba al anime porque las 4 fuentes rotas ocupaban los 4 huecos de rastreo (ahora esperan su turno con espera creciente) + POST /api/obras/encontrar por id de AniList o por nombre normalizado (web y app). La búsqueda de animeflv.or.at está rota en su web: el anime entra por el rastreo de su catálogo.
- [x] Portadas de manhwa en biblioteca: `CoverHosts` (CDN aprendidas de las respuestas de cada fuente). Buscador: título exacto primero, tipo antes de cortar, cambiar de categoría filtra sin volver a buscar, fuentes caídas (3 búsquedas distintas) se saltan 10 min. Cada fuente solo para su tipo (manga/manhwa/manhua, cómic, novela, anime).
- [x] Traducción de manga: el worker del usuario probablemente no tiene LibreTranslate (WORKER_TRANSLATION=true); la web usa el traductor de Chrome/Edge si el servidor no puede, y avisa si el capítulo ya está en el idioma de destino. App: botón «Voz» mientras lee.
- [x] F5 en un capítulo = 404 de Apache por los %2F: docs/APACHE.md (AllowEncodedSlashes NoDecode + nocanon). Darle al usuario los comandos.
- **SIGUIENTE**: confirmar con el usuario la salud de las fuentes desde el VPS; si One Piece sigue con «error de fuente», ver qué fuente abre (probablemente lectortmo/zonatmo, dominios muertos); precarga en el servidor de lo que leen (siguiente capítulo, listas de capítulos de la biblioteca); ficha por obra en la app con chips de idioma; más fuentes.
- **SIGUIENTE**: sondear todas las fuentes DESDE EL VPS (en local la red bloquea varias) y poner los dominios nuevos; más fuentes en español e inglés para que se vean más obras; la app conectada debería usar también los dominios mudados (ahora solo el servidor); ficha por obra en la app con chips de idioma (paridad con la web); medir rendimiento.

### PLAN: ÍNDICE DE OBRAS (prioridad nº 1, empezado el 2026-11-08)
**Por qué ahora va lento**: las filas de Inicio vienen de AniList y al pulsar una el servidor busca en directo en todas las fuentes (locate). Si una fuente tarda, la obra se queda cargando 20 s o más. Hay que cambiarlo por un índice construido por adelantado.
1. **Servidor, `WorkIndex.kt` + migración 18**:
   - `index_items`: cada título de cada fuente, con fuente, id, idioma, tipo, títulos, portada, estado, géneros, `work_key` y fechas.
   - `index_state`: progreso por fuente.
   - FTS5 `works_fts`: una fila por obra e idioma con todos sus títulos, para búsquedas al instante.
   - `works.popularity`: para ordenar por popularidad.
2. **Rastreador ligero en el VPS** (solo red, con pausas y memoria acotada):
   - La primera vez recorre el listado completo de cada fuente.
   - Cada 2 h, las páginas de novedades hasta llegar a lo ya visto: capítulos y obras nuevas.
   - Cada título entra en una obra: primero por nombre, al momento (`WorkKey`/`TitleMatcher`); después, en segundo plano y a ~40 por minuto, se confirma con AniList y pasa a `al:ID`. Las fichas provisionales se fusionan.
3. **Idiomas**: una obra se muestra en el idioma de la interfaz (ES/EN; ZH aparte) con sus fuentes de ese idioma, y en la ficha se puede cambiar al otro.
4. **API**:
   - `/api/obras?lang=&type=&sort=updated|popular|az&page=`
   - `/api/obras/buscar?q=` (FTS)
   - `/api/obras/{key}?lang=`: ficha, idiomas, fuentes y capítulos unidos
   - `/api/obras/anilist/{id}`: abrir al instante las filas de AniList
   - Admin: estado del índice y reindexar. Interruptor `work_index`.
5. **Clientes**: web con ficha por obra `/obra/:key`, Inicio y búsqueda contra el índice (y búsqueda en directo solo como «buscar también en las fuentes»). La app, conectada, usa lo mismo; sin servidor sigue como ahora.

**PEDIDO DEL USUARIO EL 2026-11-08** (lo probó y se quejó): (1) al pulsar una obra seguía saliendo «elige fuente»: debe abrir UNA obra con todas sus fuentes, en app Y web; (2) lentísima: portadas ~20 s, abrir obra ~15 s; (3) no había botón de saltar opening/ending (solo +85 s). Con servidor sincronizado la app puede usarlo para ir más rápida, pero sin servidor también tiene que ir más rápida. NO quiere probar nada hasta que esté TODO; al final darle los comandos exactos de instalación para su VPS (Ubuntu, sin Tailscale), el portátil worker y el NAS con Jellyfin (que ahora no se puede tocar). Los datos de sus máquinas están en la conversación, NO en el repo, que es público.
- [x] Obras sin elegir fuente. App: `locateAndOpen` abre con la primera fuente que es la misma obra, y la ficha junta los capítulos de las fuentes hermanas («De …», caché `located.json`). Web: `/api/locate/first` y `/api/works/whole`, `open.svelte.js` y `Details.svelte`. App con servidor: pregunta a la vez a sus fuentes y al servidor, y carga las hermanas que el servidor ya conoce.
- [x] Saltar opening/ending con tiempos reales de AniSkip (`shared/…/work/AniSkip.kt`, con el MAL id de los metadatos), botón flotante y «Saltar solo» en app y web; el servidor usa las huellas del worker y, si no, AniSkip.
- [x] Velocidad medida en el emulador (2026-11-08): arranque en frío **1,4 s en release** (6,3 s en debug por StrictMode/sin R8: medir siempre en release); portadas desde la caché de disco a los 3 s; abrir obra ~3 s en vez de ~8 s + elegir fuente.
- [x] Una entrada por obra en biblioteca e historial (app y web; `WorkGrouper.firstOfEachWork`).
**ORDEN DE PRIORIDAD APROBADO POR EL USUARIO (2026-11-08)**: 1) diccionario sin conexión [x HECHO: `shared/…/learning/Dictionary.kt`, worker `DictionaryHandler` (kaikki.org → SQLite), servidor `Dictionaries.kt` (/api/dictionary/{lang}[/lookup|/file]), web `WordSheet.svelte`, app `OfflineDictionary` + `ui/learning/WordSheet.kt` con pulsación larga en novelas, 9 idiomas]; 2) repaso y mazos compartidos y ranking [x HECHO: app `ReviewDialog` en Ajustes › Aprender idiomas; migración 16 decks, /api/words/decks y /ranking, web en Review.svelte; FALTA mazos/ranking dentro de la app]; 3) OCR + traducción en la app con ML Kit [x HECHO: `DeviceOcr` + `BubbleOverlay` (traducción encima del bocadillo), `BubbleSpeaker` (leer bocadillos en voz alta en orden), pulsación larga en bocadillo con el modo aprendizaje, `ScrollProfiles` (autoscroll inteligente por franjas, plan B sin OCR); y aprende la velocidad de lectura (`AutoScroller`, `readingWpm`)]; 4) voces en el dispositivo [x APP HECHA: `data/speech/DeviceVoices.kt` (sherpa-onnx 1.13.8 —la 2.x no fonetiza—, 14 voces Piper, Ajustes › Voces naturales en el móvil con Probar; el lector de novelas las usa si el servidor no tiene voces del idioma; probado en el emulador: genera voz real); WEB HECHA: Kokoro en el navegador (`web/src/lib/kokoro.worker.js`, servidor `KokoroModels.kt` con lista blanca y SHA-256, COEP para hilos; más lento que tiempo real, por eso es opcional); FALTA que `BubbleSpeaker` (app) use las voces del móvil]; 5) salas desde el móvil [x HECHO y probado contra el servidor local con un PC simulado: `data/social/PartyClient.kt` (WebSocket, mismas reglas que la web), `ui/player/PartyUi.kt` (RoomSync, botón y hoja de sala), `RoomLobbyScreen.kt` (unirse con código o enlace desde Inicio), `container.activeRoom` (Ver con amigos en la ficha; se borra al cerrar el reproductor)]; 6) social nativo [x HECHO: `ui/social/CommunityScreen.kt` (actividad, amigos y perfiles, foro con respuestas, mi perfil); los avisos ya estaban (campana)]; 7) [x HECHO] voces por personaje (`VoiceSamples.kt`, migración 17, clonación con Pocket TTS en el worker) + pregeneración nocturna (`NightlyVoices.kt`, `SpeechPlan` = `dialogue.js`, comprobado con speech_parity.json); 8) [x] TMDB (`TmdbClient`, TMDB_API_KEY opcional, fondos panorámicos para anime) — fuentes a JSON: DECIDIDO no migrar ahora (el motor declarativo solo lee HTML y casi todas las fuentes Kotlin usan APIs JSON; se migran cuando se rompan); 9) [x HECHO] Jellyfin en la app (`data/sync/ServerHostedSource.kt`: el Jellyfin del servidor como fuente mientras está conectada; la cookie solo va a las direcciones del propio servidor); 10) release (pedir permiso) e instrucciones de instalación. El usuario y sus amigos NO lo usarán hasta que esté todo «fino fino». Sin probar aún en el emulador (hacerlo en la ronda final): modo aprendizaje de la app (pulsación larga en novela, repaso) y traducción de bocadillos con ML Kit (el emulador necesita imagen con Google Play).
**Siguiente paso**: TODA la lista está hecha y la release v2.1.0 está PUBLICADA (2026-11-08; CI e imágenes Docker en verde: se compilan en gradle:9.6.0-jdk25-noble para no depender de descargas de GitHub). Dar al usuario los comandos de instalación (VPS con Caddy y un nombre sslip.io para HTTPS sin dominio; worker con install-worker.sh por HTTPS; Tailscale en el VPS solo para Jellyfin). Después, esperar sus pruebas. Pendientes menores: BubbleSpeaker con las voces del móvil; migrar fuentes a JSON cuando se rompan; probar Jellyfin con el NAS real.
**Bug importante ya arreglado**: en Android, una regex con `}` sin escapar falla (ICU) aunque en el JVM vaya. `AndroidRegexTest` lo vigila.

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
- (sesión C, F6.1) En el portátil worker: `pip install -U pocket-tts` (o la imagen `yomukai-worker-full` nueva) y `bash scripts/tts-compare.sh`; escuchar `pocket-lola-es.wav`, `pocket-alba-es.wav`… y decir qué voz española prefiere (la primera de la lista sale por defecto; se reordena en `TtsHandler.POCKET_MULTI_VOICES`). Pasar también el `informe.md` (tiempos y RAM) para ajustar `WORKER_CONCURRENCY`.
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

| Portadas (V1, 2026-11-11, local): 40 a la vez como Inicio | — | — | — | antes 7,1 s todas (mediana 5,9 s) → después 0,65 s (mediana 84 ms), 2.ª vez 0,28 s | — | — |

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
