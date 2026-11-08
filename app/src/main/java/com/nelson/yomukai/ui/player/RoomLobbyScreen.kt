package com.nelson.yomukai.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.social.PartyClient
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer

/**
 * A room joined by its code, before anything plays: who is in and the chat. As soon as the host puts an episode on,
 * this opens it in the player, in the room (the same room a computer can join from the web link).
 */
@Composable
fun RoomLobbyScreen(code: String, onBack: () -> Unit, onEpisode: (sourceId: String, mediaId: String, chapterId: String) -> Unit) {
    val container = LocalAppContainer.current
    val party = remember(code) { PartyClient(container.serverSync, code) }
    DisposableEffect(party) {
        party.open()
        // The player opened from here gets the room explicitly; nothing stays joined after leaving the lobby.
        onDispose { party.close(); if (container.activeRoom.value == code) container.activeRoom.value = null }
    }
    val room by party.room.collectAsState()
    LaunchedEffect(room.state?.chapterId) {
        room.state?.let { s -> if (s.chapterId.isNotBlank()) onEpisode(s.sourceId, s.mediaId, s.chapterId) }
    }
    var draft by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Volver")) }
            Text(room.name.ifBlank { tr("Sala {0}", code) }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        room.closed?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(tr("Cuando el anfitrión ponga un episodio, entrarás a la vez que los demás. Si alguien se queda cargando, se espera a todos."))
        if (room.members.isNotEmpty()) Text(room.members.joinToString(", ") { it.name }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(room.messages) { m -> Text(if (m.user.isEmpty()) m.text else "${m.user}: ${m.text}") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it.take(MAX_CHAT) }, Modifier.weight(1f), placeholder = { Text(tr("Escribe en la sala")) }, singleLine = true)
            IconButton(enabled = draft.isNotBlank(), onClick = { party.chat(draft.trim()); draft = "" }) { Icon(Icons.AutoMirrored.Filled.Send, tr("Enviar")) }
        }
    }
}

/** «Unirse a una sala»: the code a friend passed (also accepts their whole link). */
@Composable
fun JoinRoomDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val code = roomCodeOf(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Unirse a una sala")) },
        text = { OutlinedTextField(text, { text = it.take(MAX_INPUT) }, label = { Text(tr("Código o enlace de la sala")) }, singleLine = true) },
        confirmButton = { TextButton(enabled = code != null, onClick = { code?.let(onJoin) }) { Text(tr("Entrar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } }
    )
}

/** "ABC123", "abc-123" or ".../sala/ABC123" → "ABC123"; null when it is not a room code. */
internal fun roomCodeOf(input: String): String? {
    val raw = input.trim().substringAfterLast("/sala/").substringBefore('?').substringBefore('#')
    return com.nelson.yomukai.data.social.RoomCodes.normalize(raw)
}

private const val MAX_CHAT = 500
private const val MAX_INPUT = 300
