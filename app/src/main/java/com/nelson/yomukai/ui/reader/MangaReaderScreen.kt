package com.nelson.yomukai.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.prefs.MangaReaderMode
import com.nelson.yomukai.data.prefs.MangaReaderSettings
import com.nelson.yomukai.data.prefs.OrientationLock
import com.nelson.yomukai.data.prefs.ReaderColorFilter
import com.nelson.yomukai.data.prefs.ReaderFit
import com.nelson.yomukai.data.repository.PageData
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.AppSpinner
import com.nelson.yomukai.ui.components.rememberImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MangaReaderScreen(
    onBack: () -> Unit,
    /** The work's page, always one tap away (F1.3). */
    onOpenDetails: (() -> Unit)? = null,
    viewModel: MangaReaderViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Compartir (F4.3): the chapter on screen, to a person, a room or the work's thread.
    var shareOpen by remember { mutableStateOf(false) }
    val canShare = com.nelson.yomukai.ui.LocalAppContainer.current.serverSync.serverUrl != null && state.chapter != null
    if (shareOpen) viewModel.shareCard()?.let { card -> com.nelson.yomukai.ui.social.ShareSheet(card) { shareOpen = false } } ?: run { shareOpen = false }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chapterList by viewModel.chapterList.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    var overlayVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showIndex by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }
    val speaker = com.nelson.yomukai.ui.LocalAppContainer.current.bubbleSpeaker

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

    // Downloads the next pages ahead (to the disk cache, not decoded: that would fill the memory with
    // bitmaps never shown), so a page is already on the phone when the reader scrolls to it. Pages
    // already asked for are not asked again on every page turn.
    val appContext = LocalContext.current
    val prefetched = remember(state.generation) { HashSet<Any>() }
    LaunchedEffect(location, items) {
        val here = location?.let { ReaderItems.indexOf(items, it.first, it.second) } ?: return@LaunchedEffect
        val loader = appContext.imageLoader
        items.drop(here + 1).filterIsInstance<ReaderItem.Page>().take(PREFETCH_AHEAD).forEach { item ->
            if (!prefetched.add(item.data.model)) return@forEach
            loader.enqueue(
                ImageRequest.Builder(appContext).data(item.data.model)
                    .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                    .size(1, 1)
                    .apply {
                        if (item.data.headers.isNotEmpty()) {
                            httpHeaders(NetworkHeaders.Builder().apply { item.data.headers.forEach { (k, v) -> set(k, v) } }.build())
                        }
                    }.build()
            )
        }
    }

    val onLocation: (String, Int) -> Unit = { chapterId, page ->
        if (location?.first != chapterId) speaker.reset()
        speaker.onPage(page)
        location = chapterId to page
        viewModel.onChapterVisible(chapterId)
        val total = segments.firstOrNull { it.chapter.chapterId == chapterId }?.content?.size ?: 0
        viewModel.onPageChanged(chapterId, page, total)
    }
    val onNearEnd: () -> Unit = { if (settings.infiniteScroll) viewModel.loadNext() }

    // Bubbles translated on the device when the source is in another language than the interface. The pages are
    // also read (on the phone, page by page as they show, cached) so a long press on any bubble gives its text and
    // translation (F6.3), with or without learning mode, whenever the work is in another language.
    val bubbleSpec = remember(settings.translateBubbles, settings.speakBubbles, viewModel.language) {
        val from = viewModel.language.lowercase()
        val to = com.nelson.yomukai.i18n.I18n.lang
        val translate = settings.translateBubbles && from != to
        val speak = settings.speakBubbles
        val learning = from != to
        val wanted = translate || learning || speak
        if (from.length == 2 && wanted) com.nelson.yomukai.data.ocr.BubbleTranslation(from, to, translate, learning, speak) else null
    }
    DisposableEffect(settings.speakBubbles) {
        speaker.enabled = settings.speakBubbles
        onDispose { speaker.enabled = false; speaker.reset() }
    }
    CompositionLocalProvider(LocalBubbleTranslation provides bubbleSpec) {
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
                AppSpinner()
                Spacer(Modifier.height(12.dp))
                Text(chapter?.title ?: tr("Cargando..."), color = Color.White.copy(alpha = 0.8f))
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
                Text(state.error ?: tr("El capítulo no tiene páginas"), color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text(tr("Volver")) }
                    Button(onClick = viewModel::retry) { Text(tr("Reintentar")) }
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

        // With the menus hidden only a small clock stays, in the top corner.
        if (!overlayVisible) {
            com.nelson.yomukai.ui.components.MiniClock(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                pill = true
            )
        }

        if (autoScroll && webtoon) {
            AutoScrollSpeedBar(
                speed = settings.autoScrollSpeed,
                onSpeed = { v -> viewModel.updateSettings { it.copy(autoScrollSpeed = v) } },
                onStop = { autoScroll = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (overlayVisible) 72.dp else if (settings.showPageNumber) 36.dp else 12.dp)
            )
        }

        // Bubbles read aloud: stopping is one tap away, without opening the settings.
        if (settings.speakBubbles && !overlayVisible) {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { viewModel.updateSettings { it.copy(speakBubbles = false) } },
                icon = { Icon(Icons.Default.Stop, contentDescription = null) },
                text = { Text(tr("Parar la lectura")) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (autoScroll && webtoon) 96.dp else 24.dp)
            )
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Volver"), tint = Color.White)
                }
                if (onOpenDetails != null) {
                    IconButton(onClick = onOpenDetails) {
                        Icon(Icons.Default.Info, contentDescription = tr("Ir a la ficha"), tint = Color.White)
                    }
                }
                if (canShare) {
                    IconButton(onClick = { shareOpen = true }) { Icon(Icons.AutoMirrored.Filled.Send, tr("Compartir"), tint = Color.White) }
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
                        contentDescription = if (bookmarked) "Quitar marcador" else tr("Añadir marcador"),
                        tint = Color.White
                    )
                }
                IconButton(onClick = { showBookmarks = true }) {
                    Icon(Icons.Default.Bookmarks, contentDescription = tr("Marcadores"), tint = Color.White)
                }
                IconButton(onClick = { showIndex = true }) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = tr("Índice de capítulos"), tint = Color.White)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = tr("Ajustes del lector"), tint = Color.White)
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
                    Icon(Icons.Default.SkipPrevious, tr("Capítulo anterior"), tint = if (state.previous != null) Color.White else Color.Gray)
                }
                if (webtoon) {
                    IconButton(onClick = { autoScroll = !autoScroll; if (autoScroll) overlayVisible = false }) {
                        Icon(
                            if (autoScroll) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (autoScroll) tr("Detener desplazamiento automático") else tr("Desplazamiento automático"),
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
                    Icon(Icons.Default.SkipNext, tr("Capítulo siguiente"), tint = if (state.next != null) Color.White else Color.Gray)
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
            positionLabel = { tr("Página {0}", it + 1) },
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
    // A short last page never reaches the top of the screen: the end of the list counts as having read it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount > 0 && !listState.canScrollForward }
            .distinctUntilChanged()
            .collect { atEnd ->
                if (atEnd) (currentItems.lastOrNull { it is ReaderItem.Page } as? ReaderItem.Page)
                    ?.let { currentOnLocation(it.chapterId, it.index) }
            }
    }
    AutoScroller(listState, autoScroll, settings)

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
                    colorFilter = colorFilter, cropBorders = settings.cropBorders,
                    profileKey = item.key.takeIf { settings.smartAutoScroll }
                )
            }
        }
        item(key = "footer") { footer() }
    }
}

