package com.nelson.yomukai.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.domain.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Last resort for a player page no extractor understands: load it in an invisible WebView, let its own JavaScript
 * start the player, and catch the first request for an HLS playlist or MP4 file. That address goes to ExoPlayer.
 * The page may only navigate within its own site (ads that redirect elsewhere are blocked) and cannot open windows.
 */
class EmbedSniffer(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(embed: VideoSource, timeoutMs: Long = 20_000): VideoSource? {
        val pageHost = Uri.parse(embed.videoUrl).host ?: return null
        if (!embed.videoUrl.startsWith("https://") && !embed.videoUrl.startsWith("http://")) return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = runCatching {
                    WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.userAgentString = NetworkHelper.USER_AGENT
                    }
                }.getOrElse {
                    Log.w(TAG, "Sin WebView", it)
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                val handler = Handler(Looper.getMainLooper())
                var done = false
                fun finish(result: VideoSource?) {
                    handler.post {
                        if (done) return@post
                        done = true
                        handler.removeCallbacksAndMessages(null)
                        runCatching { webView.stopLoading(); webView.destroy() }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (isStreamUrl(url)) {
                            val referer = request.requestHeaders["Referer"] ?: embed.videoUrl
                            finish(
                                embed.copy(
                                    videoUrl = url, isDirect = true,
                                    headers = mapOf("Referer" to referer, "User-Agent" to NetworkHelper.USER_AGENT),
                                    quality = embed.quality + if (".m3u8" in url) " • HLS" else " • MP4"
                                )
                            )
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !allowedNavigation(pageHost, request.url.host.orEmpty())

                    override fun onPageFinished(view: WebView, url: String) {
                        // Many players only ask for the stream once "play" is pressed: press it, muted.
                        view.evaluateJavascript(PRESS_PLAY, null)
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        finish(null)
                        return true
                    }
                }
                handler.postDelayed({ finish(null) }, timeoutMs)
                continuation.invokeOnCancellation { finish(null) }
                webView.loadUrl(embed.videoUrl, mapOf("Referer" to (embed.headers["Referer"] ?: "https://$pageHost/")))
            }
        }
    }

    companion object {
        private const val TAG = "EmbedSniffer"
        private val STREAM = Regex("""^https?://[^\s]+?\.(m3u8|mp4)(\?.*)?$""", RegexOption.IGNORE_CASE)
        private val NOT_VIDEO = listOf("/ads/", "doubleclick", "googlesyndication", "/vast", "preroll", "blank.mp4", "/thumb")

        /** A playlist or video file that is not an ad or a placeholder. */
        fun isStreamUrl(url: String): Boolean = STREAM.matches(url) && NOT_VIDEO.none { url.contains(it, ignoreCase = true) }

        /** The player may move within its own site (www, subdomains), never to another one. */
        fun allowedNavigation(pageHost: String, target: String): Boolean {
            fun site(h: String) = h.lowercase().removePrefix("www.").split('.').takeLast(2).joinToString(".")
            return target.isNotBlank() && site(pageHost) == site(target)
        }

        private val PRESS_PLAY = listOf(
            "(function(){try{",
            "document.querySelectorAll('video').forEach(function(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});});",
            "['.vjs-big-play-button','.jw-icon-display','.plyr__control--overlaid','.play-button','#play','.btn-play']",
            ".forEach(function(s){var b=document.querySelector(s);if(b)b.click();});",
            "}catch(e){}})();"
        ).joinToString("")
    }
}
