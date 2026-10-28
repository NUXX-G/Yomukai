package com.nelson.yomukai.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val displayName: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro") }

enum class MangaReaderMode(val displayName: String) {
    WEBTOON("Vertical (Webtoon)"),
    PAGED_LTR("Páginas (izq. → der.)"),
    PAGED_RTL("Páginas (der. → izq.)")
}

enum class ReaderFit(val displayName: String) {
    SCREEN("Ajustar a pantalla"), WIDTH("Ajustar al ancho"), HEIGHT("Ajustar al alto")
}

enum class ReaderColorFilter(val displayName: String) {
    NONE("Ninguno"), GRAYSCALE("Gris"), SEPIA("Sepia"), INVERT("Invertir")
}

enum class OrientationLock(val displayName: String) { AUTO("Automática"), PORTRAIT("Vertical"), LANDSCAPE("Horizontal") }

enum class NovelTheme(val displayName: String, val background: Long, val text: Long) {
    DARK("Oscuro", 0xFF1B1B1F, 0xFFE3E2E6),
    OLED("OLED", 0xFF000000, 0xFFC8C8C8),
    SEPIA("Sepia", 0xFFF4ECD8, 0xFF5B4636),
    LIGHT("Claro", 0xFFFFFFFF, 0xFF1F1F1F)
}

enum class NovelFont(val displayName: String) { SERIF("Serif"), SANS("Sans"), MONO("Mono") }

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
    val infiniteScroll: Boolean = true
)

enum class ProxyType(val displayName: String) { NONE("Sin proxy"), HTTP("HTTP"), SOCKS("SOCKS5 (Tor/Orbot)") }

/** Options to get around DNS-level blocks and firewalls that some ISPs apply to these sites. */
data class NetworkSettings(
    val dnsOverHttps: Boolean = true,
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 9050
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** null = use the source manager's defaults. */
    val enabledSources: Set<String>? = null,
    /** Background check for new chapters/episodes of library titles. */
    val updatesEnabled: Boolean = true,
    val updateIntervalHours: Int = 12,
    val updatesWifiOnly: Boolean = false,
    /** Anime player: start the next episode when one ends. */
    val playerAutoNext: Boolean = true,
    val playerSpeed: Float = 1f,
    /** Downloads wait for an unmetered connection. */
    val downloadsWifiOnly: Boolean = false,
    val maxConcurrentDownloads: Int = 2
)

/** SharedPreferences-backed settings exposed as StateFlows so Compose reacts to changes. */
class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("yomukai_prefs", Context.MODE_PRIVATE)

    private val _app = MutableStateFlow(readApp())
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
            .apply()
        _network.value = value
    }

    private fun readNetwork() = NetworkSettings(
        dnsOverHttps = prefs.getBoolean("net_doh", true),
        proxyType = enumOr(prefs.getString("net_proxy_type", null), ProxyType.NONE),
        proxyHost = prefs.getString("net_proxy_host", null) ?: "127.0.0.1",
        proxyPort = prefs.getInt("net_proxy_port", 9050)
    )

    fun updateApp(transform: (AppSettings) -> AppSettings) {
        val value = transform(_app.value)
        prefs.edit()
            .putString("theme_mode", value.themeMode.name)
            .putBoolean("dynamic_color", value.dynamicColor)
            .putBoolean("updates_enabled", value.updatesEnabled)
            .putInt("updates_interval", value.updateIntervalHours)
            .putBoolean("updates_wifi", value.updatesWifiOnly)
            .putBoolean("player_auto_next", value.playerAutoNext)
            .putFloat("player_speed", value.playerSpeed)
            .putBoolean("downloads_wifi", value.downloadsWifiOnly)
            .putInt("downloads_max", value.maxConcurrentDownloads)
            .apply {
                if (value.enabledSources == null) remove("enabled_sources")
                else putStringSet("enabled_sources", value.enabledSources)
            }
            .apply()
        _app.value = value
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
            .apply()
        _novel.value = value
    }

    private fun readApp() = AppSettings(
        themeMode = enumOr(prefs.getString("theme_mode", null), ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic_color", true),
        updatesEnabled = prefs.getBoolean("updates_enabled", true),
        updateIntervalHours = prefs.getInt("updates_interval", 12),
        updatesWifiOnly = prefs.getBoolean("updates_wifi", false),
        playerAutoNext = prefs.getBoolean("player_auto_next", true),
        playerSpeed = prefs.getFloat("player_speed", 1f),
        downloadsWifiOnly = prefs.getBoolean("downloads_wifi", false),
        maxConcurrentDownloads = prefs.getInt("downloads_max", 2),
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
        infiniteScroll = prefs.getBoolean("novel_infinite", true)
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}

private const val SEARCH_HISTORY_LIMIT = 20
