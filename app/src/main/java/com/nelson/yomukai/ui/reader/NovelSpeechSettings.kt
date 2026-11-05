package com.nelson.yomukai.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.prefs.NovelReaderSettings

/**
 * Voices for reading aloud: natural (the server's) or the phone's, and which voice reads the
 * narration, the women and the men. [lang] is "es" or "en", the novel's language.
 */
@Composable
fun SpeechVoicesSection(
    speech: NovelSpeech,
    settings: NovelReaderSettings,
    lang: String,
    onUpdate: ((NovelReaderSettings) -> NovelReaderSettings) -> Unit
) {
    val natural by speech.naturalVoices.collectAsState()
    val system by speech.systemVoices.collectAsState()
    val naturalHere = natural.filter { it.lang == lang }
    val useNatural = naturalHere.isNotEmpty() && settings.ttsEngine != "system"

    Text("Voz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (naturalHere.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = useNatural, onClick = { onUpdate { it.copy(ttsEngine = "natural") } }, label = { Text("Naturales del servidor") })
            FilterChip(selected = !useNatural, onClick = { onUpdate { it.copy(ttsEngine = "system") } }, label = { Text("Del móvil") })
        }
    }

    val engine = if (useNatural) "natural" else "system"
    val roles = listOf("narrator" to "Narrador", "female" to "Personajes femeninos", "male" to "Personajes masculinos")
    val defaults = if (lang == "en") mapOf("narrator" to "en-lessac", "female" to "en-amy", "male" to "en-ryan")
    else mapOf("narrator" to "es-david", "female" to "es-lucia", "male" to "es-sergio")
    roles.forEach { (role, label) ->
        val key = "$engine.$lang.$role"
        val current = settings.ttsVoices[key]
        val options: List<Pair<String, String>> = if (useNatural) naturalHere.map { it.id to it.display() }
        else listOf("" to "La del sistema") + system.map { it.name to it.display() }
        val shownId = current ?: if (useNatural) defaults.getValue(role) else ""
        VoicePicker(
            label = label,
            value = options.firstOrNull { it.first == shownId }?.second ?: options.firstOrNull()?.second.orEmpty(),
            options = options,
            onPick = { id -> onUpdate { it.copy(ttsVoices = it.ttsVoices + (key to id)) } },
            onSample = { speech.sample(shownId, natural = useNatural) }
        )
    }
    Text(
        if (useNatural) "Voces neuronales de tu servidor de Yomukai. La primera vez que se usa una, el servidor la descarga (unos segundos)."
        else if (naturalHere.isEmpty()) "Para voces más naturales conecta la app a un servidor de Yomukai (Ajustes › Sincronizar), o instala voces de calidad alta en Ajustes de Android › Texto a voz."
        else "Las de calidad más alta salen primero; las «con internet» suenan mejor pero necesitan conexión.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SettingSwitch("Voces por personaje (los diálogos con la voz de quien habla)", settings.ttsCharacters) { v -> onUpdate { it.copy(ttsCharacters = v) } }
}

@Composable
private fun VoicePicker(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, onSample: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                TextButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                    Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { (id, name) ->
                        DropdownMenuItem(text = { Text(name, maxLines = 2) }, onClick = { open = false; onPick(id) })
                    }
                }
            }
        }
        IconButton(onClick = onSample) { Icon(Icons.Default.PlayArrow, contentDescription = "Probar la voz") }
    }
}


