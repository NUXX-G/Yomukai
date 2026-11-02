package com.nelson.yomukai.ui.web

import com.nelson.yomukai.ui.components.AppSpinner
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nelson.yomukai.data.network.BrowserNetwork
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.prefs.BrowserHome
import com.nelson.yomukai.data.prefs.BrowserPage
import com.nelson.yomukai.data.repository.PageData
import com.nelson.yomukai.ui.LocalAppContainer
import com.nelson.yomukai.ui.reader.ReaderWindowEffects
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONTokener

/** A media stream noticed while the page was loading. */
data class CapturedVideo(val url: String, val pageUrl: String) {
    val label: String get() = if ("m3u8" in url) "HLS (m3u8)" else "MP4"
}

/** Pages captured from a web page, handed to the quick reader. */
data class CapturedChapter(val title: String, val pages: List<PageData>)

/** In-memory hand-off between the capture screen and the quick reader. */
class CaptureStore {
    @Volatile
    var chapter: CapturedChapter? = null

    /** The open browser session, kept while the user visits the reader or the player. */
    var browserState: Bundle? = null
        private set
    var browserStartUrl: String = ""
        private set
    var browserUrl: String = ""
        private set
    var browserDesktop: Boolean = false
        private set

    fun saveBrowser(state: Bundle, startUrl: String, url: String, desktop: Boolean) {
        if (closing) {
            closing = false
            return
        }
        browserState = state
        browserStartUrl = startUrl
        browserUrl = url
        browserDesktop = desktop
    }

    private var closing = false

    /** The user closed the browser on purpose: the next one starts fresh. */
    fun clearBrowser() {
        closing = true
        browserState = null
        browserDesktop = false
    }
}

object WebCapture {
    private val VIDEO = Regex("""(?i)\.(m3u8|mp4|webm)(\?|$)|/m3u8/|/hls/[^?]*\.m3u8""")
    private val NOISE = Regex("""(?i)\.(ts|m4s|aac|vtt)(\?|$)|googlevideo|doubleclick|/ads?/""")

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    fun isVideoUrl(url: String) = VIDEO.containsMatchIn(url) && !NOISE.containsMatchIn(url)

