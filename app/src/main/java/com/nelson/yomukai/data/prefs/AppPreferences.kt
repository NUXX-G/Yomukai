package com.nelson.yomukai.data.prefs

import android.content.Context
import com.nelson.yomukai.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val displayName: String) { SYSTEM(tr("Sistema")), LIGHT(tr("Claro")), DARK(tr("Oscuro")) }

enum class MangaReaderMode(val displayName: String) {
    WEBTOON(tr("Vertical (Webtoon)")),
    PAGED_LTR(tr("Páginas (izq. → der.)")),
    PAGED_RTL(tr("Páginas (der. → izq.)"))
}

enum class ReaderFit(val displayName: String) {
    SCREEN(tr("Ajustar a pantalla")), WIDTH(tr("Ajustar al ancho")), HEIGHT(tr("Ajustar al alto"))
}

enum class ReaderColorFilter(val displayName: String) {
    NONE(tr("Ninguno")), GRAYSCALE(tr("Gris")), SEPIA(tr("Sepia")), INVERT(tr("Invertir"))
}

enum class OrientationLock(val displayName: String) { AUTO(tr("Automática")), PORTRAIT(tr("Vertical")), LANDSCAPE(tr("Horizontal")) }

enum class NovelTheme(val displayName: String, val background: Long, val text: Long) {
    DARK(tr("Oscuro"), 0xFF1B1B1F, 0xFFE3E2E6),
    OLED(tr("OLED"), 0xFF000000, 0xFFC8C8C8),
    SEPIA(tr("Sepia"), 0xFFF4ECD8, 0xFF5B4636),
    LIGHT(tr("Claro"), 0xFFFFFFFF, 0xFF1F1F1F)
}

enum class NovelFont(val displayName: String) { SERIF(tr("Serif")), SANS(tr("Sans")), MONO("Mono") }

data class MangaReaderSettings(
    val mode: MangaReaderMode = MangaReaderMode.WEBTOON,
    /** -1 = follow system brightness, otherwise 0.05..1. */
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = true,
    val tapToTurn: Boolean = true,
    val showPageNumber: Boolean = true,
    /** Append the next chapter automatically when the end is near. */
    val infiniteScroll: Boolean = true,
    val fit: ReaderFit = ReaderFit.SCREEN,
    val colorFilter: ReaderColorFilter = ReaderColorFilter.NONE,
    val cropBorders: Boolean = false,
    /** Bubbles read and translated on the device (ML Kit) into the interface language; off by default. */
    val translateBubbles: Boolean = false,
    val orientation: OrientationLock = OrientationLock.AUTO,
    /** Auto-scroll speed, 1..10 (screen heights per minute x ~0.5). */
    val autoScrollSpeed: Float = 3f
)

data class NovelReaderSettings(
    val fontSize: Float = 18f,
    val lineHeight: Float = 1.6f,
    val theme: NovelTheme = NovelTheme.DARK,
    val font: NovelFont = NovelFont.SERIF,
    val justify: Boolean = true,
    val horizontalPadding: Float = 20f,
    val keepScreenOn: Boolean = true,
    /** Text-to-speech rate, 0.5..2. */
    val ttsRate: Float = 1f,
    val infiniteScroll: Boolean = true,
    /** Auto-scroll speed, same scale as the manga reader's (3 = normal). */
    val autoScrollSpeed: Float = 3f,
    /** "natural" (the connected server's neural voices) or "system" (Android's own). */
    val ttsEngine: String = "natural",
    /** Dialogue read with the voice of who speaks (narrator, woman, man). */
    val ttsCharacters: Boolean = true,
    /** Chosen voices: "engine.lang.role" (e.g. "natural.es.narrator") -> voice id or name. */
    val ttsVoices: Map<String, String> = emptyMap()
)

enum class ProxyType(val displayName: String) { NONE(tr("Sin proxy")), HTTP("HTTP"), SOCKS(tr("SOCKS5 (Tor/Orbot)")) }

/** Where Tor comes from: embedded (tor-android), an external Orbot on 127.0.0.1:9050, or not used. */
enum class TorMode(val displayName: String) { OFF(tr("Desactivado")), BUILTIN(tr("Tor integrado")), ORBOT(tr("Orbot (externo)")) }

