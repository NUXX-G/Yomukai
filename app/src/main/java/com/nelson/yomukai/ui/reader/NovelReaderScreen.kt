package com.nelson.yomukai.ui.reader

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nelson.yomukai.data.prefs.NovelFont
import com.nelson.yomukai.data.prefs.NovelReaderSettings
import com.nelson.yomukai.data.prefs.NovelTheme
import com.nelson.yomukai.ui.AppViewModelProvider
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

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
    var overlayVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }

    val background = Color(settings.theme.background)
    val textColor = Color(settings.theme.text)
    val paragraphs = state.content

    ReaderWindowEffects(brightness = -1f, keepScreenOn = settings.keepScreenOn, immersive = !overlayVisible)

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
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(state.chapter?.title ?: "Cargando...", color = textColor.copy(alpha = 0.7f))
            }

            state.error != null || paragraphs.isNullOrEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(state.error ?: "El capítulo está vacío", color = textColor, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Volver") }
                    Button(onClick = viewModel::retry) { Text("Reintentar") }
                }
            }

            else -> key(state.chapter?.chapterId) {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (state.initialPosition > 0) state.initialPosition + 1 else 0)
                val progress by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val total = info.totalItemsCount
                        if (total == 0) 0f
                        else {
                            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            if (lastVisible >= total - 1) 1f else listState.firstVisibleItemIndex.toFloat() / (total - 1)
                        }
                    }
                }
                LaunchedEffect(listState) {
                    snapshotFlow { progress }
                        .distinctUntilChanged()
                        .debounce(400)
                        .collect { viewModel.onProgress(it, atEnd = it >= 0.999f) }
                }

                val bodyStyle = TextStyle(
                    color = textColor,
                    fontSize = settings.fontSize.sp,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp,
                    fontFamily = settings.font.family(),
                    textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start
                )

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
                        ) { overlayVisible = !overlayVisible }
                ) {
                    item {
                        Text(
                            state.chapter?.title.orEmpty(),
                            style = bodyStyle.copy(
                                fontSize = (settings.fontSize + 6).sp,
                                lineHeight = ((settings.fontSize + 6) * 1.3f).sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Start
                            ),
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                    }
                    itemsIndexed(paragraphs, key = { index, _ -> index }) { _, paragraph ->
                        Text(paragraph, style = bodyStyle, modifier = Modifier.padding(bottom = (settings.fontSize * 0.9f).dp))
                    }
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Fin del capítulo", color = textColor.copy(alpha = 0.7f))
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (state.previous != null) OutlinedButton(onClick = viewModel::goToPrevious) { Text("Anterior") }
                                if (state.next != null) Button(onClick = viewModel::goToNext) { Text("Siguiente capítulo") }
                            }
                        }
                    }
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
                                Icon(Icons.Default.SkipPrevious, "Capítulo anterior", tint = textColor.copy(alpha = if (state.previous != null) 1f else 0.3f))
                            }
                            Text(
                                "${(progress * 100).toInt()}%",
                                color = textColor,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = viewModel::goToNext, enabled = state.next != null) {
                                Icon(Icons.Default.SkipNext, "Capítulo siguiente", tint = textColor.copy(alpha = if (state.next != null) 1f else 0.3f))
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(background.copy(alpha = 0.96f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = textColor) }
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
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.FormatSize, "Ajustes de texto", tint = textColor) }
            }
        }
    }

    if (showSettings) {
        NovelSettingsSheet(settings, onUpdate = viewModel::updateSettings, onDismiss = { showSettings = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelSettingsSheet(
    settings: NovelReaderSettings,
    onUpdate: ((NovelReaderSettings) -> NovelReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Tema", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            Text("Fuente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NovelFont.entries.forEach { font ->
                    FilterChip(
                        selected = font == settings.font,
                        onClick = { onUpdate { it.copy(font = font) } },
                        label = { Text(font.displayName, fontFamily = font.family()) }
                    )
                }
            }

            LabeledSlider("Tamaño de letra", "${settings.fontSize.toInt()} sp", settings.fontSize, 12f..34f) { v ->
                onUpdate { it.copy(fontSize = v) }
            }
            LabeledSlider("Interlineado", "%.1f".format(settings.lineHeight), settings.lineHeight, 1.1f..2.4f) { v ->
                onUpdate { it.copy(lineHeight = v) }
            }
            LabeledSlider("Márgenes", "${settings.horizontalPadding.toInt()} dp", settings.horizontalPadding, 4f..48f) { v ->
                onUpdate { it.copy(horizontalPadding = v) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SettingSwitch("Texto justificado", settings.justify) { v -> onUpdate { it.copy(justify = v) } }
            SettingSwitch("Mantener pantalla encendida", settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }
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
