package com.nelson.yomukai.data.download

import android.app.Notification
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.nelson.yomukai.YomukaiApplication
import com.nelson.yomukai.data.network.NetworkHelper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Media3 plumbing for offline HLS episodes. Every request goes through the app's OkHttp client,
 * so the DNS-over-HTTPS / proxy / Tor settings apply to video traffic and downloads too.
 * Playlists and segments land in [cache]; playback reads them back through [cacheDataSourceFactory].
 */
@OptIn(UnstableApi::class)
class VideoOffline(context: Context, private val network: NetworkHelper) {
    private val appContext = context.applicationContext
    private val databaseProvider: DatabaseProvider by lazy { StandaloneDatabaseProvider(appContext) }

    val cache by lazy { SimpleCache(File(appContext.filesDir, "downloads/hls_cache"), NoOpCacheEvictor(), databaseProvider) }

    /** Referer to send per host, registered when a download or playback starts. */
    private val refererByHost = ConcurrentHashMap<String, String>()
    @Volatile private var lastReferer = ""

    fun registerReferer(url: String, referer: String) {
        if (referer.isBlank()) return
        lastReferer = referer
        runCatching { java.net.URI(url).host }.getOrNull()?.let { refererByHost[it] = referer }
    }

    /** Network access for Media3, always through the current OkHttp client (proxy/DoH aware). */
    val upstreamFactory: DataSource.Factory = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(network.callFactory).setUserAgent(NetworkHelper.USER_AGENT)
    ) { spec ->
        val host = spec.uri.host
        val referer = (host?.let { refererByHost[it] } ?: lastReferer)
        // Never downgrade: offline video is fetched over HTTPS only (or .onion over Tor).
        DownloadSecurity.requireSecureUrl(spec.uri.toString())
        if (referer.isBlank()) spec else spec.withAdditionalHeaders(mapOf("Referer" to referer))
    }

    /** Plain playback of an online stream (any scheme the source gave), still through the app proxy. */
    fun playbackFactory(referer: String): DataSource.Factory =
        OkHttpDataSource.Factory(network.callFactory)
            .setUserAgent(NetworkHelper.USER_AGENT)
            .apply { if (referer.isNotBlank()) setDefaultRequestProperties(mapOf("Referer" to referer)) }

    val cacheDataSourceFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
        appContext, databaseProvider, cache, upstreamFactory, Executors.newFixedThreadPool(2)
    ).apply { maxParallelDownloads = 2 }
    }

    companion object {
        const val CHANNEL_ID = "video_downloads"
        const val NOTIFICATION_ID = 4711
    }
}

/** Foreground service that keeps Media3 HLS downloads alive while the app is in the background. */
@OptIn(UnstableApi::class)
class VideoDownloadService : DownloadService(
    VideoOffline.NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    VideoOffline.CHANNEL_ID,
    com.nelson.yomukai.R.string.download_channel_name,
    0
) {
    override fun getDownloadManager(): DownloadManager =
        (application as YomukaiApplication).container.videoOffline.downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, VideoOffline.CHANNEL_ID).buildProgressNotification(
            this, android.R.drawable.stat_sys_download, null, null, downloads, notMetRequirements
        )
}
