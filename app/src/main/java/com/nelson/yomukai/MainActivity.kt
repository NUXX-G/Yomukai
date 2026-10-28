package com.nelson.yomukai

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nelson.yomukai.data.prefs.ThemeMode
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.updates.LibraryUpdateWorker
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.VideoSource
import com.nelson.yomukai.ui.AppViewModelProvider
import com.nelson.yomukai.ui.LocalAppContainer
import com.nelson.yomukai.ui.browse.BrowseScreen
import com.nelson.yomukai.ui.details.DetailsScreen
import com.nelson.yomukai.ui.home.HomeScreen
import com.nelson.yomukai.ui.home.HomeViewModel
import com.nelson.yomukai.ui.library.LibraryScreen
import com.nelson.yomukai.ui.navigation.Routes
import com.nelson.yomukai.ui.player.AnimePlayerScreen
import com.nelson.yomukai.ui.reader.MangaReaderScreen
import com.nelson.yomukai.ui.reader.NovelReaderScreen
import com.nelson.yomukai.ui.search.SearchScreen
import com.nelson.yomukai.ui.settings.DiagnosticsScreen
import com.nelson.yomukai.ui.settings.SettingsScreen
import com.nelson.yomukai.ui.theme.YomukaiTheme
import com.nelson.yomukai.ui.web.QuickReaderScreen
import com.nelson.yomukai.ui.web.WebCaptureScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Title to open when the activity was launched from an update notification. */
    private val pendingDetails = mutableStateOf<Pair<String, String>?>(null)

    private fun readDeepLink(intent: Intent?) {
        val sourceId = intent?.getStringExtra(LibraryUpdateWorker.EXTRA_SOURCE_ID) ?: return
        val mediaId = intent.getStringExtra(LibraryUpdateWorker.EXTRA_MEDIA_ID) ?: return
        pendingDetails.value = sourceId to mediaId
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readDeepLink(intent)
        val container = (application as YomukaiApplication).container
        setContent {
            val settings by container.preferences.app.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                YomukaiTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                    YomukaiApp(container, pendingDetails.value, onDeepLinkHandled = { pendingDetails.value = null })
                }
            }
        }
    }
}

private data class TopLevelTab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TopLevelTab(Routes.HOME, "Explorar", Icons.Default.Explore),
    TopLevelTab(Routes.SEARCH, "Buscar", Icons.Default.Search),
    TopLevelTab(Routes.LIBRARY, "Biblioteca", Icons.Default.CollectionsBookmark),
    TopLevelTab(Routes.SETTINGS, "Ajustes", Icons.Default.Settings)
)

