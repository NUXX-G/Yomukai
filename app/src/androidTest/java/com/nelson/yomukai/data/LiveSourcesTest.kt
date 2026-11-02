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
    /** Lists the servers of a few episodes of every anime source, and whether each stream answers. */
    @Test
    fun episodeServers() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val app = ApplicationProvider.getApplicationContext<YomukaiApplication>()
        for (source in app.container.sourceManager.all.filterIsInstance<com.nelson.yomukai.data.source.AnimeSource>()) {
            val titles = source.fetchLatestUpdates(1).getOrThrow().take(4)
            for (title in titles) {
                val episode = source.fetchChapterList(title.id).getOrNull()?.firstOrNull() ?: continue
                val servers = source.fetchVideoSources(episode.url).getOrElse { e ->
                    Log.i("YomukaiLive", "${source.id} ${title.title.take(24)}: $e")
                    continue
                }
                val direct = servers.filter { it.isDirect }.joinToString { d ->
                    val answer = runCatching {
                        val request = okhttp3.Request.Builder().url(d.videoUrl).header("Range", "bytes=0-1023")
                            .apply { d.headers.forEach { (k, v) -> header(k, v) } }.build()
                        app.container.network.videoClient.newCall(request).execute().use { it.code.toString() }
                    }.getOrElse { it::class.java.simpleName }
                    "${d.serverName}=$answer"
                }
                Log.i("YomukaiLive", "${source.id} ${title.title.take(24)}: total ${servers.size}; $direct")
            }
        }
    }

    @Test
    fun everySource() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val app = ApplicationProvider.getApplicationContext<YomukaiApplication>()
        for (source in app.container.sourceManager.all) {
            val start = System.currentTimeMillis()
            val steps = runCatching { SourceDiagnostics.run(source, app.container.network::checkImage) }
                .getOrElse { listOf(com.nelson.yomukai.data.source.DiagnosticStep("Excepción", false, it.toString())) }
            val ms = System.currentTimeMillis() - start
            val ok = steps.isNotEmpty() && steps.all { it.ok }
            Log.i("YomukaiLive", "${if (ok) "OK  " else "FAIL"} ${source.id} ${ms}ms")
            steps.forEach { Log.i("YomukaiLive", "   ${if (it.ok) "+" else "-"} ${it.name}: ${it.detail.take(200)}") }
        }
    }
}
