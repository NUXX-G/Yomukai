package com.nelson.yomukai.data.source.declarative

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * The pure pieces of the declarative engines. Everything read from a site is untrusted data: it is only ever matched,
 * parsed and bounded, never executed or used to build anything but plain values.
 */
object Extract {
    private const val MAX_ITEMS = 2_000

    // ---------- CSS ----------

    /** "a.title" → text; "a.title@href" → absolute attribute; "@src" alone → attribute of [root] itself. */
    fun value(root: Element, rule: String?): String {
        if (rule.isNullOrBlank()) return ""
        val (selector, attr) = split(rule)
        val el = if (selector.isEmpty()) root else root.selectFirst(selector) ?: return ""
        return read(el, attr)
    }

    fun values(root: Element, rule: String?): List<String> {
        if (rule.isNullOrBlank()) return emptyList()
        val (selector, attr) = split(rule)
        val els = if (selector.isEmpty()) listOf(root) else root.select(selector).take(MAX_ITEMS)
        return els.map { read(it, attr) }.filter { it.isNotEmpty() }
    }

    private fun split(rule: String): Pair<String, String?> {
        val at = rule.lastIndexOf('@')
        // "@" inside an attribute selector ([href*=@]) is not the attribute marker.
        return if (at >= 0 && !rule.substring(at).contains(']')) rule.substring(0, at).trim() to rule.substring(at + 1).trim() else rule.trim() to null
    }

    private fun read(el: Element, attr: String?): String = when {
        attr == null -> el.text().trim()
        attr == "own" -> el.ownText().trim()
        attr == "html" -> el.html()
        attr in URL_ATTRS -> el.absUrl(attr).ifBlank { el.attr(attr).trim() }
        else -> el.attr(attr).trim()
    }
    private val URL_ATTRS = setOf("href", "src", "data-src", "data-lazy-src", "data-original", "poster")