    fun isHttpUrl(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** A .onion start page can only load through Tor; without it the browser would open on an error. */
    fun usableHome(home: BrowserHome, viaTor: Boolean): BrowserHome =
        if (home == BrowserHome.DDG_ONION && !viaTor) BrowserHome.DDG_HTML else home

    fun errorMessage(host: String?, reason: String?, viaTor: Boolean): String = buildString {
        append("No se pudo abrir ").append(host ?: "la página").append('.')
        if (host?.endsWith(".onion") == true && !viaTor) append(" Las direcciones .onion necesitan Tor (Ajustes › Tor).")
        if (!reason.isNullOrBlank()) append("\n\n").append(reason)
    }

    /** Lists every image (DOM order, honoring lazy-load attributes) with its size, plus <video> sources. */
    const val IMAGES_JS = """
        (function() {
          var out = [];
          document.querySelectorAll('img').forEach(function(img) {
            var src = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.currentSrc || img.src;
            if (!src || src.indexOf('data:') === 0) return;
            var w = img.naturalWidth || img.width, h = img.naturalHeight || img.height;
            out.push(w + 'x' + h + ' ' + new URL(src, location.href).href);
          });
          document.querySelectorAll('video[src], video source[src]').forEach(function(v) {
            out.push('video:' + new URL(v.getAttribute('src'), location.href).href);
          });
          return JSON.stringify(out);
        })()
    """

    /**
     * Chapter pages are usually stored together: find the folder holding most of the big
     * (already loaded) images and keep every image from it, including lazy ones not yet loaded.
     */
    fun selectPages(entries: List<Pair<String, Pair<Int, Int>>>): List<String> {
        fun folder(url: String) = url.substringBefore('?').substringBeforeLast('/')
        val big = entries.filter { (_, size) -> (size.first >= 300 && size.second >= 300) || size.second >= 800 }
        val dominant = big.groupingBy { folder(it.first) }.eachCount().maxByOrNull { it.value }?.key
            ?: return big.map { it.first }.distinct()
        return entries.map { it.first }.filter { folder(it) == dominant }.distinct()
    }

    fun parseEntry(raw: String): Pair<String, Pair<Int, Int>>? {
        val size = raw.substringBefore(' ')
        val url = raw.substringAfter(' ', "").ifBlank { return null }
        val w = size.substringBefore('x').toIntOrNull() ?: 0
        val h = size.substringAfter('x').toIntOrNull() ?: 0
        return url to (w to h)
    }

    fun normalizeUrl(input: String, home: BrowserHome = BrowserHome.DDG_ONION): String {
        val trimmed = input.trim()
        return when {
            trimmed.isEmpty() -> home.url
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> home.searchUrl + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
    }
}

/**
 * Embedded browser that watches network requests for video streams and can extract the
 * images of the current page, so users can read or play content from sites without a
 * dedicated source. The session survives a trip to the reader or the player and ends when the
 * user closes the browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebCaptureScreen(
    initialUrl: String,
    onBack: () -> Unit,
    onReadImages: () -> Unit,
    onPlayVideo: (CapturedVideo) -> Unit
) {
    val container = LocalAppContainer.current
    val store = container.captureStore
    val context = LocalContext.current
    val netState by container.webViewProxy.state.collectAsStateWithLifecycle()
    val networkSettings by container.preferences.network.collectAsStateWithLifecycle()
    val favorites by container.preferences.browserFavorites.collectAsStateWithLifecycle()
    val home = WebCapture.usableHome(networkSettings.browserHome, viaTor = netState.viaTor)
    val resuming = remember { store.browserState != null && store.browserStartUrl == initialUrl }
    var started by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(TextFieldValue(if (resuming) store.browserUrl else initialUrl)) }
    var currentUrl by remember { mutableStateOf(if (resuming) store.browserUrl else initialUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var desktopMode by remember { mutableStateOf(store.browserDesktop) }
    var menuOpen by remember { mutableStateOf(false) }
    /** Like F11: only the page on screen, without the browser's bars or the system's. */
    var immersive by remember { mutableStateOf(false) }
    var addressFocused by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findResult by remember { mutableStateOf(0 to 0) }
    var fullscreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }
    val videos = remember { mutableStateListOf<CapturedVideo>() }
    val images = remember { mutableStateListOf<String>() }
    var showVideos by remember { mutableStateOf(false) }
    /** Link waiting for the user's decision to hand it to another app. */
    var leaving by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val isFavorite = favorites.any { it.url == currentUrl }
    val isLoading = progress in 0.01f..0.99f

    fun scanPage() {
        webView?.evaluateJavascript(WebCapture.IMAGES_JS) { raw ->
            val json = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
            val found = runCatching { JSONArray(json) }.getOrNull() ?: return@evaluateJavascript
            val entries = mutableListOf<Pair<String, Pair<Int, Int>>>()
            for (i in 0 until found.length()) {
                val item = found.getString(i)
                if (item.startsWith("video:")) {
                    val video = CapturedVideo(item.removePrefix("video:"), currentUrl)
                    if (videos.none { it.url == video.url } && video.url.startsWith("http")) videos += video
                } else {
                    WebCapture.parseEntry(item)?.let { entries += it }
                }
            }
            images.clear()
            images.addAll(WebCapture.selectPages(entries))
        }
    }

