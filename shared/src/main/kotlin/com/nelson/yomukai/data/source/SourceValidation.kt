package com.nelson.yomukai.data.source

import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.VideoSource

/**
 * Plausibility checks on what a source returned. A site that changed its HTML rarely fails loudly: it returns an
 * empty list, a list of menu links, titles of "Cargando...", or relative junk URLs. These rules catch that, so a
 * broken source is hidden instead of showing garbage. Each check returns the problems found; empty = fine.
 */
object SourceValidation {
    const val MIN_LATEST = 3
    const val MIN_TEXT_CHARS = 200

    fun isHttpUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return (u.startsWith("https://") || u.startsWith("http://")) && u.length > 10 && !u.contains(' ')
    }

    private fun badTitle(title: String): Boolean {
        val t = title.trim()
        return t.isEmpty() || t.length > 300 || PLACEHOLDERS.any { t.equals(it, ignoreCase = true) }
    }
    private val PLACEHOLDERS = listOf("loading", "loading...", "cargando", "cargando...", "undefined", "null", "sin título", "untitled")

    fun latest(items: List<MediaItem>): List<String> = buildList {
        if (items.size < MIN_LATEST) add("Solo ${items.size} títulos en novedades (mínimo $MIN_LATEST)")
        val badTitles = items.count { badTitle(it.title) }
        if (items.isNotEmpty() && badTitles * 5 > items.size) add("$badTitles de ${items.size} títulos vacíos o de relleno")
        if (items.isNotEmpty() && items.map { it.id }.distinct().size * 2 < items.size) add("Títulos repetidos: la lista no parece real")
        val covers = items.map { it.coverUrl }.filter { it.isNotBlank() }
        if (covers.isNotEmpty() && covers.count { !isHttpUrl(it) } * 2 > covers.size) add("Portadas con direcciones no válidas")
    }

    fun search(items: List<MediaItem>): List<String> = buildList {
        if (items.isEmpty()) add("La búsqueda no devuelve nada")
        else if (items.all { badTitle(it.title) }) add("La búsqueda devuelve títulos vacíos")
    }

    fun details(item: MediaItem): List<String> = if (badTitle(item.title)) listOf("La ficha no tiene título") else emptyList()

    fun chapters(list: List<Chapter>): List<String> = buildList {
        if (list.isEmpty()) {
            add("Sin capítulos")
            return@buildList
        }
        if (list.count { it.url.isBlank() && it.id.isBlank() } * 5 > list.size) add("Capítulos sin dirección")
        if (list.map { it.id }.distinct().size * 2 < list.size) add("Capítulos repetidos")
    }

    fun pages(urls: List<String>): List<String> = buildList {
        if (urls.isEmpty()) add("Sin imágenes")
        else if (urls.count { !isHttpUrl(it) && !it.startsWith("data:") } * 5 > urls.size) add("Imágenes con direcciones no válidas")
    }

    fun text(text: String): List<String> = if (text.trim().length < MIN_TEXT_CHARS) listOf("Texto demasiado corto (${text.trim().length} caracteres)") else emptyList()

    fun videos(list: List<VideoSource>): List<String> = if (list.isEmpty()) listOf("Sin servidores de vídeo") else emptyList()
}

/** What the canary concludes about a source from its diagnostic steps. */
enum class SourceHealth { OK, WARNING, DEGRADED }

object SourceVerdict {
    /** A failed critical step (lists, chapters, content) hides the source; a failed search or image only warns. */
    fun of(steps: List<DiagnosticStep>): SourceHealth = when {
        steps.isEmpty() -> SourceHealth.DEGRADED
        steps.any { !it.ok && it.critical } -> SourceHealth.DEGRADED
        steps.any { !it.ok } -> SourceHealth.WARNING
        else -> SourceHealth.OK
    }

    fun summary(steps: List<DiagnosticStep>): String =
        steps.filter { !it.ok }.joinToString("; ") { "${it.name}: ${it.detail}" }.ifEmpty { "Todo bien" }
}
