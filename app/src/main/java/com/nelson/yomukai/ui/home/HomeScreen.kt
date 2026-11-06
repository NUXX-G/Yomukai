package com.nelson.yomukai.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.data.local.HistoryWithMedia
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.AppSpinner
import com.nelson.yomukai.ui.components.CoverImage
import com.nelson.yomukai.ui.components.LanguageTag
import com.nelson.yomukai.ui.components.MediaCard
import com.nelson.yomukai.ui.components.RefreshSpinner
import com.nelson.yomukai.ui.components.SectionHeader
import com.nelson.yomukai.ui.components.SkeletonRow
import com.nelson.yomukai.ui.discover.DiscoverViewModel
import com.nelson.yomukai.ui.discover.ForYouSection
import com.nelson.yomukai.ui.discover.LocateHandler
import com.nelson.yomukai.ui.discover.ShuffleSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMediaClick: (MediaItem) -> Unit,
    onContinueClick: (HistoryWithMedia) -> Unit,
    onBrowseSource: (String) -> Unit,
    onSearchClick: () -> Unit,
    onOpenWeb: () -> Unit,
    onSearchTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
    discoverViewModel: DiscoverViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var shuffle by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        discoverViewModel.loadForYou()
        discoverViewModel.loadShelves()
    }
    val shelves by discoverViewModel.shelves.collectAsStateWithLifecycle()
    val heroEntries = remember(shelves) { shelves.firstOrNull()?.items.orEmpty().filter { it.banner.isNotBlank() }.take(6) }
    LocateHandler(discoverViewModel, onOpenMedia = onMediaClick, onSearch = onSearchTitle)
    if (shuffle) ShuffleSheet(discoverViewModel, onOpenMedia = onMediaClick, onDismiss = { shuffle = false })

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Yomukai", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = { shuffle = true }) { Icon(Icons.Default.Shuffle, contentDescription = "Descubrir al azar") }
                    IconButton(onClick = onOpenWeb) { Icon(Icons.Default.Public, contentDescription = "Abrir enlace en el navegador integrado") }
                    IconButton(onClick = onSearchClick) { Icon(Icons.Default.Search, contentDescription = "Buscar") }
                }
            )
        }
    ) { padding ->
        val refreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            state = refreshState,
            indicator = { RefreshSpinner(refreshState, state.isRefreshing) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (heroEntries.isNotEmpty()) item(key = "hero") { HomeHero(heroEntries, onOpen = discoverViewModel::openEntry) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(TypeFilter.entries) { filter ->
                            FilterChip(
                                selected = state.typeFilter == filter,
                                onClick = { viewModel.setTypeFilter(filter) },
                                label = { Text(filter.label) }
                            )
                        }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(LanguageFilter.entries) { filter ->
                            FilterChip(
                                selected = state.languageFilter == filter,
                                onClick = { viewModel.setLanguageFilter(filter) },
                                label = { Text(filter.label) }
                            )
                        }
                    }
                }

                if (state.continueReading.isNotEmpty()) {
                    item { SectionHeader("Continuar") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.continueReading, key = { it.sourceId + it.mediaId }) { entry ->
                                ContinueCard(entry, onClick = { onContinueClick(entry) })
                            }
                        }
                    }
                }

                item(key = "for_you") { ForYouSection(discoverViewModel) }

                shelves.forEach { shelf ->
                    item(key = "shelf_" + shelf.id) { DiscoverShelfRow(shelf, onOpen = discoverViewModel::openEntry) }
                }

                if (state.sections.isEmpty() && !state.isRefreshing) {
                    item {
                        Text(
                            "No hay fuentes activas para estos filtros. Actívalas en Ajustes.",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                state.sections.forEach { section ->
                    item(key = "header_${section.sourceId}") {
                        SectionHeader(
                            title = section.sourceName,
                            modifier = Modifier.clickable { onBrowseSource(section.sourceId) },
                            action = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LanguageTag(section.language)
                                    TextButton(onClick = { onBrowseSource(section.sourceId) }) { Text("Ver todo") }
                                }
                            }
                        )
                    }
                    item(key = "row_${section.sourceId}") {
                        when {
                            section.isLoading -> SkeletonRow()

                            section.error != null -> Row(
                                Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "No disponible: ${section.error}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = viewModel::refresh) { Text("Reintentar") }
                            }

                            else -> LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(section.items, key = { it.id }) { item ->
                                    MediaCard(item, onClick = { onMediaClick(item) }, modifier = Modifier.width(120.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(entry: HistoryWithMedia, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(250.dp)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                entry.coverUrl, entry.sourceId, entry.title,
                Modifier
                    .size(width = 52.dp, height = 74.dp)
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    entry.chapterTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
