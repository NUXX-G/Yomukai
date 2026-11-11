package com.nelson.yomukai.data.source

import kotlinx.coroutines.async
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

object SourceUtils {
    private val numberAfterKeyword = Regex(
        """(?i)(?:cap[ií]tulo|chapter|chap|ch\.?|cap\.?|episodio|ep\.?|#)\s*(\d+(?:[.,]\d+)?)"""
    )
    private val anyNumber = Regex("""(\d+(?:[.,]\d+)?)""")

    /** Extracts 12.5 from titles such as "Capítulo 12.5 - El regreso". */
    fun parseChapterNumber(title: String, fallback: Float): Float {
        val match = numberAfterKeyword.find(title) ?: anyNumber.find(title)
        return match?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: fallback
    }

    fun encode(query: String): String = URLEncoder.encode(query.trim(), "UTF-8")

    /** Picks the real image URL from lazy-loading attributes used by most WordPress themes. */
    fun imageUrl(img: Element?): String {
        if (img == null) return ""
        val candidates = listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "src")
        for (attr in candidates) {
            val value = img.absUrl(attr).ifBlank { img.attr(attr) }.trim()
            if (value.isNotBlank() && !value.startsWith("data:")) return value
        }
        val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }
        return srcset.split(",").lastOrNull()?.trim()?.substringBefore(" ").orEmpty()
    }

    /** Converts an HTML fragment into paragraphs separated by blank lines. */
    fun htmlToText(html: String): String {
        if (!html.contains('<')) return normalizeParagraphs(html)
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("script, style, ins, .ads, .adsbygoogle").remove()
        val blocks = doc.select("p, h1, h2, h3, h4, blockquote, li")
        if (blocks.isEmpty()) return normalizeParagraphs(doc.body().wholeText())
        return blocks.map { it.text().replace(invisible, "").trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** Invisible characters some sites hide in their text as a watermark (thousands per chapter). */
    private val invisible = Regex("[\\u200B\\u200C\\u200E\\u200F\\u2060-\\u2064\\uFEFF]")

    private val mdHeading = Regex("^#{1,6}\\s+")
    private val mdBold = Regex("\\*\\*(\\S(?:.*?\\S)?)\\*\\*")
    private val mdUnderline = Regex("__(\\S(?:.*?\\S)?)__")
    private val mdItalic = Regex("(?<![*\\w])\\*([^*\\s](?:.*?[^*\\s])?)\\*(?![*\\w])")
    private val mdEscape = Regex("\\\\([\\\\`*_\\[\\]()#+.!>~|-])")

    /**
     * Plain text from a chapter some sites write in Markdown (SkyNovels: «**\[Misión…\]**»): drops headings, bold,
     * italics and backslash escapes, keeping scene breaks such as «***» or «* * *» as they are.
     */
    fun markdownToText(text: String): String =
        normalizeParagraphs(text).split("\n\n").map { line ->
            var s = mdHeading.replace(line, "")
            s = mdBold.replace(s, "$1")
            s = mdUnderline.replace(s, "$1")
            s = mdItalic.replace(s, "$1")
            mdEscape.replace(s, "$1").trim()
        }.filter { it.isNotEmpty() }.joinToString("\n\n")

    fun normalizeParagraphs(text: String): String =
        text.replace(invisible, "").replace("\r\n", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
}

/**
 * Pages 2..[last] of a paged listing, fetched [parallel] at a time instead of one after another; the
 * result keeps page order. Page 1 is fetched by the caller first, since it tells how many there are.
 */
suspend fun <T> fetchRemainingPages(last: Int, parallel: Int = 4, fetch: suspend (Int) -> T): List<T> = kotlinx.coroutines.coroutineScope {
    if (last < 2) return@coroutineScope emptyList()
    val limit = kotlinx.coroutines.sync.Semaphore(parallel)
    (2..last).map { page -> async { limit.withPermitCompat { fetch(page) } } }.map { it.await() }
}

private suspend fun <T> kotlinx.coroutines.sync.Semaphore.withPermitCompat(block: suspend () -> T): T {
    acquire()
    try { return block() } finally { release() }
}
