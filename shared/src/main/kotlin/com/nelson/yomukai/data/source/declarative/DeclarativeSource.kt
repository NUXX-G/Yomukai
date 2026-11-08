package com.nelson.yomukai.data.source.declarative

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.MultiSelectFilter
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.SourceValidation
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/** Builds the right kind of source for a configuration. */
fun declarativeSource(config: SourceConfig, network: HttpFetcher, onDomainChange: (String, String, String) -> Unit = { _, _, _ -> }): CatalogueSource {
    val full = Engines.withDefaults(config)
    return if (full.kind == "novel") DeclarativeNovelSource(full, network, onDomainChange) else DeclarativeMangaSource(full, network, onDomainChange)
}

/** Theme conventions, so a config for a theme only has to say what differs. */
object Engines {
    fun withDefaults(c: SourceConfig): SourceConfig = if (c.engine != "madara") c else c.copy(
        latest = c.latest ?: ListRule(url = "{base}/", nextUrl = "{base}/page/{page}/", item = ".page-item-detail"),
        search = c.search ?: ListRule(url = "{base}/?s={query}&post_type=wp-manga", item = ".c-tabs-item__content"),
        browse = c.browse,
        card = CardRule(
            link = c.card?.link ?: ".post-title a@href",
            title = c.card?.title ?: ".post-title a",
            cover = c.card?.cover ?: "img",
            altTitles = c.card?.altTitles ?: ".mg_alternative .summary-content"
        ),
        details = DetailsRule(
            title = c.details?.title ?: ".post-title h1@own",
            cover = c.details?.cover ?: ".summary_image img",
            description = c.details?.description ?: ".manga-excerpt, .summary__content, .description-summary",
            author = c.details?.author ?: ".author-content a",
            genres = c.details?.genres ?: ".genres-content a",
            status = c.details?.status ?: LabelledRule(".post-status .post-content_item", ".summary-heading", listOf("Estado", "Status"), ".summary-content")
        ),
        chapters = c.chapters ?: ChaptersRule(url = "{media}ajax/chapters/", method = "POST", item = "li.wp-manga-chapter", link = "a@href", title = "a", date = ".chapter-release-date"),
        pages = c.pages ?: PagesRule(image = ".reading-content img"),
        text = c.text ?: TextRule(content = ".reading-content .text-left, .reading-content", remove = listOf("script", "ins", ".adsbygoogle"))
    )
}

/**
 * A source run from its [SourceConfig]. Lists, details, chapters and content each try the configured way first and
 * then generic fallbacks (embedded JSON, ld+json, og: tags, the image heuristic), so a small change on the site
 * degrades one field instead of breaking the source. Domains: requests go to the current one; if it does not answer,
 * the next in the list is tried; a permanent redirect to a new domain is followed and reported.
 */
