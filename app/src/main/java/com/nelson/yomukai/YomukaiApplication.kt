package com.nelson.yomukai

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.nelson.yomukai.data.discovery.AniListClient
import com.nelson.yomukai.data.discovery.ContentFilter
import com.nelson.yomukai.data.discovery.DiscoveryRepository
import com.nelson.yomukai.data.download.DownloadManager
import com.nelson.yomukai.data.download.VideoOffline
import com.nelson.yomukai.data.local.YomukaiDatabase
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.network.TorManager
import com.nelson.yomukai.data.network.WebViewProxy
import com.nelson.yomukai.data.prefs.AppPreferences
import com.nelson.yomukai.data.repository.ContentRepository
import com.nelson.yomukai.data.repository.HomeCache
import com.nelson.yomukai.data.repository.LibraryRepository
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.data.updates.LibraryUpdateWorker
import com.nelson.yomukai.ui.web.CaptureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.conscrypt.Conscrypt
import java.security.Security

/** Manual dependency container; one instance per process. */
class AppContainer(context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob())
    val preferences = AppPreferences(context)
    val tor = TorManager(context, preferences.network, applicationScope)
    val network = NetworkHelper(context, preferences.network, tor.state, applicationScope)
    val database = YomukaiDatabase.build(context)
    val sourceManager = SourceManager(network).also { sm ->
        network.domainsOfSources = sm::domainsOf
        preferences.enableNewSources(sm.defaultEnabledIds)
    }
    val webViewProxy = WebViewProxy(context, preferences.network, tor.state, sourceManager::domainsOf, applicationScope)
        .also { proxy ->
            network.webViewOnTor = { proxy.state.value.let { it.ready && it.viaTor } }
            network.webViewReady = { proxy.state.value.ready }
        }
    val libraryRepository = LibraryRepository(database, sourceManager).also { repo ->
        // What was learnt about chapter lists survives a restart; saved a moment after it changes.
        val file = java.io.File(context.filesDir, "chapter_health.tsv")
        applicationScope.launch(Dispatchers.IO) { runCatching { if (file.exists()) repo.chapterHealth.import(file.readText()) } }
        val saving = java.util.concurrent.atomic.AtomicBoolean(false)
        repo.chapterHealth.onChange = {
            if (saving.compareAndSet(false, true)) applicationScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(5_000)
                saving.set(false)
                runCatching { file.writeText(repo.chapterHealth.export()) }
            }
        }
    }
    private val probing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val probeLimit = kotlinx.coroutines.sync.Semaphore(2)

    /**
     * Reads in the background, two at a time, the chapter lists of [items] never checked, so empty
     * titles disappear from the lists and well-kept ones come first. Sources behind anti-bot checks are left alone.
     */
    fun probeChapters(items: List<com.nelson.yomukai.domain.model.MediaItem>) {
        libraryRepository.chapterHealth.unknown(items).forEach { item ->
            val source = sourceManager.get(item.sourceId)?.takeIf { it.cheapChapterList } ?: return@forEach
            val key = item.sourceId + "|" + item.id
            if (!probing.add(key)) return@forEach
            applicationScope.launch(Dispatchers.IO) {
                try {
                    probeLimit.withPermit {
                        kotlinx.coroutines.withTimeoutOrNull(20_000) { libraryRepository.chapterHealth.record(item.sourceId, item.id, source.fetchChapterList(item.id)) }
                    }
                } finally { probing.remove(key) }
            }
        }
    }
    val serverSync = com.nelson.yomukai.data.sync.ServerSync(context, database, preferences) { broken -> sourceManager.degraded = broken }
    val videoOffline = VideoOffline(context, network)
    val downloadManager = DownloadManager(context, database, sourceManager, network, applicationScope, preferences, videoOffline)
    val contentRepository = ContentRepository(sourceManager, downloadManager)
    val captureStore = CaptureStore()
    val homeCache = HomeCache(context)

    /** First catalogue page of each source opened in this session. */
    val browseMemory = android.util.LruCache<String, List<com.nelson.yomukai.domain.model.MediaItem>>(16)
    val contentFilter = ContentFilter(preferences, libraryRepository)
    private val aniList = AniListClient(network)
    val metadata = com.nelson.yomukai.data.metadata.MetadataRepository(
        aniList,
        com.nelson.yomukai.data.metadata.KitsuClient(network),
        com.nelson.yomukai.data.metadata.MalClient(network, BuildConfig.MAL_CLIENT_ID),
        com.nelson.yomukai.data.metadata.MetadataStore(context.filesDir),
        com.nelson.yomukai.data.metadata.MangaUpdatesClient(network),
        com.nelson.yomukai.data.metadata.OpenLibraryClient(network)
    )
    val discovery = DiscoveryRepository(
        sourceManager, { preferences.app.value.enabledSources }, aniList,
        savedTitles = { homeCache.load().values.flatten() },
        filter = { contentFilter }
    )
}

private const val IMAGE_CACHE = "images"

class YomukaiApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        // Android < 10 has no TLS 1.3; servers that require it (api.mangadex.org) refuse the handshake.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Security.insertProviderAt(Conscrypt.newProvider(), 1)
        container = AppContainer(this)
        // Up to v1.2 the cache could hold challenge pages saved as images, which never recover by themselves.
        container.applicationScope.launch(Dispatchers.IO) { cacheDir.resolve("image_cache").deleteRecursively() }
        // Keep the periodic library update check in sync with the user's settings.
        container.applicationScope.launch {
            container.preferences.app
                .map { Triple(it.updatesEnabled, it.updateIntervalHours, it.updatesWifiOnly) }
                .distinctUntilChanged()
                .collect { LibraryUpdateWorker.schedule(this@YomukaiApplication, container.preferences.app.value) }
        }
        // Optional sync with a Yomukai server: changes here go up a little later, and once an hour
        // (and at every start) what changed on the web comes down.
        val sync = container.serverSync
        val wake = { if (sync.state.value.connected) com.nelson.yomukai.data.sync.SyncWorker.soon(this) }
        container.libraryRepository.onLocalChange = wake
        container.preferences.onSharedChanged = wake
        container.applicationScope.launch {
            sync.state.map { it.connected }.distinctUntilChanged().collect { connected ->
                com.nelson.yomukai.data.sync.SyncWorker.schedule(this@YomukaiApplication, connected)
                if (connected) com.nelson.yomukai.data.sync.SyncWorker.soon(this@YomukaiApplication, 0)
            }
        }
    }

    /** Debug builds only: log I/O on the main thread and leaked resources so lag sources are visible. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build()
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.network.imageCallFactory })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(IMAGE_CACHE))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
