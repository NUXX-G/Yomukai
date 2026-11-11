package com.nelson.yomukai.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
internal fun BubbleOverlay(key: String, index: Int, image: Image, spec: BubbleTranslation, mode: FitMode, modifier: Modifier) {
    val ocr = LocalAppContainer.current.deviceOcr
    val speaker = LocalAppContainer.current.bubbleSpeaker
    var bubbles by remember(key, spec) { mutableStateOf<List<Pair<TextBox, String>>?>(null) }
    var failed by remember(key, spec) { mutableStateOf(false) }
    var original by remember(key) { mutableStateOf(false) }
    // A long press on a bubble opens its text with its translation and dictionary (F6.3).
    var picked by remember(key) { mutableStateOf<String?>(null) }
    picked?.let { text -> com.nelson.yomukai.ui.learning.WordSheet(text, text, spec.from, "") { picked = null } }
    LaunchedEffect(key, spec) {
        val read = readBubbles(ocr, key, image, spec, mode)
        if (read == null) failed = true else bubbles = read
        // Read aloud: the translation when translating (in its language), the original otherwise.
        if (spec.speak && read != null) speaker.offer(index, read.map { it.second }, if (spec.translate) spec.to else spec.from)
    }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val place = OverlayGeometry.place(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), image.width.toFloat(), image.height.toFloat(), mode)
            ?: return@BoxWithConstraints
        // Only worth saying when a translation was asked for (reading aloud or learning just get nothing).
        if (failed && spec.translate) {
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
        val shown = bubbles?.takeUnless { original && spec.translate } ?: return@BoxWithConstraints
        for ((box, text) in shown) {
            BubbleBox(box, text, image, place, spec, onOriginal = { original = true }, onPick = { picked = box.text })
        }
    }
}

/** The page's bubbles in reading order with their text to show (translated when asked); null if it failed. */
private suspend fun readBubbles(
    ocr: com.nelson.yomukai.data.ocr.DeviceOcr, key: String, image: Image, spec: BubbleTranslation, mode: FitMode
): List<Pair<TextBox, String>>? {
    val boxes = runCatching { ocr.read(key, image.toBitmap(), spec.from) }.getOrNull() ?: return null
    // A long strip top to bottom, Japanese right to left, the rest left to right.
    val direction = when {
        mode == FitMode.WIDTH -> com.nelson.yomukai.data.ocr.ReadingDirection.TOP_DOWN
        spec.from == "ja" -> com.nelson.yomukai.data.ocr.ReadingDirection.RIGHT_TO_LEFT
        else -> com.nelson.yomukai.data.ocr.ReadingDirection.LEFT_TO_RIGHT
    }
    val speech = com.nelson.yomukai.data.ocr.ReadingOrder.sort(boxes.filter { it.kind == BoxKind.BUBBLE }, direction)
    if (speech.isEmpty() || !spec.translate) return speech.map { it to it.text }
    return ocr.translate(speech.map { it.text }, spec.from, spec.to)?.let { speech.zip(it) }
}

/** One bubble: its translation painted over it (or an invisible area when only learning or reading aloud). */
@Composable
private fun BubbleBox(
    box: TextBox, text: String, image: Image, place: com.nelson.yomukai.data.ocr.Placement, spec: BubbleTranslation,
    onOriginal: () -> Unit, onPick: () -> Unit
) {
    val density = LocalDensity.current
    val wPx = (box.w * image.width * place.scale).toFloat()
    val hPx = (box.h * image.height * place.scale).toFloat()
    val x = place.dx + (box.x * image.width * place.scale).toFloat()
    val y = place.dy + (box.y * image.height * place.scale).toFloat()
    // As large as the bubble allows for this many letters, within readable limits.
    val fontPx = (sqrt(wPx * hPx / text.length.coerceAtLeast(1)) * FONT_FILL)
        .coerceIn(with(density) { MIN_FONT.dp.toPx() }, with(density) { MAX_FONT.dp.toPx() })
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .then(if (spec.translate) Modifier.background(Color.White.copy(alpha = 0.95f), RoundedCornerShape(6.dp)) else Modifier)
            .combinedClickable(
                interactionSource = null, indication = null,
                onClick = { if (spec.translate) onOriginal() },
                onLongClick = if (spec.learning) onPick else null
            ),
        contentAlignment = Alignment.Center
    ) {
        if (spec.translate) {
            Text(
                text, color = Color.Black, textAlign = TextAlign.Center,
                fontSize = with(density) { fontPx.toSp() }, lineHeight = with(density) { (fontPx * LINE).toSp() }
            )
        }
    }
}

private const val FONT_FILL = 0.62f
private const val MIN_FONT = 9
private const val MAX_FONT = 20
private const val LINE = 1.1f
