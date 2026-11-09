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
    fun aSiteThatMovedIsNoticed() = runBlocking {
        val asura = sources.require("asura")
        val moved = Canary(store, Notifier(null, null), pauseMs = 0, landing = { "https://asura-nuevo.net/home" }, diagnose = { emptyList() })
        assertEquals("asura-nuevo.net", moved.movedTo(asura))
        val same = Canary(store, Notifier(null, null), pauseMs = 0, landing = { asura.baseUrl.replace("://", "://www.") + "/x" }, diagnose = { emptyList() })
        assertEquals(null, same.movedTo(asura))
    }

    /** A fake of the server's aliases: which host a source's requests go to now. */
    private class FakeMover(private val answers: (String) -> String?) : Canary.DomainMover {
        val aliases = mutableMapOf<String, String>()
        override suspend fun isMoved(host: String) = host in aliases
        override suspend fun move(from: String, to: String) { aliases[from] = to }
        override suspend fun undo(from: String) { aliases.remove(from) }
        override suspend fun visit(url: String) = answers(url)
    }

    @Test
    fun aSourceWhoseDomainDiedIsFoundAtItsNewOneOnlyIfItWorksThere() = runBlocking {
        val zona = sources.require("zonatmo")
        val ok = listOf(DiagnosticStep("Novedades", true, "20"), DiagnosticStep("Capítulos", true, "10"))
        val bad = listOf(DiagnosticStep("Novedades", false, "Sin respuesta"))
        // zonatmo.org answers nothing; zonatmo.to answers and the source works there; zonatmo.com is a parked page.
        val mover = FakeMover { url -> if ("zonatmo.to" in url) "https://zonatmo.to/" else if ("zonatmo.com" in url) "https://zonatmo.com/" else null }
        val canary = Canary(store, Notifier(null, null), pauseMs = 0, landing = { null }, domains = mover, diagnose = {
            if (mover.aliases["zonatmo.org"] == "zonatmo.to") ok else bad
        })
        val outcome = canary.check(zona)
        assertEquals(SourceHealth.OK, outcome.status)
        assertEquals(mapOf("zonatmo.org" to "zonatmo.to"), mover.aliases)

        // A site that redirects somewhere it does not work (a forum, a chat) is not moved.
        val asura = sources.require("asura")
        val chat = FakeMover { "https://discord.com/invite/x" }
        val redirecting = Canary(store, Notifier(null, null), pauseMs = 0, landing = { "https://discord.com/invite/x" }, domains = chat, diagnose = { bad })
        assertEquals(SourceHealth.DEGRADED, redirecting.check(asura).status)
        assertTrue(chat.aliases.isEmpty())
    }

    @Test
    fun brokenSourcesHideAndComeBackOnTheirOwn() = runBlocking {
        var steps = listOf(DiagnosticStep("Novedades", true, "20"), DiagnosticStep("Capítulos", false, "Sin capítulos"))
        val repairs = mutableListOf<String>()
        val canary = Canary(store, Notifier(null, null), pauseMs = 0, onBroken = { s, _ -> repairs += s.id }, diagnose = { steps })
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
        val canary = Canary(store, Notifier(null, null), pauseMs = 0, diagnose = { error("se colgó") })
        assertEquals(SourceHealth.DEGRADED, canary.check(sources.require("mangadex")).status)
    }
}
