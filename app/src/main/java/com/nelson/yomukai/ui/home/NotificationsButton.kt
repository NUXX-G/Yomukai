package com.nelson.yomukai.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import kotlinx.coroutines.launch

private data class Note(val text: String, val link: String, val read: Boolean)

/**
 * The server's notifications (mentions, replies, recommendations, achievements) in the app: a bell with the unread
 * count; each one opens its page on the server's web. Hidden when no server is connected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsButton() {
    val sync = LocalAppContainer.current.serverSync
    val server = sync.serverUrl ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var notes by remember { mutableStateOf(emptyList<Note>()) }
    var unread by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching {
            val root = sync.api("GET", "/api/notifications")?.asJsonObject ?: return
            unread = root["unread"]?.asInt ?: 0
            notes = root.getAsJsonArray("items").map { e ->
                val o = e.asJsonObject
                Note(o["text"].asString, o["link"]?.asString.orEmpty(), o["read"]?.asBoolean == true)
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    IconButton(onClick = {
        open = true
        if (unread > 0) scope.launch { runCatching { sync.api("POST", "/api/notifications/read") }; unread = 0 }
    }) {
        BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 9) "9+" else "$unread") } }) {
            Icon(Icons.Default.Notifications, contentDescription = if (unread > 0) "$unread avisos" else tr("Avisos"))
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            if (notes.isEmpty()) {
                Text(tr("Nada nuevo por aquí."), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn {
                items(notes) { n ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = n.link.startsWith("/")) {
                                // Only the server's own pages: the link always starts with "/".
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(server + n.link)))
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(n.text, style = MaterialTheme.typography.bodyMedium, color = if (n.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
