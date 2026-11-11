package com.nelson.yomukai.data.source

import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** One checked step. A failed [critical] step means the source is broken, not just a little off. */
data class DiagnosticStep(val name: String, val ok: Boolean, val detail: String, val critical: Boolean = true)

/**
 * Exercises a source end to end (latest, search, details, chapter list and the actual
 * pages/text/video of the first chapter) and reports each step. Stops at the first failure
 * because later steps depend on the earlier ones.
 */
object SourceDiagnostics {
    /** Fails the step with the validation problems, if any. */
    private fun problems(found: List<String>) = check(found.isEmpty()) { found.joinToString("; ") }

    private const val STEP_TIMEOUT_MS = 60_000L

    /** [checkImage] downloads an image like the reader would; pass it to also test covers and pages. */
    suspend fun run(
        source: CatalogueSource,
        checkImage: (suspend (String, Map<String, String>) -> String)? = null
    ): List<DiagnosticStep> {
        val steps = mutableListOf<DiagnosticStep>()

        suspend fun <T> step(name: String, describe: (T) -> String, critical: Boolean = true, block: suspend () -> Result<T>): T? {
            val outcome = try {
                withTimeout(STEP_TIMEOUT_MS) { block() }
            } catch (e: TimeoutCancellationException) {
                Result.failure(Exception("Tiempo de espera agotado"))
            }
            outcome.onSuccess { steps += DiagnosticStep(name, true, describe(it), critical) }
                .onFailure { steps += DiagnosticStep(name, false, it.message ?: it::class.java.simpleName, critical) }
            return outcome.getOrNull()
        }

        val latest = step<List<MediaItem>>("Novedades", { "${it.size} títulos" }) {
            source.fetchLatestUpdates(1).mapCatching { items -> items.also { problems(SourceValidation.latest(it)) } }
        } ?: return steps
        val first = latest.first()

        suspend fun imageStep(name: String, url: String) {
            val check = checkImage ?: return
            step<String>(name, { it }, critical = false) { runCatching { check(url, source.imageHeaders) } }
        }
        latest.firstOrNull { it.coverUrl.isNotBlank() }?.let { imageStep("Portada", it.coverUrl) }

        val word = first.title.split(' ').firstOrNull { it.length >= 4 } ?: first.title
        step<List<MediaItem>>("Búsqueda “$word”", { "${it.size} resultados" }, critical = false) {
            source.searchMedia(word).mapCatching { items -> items.also { problems(SourceValidation.search(it)) } }
        }

        step<MediaItem>("Detalle", { it.title }, critical = false) { source.fetchMediaDetails(first).mapCatching { it.also { d -> problems(SourceValidation.details(d)) } } }
        val chapters = step<List<Chapter>>("Capítulos", { "${it.size} capítulos" }) {
            chaptersOfSomeTitle(source, latest).mapCatching { list -> list.also { problems(SourceValidation.chapters(it)) } }
        } ?: return steps
        // Prefer the oldest chapter: it is the least likely to be paywalled or still processing.
        val chapter = chapters.minByOrNull { it.chapterNumber } ?: chapters.first()

        when (source) {
            is MangaSource -> step<List<String>>("Páginas", { "${it.size} imágenes" }) {
                source.fetchPageList(chapter.url).mapCatching { list -> list.also { problems(SourceValidation.pages(it)) } }
            }?.let { imageStep("Imagen de página", it.first()) }
            is NovelSource -> step<String>("Texto", { "${it.length} caracteres" }) {
                source.fetchChapterText(chapter.url).mapCatching { text -> text.also { problems(SourceValidation.text(it)) } }
            }
            is AnimeSource -> step<List<com.nelson.yomukai.domain.model.VideoSource>>("Servidores", { list ->
                "${list.size} servidores, ${list.count { it.isDirect }} directos"
            }) { source.fetchVideoSources(chapter.url).mapCatching { list -> list.also { problems(SourceValidation.videos(it)) } } }
            else -> Unit
        }
        return steps
    }

    /**
     * The chapters of one of the newest titles. A title with none yet (announced, still uploading) is not a broken
     * source: the next one is tried, up to [TITLES_TRIED]. Any other error (blocked, page changed) counts at once.
     */
    private suspend fun chaptersOfSomeTitle(source: CatalogueSource, latest: List<MediaItem>): Result<List<Chapter>> {
        var last: Result<List<Chapter>> = Result.failure(NoChaptersException())
        for (item in latest.take(TITLES_TRIED)) {
            last = source.fetchChapterList(item.id)
            val error = last.exceptionOrNull()
            if (error == null && last.getOrNull().orEmpty().isNotEmpty()) return last
            if (error != null && error !is NoChaptersException) return last
        }
        return last
    }

    private const val TITLES_TRIED = 3
}
