package com.nelson.yomukai.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nelson.yomukai.AppContainer
import com.nelson.yomukai.YomukaiApplication
import com.nelson.yomukai.ui.browse.BrowseViewModel
import com.nelson.yomukai.ui.details.DetailsViewModel
import com.nelson.yomukai.ui.home.HomeViewModel
import com.nelson.yomukai.ui.library.LibraryViewModel
import com.nelson.yomukai.ui.reader.MangaReaderViewModel
import com.nelson.yomukai.ui.reader.NovelReaderViewModel
import com.nelson.yomukai.ui.search.SearchViewModel
import com.nelson.yomukai.ui.settings.SettingsViewModel

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer no proporcionado") }

private fun CreationExtras.container(): AppContainer =
    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as YomukaiApplication).container

object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer { HomeViewModel(container()) }
        initializer { SearchViewModel(createSavedStateHandle(), container()) }
        initializer { BrowseViewModel(createSavedStateHandle(), container()) }
        initializer { DetailsViewModel(createSavedStateHandle(), container()) }
        initializer { MangaReaderViewModel(createSavedStateHandle(), container()) }
        initializer { NovelReaderViewModel(createSavedStateHandle(), container()) }
        initializer { LibraryViewModel(container()) }
        initializer { SettingsViewModel(container()) }
    }
}
