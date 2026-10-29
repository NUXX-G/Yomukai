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
import org.conscrypt.Conscrypt
import java.security.Security
import com.nelson.yomukai.data.download.DownloadManager
import com.nelson.yomukai.data.download.VideoOffline
import com.nelson.yomukai.data.local.YomukaiDatabase
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.network.TorManager
import com.nelson.yomukai.data.network.WebViewProxy
import com.nelson.yomukai.data.prefs.AppPreferences
import com.nelson.yomukai.data.repository.ContentRepository
import com.nelson.yomukai.data.repository.LibraryRepository
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.data.updates.LibraryUpdateWorker
import com.nelson.yomukai.ui.web.CaptureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual dependency container; one instance per process. */
class AppContainer(context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob())
    val preferences = AppPreferences(context)
    val tor = TorManager(context, preferences.network, applicationScope)
    val network = NetworkHelper(context, preferences.network, tor.state, applicationScope)
    val database = YomukaiDatabase.build(context)
    val sourceManager = SourceManager(network).also { sm -> network.domainsOfSources = sm::domainsOf }
    val webViewProxy = WebViewProxy(context, preferences.network, tor.state, sourceManager::domainsOf, applicationScope)
    val libraryRepository = LibraryRepository(database, sourceManager)
    val videoOffline = VideoOffline(context, network)
    val downloadManager = DownloadManager(context, database, sourceManager, network, applicationScope, preferences, videoOffline)
    val contentRepository = ContentRepository(sourceManager, downloadManager)
    val captureStore = CaptureStore()
}

class YomukaiApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        // Android < 10 has no TLS 1.3; servers that require it (api.mangadex.org) refuse the handshake.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Security.insertProviderAt(Conscrypt.newProvider(), 1)
        container = AppContainer(this)
        // Keep the periodic library update check in sync with the user's settings.
        container.applicationScope.launch {
            container.preferences.app
                .map { Triple(it.updatesEnabled, it.updateIntervalHours, it.updatesWifiOnly) }
                .distinctUntilChanged()
                .collect { LibraryUpdateWorker.schedule(this@YomukaiApplication, container.preferences.app.value) }
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
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.network.callFactory })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
