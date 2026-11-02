package com.nelson.yomukai.data

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nelson.yomukai.YomukaiApplication
import com.nelson.yomukai.data.source.SourceDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real diagnostics of every source on a device, with its real network stack and WebView.
 * Needs internet, so it only runs when asked:
 * `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.live=true`
 * Results are printed to logcat under the tag YomukaiLive.
 */
@RunWith(AndroidJUnit4::class)
class LiveSourcesTest {
    @Test
    fun everySource() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val app = ApplicationProvider.getApplicationContext<YomukaiApplication>()
        for (source in app.container.sourceManager.all) {
            val start = System.currentTimeMillis()
            val steps = runCatching { SourceDiagnostics.run(source) }
                .getOrElse { listOf(com.nelson.yomukai.data.source.DiagnosticStep("Excepción", false, it.toString())) }
            val ms = System.currentTimeMillis() - start
            val ok = steps.isNotEmpty() && steps.all { it.ok }
            Log.i("YomukaiLive", "${if (ok) "OK  " else "FAIL"} ${source.id} ${ms}ms")
            steps.forEach { Log.i("YomukaiLive", "   ${if (it.ok) "+" else "-"} ${it.name}: ${it.detail.take(200)}") }
        }
    }
}
