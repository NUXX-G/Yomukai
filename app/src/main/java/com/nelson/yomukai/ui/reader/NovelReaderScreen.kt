package com.nelson.yomukai.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.data.prefs.NovelFont
import com.nelson.yomukai.data.prefs.NovelReaderSettings
import com.nelson.yomukai.data.prefs.NovelTheme
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.components.AppSpinner
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

private fun NovelFont.family() = when (this) {
    NovelFont.SERIF -> FontFamily.Serif
    NovelFont.SANS -> FontFamily.SansSerif
    NovelFont.MONO -> FontFamily.Monospace
}

@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun NovelReaderScreen(
    onBack: () -> Unit,
    viewModel: NovelReaderViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chapterList by viewModel.chapterList.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    var overlayVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showIndex by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchPosition by remember { mutableIntStateOf(0) }

    val background = Color(settings.theme.background)
    val textColor = Color(settings.theme.text)
    val segments = state.segments
    val items = remember(segments) { NovelItems.build(segments) }

    ReaderWindowEffects(brightness = -1f, keepScreenOn = settings.keepScreenOn, immersive = !overlayVisible && !searchOpen)

    // Where the user is: chapter and paragraph within it. Reset whenever the list restarts.
    var location by remember(state.generation) {
        mutableStateOf(segments.firstOrNull()?.let { it.chapter.chapterId to it.initialPosition })
    }
    val currentChapterId = location?.first
    val currentParagraph = location?.second ?: 0
    val currentTotal = segments.firstOrNull { it.chapter.chapterId == currentChapterId }?.content?.size ?: 0
    val progress = if (currentTotal <= 1) 0f else (currentParagraph.toFloat() / (currentTotal - 1)).coerceIn(0f, 1f)
    val bookmarked = bookmarks.any { it.chapterId == currentChapterId && it.position == currentParagraph }
    var scrollTarget by remember(state.generation) { mutableStateOf<Int?>(null) }
    var autoScroll by remember { mutableStateOf(false) }

    val matches = remember(items, currentChapterId, searchQuery, searchOpen) {
        if (searchOpen) NovelItems.search(items, currentChapterId, searchQuery) else emptyList()
    }
    val activeMatch = matches.getOrNull(searchPosition.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))

    // Text to speech: reads across loaded chapters and asks for the next one when it runs out.
    val currentItems by rememberUpdatedState(items)
    val speech = rememberNovelSpeech(onNeedMore = viewModel::loadNext)
    val spokenIndex by speech.current.collectAsStateWithLifecycle()
    val speechReady by speech.ready.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel.language) {
        speech.setLanguage(if (viewModel.language == "ES") Locale("es") else if (viewModel.language == "EN") Locale.ENGLISH else Locale.getDefault())
    }
    LaunchedEffect(settings) { speech.update(settings) }
    // While reading aloud, a foreground service keeps it going with the screen off (notification, lock screen, headphones).
    val serviceContext = androidx.compose.ui.platform.LocalContext.current
    val reading = spokenIndex != null
    LaunchedEffect(reading, state.mediaTitle) {
        if (reading) {
            ReadAloud.onStop = { speech.stop() }
            ReadAloudService.start(serviceContext, state.mediaTitle, tr("Leyendo en voz alta"))
        } else {
            ReadAloudService.stop(serviceContext)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            ReadAloud.onStop = null
            ReadAloudService.stop(serviceContext)
        }
    }
    var batteryAsk by remember { mutableStateOf(false) }
    if (batteryAsk) BatteryOptimizationDialog(onDismiss = { batteryAsk = false })
    val speechNotice by speech.notice.collectAsStateWithLifecycle()
    val noticeContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(speechNotice) { speechNotice?.let { android.widget.Toast.makeText(noticeContext, it, android.widget.Toast.LENGTH_LONG).show() } }
    LaunchedEffect(items.size) { speech.onMoreContent() }
    val speechText: (Int) -> String? = { i ->
        when (val item = currentItems.getOrNull(i)) {
            is NovelItem.Paragraph -> item.text
            is NovelItem.Title -> item.chapter.title
            null -> null
        }
    }
    // Keep the spoken paragraph on screen.
    LaunchedEffect(spokenIndex) { spokenIndex?.let { scrollTarget = it } }
    LaunchedEffect(spokenIndex) { spokenIndex?.let { i -> NovelItems.locate(currentItems, i)?.let { location = it } } }

    Box(
        Modifier
            .fillMaxSize()
            .background(background)
    ) {
        when {
            state.isLoading -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AppSpinner()
                Spacer(Modifier.height(12.dp))
                Text(state.chapter?.title ?: tr("Cargando..."), color = textColor.copy(alpha = 0.7f))
            }

            state.error != null || items.none { it is NovelItem.Paragraph } -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(state.error ?: tr("El capítulo está vacío"), color = textColor, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text(tr("Volver")) }
                    Button(onClick = viewModel::retry) { Text(tr("Reintentar")) }
                }
            }

            else -> key(state.generation) {
                val start = segments.first()
                val startIndex = NovelItems.indexOf(items, start.chapter.chapterId, start.initialPosition)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
                val currentOnNearEnd by rememberUpdatedState(viewModel::loadNext)

                LaunchedEffect(listState) {
                    snapshotFlow { listState.firstVisibleItemIndex }
                        .distinctUntilChanged()
                        .collect { index ->
                            val (chapterId, paragraph) = NovelItems.locate(currentItems, index) ?: return@collect
                            location = chapterId to paragraph
                            viewModel.onChapterVisible(chapterId)
                        }
                }
                LaunchedEffect(listState) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                        .distinctUntilChanged()
                        .collect { last ->
                            if (settings.infiniteScroll && last >= currentItems.lastIndex - 3) currentOnNearEnd()
                        }
                }
                // Persist reading progress of the chapter on screen (debounced).
                LaunchedEffect(listState) {
                    snapshotFlow { location }
                        .distinctUntilChanged()
                        .debounce(400)
                        .collect { loc ->
                            val (chapterId, paragraph) = loc ?: return@collect
                            val total = segments.firstOrNull { it.chapter.chapterId == chapterId }?.content?.size ?: return@collect
                            val atEnd = paragraph >= total - 1 ||
                                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let { v ->
                                    (currentItems.getOrNull(v.index) as? NovelItem.Paragraph)?.let { it.chapterId == chapterId && it.index >= total - 1 }
                                } == true)
                            viewModel.onProgress(chapterId, if (total <= 1) 1f else paragraph.toFloat() / (total - 1), atEnd)
                        }
                }
                LaunchedEffect(scrollTarget) {
                    scrollTarget?.let { listState.animateScrollToItem(it); scrollTarget = null }
                }
                LaunchedEffect(activeMatch) {
                    activeMatch?.let { listState.scrollToItem(it) }
                }
                // Auto-scroll: a steady glide; a tap on the text stops it, the floating bar sets its speed.
                LaunchedEffect(autoScroll, settings.autoScrollSpeed) {
                    if (!autoScroll) return@LaunchedEffect
                    var last = androidx.compose.runtime.withFrameNanos { it }
                    while (true) {
                        val now = androidx.compose.runtime.withFrameNanos { it }
                        val dt = (now - last) / 1_000_000_000f
                        last = now
                        listState.scrollBy(settings.autoScrollSpeed * NOVEL_AUTO_SCROLL_PX_PER_SECOND * dt)
                        if (!listState.canScrollForward && !state.isLoadingNext) { autoScroll = false; break }
                    }
                }

                val bodyStyle = TextStyle(
                    color = textColor,
                    fontSize = settings.fontSize.sp,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp,
                    fontFamily = settings.font.family(),
                    textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start
                )
                val highlight = textColor.copy(alpha = 0.18f)

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = settings.horizontalPadding.dp,
                        end = settings.horizontalPadding.dp,
                        // Bar-independent insets so text does not jump when entering immersive mode.
                        top = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + 72.dp,
                        bottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding() + 72.dp
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { if (autoScroll) autoScroll = false else overlayVisible = !overlayVisible }
                ) {
                    itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
                        when (item) {
                            is NovelItem.Title -> Text(
                                item.chapter.title,
                                style = bodyStyle.copy(
                                    fontSize = (settings.fontSize + 6).sp,
                                    lineHeight = ((settings.fontSize + 6) * 1.3f).sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Start
                                ),
                                modifier = Modifier.padding(top = if (index == 0) 0.dp else 48.dp, bottom = 24.dp)
                            )

                            is NovelItem.Paragraph -> {
                                val marked = index == activeMatch || index == spokenIndex
                                Text(
                                    text = if (searchOpen && item.chapterId == currentChapterId && index in matches) {
                                        highlightMatches(item.text, searchQuery, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                                    } else AnnotatedString(item.text),
                                    style = bodyStyle,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (marked) highlight else Color.Transparent)
                                        .padding(bottom = (settings.fontSize * 0.9f).dp)
                                )
                            }
                        }
                    }
                    item(key = "footer") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when {
                                state.isLoadingNext -> {
                                    AppSpinner()
                                    Spacer(Modifier.height(12.dp))
                                    Text(tr("Cargando {0}...", state.next?.title ?: tr("el siguiente capítulo")), color = textColor.copy(alpha = 0.7f))
                                }
                                state.nextError != null -> {
                                    Text(state.nextError.orEmpty(), color = textColor.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = viewModel::loadNext) { Text(tr("Reintentar")) }
                                }
                                else -> {
                                    Text(
                                        if (state.next == null) tr("No hay más capítulos disponibles") else tr("Fin del capítulo"),
                                        color = textColor.copy(alpha = 0.7f)
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        if (state.previous != null) OutlinedButton(onClick = viewModel::goToPrevious) { Text(tr("Anterior")) }
                                        if (state.next != null) Button(onClick = viewModel::goToNext) { Text(tr("Siguiente capítulo")) }
                                    }
                                }
                            }
                        }
                    }
                }

                if (autoScroll) {
                    AutoScrollSpeedBar(
                        speed = settings.autoScrollSpeed,
                        onSpeed = { v -> viewModel.updateSettings { it.copy(autoScrollSpeed = v) } },
                        onStop = { autoScroll = false },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = if (overlayVisible) 80.dp else 16.dp)
                    )
                }

                AnimatedVisibility(
                    visible = overlayVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(background.copy(alpha = 0.96f))
                            .navigationBarsPadding()
                    ) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Row(
                            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = viewModel::goToPrevious, enabled = state.previous != null) {
                                Icon(Icons.Default.SkipPrevious, tr("Capítulo anterior"), tint = textColor.copy(alpha = if (state.previous != null) 1f else 0.3f))
                            }
                            IconButton(
                                enabled = speechReady,
                                onClick = {
                                    if (spokenIndex != null) speech.stop()
                                    else {
                                        val from = currentChapterId?.let { NovelItems.indexOf(currentItems, it, currentParagraph) } ?: 0
                                        speech.start(from, speechText)
                                        if (BatteryOptimization.shouldAsk(serviceContext)) batteryAsk = true
                                    }
                                }
                            ) {
                                Icon(
                                    if (spokenIndex != null) Icons.Default.Stop else Icons.Default.RecordVoiceOver,
                                    contentDescription = if (spokenIndex != null) tr("Detener lectura en voz alta") else tr("Leer en voz alta"),
                                    tint = textColor.copy(alpha = if (speechReady) 1f else 0.3f)
                                )
                            }
                            IconButton(onClick = { autoScroll = !autoScroll; if (autoScroll) overlayVisible = false }) {
                                Icon(
                                    if (autoScroll) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (autoScroll) tr("Detener desplazamiento automático") else tr("Desplazamiento automático"),
                                    tint = textColor
                                )
                            }
                            Text(
                                "${(progress * 100).toInt()}%",
                                color = textColor,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = viewModel::goToNext, enabled = state.next != null) {
                                Icon(Icons.Default.SkipNext, tr("Capítulo siguiente"), tint = textColor.copy(alpha = if (state.next != null) 1f else 0.3f))
                            }
                        }
                    }
                }
            }
        }

        // With the menus hidden only a small clock stays, in the top corner.
        if (!overlayVisible && !searchOpen) {
            com.nelson.yomukai.ui.components.MiniClock(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                pill = true
            )
        }
        AnimatedVisibility(
            visible = overlayVisible || searchOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(background.copy(alpha = 0.96f))
                    .statusBarsPadding()
            ) {
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Volver"), tint = textColor) }
                    Column(Modifier.weight(1f)) {
                        Text(state.mediaTitle, color = textColor, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            state.chapter?.title.orEmpty(),
                            color = textColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }) {
                        Icon(Icons.Default.Search, tr("Buscar en el capítulo"), tint = textColor)
                    }
                    IconButton(onClick = { currentChapterId?.let { viewModel.toggleBookmark(it, currentParagraph, paragraphPreview(items, it, currentParagraph)) } }) {
                        Icon(
                            if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (bookmarked) "Quitar marcador" else tr("Añadir marcador"),
                            tint = textColor
                        )
                    }
                    IconButton(onClick = { showBookmarks = true }) { Icon(Icons.Default.Bookmarks, tr("Marcadores"), tint = textColor) }
                    IconButton(onClick = { showIndex = true }) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, tr("Índice de capítulos"), tint = textColor)
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.FormatSize, tr("Ajustes de texto"), tint = textColor) }
                }
                if (searchOpen) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it; searchPosition = 0 },
                            placeholder = { Text(tr("Buscar en este capítulo")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (matches.isEmpty()) "0" else "${searchPosition.coerceIn(0, matches.size - 1) + 1}/${matches.size}",
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(
                            enabled = matches.isNotEmpty(),
                            onClick = { searchPosition = (searchPosition - 1 + matches.size) % matches.size }
                        ) { Icon(Icons.Default.KeyboardArrowUp, tr("Coincidencia anterior"), tint = textColor) }
                        IconButton(
                            enabled = matches.isNotEmpty(),
                            onClick = { searchPosition = (searchPosition + 1) % matches.size }
                        ) { Icon(Icons.Default.KeyboardArrowDown, tr("Coincidencia siguiente"), tint = textColor) }
                    }
                }
            }
        }
    }

    if (showSettings) {
        NovelSettingsSheet(settings, onUpdate = viewModel::updateSettings, onDismiss = { showSettings = false }) {
            SpeechVoicesSection(speech, settings, if (viewModel.language == "EN") "en" else "es", viewModel::updateSettings)
        }
    }
    if (showIndex) {
        ChapterIndexSheet(
            chapters = chapterList,
            currentChapterId = state.chapter?.chapterId,
            onSelect = { speech.stop(); viewModel.jumpToChapter(it.chapterId) },
            onDismiss = { showIndex = false }
        )
    }
    if (showBookmarks) {
        BookmarksSheet(
            bookmarks = bookmarks,
            positionLabel = { tr("Párrafo {0}", it + 1) },
            onOpen = { b ->
                val index = NovelItems.indexOf(items, b.chapterId, b.position)
                if (segments.any { it.chapter.chapterId == b.chapterId }) {
                    location = b.chapterId to b.position
                    scrollTarget = index
                } else {
                    viewModel.jumpToChapter(b.chapterId, b.position)
                }
            },
            onDelete = { viewModel.deleteBookmark(it.id) },
            onDismiss = { showBookmarks = false }
        )
    }
}

