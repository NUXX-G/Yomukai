package com.nelson.yomukai.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.repository.PageData
import com.nelson.yomukai.ui.LocalAppContainer
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
}

object WebCapture {
    private val VIDEO = Regex("""(?i)\.(m3u8|mp4|webm)(\?|$)|/m3u8/|/hls/[^?]*\.m3u8""")
    private val NOISE = Regex("""(?i)\.(ts|m4s|aac|vtt)(\?|$)|googlevideo|doubleclick|/ads?/""")

    fun isVideoUrl(url: String) = VIDEO.containsMatchIn(url) && !NOISE.containsMatchIn(url)

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

    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.isEmpty() -> "https://duckduckgo.com/"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
    }
}

/**
 * Embedded browser that watches network requests for video streams and can extract the
 * images of the current page, so users can read or play content from sites without a
 * dedicated source.
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
    var address by remember { mutableStateOf(TextFieldValue(initialUrl)) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    val videos = remember { mutableStateListOf<CapturedVideo>() }
    val images = remember { mutableStateListOf<String>() }
    var showVideos by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val focusManager = LocalFocusManager.current

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

    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Scaffold(
        topBar = {
            Column {
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
                            keyboardActions = KeyboardActions(onGo = {
                                val url = WebCapture.normalizeUrl(address.text)
                                address = TextFieldValue(url)
                                focusManager.clearFocus()
                                webView?.loadUrl(url)
                            }),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Like a browser address bar: focusing selects everything so typing replaces it.
                                .onFocusChanged { focus ->
                                    if (focus.isFocused) address = address.copy(selection = TextRange(0, address.text.length))
                                }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cerrar") }
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Recargar") }
                    }
                )
                if (progress in 0.01f..0.99f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                            container.captureStore.chapter = CapturedChapter(
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
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = NetworkHelper.USER_AGENT
                        settings.setSupportMultipleWindows(false) // swallow pop-up ads
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                pageTitle = title.orEmpty()
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
                                if (url != null && url != currentUrl) {
                                    currentUrl = url
                                    address = TextFieldValue(url)
                                    videos.clear()
                                    images.clear()
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                scanPage()
                            }
                        }
                        loadUrl(WebCapture.normalizeUrl(initialUrl))
                        webView = this
                    }
                }
            )
        }
    }

    if (showVideos) {
        ModalBottomSheet(onDismissRequest = { showVideos = false }) {
            Text(
                "Vídeos detectados",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
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

