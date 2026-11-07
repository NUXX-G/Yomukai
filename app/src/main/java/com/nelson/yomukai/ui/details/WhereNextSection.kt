package com.nelson.yomukai.ui.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import java.net.URLEncoder

/**
 * "¿Dónde sigo?": after an anime, the chapter of the manga or novel where the story goes on; in a manga, how far the
 * anime adapts. The data is shared on the server (anyone in the group can correct it on the web), so it only shows
 * with a server connected. [lastDone] is the last episode watched or chapter read here.
 */
@Composable
fun WhereNextSection(sourceId: String, mediaId: String, anime: Boolean, lastDone: Float, modifier: Modifier = Modifier) {
    val sync = LocalAppContainer.current.serverSync
    if (sync.serverUrl == null) return
    var text by remember(sourceId, mediaId, lastDone) { mutableStateOf<String?>(null) }
    LaunchedEffect(sourceId, mediaId, lastDone) {
        text = runCatching {
            fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
            val work = sync.api("GET", "/api/work?s=${enc(sourceId)}&id=${enc(mediaId)}")?.asJsonObject ?: return@runCatching null
            val key = work.get("key")?.asString ?: return@runCatching null
            val episode = if (anime && lastDone >= 1f) "?episode=${lastDone.toInt()}" else ""
            val where = sync.api("GET", "/api/works/${enc(key)}/continue$episode")?.asJsonObject ?: return@runCatching null
            val next = where.getAsJsonArray("next")?.firstOrNull()?.asJsonObject
            val upTo = where.get("animatedUpTo")?.takeIf { it.isJsonPrimitive }?.asFloat
            when {
                anime && next != null -> {
                    val target = next.get("targetKey")?.asString
                    val name = target?.let { sync.api("GET", "/api/works/${enc(it)}")?.asJsonObject?.get("title")?.asString } ?: tr("la obra original")
                    val chapter = next.get("chapter")?.takeIf { it.isJsonPrimitive }?.asFloat
                    val at = if (chapter != null) tr("el capítulo {0}", chapter.toInt()) else tr("el volumen {0}", next.get("volume")?.asInt)
                    tr("Después del episodio {0}, la historia sigue en {1} de {2}.", lastDone.toInt(), at, name)
                }
                !anime && upTo != null -> if (lastDone >= upTo) {
                    tr("Ya has pasado lo que adapta el anime (hasta el capítulo {0}).", upTo.toInt())
                } else {
                    tr("El anime adapta hasta el capítulo {0}.", upTo.toInt())
                }
                else -> null
            }
        }.getOrNull()
    }
    val shown = text ?: return
    Card(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(tr("¿Dónde sigo?"), style = MaterialTheme.typography.titleSmall)
            Text(shown, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
