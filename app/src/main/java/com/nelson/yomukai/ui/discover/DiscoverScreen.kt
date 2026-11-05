package com.nelson.yomukai.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nelson.yomukai.data.discovery.RandomKind
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.ui.components.AppSpinner
import com.nelson.yomukai.ui.components.CoverImage
import com.nelson.yomukai.ui.components.LanguageTag
import com.nelson.yomukai.ui.components.MediaCard
import com.nelson.yomukai.ui.components.SectionHeader
import com.nelson.yomukai.ui.components.TypeBadge
import com.nelson.yomukai.ui.details.LocateSheet

@Composable
fun RelatedCard(related: RelatedTitle, onOpen: (RelatedTitle) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    MediaCard(
        item = MediaItem(related.title, related.title, related.coverUrl, related.type, "", "", related.item?.sourceId.orEmpty()),
        onClick = { onOpen(related) },
        modifier = modifier,
        subtitle = subtitle ?: related.format.ifBlank { null },
        showType = true
    )
}

/**
 * Opens what [DiscoverViewModel.open] located: one place opens at once, several or none ask. With
 * none, the global search is offered with the title's name.
 */
@Composable
fun LocateHandler(viewModel: DiscoverViewModel, onOpenMedia: (MediaItem) -> Unit, onSearch: (String) -> Unit) {
    val locate by viewModel.locate.collectAsStateWithLifecycle()
    locate?.let { found ->
        val only = found.options.singleOrNull()?.takeIf { !it.approximate }
        if (!found.searching && only != null) {
            LaunchedEffect(only) { viewModel.closeLocate(); onOpenMedia(only.item) }
        } else {
            LocateSheet(
                found,
                onOpen = { viewModel.closeLocate(); onOpenMedia(it) },
                onDismiss = viewModel::closeLocate,
                onSearch = { viewModel.closeLocate(); onSearch(it) }
            )
        }
    }
}

/** "Para ti" row of the home screen: recommendations from what the user read, watched and kept. */
@Composable
fun ForYouSection(viewModel: DiscoverViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (!state.loading && state.forYou.isEmpty()) return
    Column {
        SectionHeader("Para ti")
        if (state.loading) {
            AppSpinner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).size(28.dp), strokeWidth = 3.dp)
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Different works can share a name, so the position is what identifies each card.
                itemsIndexed(state.forYou, key = { index, _ -> index }) { _, related ->
                    RelatedCard(related, viewModel::open, Modifier.width(120.dp))
                }
            }
        }
    }
}

/** Random pick with optional type and genre, opened from the shuffle button of the home screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShuffleSheet(viewModel: DiscoverViewModel, onOpenMedia: (MediaItem) -> Unit, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state.random == null && !state.rolling) viewModel.rollRandom() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                "Descubrir al azar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RandomKind.entries) { kind ->
                    FilterChip(selected = state.kind == kind, onClick = { viewModel.setKind(kind) }, label = { Text(kind.label) })
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = state.genre == null, onClick = { viewModel.setGenre(null) }, label = { Text("Cualquier género") }) }
                items(RANDOM_GENRES) { (value, label) ->
                    FilterChip(selected = state.genre == value, onClick = { viewModel.setGenre(value) }, label = { Text(label) })
                }
            }
            Box(Modifier.fillMaxWidth().height(196.dp).padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                AnimatedContent(
                    targetState = Triple(state.rolling, state.random, state.randomError),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "shuffle"
                ) { (rolling, pick, error) ->
                    when {
                        rolling -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppSpinner(Modifier.size(36.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(8.dp))
                            Text("Buscando uno que esté en tus fuentes...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        pick != null -> RandomPick(pick, onOpen = { state.randomPlaces.firstOrNull()?.let { onOpenMedia(it.item); onDismiss() } })
                        error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            // Where the pick can be read, straight away: one tap opens it there.
            if (!state.rolling && state.random != null && state.randomPlaces.isNotEmpty()) {
                Text(
                    "Disponible en",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.randomPlaces, key = { it.item.sourceId + it.item.id }) { place ->
                        AssistChip(
                            onClick = { onOpenMedia(place.item); onDismiss() },
                            label = { Text(place.sourceName) },
                            trailingIcon = { LanguageTag(place.language) }
                        )
                    }
                }
            }
            Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::rollRandom, enabled = !state.rolling) {
                    Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Otro", modifier = Modifier.padding(start = 8.dp))
                }
                state.randomPlaces.firstOrNull()?.takeIf { state.random != null }?.let { place ->
                    OutlinedButton(onClick = { onOpenMedia(place.item); onDismiss() }, enabled = !state.rolling) {
                        Text("Abrir en ${place.sourceName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun RandomPick(pick: RelatedTitle, onOpen: () -> Unit) {
    Row(Modifier.clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        CoverImage(
            pick.coverUrl, pick.item?.sourceId.orEmpty(), pick.title,
            Modifier.width(118.dp).height(168.dp).clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(pick.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            TypeBadge(pick.type, label = pick.format.ifBlank { pick.type.displayName })
            if (pick.genres.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    pick.genres.take(3).joinToString(" · ") { com.nelson.yomukai.data.metadata.GenreTaxonomy.canonical(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}
