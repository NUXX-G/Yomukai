package com.nelson.yomukai.data.source.declarative

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * A source described as data: where its lists are, how to read them, which domains it lives on. One JSON file per
 * source in `sources/` of the repository; fixing a source that changed its HTML is editing that file, and the signed
 * bundle reaches the server and the apps without a new APK. Only special logic (deobfuscating a player) stays in Kotlin.
 *
 * URL templates take {base} (the current domain), {page}, {query} (URL-encoded), {media} (a title's URL), {chapter}
 * and, in catalogue URLs, every filter key ({sort}, ...). Selectors are CSS (Jsoup); "@attr" at the end reads an
 * attribute ("a@href"). JSON paths are dots with [n] or [*] ("props.pageProps.series[*]").
 */
data class SourceConfig(
    val id: String = "",
    val name: String = "",
    /** "ES", "EN", "MULTI"... */
    val lang: String = "",
    /** "manga" (images) or "novel" (text). */
    val kind: String = "manga",
    /** MANGA, MANHWA, MANHUA, COMIC, NOVEL. */
    val types: List<String> = emptyList(),
    /** "html", "madara", "nextjs" or "sveltekit". */
    val engine: String = "html",
    val baseUrl: String = "",
    /** Every domain the site has used, newest first; tried in order when one stops answering. */
    val domains: List<String> = emptyList(),
    val enabledByDefault: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    val extraHosts: List<String> = emptyList(),
    val latest: ListRule? = null,
    val search: ListRule? = null,
    val browse: ListRule? = null,
    /** The site's own lists of sexual content (its "adult", "smut"... genre pages), when it does not tag each title. */
    val adult: List<ListRule> = emptyList(),
    val card: CardRule? = null,
    val details: DetailsRule? = null,
    val chapters: ChaptersRule? = null,
    val pages: PagesRule? = null,
    val text: TextRule? = null,
    val images: ImageRule? = null,
    val filters: List<FilterRule> = emptyList()
)

/** A list page: the URL for page 1 and the next ones, and where each item is. */
data class ListRule(
    val url: String = "",
    /** For page 2 and later; defaults to [url]. */
    val nextUrl: String? = null,
    /** CSS selector of each card (html engines) ... */
    val item: String? = null,
    /** ... or JSON path to the array of items (nextjs, sveltekit, json). */
    val json: String? = null,
    /** For JSON items: field paths or templates ("{base}/series/{slug}"). */
    val map: Map<String, String> = emptyMap(),
    /** Catalogue only: text added once per selected value of a multi filter, e.g. "status" to "&status[]={value}". */
    val multi: Map<String, String> = emptyMap(),
    /** "GET" or "POST" (empty body). */
    val method: String = "GET",
    /** Overrides of the card selectors for this list only. */
    val card: CardRule? = null
)

data class CardRule(
    val link: String? = null,
    val title: String? = null,
    val cover: String? = null,
    @SerializedName("altTitles") val altTitles: String? = null
)

data class DetailsRule(
    val title: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val author: String? = null,
    val genres: String? = null,
    /** A labelled row ("Estado: En curso"): rows selector, heading selector, accepted headings, value selector. */
    val status: LabelledRule? = null,
    /** JSON engines: path to the title object inside the page data, and its field map. */
    val json: String? = null,
    val map: Map<String, String> = emptyMap()
)

data class LabelledRule(val rows: String = "", val heading: String = "", val match: List<String> = emptyList(), val value: String = "")

data class ChaptersRule(
    /** Defaults to the title page itself. */
    val url: String? = null,
    val method: String = "GET",
    val item: String? = null,
    val link: String? = null,
    val title: String? = null,
    val date: String? = null,
    val json: String? = null,
    val map: Map<String, String> = emptyMap(),
    /** Lists come newest first on most sites: number the untitled ones from the end. */
    val newestFirst: Boolean = true
)

data class PagesRule(
    val image: String? = null,
    val json: String? = null,
    /** Field of each JSON item holding the image URL; empty when the array holds plain strings. */
    val field: String? = null
)

data class TextRule(val content: String = "", val remove: List<String> = emptyList())

data class ImageRule(
    /** Attributes holding the real image, in order (lazy loading puts it in data-src). */
    val attrs: List<String> = listOf("data-src", "data-lazy-src", "src"),
    /** URLs containing any of these are placeholders. */
    val ignore: List<String> = emptyList(),
    val forceHttps: Boolean = true
)

data class FilterRule(
    val key: String = "",
    val label: String = "",
    val multi: Boolean = false,
    val default: String? = null,
    val options: List<FilterOptionRule> = emptyList()
)

data class FilterOptionRule(val value: String = "", val label: String = "")

object SourceConfigs {
    private val gson = Gson()
    val ENGINES = setOf("html", "madara", "nextjs", "sveltekit")
    private val ID = Regex("[a-z0-9_]{2,40}")
    val DOMAIN = Regex("https://[a-z0-9-]+(\\.[a-z0-9-]+)+")

    fun parse(json: JsonObject): SourceConfig = gson.fromJson(json, SourceConfig::class.java)

    /** Problems that make a configuration unusable; empty = usable. Untrusted input: everything is bounded. */
    fun problems(c: SourceConfig): List<String> = buildList {
        if (!ID.matches(c.id)) add("id no válido")
        if (c.name.isBlank() || c.name.length > 60) add("nombre no válido")
        if (c.engine !in ENGINES) add("motor desconocido: ${c.engine}")
        if (c.kind !in setOf("manga", "novel")) add("tipo desconocido: ${c.kind}")
        if ((listOf(c.baseUrl) + c.domains).any { !DOMAIN.matches(it) }) add("los dominios tienen que ser https://dominio sin ruta")
        if (c.latest == null && c.engine != "madara") add("falta la lista de novedades")
        if (c.headers.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) }) add("cabeceras no permitidas")
        if (c.toString().length > 50_000) add("configuración demasiado grande")
    }
}
