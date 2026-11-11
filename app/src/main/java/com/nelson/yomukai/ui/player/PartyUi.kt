package com.nelson.yomukai.ui.player

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nelson.yomukai.data.social.Correction
import com.nelson.yomukai.data.social.PartyClient
import com.nelson.yomukai.data.social.PartyState
import com.nelson.yomukai.data.social.PartySync
import com.nelson.yomukai.i18n.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps this player with its room, as the web does: whoever does not drive follows the room's episode, play/pause and
 * position (a jump when far, a slight speed change when close); whoever drives tells the room what they do.
 */
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun RoomSync(party: PartyClient, exo: ExoPlayer, viewModel: AnimePlayerViewModel, chapterId: String?) {
    // A move this player makes to follow the room is not sent back as its own.
    val applyingUntil = remember { longArrayOf(0L) }
    fun follow(state: PartyState, byServer: Boolean) {
        if (party.room.value.isHost && !byServer) return
        if (state.chapterId != chapterId) {
            // Whoever drives moved on (Siguiente) and the room still says the old episode: the room catches up with
            // them, never the other way round (F1.1). The others follow.
            if (party.room.value.isHost || viewModel.movedHere(chapterId)) {
                viewModel.partyState(exo.currentPosition / MS, exo.playWhenReady, exo.playbackParameters.speed.toDouble())?.let(party::sendState)
                return
            }
            viewModel.followRoomEpisode(state)
            return
        }
        if (exo.playbackState != Player.STATE_READY) return
        val fix = PartySync.correction(exo.currentPosition / MS, PartySync.expectedPosition(state, party.serverNow()), state.speed)
        applyingUntil[0] = System.currentTimeMillis() + APPLY_MS
        if (state.playing != exo.playWhenReady) exo.playWhenReady = state.playing
        when (fix) {
            is Correction.Seek -> exo.seekTo((fix.to * MS).toLong())
            is Correction.Rate -> exo.setPlaybackSpeed(fix.rate.toFloat())
            Correction.None -> exo.setPlaybackSpeed(state.speed.toFloat())
        }
    }
    LaunchedEffect(party, exo, chapterId) {
        // The room moved on by itself (queue, votes): everyone goes there, the host too (F4.1).
        party.states.collect { if (it.advance && it.state.chapterId != chapterId) viewModel.followRoomEpisode(it.state) else follow(it.state, it.byServer) }
    }
    LaunchedEffect(party, chapterId) {
        party.skips.collect { skipped -> if (skipped == chapterId && party.room.value.canControl && viewModel.hasNext()) viewModel.playNext() }
    }
    LaunchedEffect(party, exo, chapterId) {
        while (true) {
            delay(FOLLOW_EVERY_MS)
            val room = party.room.value
            if (!room.isHost) room.state?.let { follow(it, byServer = false) }
        }
    }
    DisposableEffect(party, exo, chapterId) {
        // Each new episode is announced once it plays (Siguiente keeps the same player and room).
        var announced = false
        fun share() {
            if (!party.room.value.canControl || System.currentTimeMillis() < applyingUntil[0]) return
            viewModel.partyState(exo.currentPosition / MS, exo.playWhenReady, exo.playbackParameters.speed.toDouble())?.let(party::sendState)
        }
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = share()

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) share()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    // Everyone waits for whoever is loading, and goes on together.
                    Player.STATE_BUFFERING -> if (party.room.value.state?.playing == true) party.loading()
                    Player.STATE_READY -> {
                        party.ready()
                        // A new episode started by whoever drives: the room goes there.
                        if (!announced) { announced = true; share() }
                    }
                    else -> Unit
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }
}

/** The room button of the player's top bar: «Ver con amigos» outside a room, the room (with unread count) inside. */
@Composable
internal fun RoomButton(viewModel: AnimePlayerViewModel, onOpen: () -> Unit) {
    val party by viewModel.party.collectAsState()
    val unread = party?.room?.collectAsState()?.value?.unread ?: 0
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    IconButton(onClick = {
        if (party != null) onOpen() else scope.launch {
            viewModel.watchTogether()
                .onSuccess { code -> shareRoom(context, viewModel, code); onOpen() }
                .onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
        }
    }) {
        BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
            Icon(Icons.Default.Group, if (party != null) tr("Sala") else tr("Ver con amigos"), tint = Color.White)
        }
    }
}