/**
 * Auto-scroll: the user's pixel rate, and with smart autoscroll slower over text and quicker over plain panels and
 * gutters (eased, never jerky). The reader's speed over text is learnt from how they correct it.
 */
@Composable
private fun AutoScroller(listState: LazyListState, autoScroll: Boolean, settings: MangaReaderSettings) {
    val container = com.nelson.yomukai.ui.LocalAppContainer.current
    val profiles = container.scrollProfiles
    val prefs = container.preferences
    // Auto-scroll: the user's pixel rate, and with smart autoscroll slower over text and quicker over plain panels and
    // gutters (eased so it never jerks). User dragging takes over and it resumes afterwards.
    // The reader's own speed is learnt: pulling back while it scrolls means it went too fast over the text,
    // pushing on means too slow. A moving average, so one distraction does not change much.
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var dragFrom by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(dragged) {
        if (!autoScroll || !settings.smartAutoScroll) return@LaunchedEffect
        if (dragged) { dragFrom = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset; return@LaunchedEffect }
        val (index, offset) = dragFrom ?: return@LaunchedEffect
        dragFrom = null
        val back = listState.firstVisibleItemIndex < index || (listState.firstVisibleItemIndex == index && listState.firstVisibleItemScrollOffset < offset)
        prefs.updateManga {
            val wpm = com.nelson.yomukai.data.ocr.ScrollPlanner.learn(it.readingWpm.toDouble(), LEARN_EXPECTED_MS, if (back) LEARN_SLOWER_MS else LEARN_FASTER_MS)
            it.copy(readingWpm = wpm.toFloat())
        }
    }
    LaunchedEffect(autoScroll, settings.autoScrollSpeed, settings.smartAutoScroll, settings.readingWpm) {
        if (!autoScroll) return@LaunchedEffect
        var last = withFrameNanos { it }
        var factor = 1.0
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            if (settings.smartAutoScroll) {
                val info = listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val item = info.visibleItemsInfo.firstOrNull { it.offset <= center && it.offset + it.size > center }
                val target = item?.let { profiles.factorAt(it.key, (center - it.offset).toFloat() / it.size.coerceAtLeast(1), settings.readingWpm.toDouble()) } ?: 1.0
                factor += (target - factor) * minOf(1.0, dt * SMART_EASE)
            }
            listState.scrollBy((settings.autoScrollSpeed * AUTO_SCROLL_PX_PER_SECOND * dt * factor).toFloat())
        }
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
    cropBorders: Boolean,
    /** Long-strip mode with smart autoscroll: the list item whose speed profile this page gives. */
    profileKey: String? = null
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
                contentDescription = tr("Página {0}", index + 1),
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
                Text(tr("Error en la página {0}", index + 1), color = Color.White.copy(alpha = 0.7f))
                TextButton(onClick = { retry++ }) { Text(tr("Reintentar")) }
            }
            is AsyncImagePainter.State.Success -> Unit
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppSpinner(modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text("${index + 1}", color = Color.White.copy(alpha = 0.5f))
            }
        }
        (imageState as? AsyncImagePainter.State.Success)?.let { PageExtras(page, index, it.result.image, fit, fitScreen, cropBorders, profileKey) }
    }
}