/** Presets for the capture browser start page / search; the user can also type any address. */
enum class BrowserHome(val displayName: String, val url: String, val searchUrl: String) {
    DDG_ONION(
        "DuckDuckGo (.onion)",
        "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/",
        "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/?q="
    ),
    DDG_HTML(tr("DuckDuckGo (HTML ligero)"), "https://html.duckduckgo.com/html/", "https://html.duckduckgo.com/html/?q="),
    BRAVE(tr("Brave Search"), "https://search.brave.com/", "https://search.brave.com/search?q="),
    AHMIA(tr("Ahmia (buscador de .onion)"), "https://ahmia.fi/", "https://ahmia.fi/search/?q=")
}

/** Genres to hide, everywhere and per source id. */
data class GenreExclusions(val global: Set<String> = emptySet(), val bySource: Map<String, Set<String>> = emptyMap()) {
    fun forSource(sourceId: String): Set<String> = global + bySource[sourceId].orEmpty()
}

/** A page the user saved or visited in the capture browser. */
data class BrowserPage(val url: String, val title: String)

/** Options to get around DNS-level blocks and firewalls that some ISPs apply to these sites. */
data class NetworkSettings(
    val dnsOverHttps: Boolean = true,
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 9050,
    val torMode: TorMode = TorMode.OFF,
    /** Sources, downloads, covers and video playback go through Tor. */
    val torRoutesApp: Boolean = true,
    /** The capture browser goes through Tor (and never loads pages before Tor is ready). */
    val torRoutesBrowser: Boolean = true,
    /** Source ids that skip Tor (many sites behind Cloudflare block Tor exits). */
    val torExcludedSources: Set<String> = emptySet(),
    val browserHome: BrowserHome = BrowserHome.DDG_ONION
)

enum class ComicFormat(val displayName: String) { CBZ(tr("CBZ (un archivo)")), IMAGES(tr("Carpeta de imágenes")) }

enum class NovelFormat(val displayName: String) { EPUB("EPUB"), TXT("TXT") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    /** A theme (shared ThemeSpec JSON): a built-in one or the one chosen on the connected server; null = default. */
    val customTheme: String? = null,
    /** null = use the source manager's defaults. */
    val enabledSources: Set<String>? = null,
    /** Background check for new chapters/episodes of library titles. */
    val updatesEnabled: Boolean = true,
    val updateIntervalHours: Int = 12,
    val updatesWifiOnly: Boolean = false,
    /** Anime player: start the next episode when one ends. */
    val playerAutoNext: Boolean = true,
    /** Skip openings and endings by themselves when their times are known. */
    val playerAutoSkip: Boolean = false,
    /** Language learning: off by default; with it on, a long press on a word of a novel opens the dictionary. */
    val learningEnabled: Boolean = false,
    val playerSpeed: Float = 1f,
    /** Downloads wait for an unmetered connection. */
    val downloadsWifiOnly: Boolean = false,
    val maxConcurrentDownloads: Int = 2,
    /** Also keep a copy in Download/Yomukai, visible from any file manager. */
    val exportDownloads: Boolean = true,
    val comicFormat: ComicFormat = ComicFormat.CBZ,
    val novelFormat: NovelFormat = NovelFormat.EPUB
)

