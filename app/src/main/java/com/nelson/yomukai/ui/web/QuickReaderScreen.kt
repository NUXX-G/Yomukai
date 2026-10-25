package com.nelson.yomukai.ui.web

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.ui.LocalAppContainer
import com.nelson.yomukai.ui.components.MessageState
import com.nelson.yomukai.ui.reader.ReaderWindowEffects
import com.nelson.yomukai.ui.reader.WebtoonReader

/** Vertical reader for images captured from an arbitrary web page (not stored in the library). */
@Composable
fun QuickReaderScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val captured = remember { container.captureStore.chapter }
    var overlay by rememberSaveable { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(0) }

    ReaderWindowEffects(brightness = -1f, keepScreenOn = true, immersive = !overlay)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (captured == null || captured.pages.isEmpty()) {
            MessageState("No hay imágenes capturadas.", actionLabel = "Volver", onAction = onBack)
            return@Box
        }
        WebtoonReader(
            pages = captured.pages,
            initialPage = 0,
            jumpTarget = null,
            onJumpHandled = {},
            onPageChanged = { page = it },
            onTap = { overlay = !overlay },
            footer = {
                Text(
                    "Fin de las imágenes capturadas",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp)
                )
            }
        )
        AnimatedVisibility(visible = overlay, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(captured.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Página ${page + 1} de ${captured.pages.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

