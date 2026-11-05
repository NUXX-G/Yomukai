package com.nelson.yomukai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.transformations
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.ui.LocalAppContainer

/**
 * Loading indicator that always turns. Compose's own one is driven by system animations, so with
 * animations reduced or off in Android (battery saver included) it stays frozen as a dot. This one
 * counts real frame time, which those settings do not touch.
 */
@Composable
fun AppSpinner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            angle = (angle + (now - last) * SPINNER_DEGREES_PER_MS) % 360f
            last = now
        }
    }
    Canvas(modifier.size(40.dp)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = SPINNER_SWEEP,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

/** Pull-to-refresh indicator built on [AppSpinner]: follows the finger, then keeps turning while it refreshes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.RefreshSpinner(state: PullToRefreshState, isRefreshing: Boolean) {
    val pull = state.distanceFraction
    if (!isRefreshing && pull <= 0f) return
    Surface(
        shape = CircleShape,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .graphicsLayer {
                val travel = 72.dp.toPx()
                translationY = if (isRefreshing) 16.dp.toPx() else pull.coerceAtMost(1.3f) * travel - 24.dp.toPx()
            }
    ) {
        AppSpinner(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.5.dp)
    }
}

private const val SPINNER_DEGREES_PER_MS = 0.36f
private const val SPINNER_SWEEP = 270f

fun MediaType.badgeColor(): Color = when (this) {
    MediaType.ANIME -> Color(0xFFE53935)
    MediaType.MANGA -> Color(0xFF1E88E5)
    MediaType.MANHWA -> Color(0xFF43A047)
    MediaType.MANHUA -> Color(0xFFFB8C00)
    MediaType.COMIC -> Color(0xFF00897B)
    MediaType.NOVEL -> Color(0xFF8E24AA)
}

/** Builds an image request with the Referer/headers the owning source requires. */
@Composable
fun rememberImageRequest(model: Any?, headers: Map<String, String>, cropBorders: Boolean = false): ImageRequest {
    val context = LocalContext.current
    return remember(model, headers, context, cropBorders) {
        ImageRequest.Builder(context)
            .data(model)
            .apply { if (cropBorders) transformations(com.nelson.yomukai.ui.reader.TrimBordersTransformation()) }
            .apply {
                if (headers.isNotEmpty()) {
                    httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
                }
            }
            .build()
    }
}

@Composable
fun CoverImage(url: String, sourceId: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val headers = LocalAppContainer.current.sourceManager.get(sourceId)?.imageHeaders.orEmpty()
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url.isBlank()) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(36.dp)
            )
        } else {
            AsyncImage(
                model = rememberImageRequest(url, headers),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Flat, quiet type marker: a small dot in the type's color and its name in the secondary text color. */
@Composable
fun TypeBadge(type: MediaType, modifier: Modifier = Modifier, label: String = type.displayName) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(type.badgeColor().copy(alpha = 0.85f), CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Poster-style card used in rows and grids: the cover and at most two lines of title. Nothing is
 * painted over the cover. [subtitle] adds one quiet line under the title; [showType] a flat type
 * marker, for lists that mix types.
 */
@Composable
fun MediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showType: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(14.dp))
        ) {
            CoverImage(item.coverUrl, item.sourceId, item.title, Modifier.fillMaxSize())
        }
        Text(
            text = item.title.trim(),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
        if (showType) {
            TypeBadge(item.type, Modifier.padding(horizontal = 2.dp), label = subtitle ?: item.type.displayName)
        } else if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

/** Compact row used by list layouts. */
@Composable
fun MediaListRow(item: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            item.coverUrl, item.sourceId, item.title,
            Modifier
                .width(56.dp)
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TypeBadge(item.type)
                if (item.status.isNotBlank()) {
                    Text(item.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        trailing?.invoke()
    }
}

/** Placeholder shaped like the row it replaces, so nothing jumps when the titles arrive. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(4) {
            Column(Modifier.width(120.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                )
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(0.8f)
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}

@Composable
fun LoadingState(message: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AppSpinner()
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MessageState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.CloudOff,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun LanguageTag(language: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
    ) {
        Text(
            language,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * Small, faint clock for full-screen reading and watching, when everything else is hidden. With
 * [pill] it sits on a dark translucent capsule, readable over any page.
 */
@Composable
fun MiniClock(modifier: Modifier = Modifier, pill: Boolean = false) {
    val context = LocalContext.current
    val format = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(format.format(java.util.Date())) }
    LaunchedEffect(format) {
        while (true) {
            now = format.format(java.util.Date())
            // Wakes up right after the minute changes instead of polling all the time.
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }
    Text(
        now,
        color = Color.White.copy(alpha = if (pill) 0.85f else 0.6f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.labelMedium.copy(
            shadow = if (pill) null else androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f)
        ),
        modifier = modifier
            .then(if (pill) Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50)) else Modifier)
            .padding(horizontal = if (pill) 8.dp else 0.dp, vertical = if (pill) 2.dp else 0.dp)
    )
}

fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - epochMs) / 60_000
    return when {
        minutes < 1 -> "ahora"
        minutes < 60 -> "hace $minutes min"
        minutes < 60 * 24 -> "hace ${minutes / 60} h"
        minutes < 60 * 24 * 30 -> "hace ${minutes / (60 * 24)} d"
        else -> "hace ${minutes / (60 * 24 * 30)} meses"
    }
}
