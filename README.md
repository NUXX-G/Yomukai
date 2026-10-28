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

### Fuentes

| Fuente | Tipo | Idioma | Estado |
|---|---|---|---|
| MangaDex | Manga/Manhwa/Manhua | Multi | API oficial |
| Olympus Scans | Manhwa | ES | API JSON del sitio |
| Asura Scans | Manhwa | EN | API JSON del sitio |
| Flame Comics | Manhwa | EN | Datos Next.js |
| Royal Road | Novela | EN | HTML |
| SkyNovels | Novela | ES | API JSON |
| TuNovelaLigera | Novela | ES | HTML (Madara) + AJAX |
| AnimeAV1 | Anime | ES | Datos SvelteKit + extractores |
| LectorTMO, AnimeFLV, LectorManga, SurtrScans | — | — | *Experimentales* (desactivadas por defecto: protegidas por Cloudflare/Wordfence o inestables) |

Los sitios web cambian con frecuencia; una fuente puede dejar de funcionar sin previo aviso.

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

### Tech stack

- **Kotlin** 2.2 · **Coroutines / Flow**
- **Jetpack Compose** + **Material 3**, Navigation Compose
- **Room** (KSP) with exported schemas and migrations
- **WorkManager** for background update checks
- **Coil 3** for images (shares the app's OkHttp client)
- **Media3 / ExoPlayer** (HLS + progressive) for video
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
│   │   └── anime/    AnimeAV1, AnimeFLV, VideoExtractors (ok.ru, MP4Upload, YourUpload, HLS)
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