/** SharedPreferences-backed settings exposed as StateFlows so Compose reacts to changes. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("yomukai_prefs", Context.MODE_PRIVATE)
    private val BLACK_THEME_MIGRATED = "black_theme_v1"

    private val _app = MutableStateFlow(migrateTheme().let { readApp() })
    val app: StateFlow<AppSettings> = _app.asStateFlow()

    private val _manga = MutableStateFlow(readManga())
    val manga: StateFlow<MangaReaderSettings> = _manga.asStateFlow()

    private val _novel = MutableStateFlow(readNovel())
    val novel: StateFlow<NovelReaderSettings> = _novel.asStateFlow()

    private val _network = MutableStateFlow(readNetwork())
    val network: StateFlow<NetworkSettings> = _network.asStateFlow()

    private val _searchHistory = MutableStateFlow(
        prefs.getString("search_history", "").orEmpty().split('\n').filter { it.isNotBlank() }
    )
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    /** Stored as `*` + tab + genre for global entries and source id + tab + genre for the others. */
    private val _exclusions = MutableStateFlow(readExclusions())
    val exclusions: StateFlow<GenreExclusions> = _exclusions.asStateFlow()

    private fun readExclusions(): GenreExclusions {
        val rows = prefs.getStringSet("excluded_genres", null).orEmpty().map { it.substringBefore('\t') to it.substringAfter('\t') }
        return GenreExclusions(
            global = rows.filter { it.first == "*" }.map { it.second }.toSet(),
            bySource = rows.filter { it.first != "*" }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        )
    }

    /** Adds [genre] to the list of [sourceId] (null = every source), or removes it when already there. */
    fun toggleExclusion(sourceId: String?, genre: String) {
        val row = (sourceId ?: "*") + "\t" + genre.replace('\t', ' ')
        val current = prefs.getStringSet("excluded_genres", null).orEmpty()
        prefs.edit().putStringSet("excluded_genres", if (row in current) current - row else current + row).apply()
        _exclusions.value = readExclusions()
        markSharedChanged()
    }

    // Sources and excluded genres are shared with the web when sync is on: each local change is
    // stamped, so the newest of the phone and the server wins.

    /** When the shared settings last changed here, and whether the server has not been told yet. */
    val sharedUpdatedAt: Long get() = prefs.getLong("sync_shared_at", 0L)
    val sharedDirty: Boolean get() = prefs.getBoolean("sync_shared_dirty", false)

    private fun markSharedChanged() {
        prefs.edit().putLong("sync_shared_at", System.currentTimeMillis()).putBoolean("sync_shared_dirty", true).apply()
        onSharedChanged()
    }

    /** Called after a local change to something that syncs; the sync code sets it. */
    @Volatile var onSharedChanged: () -> Unit = {}

    fun markSharedSent(at: Long) {
        if (sharedUpdatedAt == at) prefs.edit().putBoolean("sync_shared_dirty", false).apply()
    }

    /** Everything shared counts as changed now (first connection to a server). */
    fun markSharedForUpload() = prefs.edit().putBoolean("sync_shared_dirty", true).apply()

    /** The server's version, newer than ours: written without counting as a local change. */
    fun applySharedFromServer(enabledSources: Set<String>?, exclusions: GenreExclusions, updatedAt: Long) {
        val rows = exclusions.global.map { "*\t$it" } + exclusions.bySource.flatMap { (source, genres) -> genres.map { "$source\t$it" } }
        prefs.edit()
            .putStringSet("excluded_genres", rows.toSet())
            .putLong("sync_shared_at", updatedAt)
            .putBoolean("sync_shared_dirty", false)
            .apply {
                if (enabledSources == null) remove("enabled_sources") else putStringSet("enabled_sources", enabledSources)
            }
            .apply()
        _exclusions.value = readExclusions()
        _app.value = _app.value.copy(enabledSources = enabledSources)
    }

    /** One page per line: url, tab, title. */
    private val _browserFavorites = MutableStateFlow(
        prefs.getString("browser_favorites", "").orEmpty().split('\n').filter { it.isNotBlank() }
            .map { BrowserPage(it.substringBefore('\t'), it.substringAfter('\t', "")) }
    )
    val browserFavorites: StateFlow<List<BrowserPage>> = _browserFavorites.asStateFlow()

    /** Saves [url], or removes it when it is already a favourite. */
    fun toggleBrowserFavorite(url: String, title: String) {
        val current = _browserFavorites.value
        val updated = if (current.any { it.url == url }) current.filterNot { it.url == url }
        else listOf(BrowserPage(url, title.replace('\n', ' ').replace('\t', ' '))) + current
        prefs.edit().putString("browser_favorites", updated.joinToString("\n") { "${it.url}\t${it.title}" }).apply()
        _browserFavorites.value = updated
    }

    private fun saveHistory(list: List<String>) {
        prefs.edit().putString("search_history", list.joinToString("\n")).apply()
        _searchHistory.value = list
    }

    /** Most recent first, case-insensitive de-duplication, capped at [SEARCH_HISTORY_LIMIT]. */
    fun addSearchHistory(query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.length < 2) return
        saveHistory((listOf(q) + _searchHistory.value.filterNot { it.equals(q, ignoreCase = true) }).take(SEARCH_HISTORY_LIMIT))
    }

    fun removeSearchHistory(query: String) = saveHistory(_searchHistory.value - query)

    fun clearSearchHistory() = saveHistory(emptyList())

    fun updateNetwork(transform: (NetworkSettings) -> NetworkSettings) {
        val value = transform(_network.value)
        prefs.edit()
            .putBoolean("net_doh", value.dnsOverHttps)
            .putString("net_proxy_type", value.proxyType.name)
            .putString("net_proxy_host", value.proxyHost)
            .putInt("net_proxy_port", value.proxyPort)
            .putString("net_tor_mode", value.torMode.name)
            .putBoolean("net_tor_app", value.torRoutesApp)
            .putBoolean("net_tor_browser", value.torRoutesBrowser)
            .putStringSet("net_tor_excluded", value.torExcludedSources)
            .putString("net_browser_home", value.browserHome.name)
            .apply()
        _network.value = value
    }

    private fun readNetwork() = NetworkSettings(
        dnsOverHttps = prefs.getBoolean("net_doh", true),
        proxyType = enumOr(prefs.getString("net_proxy_type", null), ProxyType.NONE),
        proxyHost = prefs.getString("net_proxy_host", null) ?: "127.0.0.1",
        proxyPort = prefs.getInt("net_proxy_port", 9050),
        torMode = enumOr(prefs.getString("net_tor_mode", null), TorMode.OFF),
        torRoutesApp = prefs.getBoolean("net_tor_app", true),
        torRoutesBrowser = prefs.getBoolean("net_tor_browser", true),
        torExcludedSources = prefs.getStringSet("net_tor_excluded", null)?.toSet().orEmpty(),
        browserHome = enumOr(prefs.getString("net_browser_home", null), BrowserHome.DDG_ONION)
    )

    fun updateApp(transform: (AppSettings) -> AppSettings) {
        val value = transform(_app.value)
        if (value.enabledSources != _app.value.enabledSources) markSharedChanged()
        prefs.edit()
            .putString("theme_mode", value.themeMode.name)
            .putBoolean("dynamic_color_v2", value.dynamicColor)
            .putString("custom_theme", value.customTheme)
            .putBoolean("updates_enabled", value.updatesEnabled)
            .putInt("updates_interval", value.updateIntervalHours)
            .putBoolean("updates_wifi", value.updatesWifiOnly)
            .putBoolean("player_auto_next", value.playerAutoNext)
            .putBoolean("player_auto_skip", value.playerAutoSkip)
            .putBoolean("learning_enabled", value.learningEnabled)
            .putFloat("player_speed", value.playerSpeed)
            .putBoolean("downloads_wifi", value.downloadsWifiOnly)
            .putInt("downloads_max", value.maxConcurrentDownloads)
            .putBoolean("export_downloads", value.exportDownloads)
            .putString("comic_format", value.comicFormat.name)
            .putString("novel_format", value.novelFormat.name)
            .apply {
                if (value.enabledSources == null) remove("enabled_sources")
                else putStringSet("enabled_sources", value.enabledSources)
            }
            .apply()
        _app.value = value
    }

    /**
     * Someone who ever toggled a source has a saved list, which knows nothing of sources added later.
     * Those are switched on once; whatever the user turned off stays off.
     */
    fun enableNewSources(available: Set<String>) {
        val known = prefs.getStringSet("known_sources", null)?.toSet() ?: (available - FIRST_TRACKED_SOURCES)
        val added = available - known
        if (added.isNotEmpty()) _app.value.enabledSources?.let { saved -> updateApp { it.copy(enabledSources = saved + added) } }
        prefs.edit().putStringSet("known_sources", available).apply()
    }

    fun updateManga(transform: (MangaReaderSettings) -> MangaReaderSettings) {
        val value = transform(_manga.value)
        prefs.edit()
            .putString("manga_mode", value.mode.name)
            .putFloat("manga_brightness", value.brightness)
            .putBoolean("manga_keep_on", value.keepScreenOn)
            .putBoolean("manga_tap_turn", value.tapToTurn)
            .putBoolean("manga_page_number", value.showPageNumber)
            .putBoolean("manga_infinite", value.infiniteScroll)
            .putString("manga_fit", value.fit.name)
            .putString("manga_color_filter", value.colorFilter.name)
            .putBoolean("manga_crop", value.cropBorders)
            .putBoolean("manga_translate", value.translateBubbles)
            .putString("manga_orientation", value.orientation.name)
            .putFloat("manga_autoscroll", value.autoScrollSpeed)
            .apply()
        _manga.value = value
    }

    fun updateNovel(transform: (NovelReaderSettings) -> NovelReaderSettings) {
        val value = transform(_novel.value)
        prefs.edit()
            .putFloat("novel_font_size", value.fontSize)
            .putFloat("novel_line_height", value.lineHeight)
            .putString("novel_theme", value.theme.name)
            .putString("novel_font", value.font.name)
            .putBoolean("novel_justify", value.justify)
            .putFloat("novel_padding", value.horizontalPadding)
            .putBoolean("novel_keep_on", value.keepScreenOn)
            .putFloat("novel_tts_rate", value.ttsRate)
            .putBoolean("novel_infinite", value.infiniteScroll)
            .putFloat("novel_autoscroll", value.autoScrollSpeed)
            .putString("novel_tts_engine", value.ttsEngine)
            .putBoolean("novel_tts_characters", value.ttsCharacters)
            .putString("novel_tts_voices", value.ttsVoices.entries.joinToString("\n") { it.key + "=" + it.value })
            .apply()
        _novel.value = value
    }

    /**
     * v1.8.0 brought the black interface: "follow the system", the old default, becomes dark once.
     * A choice made after that is respected.
     */
    private fun migrateTheme() {
        if (prefs.getBoolean(BLACK_THEME_MIGRATED, false)) return
        val current = prefs.getString("theme_mode", null)
        prefs.edit().apply {
            if (current == null || current == ThemeMode.SYSTEM.name) putString("theme_mode", ThemeMode.DARK.name)
            putBoolean(BLACK_THEME_MIGRATED, true)
        }.apply()
    }

    private fun readApp() = AppSettings(
        themeMode = enumOr(prefs.getString("theme_mode", null), ThemeMode.DARK),
        dynamicColor = prefs.getBoolean("dynamic_color_v2", false),
        customTheme = prefs.getString("custom_theme", null),
        updatesEnabled = prefs.getBoolean("updates_enabled", true),
        updateIntervalHours = prefs.getInt("updates_interval", 12),
        updatesWifiOnly = prefs.getBoolean("updates_wifi", false),
        playerAutoNext = prefs.getBoolean("player_auto_next", true),
        playerAutoSkip = prefs.getBoolean("player_auto_skip", false),
        learningEnabled = prefs.getBoolean("learning_enabled", false),
        playerSpeed = prefs.getFloat("player_speed", 1f),
        downloadsWifiOnly = prefs.getBoolean("downloads_wifi", false),
        maxConcurrentDownloads = prefs.getInt("downloads_max", 2),
        exportDownloads = prefs.getBoolean("export_downloads", true),
        comicFormat = enumOr(prefs.getString("comic_format", null), ComicFormat.CBZ),
        novelFormat = enumOr(prefs.getString("novel_format", null), NovelFormat.EPUB),
        enabledSources = prefs.getStringSet("enabled_sources", null)?.toSet()
    )

    private fun readManga() = MangaReaderSettings(
        mode = enumOr(prefs.getString("manga_mode", null), MangaReaderMode.WEBTOON),
        brightness = prefs.getFloat("manga_brightness", -1f),
        keepScreenOn = prefs.getBoolean("manga_keep_on", true),
        tapToTurn = prefs.getBoolean("manga_tap_turn", true),
        showPageNumber = prefs.getBoolean("manga_page_number", true),
        infiniteScroll = prefs.getBoolean("manga_infinite", true),
        fit = enumOr(prefs.getString("manga_fit", null), ReaderFit.SCREEN),
        colorFilter = enumOr(prefs.getString("manga_color_filter", null), ReaderColorFilter.NONE),
        cropBorders = prefs.getBoolean("manga_crop", false),
        translateBubbles = prefs.getBoolean("manga_translate", false),
        orientation = enumOr(prefs.getString("manga_orientation", null), OrientationLock.AUTO),
        autoScrollSpeed = prefs.getFloat("manga_autoscroll", 3f)
    )

    private fun readNovel() = NovelReaderSettings(
        fontSize = prefs.getFloat("novel_font_size", 18f),
        lineHeight = prefs.getFloat("novel_line_height", 1.6f),
        theme = enumOr(prefs.getString("novel_theme", null), NovelTheme.DARK),
        font = enumOr(prefs.getString("novel_font", null), NovelFont.SERIF),
        justify = prefs.getBoolean("novel_justify", true),
        horizontalPadding = prefs.getFloat("novel_padding", 20f),
        keepScreenOn = prefs.getBoolean("novel_keep_on", true),
        ttsRate = prefs.getFloat("novel_tts_rate", 1f),
        infiniteScroll = prefs.getBoolean("novel_infinite", true),
        autoScrollSpeed = prefs.getFloat("novel_autoscroll", 3f),
        ttsEngine = prefs.getString("novel_tts_engine", null) ?: "natural",
        ttsCharacters = prefs.getBoolean("novel_tts_characters", true),
        ttsVoices = prefs.getString("novel_tts_voices", null).orEmpty().lines()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] } }
            .toMap()
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}

private const val SEARCH_HISTORY_LIMIT = 20

/** Sources added in the version that started tracking them; every other one predates it. */
private val FIRST_TRACKED_SOURCES = setOf("animeflv", "webtoons_en")
