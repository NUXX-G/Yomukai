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

    private val _storage = MutableStateFlow(0L to 0L) // image cache bytes, download bytes
    val storage: StateFlow<Pair<Long, Long>> = _storage.asStateFlow()

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

    fun clearHistory() {
        viewModelScope.launch { container.libraryRepository.clearHistory() }
    }
}
