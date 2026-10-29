package com.nelson.yomukai.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.network.TorPhase
import com.nelson.yomukai.data.network.TorState
import com.nelson.yomukai.data.prefs.NetworkSettings
import com.nelson.yomukai.data.prefs.TorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.1.1 crashed on every launch once Tor had been enabled: the first client was built before
 * `domainsOfSources` was initialised (NullPointerException in NetworkHelper.buildClient).
 */
@RunWith(AndroidJUnit4::class)
class NetworkHelperStartupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun startWith(settings: NetworkSettings, tor: TorState) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val helper = NetworkHelper(context, MutableStateFlow(settings), MutableStateFlow(tor), scope)
            assertNotNull(helper.client)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun startsWithTorSavedOnWhileTorIsStillConnecting() =
        startWith(NetworkSettings(torMode = TorMode.BUILTIN, torRoutesApp = true), TorState(TorPhase.STARTING, mode = TorMode.BUILTIN))

    @Test
    fun startsWithTorAlreadyConnected() =
        startWith(
            NetworkSettings(torMode = TorMode.BUILTIN, torRoutesApp = true, torExcludedSources = setOf("animeav1")),
            TorState(TorPhase.CONNECTED, 100, socksPort = 9050, mode = TorMode.BUILTIN)
        )

    @Test
    fun startsWithOrbot() =
        startWith(NetworkSettings(torMode = TorMode.ORBOT, torRoutesApp = true), TorState(TorPhase.STARTING, mode = TorMode.ORBOT))
}
