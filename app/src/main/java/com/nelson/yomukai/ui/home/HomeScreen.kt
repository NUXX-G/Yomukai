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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
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
import com.nelson.yomukai.i18n.tr
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
    onJoinRoom: (String) -> Unit = {},
    onCommunity: () -> Unit = {},
    onCalendar: () -> Unit = {},
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
                        Text(
                            tr("Yomukai"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onCalendar) { Icon(androidx.compose.material.icons.Icons.Default.CalendarMonth, tr("Calendario")) }
                    if (com.nelson.yomukai.ui.LocalAppContainer.current.serverSync.serverUrl != null) {
                        IconButton(onClick = onCommunity) { Icon(androidx.compose.material.icons.Icons.Default.Forum, tr("Comunidad")) }
                        var joining by remember { mutableStateOf(false) }
                        IconButton(onClick = { joining = true }) { Icon(androidx.compose.material.icons.Icons.Default.Group, tr("Unirse a una sala")) }
                        if (joining) com.nelson.yomukai.ui.player.JoinRoomDialog(onDismiss = { joining = false }) { joining = false; onJoinRoom(it) }
                    }
                    NotificationsButton()
                    IconButton(onClick = onSearchClick) { Icon(Icons.Default.Search, contentDescription = tr("Buscar")) }
                    // The less used ones in a menu, so the name never breaks in two lines (seven icons did not fit).
                    var more by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, tr("Más")) }
                        androidx.compose.material3.DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(tr("Descubrir al azar")) }, leadingIcon = { Icon(Icons.Default.Shuffle, null) },
                                onClick = { more = false; shuffle = true }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(tr("Abrir enlace en el navegador integrado")) }, leadingIcon = { Icon(Icons.Default.Public, null) },
                                onClick = { more = false; onOpenWeb() }
                            )
                        }
                    }
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
                    item { SectionHeader(tr("Continuar")) }
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

                item(key = "update") { UpdateBanner() }
                item(key = "for_you") { ForYouSection(discoverViewModel) }

                shelves.forEach { shelf ->
                    item(key = "shelf_" + shelf.id) { DiscoverShelfRow(shelf, onOpen = discoverViewModel::openEntry) }
                }

                if (state.rows.isEmpty() && !state.loadingSources && !state.isRefreshing) {
                    item {
                        Text(
                            tr("No hay fuentes activas para estos filtros. Actívalas en Ajustes."),
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state.rows.isEmpty() && state.loadingSources) {
                    item(key = "rows_loading") { SkeletonRow() }
                }
                state.rows.forEach { row ->
                    item(key = "wh_" + row.id) { SectionHeader(title = row.title) }
                    item(key = "wr_" + row.id) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(row.cards, key = { it.primary.sourceId + "|" + it.primary.id }) { card ->
                                Column(Modifier.width(120.dp)) {
                                    MediaCard(card.primary, onClick = { onMediaClick(card.primary) }, modifier = Modifier.width(120.dp))
                                    if (card.sources.size > 1) {
                                        Text(
                                            tr("En {0} fuentes", card.sources.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.sections.isNotEmpty()) {
                    item(key = "by_source") {
                        var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            TextButton(onClick = { open = !open }) { Text(tr("Explorar por fuente")) }
                            if (open) {
                                state.sections.forEach { section ->
                                    TextButton(onClick = { onBrowseSource(section.sourceId) }) { Text(section.sourceName + " · " + section.language) }
                                }
                            }
                        }
                    }
                }
                if (state.failedSources.isNotEmpty()) {
                    item(key = "failed") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr("Sin respuesta ahora: {0}", state.failedSources.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = viewModel::refresh) { Text(tr("Reintentar")) }
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
