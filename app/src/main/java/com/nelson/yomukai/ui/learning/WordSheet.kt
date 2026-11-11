package com.nelson.yomukai.ui.learning

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.learning.Dictionary
import com.nelson.yomukai.data.learning.TranslationChain
import androidx.compose.runtime.collectAsState
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * What a long press on a word or a bubble opens, in every reader (F6.3): its translation through the chain
 * (dictionary, ML Kit on the phone, the server), the offline dictionary (meaning, pronunciation, Spanish words), its
 * sentence translated, the word read aloud and, in learning mode, «Guardar» to review it later (needs the server).
 */
@Composable
private fun DictionaryResult(status: String?, entries: List<Dictionary.Entry>) {
    when {
        status == null -> CircularProgressIndicator()
        entries.isNotEmpty() -> entries.forEach { e ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.pos.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    if (e.pron.isNotEmpty()) Text(e.pron, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                }
                if (e.es.isNotEmpty()) Text(e.es.joinToString(", "), fontWeight = FontWeight.Bold)
                e.glosses.forEachIndexed { i, g -> Text("${i + 1}. $g", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        status == "building" -> Text(tr("Preparando el diccionario sin conexión en el servidor de casa; estará en unos minutos."))
        status == "ready" -> Text(tr("Sin resultados"))
        else -> Text(tr("El diccionario necesita conectar la app con tu servidor una vez."))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordSheet(word: String, sentence: String, lang: String, source: String, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<Dictionary.Entry>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val connected = container.serverSync.serverUrl != null
    val code = lang.take(2).lowercase()
    // A whole bubble or sentence: its words are offered to look up one by one.
    val choices = remember(word) { wordsOf(word) }
    var current by remember(word) { mutableStateOf(choices.firstOrNull() ?: word) }

    val target = com.nelson.yomukai.i18n.I18n.lang
    var phrase by remember(word) { mutableStateOf<TranslationChain.Result?>(null) }
    var sentenceTr by remember(sentence) { mutableStateOf<TranslationChain.Result?>(null) }
    val learning = container.preferences.app.collectAsState().value.learningEnabled
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    LaunchedEffect(word) { phrase = container.textTranslator.translate(word, code, target) }
    LaunchedEffect(sentence) { if (sentence.isNotBlank() && sentence != word) sentenceTr = container.textTranslator.translate(sentence, code, target) }
    LaunchedEffect(current, code) {
        status = null
        val (s, found) = container.dictionary.lookup(code, current)
        status = s
        entries = found
    }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { ok -> if (ok == TextToSpeech.SUCCESS) { engine?.language = Locale.forLanguageTag(lang); tts = engine } }
        onDispose { engine.shutdown() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (choices.size > 1) Text(word, style = MaterialTheme.typography.titleMedium)
            when {
                phrase == null -> CircularProgressIndicator()
                phrase?.text != null -> {
                    Text(phrase!!.text!!, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(tr("Con: {0}", viaName(phrase!!.via)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text(tr("Aquí no hay traductor disponible: ni el diccionario, ni el del móvil, ni el servidor."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    phrase?.webUrl?.let { url -> TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(tr("Abrir en el traductor de Google")) } }
                }
            }
            Text(current, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (choices.size > 1) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { w -> androidx.compose.material3.FilterChip(selected = w == current, onClick = { current = w }, label = { Text(w) }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { tts?.speak(current, TextToSpeech.QUEUE_FLUSH, null, "word") }, enabled = tts != null) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                    Text(" " + tr("Escuchar"))
                }
                Spacer(Modifier.weight(1f))
                if (connected && learning) {
                    Button(onClick = {
                        scope.launch {
                            val meaning = entries.firstOrNull()?.let { e -> e.es.joinToString(", ").ifEmpty { e.glosses.firstOrNull().orEmpty() } }.orEmpty()
                            val body = com.google.gson.JsonObject().apply {
                                addProperty("word", current); addProperty("lang", code); addProperty("meaning", meaning.take(500))
                                addProperty("context", sentence.take(500)); addProperty("source", source.take(200))
                            }
                            message = runCatching { container.serverSync.api("POST", "/api/words", body); tr("Guardada para repasar") }
                                .getOrElse { it.message }
                        }
                    }) { Text(tr("Guardar")) }
                }
            }
            DictionaryResult(status, entries)
            if (sentence.isNotBlank() && sentence != word) {
                Text("«$sentence»", color = MaterialTheme.colorScheme.onSurfaceVariant)
                sentenceTr?.text?.let { Text(it) }
            }
            if (connected && status == "ready" && !container.dictionary.isDownloaded(code)) {
                TextButton(enabled = !downloading, onClick = {
                    downloading = true
                    scope.launch {
                        message = if (container.dictionary.download(code)) tr("Diccionario guardado: ya funciona sin conexión") else tr("No se pudo descargar")
                        downloading = false
                    }
                }) { Text(if (downloading) tr("Descargando…") else tr("Guardar el diccionario en el móvil")) }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** The words of a bubble or sentence written with spaces, to pick from; empty for a single word or Chinese/Japanese. */
private fun wordsOf(text: String): List<String> {
    if (!text.trim().contains(' ')) return emptyList()
    return text.split(' ').mapNotNull { part -> com.nelson.yomukai.data.learning.WordAt.find(part, part.indexOfFirst { it.isLetterOrDigit() })?.word }
        .distinct().take(MAX_CHOICES)
}

private const val MAX_CHOICES = 30

private fun viaName(via: TranslationChain.Via?): String = when (via) {
    TranslationChain.Via.DICTIONARY -> tr("diccionario sin conexión")
    TranslationChain.Via.DEVICE -> tr("traductor del móvil")
    TranslationChain.Via.SERVER -> tr("servidor de casa")
    TranslationChain.Via.ONLINE -> tr("traductor en línea (MyMemory)")
    else -> tr("mismo idioma")
}
