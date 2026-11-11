package com.nelson.yomukai.ui.home

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.local.HistoryWithMedia
import com.nelson.yomukai.data.source.CatalogueSource
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

enum class TypeFilter(val label: String, val types: Set<MediaType>) {
    // Same tabs and order as the web (F3.3).
    ALL(tr("Todo"), MediaType.entries.toSet()),
    ANIME(tr("Anime"), setOf(MediaType.ANIME)),
    MANGA(tr("Manga"), setOf(MediaType.MANGA)),
    MANHWA(tr("Manhwa y manhua"), setOf(MediaType.MANHWA, MediaType.MANHUA)),
    COMIC(tr("Cómics"), setOf(MediaType.COMIC)),
    NOVEL(tr("Novelas"), setOf(MediaType.NOVEL))
}

enum class LanguageFilter(val label: String) { ALL(tr("Todos")), ES(tr("Español")), EN(tr("Inglés")), ZH(tr("Chino")) }

fun CatalogueSource.matches(language: LanguageFilter) =
    language == LanguageFilter.ALL || this.language == "MULTI" || this.language == language.name

@Immutable
data class SourceSection(
    val sourceId: String,
    val sourceName: String,
    val language: String,
    val types: Set<MediaType>,
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

/**
 * One row of the home screen by work, never by source: everything the sources published lately, merged and with one
 * card per work (a title on three sources is one card that says so). Sources stay behind "Explorar por fuente".
 */
@Immutable
data class WorkRow(val id: String, val title: String, val cards: List<com.nelson.yomukai.data.work.WorkCard>)

@Immutable
data class HomeUiState(
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val languageFilter: LanguageFilter = LanguageFilter.ALL,
    val sections: List<SourceSection> = emptyList(),
    val rows: List<WorkRow> = emptyList(),
    /** Still waiting for some source (rows fill in as they answer). */
    val loadingSources: Boolean = false,
    /** Sources that could not answer, by name (shown as one quiet line). */
    val failedSources: List<String> = emptyList(),
    val continueReading: List<HistoryWithMedia> = emptyList(),
    val isRefreshing: Boolean = false
)

private const val SOURCE_TIMEOUT_MS = 40_000L
private const val ROW_SIZE = 40

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val filters = MutableStateFlow(TypeFilter.ALL to LanguageFilter.ALL)
    private val rawSections = MutableStateFlow<List<SourceSection>>(emptyList())
    private val refreshing = MutableStateFlow(true) // the first load starts right away
    private var loadJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        filters, rawSections, refreshing, container.libraryRepository.observeHistory()
    ) { (type, language), sections, isRefreshing, history ->
        val visible = sections
            .filter { s -> (language == LanguageFilter.ALL || s.language == "MULTI" || s.language == language.name) }
            .filter { s -> s.types.any { it in type.types } }
            .map { s -> s.copy(items = s.items.filter { it.type in type.types }) }
            .filter { it.isLoading || it.error != null || it.items.isNotEmpty() }
        HomeUiState(
            typeFilter = type,
            languageFilter = language,
            sections = visible,
            rows = workRows(visible),
            loadingSources = visible.any { it.isLoading },
            failedSources = visible.filter { it.error != null }.map { it.sourceName },
            continueReading = history.take(12),
            isRefreshing = isRefreshing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(isRefreshing = true))

    init {
        // Reload whenever the set of enabled sources changes.
        viewModelScope.launch {
            container.preferences.app
                .map { it.enabledSources }
                .distinctUntilChanged()
                .collect { refresh() }
        }
        // Saved titles may include what was just excluded.
        viewModelScope.launch {
            container.preferences.exclusions.drop(1).collect {
                rawSections.value = emptyList()
                container.homeCache.save(emptyMap())
                refresh()
            }
        }
    }

    /** Rows by work: all the latest together (one source's titles interleaved with the next), then one row per kind. */
    private fun workRows(sections: List<SourceSection>): List<WorkRow> {
        val lists = sections.map { it.items }
        val interleaved = buildList { for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.let(::add) } }
        fun row(id: String, title: String, types: Set<MediaType>) =
            WorkRow(id, title, com.nelson.yomukai.data.work.WorkGrouper.group(interleaved.filter { it.type in types }).take(ROW_SIZE))
        return listOf(
            row("latest", tr("Recién actualizado"), MediaType.entries.toSet()),
            row("anime", tr("Anime nuevo"), setOf(MediaType.ANIME)),
            row("manga", tr("Manga nuevo"), setOf(MediaType.MANGA)),
            row("manhwa", tr("Manhwa y manhua nuevos"), setOf(MediaType.MANHWA, MediaType.MANHUA)),
            row("comics", tr("Cómics nuevos"), setOf(MediaType.COMIC)),
            row("novels", tr("Novelas nuevas"), setOf(MediaType.NOVEL))
        ).filter { it.cards.isNotEmpty() }
    }

    fun setTypeFilter(filter: TypeFilter) = filters.update { it.copy(first = filter) }
    fun setLanguageFilter(filter: LanguageFilter) = filters.update { it.copy(second = filter) }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            refreshing.value = true
            val settings = container.preferences.app.value
            val sources = container.sourceManager.enabled(settings.enabledSources)
            // Whatever is on screen, or was saved last time, stays visible while each source reloads.
            val shown = rawSections.value.associate { it.sourceId to it.items }.filterValues { it.isNotEmpty() }
                .ifEmpty { container.homeCache.load() }
            rawSections.value = sources.map { s ->
                val known = shown[s.id].orEmpty()
                SourceSection(s.id, s.name, s.language, s.supportedTypes, items = known, isLoading = known.isEmpty())
            }

            val jobs = sources.map { source ->
                async {
                    val result = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                        source.fetchLatestUpdates(1).mapCatching { container.contentFilter.screen(source, it) }
                    } ?: Result.failure(IOException(tr("{0} tardó demasiado en responder", source.name)))
                    updateSection(source.id) {
                        val fresh = result.getOrDefault(emptyList())
                        val failure = result.exceptionOrNull()?.also { e -> Log.w("YomukaiHome", "Fuente ${source.id} falló", e) }
                        it.copy(
                            items = fresh.ifEmpty { it.items },
                            isLoading = false,
                            // Old titles beat an error message; a real error only shows when there is nothing else.
                            error = failure?.takeIf { _ -> it.items.isEmpty() }?.let { e -> e.message ?: tr("Error de conexión") }
                        )
                    }
                }
            }
            jobs.forEach { it.await() }
            container.homeCache.save(rawSections.value.associate { it.sourceId to it.items })
            refreshing.value = false
            // Titles whose genres no list gave are checked after everything is on screen, and the saved
            // copy is written again without them, so they never show up on the next start.
            sources.map { source ->
                launch {
                    val shown = rawSections.value.firstOrNull { it.sourceId == source.id }?.items.orEmpty()
                    val hidden = container.contentFilter.check(source, shown)
                    if (hidden.isNotEmpty()) updateSection(source.id) { s -> s.copy(items = s.items.filterNot { it.id in hidden }) }
                }
            }.forEach { it.join() }
            container.homeCache.save(rawSections.value.associate { it.sourceId to it.items })
        }
    }

    private fun updateSection(id: String, transform: (SourceSection) -> SourceSection) {
        rawSections.update { list -> list.map { if (it.sourceId == id) transform(it) else it } }
    }
}
