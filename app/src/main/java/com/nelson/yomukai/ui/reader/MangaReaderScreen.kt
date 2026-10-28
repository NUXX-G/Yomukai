package com.nelson.yomukai.ui.reader

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import com.nelson.yomukai.data.prefs.OrientationLock
import com.nelson.yomukai.data.prefs.ReaderColorFilter
import com.nelson.yomukai.data.prefs.ReaderFit
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import coil3.request.ImageRequest
import coil3.imageLoader
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.prefs.MangaReaderMode
import com.nelson.yomukai.data.prefs.MangaReaderSettings
import com.nelson.yomukai.data.repository.PageData
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.rememberImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MangaReaderScreen(
    onBack: () -> Unit,
    viewModel: MangaReaderViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chapterList by viewModel.chapterList.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    var overlayVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showIndex by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }

    ReaderWindowEffects(settings.brightness, settings.keepScreenOn, immersive = !overlayVisible)
    ReaderOrientationEffect(settings.orientation)

    val webtoon = settings.mode == MangaReaderMode.WEBTOON
    val segments = state.segments
    val chapter = state.chapter
    val items = remember(segments, webtoon) { ReaderItems.build(segments, withHeaders = webtoon) }

    // Where the user is: chapter and page within it. Reset whenever the list restarts.
    var location by remember(state.generation) {
        mutableStateOf(segments.firstOrNull()?.let { it.chapter.chapterId to it.initialPosition })
    }
    var jumpIndex by remember(state.generation) { mutableStateOf<Int?>(null) }
    val currentChapterId = location?.first
    val currentPage = location?.second ?: 0
    val totalPages = segments.firstOrNull { it.chapter.chapterId == currentChapterId }?.content?.size ?: 0
    val bookmarked = bookmarks.any { it.chapterId == currentChapterId && it.position == currentPage }

    // Warm Coil's caches for the next pages so they are ready before the reader scrolls to them.
    val appContext = LocalContext.current
    LaunchedEffect(location, items) {
        val here = location?.let { ReaderItems.indexOf(items, it.first, it.second) } ?: return@LaunchedEffect
        val loader = appContext.imageLoader
        items.drop(here + 1).filterIsInstance<ReaderItem.Page>().take(PREFETCH_AHEAD).forEach { item ->
            loader.enqueue(
                ImageRequest.Builder(appContext).data(item.data.model).apply {
                    if (item.data.headers.isNotEmpty()) {
                        httpHeaders(NetworkHeaders.Builder().apply { item.data.headers.forEach { (k, v) -> set(k, v) } }.build())
                    }
                }.build()
            )
        }
    }

    val onLocation: (String, Int) -> Unit = { chapterId, page ->
        location = chapterId to page
        viewModel.onChapterVisible(chapterId)
        val total = segments.firstOrNull { it.chapter.chapterId == chapterId }?.content?.size ?: 0
        viewModel.onPageChanged(chapterId, page, total)
    }
    val onNearEnd: () -> Unit = { if (settings.infiniteScroll) viewModel.loadNext() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            state.isLoading -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(chapter?.title ?: "Cargando...", color = Color.White.copy(alpha = 0.8f))
            }

            state.error != null || items.none { it is ReaderItem.Page } -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.BrokenImage, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(state.error ?: "El capítulo no tiene páginas", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Volver") }
                    Button(onClick = viewModel::retry) { Text("Reintentar") }
                }
            }

            else -> key(state.generation, settings.mode) {
                val toggleOverlay = { overlayVisible = !overlayVisible }
                val footer: @Composable () -> Unit = {
                    ChapterEndFooter(
                        next = state.next,
                        previous = state.previous,
                        loadingNext = state.isLoadingNext,
                        nextError = state.nextError,
                        onNext = viewModel::goToNext,
                        onPrevious = viewModel::goToPrevious,
                        onRetryNext = viewModel::loadNext
                    )
                }
                val start = segments.first()
                if (webtoon) {
                    ContinuousWebtoon(
                        items = items,
                        startIndex = ReaderItems.indexOf(items, start.chapter.chapterId, start.initialPosition),
                        jumpIndex = jumpIndex,
                        onJumpHandled = { jumpIndex = null },
                        settings = settings,
                        autoScroll = autoScroll,
                        onLocation = onLocation,
                        onNearEnd = onNearEnd,
                        onTap = toggleOverlay,
                        footer = footer
                    )
                } else {
                    ContinuousPaged(
                        pages = items.filterIsInstance<ReaderItem.Page>(),
                        rtl = settings.mode == MangaReaderMode.PAGED_RTL,
                        settings = settings,
                        startChapterId = start.chapter.chapterId,
                        startPage = start.initialPosition,
                        jumpTo = jumpIndex,
                        onJumpHandled = { jumpIndex = null },
                        onLocation = onLocation,
                        onNearEnd = onNearEnd,
                        onToggleOverlay = toggleOverlay,
                        footer = footer
                    )
                }
            }
        }

        if (!overlayVisible && settings.showPageNumber && totalPages > 0) {
            Text(
                "${currentPage + 1} / $totalPages",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.mediaTitle,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        chapter?.title.orEmpty(),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = {
                    currentChapterId?.let { viewModel.toggleBookmark(it, currentPage) }
                }) {
                    Icon(
                        if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = if (bookmarked) "Quitar marcador" else "Añadir marcador",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { showBookmarks = true }) {
                    Icon(Icons.Default.Bookmarks, contentDescription = "Marcadores", tint = Color.White)
                }
                IconButton(onClick = { showIndex = true }) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Índice de capítulos", tint = Color.White)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Ajustes del lector", tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = viewModel::goToPrevious, enabled = state.previous != null) {
                    Icon(Icons.Default.SkipPrevious, "Capítulo anterior", tint = if (state.previous != null) Color.White else Color.Gray)
                }
                if (webtoon) {
                    IconButton(onClick = { autoScroll = !autoScroll; if (autoScroll) overlayVisible = false }) {
                        Icon(
                            if (autoScroll) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (autoScroll) "Detener desplazamiento automático" else "Desplazamiento automático",
                            tint = Color.White
                        )
                    }
                }
                if (totalPages > 1) {
                    Text("${currentPage + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    val direction = if (settings.mode == MangaReaderMode.PAGED_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        Slider(
                            value = currentPage.toFloat().coerceIn(0f, (totalPages - 1).toFloat()),
                            onValueChange = {
                                val page = it.toInt()
                                currentChapterId?.let { id ->
                                    location = id to page
                                    jumpIndex = ReaderItems.indexOf(items, id, page)
                                }
                            },
                            valueRange = 0f..(totalPages - 1).toFloat(),
                            steps = (totalPages - 2).coerceAtLeast(0),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                    }
                    Text("$totalPages", color = Color.White, style = MaterialTheme.typography.labelMedium)
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = viewModel::goToNext, enabled = state.next != null) {
                    Icon(Icons.Default.SkipNext, "Capítulo siguiente", tint = if (state.next != null) Color.White else Color.Gray)
                }
            }
        }
    }

    if (showSettings) {
        MangaReaderSettingsSheet(settings, onUpdate = viewModel::updateSettings, onDismiss = { showSettings = false })
    }
    if (showIndex) {
        ChapterIndexSheet(
            chapters = chapterList,
            currentChapterId = chapter?.chapterId,
            onSelect = { viewModel.jumpToChapter(it.chapterId) },
            onDismiss = { showIndex = false }
        )
    }
    if (showBookmarks) {
        BookmarksSheet(
            bookmarks = bookmarks,
            positionLabel = { "Página ${it + 1}" },
            onOpen = { b ->
                val index = ReaderItems.indexOf(items, b.chapterId, b.position)
                if (segments.any { it.chapter.chapterId == b.chapterId } && items.getOrNull(index) != null) {
                    location = b.chapterId to b.position
                    jumpIndex = index
                } else {
                    viewModel.jumpToChapter(b.chapterId, b.position)
                }
            },
            onDelete = { viewModel.deleteBookmark(it.id) },
            onDismiss = { showBookmarks = false }
        )
    }
}

