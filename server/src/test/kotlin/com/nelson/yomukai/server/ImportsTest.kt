package com.nelson.yomukai.server

import com.google.gson.JsonParser
import com.nelson.yomukai.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class ImportsTest {
    /** Minimal protobuf writer, enough to build a Mihon backup. */
    private class Pb {
        val out = ByteArrayOutputStream()
        private fun varint(v: Long) {
            var x = v
            while (x and 0x7F.inv().toLong() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }
        fun bytes(field: Int, b: ByteArray) = apply { varint((field.toLong() shl 3) or 2); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun bool(field: Int, v: Boolean) = apply { varint((field.toLong() shl 3)); varint(if (v) 1 else 0) }
        fun float(field: Int, f: Float) = apply {
            varint((field.toLong() shl 3) or 5)
            val bits = java.lang.Float.floatToIntBits(f)
            for (k in 0 until 4) out.write((bits ushr (8 * k)) and 0xFF)
        }
        fun build(): ByteArray = out.toByteArray()
    }

    private fun chapter(n: Float, read: Boolean) = Pb().string(1, "/c/$n").string(2, "Cap $n").bool(4, read).float(9, n).build()

    @Test
    fun readsAMihonBackup() {
        val solo = Pb().string(2, "/m/1").string(3, "Solo Leveling").bytes(16, chapter(1f, true)).bytes(16, chapter(2f, true)).bytes(16, chapter(3f, false)).build()
        val notFav = Pb().string(3, "Ya no la sigo").bool(100, false).build()
        val backup = Pb().bytes(1, solo).bytes(1, notFav).build()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(backup) } }.toByteArray()
        val entries = Imports.fromMihon(ByteArrayInputStream(gz))
        assertEquals(listOf("Solo Leveling"), entries.map { it.title })
        assertEquals(2f, entries.single().progress)
    }

    @Test
    fun readsAnAniListEntry() {
        val o = JsonParser.parseString(
            """{"status":"PAUSED","progress":12,"media":{"format":"MANGA","countryOfOrigin":"KR",""" +
                """"title":{"romaji":"Na Honjaman Level Up","english":"Solo Leveling","native":"나 혼자만 레벨업"},"synonyms":["Only I Level Up"]}}"""
        ).asJsonObject
        val e = Imports.aniListEntry(o, anime = false)!!
        assertEquals(MediaType.MANHWA, e.type)
        assertEquals("ON_HOLD", e.status)
        assertEquals(12f, e.progress)
        assertTrue("Only I Level Up" in e.names && "Solo Leveling" in e.names)
    }

    private suspend fun HttpClient.login(name: String, password: String) {
        repeat(50) {
            val r = post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"name":"$name","password":"$password"}""") }
            if (r.status == HttpStatusCode.OK) return
            delay(100)
        }
        error("no se pudo entrar como $name")
    }

    @Test
    fun guestsCannotImportAndTheQueueIsPerUser() = testApplication {
        val c = Config.fromEnv(
            mapOf(
                "DATA_DIR" to Files.createTempDirectory("ykimp").toString(), "ADMIN_USER" to "admin", "ADMIN_PASSWORD" to "admin-password-1",
                "BACKUPS_ENABLED" to "false", "GUESTS_ENABLED" to "true"
            )
        )
        val s = Services(c)
        application { yomukai(c, s) }
        val guest = createClient { install(HttpCookies) }
        guest.post("/auth/guest")
        assertEquals(HttpStatusCode.Forbidden, guest.post("/api/import/anilist") { setBody("""{"user":"alguien"}""") }.status)
        val admin = createClient { install(HttpCookies) }.also { it.login("admin", "admin-password-1") }
        assertEquals(HttpStatusCode.BadRequest, admin.post("/api/import/mihon") { setBody("no es una copia") }.status)
        assertEquals(HttpStatusCode.BadRequest, admin.post("/api/import/anilist") { setBody("""{"user":"no válido!"}""") }.status)
        val adminId = s.db.tx { queryOne("SELECT id FROM users WHERE name = 'admin'") { getLong(1) } }!!
        s.imports.enqueue(adminId, "anilist", listOf(Imports.Entry("Frieren", emptyList(), MediaType.ANIME, "READING", 3f)))
        assertTrue(admin.get("/api/import/status").bodyAsText().contains("\"pending\":1"))
        // One user's queue has a cap, whatever they try.
        val many = (1..Imports.MAX_ENTRIES).map { Imports.Entry("Obra $it", emptyList(), MediaType.MANGA, "READING", 0f) }
        assertTrue(runCatching { s.imports.enqueue(adminId, "anilist", many) }.isFailure)
    }
}
