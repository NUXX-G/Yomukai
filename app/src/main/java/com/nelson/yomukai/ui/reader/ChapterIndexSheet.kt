package com.nelson.yomukai.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.search.SmartSearch

/** Pure filter so the index search can be unit tested: matches title or chapter number, accent-insensitive. */
fun filterChapters(chapters: List<ChapterEntity>, query: String): List<ChapterEntity> {
    val q = SmartSearch.normalize(query)
    if (q.isEmpty()) return chapters
    return chapters.filter { c ->
        SmartSearch.normalize(c.title).contains(q) || SmartSearch.normalize(c.number.toString()).contains(q)
    }
}

/**
 * Chapter / episode index: searchable, current item highlighted, read items marked, tap to jump.
 * Newest chapters are shown first, like the details screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterIndexSheet(
    chapters: List<ChapterEntity>,
    currentChapterId: String?,
    onSelect: (ChapterEntity) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Capítulos"
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(chapters, query) { filterChapters(chapters, query).sortedByDescending { it.sortIndex } }
    val listState = rememberLazyListState()

    LaunchedEffect(currentChapterId) {
        val i = shown.indexOfFirst { it.chapterId == currentChapterId }
        if (i > 2) listState.scrollToItem(i - 2)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar por título o número") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Borrar") }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                items(shown, key = { it.chapterId }) { chapter ->
                    val current = chapter.chapterId == currentChapterId
                    ListItem(
                        headlineContent = { Text(chapter.title) },
                        supportingContent = chapter.dateUploaded.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                        leadingContent = if (current) ({ Icon(Icons.Default.PlayArrow, "Capítulo actual") }) else null,
                        trailingContent = if (chapter.read) ({ Icon(Icons.Default.CheckCircle, "Leído") }) else null,
                        colors = ListItemDefaults.colors(
                            containerColor = if (current) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            headlineColor = if (chapter.read && !current) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.clickable { onSelect(chapter); onDismiss() }
                    )
                }
            }
        }
    }
}
