package com.nelson.yomukai.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.local.BookmarkEntity

/** Bookmarks of the current title, newest first. [positionLabel] says "Página 4" / "Párrafo 12". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    positionLabel: (Int) -> String,
    onOpen: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Marcadores", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            if (bookmarks.isEmpty()) {
                Text(
                    "Aún no hay marcadores. Usa el icono de marcador del lector para guardar tu posición.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp)
                )
            }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    ListItem(
                        headlineContent = { Text("${bookmark.chapterTitle} · ${positionLabel(bookmark.position)}") },
                        supportingContent = bookmark.label.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2) } },
                        leadingContent = { Icon(Icons.Default.Bookmark, null) },
                        trailingContent = {
                            IconButton(onClick = { onDelete(bookmark) }) { Icon(Icons.Default.Delete, "Eliminar marcador") }
                        },
                        modifier = Modifier.clickable { onOpen(bookmark); onDismiss() }
                    )
                }
            }
        }
    }
}
