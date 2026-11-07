package com.nelson.yomukai.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.discovery.Alternative
import com.nelson.yomukai.data.discovery.MatchConfidence
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.components.AppSpinner
import com.nelson.yomukai.ui.components.LanguageTag
import com.nelson.yomukai.ui.components.MediaCard
import com.nelson.yomukai.ui.components.SectionHeader

/** Row of titles like this one. Loads when it first comes into view and disappears when there is nothing to show. */
@Composable
fun SimilarSection(state: SimilarState, onLoad: () -> Unit, onOpen: (RelatedTitle) -> Unit) {
    LaunchedEffect(Unit) { onLoad() }
    if (!state.loading && state.items.isEmpty()) return
    Column {
        SectionHeader(tr("Similares"))
        if (state.loading) {
            AppSpinner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).size(24.dp), strokeWidth = 2.5.dp)
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Different works can share a name, so the position is what identifies each card.
                itemsIndexed(state.items, key = { index, _ -> index }) { _, related ->
                    MediaCard(
                        item = MediaItem(related.title, related.title, related.coverUrl, related.type, "", "", related.item?.sourceId.orEmpty()),
                        onClick = { onOpen(related) },
                        modifier = Modifier.width(110.dp)
                    )
                }
            }
        }
    }
}

/** "episodio" or "capítulos", agreeing with [count]. */
internal fun chapterNoun(count: Int, isAnime: Boolean) = when {
    isAnime -> if (count == 1) "episodio" else tr("episodios")
    else -> if (count == 1) tr("capítulo") else tr("capítulos")
}

/** "12 capítulos • hasta el 12,5". */
private fun chaptersLine(alternative: Alternative, isAnime: Boolean): String {
    val count = alternative.chapterCount ?: return tr("No se pudo leer la lista de ") + chapterNoun(2, isAnime)
    val last = alternative.lastChapter?.takeIf { it > 0f }?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
    return "$count ${chapterNoun(count, isAnime)}" + (last?.let { tr(" • hasta el {0}", it) } ?: "")
}

@Composable
private fun AlternativeRow(alternative: Alternative, isAnime: Boolean, onClick: () -> Unit) {
    val sure = alternative.confidence == MatchConfidence.SAME_WORK
    ListItem(
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(alternative.sourceName)
                LanguageTag(alternative.language)
                if (sure) Icon(Icons.Default.Verified, tr("Misma obra"), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = {
            Column {
                Text(alternative.item.title, maxLines = 1)
                Text(chaptersLine(alternative, isAnime), color = MaterialTheme.colorScheme.primary)
                if (!sure) {
                    Text(
                        if (alternative.confidence == MatchConfidence.DIFFERENT_AUTHOR) tr("Mismo título, otro autor: puede ser otra obra")
                        else tr("Mismo título, sin confirmar que sea la misma obra"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** The same work in the user's other sources, with how far each one goes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlternativesSheet(
    state: AlternativesState,
    currentSource: String,
    currentCount: Int,
    isAnime: Boolean,
    onOpen: (MediaItem) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(tr("Otras fuentes"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                tr("Aquí, en {0}: {1} {2}", currentSource, currentCount, chapterNoun(currentCount, isAnime)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            if (state.searching) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppSpinner(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(tr("Buscando en tus fuentes..."), style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.items.isEmpty()) {
                Text(
                    tr("No está en ninguna otra de tus fuentes activas."),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(state.items, key = { it.item.sourceId + it.item.id }) { alternative ->
                    AlternativeRow(alternative, isAnime, onClick = { onOpen(alternative.item) })
                }
            }
        }
    }
}

/** Sources holding a recommended title; [state] has more than one or is still searching. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocateSheet(
    state: LocateState,
    onOpen: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    onSearch: ((String) -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(state.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            when {
                state.searching -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppSpinner(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(tr("Buscando en tus fuentes..."), style = MaterialTheme.typography.bodyMedium)
                }
                state.options.isEmpty() -> Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(
                        tr("Ninguna de tus fuentes activas la tiene con este nombre."),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (onSearch != null) {
                        TextButton(onClick = { onSearch(state.title) }, modifier = Modifier.padding(top = 4.dp)) {
                            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                            Text(tr("Buscar «{0}» en todas", state.title), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                        }
                    }
                }
                else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(state.options, key = { it.item.sourceId + it.item.id }) { option ->
                        ListItem(
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            headlineContent = { Text(option.sourceName) },
                            supportingContent = {
                                Column {
                                    Text(option.item.title, maxLines = 1)
                                    if (option.approximate) {
                                        Text(
                                            tr("Nombre parecido: comprueba que es la misma obra"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            trailingContent = { LanguageTag(option.language) },
                            modifier = Modifier.clickable { onOpen(option.item) }
                        )
                    }
                }
            }
        }
    }
}