    fun refreshHistoryButtons(view: WebView?) {
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    fun open(url: String) {
        pageError = null
        address = TextFieldValue(url)
        // Focus goes to the page: left alone it lands on a toolbar button, and the Enter key would press it.
        webView?.requestFocus()
        keyboard?.hide()
        if (netState.ready) webView?.loadUrl(url)
    }

    fun close() {
        store.clearBrowser()
        onBack()
    }

    fun applyUserAgent(view: WebView, desktop: Boolean) {
        view.settings.userAgentString = if (desktop) WebCapture.DESKTOP_USER_AGENT else NetworkHelper.USER_AGENT
    }

    fun search(query: String) {
        findQuery = query
        if (query.isBlank()) webView?.clearMatches() else webView?.findAllAsync(query)
    }

    fun closeFind() {
        findOpen = false
        findQuery = ""
        findResult = 0 to 0
        webView?.clearMatches()
    }

    fun openOutside(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "Ninguna app puede abrir este enlace", Toast.LENGTH_SHORT).show() }
    }

    // Selected a moment later: the tap that gave focus places the cursor right after this event.
    LaunchedEffect(addressFocused) {
        if (addressFocused) {
            delay(80)
            address = address.copy(selection = TextRange(0, address.text.length))
        }
    }

    // Fail closed: with "browser through Tor" on, nothing is loaded until the proxy is confirmed.
    LaunchedEffect(netState.ready, webView) {
        val view = webView ?: return@LaunchedEffect
        if (netState.ready && !started) {
            started = true
            val saved = store.browserState?.takeIf { resuming }
            if (saved != null) {
                view.restoreState(saved)
            } else {
                val url = WebCapture.normalizeUrl(initialUrl, home)
                address = TextFieldValue(url)
                view.loadUrl(url)
            }
        }
    }

    // Stricter while on Tor: no plain http inside https pages and no third-party cookies.
    LaunchedEffect(netState.viaTor, webView) {
        val view = webView ?: return@LaunchedEffect
        view.settings.mixedContentMode =
            if (netState.viaTor) WebSettings.MIXED_CONTENT_NEVER_ALLOW else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, !netState.viaTor)
    }

    ReaderWindowEffects(brightness = -1f, keepScreenOn = false, immersive = immersive || fullscreen != null)

    BackHandler {
        when {
            fullscreen != null -> fullscreen?.second?.onCustomViewHidden()
            immersive -> immersive = false
            findOpen -> closeFind()
            canGoBack -> if (netState.ready) webView?.goBack()
            else -> close()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                store.saveBrowser(Bundle().also(view::saveState), initialUrl, currentUrl, desktopMode)
                view.destroy()
            }
        }
    }

    Scaffold(
        topBar = {
            if (!immersive) Column {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            singleLine = true,
                            placeholder = { Text("Pega un enlace o busca...") },
                            trailingIcon = {
                                if (address.text.isNotEmpty()) {
                                    IconButton(onClick = { address = TextFieldValue("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Borrar dirección")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { open(WebCapture.normalizeUrl(address.text, home)) }),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Like a browser address bar: focusing selects everything so typing replaces it.
                                .onFocusChanged { focus -> addressFocused = focus.isFocused }
                                // A physical keyboard's Enter would otherwise move focus to a toolbar button.
                                .onPreviewKeyEvent { event ->
                                    val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                                    if (isEnter && event.type == KeyEventType.KeyUp) open(WebCapture.normalizeUrl(address.text, home))
                                    isEnter
                                }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = ::close) { Icon(Icons.Default.Close, contentDescription = "Cerrar el navegador") }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (isLoading) webView?.stopLoading() else if (netState.ready) { pageError = null; webView?.reload() }
                        }) {
                            Icon(
                                if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (isLoading) "Detener" else "Recargar"
                            )
                        }
                    }
                )
                TorBadge(netState)
                if (isLoading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                if (findOpen) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = ::search,
                            singleLine = true,
                            placeholder = { Text("Buscar en la página") },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (findResult.second == 0) "0" else "${findResult.first + 1}/${findResult.second}",
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(onClick = { webView?.findNext(false) }, enabled = findResult.second > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, "Coincidencia anterior")
                        }
                        IconButton(onClick = { webView?.findNext(true) }, enabled = findResult.second > 0) {
                            Icon(Icons.Default.KeyboardArrowDown, "Coincidencia siguiente")
                        }
                        IconButton(onClick = ::closeFind) { Icon(Icons.Default.Close, "Cerrar búsqueda") }
                    }
                }
            }
        },
        bottomBar = {
            if (!immersive) Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { pageError = null; webView?.goBack() }, enabled = canGoBack && netState.ready) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Página anterior")
                        }
                        IconButton(onClick = { pageError = null; webView?.goForward() }, enabled = canGoForward && netState.ready) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Página siguiente")
                        }
                        IconButton(onClick = { open(home.url) }) { Icon(Icons.Default.Home, "Página de inicio") }
                        IconButton(onClick = { container.preferences.toggleBrowserFavorite(currentUrl, pageTitle) }, enabled = currentUrl.isNotBlank()) {
                            Icon(
                                if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                if (isFavorite) "Quitar de favoritos" else "Añadir a favoritos"
                            )
                        }
                        IconButton(onClick = { showPages = true }) { Icon(Icons.Default.History, "Favoritos e historial") }
                        IconButton(onClick = { immersive = true }) { Icon(Icons.Default.Fullscreen, "Pantalla completa") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Buscar en la página") },
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    onClick = { menuOpen = false; findOpen = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Versión de escritorio") },
                                    leadingIcon = { Icon(Icons.Default.Computer, null) },
                                    trailingIcon = { if (desktopMode) Icon(Icons.Default.Check, null) },
                                    onClick = {
                                        menuOpen = false
                                        desktopMode = !desktopMode
                                        webView?.let { applyUserAgent(it, desktopMode); if (netState.ready) it.reload() }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Copiar enlace") },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                    onClick = {
                                        menuOpen = false
                                        context.getSystemService(ClipboardManager::class.java)
                                            ?.setPrimaryClip(ClipData.newPlainText("Enlace", currentUrl))
                                        Toast.makeText(context, "Enlace copiado", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Compartir") },
                                    leadingIcon = { Icon(Icons.Default.Share, null) },
                                    onClick = {
                                        menuOpen = false
                                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, currentUrl)
                                        runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Abrir en otra app") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                    onClick = { menuOpen = false; leaving = currentUrl }
                                )
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showVideos = true }, enabled = videos.isNotEmpty()) {
                            Icon(Icons.Default.Movie, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Vídeos (${videos.size})")
                        }
                        OutlinedButton(onClick = ::scanPage) {
                            Icon(Icons.Default.Image, null)
                            Spacer(Modifier.width(6.dp))
                            Text("${images.size}")
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                store.chapter = CapturedChapter(
                                    title = pageTitle.ifBlank { currentUrl },
                                    pages = images.map { PageData(it, mapOf("Referer" to currentUrl)) }
                                )
                                onReadImages()
                            },
                            enabled = images.size >= 2
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Leer")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        defaultFocusHighlightEnabled = false // it would grey out the whole page
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.setSupportMultipleWindows(false) // swallow pop-up ads
                        applyUserAgent(this, desktopMode)
                        setFindListener { active, total, done -> if (done) findResult = active to total }
                        setDownloadListener { url, _, _, _, _ ->
                            if (WebCapture.isHttpUrl(url)) leaving = url
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                pageTitle = title.orEmpty()
                            }

                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                if (view != null && callback != null) fullscreen = view to callback
                            }

                            override fun onHideCustomView() {
                                fullscreen = null
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                // Only follow web links; block intent:// and app-store redirects used by ads.
                                val scheme = request?.url?.scheme ?: return true
                                return scheme != "http" && scheme != "https"
                            }

                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val url = request?.url?.toString().orEmpty()
                                if (WebCapture.isVideoUrl(url)) {
                                    val page = currentUrl
                                    view?.post {
                                        if (videos.none { it.url == url }) videos += CapturedVideo(url, page)
                                    }
                                }
                                return null // never modify traffic, only observe it
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                pageError = null
                                if (url != null && url != currentUrl) {
                                    currentUrl = url
                                    address = TextFieldValue(url)
                                    videos.clear()
                                    images.clear()
                                }
                                refreshHistoryButtons(view)
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                refreshHistoryButtons(view)
                                // Single-page sites change address without loading a new page.
                                if (url != null && url != currentUrl) {
                                    currentUrl = url
                                    address = TextFieldValue(url)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                refreshHistoryButtons(view)
                                scanPage()
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true) {
                                    pageError = WebCapture.errorMessage(request.url?.host, error?.description?.toString(), netState.viaTor)
                                }
                            }

                            // Without this, a page process killed for lack of memory takes the whole app with it.
                            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                webView = null
                                store.clearBrowser()
                                onBack()
                                return true
                            }

                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.cancel()
                                pageError = "La conexión con este sitio no es segura y se ha bloqueado."
                            }
                        }
                        webView = this
                    }
                }
            )
            if (immersive) {
                IconButton(
                    onClick = { immersive = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) { Icon(Icons.Default.FullscreenExit, "Salir de pantalla completa", tint = Color.White) }
            }
            pageError?.let { message ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { pageError = null; if (netState.ready) webView?.reload() }) { Text("Reintentar") }
                }
            }
            if (!netState.ready) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (netState.viaTor) AppSpinner()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        netState.message ?: "Esperando a la red...",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "El navegador no carga páginas hasta que Tor esté listo, para no exponer tu conexión.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    fullscreen?.let { (view, _) ->
        AndroidView(
            factory = { view.apply { (parent as? ViewGroup)?.removeView(this) } },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    }

    leaving?.let { url ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("¿Abrir en otra app?") },
            text = {
                Text(
                    url + if (netState.viaTor) "\n\nEsa app no usa Tor: la conexión saldrá sin protección." else "",
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = { TextButton(onClick = { leaving = null; openOutside(url) }) { Text("Abrir") } },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Cancelar") } }
        )
    }

    if (showPages) {
        val session = remember {
            val list = webView?.copyBackForwardList()
            (0 until (list?.size ?: 0)).mapNotNull { list?.getItemAtIndex(it) }.reversed().map { BrowserPage(it.url, it.title.orEmpty()) }
        }
        ModalBottomSheet(onDismissRequest = { showPages = false }) {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                item { SheetTitle("Favoritos") }
                if (favorites.isEmpty()) {
                    item {
                        Text(
                            "Toca la estrella para guardar una página.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }
                items(favorites, key = { "f:" + it.url }) { page ->
                    BrowserPageRow(page, onClick = { showPages = false; open(page.url) }) {
                        IconButton(onClick = { container.preferences.toggleBrowserFavorite(page.url, page.title) }) {
                            Icon(Icons.Default.Delete, "Quitar de favoritos")
                        }
                    }
                }
                item { SheetTitle("Esta sesión") }
                items(session) { page -> BrowserPageRow(page, onClick = { showPages = false; open(page.url) }) }
            }
        }
    }

    if (showVideos) {
        ModalBottomSheet(onDismissRequest = { showVideos = false }) {
            SheetTitle("Vídeos detectados")
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(videos) { video ->
                    ListItem(
                        headlineContent = { Text(video.label) },
                        supportingContent = { Text(video.url, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Default.PlayCircle, null) },
                        modifier = Modifier.clickable { showVideos = false; onPlayVideo(video) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

@Composable
private fun BrowserPageRow(page: BrowserPage, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(page.title.ifBlank { page.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(page.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** Always-visible indicator of how this browser reaches the internet. */
@Composable
private fun TorBadge(net: BrowserNetwork) {
    val (label, color) = when {
        net.viaTor && net.ready -> "Conectado a Tor" to Color(0xFF2E7D32)
        net.viaTor -> (net.message ?: "Conectando a Tor...") to Color(0xFFF9A825)
        !net.ready -> (net.message ?: "Navegador no disponible") to MaterialTheme.colorScheme.error
        else -> "Sin Tor (conexión directa)" to MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Security, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
