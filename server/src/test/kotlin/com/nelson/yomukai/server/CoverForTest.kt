package com.nelson.yomukai.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CoverForTest {
    private val s = Services(Config.fromEnv(mapOf("DATA_DIR" to Files.createTempDirectory("ykcover").toString(), "BACKUPS_ENABLED" to "false")))

    @Test
    fun onlyTheSourcesOwnSitesAndMetadataDatabasesAreProxied() {
        val own = s.sources.require("animeflv").baseUrl + "/wp-content/uploads/cover.jpg"
        assertTrue(s.coverFor(own, "animeflv").startsWith("/api/img?"))
        assertTrue(s.coverFor("https://s4.anilist.co/file/anilistcdn/media/manga/cover/large/x.jpg", "animeflv").startsWith("/api/img?"))
        // Anything else a client sends is not signed: the proxy cannot be used to fetch it.
        assertEquals("", s.coverFor("https://evil.example.com/x.jpg", "animeflv"))
        assertEquals("", s.coverFor("http://169.254.169.254/latest/meta-data", "animeflv"))
        assertEquals("/api/jellyfin/image/1", s.coverFor("/api/jellyfin/image/1", "jellyfin"))
    }
}
