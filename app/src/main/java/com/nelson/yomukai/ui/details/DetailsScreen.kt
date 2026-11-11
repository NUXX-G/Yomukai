package com.nelson.yomukai.ui.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.data.download.EpisodeServers
import com.nelson.yomukai.data.local.CategoryEntity
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.DownloadEntity
import com.nelson.yomukai.data.local.DownloadStatus
import com.nelson.yomukai.data.local.ReadingStatus
import com.nelson.yomukai.data.metadata.Abandonment
import com.nelson.yomukai.data.metadata.ChapterIntegrity
import com.nelson.yomukai.data.metadata.ChapterIssue
import com.nelson.yomukai.data.metadata.TitleMetadata
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.PublicationStatus
import com.nelson.yomukai.domain.model.VideoSource
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.LocalAppContainer
import com.nelson.yomukai.ui.components.AppSpinner
import com.nelson.yomukai.ui.components.CoverImage
import com.nelson.yomukai.ui.components.InkOutlinedButton
import com.nelson.yomukai.ui.components.LoadingState
import com.nelson.yomukai.ui.components.MessageState
import com.nelson.yomukai.ui.components.RefreshSpinner
import com.nelson.yomukai.ui.components.SectionHeader
import com.nelson.yomukai.ui.components.TypeBadge
import com.nelson.yomukai.ui.components.rememberImageRequest
import com.nelson.yomukai.ui.discover.RelatedCard
import com.nelson.yomukai.ui.theme.CoverTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Room left under the list so the floating button never covers the last chapters. */
private val FAB_CLEARANCE = 112.dp