private fun readerColorFilter(filter: ReaderColorFilter): ColorFilter? = when (filter) {
    ReaderColorFilter.NONE -> null
    ReaderColorFilter.GRAYSCALE -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    ReaderColorFilter.SEPIA -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    ReaderColorFilter.INVERT -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}

/** Vertical strip of every loaded chapter with a separator before each one; appends the next chapter near the end. */
@Composable
private fun ContinuousWebtoon(
    items: List<ReaderItem>,
    startIndex: Int,
    jumpIndex: Int?,
    onJumpHandled: () -> Unit,
    settings: MangaReaderSettings,
    autoScroll: Boolean,
    onLocation: (String, Int) -> Unit,
    onNearEnd: () -> Unit,
    onTap: () -> Unit,
    footer: @Composable () -> Unit
) {
    val listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val zoom = remember { ZoomState(maxScale = 3f) }
    val scope = rememberCoroutineScope()
    val currentItems by rememberUpdatedState(items)
    val currentOnLocation by rememberUpdatedState(onLocation)
    val currentOnNearEnd by rememberUpdatedState(onNearEnd)
    val colorFilter = remember(settings.colorFilter) { readerColorFilter(settings.colorFilter) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index -> ReaderItems.locate(currentItems, index)?.let { (c, p) -> currentOnLocation(c, p) } }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last -> if (ReaderItems.nearEnd(currentItems, last)) currentOnNearEnd() }
    }
    LaunchedEffect(jumpIndex) {
        jumpIndex?.let { listState.scrollToItem(it); onJumpHandled() }
    }
    // Auto-scroll: a steady pixel rate per frame; user dragging takes over and it resumes afterwards.
    LaunchedEffect(autoScroll, settings.autoScrollSpeed) {
        if (!autoScroll) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            listState.scrollBy(settings.autoScrollSpeed * AUTO_SCROLL_PX_PER_SECOND * dt)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .zoomable(zoom, scope, lockVerticalPan = true, onTap = { _, _ -> onTap() })
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ReaderItem.Header -> ChapterSeparator(item.chapter.title)
                is ReaderItem.Page -> ReaderPageImage(
                    item.data, item.index, ReaderFit.WIDTH, fitScreen = false,
                    colorFilter = colorFilter, cropBorders = settings.cropBorders
                )
            }
        }
        item(key = "footer") { footer() }
    }
}