    /** The real image of an <img> (lazy loading hides it in data-src), cleaned. */
    fun image(el: Element?, rule: ImageRule): String {
        if (el == null) return ""
        val raw = rule.attrs.asSequence().map { el.absUrl(it).ifBlank { el.attr(it).trim() } }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }.orEmpty()
        val url = raw.trim().let { if (rule.forceHttps) it.replaceFirst("http://", "https://") else it }
        return if (url.isBlank() || rule.ignore.any { url.contains(it) }) "" else url
    }

    // ---------- Templates ----------

    /** Fills "{name}" placeholders; unknown ones become empty. Values are inserted as given (encode beforehand). */
    fun template(t: String, vars: Map<String, String>): String =
        Regex("\\{([a-zA-Z0-9_]+)}").replace(t) { m -> vars[m.groupValues[1]].orEmpty() }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------- JSON ----------

    /** "a.b[0].c", "a.items[*]" (all elements), "" (the element itself). */
    fun path(root: JsonElement?, path: String?): List<JsonElement> {
        if (root == null || root is JsonNull) return emptyList()
        if (path.isNullOrBlank()) return listOf(root)
        var current = listOf(root)
        for (part in Regex("[^.\\[\\]]+|\\[(\\*|\\d+)]").findAll(path).map { it.value }) {
            current = current.flatMap { node ->
                when {
                    part == "[*]" -> (node as? JsonArray)?.toList() ?: (node as? JsonObject)?.entrySet()?.map { it.value } ?: emptyList()
                    part.startsWith("[") -> listOfNotNull((node as? JsonArray)?.let { a -> part.trim('[', ']').toInt().takeIf { it < a.size() }?.let { a[it] } })
                    else -> listOfNotNull((node as? JsonObject)?.get(part)?.takeUnless { it is JsonNull })
                }
            }.take(MAX_ITEMS)
            if (current.isEmpty()) break
        }
        return current
    }

    fun string(root: JsonElement?, path: String?): String =
        path(root, path).firstOrNull()?.let { node ->
            when {
                node.isJsonPrimitive -> node.asString
                node.isJsonArray -> node.asJsonArray.joinToString(", ") { e -> if (e.isJsonPrimitive) e.asString else "" }
                else -> ""
            }
        }?.trim().orEmpty()

    /** A map value is a path ("title") or a template with {paths} inside ("{base}/serie/{slug}"). */
    fun mapped(item: JsonElement, spec: String?, vars: Map<String, String>): String {
        if (spec.isNullOrBlank()) return ""
        if (!spec.contains('{')) return string(item, spec)
        return Regex("\\{([^}]+)}").replace(spec) { m -> vars[m.groupValues[1]] ?: string(item, m.groupValues[1]) }
    }

    /** Next.js pages carry their data in <script id="__NEXT_DATA__">. */
    fun nextData(doc: Document): JsonElement? =
        doc.selectFirst("script#__NEXT_DATA__")?.data()?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }

    /**
     * SvelteKit's `__data.json`: an object whose "nodes" each hold a "data" array in devalue format. The nodes are
     * decoded and merged (layout first, page last), so paths can reach the page's data directly.
     */
    fun svelteKitData(text: String): JsonElement? = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        val merged = JsonObject()
        root.getAsJsonArray("nodes")?.forEach { node ->
            val data = (node as? JsonObject)?.getAsJsonArray("data") ?: return@forEach
            (devalue(data) as? JsonObject)?.entrySet()?.forEach { (k, v) -> merged.add(k, v) }
        }
        merged
    }.getOrNull()

    /**
     * Decodes Rich Harris's "devalue" flat format: index 0 is the root; objects and arrays hold indices of their
     * members; negative numbers are special values. Cycles and deep nesting are cut off.
     */
    fun devalue(flat: JsonArray): JsonElement = Devalue(flat).hydrate(0, 0)

    /** One decoding pass over a devalue array; values referenced twice are decoded once. */
    private class Devalue(private val flat: JsonArray) {
        private val cache = HashMap<Int, JsonElement>()

        fun hydrate(index: Int, depth: Int): JsonElement {
            if (index < 0 || index >= flat.size() || depth > 64) return JsonNull.INSTANCE
            cache[index]?.let { return it }
            val raw = flat[index]
            val out = when {
                raw.isJsonArray -> array(raw.asJsonArray, depth)
                raw.isJsonObject -> obj(raw.asJsonObject, depth)
                else -> raw
            }
            cache[index] = out
            return out
        }

        /** A member is the index of its value; anything else (negative specials, junk) becomes null. */
        private fun ref(e: JsonElement, depth: Int): JsonElement =
            if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber && e.asInt >= 0) hydrate(e.asInt, depth + 1) else JsonNull.INSTANCE

        private fun array(arr: JsonArray, depth: Int): JsonElement = when (arr.firstOrNull()?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString) {
            "Date", "BigInt" -> JsonPrimitive(arr[1].asString)
            "Set" -> JsonArray().also { a -> arr.drop(1).forEach { a.add(ref(it, depth)) } }
            "Map" -> JsonObject().also { o ->
                arr.drop(1).chunked(2).filter { it.size == 2 }.forEach { (k, v) ->
                    o.add(ref(k, depth).let { if (it.isJsonPrimitive) it.asString else "" }, ref(v, depth))
                }
            }
            else -> JsonArray().also { a -> arr.forEach { a.add(ref(it, depth)) } }
        }

        private fun obj(o: JsonObject, depth: Int): JsonElement = JsonObject().also { out -> o.entrySet().forEach { (k, v) -> out.add(k, ref(v, depth)) } }
    }

    // ---------- Fallbacks ----------

    /** Every schema.org object in ld+json blocks (flattening @graph). */
    fun ldJson(doc: Document): List<JsonObject> = doc.select("script[type=application/ld+json]").flatMap { s ->
        val parsed = runCatching { JsonParser.parseString(s.data()) }.getOrNull()
        val all = when {
            parsed == null -> emptyList()
            parsed.isJsonArray -> parsed.asJsonArray.filterIsInstance<JsonObject>()
            parsed.isJsonObject -> listOf(parsed.asJsonObject)
            else -> emptyList()
        }
        all.flatMap { o -> listOf(o) + (o.getAsJsonArray("@graph")?.filterIsInstance<JsonObject>() ?: emptyList()) }
    }

    fun meta(doc: Document, property: String): String =
        doc.selectFirst("meta[property=$property], meta[name=$property]")?.attr("content")?.trim().orEmpty()

    private val IMAGE_URL = Regex("""https?:\\?/\\?/[^"'\s<>]+?\.(?:jpe?g|png|webp|avif|gif)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)

    /**
     * Chapter images written into a script as JSON (many readers do this): the longest run of image URLs that share
     * a folder. Covers, avatars and banners elsewhere in the page rarely share the folder with 3+ pages.
     */
    fun embeddedImages(doc: Document): List<String> {
        val urls = doc.select("script").flatMap { s -> IMAGE_URL.findAll(s.data()).map { it.value.replace("\\/", "/") }.toList() }.distinct()
        return urls.groupBy { it.substringBeforeLast('/') }.values.filter { it.size >= 3 }.maxByOrNull { it.size }.orEmpty()
    }

    /**
     * Last resort for chapter pages: the element with the most <img> children (direct or one level down) is the
     * reader. Navigation, ads and thumbnails are scattered; a chapter is a run of images in one container.
     */
    fun heuristicPages(doc: Document, rule: ImageRule): List<String> {
        val byParent = doc.select("img").groupBy { img -> img.parent()?.takeIf { p -> p.tagName() != "a" } ?: img.parent()?.parent() }
        val best = byParent.entries.filter { it.key != null }.maxByOrNull { it.value.size } ?: return emptyList()
        val groups = if (best.value.size >= 3) best.value else {
            // One image per <div> (very common): group by grandparent instead.
            doc.select("img").groupBy { it.parent()?.parent() }.entries.filter { it.key != null }.maxByOrNull { it.value.size }?.value.orEmpty()
        }
        return groups.takeIf { it.size >= 3 }?.map { image(it, rule) }?.filter { it.isNotBlank() }?.distinct().orEmpty()
    }
}
