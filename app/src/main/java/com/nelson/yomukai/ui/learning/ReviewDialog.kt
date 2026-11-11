package com.nelson.yomukai.ui.learning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.JsonObject
import com.nelson.yomukai.data.learning.Grade
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import com.nelson.yomukai.ui.components.InkButton
import com.nelson.yomukai.ui.components.InkOutlinedButton
import kotlinx.coroutines.launch

private data class SharedDeck(val id: Long, val name: String, val owner: String, val lang: String, val size: Int, val mine: Boolean)

/** This week's ranking of reviews and the decks people share, as on the web's review page. */
@Composable
private fun SharedLearning(onCopied: (Int) -> Unit) {
    val sync = LocalAppContainer.current.serverSync
    val scope = rememberCoroutineScope()
    var ranking by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var decks by remember { mutableStateOf<List<SharedDeck>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        ranking = runCatching {
            sync.api("GET", "/api/words/ranking")?.asJsonArray?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                (o.get("name")?.asString ?: return@mapNotNull null) to (o.get("reviews")?.asInt ?: 0)
            }.orEmpty()
        }.getOrDefault(emptyList())
        decks = runCatching {
            sync.api("GET", "/api/words/decks")?.asJsonArray?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                SharedDeck(o.get("id")?.asLong ?: return@mapNotNull null, s("name"), s("owner"), s("lang"), o.get("size")?.asInt ?: 0, o.get("mine")?.asBoolean == true)
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
    if (ranking.isNotEmpty()) {
        Text(tr("Ranking de la semana"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ranking.forEachIndexed { i, (name, n) -> Text("${i + 1}. $name · " + tr("{0} repasos", n)) }
    }
    val others = decks.filterNot { it.mine }
    if (others.isNotEmpty()) {
        Text(tr("Mazos compartidos"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        others.take(MAX_DECKS_SHOWN).forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${d.name} · ${d.owner} · ${d.lang.uppercase()} · " + tr("{0} palabras", d.size), modifier = Modifier.weight(1f))
                InkOutlinedButton(onClick = {
                    scope.launch {
                        val added = runCatching { sync.api("POST", "/api/words/decks/${d.id}/import", JsonObject())?.asJsonObject?.get("added")?.asInt ?: 0 }
                        message = added.fold({ tr("{0} palabras nuevas para repasar", it) }, { it.message })
                        added.getOrNull()?.let(onCopied)
                    }
                }) { Text(tr("Copiar")) }
            }
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

private const val MAX_DECKS_SHOWN = 20

private data class DueWord(val id: Long, val word: String, val meaning: String, val context: String, val source: String)

/** Today's review (spaced repetition with FSRS, kept on the server like on the web): word, answer, how well it went. */
@Composable
fun ReviewDialog(onDismiss: () -> Unit) {
    val sync = LocalAppContainer.current.serverSync
    val scope = rememberCoroutineScope()
    var due by remember { mutableStateOf<List<DueWord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            sync.api("GET", "/api/words/due")?.asJsonArray?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                DueWord(o.get("id")?.asLong ?: return@mapNotNull null, s("word"), s("meaning"), s("context"), s("source"))
            }.orEmpty()
        }.onSuccess { due = it }.onFailure { error = it.message; due = emptyList() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, tr("Cerrar")) }
                val list = due
                val card = list?.getOrNull(index)
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when {
                        list == null -> CircularProgressIndicator()
                        error != null -> Text(error.orEmpty(), textAlign = TextAlign.Center)
                        card == null -> {
                            Text(
                                if (list.isEmpty()) tr("No tienes palabras para repasar hoy.") else tr("¡Repaso de hoy terminado!"),
                                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center
                            )
                            SharedLearning(onCopied = { added -> if (added > 0) { due = null; index = 0 } })
                        }
                        else -> {
                            Text("${index + 1} / ${list.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(card.word, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            if (!shown) {
                                InkButton(onClick = { shown = true }) { Text(tr("Mostrar respuesta")) }
                            } else {
                                Text(card.meaning, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                                if (card.context.isNotBlank()) Text("«${card.context}»", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                if (card.source.isNotBlank()) Text(card.source, style = MaterialTheme.typography.labelMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(Grade.AGAIN to tr("Otra vez"), Grade.HARD to tr("Difícil"), Grade.GOOD to tr("Bien"), Grade.EASY to tr("Fácil")).forEach { (g, label) ->
                                        InkOutlinedButton(modifier = Modifier.weight(1f), onClick = {
                                            scope.launch {
                                                runCatching { sync.api("POST", "/api/words/${card.id}/review", JsonObject().apply { addProperty("grade", g.name) }) }
                                                shown = false
                                                index++
                                            }
                                        }) { Text(label, maxLines = 1) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
