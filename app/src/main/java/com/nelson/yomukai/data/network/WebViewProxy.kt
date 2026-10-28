package com.nelson.yomukai.data.network

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.nelson.yomukai.data.prefs.NetworkSettings
import com.nelson.yomukai.data.prefs.TorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What the WebView is currently allowed to do. */
data class BrowserNetwork(
    /** Pages may load now. False while Tor is still connecting (so nothing leaks) or the proxy could not be applied. */
    val ready: Boolean = true,
    /** The WebView traffic is going through Tor. */
    val viaTor: Boolean = false,
    val message: String? = null
)

/**
 * Keeps the process-wide WebView proxy (androidx.webkit [ProxyController]) in sync with the Tor
 * settings. The override covers the capture browser and the Cloudflare-solving WebView; hosts of
 * sources excluded from Tor are bypassed. Fail closed: with "browser through Tor" on, [state].ready
 * only becomes true once the override is confirmed.
 */
class WebViewProxy(
    private val context: Context,
    settings: StateFlow<NetworkSettings>,
    tor: StateFlow<TorState>,
    private val domainsOf: (Set<String>) -> Set<String>,
    scope: CoroutineScope
) {
    private val _state = MutableStateFlow(BrowserNetwork(ready = true))
    val state: StateFlow<BrowserNetwork> = _state.asStateFlow()

    init {
        scope.launch {
            combine(settings, tor.distinctUntilChangedBy { Triple(it.phase, it.socksPort, it.httpPort) }) { s, t -> s to t }
                .collect { (s, t) -> withContext(Dispatchers.Main) { apply(s, t) } }
        }
    }

    private suspend fun apply(settings: NetworkSettings, tor: TorState) {
        val wantTor = settings.torMode != TorMode.OFF && settings.torRoutesBrowser
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            _state.value = if (wantTor) {
                BrowserNetwork(false, false, "Este WebView no admite proxy, no se puede usar Tor en el navegador. Actualiza Android System WebView.")
            } else BrowserNetwork(true)
            return
        }
        if (!wantTor) {
            clear()
            _state.value = BrowserNetwork(ready = true)
            return
        }
        if (!tor.isConnected) {
            _state.value = BrowserNetwork(false, true, tor.error ?: "Conectando a Tor${if (tor.progress > 0) " (${tor.progress}%)" else ""}...")
            return
        }
        // Prefer Tor's HTTP CONNECT tunnel (remote DNS); otherwise SOCKS5.
        val rule = if (tor.httpPort > 0) "127.0.0.1:${tor.httpPort}" else "socks5://127.0.0.1:${tor.socksPort}"
        val bypass = domainsOf(settings.torExcludedSources).flatMap { listOf(it, "*.$it") } + "localhost" + "127.0.0.1"
        val applied = set(ProxyConfig.Builder().addProxyRule(rule).apply { bypass.forEach(::addBypassRule) }.build())
        _state.value = if (applied) BrowserNetwork(true, true) else BrowserNetwork(false, true, "No se pudo aplicar el proxy de Tor al navegador")
    }

    private suspend fun set(config: ProxyConfig): Boolean = suspendCancellableCoroutine { cont ->
        try {
            ProxyController.getInstance().setProxyOverride(config, ContextCompat.getMainExecutor(context)) {
                if (cont.isActive) cont.resume(true)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }

    private suspend fun clear() = suspendCancellableCoroutine { cont ->
        try {
            ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(context)) {
                if (cont.isActive) cont.resume(Unit)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(Unit)
        }
    }
}
