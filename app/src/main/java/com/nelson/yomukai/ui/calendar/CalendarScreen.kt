package com.nelson.yomukai.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.JsonObject
import com.nelson.yomukai.data.discovery.CalendarSlot
import com.nelson.yomukai.data.discovery.DiscoverLabels
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.LocalAppContainer
import com.nelson.yomukai.ui.components.CoverImage
import com.nelson.yomukai.ui.discover.DiscoverViewModel
import com.nelson.yomukai.ui.discover.LocateHandler
import com.nelson.yomukai.ui.theme.DesignTokens
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Calendario (bloque 5), like the web's: this week day by day with covers, your library first. A tap opens the
 * work, a long press shows synopsis, genres, score and what the group says, and lets you recommend it. Below, the
 * group's picks of the season (needs the server) and what AniList's community rates. Works without the server too:
 * the week and the community come straight from AniList, and "yours" is known from the phone's library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onOpenDetails: (sourceId: String, mediaId: String) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onSearchTitle: (String) -> Unit,
    discover: DiscoverViewModel = viewModel(factory = AppViewModelProvider.Factory),
    model: CalendarViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    LocateHandler(discover, onOpenMedia = onMediaClick, onSearch = onSearchTitle)
    val state by model.state.collectAsStateWithLifecycle()
    var day by rememberSaveable { mutableIntStateOf(0) }
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    var peek by remember { mutableStateOf<CalendarSlot?>(null) }
    LaunchedEffect(Unit) { model.load() }
    val today = remember { LocalDate.now() }
    val week = remember(state, mineOnly) { model.week(today, mineOnly) }
    val days = week.keys.toList()

    fun open(s: CalendarSlot) {
        peek = null
        val source = s.sourceId
        val media = s.mediaId
        when {
            source != null && media != null -> onOpenDetails(source, media)
            s.entry != null -> discover.openEntry(s.entry!!)
            else -> onSearchTitle(s.title)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Calendario")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Volver")) } }
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !mineOnly, onClick = { mineOnly = false }, label = { Text(tr("Todo lo que se emite")) })
                FilterChip(selected = mineOnly, onClick = { mineOnly = true }, label = { Text(tr("Mi biblioteca")) })
            }
            if (days.isNotEmpty()) {
                ScrollableTabRow(selectedTabIndex = day.coerceIn(0, days.lastIndex), edgePadding = 12.dp) {
                    days.forEachIndexed { i, d ->
                        val n = week[d].orEmpty().size
                        Tab(selected = day == i, onClick = { day = i }, text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(dayName(d, i), fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(if (state.loading) "${d.dayOfMonth}" else "${d.dayOfMonth} · $n", style = MaterialTheme.typography.labelSmall)
                            }
                        })
                    }
                }
            }
            val list = days.getOrNull(day)?.let { week[it] }.orEmpty()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (state.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                } else if (list.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(if (mineOnly) tr("Nada de tu biblioteca este día.") else tr("Nada este día."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list, key = { it.key }) { s ->
                    SlotCard(s, state.recommends[s.anilistId] ?: 0, onClick = { open(s) }, onLongClick = { peek = s })
                }
                state.error?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { Text(e, color = MaterialTheme.colorScheme.error) } }
                if (state.picksOn) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 20.dp)) {
                            Text(tr("Recomendado por el grupo"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            if (state.picks.isEmpty()) {
                                Text(
                                    tr("Nadie ha recomendado nada todavía esta temporada. Abre un anime del calendario y pulsa «Recomendar o comentar»."),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            state.picks.forEach { p -> PickRow(p) { peek = model.slotFor(p) } }
                        }
                    }
                }
                if (state.top.isNotEmpty() || state.trending.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 20.dp)) {
                            Text(tr("Lo que más gusta esta temporada"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text(
                                tr("Según la comunidad de AniList: nota media de quienes lo ven (solo con bastantes votos) y lo que más se comenta ahora."),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            listOf(tr("Mejor valorados") to state.top, tr("Tendencia ahora") to state.trending).forEach { (label, row) ->
                                if (row.isNotEmpty()) {
                                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(row, key = { it.key }) { s ->
                                            Box(Modifier.width(118.dp)) { SlotCard(s, state.recommends[s.anilistId] ?: 0, onClick = { open(s) }, onLongClick = { peek = s }) }
                                        }
                                    }
                                }
                            }
                            Text(tr("Datos de") + " AniList", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    peek?.let { s -> PeekSheet(s, model, onOpen = { open(s) }, onDismiss = { peek = null }) }
}

private fun dayName(d: LocalDate, i: Int): String = when (i) {
    0 -> tr("Hoy")
    1 -> tr("Mañana")
    else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun timeOf(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault()).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

internal fun slotLabel(s: CalendarSlot): String = when {
    s.episode != null -> tr("Ep. {0}", s.episode)
    s.label.isNotBlank() -> s.label + if (s.estimated) " " + tr("· estimado") else ""
    else -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotCard(s: CalendarSlot, recommends: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(DesignTokens.POSTER_RATIO).clip(RoundedCornerShape(DesignTokens.radiusMd))
                .then(if (s.mine) Modifier.border(2.dp, accent, RoundedCornerShape(DesignTokens.radiusMd)) else Modifier)
        ) {
            CoverImage(s.cover, s.sourceId.orEmpty(), null, Modifier.fillMaxSize())
            s.airingAt?.let { Badge(timeOf(it), Modifier.align(Alignment.TopStart)) }
            if (recommends > 0) Badge("♥ $recommends", Modifier.align(Alignment.TopEnd))
            s.score?.takeIf { s.airingAt == null }?.let { Badge("★ " + DiscoverLabels.score(it), Modifier.align(Alignment.BottomStart)) }
            if (s.mine) Badge(tr("Tuyo"), Modifier.align(Alignment.BottomStart), background = accent, color = MaterialTheme.colorScheme.onPrimary)
        }
        Text(s.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        slotLabel(s).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, background: Color = Color.Black.copy(alpha = 0.78f), color: Color = Color.White) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(6.dp).background(background, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun PickRow(p: CalendarViewModel.Pick, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DesignTokens.radiusMd)).combinedClickableCompat(onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CoverImage(p.cover, "", null, Modifier.width(54.dp).aspectRatio(DesignTokens.POSTER_RATIO).clip(RoundedCornerShape(6.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (p.recommends > 0) {
                Text(
                    if (p.recommends == 1) tr("Lo recomienda {n} del grupo").replace("{n}", "1") else tr("Lo recomiendan {n} del grupo").replace("{n}", p.recommends.toString()),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium
                )
            }
            p.comments.firstOrNull()?.let { c -> Text("${c.name}: ${c.comment}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeekSheet(s: CalendarSlot, model: CalendarViewModel, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val pick = s.anilistId?.let { id -> state.picks.firstOrNull { it.anilistId == id } }
    val signedIn = LocalAppContainer.current.serverSync.serverUrl != null
    var editing by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoverImage(s.cover, s.sourceId.orEmpty(), null, Modifier.width(84.dp).aspectRatio(DesignTokens.POSTER_RATIO).clip(RoundedCornerShape(DesignTokens.radiusMd)))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    slotLabel(s).takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    s.score?.let { Text("★ " + DiscoverLabels.score(it), fontWeight = FontWeight.Bold) }
                    if (s.genres.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            s.genres.take(3).forEach { g -> AssistChip(onClick = {}, label = { Text(DiscoverLabels.genre(g), fontSize = 11.sp) }) }
                        }
                    }
                    pick?.recommends?.takeIf { it > 0 }?.let { n ->
                        Text(
                            if (n == 1) tr("Lo recomienda {n} del grupo").replace("{n}", "1") else tr("Lo recomiendan {n} del grupo").replace("{n}", n.toString()),
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            if (s.synopsis.isNotBlank()) Text(s.synopsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
            pick?.comments?.forEach { c ->
                Column {
                    Text(c.name + if (c.recommend) " · " + tr("lo recomienda") else "", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Text(c.comment, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text(tr("Abrir ficha")) }
                if (state.picksOn && signedIn && s.anilistId != null) {
                    FilledTonalButton(onClick = { editing = true }) { Text(if (pick?.mine != null) tr("Cambiar mi recomendación") else tr("Recomendar o comentar")) }
                }
            }
        }
    }
    if (editing && s.anilistId != null) PickDialog(s.anilistId!!, pick?.mine, model) { editing = false }
}

@Composable
private fun PickDialog(anilistId: Int, mine: CalendarViewModel.Comment?, model: CalendarViewModel, onDone: () -> Unit) {
    var recommend by remember { mutableStateOf(mine?.recommend ?: true) }
    var comment by remember { mutableStateOf(mine?.comment.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(tr("Recomendar o comentar")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = recommend, onCheckedChange = { recommend = it })
                    Text(tr("Lo recomiendo al grupo"))
                }
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it.take(500) }, minLines = 3,
                    placeholder = { Text(tr("Un comentario para el grupo (opcional)")) }, modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (!recommend && comment.isBlank()) { error = tr("Escribe un comentario o recomiéndalo"); return@TextButton }
                busy = true
                scope.launch {
                    val body = JsonObject().apply { addProperty("recommend", recommend); addProperty("comment", comment.trim()) }
                    model.savePick(anilistId, body).onSuccess { onDone() }.onFailure { error = it.message ?: tr("No se ha podido guardar") }
                    busy = false
                }
            }) { Text(tr("Guardar")) }
        },
        dismissButton = {
            Row {
                if (mine != null) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch { model.removePick(anilistId).onSuccess { onDone() }.onFailure { error = it.message }; busy = false }
                    }) { Text(tr("Quitar lo mío")) }
                }
                TextButton(onClick = onDone) { Text(tr("Cancelar")) }
            }
        }
    )
}
