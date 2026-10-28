package com.nelson.yomukai.data.network

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nelson.yomukai.data.prefs.NetworkSettings
import com.nelson.yomukai.data.prefs.TorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Socket

enum class TorPhase { DISABLED, STARTING, CONNECTED, ERROR }

/** What the rest of the app needs to know about Tor right now. */
data class TorState(
    val phase: TorPhase = TorPhase.DISABLED,
    /** Bootstrap progress 0..100 (embedded Tor only). */
    val progress: Int = 0,
    val socksPort: Int = 0,
    /** HTTP CONNECT tunnel offered by embedded Tor; the WebView prefers it. */
    val httpPort: Int = 0,
    val mode: TorMode = TorMode.OFF,
    val error: String? = null
) {
    val isConnected get() = phase == TorPhase.CONNECTED && socksPort > 0
}

/**
 * Owns the Tor connection: starts the embedded daemon from `tor-android` (Guardian Project) or
 * probes an external Orbot, and publishes [state]. The daemon is only run while the user's
 * settings route something through Tor.
 */
class TorManager(
    private val context: Context,
    settings: StateFlow<NetworkSettings>,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(TorState())
    val state: StateFlow<TorState> = _state.asStateFlow()

    private var monitor: Job? = null
    private var service: TorService? = null
    private var bound = false
    private var statusReceiver: BroadcastReceiver? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    init {
        scope.launch {
            settings.map { s -> s.torMode.takeIf { s.torRoutesApp || s.torRoutesBrowser } ?: TorMode.OFF }
                .distinctUntilChanged()
                .collect { mode ->
                    try {
                        apply(mode)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Tor no pudo iniciarse; la app sigue funcionando sin él", e)
                        _state.value = TorState(TorPhase.ERROR, mode = mode, error = e.message ?: "Tor no pudo iniciarse")
                    }
                }
        }
    }

    private fun apply(mode: TorMode) {
        monitor?.cancel()
        when (mode) {
            TorMode.OFF -> {
                stopEmbedded()
                _state.value = TorState(mode = TorMode.OFF)
            }
            TorMode.BUILTIN -> {
                _state.value = TorState(TorPhase.STARTING, mode = mode)
                startEmbedded()
                monitor = scope.launch(Dispatchers.IO) { monitorEmbedded() }
            }
            TorMode.ORBOT -> {
                stopEmbedded()
                _state.value = TorState(TorPhase.STARTING, mode = mode)
                monitor = scope.launch(Dispatchers.IO) { monitorOrbot() }
            }
        }
    }

    private fun startEmbedded() {
        if (bound) return
        try {
            val intent = Intent(context, TorService::class.java)
            context.startService(intent)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            registerStatusReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar Tor", e)
            _state.value = TorState(TorPhase.ERROR, mode = TorMode.BUILTIN, error = e.message ?: "No se pudo iniciar Tor")
        }
    }

    private fun stopEmbedded() {
        statusReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        statusReceiver = null
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        service = null
        runCatching { context.stopService(Intent(context, TorService::class.java)) }
    }

    /** Errors reported by the daemon arrive as broadcasts; progress is read by polling the control port. */
    private fun registerStatusReceiver() {
        if (statusReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == TorService.ACTION_ERROR) {
                    _state.value = _state.value.copy(phase = TorPhase.ERROR, error = "Tor informó de un error")
                }
            }
        }
        val filter = IntentFilter(TorService.ACTION_ERROR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        statusReceiver = receiver
    }

    private suspend fun monitorEmbedded() {
        var waited = 0
        while (scope.isActive) {
            val tor = service
            if (tor != null) {
                val progress = runCatching { parseBootstrap(tor.getInfo("status/bootstrap-phase")) }.getOrNull()
                val socks = runCatching { tor.socksPort }.getOrDefault(0)
                val http = runCatching { tor.httpTunnelPort }.getOrDefault(0)
                if (progress != null) {
                    val connected = progress >= 100 && socks > 0
                    val previous = _state.value
                    if (previous.phase != TorPhase.ERROR || connected) {
                        _state.value = TorState(
                            phase = if (connected) TorPhase.CONNECTED else TorPhase.STARTING,
                            progress = progress,
                            socksPort = if (connected) socks else 0,
                            httpPort = if (connected) http else 0,
                            mode = TorMode.BUILTIN
                        )
                    }
                }
            }
            waited++
            if (waited > START_TIMEOUT_SECONDS && !_state.value.isConnected && _state.value.phase != TorPhase.ERROR) {
                _state.value = _state.value.copy(
                    phase = TorPhase.ERROR,
                    error = "Tor no logró conectar. La red puede bloquearlo; prueba con otra red u Orbot."
                )
            }
            delay(1_000)
        }
    }

    private suspend fun monitorOrbot() {
        while (scope.isActive) {
            val up = canConnect("127.0.0.1", ORBOT_SOCKS_PORT)
            _state.value = if (up) {
                TorState(TorPhase.CONNECTED, 100, ORBOT_SOCKS_PORT, 0, TorMode.ORBOT)
            } else {
                TorState(TorPhase.STARTING, 0, 0, 0, TorMode.ORBOT, "Orbot no responde en 127.0.0.1:$ORBOT_SOCKS_PORT; ábrelo y conéctalo")
            }
            delay(3_000)
        }
    }

    private fun canConnect(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 1_500) }
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val TAG = "YomukaiTor"
        const val ORBOT_SOCKS_PORT = 9050
        private const val START_TIMEOUT_SECONDS = 120
        private val PROGRESS = Regex("""PROGRESS=(\d+)""")

        /** `status/bootstrap-phase` looks like `NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY="Done"`. */
        fun parseBootstrap(info: String?): Int? = info?.let { PROGRESS.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }
}
