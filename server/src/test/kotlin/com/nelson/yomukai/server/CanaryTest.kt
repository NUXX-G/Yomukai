package com.nelson.yomukai.server

import com.nelson.yomukai.data.source.DiagnosticStep
import com.nelson.yomukai.data.source.SourceHealth
import com.nelson.yomukai.data.source.SourceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CanaryTest {
    private val db = Database(File(Files.createTempDirectory("ykcan").toFile(), "t.db"))
    private val store = SourceHealthStore(db)
    private val sources = SourceManager(object : com.nelson.yomukai.data.network.HttpFetcher {
        override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean) = error("sin red")
        override suspend fun postString(url: String, headers: Map<String, String>) = error("sin red")
        override suspend fun postJson(url: String, body: String) = error("sin red")
        override suspend fun checkImage(url: String, headers: Map<String, String>) = error("sin red")
    })

    @Test
    fun brokenSourcesHideAndComeBackOnTheirOwn() = runBlocking {
        var steps = listOf(DiagnosticStep("Novedades", true, "20"), DiagnosticStep("Capítulos", false, "Sin capítulos"))
        val repairs = mutableListOf<String>()
        val canary = Canary(store, Notifier(null, null), pauseMs = 0, onBroken = { s, _ -> repairs += s.id }) { steps }
        val asura = sources.require("asura")
        assertTrue(asura in sources.enabled(null))

        val broken = canary.check(asura)
        assertEquals(SourceHealth.DEGRADED, broken.status)
        assertTrue(broken.changed)
        assertEquals(listOf("asura"), repairs)
        refreshDegraded(store, sources)
        assertFalse("hidden from every list", asura in sources.enabled(null))
        assertEquals("Capítulos: Sin capítulos", sources.degraded["asura"])
        val since = store.get("asura")!!.failingSince!!

        // Still broken: no new warning, no new repair, same start of the failure.
        assertFalse(canary.check(asura).changed)
        assertEquals(1, repairs.size)
        assertEquals(since, store.get("asura")!!.failingSince)

        // A failed search only warns: the source stays visible.
        steps = listOf(DiagnosticStep("Novedades", true, "20"), DiagnosticStep("Búsqueda", false, "Sin resultados", critical = false))
        val back = canary.check(asura)
        assertEquals(SourceHealth.WARNING, back.status)
        assertTrue(back.changed)
        refreshDegraded(store, sources)
        assertTrue(asura in sources.enabled(null))
        assertNull(store.get("asura")!!.failingSince)
    }

    @Test
    fun aCrashingCheckCountsAsBroken() = runBlocking {
        val canary = Canary(store, Notifier(null, null), pauseMs = 0) { error("se colgó") }
        assertEquals(SourceHealth.DEGRADED, canary.check(sources.require("mangadex")).status)
    }
}
