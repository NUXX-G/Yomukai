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
    /** Every filter of every source against its real site, and the genre exclusion on a catalogue. */
    @Test
    fun filtersAndExclusions() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val app = ApplicationProvider.getApplicationContext<YomukaiApplication>()
        val container = app.container
        for (source in container.sourceManager.all) {
            for (filter in source.filters) {
                val option = filter.options.lastOrNull { it.value.isNotEmpty() } ?: continue
                val found = runCatching { source.browse(1, mapOf(filter.key to setOf(option.value))).getOrThrow().size }
                Log.i("YomukaiLive", "${source.id} ${filter.key}=${option.value}: ${found.getOrElse { "ERROR $it" }}")
            }
            if (source.filters.isEmpty()) Log.i("YomukaiLive", "${source.id}: sin filtros")
        }
        val zona = container.sourceManager.require("zonatmo")
        val all = zona.browse(1, emptyMap()).getOrThrow()
        container.preferences.toggleExclusion("zonatmo", "Drama")
        val hidden = container.contentFilter.check(zona, all); val kept = container.contentFilter.known(zona, all)
        container.preferences.toggleExclusion("zonatmo", "Drama")
        Log.i("YomukaiLive", "exclusion Drama zonatmo: ${all.size} -> ${kept.size}")
        val names = container.discovery.aliases("Frieren")
        Log.i("YomukaiLive", "alias Frieren: ${names.take(4)}")
    }

    /** Downloads a chapter and an episode for real; the files can then be checked in Download/Yomukai. */
    @Test
    fun downloads() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val app = ApplicationProvider.getApplicationContext<YomukaiApplication>()
        val container = app.container
        for ((sourceId, index) in listOf("zonatmo" to 0, "animeflv" to 2)) {
            val source = container.sourceManager.require(sourceId)
            val item = source.fetchLatestUpdates(1).getOrThrow()[index]
            container.libraryRepository.cacheMedia(item)
            container.libraryRepository.refreshDetails(sourceId, item.id)
            container.libraryRepository.refreshChapters(sourceId, item.id)
            val media = container.libraryRepository.getMedia(sourceId, item.id)!!
            val chapter = container.libraryRepository.getChapters(sourceId, item.id).first()
            container.downloadManager.delete(sourceId, chapter.chapterId)
            container.downloadManager.enqueue(media, listOf(chapter))
            val start = System.currentTimeMillis()
            var row = container.database.downloadDao().get(sourceId, chapter.chapterId)
            while (row != null && row.status != com.nelson.yomukai.data.local.DownloadStatus.COMPLETED &&
                row.status != com.nelson.yomukai.data.local.DownloadStatus.FAILED && System.currentTimeMillis() - start < 200_000
            ) {
                kotlinx.coroutines.delay(1_000)
                row = container.database.downloadDao().get(sourceId, chapter.chapterId)
            }
            Log.i("YomukaiLive", "$sourceId ${chapter.title}: ${row?.status} ${row?.progress}/${row?.total} ${(System.currentTimeMillis() - start) / 1000}s ${row?.videoServer} ${row?.error?.take(120)}")
        }
    }

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