/** Paged reader over the pages of every loaded chapter; swiping past the last page enters the next chapter. */
@Composable
private fun ContinuousPaged(
    pages: List<ReaderItem.Page>,
    rtl: Boolean,
    settings: MangaReaderSettings,
    startChapterId: String,
    startPage: Int,
    jumpTo: Int?,
    onJumpHandled: () -> Unit,
    onLocation: (String, Int) -> Unit,
    onNearEnd: () -> Unit,
    onToggleOverlay: () -> Unit,
    footer: @Composable () -> Unit
) {
    // One extra trailing page shows the end-of-list navigation (only visible when no next chapter follows).
    val startIndex = remember { pages.indexOfFirst { it.chapterId == startChapterId && it.index == startPage }.coerceAtLeast(0) }
    val pagerState: PagerState = rememberPagerState(initialPage = startIndex) { pages.size + 1 }
    val zoomStates = remember { mutableMapOf<Int, ZoomState>() }
    val scope = rememberCoroutineScope()
    val currentPages by rememberUpdatedState(pages)
    val currentOnLocation by rememberUpdatedState(onLocation)
    val currentOnNearEnd by rememberUpdatedState(onNearEnd)
    val colorFilter = remember(settings.colorFilter) { readerColorFilter(settings.colorFilter) }
    fun zoomFor(page: Int) = zoomStates.getOrPut(page) { ZoomState() }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            zoomStates.filterKeys { it != settled }.values.forEach { it.reset() }
            currentPages.getOrNull(settled)?.let { currentOnLocation(it.chapterId, it.index) }
            if (settled >= currentPages.size - 1 - 3) currentOnNearEnd()
        }
    }
    LaunchedEffect(jumpTo) {
        jumpTo?.let { pagerState.scrollToPage(it.coerceIn(0, pages.size - 1)); onJumpHandled() }
    }

    HorizontalPager(
        state = pagerState,
        reverseLayout = rtl,
        beyondViewportPageCount = 1,
        userScrollEnabled = !zoomFor(pagerState.currentPage).isZoomed,
        key = { if (it < pages.size) pages[it].key else "end" },
        modifier = Modifier.fillMaxSize()
    ) { page ->
        if (page >= pages.size) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { footer() }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .zoomable(zoomFor(page), scope, onTap = { offset, size ->
                        val third = size.width / 3f
                        val goBackward = offset.x < third
                        val goForward = offset.x > size.width - third
                        val previousPage = { scope.launch { pagerState.animateScrollToPage((page - 1).coerceAtLeast(0)) } }
                        val nextPage = { scope.launch { pagerState.animateScrollToPage(page + 1) } }
                        when {
                            !settings.tapToTurn || zoomFor(page).isZoomed -> onToggleOverlay()
                            goBackward -> if (rtl) nextPage() else previousPage()
                            goForward -> if (rtl) previousPage() else nextPage()
                            else -> onToggleOverlay()
                        }
                    }),
                contentAlignment = Alignment.Center
            ) {
                val item = pages[page]
                ReaderPageImage(
                    item.data, item.index, settings.fit, fitScreen = true,
                    colorFilter = colorFilter, cropBorders = settings.cropBorders
                )
            }
        }
    }
}

