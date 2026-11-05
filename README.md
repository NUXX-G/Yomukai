# Yomukai 読む会

**Manga · Manhwa · Novelas ligeras · Anime** — un cliente Android local-first, sin cuentas ni seguimiento.

[Español](#español) · [English](#english)

> ⚠️ **Aviso legal / Legal notice** — Yomukai es una aplicación cliente de código abierto. **No aloja, almacena ni distribuye contenido protegido por derechos de autor.** Todo el contenido se obtiene en tiempo real de sitios web de terceros públicamente accesibles, que son los únicos responsables de él. Ver [Aviso legal](#aviso-legal--legal-disclaimer).

---

## Español

### Funciones

| Área | Qué incluye |
|---|---|
| **Inicio y búsqueda** | Novedades por fuente, «Para ti» y botón de aleatorio en la cabecera, filtros por tipo (manga, manhwa, novela, anime) e idioma, búsqueda simultánea en todas las fuentes activas, catálogo por fuente en cuadrícula o lista con paginación. |
| **Lector de manga** | Modo vertical (webtoon) y por páginas (izq.→der. / der.→izq.), zoom con pellizco y doble toque, toque en los bordes para pasar página, barra de páginas, brillo, pantalla siempre encendida, modo inmersivo, reanudación en la última página. |
| **Lector de novelas** | Temas Oscuro / OLED / Sepia / Claro, fuente, tamaño, interlineado, márgenes, texto justificado y progreso guardado por capítulo. |
| **Anime** | Lista de episodios, selector de servidor, reproducción en la app (ExoPlayer/Media3) de flujos HLS y MP4 directos; los servidores no compatibles se abren en el navegador. |
| **Biblioteca** | Estados de lectura (Leyendo, Pendiente, Completado, En pausa, Abandonado), **categorías propias**, historial y gestor de descargas para leer sin conexión. |
| **Actualizaciones en segundo plano** | Un *worker* de WorkManager comprueba periódicamente los títulos de la biblioteca y envía una **notificación** cuando hay capítulos o episodios nuevos (frecuencia configurable, opción solo Wi-Fi). |
| **Navegador con captura** | WebView integrado con atrás/adelante, favoritos, historial, búsqueda en la página y versión de escritorio. Detecta los **flujos de vídeo** (m3u8/mp4) que carga la página y las **imágenes del capítulo**, para reproducirlos o leerlos en sus visores nativos. |
| **Red** | Limitación de peticiones, reintentos, cookies compartidas con WebView para superar desafíos de Cloudflare, **DNS sobre HTTPS** y **proxy HTTP/SOCKS5** (p. ej. Orbot/Tor) para sitios bloqueados por el operador. |
| **Catálogo por fuente** | Desde Inicio entras en cualquier fuente y ves su catálogo completo con **scroll infinito** y **filtros propios de cada web** (orden, estado, tipo, género/editorial, idioma de capítulos, según lo que soporte cada una). |
| **Búsqueda inteligente** | Insensible a mayúsculas, tildes y puntuación; tolera erratas y coincidencias parciales; busca en títulos alternativos; ordena por relevancia, **agrupa duplicados entre fuentes**, con historial y sugerencias. |
| **Lectura continua** | En manga vertical, cómic y novela el capítulo siguiente se añade solo al final, con separador «Capítulo X»; el progreso y el historial siguen al capítulo visible. **Índice de capítulos** con búsqueda, **marcadores**, auto-scroll, recorte de bordes, filtros de color, bloqueo de orientación; en novelas **lectura en voz alta** con voces naturales (Piper, en tu servidor) o del sistema, a elegir, y voces por personaje (narrador, mujeres, hombres), y búsqueda en el capítulo. |
| **Similares, relacionadas y otras fuentes** | En cada ficha, precuelas, secuelas y adaptaciones en cualquier formato, títulos parecidos (recomendaciones de AniList) y el mismo título en tus otras fuentes con cuántos capítulos tiene cada una, avisando cuando no se puede confirmar que sea la misma obra. |
| **Descubrir** | En Inicio: recomendaciones según tu biblioteca e historial, y un título aleatorio con tipo y género opcionales. |
| **Ficha inteligente** | Color tomado de la portada, nota de AniList y MyAnimeList (Kitsu de respaldo), géneros unificados en español (o deducidos de la sinopsis), aviso de saltos o desorden en los capítulos y de posible abandono. |
| **Reproductor limpio** | Solo un reloj hasta que tocas la pantalla; la pantalla no se apaga y pausa la música de otras apps. Leer no toca tu música. |
| **Géneros excluidos** | Oculta cualquier género en todas las fuentes o solo en algunas. |
| **Carpeta pública** | Cada descarga se copia a `Download/Yomukai`, por tipo, serie y temporada: MP4, CBZ o imágenes, EPUB o TXT, con `tvshow.nfo` y portada. |
| **Reproductor** | Siguiente episodio automático, índice de episodios, velocidad 0,5–2x, salto de +85 s, imagen en imagen y posición recordada por episodio. |
| **Descargas seguras** | Manga, novelas y **anime** (HLS con Media3 y MP4 con reanudación). «Descargar todo», no leídos o un rango. Solo HTTPS, comprobación de tipo y tamaño, almacenamiento privado, límite de descargas simultáneas y solo Wi-Fi. |
| **Tor** | Tor integrado (Guardian Project `tor-android`) u Orbot: fuentes, descargas, vídeo y navegador por Tor, con indicador «Conectado a Tor», bloqueo en vez de fuga mientras no hay conexión y **Tor activable por fuente** (Cloudflare suele bloquear Tor). Buscador del navegador configurable (por defecto DuckDuckGo .onion). |
| **Diagnóstico** | Ajustes › Diagnóstico de fuentes prueba novedades, búsqueda, detalle, capítulos y lectura/reproducción de cada fuente. |

### Fuentes

| Fuente | Tipo | Idioma | Estado |
|---|---|---|---|
| MangaDex | Manga/Manhwa/Manhua | Multi | API oficial |
| Olympus Scans | Manhwa | ES | API JSON del sitio |
| Asura Scans | Manhwa | EN | API JSON del sitio |
| Flame Comics | Manhwa | EN | Datos Next.js |
| SkyNovels | Novela | ES | API JSON |
| Marmota Comics | Cómic | ES | HTML (Madara) + AJAX |
| MegaBanana | Cómic | ES | API JSON propia (WordPress) |
| LectorTMO | Manga | ES | HTML (tema propio) |
| ZonaTMO | Manga/Manhwa/Manhua | ES | HTML |
| NovelCool (ES), NovelCool (EN) | Manga/Manhwa/Manhua | ES, EN | HTML |
| NovelCool Novels (EN) | Novela | EN | HTML |
| YSK Comics | Cómic | EN | API JSON |
| Anitaku | Anime | EN | HTML + extractores |
| AnimeAV1 | Anime | ES | Datos SvelteKit + extractores |
| AnimeFLV | Anime | ES | HTML + extractores |
| ReadAllComics | — | — | *Sin implementar*: pendiente de captura vía Tor. |

Los sitios web cambian con frecuencia; una fuente puede dejar de funcionar sin previo aviso.

> **Tor y Cloudflare:** muchos sitios protegidos por Cloudflare bloquean las salidas de Tor. Desactiva «Usar Tor» en esas fuentes (Ajustes › Tor) y comprueba cada una en Ajustes › Diagnóstico de fuentes.

### Compilar

Requisitos: Android Studio (JDK 17+ incluido) y el SDK de Android 37.

```bash
git clone https://github.com/NUXX-G/Yomukai.git
cd Yomukai
./gradlew assembleDebug          # APK en app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # pruebas unitarias de los parsers
```

Nota de MyAnimeList (opcional): crea una Client ID de tipo *android* en <https://myanimelist.net/apiconfig> y añádela a `local.properties` como `mal.clientId=...` (o en la variable de entorno `MAL_CLIENT_ID`). Sin ella, la app lee la nota pública de MAL a través de Jikan.

Para probar todas las fuentes contra las webs reales en un dispositivo conectado:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e live true -e class com.nelson.yomukai.data.LiveSourcesTest com.nelson.yomukai.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s YomukaiLive:I
```

### Versión web (PC, iPhone, servidor propio)

La misma app en el navegador: inicio, búsqueda, biblioteca, lectores de manga y novela, reproductor de anime, notas y recomendaciones. Se instala como app (PWA) en iPhone y Android desde «Añadir a pantalla de inicio».

**Instalar con una línea** (necesita Docker con Compose):

```bash
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.sh | bash
```

```powershell
# Windows (PowerShell)
irm https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.ps1 | iex
```

El instalador crea `~/yomukai`, genera una contraseña de administrador, arranca Yomukai y FlareSolverr (para las webs con Cloudflare) y te enseña la dirección y el usuario. La primera vez compila la imagen y tarda unos minutos. Para actualizar, vuelve a ejecutar la misma línea: tus datos se conservan en el volumen `yomukai-data`.

- **Cuentas:** entra como `admin` y crea las cuentas de tus amigos en Ajustes › Administración. La sesión dura hasta que se cierre.
- **Invitados:** «Probar sin cuenta» no pide contraseña: sirve para echar un vistazo (por ejemplo, desde un portfolio). Tienen límites más bajos, no pueden exportar, importar ni sincronizar, descargan como mucho 3 capítulos de una vez, y la cuenta y sus datos se borran al cabo de un día.
- **Protección contra abusos:** una cuenta que pide a las fuentes muchísimo más de lo que puede leer una persona (más de 1500 peticiones en 10 minutos; 300 los invitados) queda suspendida hasta que el administrador la reactiva en Ajustes › Administración. El administrador nunca se suspende.
- **Exportar e importar:** Ajustes › Tus datos descarga un JSON con biblioteca, categorías, progreso, historial, marcadores y ajustes, y lo importa en cualquier otra cuenta o servidor (fusionar o reemplazar).
- **Descargas:** desde la ficha (⋮ › Descargar en este dispositivo) los capítulos se guardan en el móvil o el PC de quien descarga, no en el servidor: cómics en CBZ (varios capítulos en un ZIP), novelas en EPUB y episodios en MP4.
- **Sincronizar la app de Android (opcional):** en la app, Ajustes › Sincronizar con servidor, con la dirección de la web y tu usuario. Biblioteca, estados, categorías, progreso, historial, marcadores, fuentes y géneros excluidos quedan iguales en la web y en el móvil; gana el cambio más reciente. La app solo se conecta por HTTPS (en Android 17 pide permiso si el servidor está en tu red de casa). Las cuentas de invitado no se sincronizan.

Opciones en `~/yomukai/.env` (aplícalas con `docker compose up -d`):

| Variable | Por defecto | Qué hace |
|---|---|---|
| `PORT` | `8080` | Puerto en el que escucha |
| `ADMIN_USER` / `ADMIN_PASSWORD` | `admin` / generada | Cuenta de administrador inicial |
| `GUESTS_ENABLED` | `true` | Permite entrar como invitado |
| `GUEST_DAYS` | `1` | Días hasta borrar a un invitado |
| `GUEST_RATE` / `GUEST_STREAMS` | `120` / `2` | Peticiones por minuto y vídeos a la vez por invitado |
| `ABUSE_LIMIT` / `GUEST_ABUSE_LIMIT` | `1500` / `300` | Peticiones a las fuentes en 10 minutos antes de suspender la cuenta |
| `MAL_CLIENT_ID` | vacío | Client ID de MyAnimeList (opcional) |
| `TRUST_PROXY` | `false` | Ponlo a `true` solo si hay un proxy inverso delante (nginx, Caddy) |
| `BACKUP_DIR` | `/data/backups` | Carpeta de las copias nocturnas de la base de datos |
| `BACKUP_HOUR` / `BACKUP_KEEP_DAYS` | `4` / `14` | Hora de la copia (zona `TZ` del contenedor) y días que se guardan |
| `BACKUP_COPY_DIR` | vacío | Segunda carpeta (NAS o worker montado por Tailscale) donde copiar cada copia cuando esté accesible |
| `BACKUPS_ENABLED` | `true` | `false` desactiva las copias automáticas |
| `YOMUKAI_MEMORY` / `YOMUKAI_CPUS` | `1g` / `1.0` | Límites del contenedor en el VPS (ver [docs/VPS.md](docs/VPS.md)) |
| `IMAGE_CACHE_MB` / `RESULT_CACHE_MB` | `2048` / `2048` | Disco para imágenes y para resultados del worker (se borra lo menos usado) |
| `BANDWIDTH_CAP_GB` | `0` | Tope mensual de vídeo que pasa por el VPS (0 = solo medir); avisos al 80 y 100 % |
| `DISCORD_WEBHOOK_URL` / `NTFY_URL` / `NTFY_TOKEN` | vacío | Avisos al admin (worker apagado, tope de tráfico, fuentes rotas) |

Detrás de nginx o Caddy con HTTPS, apunta el proxy a `http://127.0.0.1:8080`, pon `TRUST_PROXY=true` y desactiva el buffering para que la búsqueda en vivo y el vídeo fluyan (`proxy_buffering off;` en nginx).

La forma más sencilla de tener HTTPS es [Caddy](https://caddyserver.com), que pide y renueva el certificado solo. Con un dominio que apunte a tu VPS, este `Caddyfile` basta:

```
yomukai.tudominio.com {
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
    }
}
```

### Servidor de casa (worker)

Lo pesado (voces de calidad, OCR, subtítulos, huellas de audio, descargas a Jellyfin) no se hace en el VPS sino en un **worker**: un programa que corre en tu servidor de casa (o en un portátil con GPU), se conecta él al VPS y va pidiendo trabajos. Crea un worker en Ajustes › Administración › Workers y usa su token:

```bash
curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install-worker.sh | \n  YOMUKAI_URL=https://yomukai.tudominio.com WORKER_TOKEN=ykw_... bash
```

Si el worker está apagado, esos trabajos esperan en la cola y la web y la app lo indican. Guía completa (límites de memoria, swap, Tailscale): [docs/VPS.md](docs/VPS.md).

### Copias de seguridad

Cada noche se hace una copia en caliente de la base de datos (`yomukai-AAAAMMDD-HHMMSS.db.gz`, sin parar el servidor) y se borran las de más de 14 días (la más reciente nunca se borra). Si `BACKUP_COPY_DIR` apunta a una carpeta del NAS montada, cada copia se replica allí; si la carpeta no está, se reintenta cada hora.

```bash
cd ~/yomukai
docker compose exec yomukai /app/bin/server backup          # copia ahora
docker compose exec yomukai /app/bin/server backups         # lista las copias
docker compose stop yomukai                                   # para restaurar, el servidor tiene que estar parado
docker compose run --rm --no-deps yomukai /app/bin/server restore latest   # o el nombre de una copia
docker compose start yomukai
```

La restauración comprueba la copia antes de usarla y deja la base anterior al lado (`yomukai.db.antes-de-restaurar-…`), así que se puede deshacer.

Para desarrollar sin Docker: `./gradlew :server:installDist` (con `YOMUKAI_SERVER_ONLY=true` no hace falta el SDK de Android), `cd web && npm install && npm run dev`, y arranca `server/build/install/server/bin/server` con `PORT=8099`.

---

## English

### Features

| Area | What's included |
|---|---|
| **Browse & search** | Latest updates per source, type (manga, manhwa, novel, anime) and language filters, simultaneous search across all enabled sources, per-source catalogue in grid or list with paging. |
| **Manga reader** | Vertical (webtoon) and paged (LTR / RTL) modes, pinch & double-tap zoom, tap-to-turn edges, page slider, brightness, keep-screen-on, immersive mode, resume at the last page. |
| **Novel reader** | Dark / OLED / Sepia / Light themes, font, size, line height, margins, justified text, per-chapter progress. |
| **Anime** | Episode lists, server picker, in-app playback (ExoPlayer/Media3) of direct HLS and MP4 streams; unsupported hosts open in the browser. |
| **Library** | Reading statuses (Reading, Plan to read, Completed, On hold, Dropped), **custom categories**, history and an offline download manager. |
| **Background updates** | A WorkManager worker periodically checks library titles and posts a **notification** when new chapters/episodes appear (configurable interval, Wi-Fi only option). |
| **Capture browser** | Built-in WebView: paste any link and Yomukai detects the **video streams** (m3u8/mp4) the page loads and the **chapter images**, then plays/reads them in its native viewers. |
| **Networking** | Per-host rate limiting, retries, cookies shared with WebView to pass Cloudflare challenges, **DNS-over-HTTPS** and an **HTTP/SOCKS5 proxy** (e.g. Orbot/Tor) for ISP-blocked sites. |
| **Per-source catalogue** | From Browse open any source and scroll its full catalogue with **infinite scroll** and **per-site filters** (sort, status, type, genre/publisher, chapter language — whatever each site supports). |
| **Smart search** | Case-, accent- and punctuation-insensitive; typo tolerant with partial matches; searches alternative titles; ranked by relevance, **duplicates grouped across sources**, with history and suggestions. |
| **Continuous reading** | In vertical manga, comics and novels the next chapter is appended automatically with a "Chapter X" separator; progress and history follow the visible chapter. Searchable **chapter index**, **bookmarks**, auto-scroll, border crop, colour filters, orientation lock; novels add **text-to-speech** and in-chapter search. |
| **Player** | Auto-play next episode, episode index, 0.5–2x speed, +85 s skip, picture-in-picture and per-episode resume. |
| **Safe downloads** | Manga, novels and **anime** (HLS via Media3, resumable MP4). "Download all", unread or a range. HTTPS only, content-type/size checks, private storage, concurrency limit and Wi-Fi-only. |
| **Tor** | Embedded Tor (Guardian Project `tor-android`) or Orbot: sources, downloads, video and the browser over Tor, with a "Connected to Tor" indicator, fail-closed while disconnected and **per-source Tor toggle** (Cloudflare often blocks Tor). Configurable browser start page/search (default DuckDuckGo .onion). |
| **Diagnostics** | Settings › Source diagnostics tests latest, search, details, chapters and reading/playback of every source. |

### Tech stack

- **Kotlin** 2.2 · **Coroutines / Flow**
- **Jetpack Compose** + **Material 3**, Navigation Compose
- **Room** (KSP) with exported schemas and migrations
- **WorkManager** for background update checks
- **Coil 3** for images (shares the app's OkHttp client)
- **Media3 / ExoPlayer** (HLS + progressive, offline downloads) for video
- **tor-android** (Guardian Project) and **androidx.webkit** ProxyController for Tor routing
- **OkHttp** (+ DNS-over-HTTPS) for networking — Ktor is not used
- **Jsoup** and **Gson** for scraping and JSON parsing

### Architecture

Single-module app with a manual DI container (`AppContainer`) and MVVM:

```
app/src/main/java/com/nelson/yomukai/
├── data/
│   ├── network/      NetworkHelper: OkHttp stack, anti-bot detection, DoH, proxy, WebView cookie bridge
│   ├── source/       CatalogueSource / MangaSource / NovelSource / AnimeSource contracts
│   │   ├── manga/    MangaDex, Olympus, Asura, Flame, LectorTMO, …
│   │   ├── novel/    SkyNovels (NovelCool Novels vive en manga/NovelCoolSource.kt)
│   │   └── anime/    AnimeAV1, VideoExtractors (ok.ru, MP4Upload, YourUpload, HLS)
│   ├── local/        Room entities, DAOs, migrations
│   ├── repository/   LibraryRepository (single source of truth), ContentRepository (offline first)
│   ├── download/     Persistent download queue
│   ├── updates/      LibraryUpdateWorker (WorkManager + notifications)
│   └── prefs/        Settings exposed as StateFlow
├── domain/model/     MediaItem, Chapter, VideoSource, …
└── ui/               Compose screens + ViewModels (home, search, browse, details,
                      reader, player, library, settings, web capture)
```

- **Single source of truth:** screens observe Room through `Flow`; network refreshes write into Room, so cached data shows instantly and works offline.
- **Sources** are small classes implementing one interface each; parsers are pure functions tested against real captured pages (`app/src/test/resources`).
- **Downloads** and **update checks** survive process death (state in Room / WorkManager).

### Build

Requirements: Android Studio (bundled JDK 17+) and Android SDK 37.

```bash
git clone https://github.com/NUXX-G/Yomukai.git
cd Yomukai
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # parser unit tests
```

### Web version (self-hosted)

The same app in the browser, installable as a PWA on iPhone and Android. One line with Docker Compose:

```bash
curl -fsSL https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.sh | bash      # Linux / macOS
irm https://raw.githubusercontent.com/NUXX-G/Yomukai/main/install.ps1 | iex             # Windows PowerShell
```

It creates `~/yomukai`, generates an admin password, starts Yomukai plus FlareSolverr and prints the address. Run it again to update. Chapters and episodes can be downloaded to the viewer's own device (CBZ, EPUB, MP4; nothing is stored on the server), and the Android app can optionally sync its library, progress, history, bookmarks and settings with an account on the server (HTTPS only). Create accounts for friends in Settings › Administration; guests need no password, have lower limits and are deleted after one day; accounts that hammer the sources are suspended until an admin reactivates them; data can be exported and imported as JSON. Options live in `~/yomukai/.env` (see the table above). Behind a reverse proxy, set `TRUST_PROXY=true` and disable response buffering.

The backend is a Ktor server (`server/`) reusing the app's sources from the pure-Kotlin `shared/` module; the frontend is Svelte 5 + Vite (`web/`). User data is stored in SQLite, and images and video go through a signed proxy with a disk cache.

---

## Aviso legal / Legal disclaimer

**ES —** Yomukai es un proyecto de código abierto con fines educativos. Es únicamente una aplicación cliente: **no aloja, sube, almacena ni distribuye ningún contenido protegido por derechos de autor** (manga, novelas, imágenes ni vídeos). Todo el contenido mostrado procede en tiempo real de sitios web de terceros públicamente accesibles, que son los únicos responsables de él. Los autores de Yomukai no tienen relación con dichos sitios. Cada usuario es responsable de cumplir las leyes de su país y los términos de los sitios que visita. Apoya a los autores comprando las obras oficiales cuando estén disponibles. Si eres titular de derechos y tienes una consulta, abre un *issue*.

**EN —** Yomukai is an open-source project for educational purposes. It is solely a client application: **it does not host, upload, store or distribute any copyrighted content** (manga, novels, images or videos). All content shown is fetched in real time from publicly accessible third-party websites, which are solely responsible for it. Yomukai's authors are not affiliated with those websites. Users are responsible for complying with the laws of their country and the terms of the sites they visit. Please support creators by buying official releases when available. Rights holders with concerns may open an issue.

## Licencia / License

[MIT](LICENSE) © 2026 Nelson Filipe Fardilha Karlsson
