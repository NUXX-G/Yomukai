package com.nelson.yomukai.ui.details

import com.nelson.yomukai.domain.model.VideoSource
import com.nelson.yomukai.data.download.EpisodeServers
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.data.local.CategoryEntity
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.DownloadEntity
import com.nelson.yomukai.data.local.DownloadStatus
import com.nelson.yomukai.data.local.ReadingStatus
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.PublicationStatus
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.CoverImage
import com.nelson.yomukai.ui.components.LoadingState
import com.nelson.yomukai.ui.components.MessageState
import com.nelson.yomukai.ui.components.TypeBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    onBack: () -> Unit,
    onOpenChapter: (ChapterEntity) -> Unit,
    viewModel: DetailsViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }

    var failed by remember { mutableStateOf<ChapterEntity?>(null) }
    failed?.let { chapter ->
        val retryServers by viewModel.retryServers.collectAsStateWithLifecycle()
        FailedDownloadDialog(
            error = state.downloads[chapter.chapterId]?.error,
            isAnime = state.isAnime,
            servers = retryServers,
            onRetry = { server -> viewModel.retryDownload(chapter, server); failed = null },
            onCancel = { viewModel.deleteDownload(chapter); failed = null },
            onDismiss = { failed = null }
        )
    }

    var showRange by remember { mutableStateOf(false) }
    if (showRange) {
        RangeDialog(
            onConfirm = { from, to -> viewModel.downloadRange(from, to) },
            onDismiss = { showRange = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.media?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleSort) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Invertir orden")
                    }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Descargar todo") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { menu = false; viewModel.downloadAll() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.isAnime) "Descargar no vistos" else "Descargar no leídos") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { menu = false; viewModel.downloadUnread() }
                        )
                        DropdownMenuItem(
                            text = { Text("Descargar un rango...") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { menu = false; showRange = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Actualizar") },
                            onClick = { menu = false; viewModel.refresh() }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            state.continueTarget?.let { (chapter, started) ->
                ExtendedFloatingActionButton(
                    onClick = { onOpenChapter(chapter) },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = { Text(if (started) "Continuar" else if (state.isAnime) "Ver episodio 1" else "Empezar a leer") }
                )
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val media = state.media
            if (media == null) {
                if (state.isRefreshing) LoadingState("Cargando información...")
                else MessageState(state.error ?: "No se encontró el título", actionLabel = "Reintentar", onAction = viewModel::refresh)
                return@PullToRefreshBox
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item {
                    Header(state, onToggleLibrary = viewModel::toggleLibrary)
                    if (media.inLibrary) {
                        LibraryControls(
                            state = state,
                            onStatus = viewModel::setReadingStatus,
                            onNotify = viewModel::setNotifyUpdates,
                            onSaveCategories = viewModel::saveCategories,
                            onCreateCategory = viewModel::createCategory
                        )
                    }
                }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${state.chapters.size} ${if (state.isAnime) "episodios" else "capítulos"}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        val readCount = state.chapters.count { it.read }
                        if (readCount > 0) {
                            Text(if (state.isAnime) "$readCount vistos" else "$readCount leídos", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
                if (state.chapters.isEmpty() && !state.isRefreshing) {
                    item {
                        Text(
                            "No se encontraron capítulos.",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(state.displayedChapters, key = { it.chapterId }) { chapter ->
                    ChapterRow(
                        chapter = chapter,
                        download = state.downloads[chapter.chapterId],
                        isNovel = state.isNovel,
                        isAnime = state.isAnime,
                        onClick = { onOpenChapter(chapter) },
                        onToggleRead = { viewModel.setRead(chapter, !chapter.read) },
                        onMarkPrevious = { viewModel.markPreviousRead(chapter) },
                        onDownload = {
                            if (state.downloads[chapter.chapterId]?.status == DownloadStatus.FAILED) {
                                failed = chapter
                                viewModel.loadRetryServers(chapter)
                            } else {
                                viewModel.download(listOf(chapter))
                            }
                        },
                        onDeleteDownload = { viewModel.deleteDownload(chapter) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(state: DetailsUiState, onToggleLibrary: () -> Unit) {
    val media = state.media ?: return
    val type = MediaType.fromName(media.type)
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.padding(16.dp)) {
        Row {
            CoverImage(
                media.coverUrl, media.sourceId, media.title,
                Modifier
                    .width(120.dp)
                    .height(172.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(media.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (media.author.isNotBlank()) {
                    Text(media.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TypeBadge(type)
                    val status = PublicationStatus.parse(media.status)
                    Text(
                        if (status == PublicationStatus.UNKNOWN) media.status.ifBlank { status.displayName } else status.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(state.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onToggleLibrary) {
                    Icon(
                        if (media.inLibrary) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = if (media.inLibrary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (media.inLibrary) "En biblioteca" else "Añadir a biblioteca")
                }
            }
        }

        if (media.description.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                media.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .animateContentSize()
                    .clickable { expanded = !expanded }
            )
            Text(
                if (expanded) "Mostrar menos" else "Mostrar más",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { expanded = !expanded }
            )
        }

        val genres = media.genres.split('|').filter { it.isNotBlank() }
        if (genres.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                genres.forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: ChapterEntity,
    download: DownloadEntity?,
    isNovel: Boolean,
    isAnime: Boolean,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onMarkPrevious: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val contentAlpha = if (chapter.read) 0.45f else 1f

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val progressText = when {
                    chapter.read -> if (isAnime) "Visto" else "Leído"
                    !isNovel && chapter.lastPageRead > 0 && chapter.totalPages > 0 -> "Página ${chapter.lastPageRead + 1} de ${chapter.totalPages}"
                    isNovel && chapter.progress > 0.01f -> "${(chapter.progress * 100).toInt()}% leído"
                    else -> null
                }
                val meta = listOfNotNull(chapter.dateUploaded.ifBlank { null }, progressText).joinToString(" • ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (progressText != null && !chapter.read) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                    )
                }
            }
            DownloadIndicator(download, onDownload = onDownload, onDelete = onDeleteDownload)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (chapter.read) "Marcar como no visto/leído" else "Marcar como visto/leído") },
                leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                onClick = { menu = false; onToggleRead() }
            )
            DropdownMenuItem(text = { Text("Marcar anteriores como leídos") }, onClick = { menu = false; onMarkPrevious() })
        }
    }
}

@Composable
private fun DownloadIndicator(download: DownloadEntity?, onDownload: () -> Unit, onDelete: () -> Unit) {
    when (download?.status) {
        null -> IconButton(onClick = onDownload) {
            Icon(Icons.Default.Download, contentDescription = "Descargar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DownloadStatus.COMPLETED -> IconButton(onClick = onDelete) {
            Icon(Icons.Default.DownloadDone, contentDescription = "Borrar descarga", tint = MaterialTheme.colorScheme.primary)
        }
        DownloadStatus.FAILED -> IconButton(onClick = onDownload) {
            Icon(Icons.Default.ErrorOutline, contentDescription = "Reintentar descarga", tint = MaterialTheme.colorScheme.error)
        }
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> IconButton(onClick = onDelete) {
            val progress = if (download.total > 0) download.progress.toFloat() / download.total else 0f
            if (download.status == DownloadStatus.DOWNLOADING && progress > 0f) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            } else {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
        }
    }
}

/**
 * Why a download failed and how to go on. For an episode it lists the servers again: direct ones
 * can be picked to retry with, web embeds are shown as browser-only.
 */
@Composable
private fun FailedDownloadDialog(
    error: String?,
    isAnime: Boolean,
    servers: Result<List<VideoSource>>?,
    onRetry: (server: String?) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("La descarga falló") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error ?: "Error desconocido", color = MaterialTheme.colorScheme.error)
                if (isAnime) {
                    Text("Elige con qué servidor reintentar:", style = MaterialTheme.typography.titleSmall)
                    when {
                        servers == null -> CircularProgressIndicator(Modifier.size(24.dp))
                        servers.isFailure -> Text("No se pudieron cargar los servidores: ${servers.exceptionOrNull()?.message}")
                        else -> servers.getOrThrow().forEach { server ->
                            val usable = EpisodeServers.isDownloadable(server)
                            TextButton(onClick = { onRetry(server.serverName) }, enabled = usable, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "${server.serverName} • ${server.quality}" + if (usable) "" else " (solo navegador)",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRetry(null) }) { Text(if (isAnime) "Probar todos" else "Reintentar") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Quitar descarga") } }
    )
}

/** Reading status, categories and update notifications for a title in the library. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryControls(
    state: DetailsUiState,
    onStatus: (ReadingStatus) -> Unit,
    onNotify: (Boolean) -> Unit,
    onSaveCategories: (Set<Long>) -> Unit,
    onCreateCategory: (String) -> Unit
) {
    val media = state.media ?: return
    var statusMenu by remember { mutableStateOf(false) }
    var categoriesDialog by remember { mutableStateOf(false) }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Box {
            AssistChip(
                onClick = { statusMenu = true },
                label = { Text("Estado: " + state.readingStatus.displayName) },
                leadingIcon = { Icon(Icons.Default.Bookmark, null, Modifier.size(18.dp)) }
            )
            DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                ReadingStatus.entries.filter { it != ReadingStatus.NONE }.forEach { status ->
                    DropdownMenuItem(
                        text = { Text(status.displayName) },
                        onClick = { statusMenu = false; onStatus(status) },
                        trailingIcon = { if (status == state.readingStatus) Icon(Icons.Default.Check, null) }
                    )
                }
            }
        }
        val selectedNames = state.categories.filter { it.id in state.selectedCategoryIds }.map { it.name }
        AssistChip(
            onClick = { categoriesDialog = true },
            label = { Text(if (selectedNames.isEmpty()) "Categorías" else selectedNames.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(18.dp)) }
        )
        FilterChip(
            selected = media.notifyUpdates,
            onClick = { onNotify(!media.notifyUpdates) },
            label = { Text(if (media.notifyUpdates) "Avisos activos" else "Sin avisos") },
            leadingIcon = {
                Icon(if (media.notifyUpdates) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff, null, Modifier.size(18.dp))
            }
        )
    }

    if (categoriesDialog) {
        CategoriesDialog(
            categories = state.categories,
            initiallySelected = state.selectedCategoryIds,
            onCreate = onCreateCategory,
            onSave = { onSaveCategories(it); categoriesDialog = false },
            onDismiss = { categoriesDialog = false }
        )
    }
}

@Composable
private fun CategoriesDialog(
    categories: List<CategoryEntity>,
    initiallySelected: Set<Long>,
    onCreate: (String) -> Unit,
    onSave: (Set<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(initiallySelected) { mutableStateOf(initiallySelected) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categorías") },
        text = {
            Column {
                if (categories.isEmpty()) {
                    Text("Aún no tienes categorías. Crea una abajo (p. ej. Favoritos, Isekai, Para releer).")
                }
                categories.forEach { category ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (category.id in selected) selected - category.id else selected + category.id
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = category.id in selected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(category.name)
                    }
                }
                Spacer(Modifier.height(12.dp))
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
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun RangeDialog(onConfirm: (Float, Float) -> Unit, onDismiss: () -> Unit) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    val fromValue = from.replace(',', '.').toFloatOrNull()
    val toValue = to.replace(',', '.').toFloatOrNull()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Descargar un rango") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Números de capítulo o episodio, ambos incluidos.")
                androidx.compose.material3.OutlinedTextField(from, { from = it }, label = { Text("Desde") }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(to, { to = it }, label = { Text("Hasta") }, singleLine = true)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = fromValue != null && toValue != null,
                onClick = { onConfirm(fromValue!!, toValue!!); onDismiss() }
            ) { Text("Descargar") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
