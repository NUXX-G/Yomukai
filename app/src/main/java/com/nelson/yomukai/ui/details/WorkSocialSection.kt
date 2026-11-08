package com.nelson.yomukai.ui.details

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import kotlinx.coroutines.launch
import java.util.Locale

private data class FriendOn(val name: String, val status: String, val last: String)

private val STATUS = mapOf(
    "READING" to tr("va por ella"), "PLAN_TO_READ" to tr("la tiene pendiente"), "COMPLETED" to tr("la terminó"),
    "ON_HOLD" to tr("la tiene en pausa"), "DROPPED" to tr("la dejó")
)

/**
 * With a server connected (Ajustes › Sincronizar): friends who have this work, your 1-10 rating with the group's
 * average, and for anime «Ver con amigos», which opens your room on the server's web. Nothing shows without a server.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkSocialSection(sourceId: String, mediaId: String, title: String, cover: String, anime: Boolean, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val sync = container.serverSync
    if (sync.serverUrl == null) return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var friends by remember { mutableStateOf(emptyList<FriendOn>()) }
    var average by remember { mutableStateOf<Double?>(null) }
    var count by remember { mutableIntStateOf(0) }
    var mine by remember { mutableStateOf<Int?>(null) }
    var rating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        runCatching {
            val q = "s=${Uri.encode(sourceId)}&id=${Uri.encode(mediaId)}"
            friends = sync.api("GET", "/api/friends-on?$q")?.asJsonArray?.map { e ->
                val o = e.asJsonObject
                FriendOn(o["name"].asString, o["status"]?.asString.orEmpty(), o["last"]?.takeIf { !it.isJsonNull }?.asString.orEmpty())
            }.orEmpty()
            val r = sync.api("GET", "/api/ratings?$q")?.asJsonObject
            average = r?.get("average")?.takeIf { !it.isJsonNull }?.asDouble
            count = r?.get("count")?.asInt ?: 0
            mine = r?.getAsJsonArray("ratings")?.map { it.asJsonObject }?.firstOrNull { it["me"]?.asBoolean == true }?.get("score")?.asInt
        }
    }
    LaunchedEffect(sourceId, mediaId) { load() }

    Column(modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { rating = true }, label = { Text(mine?.let { tr("Tu nota: {0}", it) } ?: "Valorar") }, leadingIcon = { Icon(Icons.Default.Star, null) })
            if (anime) {
                AssistChip(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val code = sync.api("GET", "/api/rooms/mine")?.asJsonObject?.get("code")?.asString ?: return@launch
                                // Your room becomes the active one: the episode you put on next is watched together.
                                container.activeRoom.value = code
                                val link = sync.serverUrl.orEmpty().trimEnd('/') + "/sala/" + code
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tr("Ven a ver conmigo en Yomukai: {0} (sala {1})", link, code)),
                                        tr("Invitar a la sala")
                                    )
                                )
                                error = tr("Sala lista: pon un episodio y lo veréis a la vez")
                            }.onFailure { error = it.message }
                        }
                    },
                    label = { Text(tr("Ver con amigos")) }, leadingIcon = { Icon(Icons.Default.Groups, null) }
                )
            }
        }
        average?.let {
            Text(
                "Media del grupo ${String.format(Locale.forLanguageTag("es"), "%.1f", it)} · $count ${if (count == 1) "nota" else "notas"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (friends.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                friends.forEach { f ->
                    Text(
                        "${f.name} ${STATUS[f.status] ?: "la tiene"}${if (f.last.isNotBlank()) " · ${f.last}" else ""}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    if (rating) {
        var pick by remember { mutableStateOf(mine) }
        AlertDialog(
            onDismissRequest = { rating = false },
            title = { Text(tr("Tu valoración")) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in 1..10) FilterChip(selected = pick == n, onClick = { pick = n }, label = { Text("$n") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    rating = false
                    scope.launch {
                        runCatching {
                            sync.api(
                                "PUT", "/api/ratings",
                                JsonObject().apply {
                                    addProperty("sourceId", sourceId); addProperty("mediaId", mediaId); addProperty("title", title); addProperty("cover", cover)
                                    if (pick != null) addProperty("score", pick) else add("score", com.google.gson.JsonNull.INSTANCE)
                                }
                            )
                            load()
                        }.onFailure { error = it.message }
                    }
                }) { Text(tr("Guardar")) }
            },
            dismissButton = { TextButton(onClick = { pick = null }) { Text(tr("Quitar nota")) } }
        )
    }
}
