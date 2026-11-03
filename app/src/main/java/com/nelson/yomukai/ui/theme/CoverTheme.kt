package com.nelson.yomukai.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Accent colors already taken from covers in this session; 0 means "this cover has no color". */
private val seeds = ConcurrentHashMap<String, Int>()

/** The dominant color of a cover, read from a tiny copy off the main thread; null while loading or for gray covers. */
@Composable
fun rememberCoverSeed(url: String, headers: Map<String, String>): Color? {
    val context = LocalContext.current
    var seed by remember(url) { mutableStateOf(seeds[url]?.takeIf { it != 0 }?.let(::Color)) }
    LaunchedEffect(url) {
        if (url.isBlank() || seeds.containsKey(url)) return@LaunchedEffect
        val found = withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(SAMPLE_SIZE)
                    .allowHardware(false)
                    .apply { if (headers.isNotEmpty()) httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build()) }
                    .build()
                val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@runCatching null
                val bitmap = result.image.toBitmap()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                CoverPalette.dominant(pixels)
            }.getOrNull()
        }
        seeds[url] = found ?: 0
        seed = found?.let(::Color)
    }
    return seed
}

/** Material scheme tinted by [seed] over [base]: accents, containers and a faint tint on the surfaces. */
fun coverColorScheme(seed: Color, base: ColorScheme, dark: Boolean): ColorScheme {
    val s = seed.toArgb()
    fun t(lightness: Float, saturation: Float = 0.75f) = Color(CoverPalette.tone(s, lightness, saturation))
    return if (dark) base.copy(
        primary = t(0.74f),
        onPrimary = t(0.14f),
        primaryContainer = t(0.28f, 0.5f),
        onPrimaryContainer = t(0.9f),
        secondary = t(0.8f, 0.35f),
        onSecondary = t(0.14f),
        secondaryContainer = t(0.22f, 0.3f),
        onSecondaryContainer = t(0.9f, 0.3f),
        background = t(0.07f, 0.28f),
        surface = t(0.07f, 0.28f),
        surfaceVariant = t(0.17f, 0.18f),
        surfaceContainerLowest = t(0.05f, 0.25f),
        surfaceContainerLow = t(0.09f, 0.25f),
        surfaceContainer = t(0.11f, 0.22f),
        surfaceContainerHigh = t(0.14f, 0.2f),
        surfaceContainerHighest = t(0.18f, 0.18f)
    ) else base.copy(
        primary = t(0.36f),
        onPrimary = Color.White,
        primaryContainer = t(0.88f, 0.6f),
        onPrimaryContainer = t(0.12f),
        secondary = t(0.4f, 0.3f),
        onSecondary = Color.White,
        secondaryContainer = t(0.9f, 0.35f),
        onSecondaryContainer = t(0.14f, 0.35f),
        background = t(0.975f, 0.4f),
        surface = t(0.975f, 0.4f),
        surfaceVariant = t(0.91f, 0.2f),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = t(0.96f, 0.3f),
        surfaceContainer = t(0.94f, 0.28f),
        surfaceContainerHigh = t(0.92f, 0.25f),
        surfaceContainerHighest = t(0.9f, 0.22f)
    )
}

/**
 * Wraps [content] in a theme colored after the cover at [coverUrl]. Colors fade in once the cover
 * is read; a cover without color keeps the app's own theme.
 */
@Composable
fun CoverTheme(coverUrl: String, headers: Map<String, String>, enabled: Boolean = true, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val seed = if (enabled) rememberCoverSeed(coverUrl, headers) else null
    val dark = base.background.luminance() < 0.5f
    val target = remember(seed, base, dark) { seed?.let { coverColorScheme(it, base, dark) } ?: base }
    val scheme = target.animated()
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable
    fun Color.anim() = animateColorAsState(this, tween(FADE_MS), label = "cover").value
    return copy(
        primary = primary.anim(),
        onPrimary = onPrimary.anim(),
        primaryContainer = primaryContainer.anim(),
        onPrimaryContainer = onPrimaryContainer.anim(),
        secondary = secondary.anim(),
        secondaryContainer = secondaryContainer.anim(),
        onSecondaryContainer = onSecondaryContainer.anim(),
        background = background.anim(),
        surface = surface.anim(),
        surfaceVariant = surfaceVariant.anim(),
        surfaceContainerLow = surfaceContainerLow.anim(),
        surfaceContainer = surfaceContainer.anim(),
        surfaceContainerHigh = surfaceContainerHigh.anim(),
        surfaceContainerHighest = surfaceContainerHighest.anim()
    )
}

private const val SAMPLE_SIZE = 48
private const val FADE_MS = 450
