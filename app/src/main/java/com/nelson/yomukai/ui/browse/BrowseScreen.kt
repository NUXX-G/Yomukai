package com.nelson.yomukai.ui.browse

import com.nelson.yomukai.ui.components.AppSpinner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.LoadingState
import com.nelson.yomukai.ui.components.MediaCard
import com.nelson.yomukai.ui.components.MediaListRow
import com.nelson.yomukai.ui.components.MessageState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onBack: () -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    viewModel: BrowseViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var gridMode by rememberSaveable { mutableStateOf(true) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }

    if (showFilters) {
        FilterSheet(state.filters, state.values, onApply = viewModel::applyFilters, onDismiss = { showFilters = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.sourceName) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                actions = {
                    if (state.filters.isNotEmpty()) {
                        IconButton(onClick = { showFilters = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filtros")
                        }
                    }
                    IconButton(onClick = { gridMode = !gridMode }) {
                        Icon(
                            if (gridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (gridMode) "Vista de lista" else "Vista de cuadrícula"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; if (it.isEmpty()) viewModel.search("") },
            placeholder = { Text("Buscar en ${state.sourceName}") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = ""; viewModel.search("") }) { Icon(Icons.Default.Clear, contentDescription = "Borrar") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(text) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Box(Modifier.fillMaxSize()) {
            when {
                state.items.isEmpty() && state.isLoading -> LoadingState("Cargando...")
                state.items.isEmpty() && state.query.isNotEmpty() && state.error == null ->
                    MessageState("Sin resultados para \"${state.query}\"", icon = Icons.Default.Search)
                state.items.isEmpty() && state.error != null ->
                    MessageState(state.error!!, actionLabel = "Reintentar", onAction = viewModel::loadNextPage)

                gridMode -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.items, key = { it.id }) { item ->
                        MediaCard(item, onClick = { onMediaClick(item) })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PagingFooter(state, viewModel::loadNextPage)
                    }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { it.id }) { item ->
                        MediaListRow(item, onClick = { onMediaClick(item) })
                    }
                    item { PagingFooter(state, viewModel::loadNextPage) }
                }
            }
        }
        }
    }
}

@Composable
private fun PagingFooter(state: BrowseUiState, loadMore: () -> Unit) {
    // Reaching the footer triggers the next page automatically.
    LaunchedEffect(state.items.size) { if (!state.endReached && state.error == null) loadMore() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            state.isLoading -> AppSpinner()
            state.error != null -> TextButton(onClick = loadMore) { Text("Error al cargar más. Reintentar") }
        }
    }
}