@Composable
fun YomukaiApp(
    container: AppContainer,
    pendingDetails: Pair<String, String>? = null,
    onDeepLinkHandled: () -> Unit = {}
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingDetails) {
        pendingDetails?.let { (sourceId, mediaId) ->
            navController.navigate(Routes.details(sourceId, mediaId))
            onDeepLinkHandled()
        }
    }

    // Background update checks are on by default; ask once for notification permission (Android 13+).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && container.preferences.app.value.updatesEnabled &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val homeViewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    var episodeServers by remember { mutableStateOf<EpisodeServers?>(null) }

    fun openEpisode(source: AnimeSource, sourceId: String, chapterId: String) {
        scope.launch {
            val repo = container.libraryRepository
            val chapter = repo.getChapter(sourceId, chapterId)
            if (chapter == null) {
                snackbar.showSnackbar("Episodio no encontrado")
                return@launch
            }
            container.downloadManager.localVideo(sourceId, chapterId)?.let { local ->
                val mediaTitle = repo.getMedia(sourceId, chapter.mediaId)?.title
                repo.saveProgress(chapter, chapter.lastPageRead, chapter.totalPages, chapter.progress, markRead = false)
                navController.navigate(
                    Routes.animePlayer(
                        local.url, listOfNotNull(mediaTitle, chapter.title).joinToString(" - "), local.referer,
                        sourceId, chapter.mediaId, chapter.chapterId, local = if (local.isHls) "hls" else "file"
                    )
                )
                return@launch
            }
            val loading = launch { snackbar.showSnackbar("Buscando servidores de ${chapter.title}...") }
            val result = source.fetchVideoSources(chapter.url)
            loading.cancel()
            result
                .onSuccess { servers ->
                    repo.saveProgress(chapter, chapter.lastPageRead, chapter.totalPages, chapter.progress, markRead = false)
                    val mediaTitle = repo.getMedia(sourceId, chapter.mediaId)?.title
                    episodeServers = EpisodeServers(
                        listOfNotNull(mediaTitle, chapter.title).joinToString(" - "), servers, sourceId, chapter.mediaId, chapter.chapterId
                    )
                }
                .onFailure { snackbar.showSnackbar(it.message ?: "No se pudo cargar el episodio") }
        }
    }

    fun openReader(sourceId: String, mediaId: String, chapterId: String) {
        when (val source = container.sourceManager.get(sourceId)) {
            is AnimeSource -> openEpisode(source, sourceId, chapterId)
            is NovelSource -> navController.navigate(Routes.novelReader(sourceId, mediaId, chapterId))
            else -> navController.navigate(Routes.mangaReader(sourceId, mediaId, chapterId))
        }
    }

    fun openMedia(item: MediaItem) {
        scope.launch {
            container.libraryRepository.cacheMedia(item)
            navController.navigate(Routes.details(item.sourceId, item.id))
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val tabModifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())

        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    onMediaClick = ::openMedia,
                    onContinueClick = { openReader(it.sourceId, it.mediaId, it.chapterId) },
                    onBrowseSource = { navController.navigate(Routes.browse(it)) },
                    onSearchClick = { navController.navigateToTab(Routes.SEARCH) },
                    onOpenWeb = { navController.navigate(Routes.webCapture()) },
                    modifier = tabModifier,
                    viewModel = homeViewModel
                )
            }
            composable(
                Routes.SEARCH,
                arguments = listOf(navArgument(Routes.ARG_QUERY) { type = NavType.StringType; defaultValue = "" })
            ) {
                SearchScreen(
                    onMediaClick = ::openMedia,
                    onOpenUrl = { navController.navigate(Routes.webCapture(it)) },
                    modifier = tabModifier
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onMediaClick = ::openMedia,
                    onHistoryClick = { openReader(it.sourceId, it.mediaId, it.chapterId) },
                    onDownloadClick = { openReader(it.sourceId, it.mediaId, it.chapterId) },
                    modifier = tabModifier
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) }, modifier = tabModifier)
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.BROWSE, arguments = listOf(navArgument(Routes.ARG_SOURCE) { type = NavType.StringType })) {
                BrowseScreen(onBack = { navController.popBackStack() }, onMediaClick = ::openMedia)
            }
            composable(
                Routes.DETAILS,
                arguments = listOf(
                    navArgument(Routes.ARG_SOURCE) { type = NavType.StringType },
                    navArgument(Routes.ARG_MEDIA) { type = NavType.StringType }
                )
            ) {
                DetailsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { openReader(it.sourceId, it.mediaId, it.chapterId) }
                )
            }
            val readerArgs = listOf(
                navArgument(Routes.ARG_SOURCE) { type = NavType.StringType },
                navArgument(Routes.ARG_MEDIA) { type = NavType.StringType },
                navArgument(Routes.ARG_CHAPTER) { type = NavType.StringType }
            )
            composable(Routes.MANGA_READER, arguments = readerArgs) {
                MangaReaderScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.WEB_CAPTURE,
                arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" })
            ) { entry ->
                WebCaptureScreen(
                    initialUrl = entry.arguments?.getString("url").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onReadImages = { navController.navigate(Routes.QUICK_READER) },
                    onPlayVideo = { video -> navController.navigate(Routes.animePlayer(video.url, "Vídeo capturado", video.pageUrl)) }
                )
            }
            composable(Routes.QUICK_READER) {
                QuickReaderScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.NOVEL_READER, arguments = readerArgs) {
                NovelReaderScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.ANIME_PLAYER,
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "Anime" },
                    navArgument("referer") { type = NavType.StringType; defaultValue = "" },
                    navArgument("sourceId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("mediaId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("chapterId") { type = NavType.StringType; defaultValue = "" },
                    navArgument("server") { type = NavType.StringType; defaultValue = "" },
                    navArgument("local") { type = NavType.StringType; defaultValue = "" }
                )
            ) {
                AnimePlayerScreen(onBackClick = { navController.popBackStack() })
            }
        }
    }

    episodeServers?.let { state ->
        EpisodeServerDialog(
            state = state,
            onPlay = { server ->
                navController.navigate(
                    Routes.animePlayer(
                        server.videoUrl, state.title, server.headers["Referer"].orEmpty(),
                        state.sourceId, state.mediaId, state.chapterId, server.serverName
                    )
                )
            },
            onDismiss = { episodeServers = null }
        )
    }
}

private data class EpisodeServers(
    val title: String,
    val servers: List<VideoSource>,
    val sourceId: String = "",
    val mediaId: String = "",
    val chapterId: String = ""
)

/** Lets the user pick a server: direct streams play in-app, web embeds open in the browser. */
@Composable
private fun EpisodeServerDialog(
    state: EpisodeServers,
    onPlay: (VideoSource) -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn {
                items(state.servers) { server ->
                    ListItem(
                        headlineContent = { Text(server.serverName) },
                        supportingContent = {
                            Text(server.quality + if (server.isDirect) " • Reproducir en la app" else " • Abrir en el navegador")
                        },
                        leadingContent = {
                            Icon(if (server.isDirect) Icons.Default.PlayCircle else Icons.AutoMirrored.Filled.OpenInNew, null)
                        },
                        modifier = Modifier.clickable {
                            if (server.isDirect) onPlay(server) else runCatching { uriHandler.openUri(server.videoUrl) }
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(if (route == Routes.SEARCH) Routes.search() else route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