abstract class DeclarativeSource(
    val config: SourceConfig,
    private val network: HttpFetcher,
    private val onDomainChange: (sourceId: String, from: String, to: String) -> Unit
) : CatalogueSource {
    override val id = config.id
    override val name = config.name
    override val language = config.lang.ifBlank { "MULTI" }
    override val supportedTypes: Set<MediaType> = config.types.mapNotNull { t -> MediaType.entries.firstOrNull { it.name == t } }.toSet().ifEmpty { setOf(MediaType.MANGA) }
    override val enabledByDefault = config.enabledByDefault
    override val supportsLatestPaging = config.latest?.nextUrl != null || config.latest?.url?.contains("{page}") == true
    override val extraHosts = config.extraHosts

    @Volatile private var base: String = config.baseUrl.trimEnd('/')
    override val baseUrl: String get() = base
    private val knownBases = (listOf(config.baseUrl) + config.domains).map { it.trimEnd('/') }.distinct()

    override val imageHeaders: Map<String, String> get() = mapOf("Referer" to "$base/")
    private val imageRule = config.images ?: ImageRule()

    override val filters: List<SourceFilter> = config.filters.mapNotNull { f ->
        val options = f.options.map { FilterOption(it.value, it.label) }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (f.multi) MultiSelectFilter(f.key, f.label, options) else SelectFilter(f.key, f.label, options, f.default ?: options.first().value)
    }

    /** A URL saved under an old domain (library, history) is read from the current one. */
    fun rebase(url: String): String {
        val old = knownBases.firstOrNull { it != base && url.startsWith(it) } ?: return url
        return base + url.removePrefix(old)
    }

    private fun headers(): Map<String, String> = mapOf("Referer" to "$base/") + config.headers.mapValues { Extract.template(it.value, mapOf("base" to base)) }

    /** GET or POST [url]; on a connection failure the other known domains are tried, and a moved domain is adopted. */
    protected suspend fun fetch(url: String, method: String = "GET"): Pair<String, String> {
        val target = rebase(url)
        val attempt: suspend (String) -> Pair<String, String> = { u ->
            if (method.equals("POST", true)) network.postString(u, headers()) to u else network.getStringWithUrl(u, headers())
        }
        val (body, finalUrl) = try {
            attempt(target)
        } catch (e: IOException) {
            val alternatives = knownBases.filter { it != base && target.startsWith(base) }
            var result: Pair<String, String>? = null
            for (alt in alternatives) {
                result = runCatching { attempt(alt + target.removePrefix(base)) }.getOrNull()
                if (result != null) {
                    switchTo(alt)
                    break
                }
            }
            result ?: throw e
        }
        followMove(target, finalUrl)
        return body to finalUrl
    }

    private fun followMove(requested: String, final: String) {
        val from = SourceConfigs.DOMAIN.find(requested)?.value ?: return
        val to = SourceConfigs.DOMAIN.find(final)?.value ?: return
        // Same path on another domain: the site moved (http→https or www are not moves).
        val moved = from != to && from == base && to.removePrefix("https://").removePrefix("www.") != from.removePrefix("https://").removePrefix("www.")
        if (moved && final.removePrefix(to).trimEnd('/') == requested.removePrefix(from).trimEnd('/')) switchTo(to)
    }

    private fun switchTo(newBase: String) {
        if (newBase == base) return
        val old = base
        base = newBase
        onDomainChange(id, old, newBase)
    }

    protected fun vars(extra: Map<String, String> = emptyMap()) = mapOf("base" to base) + extra

    // ---------- Data of a page ----------

    /** JSON behind a page: the body itself when it is JSON, SvelteKit's __data.json, or Next.js's __NEXT_DATA__. */
    protected fun data(body: String, doc: Document?): JsonElement? {
        val trimmed = body.trimStart()
        return when {
            config.engine == "sveltekit" -> Extract.svelteKitData(body)
            trimmed.startsWith("{") || trimmed.startsWith("[") -> runCatching { JsonParser.parseString(body) }.getOrNull()
            else -> doc?.let(Extract::nextData)
        }
    }

    // ---------- Lists ----------

    private suspend fun list(rule: ListRule?, url: String): List<MediaItem> {
        rule ?: error("Esta fuente no tiene esa lista")
        val (body, finalUrl) = fetch(url, rule.method)
        val doc = Jsoup.parse(body, finalUrl)
        return parseList(rule, body, doc).distinctBy { it.id }
    }

    /** Cards from a list page: JSON items when configured, else CSS cards. Pure: used by the tests with real pages. */
    fun parseList(rule: ListRule, body: String, doc: Document): List<MediaItem> {
        if (rule.json != null) {
            val items = Extract.path(data(body, doc), rule.json).mapNotNull { item ->
                val url = Extract.mapped(item, rule.map["url"], vars()).let { if (it.startsWith("/")) base + it else it }
                val title = Extract.mapped(item, rule.map["title"], vars())
                if (url.isBlank() || title.isBlank()) return@mapNotNull null
                MediaItem(
                    id = url, title = title, coverUrl = Extract.mapped(item, rule.map["cover"], vars()).let { if (it.startsWith("/")) base + it else it },
                    type = itemType(Extract.mapped(item, rule.map["type"], vars())), status = Extract.mapped(item, rule.map["status"], vars()),
                    description = "", sourceId = id
                )
            }
            if (items.isNotEmpty()) return items
        }
        val card = rule.card ?: config.card ?: CardRule()
        return doc.select(rule.item ?: return emptyList()).take(500).mapNotNull { el ->
            val url = Extract.value(el, card.link ?: "a@href").takeIf { SourceValidation.isHttpUrl(it) } ?: return@mapNotNull null
            val title = Extract.value(el, card.title ?: "a").ifBlank { el.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            MediaItem(
                id = url, title = title,
                coverUrl = Extract.image(card.cover?.let { el.selectFirst(it.substringBefore('@')) } ?: el.selectFirst("img"), imageRule),
                type = supportedTypes.first(), status = "", description = "", sourceId = id,
                altTitles = Extract.value(el, card.altTitles).split(",", ";", "/").map { it.trim() }.filter { it.isNotEmpty() }
            )
        }
    }

    private fun itemType(raw: String): MediaType =
        MediaType.entries.firstOrNull { it.name.equals(raw, true) || it.displayName.equals(raw, true) } ?: supportedTypes.first()

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        val rule = config.latest ?: error("Sin lista de novedades")
        val t = if (page > 1) rule.nextUrl ?: rule.url else rule.url
        list(rule, Extract.template(t, vars(mapOf("page" to page.toString()))))
    }

    override val adultLists: Int get() = config.adult.size

    override suspend fun adultTitles(list: Int, page: Int): Result<List<MediaItem>> = runCatching {
        val rule = config.adult.getOrNull(list) ?: return@runCatching emptyList()
        val t = if (page > 1) rule.nextUrl ?: rule.url else rule.url
        list(rule, Extract.template(t, vars(mapOf("page" to page.toString()))))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        val rule = config.search ?: error("Esta fuente no permite buscar")
        list(rule, Extract.template(rule.url, vars(mapOf("query" to Extract.encode(query), "page" to "1"))))
    }

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> {
        val rule = config.browse ?: return fetchLatestUpdates(page)
        return runCatching { list(rule, catalogueUrl(rule, page, values)) }
    }

    /** The catalogue URL for [values]; pure so it can be tested. */
    fun catalogueUrl(rule: ListRule, page: Int, values: FilterValues): String {
        val singles = filters.filterIsInstance<SelectFilter>().associate { f -> f.key to Extract.encode(values[f.key]?.firstOrNull() ?: f.default) }
        val url = Extract.template(rule.url, vars(singles + ("page" to page.coerceAtLeast(1).toString())))
        return url + rule.multi.entries.joinToString("") { (key, t) -> values[key].orEmpty().joinToString("") { Extract.template(t, mapOf("value" to Extract.encode(it))) } }
    }

    // ---------- Details ----------

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val (body, finalUrl) = fetch(media.id)
        parseDetails(body, Jsoup.parse(body, finalUrl), media)
    }

    /** Each field from the configured place, then ld+json, then og: tags. Pure. */
    fun parseDetails(body: String, doc: Document, media: MediaItem): MediaItem {
        val rule = config.details ?: DetailsRule()
        val json = rule.json?.let { Extract.path(data(body, doc), it).firstOrNull() }
        fun fromJson(field: String) = json?.let { Extract.mapped(it, rule.map[field], vars()) }.orEmpty()
        val ld = Extract.ldJson(doc).firstOrNull { o -> o.get("@type")?.let { t -> t.toString().contains("Book") || t.toString().contains("Series") || t.toString().contains("CreativeWork") } == true }
        fun ldString(name: String) = ld?.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val status = rule.status?.let { s ->
            doc.select(s.rows).firstOrNull { row -> s.match.any { m -> Extract.value(row, s.heading).contains(m, ignoreCase = true) } }?.let { Extract.value(it, s.value) }
        }.orEmpty()
        val genres = fromJson("genres").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { Extract.values(doc, rule.genres) }
        return media.copy(
            title = fromJson("title").ifBlank { Extract.value(doc, rule.title) }.ifBlank { ldString("name") }
                .ifBlank { Extract.meta(doc, "og:title") }.ifBlank { media.title },
            coverUrl = media.coverUrl.ifBlank {
                fromJson("cover").ifBlank { rule.cover?.let { Extract.image(doc.selectFirst(it.substringBefore('@')), imageRule) }.orEmpty() }
                    .ifBlank { Extract.meta(doc, "og:image") }
            },
            description = fromJson("description").ifBlank { Extract.value(doc, rule.description) }.ifBlank { ldString("description") }
                .ifBlank { Extract.meta(doc, "og:description") },
            author = fromJson("author").ifBlank { Extract.values(doc, rule.author).joinToString(", ") },
            genres = genres,
            status = fromJson("status").ifBlank { status }.ifBlank { media.status }
        )
    }

    // ---------- Chapters ----------

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val rule = config.chapters ?: ChaptersRule()
        val media = rebase(mediaId).let { if (it.endsWith("/")) it else "$it/" }
        val url = Extract.template(rule.url ?: "{media}", vars(mapOf("media" to media)))
        val (body, finalUrl) = fetch(url, rule.method)
        parseChapters(body, Jsoup.parse(body, finalUrl), mediaId).ifEmpty { throw NoChaptersException("Sin capítulos disponibles") }
    }

    /** Pure. */
    fun parseChapters(body: String, doc: Document, mediaId: String): List<Chapter> {
        val rule = config.chapters ?: ChaptersRule()
        if (rule.json != null) {
            val items = Extract.path(data(body, doc), rule.json)
            val parsed = items.mapIndexedNotNull { i, item ->
                val url = Extract.mapped(item, rule.map["url"], vars(mapOf("media" to mediaId.trimEnd('/')))).let { if (it.startsWith("/")) base + it else it }
                if (url.isBlank()) return@mapIndexedNotNull null
                val title = Extract.mapped(item, rule.map["title"], vars())
                val fallback = (if (rule.newestFirst) items.size - i else i + 1).toFloat()
                val number = Extract.mapped(item, rule.map["number"], vars()).toFloatOrNull() ?: SourceUtils.parseChapterNumber(title, fallback)
                val label = if (number % 1f == 0f) number.toInt().toString() else number.toString()
                Chapter(url, mediaId, title.ifBlank { "Capítulo $label" }, number, url, Extract.mapped(item, rule.map["date"], vars()))
            }
            if (parsed.isNotEmpty()) return parsed.distinctBy { it.id }
        }
        val items = doc.select(rule.item ?: return emptyList()).take(10_000)
        return items.mapIndexedNotNull { idx, el ->
            val url = Extract.value(el, rule.link ?: "a@href").takeIf { SourceValidation.isHttpUrl(it) } ?: return@mapIndexedNotNull null
            val title = Extract.value(el, rule.title ?: "a")
            val fallback = (if (rule.newestFirst) items.size - idx else idx + 1).toFloat()
            Chapter(
                id = url, mediaId = mediaId, title = title.ifBlank { "Capítulo ${fallback.toInt()}" },
                chapterNumber = SourceUtils.parseChapterNumber(title, fallback), url = url, dateUploaded = Extract.value(el, rule.date)
            )
        }.distinctBy { it.id }
    }

    // ---------- Content ----------

    /**
     * Chapter images, trying in order: the configured JSON, the configured selector, image URLs embedded in the
     * page's scripts, and the container with the most images. The first that looks like a real chapter wins. Pure.
     */
    fun parsePages(body: String, doc: Document): List<String> {
        val rule = config.pages ?: PagesRule()
        val candidates = sequence {
            rule.json?.let { path ->
                yield(Extract.path(data(body, doc), path).map { e -> if (e.isJsonPrimitive) e.asString else Extract.mapped(e, rule.field, vars()) }
                    .map { if (it.startsWith("/")) base + it else it })
            }
            rule.image?.let { sel -> yield(doc.select(sel.substringBefore('@')).map { Extract.image(it, imageRule) }.filter { it.isNotBlank() }) }
            yield(Extract.embeddedImages(doc))
            yield(Extract.heuristicPages(doc, imageRule))
        }
        return candidates.map { it.distinct() }.firstOrNull { it.isNotEmpty() && SourceValidation.pages(it).isEmpty() }.orEmpty()
    }

    suspend fun pagesOf(chapterUrl: String): List<String> {
        val (body, finalUrl) = fetch(chapterUrl)
        return parsePages(body, Jsoup.parse(body, finalUrl)).ifEmpty { error("El capítulo no tiene imágenes") }
    }

    /** Novel text: the configured container without ads and scripts, paragraphs separated by blank lines. Pure. */
    fun parseText(doc: Document): String {
        val rule = config.text ?: TextRule(content = "article, .entry-content, .chapter-content")
        val box = doc.selectFirst(rule.content) ?: return ""
        rule.remove.forEach { box.select(it).remove() }
        return SourceUtils.htmlToText(box.html())
    }
}

class DeclarativeMangaSource(config: SourceConfig, network: HttpFetcher, onDomainChange: (String, String, String) -> Unit) :
    DeclarativeSource(config, network, onDomainChange), MangaSource {
    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching { pagesOf(chapterUrl) }
}

class DeclarativeNovelSource(config: SourceConfig, network: HttpFetcher, onDomainChange: (String, String, String) -> Unit) :
    DeclarativeSource(config, network, onDomainChange), NovelSource {
    override suspend fun fetchChapterText(chapterUrl: String): Result<String> = runCatching {
        val (body, finalUrl) = fetch(chapterUrl)
        parseText(Jsoup.parse(body, finalUrl)).ifBlank { error("El capítulo no tiene texto") }
    }
}