/** What a loaded page adds over itself: its smart-autoscroll profile and its bubbles (translation, voice, learning). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PageExtras(
    page: PageData,
    index: Int,
    image: coil3.Image,
    fit: ReaderFit,
    fitScreen: Boolean,
    cropBorders: Boolean,
    profileKey: String?
) {
    val ocrKey = "${page.model}|$cropBorders"
    if (profileKey != null) {
        val profiles = com.nelson.yomukai.ui.LocalAppContainer.current.scrollProfiles
        LaunchedEffect(profileKey) { runCatching { profiles.compute(profileKey, page.model, page.headers, ocrKey) } }
    }
    val translation = LocalBubbleTranslation.current ?: return
    // A page fitted to the width inside its own scroll moves under the bubbles: no overlay there.
    if (fitScreen && fit == ReaderFit.WIDTH) return
    val mode = when {
        !fitScreen -> com.nelson.yomukai.data.ocr.FitMode.WIDTH
        fit == ReaderFit.HEIGHT -> com.nelson.yomukai.data.ocr.FitMode.HEIGHT
        else -> com.nelson.yomukai.data.ocr.FitMode.INSIDE
    }
    BubbleOverlay(ocrKey, index, image, translation, mode, Modifier.matchParentSize())
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
                AppSpinner()
                Spacer(Modifier.height(12.dp))
                Text(tr("Cargando {0}...", next?.title ?: tr("el siguiente capítulo")), color = Color.White.copy(alpha = 0.7f))
            }
            nextError != null -> {
                Text(nextError, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetryNext) { Text(tr("Reintentar")) }
            }
            else -> {
                Text(tr("Fin del capítulo"), color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    next?.let { "Siguiente: ${it.title}" } ?: tr("No hay más capítulos disponibles"),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (previous != null) OutlinedButton(onClick = onPrevious) { Text(tr("Anterior")) }
                    if (next != null) Button(onClick = onNext) { Text(tr("Siguiente capítulo")) }
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
            Text(tr("Modo de lectura"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            MangaReaderMode.entries.forEach { mode ->
                RadioRow(mode.displayName, settings.mode == mode) { onUpdate { it.copy(mode = mode) } }
            }
            SettingSwitch(tr("Cargar el siguiente capítulo automáticamente"), settings.infiniteScroll) { v ->
                onUpdate { it.copy(infiniteScroll = v) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(tr("Ajuste de imagen (modo páginas)"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ReaderFit.entries.forEach { fit -> RadioRow(fit.displayName, settings.fit == fit) { onUpdate { it.copy(fit = fit) } } }
            SettingSwitch(tr("Recortar bordes blancos"), settings.cropBorders) { v -> onUpdate { it.copy(cropBorders = v) } }
            SettingSwitch(tr("Traducir bocadillos en el móvil"), settings.translateBubbles) { v -> onUpdate { it.copy(translateBubbles = v) } }
            SettingSwitch(tr("Leer bocadillos en voz alta"), settings.speakBubbles) { v -> onUpdate { it.copy(speakBubbles = v) } }
            SettingSwitch(tr("Desplazamiento automático inteligente"), settings.smartAutoScroll) { v -> onUpdate { it.copy(smartAutoScroll = v) } }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(tr("Filtro de color"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ReaderColorFilter.entries.forEach { f ->
                RadioRow(f.displayName, settings.colorFilter == f) { onUpdate { it.copy(colorFilter = f) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(tr("Orientación"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OrientationLock.entries.forEach { o ->
                RadioRow(o.displayName, settings.orientation == o) { onUpdate { it.copy(orientation = o) } }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(tr("Desplazamiento automático"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            var speed by remember(settings.autoScrollSpeed) { mutableFloatStateOf(settings.autoScrollSpeed) }
            Text(tr("Velocidad: {0} (también con − y + mientras se desplaza)", AutoScrollSpeed.label(speed)), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = speed,
                onValueChange = { speed = it },
                onValueChangeFinished = { onUpdate { it.copy(autoScrollSpeed = speed.coerceIn(AutoScrollSpeed.MIN, AutoScrollSpeed.MAX)) } },
                valueRange = AutoScrollSpeed.MIN..AutoScrollSpeed.MAX
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(tr("Brillo"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            SettingSwitch(tr("Usar brillo del sistema"), settings.brightness < 0f) { auto ->
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
            SettingSwitch(tr("Mantener pantalla encendida"), settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }
            SettingSwitch(tr("Tocar los bordes para pasar página"), settings.tapToTurn) { v -> onUpdate { it.copy(tapToTurn = v) } }
            SettingSwitch(tr("Mostrar número de página"), settings.showPageNumber) { v -> onUpdate { it.copy(showPageNumber = v) } }
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

private const val PREFETCH_AHEAD = 8
private const val AUTO_SCROLL_PX_PER_SECOND = 40f
private const val SMART_EASE = 3.0
private const val LEARN_EXPECTED_MS = 1_000L
private const val LEARN_SLOWER_MS = 1_300L
private const val LEARN_FASTER_MS = 770L
