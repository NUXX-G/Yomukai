package com.nelson.yomukai.ui.library

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.data.local.CategoryEntity
import com.nelson.yomukai.data.local.DownloadEntity
import com.nelson.yomukai.data.local.DownloadStatus
import com.nelson.yomukai.data.local.HistoryWithMedia
import com.nelson.yomukai.data.local.ReadingStatus
import com.nelson.yomukai.data.repository.toDomain
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.CoverImage
import com.nelson.yomukai.ui.components.LoadingState
import com.nelson.yomukai.ui.components.MediaCard
import com.nelson.yomukai.ui.components.MediaListRow
import com.nelson.yomukai.ui.components.MessageState
import com.nelson.yomukai.ui.components.relativeTime
import com.nelson.yomukai.ui.home.TypeFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onMediaClick: (MediaItem) -> Unit,
    onHistoryClick: (HistoryWithMedia) -> Unit,
    onDownloadClick: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var gridMode by rememberSaveable { mutableStateOf(true) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var typeFilter by rememberSaveable { mutableStateOf(TypeFilter.ALL) }
    var typeMenu by remember { mutableStateOf(false) }
    var manageCategories by remember { mutableStateOf(false) }

    if (manageCategories) {
        ManageCategoriesDialog(
            categories = state.categories,
            onCreate = viewModel::createCategory,
            onRename = viewModel::renameCategory,
            onDelete = viewModel::deleteCategory,
            onDismiss = { manageCategories = false }
        )
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Biblioteca") },
                    actions = {
                        when (tab) {
                            0 -> {
                                Box {
                                    IconButton(onClick = { typeMenu = true }) {
                                        Icon(Icons.Default.FilterList, contentDescription = "Filtrar por tipo")
                                    }
                                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                                        TypeFilter.entries.forEach { f ->
                                            DropdownMenuItem(
                                                text = { Text(f.label) },
                                                onClick = { typeFilter = f; typeMenu = false },
                                                trailingIcon = { if (f == typeFilter) Icon(Icons.Default.Check, null) }
                                            )
                                        }
                                    }
                                }
                                IconButton(onClick = { gridMode = !gridMode }) {
                                    Icon(
                                        if (gridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                        contentDescription = "Cambiar vista"
                                    )
                                }
                            }
                            1 -> if (state.history.isNotEmpty()) IconButton(onClick = { confirm = "history" }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Borrar historial")
                            }
                            2 -> if (state.downloads.isNotEmpty()) IconButton(onClick = { confirm = "downloads" }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "Borrar descargas")
                            }
                        }
                    }
                )
                TabRow(selectedTabIndex = tab) {
                    listOf(
                        "Favoritos" to Icons.Default.CollectionsBookmark,
                        "Historial" to Icons.Default.History,
                        "Descargas" to Icons.Default.Download
                    ).forEachIndexed { index, (title, icon) ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title) },
                            icon = { Icon(icon, contentDescription = null) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!state.isLoaded) {
                LoadingState()
                return@Box
            }
            when (tab) {
                0 -> FavoritesTab(state, gridMode, typeFilter, onMediaClick, onManageCategories = { manageCategories = true })
                1 -> HistoryTab(state.history, onHistoryClick, viewModel::removeHistory)
                else -> DownloadsTab(state.downloads, onDownloadClick, viewModel::retryDownload, viewModel::deleteDownload)
            }
        }
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "history") "¿Borrar historial?" else "¿Borrar todas las descargas?") },
            text = { Text("Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "history") viewModel.clearHistory() else viewModel.deleteAllDownloads()
                    confirm = null
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun FavoritesTab(
    state: LibraryUiState,
    gridMode: Boolean,
    typeFilter: TypeFilter,
    onMediaClick: (MediaItem) -> Unit,
    onManageCategories: () -> Unit
) {
    var statusFilter by rememberSaveable { mutableStateOf<ReadingStatus?>(null) }
    var categoryFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    val visible = state.library
        .filter { MediaType.fromName(it.type) in typeFilter.types }
        .filter { statusFilter == null || ReadingStatus.parse(it.readingStatus) == statusFilter }
        .filter { categoryFilter == null || categoryFilter in state.categoriesOf(it) }
        .map { entity ->
            // Show the user's reading status instead of the publication status on cards.
            val status = ReadingStatus.parse(entity.readingStatus)
            entity.toDomain().copy(status = if (status == ReadingStatus.NONE) entity.status else status.displayName)
        }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("Todos") }) }
            items(ReadingStatus.entries.filter { it != ReadingStatus.NONE }) { status ->
                val count = state.library.count { ReadingStatus.parse(it.readingStatus) == status }
                FilterChip(
                    selected = statusFilter == status,
                    onClick = { statusFilter = if (statusFilter == status) null else status },
                    label = { Text(if (count > 0) "${status.displayName} ($count)" else status.displayName) }
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.categories, key = { it.id }) { category ->
                FilterChip(
                    selected = categoryFilter == category.id,
                    onClick = { categoryFilter = if (categoryFilter == category.id) null else category.id },
                    label = { Text(category.name) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(16.dp)) }
                )
            }
            item {
                AssistChip(
                    onClick = onManageCategories,
                    label = { Text(if (state.categories.isEmpty()) "Crear categorías" else "Gestionar") },
                    leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) }
                )
            }
        }
        when {
            visible.isEmpty() -> MessageState(
                if (state.library.isEmpty()) "Aún no hay títulos aquí. Añade títulos desde su página de detalles."
                else "Ningún título coincide con estos filtros.",
                icon = Icons.Default.CollectionsBookmark
            )

            gridMode -> LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(visible, key = { it.sourceId + it.id }) { item -> MediaCard(item, onClick = { onMediaClick(item) }) }
            }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.sourceId + it.id }) { item -> MediaListRow(item, onClick = { onMediaClick(item) }) }
            }
        }
    }
}