@Composable
private fun ChapterSeparator(title: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
            .padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun WebtoonReader(
    pages: List<PageData>,
    initialPage: Int,
    jumpTarget: Int?,
    onJumpHandled: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    footer: @Composable () -> Unit
) {
    val listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val zoom = remember { ZoomState(maxScale = 3f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= pages.size) pages.size - 1 else listState.firstVisibleItemIndex.coerceIn(0, pages.size - 1)
        }.distinctUntilChanged().collect(onPageChanged)
    }
    LaunchedEffect(jumpTarget) {
        jumpTarget?.let { listState.scrollToItem(it); onJumpHandled() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .zoomable(zoom, scope, lockVerticalPan = true, onTap = { _, _ -> onTap() })
    ) {
        itemsIndexed(pages, key = { index, _ -> index }) { index, page ->
            ReaderPage(page, index, fitScreen = false)
        }
        item { footer() }
    }
}


/** Legacy entry point used by the quick reader (captured pages). */
@Composable
internal fun ReaderPage(page: PageData, index: Int, fitScreen: Boolean) {
    ReaderPageImage(page, index, ReaderFit.SCREEN, fitScreen, colorFilter = null, cropBorders = false)
}

@Composable
internal fun ReaderPageImage(
    page: PageData,
    index: Int,
    fit: ReaderFit,
    fitScreen: Boolean,
    colorFilter: ColorFilter?,
    cropBorders: Boolean
) {
    var retry by remember { mutableIntStateOf(0) }
    var imageState by remember(retry) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val loaded = imageState is AsyncImagePainter.State.Success
    val scrollWidthFit = fitScreen && fit == ReaderFit.WIDTH

    Box(
        modifier = if (fitScreen) Modifier.fillMaxSize()
        else Modifier
            .fillMaxWidth()
            .heightIn(min = if (loaded) 0.dp else 480.dp),
        contentAlignment = if (scrollWidthFit) Alignment.TopCenter else Alignment.Center
    ) {
        key(retry) {
            val imageModifier = when {
                !fitScreen -> Modifier.fillMaxWidth()
                scrollWidthFit -> Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                else -> Modifier.fillMaxSize()
            }
            AsyncImage(
                model = rememberImageRequest(page.model, page.headers, cropBorders),
                contentDescription = "Página ${index + 1}",
                contentScale = when {
                    !fitScreen -> ContentScale.FillWidth
                    fit == ReaderFit.WIDTH -> ContentScale.FillWidth
                    fit == ReaderFit.HEIGHT -> ContentScale.FillHeight
                    else -> ContentScale.Fit
                },
                colorFilter = colorFilter,
                onState = { imageState = it },
                modifier = imageModifier
            )
        }
        when (imageState) {
            is AsyncImagePainter.State.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.BrokenImage, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                Text("Error en la página ${index + 1}", color = Color.White.copy(alpha = 0.7f))
                TextButton(onClick = { retry++ }) { Text("Reintentar") }
            }
            is AsyncImagePainter.State.Success -> Unit
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text("${index + 1}", color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun ChapterEndFooter(
    next: ChapterEntity?,
    previous: ChapterEntity?,
    loadingNext: Boolean,
    nextError: String?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRetryNext: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            loadingNext -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Cargando ${next?.title ?: "el siguiente capítulo"}...", color = Color.White.copy(alpha = 0.7f))
            }
            nextError != null -> {
                Text(nextError, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetryNext) { Text("Reintentar") }
            }
            else -> {
                Text("Fin del capítulo", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    next?.let { "Siguiente: ${it.title}" } ?: "No hay más capítulos disponibles",
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (previous != null) OutlinedButton(onClick = onPrevious) { Text("Anterior") }
                    if (next != null) Button(onClick = onNext) { Text("Siguiente capítulo") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaReaderSettingsSheet(
    settings: MangaReaderSettings,
    onUpdate: ((MangaReaderSettings) -> MangaReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Modo de lectura", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            MangaReaderMode.entries.forEach { mode ->
                RadioRow(mode.displayName, settings.mode == mode) { onUpdate { it.copy(mode = mode) } }
            }
            SettingSwitch("Cargar el siguiente capítulo automáticamente", settings.infiniteScroll) { v ->
                onUpdate { it.copy(infiniteScroll = v) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Ajuste de imagen (modo páginas)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ReaderFit.entries.forEach { fit -> RadioRow(fit.displayName, settings.fit == fit) { onUpdate { it.copy(fit = fit) } } }
            SettingSwitch("Recortar bordes blancos", settings.cropBorders) { v -> onUpdate { it.copy(cropBorders = v) } }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Filtro de color", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ReaderColorFilter.entries.forEach { f ->
                RadioRow(f.displayName, settings.colorFilter == f) { onUpdate { it.copy(colorFilter = f) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Orientación", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OrientationLock.entries.forEach { o ->
                RadioRow(o.displayName, settings.orientation == o) { onUpdate { it.copy(orientation = o) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Desplazamiento automático", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Velocidad: ${settings.autoScrollSpeed.toInt()}", style = MaterialTheme.typography.bodySmall)
            var speed by remember { mutableFloatStateOf(settings.autoScrollSpeed) }
            Slider(
                value = speed,
                onValueChange = { speed = it },
                onValueChangeFinished = { onUpdate { it.copy(autoScrollSpeed = speed.toInt().toFloat().coerceIn(1f, 10f)) } },
                valueRange = 1f..10f,
                steps = 8
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Brillo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SettingSwitch("Usar brillo del sistema", settings.brightness < 0f) { auto ->
                onUpdate { it.copy(brightness = if (auto) -1f else 0.5f) }
            }
            if (settings.brightness >= 0f) {
                var local by remember { mutableFloatStateOf(settings.brightness) }
                Slider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { onUpdate { it.copy(brightness = local) } },
                    valueRange = 0.02f..1f
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SettingSwitch("Mantener pantalla encendida", settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }
            SettingSwitch("Tocar los bordes para pasar página", settings.tapToTurn) { v -> onUpdate { it.copy(tapToTurn = v) } }
            SettingSwitch("Mostrar número de página", settings.showPageNumber) { v -> onUpdate { it.copy(showPageNumber = v) } }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
internal fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChange(!checked) })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private const val PREFETCH_AHEAD = 3
private const val AUTO_SCROLL_PX_PER_SECOND = 40f
