package com.nelson.yomukai.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.data.discovery.RandomKind
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.AppSpinner
import com.nelson.yomukai.ui.components.MediaCard
import com.nelson.yomukai.ui.components.SectionHeader
import com.nelson.yomukai.ui.details.LocateSheet

@Composable
private fun RelatedCard(related: RelatedTitle, onOpen: (RelatedTitle) -> Unit, modifier: Modifier = Modifier) {
    MediaCard(
        item = MediaItem(related.title, related.title, related.coverUrl, related.type, "", "", related.item?.sourceId.orEmpty()),
        onClick = { onOpen(related) },
        modifier = modifier
    )
}

/** Recommendations from what the user has read and watched, plus a random pick with optional filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onOpenMedia: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locate by viewModel.locate.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadForYou() }

    locate?.let { found ->
        val only = found.options.singleOrNull()
        if (!found.searching && only != null) {
            LaunchedEffect(only) { viewModel.closeLocate(); onOpenMedia(only.item) }
        } else {
            LocateSheet(found, onOpen = { viewModel.closeLocate(); onOpenMedia(it) }, onDismiss = viewModel::closeLocate)
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Descubrir") }) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionHeader("Aleatorio") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RandomKind.entries) { kind ->
                        FilterChip(selected = state.kind == kind, onClick = { viewModel.setKind(kind) }, label = { Text(kind.label) })
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = state.genre == null, onClick = { viewModel.setGenre(null) }, label = { Text("Cualquier género") }) }
                    items(RANDOM_GENRES) { (value, label) ->
                        FilterChip(selected = state.genre == value, onClick = { viewModel.setGenre(value) }, label = { Text(label) })
                    }
                }
                Button(onClick = viewModel::rollRandom, enabled = !state.rolling, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Icon(Icons.Default.Casino, contentDescription = null)
                    Text("Recomiéndame uno", modifier = Modifier.padding(start = 8.dp))
                }
            }
            item {
                when {
                    state.rolling -> AppSpinner(Modifier.padding(16.dp).size(32.dp), strokeWidth = 3.dp)
                    state.random != null -> Row(Modifier.padding(horizontal = 16.dp)) { RelatedCard(state.random!!, viewModel::open, Modifier.width(160.dp)) }
                    state.randomError != null -> Text(
                        state.randomError!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            item { SectionHeader("Para ti") }
            when {
                state.loading -> item { AppSpinner(Modifier.padding(16.dp).size(32.dp), strokeWidth = 3.dp) }
                state.forYou.isEmpty() -> item {
                    Text(
                        "Lee o mira algo, o añádelo a tu biblioteca, y aquí verás recomendaciones según tus gustos.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }
                else -> itemsIndexed(state.forYou.chunked(3)) { _, row ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Equal shares, so the cards span the screen and an incomplete last row keeps the grid.
                        row.forEach { RelatedCard(it, viewModel::open, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