private fun shareRoom(context: android.content.Context, viewModel: AnimePlayerViewModel, code: String) {
    val link = viewModel.roomLink(code)
    val text = tr("Ven a ver conmigo en Yomukai: {0} (sala {1})", link, code)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), tr("Invitar a la sala")))
}

/** The room: who is in, the chat, quick reactions, invite and leave. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod")
@Composable
internal fun RoomSheet(viewModel: AnimePlayerViewModel, onDismiss: () -> Unit) {
    val party = viewModel.party.collectAsState().value ?: return onDismiss()
    val room by party.room.collectAsState()
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var attached by remember { mutableStateOf<com.nelson.yomukai.data.social.ShareCard?>(null) }
    var picking by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(room.messages.size) { party.seen(); if (room.messages.isNotEmpty()) list.animateScrollToItem(room.messages.size - 1) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(room.name.ifBlank { tr("Sala {0}", party.code) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        room.members.joinToString(", ") { it.name } + if (room.isHost) " · " + tr("tú controlas") else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { shareRoom(context, viewModel, party.code) }) { Icon(Icons.Default.Share, tr("Invitar a la sala")) }
            }
            room.closed?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!room.connected && room.closed == null) Text(tr("Conectando con la sala…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (room.waiting.isNotEmpty()) Text(tr("Esperando a {0}...", room.waiting.joinToString(", ")))
            // Queue (F4.1): what plays next, votes to skip, and adding the next episode.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Cola · {0}", room.queue.size), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                if (room.state != null) {
                    TextButton(onClick = { party.skip() }) {
                        Text((if (room.votedSkip) "✓ " else "") + tr("Saltar") + " ${room.skipVotes.size}/${room.skipNeeded}")
                    }
                }
                TextButton(onClick = {
                    if (!viewModel.queueNext()) android.widget.Toast.makeText(context, tr("No hay siguiente episodio"), android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(tr("+ Siguiente")) }
            }
            val myName = room.members.firstOrNull { it.id == room.you }?.name
            room.queue.forEachIndexed { i, q ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${q.title} · ${q.episode}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    if (room.canControl) {
                        TextButton(onClick = { party.queuePlay(q.id) }) { Text(tr("Poner ahora")) }
                        if (i > 0) TextButton(onClick = { party.queueMove(q.id, i - 1) }) { Text("↑") }
                    }
                    if (room.canControl || q.addedBy == myName) TextButton(onClick = { party.queueRemove(q.id) }) { Text("✕") }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), state = list) {
                items(room.messages) { m ->
                    if (m.user.isEmpty()) Text(m.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (m.text.isNotEmpty()) Text("${m.user}: " + if (m.spoiler) tr("[spoiler]") + " " + m.text else m.text)
                    else Text(m.user + ":", style = MaterialTheme.typography.bodySmall)
                    m.card?.let { card -> com.nelson.yomukai.ui.social.ShareCardView(card, mine = false) }
                }
            }
            if (room.reactions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    room.reactions.take(MAX_REACTIONS).forEach { e -> TextButton(onClick = { party.react(e) }) { Text(e) } }
                }
            }
            attached?.let { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { com.nelson.yomukai.ui.social.ShareCardView(c, mine = true) }
                    TextButton(onClick = { attached = null }) { Text(tr("Quitar la obra")) }
                }
            }
            if (picking) com.nelson.yomukai.ui.social.WorkPickerDialog(onPick = { attached = it; picking = false }, onDismiss = { picking = false })
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picking = true }) { Text("+") }
                OutlinedTextField(draft, { draft = it.take(MAX_CHAT) }, Modifier.weight(1f), placeholder = { Text(tr("Escribe en la sala")) }, singleLine = true)
                IconButton(enabled = draft.isNotBlank() || attached != null, onClick = { party.chat(draft.trim(), card = attached); draft = ""; attached = null }) {
                    Icon(Icons.AutoMirrored.Filled.Send, tr("Enviar"))
                }
            }
            TextButton(onClick = { viewModel.leaveRoom(); onDismiss() }) { Text(tr("Salir de la sala")) }
        }
    }
}

private const val MS = 1000.0
private const val APPLY_MS = 400L
private const val FOLLOW_EVERY_MS = 1_000L
private const val MAX_REACTIONS = 8
private const val MAX_CHAT = 500