@Composable
fun DetailsScreen(
    onBack: () -> Unit,
    onOpenChapter: (ChapterEntity) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onSearchTitle: (String) -> Unit = {},
    viewModel: DetailsViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val coverUrl = state.media?.coverUrl.orEmpty()
    val headers = LocalAppContainer.current.sourceManager.get(viewModel.sourceId)?.imageHeaders.orEmpty()
    // The whole page takes its accent from the cover.
    CoverTheme(coverUrl, headers) {
        DetailsContent(state, viewModel, onBack, onOpenChapter, onOpenMedia, onSearchTitle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsContent(
    state: DetailsUiState,
    viewModel: DetailsViewModel,
    onBack: () -> Unit,
    onOpenChapter: (ChapterEntity) -> Unit,
    onOpenMedia: (MediaItem) -> Unit,
    onSearchTitle: (String) -> Unit
) {
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

    val similar by viewModel.similar.collectAsStateWithLifecycle()
    val alternatives by viewModel.alternatives.collectAsStateWithLifecycle()
    val locate by viewModel.locate.collectAsStateWithLifecycle()
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    val fillers by viewModel.fillers.collectAsStateWithLifecycle()
    val spoilerPrefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    var shield by remember { mutableStateOf(spoilerPrefs.getBoolean("spoilerShield", true)) }
    val metadataLoading by viewModel.metadataLoading.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val extraChapters by viewModel.extraChapters.collectAsStateWithLifecycle()

    if (alternatives.open) {
        AlternativesSheet(
            state = alternatives,
            currentSource = state.sourceName,
            currentCount = state.chapters.size,
            isAnime = state.isAnime,
            onOpen = { viewModel.closeAlternatives(); onOpenMedia(it) },
            onDismiss = viewModel::closeAlternatives
        )
    }
    locate?.let { found ->
        // A single place to read it needs no question.
        val only = found.openNow ?: found.options.singleOrNull()?.takeIf { !it.approximate && !found.searching }?.item
        if (only != null) {
            LaunchedEffect(only) { viewModel.closeLocate(); onOpenMedia(only) }
        } else {
            LocateSheet(
                state = found,
                onOpen = { viewModel.closeLocate(); onOpenMedia(it) },
                onDismiss = viewModel::closeLocate,
                onSearch = { viewModel.closeLocate(); onSearchTitle(it) }
            )
        }
    }

    var showRange by remember { mutableStateOf(false) }
    if (showRange) {
        RangeDialog(
            onConfirm = { from, to -> viewModel.downloadRange(from, to) },
            onDismiss = { showRange = false }
        )
    }

    val listState = rememberLazyListState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 0 at the top, 1 once the header has scrolled a little: the bar fills in gradually, never in one jump.
            val fill by remember {
                derivedStateOf {
                    if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / 420f).coerceIn(0f, 1f)
                }
            }
            // Over a bright cover the icons sit on a dark disc, which fades as the bar fills.
            val discs = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = (1f - fill) * 0.42f)
            )
            TopAppBar(
                title = {
                    Text(
                        state.media?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = ((fill - 0.6f) * 2.5f).coerceIn(0f, 1f) }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = fill)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, colors = discs) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Volver")) }
                },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    var shareOpen by remember { mutableStateOf(false) }
                    val container = LocalAppContainer.current
                    val media = state.media
                    if (media != null && container.serverSync.serverUrl != null) {
                        IconButton(onClick = { shareOpen = true }, colors = discs) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Compartir")) }
                    }
                    if (shareOpen && media != null) {
                        com.nelson.yomukai.ui.social.ShareSheet(
                            com.nelson.yomukai.data.social.ShareCard(
                                kind = "work", sourceId = media.sourceId, mediaId = media.mediaId, title = media.title, type = media.type,
                                cover = media.coverUrl.takeIf { it.startsWith("https://") }.orEmpty(),
                                lang = container.sourceManager.get(media.sourceId)?.language.orEmpty()
                            )
                        ) { shareOpen = false }
                    }
                    IconButton(onClick = { menu = true }, colors = discs) { Icon(Icons.Default.MoreVert, contentDescription = tr("Más")) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(tr("Descargar todo")) },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { menu = false; viewModel.downloadAll() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.isAnime) tr("Descargar no vistos") else tr("Descargar no leídos")) },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { menu = false; viewModel.downloadUnread() }
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Descargar un rango...")) },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { menu = false; showRange = true }
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Buscar en otras fuentes")) },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            onClick = { menu = false; viewModel.openAlternatives() }
                        )
                        DropdownMenuItem(text = { Text(tr("Actualizar")) }, onClick = { menu = false; viewModel.refresh() })
                    }
                }
            )
        },
        floatingActionButton = {
            state.continueTarget?.let { (chapter, started) ->
                val label = ChapterLabels.of(chapter.title, chapter.number, state.isAnime, state.media?.title.orEmpty()).short
                ExtendedFloatingActionButton(
                    // Always the same size: resizing it while scrolling made it flicker.
                    onClick = { onOpenChapter(chapter) },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = {
                        Text(
                            when {
                                started -> "Continuar · $label"
                                state.isAnime -> tr("Empezar a ver · {0}", label)
                                else -> tr("Empezar a leer · {0}", label)
                            },
                            maxLines = 1
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
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
                .padding(top = padding.calculateTopPadding())
        ) {
            val media = state.media
            if (media == null) {
                if (state.isRefreshing) LoadingState(tr("Cargando información..."))
                else MessageState(state.error ?: tr("No se encontró el título"), actionLabel = tr("Reintentar"), onAction = viewModel::refresh)
                return@PullToRefreshBox
            }

            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item(key = "header") {
                    Header(state, metadata, insights, onToggleLibrary = viewModel::toggleLibrary)
                    WorkLanguageChips(viewModel, onOpenMedia)
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
                item(key = "tabs") {
                    val count = state.chapters.size
                    PrimaryTabRow(
                        selectedTabIndex = tab,
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("{0} · {1}", if (state.isAnime) tr("Episodios") else tr("Capítulos"), count)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("Relacionadas")) })
                    }
                }
                if (tab == 0) {
                    item(key = "chapters_bar") {
                        ChaptersBar(
                            state, onToggleSort = viewModel::toggleSort, shield = shield,
                            onToggleShield = {
                                shield = !shield
                                spoilerPrefs.edit().putBoolean("spoilerShield", shield).apply()
                            }
                        )
                        IntegrityNotice(insights.issues, state.isAnime)
                    }
                    if (state.chapters.isEmpty() && !state.isRefreshing) {
                        item(key = "empty") {
                            Text(
                                tr("No se encontraron capítulos."),
                                modifier = Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // One list for the work: this source's chapters plus those only its sister sources have.
                    val merged = (state.chapters + extraChapters).sortedWith(compareBy({ it.number }, { it.sortIndex }))
                        .let { if (state.sortDescending) it.asReversed() else it }
                    val furthestRead = merged.filter { it.read }.maxOfOrNull { it.number }
                    merged.forEach { chapter ->
                        val foreign = chapter.sourceId != viewModel.sourceId || chapter.mediaId != viewModel.mediaId
                        val gap = if (foreign || extraChapters.isNotEmpty()) null else insights.gapAfter[chapter.chapterId]
                        // The gap sits between this chapter and the next one in reading order.
                        if (gap != null && state.sortDescending) item(key = "gap_" + chapter.chapterId) { GapMarker(gap, state.isAnime) }
                        item(key = chapter.sourceId + "|" + chapter.chapterId) {
                            ChapterRow(
                                chapter = chapter,
                                mediaTitle = media.title,
                                download = state.downloads[chapter.chapterId],
                                isNovel = state.isNovel,
                                isAnime = state.isAnime,
                                hideName = com.nelson.yomukai.data.work.SpoilerShield.hideChapter(shield && !chapter.read, chapter.number, furthestRead),
                                kind = if (state.isAnime) fillers[chapter.number.toInt()] else null,
                                fromSource = if (foreign) viewModel.sourceNames[chapter.sourceId] ?: chapter.sourceId else null,
                                onClick = { onOpenChapter(chapter) },
                                onToggleRead = { viewModel.setRead(chapter, !chapter.read) },
                                onMarkPrevious = { viewModel.markPreviousRead(chapter) },
                                onUnmarkPrevious = { viewModel.unmarkPrevious(chapter) },
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
                        if (gap != null && !state.sortDescending) item(key = "gap_" + chapter.chapterId) { GapMarker(gap, state.isAnime) }
                    }
                } else {
                    item(key = "related") {
                        RelatedTab(
                            metadata = metadata,
                            loading = metadataLoading,
                            similar = similar,
                            onLoadSimilar = viewModel::loadSimilar,
                            onOpenRelated = { related -> related.item?.let(onOpenMedia) ?: viewModel.locate(related) },
                            onOtherSources = viewModel::openAlternatives
                        )
                    }
                }
                item(key = "fab_space") {
                    Spacer(Modifier.height(FAB_CLEARANCE))
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

/** "2023" style date of the newest chapter, for the abandonment note. */
private fun formatMonthYear(epochMs: Long): String =
    SimpleDateFormat("MMMM 'de' yyyy", Locale.forLanguageTag("es")).format(Date(epochMs))

/** The same work in other languages (Español / English…): one tap opens it there, progress included (F1.5). */
@Composable
private fun WorkLanguageChips(viewModel: DetailsViewModel, onOpenMedia: (MediaItem) -> Unit) {
    val languages by viewModel.languages.collectAsStateWithLifecycle()
    val work = languages ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    if (work.versions.size > 1) {
        WorkVersionChips(viewModel, work, onOpenMedia)
        return
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        work.langs.forEach { lang ->
            FilterChip(
                selected = lang == work.lang,
                onClick = {
                    if (lang != work.lang) scope.launch {
                        val page = viewModel.pageIn(lang)
                        if (page != null) onOpenMedia(page)
                        else android.widget.Toast.makeText(context, tr("Esta obra aún no tiene fuentes en este idioma."), android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                label = { Text(LANGUAGE_NAMES[lang] ?: lang.uppercase()) }
            )
        }
    }
}

/** O2: an anime's versions (subtitles in each language, each dub), the subtitled one in your language first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkVersionChips(viewModel: DetailsViewModel, work: DetailsViewModel.WorkLanguages, onOpenMedia: (MediaItem) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        work.versions.forEach { (lang, audio) ->
            val current = lang == work.lang && audio == work.audio
            FilterChip(
                selected = current,
                onClick = {
                    if (!current) scope.launch {
                        val page = viewModel.pageIn(lang, audio)
                        if (page != null) onOpenMedia(page)
                        else android.widget.Toast.makeText(context, tr("Ahora mismo no se puede ver con esta versión."), android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                label = { Text(versionName(lang, audio)) }
            )
        }
    }
}

private fun versionName(lang: String, audio: String): String = when (audio) {
    "ORIGINAL" -> when (lang) {
        "es" -> tr("Subtítulos en español")
        "en" -> tr("Subtítulos en inglés")
        else -> tr("Subtítulos") + " · " + (LANGUAGE_NAMES[lang] ?: lang.uppercase())
    }
    else -> com.nelson.yomukai.domain.model.AudioTrack.fromName(audio).label
}

private val LANGUAGE_NAMES = mapOf("es" to "Español", "en" to "English", "zh" to "中文", "ja" to "日本語", "ko" to "한국어", "fr" to "Français", "pt" to "Português")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(state: DetailsUiState, metadata: TitleMetadata?, insights: ChapterInsights, onToggleLibrary: () -> Unit) {
    val media = state.media ?: return
    val type = MediaType.fromName(media.type)
    var expanded by rememberSaveable { mutableStateOf(false) }

    val background = MaterialTheme.colorScheme.background
    val imageHeaders = LocalAppContainer.current.sourceManager.get(media.sourceId)?.imageHeaders.orEmpty()
    // Older saved metadata has no banner field at all (null despite the type), hence the safe call.
    val banner = metadata?.banner.orEmpty()
    // AniList's large cover is sharper than the 200-300 px thumbnails many sources give.
    val poster = metadata?.cover?.takeIf { it.startsWith("https://") } ?: media.coverUrl
    Box {
        if (banner.isNotBlank()) {
            AsyncImage(
                model = rememberImageRequest(banner, emptyMap()),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.75f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Brush.verticalGradient(0f to background.copy(alpha = 0.25f), 0.5f to background.copy(alpha = 0.35f), 1f to background))
            )
        } else if (media.coverUrl.isNotBlank()) {
            AsyncImage(
                model = rememberImageRequest(media.coverUrl, imageHeaders),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.5f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .blur(32.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .background(Brush.verticalGradient(0f to background.copy(alpha = 0.15f), 1f to background))
            )
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(top = if (banner.isNotBlank()) 110.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                CoverImage(
                    poster, if (poster == media.coverUrl) media.sourceId else "", media.title,
                    Modifier
                        .width(116.dp)
                        .height(166.dp)
                        .shadow(12.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        media.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (media.author.isNotBlank()) {
                        Text(
                            media.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val sourceStatus = PublicationStatus.parse(media.status)
                    val status = if (sourceStatus == PublicationStatus.UNKNOWN) metadata?.officialStatus ?: sourceStatus else sourceStatus
                    val statusText = if (status == PublicationStatus.UNKNOWN) media.status.ifBlank { null } else status.displayName
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TypeBadge(type)
                        statusText?.let {
                            MetaDot()
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    FlowRow(verticalArrangement = Arrangement.Center) {
                        Text(state.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        metadata?.score?.let { score ->
                            MetaDot()
                            ScoreLabel(score, metadata.scoreSource)
                        }
                        metadata?.malScore?.let { score ->
                            MetaDot()
                            ScoreLabel(score, tr("MAL"))
                        }
                    }
                }
            }

            if (insights.abandoned) {
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HourglassEmpty, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            tr("Posible abandono o hiato prolongado") +
                                (insights.lastUpdate?.let { tr(" · último capítulo en {0}", formatMonthYear(it)) } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            InkOutlinedButton(onClick = onToggleLibrary, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    if (media.inLibrary) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (media.inLibrary) tr("En tu biblioteca") else tr("Añadir a la biblioteca"))
            }

            // The source's synopsis is in the user's language; the official one fills in when it has none.
            val synopsis = media.description.ifBlank { metadata?.synopsis.orEmpty() }
            if (synopsis.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .animateContentSize()
                        .clickable { expanded = !expanded }
                )
                Text(
                    if (expanded) "Mostrar menos" else tr("Mostrar más"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { expanded = !expanded }
                )
                if (media.description.isBlank() && metadata?.scoreSource == "AniList") {
                    Text(tr("Sinopsis de AniList"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            WorkSocialSection(media.sourceId, media.mediaId, media.title, cover = "", anime = type == MediaType.ANIME)
            WhereNextSection(media.sourceId, media.mediaId, anime = type == MediaType.ANIME, lastDone = state.chapters.filter { it.read }.maxOfOrNull { it.number } ?: 0f)

            val genres = metadata?.genres?.takeIf { it.isNotEmpty() } ?: media.genres.split('|').filter { it.isNotBlank() }
            if (genres.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    genres.forEach { GenrePill(tr(it)) }
                }
                if (metadata?.inferredGenres == true) {
                    Text(
                        tr("Etiquetas deducidas de la sinopsis"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MetaDot() {
    Text(" · ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "★ 8,4 AniList", small and quiet. */
@Composable
private fun ScoreLabel(score: Float, source: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(3.dp))
        Text(
            String.format(Locale.forLanguageTag("es"), "%.1f", score),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (source.isNotBlank()) {
            Text(" $source", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GenrePill(genre: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)) {
        Text(
            genre,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** The chapter's own name (blurred while it could spoil) and its filler/mixed badge. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ChapterNameAndKind(
    name: String?,
    hideName: Boolean,
    kind: com.nelson.yomukai.data.work.EpisodeKind?,
    contentAlpha: Float
) {
    if (name != null) {
        // Blurred on Android 12+; older phones cannot blur text, so the name is replaced.
        val canBlur = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        Text(
            if (hideName && !canBlur) tr("  · título oculto") else "  $name",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha * 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).then(if (hideName && canBlur) Modifier.blur(6.dp) else Modifier)
        )
    }
    if (kind == null) return
    val filler = kind == com.nelson.yomukai.data.work.EpisodeKind.FILLER
    Text(
        if (filler) tr("Relleno") else tr("Mixto"),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (filler) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(if (filler) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 7.dp, vertical = 1.dp)
    )
}

/** Count of read chapters and the order switch, right above the list. */
@Composable
private fun ChaptersBar(state: DetailsUiState, onToggleSort: () -> Unit, shield: Boolean, onToggleShield: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val readCount = state.chapters.count { it.read }
        Text(
            if (readCount > 0) (if (state.isAnime) "$readCount vistos" else tr("{0} leídos", readCount)) + tr(" de {0}", state.chapters.size) else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onToggleShield) {
            Icon(if (shield) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (shield) tr("Sin spoilers") else tr("Títulos visibles"), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onToggleSort) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.sortDescending) tr("Más recientes") else tr("Desde el principio"), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Discreet warning above the list when the numbering looks broken; tap to see each problem. */
@Composable
private fun IntegrityNotice(issues: List<ChapterIssue>, isAnime: Boolean) {
    if (issues.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { open = !open }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (issues.size == 1) ChapterIntegrity.describe(issues.first(), isAnime)
                    else tr("La lista de la fuente tiene {0} irregularidades", issues.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            if (open) {
                if (issues.size > 1) {
                    issues.forEach {
                        Text("• " + ChapterIntegrity.describe(it, isAnime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    tr("Viene de la fuente, no de la app. Prueba «Buscar en otras fuentes» si falta algo."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** Thin line between two chapters whose numbers jump. */
@Composable
private fun GapMarker(gap: ChapterIssue.Gap, isAnime: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)))
        Text(
            "  ${ChapterIntegrity.describe(gap, isAnime)}  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)))
    }
}

/** Related works in every format, then similar titles, then the same title in other sources. */
@Composable
private fun RelatedTab(
    metadata: TitleMetadata?,
    loading: Boolean,
    similar: SimilarState,
    onLoadSimilar: () -> Unit,
    onOpenRelated: (RelatedTitle) -> Unit,
    onOtherSources: () -> Unit
) {
    Column(Modifier.padding(bottom = 8.dp)) {
        val relations = metadata?.relations.orEmpty()
        when {
            loading -> AppSpinner(Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.5.dp)
            relations.isNotEmpty() -> {
                SectionHeader(tr("Universo de la obra"))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(relations, key = { index, _ -> index }) { _, related ->
                        RelatedCard(
                            related, onOpenRelated, Modifier.width(110.dp),
                            subtitle = listOfNotNull(related.relation, related.format.ifBlank { null }).joinToString(" · ")
                        )
                    }
                }
            }
        }
        SimilarSection(state = similar, onLoad = onLoadSimilar, onOpen = onOpenRelated)
        AssistChip(
            onClick = onOtherSources,
            label = { Text(tr("Buscar en otras fuentes")) },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (!loading && relations.isEmpty() && !similar.loading && similar.items.isEmpty()) {
            Text(
                tr("No se conocen obras relacionadas."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: ChapterEntity,
    mediaTitle: String,
    download: DownloadEntity?,
    isNovel: Boolean,
    isAnime: Boolean,
    hideName: Boolean,
    kind: com.nelson.yomukai.data.work.EpisodeKind?,
    fromSource: String?,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onMarkPrevious: () -> Unit,
    onUnmarkPrevious: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val contentAlpha = if (chapter.read) 0.5f else 1f
    val label = remember(chapter.title, chapter.number, mediaTitle) { ChapterLabels.of(chapter.title, chapter.number, isAnime, mediaTitle) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label.short,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        maxLines = 1
                    )
                    ChapterNameAndKind(label.name, hideName, kind, contentAlpha)
                }
                fromSource?.let {
                    Text(tr("De {0}", it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha))
                }
                val progressText = when {
                    chapter.read -> null
                    // Episodes store seconds in lastPageRead/totalPages, not pages.
                    isAnime -> if (chapter.lastPageRead > 0 && chapter.totalPages > 0) {
                        tr("Vas por {0} de {1}", formatEpisodeTime(chapter.lastPageRead), formatEpisodeTime(chapter.totalPages))
                    } else null
                    !isNovel && chapter.lastPageRead > 0 && chapter.totalPages > 0 -> tr("Página {0} de {1}", chapter.lastPageRead + 1, chapter.totalPages)
                    isNovel && chapter.progress > 0.01f -> tr("{0}% leído", (chapter.progress * 100).toInt())
                    else -> null
                }
                if (chapter.dateUploaded.isNotBlank() || progressText != null) {
                    Row {
                        if (chapter.dateUploaded.isNotBlank()) {
                            Text(
                                chapter.dateUploaded,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f * contentAlpha)
                            )
                        }
                        progressText?.let {
                            Text(
                                (if (chapter.dateUploaded.isNotBlank()) "  ·  " else "") + it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            ReadToggle(chapter.read, isAnime, onToggleRead)
            DownloadIndicator(download, onDownload = onDownload, onDelete = onDeleteDownload)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (chapter.read) tr("Marcar como no visto/leído") else tr("Marcar como visto/leído")) },
                leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                onClick = { menu = false; onToggleRead() }
            )
            DropdownMenuItem(text = { Text(tr("Marcar anteriores como leídos")) }, onClick = { menu = false; onMarkPrevious() })
            DropdownMenuItem(text = { Text(tr("Quitar la marca a los anteriores")) }, onClick = { menu = false; onUnmarkPrevious() })
        }
    }
}

/** Small check: filled when read, a faint ring when not. */
@Composable
private fun ReadToggle(read: Boolean, isAnime: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        if (read) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = if (isAnime) tr("Marcar como no visto") else tr("Marcar como no leído"),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
        } else {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            )
        }
    }
}

/** 754 -> "12:34", 4000 -> "1:06:40". */
internal fun formatEpisodeTime(seconds: Int): String {
    val minutes = seconds / 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds % 60)
    else "%d:%02d".format(minutes, seconds % 60)
}

@Composable
private fun DownloadIndicator(download: DownloadEntity?, onDownload: () -> Unit, onDelete: () -> Unit) {
    when (download?.status) {
        null -> IconButton(onClick = onDownload) {
            Icon(Icons.Default.Download, contentDescription = tr("Descargar"), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
        }
        DownloadStatus.COMPLETED -> IconButton(onClick = onDelete) {
            Icon(Icons.Default.DownloadDone, contentDescription = tr("Borrar descarga"), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
        }
        DownloadStatus.FAILED -> IconButton(onClick = onDownload) {
            Icon(Icons.Default.ErrorOutline, contentDescription = tr("Reintentar descarga"), tint = MaterialTheme.colorScheme.error)
        }
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> IconButton(onClick = onDelete) {
            val progress = if (download.total > 0) download.progress.toFloat() / download.total else 0f
            if (download.status == DownloadStatus.DOWNLOADING && progress > 0f) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            } else {
                AppSpinner(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
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
        title = { Text(tr("La descarga falló")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error ?: tr("Error desconocido"), color = MaterialTheme.colorScheme.error)
                if (isAnime) {
                    Text(tr("Elige con qué servidor reintentar:"), style = MaterialTheme.typography.titleSmall)
                    when {
                        servers == null -> AppSpinner(Modifier.size(24.dp))
                        servers.isFailure -> Text(tr("No se pudieron cargar los servidores: {0}", servers.exceptionOrNull()?.message))
                        else -> {
                            val all = servers.getOrThrow()
                            all.filter(EpisodeServers::isDownloadable).forEach { server ->
                                TextButton(onClick = { onRetry(server.serverName) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("${server.serverName} • ${server.quality}", modifier = Modifier.fillMaxWidth())
                                }
                            }
                            EpisodeServers.browserOnly(all).takeIf { it.isNotEmpty() }?.let { names ->
                                Text(tr("Solo en el navegador: {0}", names.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRetry(null) }) { Text(if (isAnime) tr("Probar todos") else tr("Reintentar")) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(tr("Quitar descarga")) } }
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
                label = { Text(tr("Estado: ") + state.readingStatus.displayName) },
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
            label = { Text(if (selectedNames.isEmpty()) tr("Categorías") else selectedNames.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(18.dp)) }
        )
        FilterChip(
            selected = media.notifyUpdates,
            onClick = { onNotify(!media.notifyUpdates) },
            label = { Text(if (media.notifyUpdates) "Avisos activos" else tr("Sin avisos")) },
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
        title = { Text(tr("Categorías")) },
        text = {
            Column {
                if (categories.isEmpty()) {
                    Text(tr("Aún no tienes categorías. Crea una abajo (p. ej. Favoritos, Isekai, Para releer)."))
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
                        label = { Text(tr("Nueva categoría")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onCreate(newName); newName = "" },
                        enabled = newName.isNotBlank() && categories.none { it.name.equals(newName.trim(), ignoreCase = true) }
                    ) { Icon(Icons.Default.Add, contentDescription = tr("Crear")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text(tr("Guardar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } }
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
        title = { Text(tr("Descargar un rango")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Números de capítulo o episodio, ambos incluidos."))
                androidx.compose.material3.OutlinedTextField(from, { from = it }, label = { Text(tr("Desde")) }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(to, { to = it }, label = { Text(tr("Hasta")) }, singleLine = true)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = fromValue != null && toValue != null,
                onClick = { onConfirm(fromValue!!, toValue!!); onDismiss() }
            ) { Text(tr("Descargar")) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } }
    )
}
