package com.nelson.yomukai.ui.home

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nelson.yomukai.data.discovery.DiscoverEntry
import com.nelson.yomukai.data.discovery.DiscoverLabels
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.i18n.tr
import com.nelson.yomukai.ui.components.CoverImage
import com.nelson.yomukai.ui.components.SectionHeader
import com.nelson.yomukai.ui.discover.DiscoverShelf
import com.nelson.yomukai.ui.theme.DesignTokens
import kotlinx.coroutines.delay

private fun score10(score: Int?): String = DiscoverLabels.score(score)

/**
 * Highlighted titles at the top of the home page, like the web: AniList's wide banner faded into the page, title,
 * score and genres, and one button. Changes every 8 s unless the system's animations are off.
 */
@Composable
fun HomeHero(entries: List<DiscoverEntry>, onOpen: (DiscoverEntry) -> Unit, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val pager = rememberPagerState { entries.size }
    val context = LocalContext.current
    val animationsOn = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    LaunchedEffect(pager, animationsOn) {
        if (!animationsOn) return@LaunchedEffect
        while (true) {
            delay(8_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % entries.size)
        }
    }
    Box(modifier.fillMaxWidth().height(380.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val e = entries[page]
            Box(Modifier.fillMaxSize().clickable { onOpen(e) }) {
                CoverImage(e.banner.ifBlank { e.cover }, "", null, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            0.3f to Color.Transparent,
                            0.55f to Color.Black.copy(alpha = 0.7f),
                            0.75f to Color.Black.copy(alpha = 0.85f),
                            1f to MaterialTheme.colorScheme.background
                        )
                    )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 36.dp)) {
                    Text(tr("Tendencia"), color = DesignTokens.text, style = MaterialTheme.typography.labelLarge)
                    Text(
                        e.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
                        color = DesignTokens.text, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    val meta = listOfNotNull(score10(e.score).takeIf { it.isNotEmpty() }?.let { "★ $it" }) + e.genres.take(3).map(DiscoverLabels::genre)
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString("  ·  "), color = DesignTokens.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    Button(onClick = { onOpen(e) }, modifier = Modifier.padding(top = 14.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (e.type == MediaType.ANIME) tr("Ver") else tr("Leer"))
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(entries.size) { i ->
                val on = i == pager.currentPage
                Box(
                    Modifier
                        .size(width = if (on) 20.dp else 7.dp, height = 7.dp)
                        .clip(CircleShape)
                        .background(if (on) DesignTokens.text else DesignTokens.text.copy(alpha = 0.3f))
                )
            }
        }
    }
}

/** A titled row of AniList posters (2:3) with score and format, the same rows as the web's home. */
@Composable
fun DiscoverShelfRow(shelf: DiscoverShelf, onOpen: (DiscoverEntry) -> Unit) {
    Column {
        SectionHeader(shelf.title)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shelf.items, key = { it.anilistId }) { e -> PosterCard(e, onClick = { onOpen(e) }) }
        }
    }
}

@Composable
private fun PosterCard(e: DiscoverEntry, onClick: () -> Unit) {
    Column(
        Modifier
            .width(124.dp)
            .clip(RoundedCornerShape(DesignTokens.radiusMd))
            .clickable(onClick = onClick)
            .semantics { contentDescription = e.title }
    ) {
        Box {
            CoverImage(e.cover, "", null, Modifier.fillMaxWidth().aspectRatio(DesignTokens.POSTER_RATIO).clip(RoundedCornerShape(DesignTokens.radiusMd)))
            score10(e.score).takeIf { it.isNotEmpty() }?.let {
                Text(
                    "★ $it", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DesignTokens.text,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(DesignTokens.radiusXl))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            e.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
        val line = listOfNotNull(DiscoverLabels.format(e.format).takeIf { it.isNotBlank() }, e.year?.toString()).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelSmall, color = DesignTokens.textDim, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}
