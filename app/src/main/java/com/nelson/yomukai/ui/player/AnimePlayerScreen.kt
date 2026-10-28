package com.nelson.yomukai.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.LocalAppContainer
import com.nelson.yomukai.ui.reader.ChapterIndexSheet
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Seconds skipped by the "skip opening" button. */
private const val SKIP_OPENING_MS = 85_000L

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
fun AnimePlayerScreen(
    onBackClick: () -> Unit = {},
    viewModel: AnimePlayerViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val context = LocalContext.current
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val episodes by viewModel.chapterList.collectAsStateWithLifecycle()
    val playing = ui.playing

    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Fill",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom"
    )
    var currentResizeModeIndex by remember { mutableStateOf(0) }

    var isPlaying by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var currentPosition by remember(playing.videoUrl) { mutableLongStateOf(playing.startPositionMs) }
    var totalDuration by remember(playing.videoUrl) { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var sliderPosition by remember(playing.videoUrl) { mutableFloatStateOf(0f) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var showIndex by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var inPip by remember { mutableStateOf(false) }

    val currentAutoNext by rememberUpdatedState(settings.playerAutoNext)
    val currentViewModel by rememberUpdatedState(viewModel)

    val offline = LocalAppContainer.current.videoOffline
    val exoPlayer = remember(playing.videoUrl, playing.referer, playing.local) {
        // Network playback goes through the app's OkHttp client (proxy / Tor / DoH); downloads read the Media3 cache.
        offline.registerReferer(playing.videoUrl, playing.referer)
        val dataSourceFactory = when (playing.local) {
            "hls" -> offline.cacheDataSourceFactory
            "file" -> DefaultDataSource.Factory(context)
            else -> offline.playbackFactory(playing.referer)
        }
        val mediaItem = MediaItem.fromUri(Uri.parse(playing.videoUrl))
        // HLS playlists are not always served with a .m3u8 extension (e.g. /m3u8/{id}).
        val mediaSource = if (playing.local == "hls" || (playing.local != "file" && playing.videoUrl.contains("m3u8"))) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            if (playing.startPositionMs > 0) seekTo(playing.startPositionMs)
            playWhenReady = true
        }
    }

    LaunchedEffect(exoPlayer, settings.playerSpeed) { exoPlayer.setPlaybackSpeed(settings.playerSpeed) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isLoading = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                }
                if (playbackState == Player.STATE_ENDED) {
                    val duration = exoPlayer.duration.coerceAtLeast(0L)
                    currentViewModel.onPosition(duration, duration, force = true)
                    if (currentAutoNext && currentViewModel.hasNext()) currentViewModel.playNext() else showControls = true
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            currentViewModel.onPosition(exoPlayer.currentPosition, exoPlayer.duration.coerceAtLeast(0L), force = true)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, isPlaying, isUserSeeking) {
        while (isPlaying && !isUserSeeking) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            totalDuration = exoPlayer.duration.coerceAtLeast(0L)
            if (totalDuration > 0) {
                sliderPosition = currentPosition.toFloat() / totalDuration.toFloat()
                viewModel.onPosition(currentPosition, totalDuration)
            }
            delay(500)
        }
    }

    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3500)
            showControls = false
        }
    }

    // Picture in picture: hide the controls while floating and let the system enter it on leaving the app.
    val activity = remember(context) { context.findComponentActivity() }
    DisposableEffect(activity) {
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            inPip = info.isInPictureInPictureMode
            if (inPip) showControls = false
        }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            activity?.removeOnPictureInPictureModeChangedListener(listener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }
    LaunchedEffect(activity, isPlaying) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity?.setPictureInPictureParams(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setAutoEnterEnabled(isPlaying).build()
            )
        }
    }
    fun enterPip() {
        activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = resizeModes[currentResizeModeIndex].first
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerView.resizeMode = resizeModes[currentResizeModeIndex].first
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading || ui.isResolving) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }

        ui.error?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(message, color = Color.White)
                TextButton(onClick = viewModel::dismissError) { Text("Cerrar") }
            }
        }

        AnimatedVisibility(
            visible = showControls && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = playing.title.ifEmpty { "Reproduciendo Anime" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (viewModel.hasEpisodeContext) {
                        IconButton(onClick = { showIndex = true }) {
                            Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Índice de episodios", tint = Color.White)
                        }
                    }

                    Box {
                        TextButton(onClick = { speedMenu = true }) {
                            Text(speedLabel(settings.playerSpeed), color = Color.White)
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            SPEEDS.forEach { speed ->
                                DropdownMenuItem(
                                    text = { Text(speedLabel(speed)) },
                                    onClick = { viewModel.setSpeed(speed); speedMenu = false }
                                )
                            }
                        }
                    }

                    if (activity != null) {
                        IconButton(onClick = ::enterPip) {
                            Icon(Icons.Default.PictureInPictureAlt, "Imagen en imagen", tint = Color.White)
                        }
                    }

                    IconButton(onClick = {
                        currentResizeModeIndex = (currentResizeModeIndex + 1) % resizeModes.size
                    }) {
                        Icon(
                            imageVector = Icons.Default.AspectRatio,
                            contentDescription = "Cambiar Relación de Aspecto",
                            tint = Color.White
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition - 10000).coerceAtLeast(0L)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Retroceder 10s",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                exoPlayer.play()
                            }
                        },
                        modifier = Modifier.size(68.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition + 10000).coerceAtMost(totalDuration)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Adelantar 10s",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(if (isUserSeeking) (sliderPosition * totalDuration).toLong() else currentPosition),
                            color = Color.White,
                            fontSize = 12.sp
                        )

                        Text(
                            text = formatTime(totalDuration),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Slider(
                        value = sliderPosition,
                        onValueChange = { pos ->
                            isUserSeeking = true
                            sliderPosition = pos
                        },
                        onValueChangeFinished = {
                            val targetMs = (sliderPosition * totalDuration).toLong()
                            exoPlayer.seekTo(targetMs)
                            currentPosition = targetMs
                            isUserSeeking = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = {
                            val target = (exoPlayer.currentPosition + SKIP_OPENING_MS).coerceAtMost(totalDuration.takeIf { it > 0 } ?: Long.MAX_VALUE)
                            exoPlayer.seekTo(target)
                            currentPosition = target
                        }) {
                            Icon(Icons.Default.FastForward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("+85 s", color = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        if (viewModel.hasEpisodeContext) {
                            Text("Siguiente automático", color = Color.White, fontSize = 12.sp)
                            Switch(checked = settings.playerAutoNext, onCheckedChange = viewModel::setAutoNext)
                            if (viewModel.hasNext()) {
                                Button(onClick = { viewModel.playNext() }) {
                                    Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Siguiente")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIndex) {
        ChapterIndexSheet(
            chapters = episodes,
            currentChapterId = playing.chapterId,
            onSelect = { viewModel.playEpisode(it) },
            onDismiss = { showIndex = false },
            title = "Episodios"
        )
    }
}

private fun speedLabel(speed: Float) = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

private fun formatTime(timeMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(timeMs.coerceAtLeast(0L))
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
