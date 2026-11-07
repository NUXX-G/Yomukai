package com.nelson.yomukai.data.source

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.declarative.SourceConfigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceCatalogTest {
    private object NoNetwork : HttpFetcher {
        override suspend fun getString(url: String, headers: Map<String, String>, cloudflareFallback: Boolean) = error("no")
        override suspend fun postString(url: String, headers: Map<String, String>) = error("no")
        override suspend fun postJson(url: String, body: String) = error("no")
        override suspend fun checkImage(url: String, headers: Map<String, String>) = error("no")
    }

    private fun config(id: String) = SourceConfigs.parse(
        JsonParser.parseString(File("../sources/marmota.json").readText()).asJsonObject.apply { addProperty("id", id); addProperty("name", "Otra $id") }
    )

    @Test
    fun extraRepositoriesAddSourcesButNeverReplaceOfficialOnes() {
        val manager = SourceManager(NoNetwork)
        val rejected = manager.applyExtra(mapOf("fuentes.amigo.dev" to listOf(config("amigo_manga"), config("mangadex"), config("marmota"))))
        assertNotNull(manager.get("amigo_manga"))
        assertEquals("fuentes.amigo.dev", manager.origins["amigo_manga"])
        assertEquals("code and official ids stay official", listOf("mangadex (fuentes.amigo.dev)", "marmota (fuentes.amigo.dev)"), rejected)
        assertTrue(manager.get("mangadex") is com.nelson.yomukai.data.source.manga.MangaDexSource)
        // Removing the repository takes its sources away again.
        manager.applyExtra(emptyMap())
        assertEquals(null, manager.get("amigo_manga"))
    }

    @Test
    fun hiddenSourcesLeaveEveryListButStillOpen() {
        val manager = SourceManager(NoNetwork)
        manager.hidden = setOf("asura")
        assertFalse("asura" in manager.defaultEnabledIds)
        assertTrue(manager.enabled(setOf("asura", "mangadex")).none { it.id == "asura" })
        assertNotNull("a title already saved still opens", manager.get("asura"))
    }
}
