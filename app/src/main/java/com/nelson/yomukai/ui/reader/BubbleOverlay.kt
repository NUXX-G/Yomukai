package com.nelson.yomukai.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.toBitmap
import com.nelson.yomukai.data.ocr.BoxKind
import com.nelson.yomukai.data.ocr.BubbleTranslation
import com.nelson.yomukai.data.ocr.FitMode
import com.nelson.yomukai.data.ocr.OverlayGeometry
import com.nelson.yomukai.data.ocr.TextBox
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.LocalAppContainer
import kotlin.math.roundToInt
import kotlin.math.sqrt

val LocalBubbleTranslation = compositionLocalOf<BubbleTranslation?> { null }

/**
 * The page's bubbles read and translated on the device, each painted over its bubble. A tap on one shows the
 * original page again; sound effects are left as drawn.
 */
@Composable
internal fun BubbleOverlay(key: String, image: Image, spec: BubbleTranslation, mode: FitMode, modifier: Modifier) {
    val ocr = LocalAppContainer.current.deviceOcr
    var bubbles by remember(key, spec) { mutableStateOf<List<Pair<TextBox, String>>?>(null) }
    var failed by remember(key, spec) { mutableStateOf(false) }
    var original by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, spec) {
        val boxes = runCatching { ocr.read(key, image.toBitmap(), spec.from) }.getOrNull()
        val speech = boxes?.filter { it.kind == BoxKind.BUBBLE }.orEmpty()
        val translated = if (speech.isEmpty()) emptyList() else ocr.translate(speech.map { it.text }, spec.from, spec.to)
        if (boxes == null || translated == null) failed = true else bubbles = speech.zip(translated)
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val place = OverlayGeometry.place(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), image.width.toFloat(), image.height.toFloat(), mode)
            ?: return@BoxWithConstraints
        if (failed) {
            Text(
                tr("No se pudo traducir esta página en el móvil"), color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(6.dp)
            )
        }
        if (original && bubbles != null) {
            Text(
                tr("Ver traducción"), color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable { original = false }.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        val shown = bubbles?.takeUnless { original } ?: return@BoxWithConstraints
        for ((box, text) in shown) {
            val wPx = (box.w * image.width * place.scale).toFloat()
            val hPx = (box.h * image.height * place.scale).toFloat()
            val x = place.dx + (box.x * image.width * place.scale).toFloat()
            val y = place.dy + (box.y * image.height * place.scale).toFloat()
            // As large as the bubble allows for this many letters, within readable limits.
            val fontPx = (sqrt(wPx * hPx / text.length.coerceAtLeast(1)) * FONT_FILL).coerceIn(with(density) { MIN_FONT.dp.toPx() }, with(density) { MAX_FONT.dp.toPx() })
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                    .background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                    .clickable { original = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text, color = Color.Black, textAlign = TextAlign.Center,
                    fontSize = with(density) { fontPx.toSp() }, lineHeight = with(density) { (fontPx * LINE).toSp() }
                )
            }
        }
    }
}

private const val FONT_FILL = 0.62f
private const val MIN_FONT = 9
private const val MAX_FONT = 20
private const val LINE = 1.1f
