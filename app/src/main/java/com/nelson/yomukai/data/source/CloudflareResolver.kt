package com.nelson.yomukai.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nelson.yomukai.data.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Loads a page in an off-screen WebView so JavaScript challenges (Cloudflare) can run,
 * then returns the rendered HTML plus the cookies the page set.
 */
class CloudflareResolver(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveHtmlAndCookies(url: String): Pair<String, String>? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val webView = try {
                WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = NetworkHelper.USER_AGENT
                }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo crear el WebView", e)
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val handler = Handler(Looper.getMainLooper())
            var finished = false

            fun finish(result: Pair<String, String>?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
                if (continuation.isActive) continuation.resume(result)
            }

            fun captureHtml(pageUrl: String, isTimeout: Boolean) {
                if (finished) return
                webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                    val html = decodeJsString(raw)
                    if (NetworkHelper.isCloudflareChallenge(html) && !isTimeout) {
                        // The challenge reloads the page by itself once solved: look again shortly.
                        handler.postDelayed({ captureHtml(pageUrl, isTimeout = false) }, POLL_MS)
                        return@evaluateJavascript
                    }
                    val cookies = CookieManager.getInstance().getCookie(pageUrl).orEmpty()
                    Log.d(TAG, "WebView resolvió $pageUrl (${html.length} chars, cookies=${cookies.length})")
                    finish(html to cookies)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                // Without this, a WebView process killed for lack of memory takes the whole app with it.
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    finish(null)
                    return true
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    captureHtml(loadedUrl ?: url, isTimeout = false)
                }
            }

            continuation.invokeOnCancellation { handler.post { finish(null) } }
            handler.postDelayed({ captureHtml(url, isTimeout = true) }, TIMEOUT_MS)
            webView.loadUrl(url)
        }
    }

    /** evaluateJavascript returns a JSON-encoded string literal; decode it properly. */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
    }

    private companion object {
        const val TAG = "YomukaiScraper"
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 500L
    }
}
