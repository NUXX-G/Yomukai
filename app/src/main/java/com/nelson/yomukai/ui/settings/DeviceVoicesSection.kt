package com.nelson.yomukai.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.speech.DeviceVoices
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import kotlinx.coroutines.launch

private val LANGUAGE_NAMES = mapOf(
    "es" to "Español", "en" to "Inglés", "zh" to "Chino", "fr" to "Francés", "de" to "Alemán",
    "it" to "Italiano", "pt" to "Portugués", "ru" to "Ruso"
)

private val SAMPLES = mapOf(
    "es" to "Hola. Así suena esta voz leyendo tus novelas.", "en" to "Hello. This is how this voice reads your novels.",
    "zh" to "你好。这是这个声音朗读小说的样子。", "fr" to "Bonjour. Voici comment cette voix lit vos romans.",
    "de" to "Hallo. So liest diese Stimme deine Romane.", "it" to "Ciao. Ecco come questa voce legge i tuoi romanzi.",
    "pt" to "Olá. É assim que esta voz lê os seus romances.", "ru" to "Привет. Так этот голос читает ваши романы."
)

/** Plays a WAV made on the phone. */
private suspend fun play(context: android.content.Context, wav: ByteArray) {
    val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        java.io.File(context.cacheDir, "voice-sample.wav").apply { writeBytes(wav) }
    }
    android.media.MediaPlayer().apply {
        setDataSource(file.path)
        setOnCompletionListener { it.release() }
        prepare()
        start()
    }
}

/**
 * Ajustes › Voces naturales en el móvil: download or remove each voice (about 20 MB). Once downloaded, novels and
 * bubbles can be read aloud with them without a server or a connection.
 */
@Composable
fun DeviceVoicesSection() {
    val voices = LocalAppContainer.current.deviceVoices
    val installed by voices.installed.collectAsState()
    val downloading by voices.downloading.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        if (!voices.supported) {
            Text(tr("Este móvil no puede usar las voces del dispositivo."), modifier = Modifier.padding(horizontal = 16.dp))
            return@Column
        }
        Text(
            tr("Unos 20 MB cada una. Funcionan sin servidor y sin conexión al leer novelas en voz alta."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        DeviceVoices.CATALOG.forEach { v ->
            val has = v.id in installed
            ListItem(
                headlineContent = { Text(v.label) },
                supportingContent = { Text(tr(LANGUAGE_NAMES[v.lang] ?: v.lang) + " · " + tr(if (v.gender == "female") "mujer" else "hombre")) },
                trailingContent = {
                    when {
                        downloading == v.id -> CircularProgressIndicator()
                        has -> androidx.compose.foundation.layout.Row {
                            TextButton(onClick = {
                                scope.launch { runCatching { play(context, voices.speak(v.id, SAMPLES[v.lang] ?: SAMPLES.getValue("en"), 1f)) }.onFailure { error = it.message } }
                            }) { Text(tr("Probar")) }
                            TextButton(onClick = { scope.launch { voices.delete(v.id) } }) { Text(tr("Quitar")) }
                        }
                        else -> TextButton(enabled = downloading == null, onClick = {
                            scope.launch { voices.download(v.id).onFailure { error = it.message } }
                        }) { Text(tr("Descargar")) }
                    }
                }
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}
