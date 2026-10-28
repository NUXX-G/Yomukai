package com.nelson.yomukai.ui.search

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.LanguageTag
import com.nelson.yomukai.ui.components.MediaCard
import com.nelson.yomukai.ui.components.MessageState
import com.nelson.yomukai.ui.components.SectionHeader
import com.nelson.yomukai.ui.home.LanguageFilter
import com.nelson.yomukai.ui.home.TypeFilter

@Composable
fun SearchScreen(
    onMediaClick: (MediaItem) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { if (state.query.isEmpty()) focusRequester.requestFocus() }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text("Buscar en todas las fuentes...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Borrar")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        viewModel.search()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    items(TypeFilter.entries) { filter ->
                        FilterChip(
                            selected = state.typeFilter == filter,
                            onClick = { viewModel.setTypeFilter(filter) },
                            label = { Text(filter.label) }
                        )
                    }
                    items(LanguageFilter.entries) { filter ->
                        FilterChip(
                            selected = state.languageFilter == filter,
                            onClick = { viewModel.setLanguageFilter(filter) },
                            label = { Text(filter.label) }
                        )
                    }
                }
                if (state.query.trim().let { it.startsWith("http://") || it.startsWith("https://") }) {
                    androidx.compose.material3.AssistChip(
                        onClick = { onOpenUrl(state.query.trim()) },
                        label = { Text("Abrir este enlace en el navegador integrado") },
                        leadingIcon = { Icon(Icons.Default.Public, null) },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (state.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                !state.hasSearched -> MessageState(
                    "Busca manga, novelas y anime en todas las fuentes activas a la vez.",
                    icon = Icons.Default.Search
                )

                state.results.isEmpty() -> MessageState("No hay fuentes activas que coincidan con los filtros.")

                state.results.none { it.isLoading } && state.results.all { it.items.isEmpty() } ->
                    MessageState("Sin resultados para \"${state.query}\"", icon = Icons.Default.Search)

                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.results, key = { it.sourceId }) { section ->
                        Column {
                            SectionHeader(section.sourceName, action = { LanguageTag(section.language) })
                            when {
                                section.isLoading -> Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(80.dp),
                                    contentAlignment = Alignment.Center
                                ) { CircularProgressIndicator() }

                                section.error != null -> Text(
                                    "Error: ${section.error}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                section.items.isEmpty() -> Text(
                                    "Sin resultados",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                else -> LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(section.items, key = { it.id }) { item ->
                                        MediaCard(item, onClick = { onMediaClick(item) }, modifier = Modifier.width(115.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