private fun paragraphPreview(items: List<NovelItem>, chapterId: String, paragraph: Int): String =
    (items.getOrNull(NovelItems.indexOf(items, chapterId, paragraph)) as? NovelItem.Paragraph)?.text?.take(90).orEmpty()

/** Marks every case-insensitive occurrence of [query] in [text]; accent variants are matched by the paragraph filter only. */
private fun highlightMatches(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val q = query.trim()
    if (q.length < 2) return@buildAnnotatedString
    var from = 0
    while (true) {
        val at = text.indexOf(q, from, ignoreCase = true)
        if (at < 0) break
        addStyle(SpanStyle(background = color), at, at + q.length)
        from = at + q.length
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelSettingsSheet(
    settings: NovelReaderSettings,
    onUpdate: ((NovelReaderSettings) -> NovelReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    voices: @Composable () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(tr("Tema"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NovelTheme.entries.forEach { theme ->
                    val selected = theme == settings.theme
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(Color(theme.background), RoundedCornerShape(10.dp))
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onUpdate { it.copy(theme = theme) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(theme.displayName, color = Color(theme.text), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(tr("Fuente"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelFont.entries.forEach { font ->
                    FilterChip(
                        selected = font == settings.font,
                        onClick = { onUpdate { it.copy(font = font) } },
                        label = { Text(font.displayName, fontFamily = font.family()) }
                    )
                }
            }

            LabeledSlider(tr("Tamaño de letra"), "${settings.fontSize.toInt()} sp", settings.fontSize, 12f..34f) { v ->
                onUpdate { it.copy(fontSize = v) }
            }
            LabeledSlider(tr("Interlineado"), "%.1f".format(settings.lineHeight), settings.lineHeight, 1.1f..2.4f) { v ->
                onUpdate { it.copy(lineHeight = v) }
            }
            LabeledSlider(tr("Márgenes"), "${settings.horizontalPadding.toInt()} dp", settings.horizontalPadding, 4f..48f) { v ->
                onUpdate { it.copy(horizontalPadding = v) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SettingSwitch(tr("Texto justificado"), settings.justify) { v -> onUpdate { it.copy(justify = v) } }
            SettingSwitch(tr("Mantener pantalla encendida"), settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }
            SettingSwitch(tr("Cargar el siguiente capítulo automáticamente"), settings.infiniteScroll) { v -> onUpdate { it.copy(infiniteScroll = v) } }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            voices()
            LabeledSlider(tr("Velocidad de lectura en voz alta"), "%.2fx".format(settings.ttsRate), settings.ttsRate, 0.5f..2f) { v ->
                onUpdate { it.copy(ttsRate = v) }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
}

private const val NOVEL_AUTO_SCROLL_PX_PER_SECOND = 12f
