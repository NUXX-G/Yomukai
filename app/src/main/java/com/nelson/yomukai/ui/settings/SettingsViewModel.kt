package com.nelson.yomukai.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.data.prefs.AppSettings
import com.nelson.yomukai.data.prefs.MangaReaderSettings
import com.nelson.yomukai.data.prefs.NetworkSettings
import com.nelson.yomukai.data.source.CatalogueSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val app: StateFlow<AppSettings> = container.preferences.app
    val manga: StateFlow<MangaReaderSettings> = container.preferences.manga
    val network: StateFlow<NetworkSettings> = container.preferences.network
    val sources: List<CatalogueSource> = container.sourceManager.all
    val tor: StateFlow<com.nelson.yomukai.data.network.TorState> = container.tor.state
    val sync: StateFlow<com.nelson.yomukai.data.sync.SyncState> = container.serverSync.state

    suspend fun connectServer(server: String, user: String, password: String, code: String?): String? =
        container.serverSync.connect(server, user, password, code)

    private val _features = MutableStateFlow<com.nelson.yomukai.data.sync.ServerFeatures?>(null)

    /** The connected server's switches and home-worker status; null when not connected. */
    val features: StateFlow<com.nelson.yomukai.data.sync.ServerFeatures?> = _features.asStateFlow()

    fun refreshFeatures() { viewModelScope.launch { _features.value = container.serverSync.features() } }

    init { refreshFeatures() }

    fun syncNow() { viewModelScope.launch { container.serverSync.sync(); refreshFeatures() } }

    fun disconnectServer() { viewModelScope.launch { container.serverSync.disconnect() } }

    private val _storage = MutableStateFlow(0L to 0L) // image cache bytes, download bytes
    val storage: StateFlow<Pair<Long, Long>> = _storage.asStateFlow()

    fun degradedReason(id: String): String? = container.sourceManager.degraded[id]

    fun isSourceEnabled(settings: AppSettings, id: String) =
        id in (settings.enabledSources ?: container.sourceManager.defaultEnabledIds)

    fun setSourceEnabled(id: String, enabled: Boolean) = container.preferences.updateApp { s ->
        val current = s.enabledSources ?: container.sourceManager.defaultEnabledIds
        s.copy(enabledSources = if (enabled) current + id else current - id)
    }

    fun updateApp(transform: (AppSettings) -> AppSettings) = container.preferences.updateApp(transform)
    fun updateNetwork(transform: (NetworkSettings) -> NetworkSettings) = container.preferences.updateNetwork(transform)
    fun updateManga(transform: (MangaReaderSettings) -> MangaReaderSettings) = container.preferences.updateManga(transform)

    fun refreshStorage(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val images = SingletonImageLoader.get(context).diskCache?.size ?: 0L
            _storage.value = images to container.downloadManager.storageBytes()
        }
    }

    fun clearImageCache(context: Context) {
        viewModelScope.launch {
            val loader = SingletonImageLoader.get(context)
            loader.memoryCache?.clear()
            withContext(Dispatchers.IO) { loader.diskCache?.clear() }
            refreshStorage(context)
        }
    }

    /** Copies what was downloaded before to Download/Yomukai. */
    fun exportDownloads(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = container.downloadManager.exportAll()
            onDone(count)
        }
    }

    val exclusions = container.preferences.exclusions
    fun toggleExclusion(sourceId: String?, genre: String) = container.preferences.toggleExclusion(sourceId, genre)

    fun clearHistory() {
        viewModelScope.launch { container.libraryRepository.clearHistory() }
    }
}
