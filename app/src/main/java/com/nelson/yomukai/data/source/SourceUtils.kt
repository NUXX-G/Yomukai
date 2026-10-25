package com.nelson.yomukai.data.source

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
        return blocks.map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    fun normalizeParagraphs(text: String): String =
        text.replace("\r\n", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
}
