package com.nelson.yomukai.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.prefs.GenreExclusions
import com.nelson.yomukai.data.source.CatalogueSource

private val SUGGESTIONS = listOf("BL", "GL", "Ecchi", "Harem", "Gore", "Terror", "Manhua", "Manhwa", "Novela", "Romance")

/** Genres to hide: for every source, or for one. Any word works, not only the suggested ones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExclusionsDialog(
    exclusions: GenreExclusions,
    sources: List<CatalogueSource>,
    onToggle: (sourceId: String?, genre: String) -> Unit,
    onDismiss: () -> Unit
) {
    var scope by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    val current = scope?.let { exclusions.bySource[it].orEmpty() } ?: exclusions.global

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Géneros excluidos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dónde se aplican", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = scope == null, onClick = { scope = null }, label = { Text("Todas las fuentes") })
                    sources.forEach { source ->
                        val count = exclusions.bySource[source.id].orEmpty().size
                        FilterChip(
                            selected = scope == source.id,
                            onClick = { scope = source.id },
                            label = { Text(if (count > 0) "${source.name} ($count)" else source.name) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(40) },
                        label = { Text("Género a excluir") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onToggle(scope, text.trim()); text = "" }, enabled = text.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "Añadir")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    current.sorted().forEach { genre ->
                        AssistChip(
                            onClick = { onToggle(scope, genre) },
                            label = { Text(genre) },
                            trailingIcon = { Icon(Icons.Default.Close, "Quitar", Modifier.size(16.dp)) }
                        )
                    }
                }
                Text("Sugerencias", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SUGGESTIONS.filter { it !in current }.forEach { genre ->
                        AssistChip(onClick = { onToggle(scope, genre) }, label = { Text(genre) })
                    }
                }
                Text(
                    "Muchas webs no indican los géneros en sus listas: Yomukai los consulta título a título, así que con exclusiones activas esas listas tardan algo más.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } }
    )
}
