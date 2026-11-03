package com.nelson.yomukai.data.source

import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class DiagnosticStep(val name: String, val ok: Boolean, val detail: String)

/**
 * Exercises a source end to end (latest, search, details, chapter list and the actual
 * pages/text/video of the first chapter) and reports each step. Stops at the first failure
 * because later steps depend on the earlier ones.
 */
object SourceDiagnostics {
    private const val STEP_TIMEOUT_MS = 60_000L

    /** [checkImage] downloads an image like the reader would; pass it to also test covers and pages. */
    suspend fun run(
        source: CatalogueSource,
        checkImage: (suspend (String, Map<String, String>) -> String)? = null
    ): List<DiagnosticStep> {
        val steps = mutableListOf<DiagnosticStep>()

        suspend fun <T> step(name: String, describe: (T) -> String, block: suspend () -> Result<T>): T? {
            val outcome = try {
                withTimeout(STEP_TIMEOUT_MS) { block() }
            } catch (e: TimeoutCancellationException) {
                Result.failure(Exception("Tiempo de espera agotado"))
            }
            outcome.onSuccess { steps += DiagnosticStep(name, true, describe(it)) }
                .onFailure { steps += DiagnosticStep(name, false, it.message ?: it::class.java.simpleName) }
            return outcome.getOrNull()
        }

        val latest = step<List<MediaItem>>("Novedades", { "${it.size} títulos" }) {
            source.fetchLatestUpdates(1).mapCatching { items -> items.also { check(it.isNotEmpty()) { "Lista vacía" } } }
        } ?: return steps
        val first = latest.first()

        suspend fun imageStep(name: String, url: String) {
            val check = checkImage ?: return
            step<String>(name, { it }) { runCatching { check(url, source.imageHeaders) } }
        }
        latest.firstOrNull { it.coverUrl.isNotBlank() }?.let { imageStep("Portada", it.coverUrl) }

        val word = first.title.split(' ').firstOrNull { it.length >= 4 } ?: first.title
        step<List<MediaItem>>("Búsqueda “$word”", { "${it.size} resultados" }) {
            source.searchMedia(word).mapCatching { items -> items.also { check(it.isNotEmpty()) { "Sin resultados" } } }
        }

        step<MediaItem>("Detalle", { it.title }) { source.fetchMediaDetails(first) }
        val chapters = step<List<Chapter>>("Capítulos", { "${it.size} capítulos" }) {
            source.fetchChapterList(first.id).mapCatching { list -> list.also { check(it.isNotEmpty()) { "Lista vacía" } } }
        } ?: return steps
        // Prefer the oldest chapter: it is the least likely to be paywalled or still processing.
        val chapter = chapters.minByOrNull { it.chapterNumber } ?: chapters.first()

        when (source) {
            is MangaSource -> step<List<String>>("Páginas", { "${it.size} imágenes" }) {
                source.fetchPageList(chapter.url).mapCatching { list -> list.also { check(it.isNotEmpty()) { "Sin imágenes" } } }
            }?.let { imageStep("Imagen de página", it.first()) }
            is NovelSource -> step<String>("Texto", { "${it.length} caracteres" }) {
                source.fetchChapterText(chapter.url).mapCatching { text -> text.also { check(it.isNotBlank()) { "Texto vacío" } } }
            }
            is AnimeSource -> step<List<com.nelson.yomukai.domain.model.VideoSource>>("Servidores", { list ->
                "${list.size} servidores, ${list.count { it.isDirect }} directos"
            }) { source.fetchVideoSources(chapter.url) }
            else -> Unit
        }
        return steps
    }
}
