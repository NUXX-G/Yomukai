package com.nelson.yomukai.ui.reader

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
    var overlayVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    ReaderWindowEffects(settings.brightness, settings.keepScreenOn, immersive = !overlayVisible)

    val pages = state.content
    val chapter = state.chapter
    // Current page and slider jump requests, reset per chapter.
    var currentPage by remember(chapter?.chapterId, pages) { mutableIntStateOf(state.initialPosition) }
    var jumpTarget by remember(chapter?.chapterId, pages) { mutableStateOf<Int?>(null) }

    // Warm Coil's caches for the next pages so they are ready before the reader scrolls to them.
    val appContext = LocalContext.current
    LaunchedEffect(currentPage, pages) {
        val list = pages ?: return@LaunchedEffect
        val loader = appContext.imageLoader
        for (i in currentPage + 1..minOf(currentPage + PREFETCH_AHEAD, list.lastIndex)) {
            val page = list[i]
            val request = ImageRequest.Builder(appContext).data(page.model).apply {
                if (page.headers.isNotEmpty()) {
                    httpHeaders(NetworkHeaders.Builder().apply { page.headers.forEach { (k, v) -> set(k, v) } }.build())
                }
            }.build()
            loader.enqueue(request)
        }
    }

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

            state.error != null || pages.isNullOrEmpty() -> Column(
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

            else -> key(chapter?.chapterId, settings.mode) {
                val toggleOverlay = { overlayVisible = !overlayVisible }
                val footer: @Composable () -> Unit = {
                    ChapterEndFooter(state.next, state.previous, viewModel::goToNext, viewModel::goToPrevious)
                }
                if (settings.mode == MangaReaderMode.WEBTOON) {
                    WebtoonReader(
                        pages = pages,
                        initialPage = currentPage,
                        jumpTarget = jumpTarget,
                        onJumpHandled = { jumpTarget = null },
                        onPageChanged = { currentPage = it; viewModel.onPageChanged(it) },
                        onTap = toggleOverlay,
                        footer = footer
                    )
                } else {
                    PagedReader(
                        pages = pages,
                        rtl = settings.mode == MangaReaderMode.PAGED_RTL,
                        tapToTurn = settings.tapToTurn,
                        initialPage = currentPage,
                        jumpTarget = jumpTarget,
                        onJumpHandled = { jumpTarget = null },
                        onPageChanged = { currentPage = it; viewModel.onPageChanged(it) },
                        onToggleOverlay = toggleOverlay,
                        footer = footer
                    )
                }
            }
        }

        if (!overlayVisible && settings.showPageNumber && !pages.isNullOrEmpty()) {
            Text(
                "${currentPage + 1} / ${pages.size}",
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
                val total = pages?.size ?: 0
                if (total > 1) {
                    Text("${currentPage + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    val direction = if (settings.mode == MangaReaderMode.PAGED_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        Slider(
                            value = currentPage.toFloat(),
                            onValueChange = { currentPage = it.toInt(); jumpTarget = it.toInt() },
                            valueRange = 0f..(total - 1).toFloat(),
                            steps = (total - 2).coerceAtLeast(0),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                    }
                    Text("$total", color = Color.White, style = MaterialTheme.typography.labelMedium)
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

@Composable
private fun PagedReader(
    pages: List<PageData>,
    rtl: Boolean,
    tapToTurn: Boolean,
    initialPage: Int,
    jumpTarget: Int?,
    onJumpHandled: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onToggleOverlay: () -> Unit,
    footer: @Composable () -> Unit
) {
    // One extra trailing page shows the end-of-chapter navigation.
    val pagerState: PagerState = rememberPagerState(initialPage = initialPage) { pages.size + 1 }
    val zoomStates = remember { mutableMapOf<Int, ZoomState>() }
    val scope = rememberCoroutineScope()
    fun zoomFor(page: Int) = zoomStates.getOrPut(page) { ZoomState() }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            zoomStates.filterKeys { it != settled }.values.forEach { it.reset() }
            onPageChanged(settled.coerceAtMost(pages.size - 1))
        }
    }
    LaunchedEffect(jumpTarget) {
        jumpTarget?.let { pagerState.scrollToPage(it); onJumpHandled() }
    }

    HorizontalPager(
        state = pagerState,
        reverseLayout = rtl,
        beyondViewportPageCount = 1,
        userScrollEnabled = !zoomFor(pagerState.currentPage).isZoomed,
        key = { it },
        modifier = Modifier.fillMaxSize()
    ) { page ->
        if (page == pages.size) {
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
                            !tapToTurn || zoomFor(page).isZoomed -> onToggleOverlay()
                            goBackward -> if (rtl) nextPage() else previousPage()
                            goForward -> if (rtl) previousPage() else nextPage()
                            else -> onToggleOverlay()
                        }
                    }),
                contentAlignment = Alignment.Center
            ) {
                ReaderPage(pages[page], page, fitScreen = true)
            }
        }
    }
}

@Composable
internal fun ReaderPage(page: PageData, index: Int, fitScreen: Boolean) {
    var retry by remember { mutableIntStateOf(0) }
    var imageState by remember(retry) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val loaded = imageState is AsyncImagePainter.State.Success

    Box(
        modifier = if (fitScreen) Modifier.fillMaxSize()
        else Modifier
            .fillMaxWidth()
            .heightIn(min = if (loaded) 0.dp else 480.dp),
        contentAlignment = Alignment.Center
    ) {
        key(retry) {
            AsyncImage(
                model = rememberImageRequest(page.model, page.headers),
                contentDescription = "Página ${index + 1}",
                contentScale = if (fitScreen) ContentScale.Fit else ContentScale.FillWidth,
                onState = { imageState = it },
                modifier = if (fitScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
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
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Modo de lectura", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            MangaReaderMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = settings.mode == mode, onClick = { onUpdate { it.copy(mode = mode) } })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = settings.mode == mode, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(mode.displayName)
                }
            }

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
