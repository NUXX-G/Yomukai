# Yomukai 読む会

**Manga · Manhwa · Novelas ligeras · Anime** — un cliente Android local-first, sin cuentas ni seguimiento.

[Español](#español) · [English](#english)

> ⚠️ **Aviso legal / Legal notice** — Yomukai es una aplicación cliente de código abierto. **No aloja, almacena ni distribuye contenido protegido por derechos de autor.** Todo el contenido se obtiene en tiempo real de sitios web de terceros públicamente accesibles, que son los únicos responsables de él. Ver [Aviso legal](#aviso-legal--legal-disclaimer).

---

## Español

### Funciones

| Área | Qué incluye |
|---|---|
| **Explorar y buscar** | Novedades por fuente, filtros por tipo (manga, manhwa, novela, anime) e idioma, búsqueda simultánea en todas las fuentes activas, catálogo por fuente en cuadrícula o lista con paginación. |
| **Lector de manga** | Modo vertical (webtoon) y por páginas (izq.→der. / der.→izq.), zoom con pellizco y doble toque, toque en los bordes para pasar página, barra de páginas, brillo, pantalla siempre encendida, modo inmersivo, reanudación en la última página. |
| **Lector de novelas** | Temas Oscuro / OLED / Sepia / Claro, fuente, tamaño, interlineado, márgenes, texto justificado y progreso guardado por capítulo. |
| **Anime** | Lista de episodios, selector de servidor, reproducción en la app (ExoPlayer/Media3) de flujos HLS y MP4 directos; los servidores no compatibles se abren en el navegador. |
| **Biblioteca** | Estados de lectura (Leyendo, Pendiente, Completado, En pausa, Abandonado), **categorías propias**, historial y gestor de descargas para leer sin conexión. |
| **Actualizaciones en segundo plano** | Un *worker* de WorkManager comprueba periódicamente los títulos de la biblioteca y envía una **notificación** cuando hay capítulos o episodios nuevos (frecuencia configurable, opción solo Wi-Fi). |
| **Navegador con captura** | WebView integrado: pega cualquier enlace y Yomukai detecta los **flujos de vídeo** (m3u8/mp4) que carga la página y las **imágenes del capítulo**, para reproducirlos o leerlos en sus visores nativos. |
| **Red** | Limitación de peticiones, reintentos, cookies compartidas con WebView para superar desafíos de Cloudflare, **DNS sobre HTTPS** y **proxy HTTP/SOCKS5** (p. ej. Orbot/Tor) para sitios bloqueados por el operador. |
| **Catálogo por fuente** | Desde Explorar entras en cualquier fuente y ves su catálogo completo con **scroll infinito** y **filtros propios de cada web** (orden, estado, tipo, género/editorial, idioma de capítulos, según lo que soporte cada una). |
| **Búsqueda inteligente** | Insensible a mayúsculas, tildes y puntuación; tolera erratas y coincidencias parciales; busca en títulos alternativos; ordena por relevancia, **agrupa duplicados entre fuentes**, con historial y sugerencias. |
| **Lectura continua** | En manga vertical, cómic y novela el capítulo siguiente se añade solo al final, con separador «Capítulo X»; el progreso y el historial siguen al capítulo visible. **Índice de capítulos** con búsqueda, **marcadores**, auto-scroll, recorte de bordes, filtros de color, bloqueo de orientación; en novelas **lectura en voz alta** y búsqueda en el capítulo. |
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
| Royal Road | Novela | EN | HTML |
| SkyNovels | Novela | ES | API JSON |
| Marmota Comics | Cómic | ES | HTML (Madara) + AJAX |
| LectorTMO | Manga | ES | HTML (tema propio) |
| TuNovelaLigera | Novela | ES | HTML (Madara) + AJAX |
| AnimeAV1 | Anime | ES | Datos SvelteKit + extractores |
| LectorManga, SurtrScans, ReadAllComics | — | — | *Experimentales / sin implementar* (sin respuesta o protegidas por Cloudflare; ReadAllComics pendiente de captura vía Tor). AnimeFLV se eliminó: su dominio original dejó de funcionar. |

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
│   │   ├── novel/    Royal Road, SkyNovels, TuNovelaLigera
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

---

## Aviso legal / Legal disclaimer

**ES —** Yomukai es un proyecto de código abierto con fines educativos. Es únicamente una aplicación cliente: **no aloja, sube, almacena ni distribuye ningún contenido protegido por derechos de autor** (manga, novelas, imágenes ni vídeos). Todo el contenido mostrado procede en tiempo real de sitios web de terceros públicamente accesibles, que son los únicos responsables de él. Los autores de Yomukai no tienen relación con dichos sitios. Cada usuario es responsable de cumplir las leyes de su país y los términos de los sitios que visita. Apoya a los autores comprando las obras oficiales cuando estén disponibles. Si eres titular de derechos y tienes una consulta, abre un *issue*.

**EN —** Yomukai is an open-source project for educational purposes. It is solely a client application: **it does not host, upload, store or distribute any copyrighted content** (manga, novels, images or videos). All content shown is fetched in real time from publicly accessible third-party websites, which are solely responsible for it. Yomukai's authors are not affiliated with those websites. Users are responsible for complying with the laws of their country and the terms of the sites they visit. Please support creators by buying official releases when available. Rights holders with concerns may open an issue.

## Licencia / License

[MIT](LICENSE) © 2026 Nelson Filipe Fardilha Karlsson