@Composable
private fun HistoryTab(
    history: List<HistoryWithMedia>,
    onClick: (HistoryWithMedia) -> Unit,
    onRemove: (HistoryWithMedia) -> Unit
) {
    if (history.isEmpty()) {
        MessageState("Tu historial de lectura está vacío.", icon = Icons.Default.History)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(history, key = { it.sourceId + it.mediaId }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(entry) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(
                    entry.coverUrl, entry.sourceId, entry.title,
                    Modifier
                        .width(52.dp)
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(relativeTime(entry.readAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onRemove(entry) }) { Icon(Icons.Default.Close, contentDescription = "Quitar del historial") }
            }
        }
    }
}

@Composable
private fun DownloadsTab(
    downloads: List<DownloadEntity>,
    onOpen: (DownloadEntity) -> Unit,
    onRetry: (DownloadEntity) -> Unit,
    onDelete: (DownloadEntity) -> Unit
) {
    if (downloads.isEmpty()) {
        MessageState(
            "No hay descargas. Descarga capítulos desde la página de un título para leerlos sin conexión.",
            icon = Icons.Default.Download
        )
        return
    }
    val active = downloads.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (active > 0) {
            item {
                Text(
                    "$active en cola",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        items(downloads, key = { it.sourceId + it.chapterId }) { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = d.status == DownloadStatus.COMPLETED) { onOpen(d) }
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.mediaTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.chapterTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val statusText = when (d.status) {
                        DownloadStatus.QUEUED -> "En cola"
                        DownloadStatus.DOWNLOADING -> if (d.total > 0) "Descargando ${d.progress}/${d.total}" else "Descargando..."
                        DownloadStatus.COMPLETED -> if (d.isNovel) "Disponible sin conexión" else "Disponible sin conexión • ${d.total} páginas"
                        DownloadStatus.FAILED -> "Error: ${d.error ?: "desconocido"}"
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (d.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (d.status == DownloadStatus.DOWNLOADING) {
                        if (d.total > 0) {
                            LinearProgressIndicator(
                                progress = { d.progress.toFloat() / d.total },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
                if (d.status == DownloadStatus.FAILED) {
                    IconButton(onClick = { onRetry(d) }) { Icon(Icons.Default.Refresh, contentDescription = "Reintentar") }
                }
                IconButton(onClick = { onDelete(d) }) {
                    Icon(Icons.Default.Close, contentDescription = if (d.status == DownloadStatus.COMPLETED) "Borrar" else "Cancelar")
                }
            }
        }
    }
}

@Composable
private fun ManageCategoriesDialog(
    categories: List<CategoryEntity>,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Long?>(null) }
    var editName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categorías") },
        text = {
            Column {
                Text(
                    "Agrupa tus títulos con etiquetas propias. Se asignan desde la página de cada título.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                categories.forEach { category ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (editing == category.id) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it.take(40) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRename(category.id, editName); editing = null }, enabled = editName.isNotBlank()) {
                                Icon(Icons.Default.Check, contentDescription = "Guardar")
                            }
                        } else {
                            Text(category.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { editing = category.id; editName = category.name }) {
                                Icon(Icons.Default.Edit, contentDescription = "Renombrar")
                            }
                            IconButton(onClick = { onDelete(category.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(40) },
                        label = { Text("Nueva categoría") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onCreate(newName); newName = "" },
                        enabled = newName.isNotBlank() && categories.none { it.name.equals(newName.trim(), ignoreCase = true) }
                    ) { Icon(Icons.Default.Add, contentDescription = "Crear") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } }
    )
}
